/*
 * Copyright (C) 2012 Square, Inc.
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

import android.os.Handler;
import android.os.Looper;

import static java.util.Collections.unmodifiableList;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.http.GET;
import retrofit2.http.HTTP;
import retrofit2.http.Header;
import retrofit2.http.Url;

/**
 * Retrofit adapts a Java interface to HTTP calls by using annotations on the declared methods to
 * define how requests are made. Create instances using {@linkplain Builder the builder} and pass
 * your interface to {@link #create} to generate an implementation.
 *
 * <p>For example,
 *
 * <pre><code>
 * Retrofit retrofit = new Retrofit.Builder()
 *     .baseUrl("https://api.example.com/")
 *     .addConverterFactory(GsonConverterFactory.create())
 *     .build();
 *
 * MyApi api = retrofit.create(MyApi.class);
 * Response&lt;User&gt; user = api.getUser().execute();
 * </code></pre>
 *
 * @author Bob Lee (bob@squareup.com)
 * @author Jake Wharton (jw@squareup.com)
 */

/**
 * 使用建造者模式构建Retrofit对象
 */
public final class Retrofit {
  // 缓存Service接口方法集合
  // key Method Value Service
  private final Map<Method, ServiceMethod<?>> serviceMethodCache = new ConcurrentHashMap<>();

  final okhttp3.Call.Factory callFactory;
  final HttpUrl baseUrl;
  final List<Converter.Factory> converterFactories;
  final List<CallAdapter.Factory> callAdapterFactories;
  final @Nullable Executor callbackExecutor;
  final boolean validateEagerly;

  Retrofit(
      okhttp3.Call.Factory callFactory,
      HttpUrl baseUrl,
      List<Converter.Factory> converterFactories,
      List<CallAdapter.Factory> callAdapterFactories,
      @Nullable Executor callbackExecutor,
      boolean validateEagerly) {
    this.callFactory = callFactory;
    this.baseUrl = baseUrl;
    this.converterFactories = converterFactories; // Copy+unmodifiable at call site.
    this.callAdapterFactories = callAdapterFactories; // Copy+unmodifiable at call site.
    this.callbackExecutor = callbackExecutor;
    this.validateEagerly = validateEagerly;
  }

