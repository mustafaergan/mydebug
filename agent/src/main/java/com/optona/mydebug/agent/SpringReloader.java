package com.optona.mydebug.agent;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Spring uygulama bağlamlarını, değişen sınıflara göre günceller:
 * <ul>
 *   <li>Spring'in yansıma/anotasyon önbelleklerini temizler,</li>
 *   <li>değişen sınıfların mevcut singleton'larına bağımlılıkları yeniden enjekte eder
 *       (yeni {@code @Autowired}/{@code @Value}/{@code @Resource} alanları dolar; nesne kimliği korunur),</li>
 *   <li>controller'ların istek eşlemelerini yeniden kaydeder (eklenen/silinen/değişen endpoint'ler),</li>
 *   <li>bileşen taraması kapsamında yeni eklenen {@code @Component} türevi sınıfları bean olarak kaydeder.</li>
 * </ul>
 * Bağlamlar derleme zamanı bağımlılığı olmadan, statik kayıtlardan bulunur (Spring Boot kapanış kancası,
 * {@code ContextLoader}, {@code LiveBeansView}).
 */
final class SpringReloader {
    private static final String[] CACHE_CLEARERS = {
            "org.springframework.util.ReflectionUtils#clearCache",
            "org.springframework.core.annotation.AnnotationUtils#clearCache",
            "org.springframework.core.annotation.AnnotationsScanner#clearCache",
            "org.springframework.core.annotation.AnnotationTypeMappings#clearCache",
            "org.springframework.core.ResolvableType#clearCache",
    };
    private static final String[] INJECTION_CACHES = {
            "injectionMetadataCache", "candidateConstructorsCache", "lookupMethodsChecked",
            "findInjectionMetadataCache"
    };
    private static final String[] HANDLER_MAPPINGS = {
            "org.springframework.web.servlet.handler.AbstractHandlerMethodMapping",
            "org.springframework.web.reactive.result.method.AbstractHandlerMethodMapping",
    };
    private static final String COMPONENT = "org.springframework.stereotype.Component";

    private final LoadedClasses loaded;
    private final Map<Object, Set<String>> basePackages = new WeakHashMap<Object, Set<String>>();

    SpringReloader(LoadedClasses loaded) {
        this.loaded = loaded;
    }

    // ---------------------------------------------------------------- değişen sınıflar

    void onClassesRedefined(Set<Class<?>> changed) throws Exception {
        List<Object> contexts = contexts();
        if (contexts.isEmpty()) return;
        clearFrameworkCaches(changed);

        for (Object context : contexts) {
            Object factory = Reflect.call(context, "getBeanFactory");
            clearInjectionCaches(factory);
            Reflect.tryCall(factory, "clearMetadataCache");

            Set<String> touched = new LinkedHashSet<String>();
            for (Class<?> type : changed) {
                if (type.isInterface() || type.isAnnotation() || type.isEnum()) continue;
                String[] names = (String[]) Reflect.call(factory, "getBeanNamesForType", type, Boolean.TRUE, Boolean.FALSE);
                Collections.addAll(touched, names);
            }
            if (touched.isEmpty()) continue;

            List<String> reinjected = new ArrayList<String>();
            List<Object> mappings = handlerMappings(factory);
            int endpoints = 0;
            for (String name : touched) {
                Object bean = Reflect.call(factory, "getSingleton", name); // oluşturmadan mevcut örnek
                if (bean == null) continue;
                Object target = unwrap(bean);
                try {
                    Reflect.call(factory, "autowireBean", target);
                    reinjected.add(name);
                } catch (Throwable t) {
                    Log.warn("could not re-inject " + name, t);
                }
                for (Object mapping : mappings) endpoints += remap(mapping, name, userClass(target.getClass()));
            }
            if (!reinjected.isEmpty()) {
                Log.info("Spring: refreshed " + join(reinjected)
                        + (endpoints > 0 ? " - " + endpoints + " endpoint" + (endpoints == 1 ? "" : "s") + " mapped" : ""));
            }
        }
    }

