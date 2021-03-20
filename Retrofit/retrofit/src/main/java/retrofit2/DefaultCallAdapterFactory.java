/*
 * Copyright (C) 2015 Square, Inc.
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
package retrofit2;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Objects;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;
import okhttp3.Request;
import okio.Timeout;

/**
 * 默认网络请求适配器
 */
final class DefaultCallAdapterFactory extends CallAdapter.Factory {
  // 线程池
  //    用于处理响应回调，由构造方法传入，默认为MainThreadExecutor
  private final @Nullable Executor callbackExecutor;

  DefaultCallAdapterFactory(@Nullable Executor callbackExecutor) {
    this.callbackExecutor = callbackExecutor;
  }

  /** 根据响应返回类型(returnType)，返回对应的适配器对象
   *
   * @param returnType 响应返回类型，转换后必须为Call.class
   * @param annotations 注解信息
   * @param retrofit Retrofit对象
   * @return
   */
  @Override
  public @Nullable CallAdapter<?, ?> get(
      Type returnType, Annotation[] annotations, Retrofit retrofit) {
    // 1. 响应类型必须为Call
    if (getRawType(returnType) != Call.class) {
      return null;
    }
    if (!(returnType instanceof ParameterizedType)) {
      throw new IllegalArgumentException(
          "Call return type must be parameterized as Call<Foo> or Call<? extends Foo>");
    }
    final Type responseType = Utils.getParameterUpperBound(0, (ParameterizedType) returnType);

    // 2. 设置响应回调处理线程池
    // 如果传入的annotations列表中包含SkipCallbackExecutor.class
    // 则将executor = null
    final Executor executor =
        Utils.isAnnotationPresent(annotations, SkipCallbackExecutor.class)
            ? null
            : callbackExecutor;
    // 3. 创建CallAdapter对象
    // 最终将通过该对象的adapt方法返回适配器对象，规则为
    // 如果executor=null，则返回用户传入的Call对象
    // 否则，返回new ExecutorCallbackCall()，该类继承于Call
    return new CallAdapter<Object, Call<?>>() {
      @Override
      public Type responseType() {
        return responseType;
      }

      @Override
      public Call<Object> adapt(Call<Object> call) {
        // ExecutorCallbackCall使用了代理模式（均继承于interface Call<T>）
        // ExecutorCallbackCall就是call的代理对象，它持有了call对象的引用
        return executor == null ? call : new ExecutorCallbackCall<>(executor, call);
      }
    };
  }

  static final class ExecutorCallbackCall<T> implements Call<T> {
    final Executor callbackExecutor;
    // 真实的Call对象
    final Call<T> delegate;

    ExecutorCallbackCall(Executor callbackExecutor, Call<T> delegate) {
      this.callbackExecutor = callbackExecutor;
      this.delegate = delegate;
    }

    @Override
    public void enqueue(final Callback<T> callback) {
      Objects.requireNonNull(callback, "callback == null");
      // 发起异步网络请求
      // 当响应结果返回后response，通过响应回调线程池(默认为MainThreadExecutor)
      // MainThreadExecutor最终将结果由子线程自动切换到主线程
      delegate.enqueue(
          new Callback<T>() {
            @Override
            public void onResponse(Call<T> call, final Response<T> response) {
              callbackExecutor.execute(
                  () -> {
                    if (delegate.isCanceled()) {
                      // Emulate OkHttp's behavior of throwing/delivering an IOException on
                      // cancellation.
                      callback.onFailure(ExecutorCallbackCall.this, new IOException("Canceled"));
                    } else {
                      callback.onResponse(ExecutorCallbackCall.this, response);
                    }
                  });
            }

            @Override
            public void onFailure(Call<T> call, final Throwable t) {
              callbackExecutor.execute(() -> callback.onFailure(ExecutorCallbackCall.this, t));
            }
          });
    }

    @Override
    public boolean isExecuted() {
      return delegate.isExecuted();
    }

    // 同步请求
    @Override
    public Response<T> execute() throws IOException {
      return delegate.execute();
    }

    @Override
    public void cancel() {
      delegate.cancel();
    }

    @Override
    public boolean isCanceled() {
      return delegate.isCanceled();
    }

    @SuppressWarnings("CloneDoesntCallSuperClone") // Performing deep clone.
    @Override
    public Call<T> clone() {
      return new ExecutorCallbackCall<>(callbackExecutor, delegate.clone());
    }

    @Override
    public Request request() {
      return delegate.request();
    }

    @Override
    public Timeout timeout() {
      return delegate.timeout();
    }
  }
}