  /**
   * Create an implementation of the API endpoints defined by the {@code service} interface.
   *
   * <p>The relative path for a given method is obtained from an annotation on the method describing
   * the request type. The built-in methods are {@link retrofit2.http.GET GET}, {@link
   * retrofit2.http.PUT PUT}, {@link retrofit2.http.POST POST}, {@link retrofit2.http.PATCH PATCH},
   * {@link retrofit2.http.HEAD HEAD}, {@link retrofit2.http.DELETE DELETE} and {@link
   * retrofit2.http.OPTIONS OPTIONS}. You can use a custom HTTP method with {@link HTTP @HTTP}. For
   * a dynamic URL, omit the path on the annotation and annotate the first parameter with {@link
   * Url @Url}.
   *
   * <p>Method parameters can be used to replace parts of the URL by annotating them with {@link
   * retrofit2.http.Path @Path}. Replacement sections are denoted by an identifier surrounded by
   * curly braces (e.g., "{foo}"). To add items to the query string of a URL use {@link
   * retrofit2.http.Query @Query}.
   *
   * <p>The body of a request is denoted by the {@link retrofit2.http.Body @Body} annotation. The
   * object will be converted to request representation by one of the {@link Converter.Factory}
   * instances. A {@link RequestBody} can also be used for a raw representation.
   *
   * <p>Alternative request body formats are supported by method annotations and corresponding
   * parameter annotations:
   *
   * <ul>
   *   <li>{@link retrofit2.http.FormUrlEncoded @FormUrlEncoded} - Form-encoded data with key-value
   *       pairs specified by the {@link retrofit2.http.Field @Field} parameter annotation.
   *   <li>{@link retrofit2.http.Multipart @Multipart} - RFC 2388-compliant multipart data with
   *       parts specified by the {@link retrofit2.http.Part @Part} parameter annotation.
   * </ul>
   *
   * <p>Additional static headers can be added for an endpoint using the {@link
   * retrofit2.http.Headers @Headers} method annotation. For per-request control over a header
   * annotate a parameter with {@link Header @Header}.
   *
   * <p>By default, methods return a {@link Call} which represents the HTTP request. The generic
   * parameter of the call is the response body type and will be converted by one of the {@link
   * Converter.Factory} instances. {@link ResponseBody} can also be used for a raw representation.
   * {@link Void} can be used if you do not care about the body contents.
   *
   * <p>For example:
   *
   * <pre>
   * public interface CategoryService {
   *   &#64;POST("category/{cat}/")
   *   Call&lt;List&lt;Item&gt;&gt; categoryList(@Path("cat") String a, @Query("page") int b);
   * }
   * </pre>
   */
  @SuppressWarnings("unchecked") // Single-interface proxy creation guarded by parameter safety.
  public <T> T create(final Class<T> service) {
    // 1. 检查Service接口的有效性
    // 即Service必须是接口，且不能是static方法和default方法(后者需开启严格检查validateEagerly)
    validateServiceInterface(service);

    // 2. 动态代理模式
    // 为service动态创建一个代理对象，并返回
    // 当调用Service接口中的方法时，会触发invoke方法，该方法最终会通过反射调用对应的方法。
    return (T)
        Proxy.newProxyInstance(
            service.getClassLoader(),
            new Class<?>[] {service},
            new InvocationHandler() {
              private final Platform platform = Platform.get();
              private final Object[] emptyArgs = new Object[0];

              @Override
              public @Nullable Object invoke(Object proxy, Method method, @Nullable Object[] args)
                  throws Throwable {
                // If the method is a method from Object then defer to normal invocation.
                // 2-1 如果被调用的方法属于类的对象
                // 直接反射调用即可
                if (method.getDeclaringClass() == Object.class) {
                  return method.invoke(this, args);
                }

                // 2-2 如果被调用的方法是一个default方法，通过platform.invokeDefaultMethod完成调用
                // 否则，通过调用ServiceMethod的invoke方法完成最终的网络请求调用。其中，ServiceMethod通过loadServiceMethod获取
                // 注意：Service接口中的每一个method都对应一个ServiceMethod对象
                args = args != null ? args : emptyArgs;
                return platform.isDefaultMethod(method)
                    ? platform.invokeDefaultMethod(method, service, proxy, args)
                    : loadServiceMethod(method).invoke(args);
              }
            });
  }

  private void validateServiceInterface(Class<?> service) {
    // 1-1 Service必须是接口，否则抛出异常
    if (!service.isInterface()) {
      throw new IllegalArgumentException("API declarations must be interfaces.");
    }
    Deque<Class<?>> check = new ArrayDeque<>(1);
    check.add(service);
    while (!check.isEmpty()) {
      Class<?> candidate = check.removeFirst();
      if (candidate.getTypeParameters().length != 0) {
        StringBuilder message =
            new StringBuilder("Type parameters are unsupported on ").append(candidate.getName());
        if (candidate != service) {
          message.append(" which is an interface of ").append(service.getName());
        }
        throw new IllegalArgumentException(message.toString());
      }
      Collections.addAll(check, candidate.getInterfaces());
    }
    // 1-2 如果设置了validateEagerly标签
    // 还需要判断接口中的方法不能是静态方法或Java中的default方法
    if (validateEagerly) {
      Platform platform = Platform.get();
      for (Method method : service.getDeclaredMethods()) {
        if (!platform.isDefaultMethod(method) && !Modifier.isStatic(method.getModifiers())) {
          loadServiceMethod(method);
        }
      }
    }
  }

  ServiceMethod<?> loadServiceMethod(Method method) {
    // 2-2-1 从ServiceMethod缓存中获取method对应的ServiceMethod
    // 如果找到直接返回。
    ServiceMethod<?> result = serviceMethodCache.get(method);
    if (result != null) return result;

    // 2-2-2 再次尝试从缓存中查找ServiceMethod
    // 如果没有找到，就调用ServiceMethod.parseAnnotations创建一个ServiceMethod返回
    // 同时将其缓存到serviceMethodCache集合中
    synchronized (serviceMethodCache) {
      result = serviceMethodCache.get(method);
      if (result == null) {
        // a. 创建method对应的ServiceMethod
        result = ServiceMethod.parseAnnotations(this, method);
        // b. 将ServiceMethod缓存到集合serviceMethodCache
        serviceMethodCache.put(method, result);
      }
    }
    return result;
  }

