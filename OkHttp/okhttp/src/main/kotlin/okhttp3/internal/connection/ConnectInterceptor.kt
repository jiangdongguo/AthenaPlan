/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package okhttp3.internal.connection

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.internal.http.RealInterceptorChain

/**
 * Opens a connection to the target server and proceeds to the next interceptor. The network might
 * be used for the returned response, or to validate a cached response with a conditional GET.
 */
object ConnectInterceptor : Interceptor {
  /**
   * 4-4 ConnectInterceptor拦截器
   * 打开一个连接，去连接目标服务器
   */
  @Throws(IOException::class)
  override fun intercept(chain: Interceptor.Chain): Response {
    val realChain = chain as RealInterceptorChain
    // (1) 获取一个ExChange对象，该对象是对Http1ExchangeCodec/Http2ExchangeCodec和下一个Chain的封装
    //  Http1ExchangeCodec/Http2ExchangeCodec分别对应于HTTP1.1/HTTP2.0
    // 主要用户对HTTP请求编码，发起Socket连接，并解码HTTP响应

    // Socket通信过程：
    // a. 发送网络请求头部headers【writeRequest】；
    // b. 打开一个接收器sink，写网络请求body【newKnownLengthSink或newChunkedSink】；
    // c. 完成写入，并关闭接收器sink；
    // d. 读取响应头部【readResponseHeaders】；
    // e. 打开数据源source，读取响应实体body【newFixedLengthSource】
    // f. 读取完毕，关闭数据源Source
    val exchange = realChain.call.initExchange(chain)
    val connectedChain = realChain.copy(exchange = exchange)

    // (2) 执行下一个拦截器CallServerInterceptor
    // 并将该拦截器的response作为本拦截器(ConnectInterceptor)的response返回给上一个拦截器
    return connectedChain.proceed(realChain.request)
  }
}
