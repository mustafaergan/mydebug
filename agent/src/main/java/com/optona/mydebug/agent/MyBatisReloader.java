package com.optona.mydebug.agent;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Değişen MyBatis mapper XML dosyalarını çalışan {@code Configuration}'a yeniden yükler.
 * Yalnızca o namespace'e ait ifadeler, sonuç eşlemeleri, parametre eşlemeleri, anahtar üreticileri,
 * SQL parçaları ve önbellek kaldırılıp XML yeniden ayrıştırılır; diğer mapper'lara dokunulmaz.
 */
final class MyBatisReloader {
    private static final Pattern NAMESPACE = Pattern.compile("<mapper[^>]*\\snamespace\\s*=\\s*\"([^\"]+)\"");
    private static final String[] NAMESPACED_MAPS = {
            "mappedStatements", "resultMaps", "parameterMaps", "keyGenerators", "sqlFragments"
    };
    private static final boolean WINDOWS = File.separatorChar == '\\';

    private final SpringReloader spring;

    MyBatisReloader(SpringReloader spring) {
        this.spring = spring;
    }

    void onXmlChanged(Path file) {
        byte[] xml;
        try {
            xml = Files.readAllBytes(file);
        } catch (Exception e) {
            return;
        }
        String head = new String(xml, 0, Math.min(xml.length, 4096), Charset.forName("UTF-8"));
        if (!head.contains("<mapper")) return;
        Matcher m = NAMESPACE.matcher(head);
        if (!m.find()) return;
        String namespace = m.group(1);

        for (Object configuration : configurations()) {
            Object resources = Reflect.get(configuration, "loadedResources");
            if (!(resources instanceof Set)) continue;
            String resource = null;
            for (Object r : ((Set<?>) resources).toArray()) {
                if (r instanceof String && refersTo((String) r, file)) {
                    resource = (String) r;
                    break;
                }
            }
            if (resource == null) continue;
            try {
                long start = System.nanoTime();
                int removed = reload(configuration, (Set<?>) resources, resource, namespace, xml);
                long ms = (System.nanoTime() - start) / 1000000L;
                Log.info("MyBatis: reloaded " + file.getFileName() + " (" + removed + " statements) in " + ms + " ms");
            } catch (Throwable t) {
                Log.warn("MyBatis: could not reload " + file.getFileName(), t);
            }
        }
    }

    private int reload(Object configuration, Set<?> resources, String resource, String namespace, byte[] xml)
            throws Exception {
        int statements = 0;
        synchronized (configuration) {
            for (String field : NAMESPACED_MAPS) {
                Object map = Reflect.get(configuration, field);
                if (!(map instanceof Map)) continue;
                int n = removeNamespace((Map<?, ?>) map, namespace);
                if (field.equals("mappedStatements")) statements = n;
            }
            Object caches = Reflect.get(configuration, "caches");
            if (caches instanceof Map) ((Map<?, ?>) caches).remove(namespace);
            resources.remove(resource);

            ClassLoader cl = configuration.getClass().getClassLoader();
            Class<?> builderType = Class.forName("org.apache.ibatis.builder.xml.XMLMapperBuilder", false, cl);
            Object fragments = Reflect.call(configuration, "getSqlFragments");
            Object builder = null;
            for (java.lang.reflect.Constructor<?> c : builderType.getConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 4 && p[0] == InputStream.class && p[2] == String.class && p[3] == Map.class) {
                    builder = c.newInstance(new ByteArrayInputStream(xml), configuration, resource, fragments);
                    break;
                }
            }
            if (builder == null) throw new NoSuchMethodException("XMLMapperBuilder(InputStream, Configuration, String, Map)");
            Reflect.call(builder, "parse");
        }
        return statements;
    }

    /** "ns.id" anahtarlarını ve onların kısa ("id") kopyalarını kaldırır. */
    private static int removeNamespace(Map<?, ?> map, String namespace) {
        String prefix = namespace + ".";
        List<Object> keys = new ArrayList<Object>();
        for (Object k : map.keySet().toArray()) {
            if (k instanceof String && ((String) k).startsWith(prefix)) keys.add(k);
        }
        int removed = 0;
        for (Object k : keys) {
            Object value = map.remove(k);
            removed++;
            String shortKey = ((String) k).substring(prefix.length());
            Object shortValue = map.get(shortKey);
            if (shortValue == value || (shortValue != null && shortValue.getClass().getSimpleName().equals("Ambiguity"))) {
                map.remove(shortKey);
            }
        }
        return removed;
    }

    /** Spring bağlamlarındaki SqlSessionFactory bean'lerinin Configuration nesneleri. */
    private List<Object> configurations() {
        Map<Object, Boolean> result = new IdentityHashMap<Object, Boolean>();
        for (Object context : spring.contexts()) {
            try {
                Object factory = Reflect.call(context, "getBeanFactory");
                ClassLoader cl = (ClassLoader) Reflect.call(factory, "getBeanClassLoader");
                Class<?> type;
                try {
                    type = Class.forName("org.apache.ibatis.session.SqlSessionFactory", false, cl);
                } catch (Throwable t) {
                    continue;
                }
                Map<?, ?> beans = (Map<?, ?>) Reflect.call(factory, "getBeansOfType", type, Boolean.FALSE, Boolean.FALSE);
                for (Object sessionFactory : beans.values()) {
                    Object configuration = Reflect.tryCall(sessionFactory, "getConfiguration");
                    if (configuration != null) result.put(configuration, Boolean.TRUE);
                }
            } catch (Throwable t) {
                Log.debug("MyBatis lookup failed: " + Log.describe(t));
            }
        }
        return new ArrayList<Object>(result.keySet());
    }

    /** loadedResources girdisi bu dosyayı mı gösteriyor? ("file [..]", "class path resource [..]", "URL [..]", "a/b.xml") */
    static boolean refersTo(String resource, Path file) {
        String inner = resource;
        int open = resource.indexOf('[');
        int close = resource.lastIndexOf(']');
        if (open >= 0 && close > open) inner = resource.substring(open + 1, close);
        if (!inner.endsWith(".xml")) return false;
        if (inner.startsWith("file:")) {
            try {
                inner = new File(new URI(inner)).getPath();
            } catch (Exception ignored) {
                // olduğu gibi karşılaştır
            }
        }
        String target = normalize(file.toAbsolutePath().normalize().toString());
        String candidate = normalize(inner);
        boolean absolute = resource.startsWith("file [") || new File(inner).isAbsolute();
        return absolute ? target.equals(candidate) : target.endsWith("/" + trimSlash(candidate));
    }

    private static String normalize(String path) {
        String p = path.replace('\\', '/');
        return WINDOWS ? p.toLowerCase() : p;
    }

    private static String trimSlash(String p) {
        return p.startsWith("/") ? p.substring(1) : p;
    }

    @SuppressWarnings("unused")
    private static boolean isEmpty(Collection<?> c) {
        return c == null || c.isEmpty();
    }
}