    /** Bir controller'ın eski eşlemelerini kaldırır, sınıfın güncel hâlinden yeniden çıkarır. */
    private int remap(Object mapping, String beanName, Class<?> userClass) {
        try {
            if (!Boolean.TRUE.equals(Reflect.call(mapping, "isHandler", userClass))) return 0;
            Map<?, ?> registered = (Map<?, ?>) Reflect.call(mapping, "getHandlerMethods");
            for (Map.Entry<?, ?> e : new ArrayList<Map.Entry<?, ?>>(registered.entrySet())) {
                if (Reflect.call(e.getValue(), "getBeanType") == userClass) {
                    Reflect.call(mapping, "unregisterMapping", e.getKey());
                }
            }
            Reflect.call(mapping, "detectHandlerMethods", beanName);
            int count = 0;
            for (Object handlerMethod : ((Map<?, ?>) Reflect.call(mapping, "getHandlerMethods")).values()) {
                if (Reflect.call(handlerMethod, "getBeanType") == userClass) count++;
            }
            return count;
        } catch (Throwable t) {
            Log.warn("could not remap endpoints of " + beanName, t);
            return 0;
        }
    }

    // ---------------------------------------------------------------- yeni sınıflar

    /** Bileşen taraması kapsamındaki yeni {@code @Component} türevi sınıfları bean olarak kaydeder. */
    void registerNewClasses(List<String> classNames) throws Exception {
        if (classNames.isEmpty()) return;
        for (Object context : contexts()) {
            Object factory = Reflect.call(context, "getBeanFactory");
            ClassLoader cl = (ClassLoader) Reflect.call(factory, "getBeanClassLoader");
            Set<String> packages = basePackages(factory);
            List<Object> mappings = null;
            List<String> added = new ArrayList<String>();
            for (String className : classNames) {
                if (!inPackages(className, packages)) continue;
                Class<?> type;
                try {
                    type = Class.forName(className, false, cl);
                } catch (Throwable t) {
                    continue;
                }
                int mod = type.getModifiers();
                if (type.isInterface() || type.isAnnotation() || type.isEnum() || Modifier.isAbstract(mod)
                        || !isComponent(type)) continue;
                if (((String[]) Reflect.call(factory, "getBeanNamesForType", type, Boolean.TRUE, Boolean.FALSE)).length > 0) {
                    continue; // zaten bean
                }
                try {
                    Object definition = newInstance(cl, "org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition", type);
                    Object generator = newInstance(cl, "org.springframework.context.annotation.AnnotationBeanNameGenerator");
                    String name = (String) Reflect.call(generator, "generateBeanName", definition, factory);
                    if (Boolean.TRUE.equals(Reflect.call(factory, "containsBeanDefinition", name))) continue;
                    Reflect.call(factory, "registerBeanDefinition", name, definition);
                    Reflect.call(factory, "getBean", name);
                    if (mappings == null) mappings = handlerMappings(factory);
                    int endpoints = 0;
                    for (Object mapping : mappings) endpoints += remap(mapping, name, type);
                    added.add(name + (endpoints > 0 ? " (" + endpoints + " endpoint" + (endpoints == 1 ? "" : "s") + ")" : ""));
                    if (hasAnnotation(type, "org.springframework.context.annotation.Configuration")) {
                        Log.info("Spring: new @Configuration " + type.getSimpleName()
                                + " registered; its @Bean methods take effect after restart");
                    }
                } catch (Throwable t) {
                    Log.warn("could not register new bean " + type.getSimpleName(), t);
                }
            }
            if (!added.isEmpty()) Log.info("Spring: new bean" + (added.size() == 1 ? " " : "s ") + join(added));
        }
    }

