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

import static retrofit2.Utils.methodError;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import javax.annotation.Nullable;

/** 抽象类，ServiceMethod
 *  它将完成对Method注解解析，得到HTTP的各类请求参数
 *
 * 每个method对应的ServiceMethod，它的实现类为HttpServiceMethod
 *
 * @param <T>
 */
abstract class ServiceMethod<T> {
  /** 创建ServiceMethod
   *  完成对HTTP请求参数的解析
   * @param retrofit
   * @param method
   * @param <T>
   * @return
   */
  static <T> ServiceMethod<T> parseAnnotations(Retrofit retrofit, Method method) {
    // 1. 实例化一个RequestFactory
    // 该对象是对请求参数的封装
    RequestFactory requestFactory = RequestFactory.parseAnnotations(retrofit, method);

    Type returnType = method.getGenericReturnType();
    if (Utils.hasUnresolvableType(returnType)) {
      throw methodError(
          method,
          "Method return type must not include a type variable or wildcard: %s",
          returnType);
    }
    if (returnType == void.class) {
      throw methodError(method, "Service methods cannot return void.");
    }
    // 2. 实例化一个HttpServiceMethod对象，并返回
    return HttpServiceMethod.parseAnnotations(retrofit, method, requestFactory);
  }

  /** 执行mthod方法调用逻辑，即该方法最终将得到发起网络请求的适配器对象
   *
   * @param args 参数
   * @return
   */
  abstract @Nullable T invoke(Object[] args);
}
