/*
 * Copyright (C) 2016 Jake Wharton
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
package retrofit2.adapter.rxjava2;

import io.reactivex.Observable;
import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;
import io.reactivex.exceptions.CompositeException;
import io.reactivex.exceptions.Exceptions;
import io.reactivex.plugins.RxJavaPlugins;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** 发起异步网络请求，将响应转换为RxJava发射出去
 *
 * @param <T>
 */
final class CallEnqueueObservable<T> extends Observable<Response<T>> {
  private final Call<T> originalCall;

  CallEnqueueObservable(Call<T> originalCall) {
    this.originalCall = originalCall;
  }

  @Override
  protected void subscribeActual(Observer<? super Response<T>> observer) {
    // Since Call is a one-shot type, clone it for each new observer.
    // 3-1-1 拷贝Call，便于每个Observer对应一个单独Call
    Call<T> call = originalCall.clone();
    // 3-1-2 实例化一个CallCallback对象，继承于Disposable和Callback
    // 该对象完成将响应结果转换成RxJava的回调（代理模式CallCallback代理originalCall）
    CallCallback<T> callback = new CallCallback<>(call, observer);
    // 3-1-4 调用observer的onSubscribe
    // 这个callback实际上是一个Disposable，便于调用者终止网络请求
    observer.onSubscribe(callback);
    // 3-1-4 执行异步网络请求
    // 最终还是调用Call.enqueue方法
    if (!callback.isDisposed()) {
      call.enqueue(callback);
    }
  }

  private static final class CallCallback<T> implements Disposable, Callback<T> {
    private final Call<?> call;
    private final Observer<? super Response<T>> observer;
    private volatile boolean disposed;
    boolean terminated = false;

    /** CallCallback构造方法，它是原始Call的代理
     *
     * @param call 原始Call
     * @param observer Observer对象
     */
    CallCallback(Call<?> call, Observer<? super Response<T>> observer) {
      this.call = call;
      this.observer = observer;
    }

    /** 网络响应结果回调方法
     *
     * @param call 发起网络请求的Call对象
     * @param response 响应结果
     */
    @Override
    public void onResponse(Call<T> call, Response<T> response) {
      // （1）"水管"被切断，终止向外发射数据
      if (disposed) return;

      try {
        //（2）调用Observer的onNext方法
        // 将响应结果response发射出去
        observer.onNext(response);

        //（3）调用Observer的onComplete方法
        // 结束数据发射
        if (!disposed) {
          terminated = true;
          observer.onComplete();
        }
      } catch (Throwable t) {
        Exceptions.throwIfFatal(t);
        if (terminated) {
          RxJavaPlugins.onError(t);
        } else if (!disposed) {
          try {
            // (4)调用Observer的onError方法
            // 向外发射异常
            observer.onError(t);
          } catch (Throwable inner) {
            Exceptions.throwIfFatal(inner);
            RxJavaPlugins.onError(new CompositeException(t, inner));
          }
        }
      }
    }

    @Override
    public void onFailure(Call<T> call, Throwable t) {
      if (call.isCanceled()) return;

      try {
        // (4)调用Observer的onError方法
        // 向外发射异常
        observer.onError(t);
      } catch (Throwable inner) {
        Exceptions.throwIfFatal(inner);
        RxJavaPlugins.onError(new CompositeException(t, inner));
      }
    }

    @Override
    public void dispose() {
      disposed = true;
      // 当用户调用Dispose.dispose()方法时
      // 取消网络请求
      call.cancel();
    }

    @Override
    public boolean isDisposed() {
      return disposed;
    }
  }
}
