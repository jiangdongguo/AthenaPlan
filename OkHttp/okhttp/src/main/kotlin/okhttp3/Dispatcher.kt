/*
 * Copyright (C) 2013 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3

import java.util.ArrayDeque
import java.util.Collections
import java.util.Deque
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import okhttp3.internal.assertThreadDoesntHoldLock
import okhttp3.internal.connection.RealCall
import okhttp3.internal.connection.RealCall.AsyncCall
import okhttp3.internal.okHttpName
import okhttp3.internal.threadFactory

/**
 * Policy on when async requests are executed.
 *
 * Each dispatcher uses an [ExecutorService] to run calls internally. If you supply your own
 * executor, it should be able to run [the configured maximum][maxRequests] number of calls
 * concurrently.
 */
class Dispatcher constructor() {
  /**
   * The maximum number of requests to execute concurrently. Above this requests queue in memory,
   * waiting for the running calls to complete.
   *
   * If more than [maxRequests] requests are in flight when this is invoked, those requests will
   * remain in flight.
   */
  @get:Synchronized var maxRequests = 64
    set(maxRequests) {
      require(maxRequests >= 1) { "max < 1: $maxRequests" }
      synchronized(this) {
        field = maxRequests
      }
      promoteAndExecute()
    }

  /**
   * The maximum number of requests for each host to execute concurrently. This limits requests by
   * the URL's host name. Note that concurrent requests to a single IP address may still exceed this
   * limit: multiple hostnames may share an IP address or be routed through the same HTTP proxy.
   *
   * If more than [maxRequestsPerHost] requests are in flight when this is invoked, those requests
   * will remain in flight.
   *
   * WebSocket connections to hosts **do not** count against this limit.
   */
  @get:Synchronized var maxRequestsPerHost = 5
    set(maxRequestsPerHost) {
      require(maxRequestsPerHost >= 1) { "max < 1: $maxRequestsPerHost" }
      synchronized(this) {
        field = maxRequestsPerHost
      }
      promoteAndExecute()
    }

  /**
   * A callback to be invoked each time the dispatcher becomes idle (when the number of running
   * calls returns to zero).
   *
   * Note: The time at which a [call][Call] is considered idle is different depending on whether it
   * was run [asynchronously][Call.enqueue] or [synchronously][Call.execute]. Asynchronous calls
   * become idle after the [onResponse][Callback.onResponse] or [onFailure][Callback.onFailure]
   * callback has returned. Synchronous calls become idle once [execute()][Call.execute] returns.
   * This means that if you are doing synchronous calls the network layer will not truly be idle
   * until every returned [Response] has been closed.
   */
  @set:Synchronized
  @get:Synchronized
  var idleCallback: Runnable? = null

  private var executorServiceOrNull: ExecutorService? = null

  @get:Synchronized
  @get:JvmName("executorService") val executorService: ExecutorService
    get() {
      if (executorServiceOrNull == null) {
        executorServiceOrNull = ThreadPoolExecutor(0, Int.MAX_VALUE, 60, TimeUnit.SECONDS,
            SynchronousQueue(), threadFactory("$okHttpName Dispatcher", false))
      }
      return executorServiceOrNull!!
    }

  /** Ready async calls in the order they'll be run. */
  // 异步等待队列
  private val readyAsyncCalls = ArrayDeque<AsyncCall>()

  /** Running asynchronous calls. Includes canceled calls that haven't finished yet. */
  // 异步执行队列
  private val runningAsyncCalls = ArrayDeque<AsyncCall>()

  /** Running synchronous calls. Includes canceled calls that haven't finished yet. */
  // 同步执行队列
  // ArrayDeque即可作为Stack，也可作为Queue，它是线程非安全的
  private val runningSyncCalls = ArrayDeque<RealCall>()

  constructor(executorService: ExecutorService) : this() {
    this.executorServiceOrNull = executorService
  }

  internal fun enqueue(call: AsyncCall) {
    synchronized(this) {
      // 3-2-(2)-a 首先，将异步任务AsyncCall添加到等待队列中
      readyAsyncCalls.add(call)
      if (!call.call.forWebSocket) {
        val existingCall = findExistingCallWithHost(call.host)
        if (existingCall != null) call.reuseCallsPerHostFrom(existingCall)
      }
    }
    // 3-2-(2)-a 然后，从等待队列取任务插入到执行队列中
    // 再在线程池中执行新任务
    promoteAndExecute()
  }

  private fun findExistingCallWithHost(host: String): AsyncCall? {
    for (existingCall in runningAsyncCalls) {
      if (existingCall.host == host) return existingCall
    }
    for (existingCall in readyAsyncCalls) {
      if (existingCall.host == host) return existingCall
    }
    return null
  }

