package com.alibaba.android.arouter.core;

import com.alibaba.android.arouter.base.UniqueKeyTreeMap;
import com.alibaba.android.arouter.facade.model.RouteMeta;
import com.alibaba.android.arouter.facade.template.IInterceptor;
import com.alibaba.android.arouter.facade.template.IProvider;
import com.alibaba.android.arouter.facade.template.IRouteGroup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Storage of route meta and other data.
 *
 * @author zhilong <a href="mailto:zhilong.lzl@alibaba-inc.com">Contact me.</a>
 * @version 1.0
 * @since 2017/2/23 下午1:39
 */
// 该类用于缓存路由元数据(route meta)和其他数据
class Warehouse {
    // Cache route and metas
    /**
     * groupsIndex缓存路列表
     *
     * key 路由分组；value 注解处理器自动生成的路由组，如ARouter$$Group$$service(service分组)
     */
    static Map<String, Class<? extends IRouteGroup>> groupsIndex = new HashMap<>();

    /**
     * routes 缓存所有路由的元数据(不区分Group)
     *
     * key 路由路径；value 路由元数据，如RouteMeta.build(RouteType.ACTIVITY, ....)
     */
    static Map<String, RouteMeta> routes = new HashMap<>();

    // Cache provider
    /**
     * 缓存provider(服务)列表
     *
     * key 服务class；value 注解处理器自动生成的服务组，如ARouter$$Providers$$camera(service分组)
     */
    static Map<Class, IProvider> providers = new HashMap<>();
    static Map<String, RouteMeta> providersIndex = new HashMap<>();

    // Cache interceptor
    /**
     * 缓存拦截器列表
     *
     * key 拦截器优先级；value 注解处理器自动生成拦截器，如ARouter$$Interceptor$$..
     */
    static Map<Integer, Class<? extends IInterceptor>> interceptorsIndex = new UniqueKeyTreeMap<>("More than one interceptors use same priority [%s]");
    static List<IInterceptor> interceptors = new ArrayList<>();

    static void clear() {
        routes.clear();
        groupsIndex.clear();
        providers.clear();
        providersIndex.clear();
        interceptors.clear();
        interceptorsIndex.clear();
    }
}