  /**
   * The factory used to create {@linkplain okhttp3.Call OkHttp calls} for sending a HTTP requests.
   * Typically an instance of {@link OkHttpClient}.
   */
  public okhttp3.Call.Factory callFactory() {
    return callFactory;
  }

  /** The API base URL. */
  public HttpUrl baseUrl() {
    return baseUrl;
  }

  /**
   * Returns a list of the factories tried when creating a {@linkplain #callAdapter(Type,
   * Annotation[])} call adapter}.
   */
  public List<CallAdapter.Factory> callAdapterFactories() {
    return callAdapterFactories;
  }

  /**
   * Returns the {@link CallAdapter} for {@code returnType} from the available {@linkplain
   * #callAdapterFactories() factories}.
   *
   * @throws IllegalArgumentException if no call adapter available for {@code type}.
   */
  public CallAdapter<?, ?> callAdapter(Type returnType, Annotation[] annotations) {
    return nextCallAdapter(null, returnType, annotations);
  }

  /**
   * Returns the {@link CallAdapter} for {@code returnType} from the available {@linkplain
   * #callAdapterFactories() factories} except {@code skipPast}.
   *
   * @throws IllegalArgumentException if no call adapter available for {@code type}.
   */
  public CallAdapter<?, ?> nextCallAdapter(
      @Nullable CallAdapter.Factory skipPast, Type returnType, Annotation[] annotations) {
    Objects.requireNonNull(returnType, "returnType == null");
    Objects.requireNonNull(annotations, "annotations == null");

    int start = callAdapterFactories.indexOf(skipPast) + 1;
    for (int i = start, count = callAdapterFactories.size(); i < count; i++) {
      CallAdapter<?, ?> adapter = callAdapterFactories.get(i).get(returnType, annotations, this);
      if (adapter != null) {
        return adapter;
      }
    }

    StringBuilder builder =
        new StringBuilder("Could not locate call adapter for ").append(returnType).append(".\n");
    if (skipPast != null) {
      builder.append("  Skipped:");
      for (int i = 0; i < start; i++) {
        builder.append("\n   * ").append(callAdapterFactories.get(i).getClass().getName());
      }
      builder.append('\n');
    }
    builder.append("  Tried:");
    for (int i = start, count = callAdapterFactories.size(); i < count; i++) {
      builder.append("\n   * ").append(callAdapterFactories.get(i).getClass().getName());
    }
    throw new IllegalArgumentException(builder.toString());
  }

  /**
   * Returns an unmodifiable list of the factories tried when creating a {@linkplain
   * #requestBodyConverter(Type, Annotation[], Annotation[]) request body converter}, a {@linkplain
   * #responseBodyConverter(Type, Annotation[]) response body converter}, or a {@linkplain
   * #stringConverter(Type, Annotation[]) string converter}.
   */
  public List<Converter.Factory> converterFactories() {
    return converterFactories;
  }

  /**
   * Returns a {@link Converter} for {@code type} to {@link RequestBody} from the available
   * {@linkplain #converterFactories() factories}.
   *
   * @throws IllegalArgumentException if no converter available for {@code type}.
   */
  public <T> Converter<T, RequestBody> requestBodyConverter(
      Type type, Annotation[] parameterAnnotations, Annotation[] methodAnnotations) {
    return nextRequestBodyConverter(null, type, parameterAnnotations, methodAnnotations);
  }

  /**
   * Returns a {@link Converter} for {@code type} to {@link RequestBody} from the available
   * {@linkplain #converterFactories() factories} except {@code skipPast}.
   *
   * @throws IllegalArgumentException if no converter available for {@code type}.
   */
  public <T> Converter<T, RequestBody> nextRequestBodyConverter(
      @Nullable Converter.Factory skipPast,
      Type type,
      Annotation[] parameterAnnotations,
      Annotation[] methodAnnotations) {
    Objects.requireNonNull(type, "type == null");
    Objects.requireNonNull(parameterAnnotations, "parameterAnnotations == null");
    Objects.requireNonNull(methodAnnotations, "methodAnnotations == null");

    int start = converterFactories.indexOf(skipPast) + 1;
    for (int i = start, count = converterFactories.size(); i < count; i++) {
      Converter.Factory factory = converterFactories.get(i);
      Converter<?, RequestBody> converter =
          factory.requestBodyConverter(type, parameterAnnotations, methodAnnotations, this);
      if (converter != null) {
        //noinspection unchecked
        return (Converter<T, RequestBody>) converter;
      }
    }

    StringBuilder builder =
        new StringBuilder("Could not locate RequestBody converter for ").append(type).append(".\n");
    if (skipPast != null) {
      builder.append("  Skipped:");
      for (int i = 0; i < start; i++) {
        builder.append("\n   * ").append(converterFactories.get(i).getClass().getName());
      }
      builder.append('\n');
    }
    builder.append("  Tried:");
    for (int i = start, count = converterFactories.size(); i < count; i++) {
      builder.append("\n   * ").append(converterFactories.get(i).getClass().getName());
    }
    throw new IllegalArgumentException(builder.toString());
  }

