package com.alibaba.android.arouter.core;

import android.content.Context;
import android.net.Uri;

import com.alibaba.android.arouter.exception.HandlerException;
import com.alibaba.android.arouter.exception.NoRouteFoundException;
import com.alibaba.android.arouter.facade.Postcard;
import com.alibaba.android.arouter.facade.enums.TypeKind;
import com.alibaba.android.arouter.facade.model.RouteMeta;
import com.alibaba.android.arouter.facade.template.IInterceptorGroup;
import com.alibaba.android.arouter.facade.template.IProvider;
import com.alibaba.android.arouter.facade.template.IProviderGroup;
import com.alibaba.android.arouter.facade.template.IRouteGroup;
import com.alibaba.android.arouter.facade.template.IRouteRoot;
import com.alibaba.android.arouter.launcher.ARouter;
import com.alibaba.android.arouter.utils.ClassUtils;
import com.alibaba.android.arouter.utils.Consts;
import com.alibaba.android.arouter.utils.MapUtils;
import com.alibaba.android.arouter.utils.PackageUtils;
import com.alibaba.android.arouter.utils.TextUtils;

import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;

import static com.alibaba.android.arouter.launcher.ARouter.logger;
import static com.alibaba.android.arouter.utils.Consts.AROUTER_SP_CACHE_KEY;
import static com.alibaba.android.arouter.utils.Consts.AROUTER_SP_KEY_MAP;
import static com.alibaba.android.arouter.utils.Consts.DOT;
import static com.alibaba.android.arouter.utils.Consts.ROUTE_ROOT_PAKCAGE;
import static com.alibaba.android.arouter.utils.Consts.SDK_NAME;
import static com.alibaba.android.arouter.utils.Consts.SEPARATOR;
import static com.alibaba.android.arouter.utils.Consts.SUFFIX_INTERCEPTORS;
import static com.alibaba.android.arouter.utils.Consts.SUFFIX_PROVIDERS;
import static com.alibaba.android.arouter.utils.Consts.SUFFIX_ROOT;
import static com.alibaba.android.arouter.utils.Consts.TAG;

/**
 * LogisticsCenter contains all of the map.
 * <p>
 * 1. Creates instance when it is first used.
 * 2. Handler Multi-Module relationship map(*)
 * 3. Complex logic to solve duplicate group definition
 *
 * @author Alex <a href="mailto:zhilong.liu@aliyun.com">Contact me.</a>
 * @version 1.0
 * @since 16/8/23 15:02
 */
public class LogisticsCenter {
    private static Context mContext;
    static ThreadPoolExecutor executor;
    private static boolean registerByPlugin;

    /**
     * arouter-auto-register plugin will generate code inside this method
     * call this method to register all Routers, Interceptors and Providers
     */
    private static void loadRouterMap() {
        registerByPlugin = false;
        // auto generate register code by gradle plugin: arouter-auto-register
        // looks like below:
        // registerRouteRoot(new ARouter..Root..modulejava());
        // registerRouteRoot(new ARouter..Root..modulekotlin());
    }

    /**
     * register by class name
     * Sacrificing a bit of efficiency to solve
     * the problem that the main dex file size is too large
     */
    private static void register(String className) {
        if (!TextUtils.isEmpty(className)) {
            try {
                Class<?> clazz = Class.forName(className);
                Object obj = clazz.getConstructor().newInstance();
                if (obj instanceof IRouteRoot) {
                    registerRouteRoot((IRouteRoot) obj);
                } else if (obj instanceof IProviderGroup) {
                    registerProvider((IProviderGroup) obj);
                } else if (obj instanceof IInterceptorGroup) {
                    registerInterceptor((IInterceptorGroup) obj);
                } else {
                    logger.info(TAG, "register failed, class name: " + className
                            + " should implements one of IRouteRoot/IProviderGroup/IInterceptorGroup.");
                }
            } catch (Exception e) {
                logger.error(TAG,"register class error:" + className, e);
            }
        }
    }

    /**
     * method for arouter-auto-register plugin to register Routers
     * @param routeRoot IRouteRoot implementation class in the package: com.alibaba.android.arouter.core.routers
     */
    private static void registerRouteRoot(IRouteRoot routeRoot) {
        markRegisteredByPlugin();
        if (routeRoot != null) {
            routeRoot.loadInto(Warehouse.groupsIndex);
        }
    }

