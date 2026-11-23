package com.optona.mydebug.agent;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Çerçeve sınıflarına derleme zamanı bağımlılığı olmadan erişim. Bulunan metot ve alanlar önbelleğe alınır;
 * aynı çağrı ikinci kez yansıma araması yapmaz.
 */
final class Reflect {
    private static final Map<String, Method> METHODS = new ConcurrentHashMap<String, Method>();
    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<String, Field>();
    private static final Field MISSING_FIELD;
    private static final Map<Class<?>, Class<?>> BOXES = new HashMap<Class<?>, Class<?>>();

    static {
        BOXES.put(boolean.class, Boolean.class);
        BOXES.put(int.class, Integer.class);
        BOXES.put(long.class, Long.class);
        BOXES.put(short.class, Short.class);
        BOXES.put(byte.class, Byte.class);
        BOXES.put(char.class, Character.class);
        BOXES.put(double.class, Double.class);
        BOXES.put(float.class, Float.class);
        try {
            MISSING_FIELD = Reflect.class.getDeclaredField("MISSING_FIELD");
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Reflect() {}

    static Object call(Object target, String name, Object... args) throws Exception {
        return invoke(find(target.getClass(), name, args), target, args);
    }

    static Object callStatic(Class<?> type, String name, Object... args) throws Exception {
        return invoke(find(type, name, args), null, args);
    }

    /** Metot yoksa ya da hata verirse null döner; isteğe bağlı çağrılar için. */
    static Object tryCall(Object target, String name, Object... args) {
        try {
            return call(target, name, args);
        } catch (Throwable t) {
            Log.debug("call " + name + " failed: " + Log.describe(t));
            return null;
        }
    }

    static boolean hasMethod(Class<?> type, String name, int argCount) {
        for (Class<?> k = type; k != null; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == argCount) return true;
            }
        }
        return false;
    }

    static Object get(Object target, String name) {
        Field f = field(target.getClass(), name);
        if (f == null) return null;
        try {
            return f.get(target);
        } catch (Exception e) {
            return null;
        }
    }

    static Object getStatic(Class<?> type, String name) {
        Field f = field(type, name);
        if (f == null || !Modifier.isStatic(f.getModifiers())) return null;
        try {
            return f.get(null);
        } catch (Exception e) {
            return null;
        }
    }

    static Field field(Class<?> type, String name) {
        String key = id(type) + "." + name;
        Field cached = FIELDS.get(key);
        if (cached != null) return cached == MISSING_FIELD ? null : cached;
        Field found = null;
        for (Class<?> k = type; k != null && found == null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                found = f;
            } catch (NoSuchFieldException ignored) {
                // üst sınıfa bak
            } catch (RuntimeException e) {
                break; // erişilemiyor (modül sınırı)
            }
        }
        FIELDS.put(key, found == null ? MISSING_FIELD : found);
        return found;
    }

    static boolean implementsInterface(Class<?> type, String interfaceName) {
        for (Class<?> k = type; k != null; k = k.getSuperclass()) {
            for (Class<?> i : k.getInterfaces()) {
                if (i.getName().equals(interfaceName) || implementsInterface(i, interfaceName)) return true;
            }
        }
        return false;
    }

    static boolean extendsClass(Class<?> type, String className) {
        for (Class<?> k = type; k != null; k = k.getSuperclass()) {
            if (k.getName().equals(className)) return true;
        }
        return false;
    }

    private static Object invoke(Method m, Object target, Object[] args) throws Exception {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw e;
        }
    }

    private static Method find(Class<?> type, String name, Object[] args) throws NoSuchMethodException {
        StringBuilder key = new StringBuilder(id(type)).append('#').append(name);
        for (Object a : args) key.append(',').append(a == null ? "null" : a.getClass().getName());
        String k = key.toString();
        Method cached = METHODS.get(k);
        if (cached != null) return cached;

        Method found = null;
        for (Method m : type.getMethods()) {
            if (matches(m, name, args)) {
                found = m;
                break;
            }
        }
        for (Class<?> c = type; found == null && c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (matches(m, name, args)) {
                    found = m;
                    break;
                }
            }
        }
        if (found == null) throw new NoSuchMethodException(type.getName() + "." + name);
        try {
            found.setAccessible(true);
        } catch (RuntimeException ignored) {
            // public metotlar erişilebilir kalır
        }
        METHODS.put(k, found);
        return found;
    }

    private static boolean matches(Method m, String name, Object[] args) {
        if (!m.getName().equals(name)) return false;
        Class<?>[] params = m.getParameterTypes();
        if (params.length != args.length) return false;
        for (int i = 0; i < params.length; i++) {
            Class<?> p = params[i].isPrimitive() ? BOXES.get(params[i]) : params[i];
            if (args[i] == null ? params[i].isPrimitive() : !p.isInstance(args[i])) return false;
        }
        return true;
    }

    private static String id(Class<?> type) {
        return type.getName() + "@" + System.identityHashCode(type);
    }
}