    private Set<String> basePackages(Object factory) throws Exception {
        Set<String> cached = basePackages.get(factory);
        if (cached != null) return cached;
        Set<String> packages = new HashSet<String>();
        ClassLoader cl = (ClassLoader) Reflect.call(factory, "getBeanClassLoader");
        for (String annotation : new String[]{
                "org.springframework.context.annotation.ComponentScan",
                "org.springframework.boot.autoconfigure.SpringBootApplication"}) {
            Class<?> type;
            try {
                type = Class.forName(annotation, false, cl);
            } catch (Throwable t) {
                continue;
            }
            for (String name : (String[]) Reflect.call(factory, "getBeanNamesForAnnotation", type)) {
                Class<?> beanType = (Class<?>) Reflect.call(factory, "getType", name);
                if (beanType == null) continue;
                String n = userClass(beanType).getName();
                int dot = n.lastIndexOf('.');
                if (dot > 0) packages.add(n.substring(0, dot));
            }
        }
        basePackages.put(factory, packages);
        return packages;
    }

    private static boolean inPackages(String className, Set<String> packages) {
        for (String p : packages) if (className.startsWith(p + ".")) return true;
        return false;
    }

    private static boolean isComponent(Class<?> type) {
        return hasAnnotation(type, COMPONENT);
    }

    /** Doğrudan ya da meta-anotasyon olarak (ör. @RestController → @Controller → @Component). */
    private static boolean hasAnnotation(Class<?> type, String annotationName) {
        Set<Class<?>> seen = new HashSet<Class<?>>();
        for (Annotation a : type.getAnnotations()) {
            if (metaPresent(a.annotationType(), annotationName, seen, 0)) return true;
        }
        return false;
    }

