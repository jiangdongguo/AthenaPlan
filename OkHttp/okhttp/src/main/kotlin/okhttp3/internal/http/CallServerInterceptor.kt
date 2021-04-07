/*
 * Copyright (C) 2016 Square, Inc.
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
package okhttp3.internal.http

import java.io.IOException
import java.net.ProtocolException
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.internal.EMPTY_RESPONSE
import okhttp3.internal.http2.ConnectionShutdownException
import okio.buffer

/** This is the last interceptor in the chain. It makes a network call to the server. */
class CallServerInterceptor(private val forWebSocket: Boolean) : Interceptor {

  /**
   * 4-5 CallServerInterceptor拦截器
   * 拦截器链的最后一个拦截器，执行通过网络请求服务器，即完成Socket通信
   */
  @Throws(IOException::class)
  override fun intercept(chain: Interceptor.Chain): Response {
    val realChain = chain as RealInterceptorChain
    // (1) 获取网络访问对象Http1ExchangeCodec/Http2ExchangeCodec
    val exchange = realChain.exchange!!
    // (2) 获取网络访问请求request和body
    val request = realChain.request
    val requestBody = request.body
    val sentRequestMillis = System.currentTimeMillis()

    var invokeStartEvent = true
    var responseBuilder: Response.Builder? = null
    var sendRequestException: IOException? = null
    try {
      // (3) 打开一个Socket连接
      // 获取一个sink对象，写入头部
      exchange.writeRequestHeaders(request)

      // (4) 检查是否有body的请求方法
      // 即请求方法不是GET和HEAD的情况
      if (HttpMethod.permitsRequestBody(request.method) && requestBody != null) {
        //如果请求头是"100-continue"，等待服务器的响应
        if ("100-continue".equals(request.header("Expect"), ignoreCase = true)) {
          exchange.flushRequest()
          responseBuilder = exchange.readResponseHeaders(expectContinue = true)
          exchange.responseHeadersStart()
          invokeStartEvent = false
        }
        // 写入body，发送请求数据到服务端
        if (responseBuilder == null) {
          if (requestBody.isDuplex()) {
            // Prepare a duplex body so that the application can send a request body later.
            exchange.flushRequest()
            val bufferedRequestBody = exchange.createRequestBody(request, true).buffer()
            requestBody.writeTo(bufferedRequestBody)
          } else {
            // Write the request body if the "Expect: 100-continue" expectation was met.
            val bufferedRequestBody = exchange.createRequestBody(request, false).buffer()
            requestBody.writeTo(bufferedRequestBody)
            bufferedRequestBody.close()
          }
        } else {
          exchange.noRequestBody()
          if (!exchange.connection.isMultiplexed) {
            // If the "Expect: 100-continue" expectation wasn't met, prevent the HTTP/1 connection
            // from being reused. Otherwise we're still obligated to transmit the request body to
            // leave the connection in a consistent state.
            exchange.noNewExchangesOnConnection()
          }
        }
      } else {
        exchange.noRequestBody()
      }

      if (requestBody == null || !requestBody.isDuplex()) {
        exchange.finishRequest()
      }
    } catch (e: IOException) {
      if (e is ConnectionShutdownException) {
        throw e // No request was sent so there's no response to read.
      }
      if (!exchange.hasFailure) {
        throw e // Don't attempt to read the response; we failed to send the request.
      }
      sendRequestException = e
    }

    try {
      // (5) 读取响应头
      if (responseBuilder == null) {
        responseBuilder = exchange.readResponseHeaders(expectContinue = false)!!
        if (invokeStartEvent) {
          exchange.responseHeadersStart()
          invokeStartEvent = false
        }
      }
      // (6) 构建响应Response
      var response = responseBuilder
          .request(request)
          .handshake(exchange.connection.handshake())
          .sentRequestAtMillis(sentRequestMillis)
          .receivedResponseAtMillis(System.currentTimeMillis())
          .build()
      var code = response.code
      // (7) 如果服务器返回的状态码是100，再次尝试读取具体的response
      if (code == 100) {
        responseBuilder = exchange.readResponseHeaders(expectContinue = false)!!
        if (invokeStartEvent) {
          exchange.responseHeadersStart()
        }
        response = responseBuilder
            .request(request)
            .handshake(exchange.connection.handshake())
            .sentRequestAtMillis(sentRequestMillis)
            .receivedResponseAtMillis(System.currentTimeMillis())
            .build()
        code = response.code
      }

      exchange.responseHeadersEnd(response)
      // (8) 如果是WebSocket，并且返回状态码为101，表示响应body为空
      response = if (forWebSocket && code == 101) {
        // Connection is upgrading, but we need to ensure interceptors see a non-null response body.
        response.newBuilder()
            .body(EMPTY_RESPONSE)
            .build()
      } else {
        // (9) 读取响应实体body
        response.newBuilder()
            .body(exchange.openResponseBody(response))
            .build()
      }
      if ("close".equals(response.request.header("Connection"), ignoreCase = true) ||
          "close".equals(response.header("Connection"), ignoreCase = true)) {
        exchange.noNewExchangesOnConnection()
      }
      if ((code == 204 || code == 205) && response.body?.contentLength() ?: -1L > 0L) {
        throw ProtocolException(
            "HTTP $code had non-zero Content-Length: ${response.body?.contentLength()}")
      }
      // (10) 将最终的网络响应response返回给上一层拦截器ConnectInterceptor
      return response
    } catch (e: IOException) {
      if (sendRequestException != null) {
        sendRequestException.addSuppressed(e)
        throw sendRequestException
      }
      throw e
    }
  }
}