  /**
   * Returns a {@link Converter} for {@link ResponseBody} to {@code type} from the available
   * {@linkplain #converterFactories() factories}.
   *
   * @throws IllegalArgumentException if no converter available for {@code type}.
   */
  public <T> Converter<ResponseBody, T> responseBodyConverter(Type type, Annotation[] annotations) {
    return nextResponseBodyConverter(null, type, annotations);
  }

  /**
   * Returns a {@link Converter} for {@link ResponseBody} to {@code type} from the available
   * {@linkplain #converterFactories() factories} except {@code skipPast}.
   *
   * @throws IllegalArgumentException if no converter available for {@code type}.
   */
  public <T> Converter<ResponseBody, T> nextResponseBodyConverter(
      @Nullable Converter.Factory skipPast, Type type, Annotation[] annotations) {
    Objects.requireNonNull(type, "type == null");
    Objects.requireNonNull(annotations, "annotations == null");

    int start = converterFactories.indexOf(skipPast) + 1;
    for (int i = start, count = converterFactories.size(); i < count; i++) {
      Converter<ResponseBody, ?> converter =
          converterFactories.get(i).responseBodyConverter(type, annotations, this);
      if (converter != null) {
        //noinspection unchecked
        return (Converter<ResponseBody, T>) converter;
      }
    }

    StringBuilder builder =
        new StringBuilder("Could not locate ResponseBody converter for ")
            .append(type)
            .append(".\n");
    if (skipPast != null) {
      builder.append("  Skipped:");
      for (int i = 0; i < start; i++) {
        builder.append("\n   * ").append(converterFactories.get(i).getClass().getName());
      }
      builder.append('\n');
    }
    builder.append("  Tried:");
    for (int i = start, count = converterFactories.size(); i < count; i++) {
      builder.append("\n   * ").append(converterFactories.get(i).getClass().getName());
    }
    throw new IllegalArgumentException(builder.toString());
  }

  /**
   * Returns a {@link Converter} for {@code type} to {@link String} from the available {@linkplain
   * #converterFactories() factories}.
   */
  public <T> Converter<T, String> stringConverter(Type type, Annotation[] annotations) {
    Objects.requireNonNull(type, "type == null");
    Objects.requireNonNull(annotations, "annotations == null");

    for (int i = 0, count = converterFactories.size(); i < count; i++) {
      Converter<?, String> converter =
          converterFactories.get(i).stringConverter(type, annotations, this);
      if (converter != null) {
        //noinspection unchecked
        return (Converter<T, String>) converter;
      }
    }

    // Nothing matched. Resort to default converter which just calls toString().
    //noinspection unchecked
    return (Converter<T, String>) BuiltInConverters.ToStringConverter.INSTANCE;
  }

  /**
   * The executor used for {@link Callback} methods on a {@link Call}. This may be {@code null}, in
   * which case callbacks should be made synchronously on the background thread.
   */
  public @Nullable Executor callbackExecutor() {
    return callbackExecutor;
  }

  public Builder newBuilder() {
    return new Builder(this);
  }

  /**
   * Build a new {@link Retrofit}.
   *
   * <p>Calling {@link #baseUrl} is required before calling {@link #build()}. All other methods are
   * optional.
   */
  public static final class Builder {
    // 平台信息，区分Android平台还是Java
    private final Platform platform;
    // CaLL
    private @Nullable okhttp3.Call.Factory callFactory;
    // Http URL
    private @Nullable HttpUrl baseUrl;
    // 数据转换器列表
    private final List<Converter.Factory> converterFactories = new ArrayList<>();
    // 网络请求器列表
    private final List<CallAdapter.Factory> callAdapterFactories = new ArrayList<>();
    // 线程池
    private @Nullable Executor callbackExecutor;
    private boolean validateEagerly;