    /**
     * method for arouter-auto-register plugin to register Interceptors
     * @param interceptorGroup IInterceptorGroup implementation class in the package: com.alibaba.android.arouter.core.routers
     */
    private static void registerInterceptor(IInterceptorGroup interceptorGroup) {
        markRegisteredByPlugin();
        if (interceptorGroup != null) {
            interceptorGroup.loadInto(Warehouse.interceptorsIndex);
        }
    }

    /**
     * method for arouter-auto-register plugin to register Providers
     * @param providerGroup IProviderGroup implementation class in the package: com.alibaba.android.arouter.core.routers
     */
    private static void registerProvider(IProviderGroup providerGroup) {
        markRegisteredByPlugin();
        if (providerGroup != null) {
            providerGroup.loadInto(Warehouse.providersIndex);
        }
    }

    /**
     * mark already registered by arouter-auto-register plugin
     */
    private static void markRegisteredByPlugin() {
        if (!registerByPlugin) {
            registerByPlugin = true;
        }
    }

    /**
     * LogisticsCenter init, load all metas in memory. Demand initialization
     */
    public synchronized static void init(Context context, ThreadPoolExecutor tpe) throws HandlerException {
        mContext = context;
        executor = tpe;

        try {
            long startInit = System.currentTimeMillis();
            //load by plugin first
            loadRouterMap();
            if (registerByPlugin) {
                logger.info(TAG, "Load router map by arouter-auto-register plugin.");
            } else {
                Set<String> routerMap;

                // It will rebuild router map every times when debuggable.
                if (ARouter.debuggable() || PackageUtils.isNewVersion(context)) {
                    logger.info(TAG, "Run with debug mode or new install, rebuild router map.");
                    // These class was generated by arouter-compiler.
                    // 加载注解处理器生成的路由信息
                    // 缓存到内存中WareHouse
                    routerMap = ClassUtils.getFileNameByPackageName(mContext, ROUTE_ROOT_PAKCAGE);
                    if (!routerMap.isEmpty()) {
                        context.getSharedPreferences(AROUTER_SP_CACHE_KEY, Context.MODE_PRIVATE).edit().putStringSet(AROUTER_SP_KEY_MAP, routerMap).apply();
                    }

                    PackageUtils.updateVersion(context);    // Save new version name when router map update finishes.
                } else {
                    logger.info(TAG, "Load router map from cache.");
                    routerMap = new HashSet<>(context.getSharedPreferences(AROUTER_SP_CACHE_KEY, Context.MODE_PRIVATE).getStringSet(AROUTER_SP_KEY_MAP, new HashSet<String>()));
                }

                logger.info(TAG, "Find router map finished, map size = " + routerMap.size() + ", cost " + (System.currentTimeMillis() - startInit) + " ms.");
                startInit = System.currentTimeMillis();
                // 加载注解处理器生成的路由信息
                // 缓存到内存中WareHouse
                for (String className : routerMap) {
                    if (className.startsWith(ROUTE_ROOT_PAKCAGE + DOT + SDK_NAME + SEPARATOR + SUFFIX_ROOT)) {
                        // This one of root elements, load root.
                        ((IRouteRoot) (Class.forName(className).getConstructor().newInstance())).loadInto(Warehouse.groupsIndex);
                    } else if (className.startsWith(ROUTE_ROOT_PAKCAGE + DOT + SDK_NAME + SEPARATOR + SUFFIX_INTERCEPTORS)) {
                        // Load interceptorMeta
                        ((IInterceptorGroup) (Class.forName(className).getConstructor().newInstance())).loadInto(Warehouse.interceptorsIndex);
                    } else if (className.startsWith(ROUTE_ROOT_PAKCAGE + DOT + SDK_NAME + SEPARATOR + SUFFIX_PROVIDERS)) {
                        // Load providerIndex
                        ((IProviderGroup) (Class.forName(className).getConstructor().newInstance())).loadInto(Warehouse.providersIndex);
                    }
                }
            }

            logger.info(TAG, "Load root element finished, cost " + (System.currentTimeMillis() - startInit) + " ms.");

            if (Warehouse.groupsIndex.size() == 0) {
                logger.error(TAG, "No mapping files were found, check your configuration please!");
            }

            if (ARouter.debuggable()) {
                logger.debug(TAG, String.format(Locale.getDefault(), "LogisticsCenter has already been loaded, GroupIndex[%d], InterceptorIndex[%d], ProviderIndex[%d]", Warehouse.groupsIndex.size(), Warehouse.interceptorsIndex.size(), Warehouse.providersIndex.size()));
            }
        } catch (Exception e) {
            throw new HandlerException(TAG + "ARouter init logistics center exception! [" + e.getMessage() + "]");
        }
    }

