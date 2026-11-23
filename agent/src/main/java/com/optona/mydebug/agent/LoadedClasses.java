package com.optona.mydebug.agent;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Yüklü sınıfları ada göre bulur. Çerçeve sınıfları (Spring, MyBatis) bir kez bulunduktan sonra
 * önbellekte kalır; eksik olanlar için tüm sınıf listesi her toplu işlemde en fazla bir kez taranır.
 */
final class LoadedClasses {
    private final Instrumentation inst;
    private final Map<String, List<Class<?>>> cache = new HashMap<String, List<Class<?>>>();
    private long scannedInBatch = -1;
    private long batch;

    LoadedClasses(Instrumentation inst) {
        this.inst = inst;
    }

    /** Yeni bir toplu işlem başlar: eksik sınıflar için yeniden tarama izni verir. */
    void newBatch() {
        batch++;
    }

    List<Class<?>> framework(String name) {
        List<Class<?>> found = cache.get(name);
        if ((found == null || found.isEmpty()) && scannedInBatch != batch) {
            scannedInBatch = batch;
            rescanFramework();
            found = cache.get(name);
        }
        return found == null ? Collections.<Class<?>>emptyList() : found;
    }

    private void rescanFramework() {
        for (Class<?> c : inst.getAllLoadedClasses()) {
            String n = c.getName();
            if (n.startsWith("org.springframework.") || n.startsWith("org.apache.ibatis.")) {
                List<Class<?>> list = cache.get(n);
                if (list == null) cache.put(n, list = new ArrayList<Class<?>>(1));
                if (!list.contains(c)) list.add(c);
            }
        }
    }

    /** Verilen adlardaki yüklü uygulama sınıfları (tek tarama). */
    Map<String, List<Class<?>>> byName(Collection<String> names) {
        Map<String, List<Class<?>>> result = new HashMap<String, List<Class<?>>>();
        if (names.isEmpty()) return result;
        java.util.Set<String> wanted = new java.util.HashSet<String>(names);
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c.isArray() || c.isPrimitive()) continue;
            String n = c.getName();
            if (!wanted.contains(n) || c.getClassLoader() == null) continue;
            List<Class<?>> list = result.get(n);
            if (list == null) result.put(n, list = new ArrayList<Class<?>>(1));
            list.add(c);
        }
        return result;
    }
}