    Builder(Platform platform) {
      this.platform = platform;
    }

    // Buidler构造方法
    // 在该方法中通过判断System.getProperty("java.vm.name")是否为"Dalvik"
    // 将this.platform = new Android();
    public Builder() {
      this(Platform.get());
    }

    Builder(Retrofit retrofit) {
      platform = Platform.get();
      callFactory = retrofit.callFactory;
      baseUrl = retrofit.baseUrl;

      // Do not add the default BuiltIntConverters and platform-aware converters added by build().
      for (int i = 1,
              size = retrofit.converterFactories.size() - platform.defaultConverterFactoriesSize();
          i < size;
          i++) {
        converterFactories.add(retrofit.converterFactories.get(i));
      }

      // Do not add the default, platform-aware call adapters added by build().
      for (int i = 0,
              size =
                  retrofit.callAdapterFactories.size() - platform.defaultCallAdapterFactoriesSize();
          i < size;
          i++) {
        callAdapterFactories.add(retrofit.callAdapterFactories.get(i));
      }

      callbackExecutor = retrofit.callbackExecutor;
      validateEagerly = retrofit.validateEagerly;
    }

    /**
     * The HTTP client used for requests.
     *
     * <p>This is a convenience method for calling {@link #callFactory}.
     */
    public Builder client(OkHttpClient client) {
      return callFactory(Objects.requireNonNull(client, "client == null"));
    }

    /**
     * Specify a custom call factory for creating {@link Call} instances.
     *
     * <p>Note: Calling {@link #client} automatically sets this value.
     */
    public Builder callFactory(okhttp3.Call.Factory factory) {
      this.callFactory = Objects.requireNonNull(factory, "factory == null");
      return this;
    }

    /**
     * Set the API base URL.
     *
     * @see #baseUrl(HttpUrl)
     */
    public Builder baseUrl(URL baseUrl) {
      Objects.requireNonNull(baseUrl, "baseUrl == null");
      return baseUrl(HttpUrl.get(baseUrl.toString()));
    }

    /**
     * Set the API base URL.
     *
     * @see #baseUrl(HttpUrl)
     */
    public Builder baseUrl(String baseUrl) {
      Objects.requireNonNull(baseUrl, "baseUrl == null");
      return baseUrl(HttpUrl.get(baseUrl));
    }

    /**
     * Set the API base URL.
     *
     * <p>The specified endpoint values (such as with {@link GET @GET}) are resolved against this
     * value using {@link HttpUrl#resolve(String)}. The behavior of this matches that of an {@code
     * <a href="">} link on a website resolving on the current URL.
     *
     * <p><b>Base URLs should always end in {@code /}.</b>
     *
     * <p>A trailing {@code /} ensures that endpoints values which are relative paths will correctly
     * append themselves to a base which has path components.
     *
     * <p><b>Correct:</b><br>
     * Base URL: http://example.com/api/<br>
     * Endpoint: foo/bar/<br>
     * Result: http://example.com/api/foo/bar/
     *
     * <p><b>Incorrect:</b><br>
     * Base URL: http://example.com/api<br>
     * Endpoint: foo/bar/<br>
     * Result: http://example.com/foo/bar/
     *
     * <p>This method enforces that {@code baseUrl} has a trailing {@code /}.
     *
     * <p><b>Endpoint values which contain a leading {@code /} are absolute.</b>
     *
     * <p>Absolute values retain only the host from {@code baseUrl} and ignore any specified path
     * components.
     *
     * <p>Base URL: http://example.com/api/<br>
     * Endpoint: /foo/bar/<br>
     * Result: http://example.com/foo/bar/
     *
     * <p>Base URL: http://example.com/<br>
     * Endpoint: /foo/bar/<br>
     * Result: http://example.com/foo/bar/
     *
     * <p><b>Endpoint values may be a full URL.</b>
     *
     * <p>Values which have a host replace the host of {@code baseUrl} and values also with a scheme
     * replace the scheme of {@code baseUrl}.
     *
     * <p>Base URL: http://example.com/<br>
     * Endpoint: https://github.com/square/retrofit/<br>
     * Result: https://github.com/square/retrofit/
     *
     * <p>Base URL: http://example.com<br>
     * Endpoint: //github.com/square/retrofit/<br>
     * Result: http://github.com/square/retrofit/ (note the scheme stays 'http')
     */
    public Builder baseUrl(HttpUrl baseUrl) {
      Objects.requireNonNull(baseUrl, "baseUrl == null");
      List<String> pathSegments = baseUrl.pathSegments();
      if (!"".equals(pathSegments.get(pathSegments.size() - 1))) {
        throw new IllegalArgumentException("baseUrl must end in /: " + baseUrl);
      }
      this.baseUrl = baseUrl;
      return this;
    }