    /**
     * Build postcard by serviceName
     *
     * @param serviceName interfaceName
     * @return postcard
     */
    public static Postcard buildProvider(String serviceName) {
        RouteMeta meta = Warehouse.providersIndex.get(serviceName);

        if (null == meta) {
            return null;
        } else {
            return new Postcard(meta.getPath(), meta.getGroup());
        }
    }

    /**
     * Completion the postcard by route metas
     * 完善postcard的路由信息
     * @param postcard Incomplete postcard, should complete by this method.
     */
    public synchronized static void completion(Postcard postcard) {
        if (null == postcard) {
            throw new NoRouteFoundException(TAG + "No postcard!");
        }
        // 3-1 通过路由path获取路由的元数据RouteMeta
        //    private RouteType type;         // Type of route
        //    private Element rawType;        // Raw type of route
        //    private Class<?> destination;   // Destination
        //    private String path;            // Path of route
        //    private String group;           // Group of route
        //    private int priority = -1;      // The smaller the number, the higher the priority
        //    private int extra;              // Extra data
        //    private Map<String, Integer> paramsType;  // Param type
        //    private String name;
        //    private Map<String, Autowired> injectConfig;
        // （1）如果获取元数据失败，则
        //      如果没有找到(路由分组，如ARouter$$Group$$service，不存在)，抛出NoRouteFoundException
        //      否则，尝试动态加载Group，然后再尝试completion
        RouteMeta routeMeta = Warehouse.routes.get(postcard.getPath());
        if (null == routeMeta) {
            // Maybe its does't exist, or didn't load.
            if (!Warehouse.groupsIndex.containsKey(postcard.getGroup())) {
                throw new NoRouteFoundException(TAG + "There is no route match the path [" + postcard.getPath() + "], in group [" + postcard.getGroup() + "]");
            } else {
                // Load route and cache it into memory, then delete from metas.
                try {
                    if (ARouter.debuggable()) {
                        logger.debug(TAG, String.format(Locale.getDefault(), "The group [%s] starts loading, trigger by [%s]", postcard.getGroup(), postcard.getPath()));
                    }
                    // 动态加载group
                    //         if (Warehouse.groupsIndex.containsKey(groupName)){
                    //            // If this group is included, but it has not been loaded
                    //            // load this group first, because dynamic route has high priority.
                    //            Warehouse.groupsIndex.get(groupName).getConstructor().newInstance().loadInto(Warehouse.routes);
                    //            Warehouse.groupsIndex.remove(groupName);
                    //        }
                    addRouteGroupDynamic(postcard.getGroup(), null);

                    if (ARouter.debuggable()) {
                        logger.debug(TAG, String.format(Locale.getDefault(), "The group [%s] has already been loaded, trigger by [%s]", postcard.getGroup(), postcard.getPath()));
                    }
                } catch (Exception e) {
                    throw new HandlerException(TAG + "Fatal exception when loading group meta. [" + e.getMessage() + "]");
                }

                completion(postcard);   // Reload
            }
        }
        // （2）获取路由元数据routeMeta成功，将routeMeta的数据依次填充的Postcard相关字段
        else {
            // a. 路由基本参数填充
            // destination：路由目，如LoginActivity.class
            // type：路由类型，如RouteType.Activity；
            // priority：路由优先级，如拦截器优先级；
            // extra：
            // uri:路由URI
            // params：传递的参数，最终会被保存到Bundle对象中
            postcard.setDestination(routeMeta.getDestination());
            postcard.setType(routeMeta.getType());
            postcard.setPriority(routeMeta.getPriority());
            postcard.setExtra(routeMeta.getExtra());

            Uri rawUri = postcard.getUri();
            if (null != rawUri) {   // Try to set params into bundle.
                Map<String, String> resultMap = TextUtils.splitQueryParameters(rawUri);
                Map<String, Integer> paramsType = routeMeta.getParamsType();

                if (MapUtils.isNotEmpty(paramsType)) {
                    // 获取所有传递参数，保存到Bundle
                    // Set value by its type, just for params which annotation by @Param
                    for (Map.Entry<String, Integer> params : paramsType.entrySet()) {
                        setValue(postcard,
                                params.getValue(),
                                params.getKey(),
                                resultMap.get(params.getKey()));
                    }

                    // Save params name which need auto inject.
                    postcard.getExtras().putStringArray(ARouter.AUTO_INJECT, paramsType.keySet().toArray(new String[]{}));
                }

                // Save raw uri
                postcard.withString(ARouter.RAW_URI, rawUri.toString());
            }
            // b. 根据路由类型决定是否屏蔽拦截器
            // 如果是Provider，通过反射实例化一个对象保存到PostCard中，然后将greenChannel标志置为true
            // 如果是Fragment，将greenChannel标志置为true
            // ps：_ARouter.navigation方法最终根据greenChannel决定是否执行拦截器
            switch (routeMeta.getType()) {
                case PROVIDER:  // if the route is provider, should find its instance
                    // Its provider, so it must implement IProvider
                    Class<? extends IProvider> providerMeta = (Class<? extends IProvider>) routeMeta.getDestination();
                    IProvider instance = Warehouse.providers.get(providerMeta);
                    if (null == instance) { // There's no instance of this provider
                        IProvider provider;
                        try {
                            provider = providerMeta.getConstructor().newInstance();
                            provider.init(mContext);
                            Warehouse.providers.put(providerMeta, provider);
                            instance = provider;
                        } catch (Exception e) {
                            logger.error(TAG, "Init provider failed!", e);
                            throw new HandlerException("Init provider failed!");
                        }
                    }
                    postcard.setProvider(instance);
                    postcard.greenChannel();    // Provider should skip all of interceptors
                    break;
                case FRAGMENT:
                    postcard.greenChannel();    // Fragment needn't interceptors
                default:
                    break;
            }
        }
    }

