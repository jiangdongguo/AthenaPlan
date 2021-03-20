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
import retrofit2.Response;

/** 发起同步网络请求，将响应结果通过RxJava发射出去
 *
 * @param <T>
 */
final class CallExecuteObservable<T> extends Observable<Response<T>> {
  private final Call<T> originalCall;

  CallExecuteObservable(Call<T> originalCall) {
    this.originalCall = originalCall;
  }

  @Override
  protected void subscribeActual(Observer<? super Response<T>> observer) {
    // Since Call is a one-shot type, clone it for each new observer.
    // 3-1-1 拷贝Call，便于每个Observer对应一个单独Call
    Call<T> call = originalCall.clone();
    // 3-1-2 设置Observer的Dispose
    CallDisposable disposable = new CallDisposable(call);
    observer.onSubscribe(disposable);
    if (disposable.isDisposed()) {
      return;
    }

    // 3-1-3 发起同步网络请求，并将结果通过observer.onNext发射出去
    // 需要注意的是observable.subscribeOn
    // 指定为子线程操作，因为是网络请求
    boolean terminated = false;
    try {
      Response<T> response = call.execute();
      if (!disposable.isDisposed()) {
        observer.onNext(response);
      }
      if (!disposable.isDisposed()) {
        terminated = true;
        observer.onComplete();
      }
    } catch (Throwable t) {
      Exceptions.throwIfFatal(t);
      if (terminated) {
        RxJavaPlugins.onError(t);
      } else if (!disposable.isDisposed()) {
        try {
          observer.onError(t);
        } catch (Throwable inner) {
          Exceptions.throwIfFatal(inner);
          RxJavaPlugins.onError(new CompositeException(t, inner));
        }
      }
    }
  }

  private static final class CallDisposable implements Disposable {
    private final Call<?> call;
    private volatile boolean disposed;

    CallDisposable(Call<?> call) {
      this.call = call;
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