    /** Add converter factory for serialization and deserialization of objects. */
    public Builder addConverterFactory(Converter.Factory factory) {
      converterFactories.add(Objects.requireNonNull(factory, "factory == null"));
      return this;
    }

    /**
     * Add a call adapter factory for supporting service method return types other than {@link
     * Call}.
     */
    public Builder addCallAdapterFactory(CallAdapter.Factory factory) {
      callAdapterFactories.add(Objects.requireNonNull(factory, "factory == null"));
      return this;
    }

    /**
     * The executor on which {@link Callback} methods are invoked when returning {@link Call} from
     * your service method.
     *
     * <p>Note: {@code executor} is not used for {@linkplain #addCallAdapterFactory custom method
     * return types}.
     */
    public Builder callbackExecutor(Executor executor) {
      this.callbackExecutor = Objects.requireNonNull(executor, "executor == null");
      return this;
    }

    /** Returns a modifiable list of call adapter factories. */
    public List<CallAdapter.Factory> callAdapterFactories() {
      return this.callAdapterFactories;
    }

    /** Returns a modifiable list of converter factories. */
    public List<Converter.Factory> converterFactories() {
      return this.converterFactories;
    }

    /**
     * When calling {@link #create} on the resulting {@link Retrofit} instance, eagerly validate the
     * configuration of all methods in the supplied interface.
     */
    public Builder validateEagerly(boolean validateEagerly) {
      this.validateEagerly = validateEagerly;
      return this;
    }

    /**
     * Create the {@link Retrofit} instance using the configured values.
     *
     * <p>Note: If neither {@link #client} nor {@link #callFactory} is called a default {@link
     * OkHttpClient} will be created and used.
     */
    public Retrofit build() {
      if (baseUrl == null) {
        throw new IllegalStateException("Base URL required.");
      }
      // 1. 如果用户没有创建自定义的OkHttpClient
      // 就new一个出来
      okhttp3.Call.Factory callFactory = this.callFactory;
      if (callFactory == null) {
        callFactory = new OkHttpClient();
      }
      // 2. 如果用户没有创建自定义的响应回调线程池
      // 就调用Android().defaultCallbackExecutor()获取MainThreadExecutor
      // 该方法继承于Executor，当执行execute(r)方法时，实际上使用主线程的Handler发送Message
      //      private final Handler handler = new Handler(Looper.getMainLooper());
      //
      //      @Override
      //      public void execute(Runnable r) {
      //        handler.post(r);
      //      }
      Executor callbackExecutor = this.callbackExecutor;
      if (callbackExecutor == null) {
        callbackExecutor = platform.defaultCallbackExecutor();
      }

      // Make a defensive copy of the adapters and add the default Call adapter.
      // 3. 将用户自定义网络请求适配器和默认的网络适配器添加到集合callAdapterFactories中
      // 其中，默认的网络适配器列表为DefaultCallAdapterFactory
      List<CallAdapter.Factory> callAdapterFactories = new ArrayList<>(this.callAdapterFactories);
      callAdapterFactories.addAll(platform.defaultCallAdapterFactories(callbackExecutor));

      // Make a defensive copy of the converters.
      // 4. 将数据转换适配器器添加到集合中
      List<Converter.Factory> converterFactories =
          new ArrayList<>(
              1 + this.converterFactories.size() + platform.defaultConverterFactoriesSize());

      // Add the built-in converter factory first. This prevents overriding its behavior but also
      // ensures correct behavior when using converters that consume all types.
      converterFactories.add(new BuiltInConverters());
      converterFactories.addAll(this.converterFactories);
      converterFactories.addAll(platform.defaultConverterFactories());
      // 5. 构建Retrofit对象
      return new Retrofit(
          callFactory,
          baseUrl,
          unmodifiableList(converterFactories),
          unmodifiableList(callAdapterFactories),
          callbackExecutor,
          validateEagerly);
    }
  }
}