  /**
   * Cancel all calls currently enqueued or executing. Includes calls executed both
   * [synchronously][Call.execute] and [asynchronously][Call.enqueue].
   */
  @Synchronized fun cancelAll() {
    for (call in readyAsyncCalls) {
      call.call.cancel()
    }
    for (call in runningAsyncCalls) {
      call.call.cancel()
    }
    for (call in runningSyncCalls) {
      call.cancel()
    }
  }

  /**
   * Promotes eligible calls from [readyAsyncCalls] to [runningAsyncCalls] and runs them on the
   * executor service. Must not be called with synchronization because executing calls can call
   * into user code.
   *
   * @return true if the dispatcher is currently running calls.
   */
  private fun promoteAndExecute(): Boolean {
    this.assertThreadDoesntHoldLock()

    val executableCalls = mutableListOf<AsyncCall>()
    val isRunning: Boolean
    // b-1 遍历等待队列，决定是否将其添加到可执行队列中（线程安全）
    // - 如果执行队列中正在执行的任务数量>=maxRequests，拒绝添加任何新任务到执行队列；
    // - 如果当前Host的请求数超过maxRequestsPerHost，拒绝本次新任务到执行队列；
    // - 否则，将等待队列中的任务添加到执行队列，并从等待队列删除任务
    synchronized(this) {
      val i = readyAsyncCalls.iterator()
      while (i.hasNext()) {
        val asyncCall = i.next()
        // 最大并发量判断，maxRequests=64
        if (runningAsyncCalls.size >= this.maxRequests) break // Max capacity.
        // Host允许最大请求数判断，maxRequestsPerHost = 5
        if (asyncCall.callsPerHost.get() >= this.maxRequestsPerHost) continue // Host max capacity.
        // 从等待队列移除
        i.remove()
        asyncCall.callsPerHost.incrementAndGet()
        // 将新任务添加到executableCalls列表中
        executableCalls.add(asyncCall)
        // 将其添加到执行队列中
        runningAsyncCalls.add(asyncCall)
      }
      isRunning = runningCallsCount() > 0
    }
    // b-2 遍历executableCalls列表
    // 依次将要执行的新任务放在线程池中执行，调用asyncCall.executeOn实现
    // （1）将新任务插入到线程池执行， executorService.execute(this)；
    // （2）执行异常或执行完毕均尝试从等待队列中取新任务任务， client.dispatcher.finished(this)；
    // 注：具体的任务执行流程，请看AsyncCall.run()方法
    for (i in 0 until executableCalls.size) {
      val asyncCall = executableCalls[i]
      asyncCall.executeOn(executorService)
    }

    return isRunning
  }

  /** Used by [Call.execute] to signal it is in-flight. */
  @Synchronized internal fun executed(call: RealCall) {
    // 将Call任务添加到同步执行队列中ArrayDeque
    runningSyncCalls.add(call)
  }

  /** Used by [AsyncCall.run] to signal completion. */
  internal fun finished(call: AsyncCall) {
    call.callsPerHost.decrementAndGet()
    finished(runningAsyncCalls, call)
  }

  /** Used by [Call.execute] to signal completion. */
  internal fun finished(call: RealCall) {
    finished(runningSyncCalls, call)
  }

  private fun <T> finished(calls: Deque<T>, call: T) {
    val idleCallback: Runnable?
    synchronized(this) {
      // a. 从对应队列中移除任务RealCall(同步任务)、AsyncCall(异步任务，Runnable)
      if (!calls.remove(call)) throw AssertionError("Call wasn't in-flight!")
      idleCallback = this.idleCallback
    }

    //  b. 对于异步请求来说，遍历等待队列
    //  从等待队列中取新的任务，插入到执行队列中
    val isRunning = promoteAndExecute()

    if (!isRunning && idleCallback != null) {
      idleCallback.run()
    }
  }

  /** Returns a snapshot of the calls currently awaiting execution. */
  @Synchronized fun queuedCalls(): List<Call> {
    return Collections.unmodifiableList(readyAsyncCalls.map { it.call })
  }

  /** Returns a snapshot of the calls currently being executed. */
  @Synchronized fun runningCalls(): List<Call> {
    return Collections.unmodifiableList(runningSyncCalls + runningAsyncCalls.map { it.call })
  }

  @Synchronized fun queuedCallsCount(): Int = readyAsyncCalls.size

  @Synchronized fun runningCallsCount(): Int = runningAsyncCalls.size + runningSyncCalls.size

  @JvmName("-deprecated_executorService")
  @Deprecated(
      message = "moved to val",
      replaceWith = ReplaceWith(expression = "executorService"),
      level = DeprecationLevel.ERROR)
  fun executorService(): ExecutorService = executorService
}