    private static boolean metaPresent(Class<? extends Annotation> type, String name, Set<Class<?>> seen, int depth) {
        if (type.getName().equals(name)) return true;
        if (depth > 4 || !seen.add(type) || type.getName().startsWith("java.")) return false;
        for (Annotation a : type.getAnnotations()) {
            if (metaPresent(a.annotationType(), name, seen, depth + 1)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- bağlamlar ve önbellekler

    /** Etkin Spring bağlamları (ebeveynler dahil, tekrarsız). */
    List<Object> contexts() {
        Map<Object, Boolean> found = new IdentityHashMap<Object, Boolean>();
        for (Class<?> c : loaded.framework("org.springframework.boot.SpringApplication")) {
            Object hook = Reflect.getStatic(c, "shutdownHook");
            if (hook != null) addAll(found, Reflect.get(hook, "contexts"));
        }
        for (Class<?> c : loaded.framework("org.springframework.web.context.ContextLoader")) {
            Object perThread = Reflect.getStatic(c, "currentContextPerThread");
            if (perThread instanceof Map) addAll(found, ((Map<?, ?>) perThread).values());
            Object current = Reflect.getStatic(c, "currentContext");
            if (current != null) found.put(current, Boolean.TRUE);
        }
        for (Class<?> c : loaded.framework("org.springframework.context.support.LiveBeansView")) {
            addAll(found, Reflect.getStatic(c, "applicationContexts"));
        }
        List<Object> result = new ArrayList<Object>();
        for (Object context : new ArrayList<Object>(found.keySet())) {
            for (Object ctx = context; ctx != null; ctx = Reflect.tryCall(ctx, "getParent")) {
                if (!result.contains(ctx) && !Boolean.FALSE.equals(Reflect.tryCall(ctx, "isActive"))
                        && Reflect.hasMethod(ctx.getClass(), "getBeanFactory", 0)) {
                    result.add(ctx);
                }
            }
        }
        return result;
    }

    private static void addAll(Map<Object, Boolean> into, Object collection) {
        if (!(collection instanceof Collection)) return;
        Object[] items;
        synchronized (collection) {
            items = ((Collection<?>) collection).toArray();
        }
        for (Object o : items) if (o != null) into.put(o, Boolean.TRUE);
    }

    private void clearFrameworkCaches(Set<Class<?>> changed) {
        for (String entry : CACHE_CLEARERS) {
            int hash = entry.indexOf('#');
            for (Class<?> c : loaded.framework(entry.substring(0, hash))) {
                try {
                    Reflect.callStatic(c, entry.substring(hash + 1));
                } catch (Throwable ignored) {
                    // bu Spring sürümünde yok
                }
            }
        }
        Set<ClassLoader> loaders = Collections.newSetFromMap(new IdentityHashMap<ClassLoader, Boolean>());
        for (Class<?> c : changed) if (c.getClassLoader() != null) loaders.add(c.getClassLoader());
        for (Class<?> c : loaded.framework("org.springframework.beans.CachedIntrospectionResults")) {
            for (ClassLoader cl : loaders) {
                try {
                    Reflect.callStatic(c, "clearClassLoader", cl);
                } catch (Throwable ignored) {
                    // isteğe bağlı
                }
            }
        }
    }

    /** Enjeksiyon meta verisi sınıf kimliğine göre önbelleklenir; sınıf yerinde değiştiği için temizlenmeli. */
    private static void clearInjectionCaches(Object factory) {
        Object processors = Reflect.tryCall(factory, "getBeanPostProcessors");
        if (!(processors instanceof List)) return;
        for (Object processor : (List<?>) processors) {
            for (String field : INJECTION_CACHES) {
                Object cache = Reflect.get(processor, field);
                if (cache instanceof Map) ((Map<?, ?>) cache).clear();
                else if (cache instanceof Collection) ((Collection<?>) cache).clear();
            }
        }
    }

    private List<Object> handlerMappings(Object factory) throws Exception {
        List<Object> result = new ArrayList<Object>();
        ClassLoader cl = (ClassLoader) Reflect.call(factory, "getBeanClassLoader");
        for (String name : HANDLER_MAPPINGS) {
            Class<?> type;
            try {
                type = Class.forName(name, false, cl);
            } catch (Throwable t) {
                continue;
            }
            Map<?, ?> beans = (Map<?, ?>) Reflect.call(factory, "getBeansOfType", type, Boolean.FALSE, Boolean.FALSE);
            result.addAll(beans.values());
        }
        return result;
    }

    private static Object unwrap(Object bean) {
        Object current = bean;
        for (int i = 0; i < 4 && Reflect.implementsInterface(current.getClass(), "org.springframework.aop.framework.Advised"); i++) {
            Object source = Reflect.tryCall(current, "getTargetSource");
            Object target = source == null ? null : Reflect.tryCall(source, "getTarget");
            if (target == null || target == current) break;
            current = target;
        }
        return current;
    }

    static Class<?> userClass(Class<?> type) {
        Class<?> c = type;
        while (c.getName().contains("$$") && c.getSuperclass() != null && c.getSuperclass() != Object.class) {
            c = c.getSuperclass();
        }
        return c;
    }

    private static Object newInstance(ClassLoader cl, String className, Object... args) throws Exception {
        Class<?> type = Class.forName(className, false, cl);
        for (Constructor<?> c : type.getConstructors()) {
            Class<?>[] params = c.getParameterTypes();
            if (params.length != args.length) continue;
            boolean ok = true;
            for (int i = 0; i < params.length && ok; i++) ok = args[i] == null || params[i].isInstance(args[i]);
            if (ok) return c.newInstance(args);
        }
        throw new NoSuchMethodException(className + "<init>");
    }

    private static String join(List<String> items) {
        if (items.size() <= 4) {
            StringBuilder sb = new StringBuilder();
            for (String s : items) sb.append(sb.length() == 0 ? "" : ", ").append(s);
            return sb.toString();
        }
        return items.get(0) + ", " + items.get(1) + " +" + (items.size() - 2) + " more";
    }
}