    /**
     * Set value by known type
     *
     * @param postcard postcard
     * @param typeDef  type
     * @param key      key
     * @param value    value
     */
    private static void setValue(Postcard postcard, Integer typeDef, String key, String value) {
        if (TextUtils.isEmpty(key) || TextUtils.isEmpty(value)) {
            return;
        }

        try {
            if (null != typeDef) {
                if (typeDef == TypeKind.BOOLEAN.ordinal()) {
                    postcard.withBoolean(key, Boolean.parseBoolean(value));
                } else if (typeDef == TypeKind.BYTE.ordinal()) {
                    postcard.withByte(key, Byte.parseByte(value));
                } else if (typeDef == TypeKind.SHORT.ordinal()) {
                    postcard.withShort(key, Short.parseShort(value));
                } else if (typeDef == TypeKind.INT.ordinal()) {
                    postcard.withInt(key, Integer.parseInt(value));
                } else if (typeDef == TypeKind.LONG.ordinal()) {
                    postcard.withLong(key, Long.parseLong(value));
                } else if (typeDef == TypeKind.FLOAT.ordinal()) {
                    postcard.withFloat(key, Float.parseFloat(value));
                } else if (typeDef == TypeKind.DOUBLE.ordinal()) {
                    postcard.withDouble(key, Double.parseDouble(value));
                } else if (typeDef == TypeKind.STRING.ordinal()) {
                    postcard.withString(key, value);
                } else if (typeDef == TypeKind.PARCELABLE.ordinal()) {
                    // TODO : How to description parcelable value with string?
                } else if (typeDef == TypeKind.OBJECT.ordinal()) {
                    postcard.withString(key, value);
                } else {    // Compatible compiler sdk 1.0.3, in that version, the string type = 18
                    postcard.withString(key, value);
                }
            } else {
                postcard.withString(key, value);
            }
        } catch (Throwable ex) {
            logger.warning(Consts.TAG, "LogisticsCenter setValue failed! " + ex.getMessage());
        }
    }

    /**
     * Suspend business, clear cache.
     */
    public static void suspend() {
        Warehouse.clear();
    }

    public synchronized static void addRouteGroupDynamic(String groupName, IRouteGroup group) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException, InstantiationException {
        if (Warehouse.groupsIndex.containsKey(groupName)){
            // If this group is included, but it has not been loaded
            // load this group first, because dynamic route has high priority.
            Warehouse.groupsIndex.get(groupName).getConstructor().newInstance().loadInto(Warehouse.routes);
            Warehouse.groupsIndex.remove(groupName);
        }

        // cover old group.
        if (null != group) {
            group.loadInto(Warehouse.routes);
        }
    }
}
