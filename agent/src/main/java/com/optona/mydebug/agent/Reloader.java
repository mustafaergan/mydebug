package com.optona.mydebug.agent;

import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

/**
 * Tüm yeniden yükleme işini tek bir arka plan iş parçacığında sıraya koyar (kilit gerekmez):
 * değişen sınıfları toplu yükler, ardından Spring ve MyBatis'i günceller.
 */
final class Reloader {
    /** Sınıf yeniden tanımlamalarını tek çerçeve güncellemesinde toplama penceresi. */
    private static final long FLUSH_DELAY_MS = 30;

    private final Instrumentation inst;
    private final boolean redefineItself;
    private final ScheduledExecutorService executor;
    private final Set<Class<?>> redefined = Collections.newSetFromMap(new ConcurrentHashMap<Class<?>, Boolean>());
    private final Map<String, Long> lastCrc = new HashMap<String, Long>();
    private final LoadedClasses loaded;
    private final SpringReloader spring;
    private final MyBatisReloader mybatis;
    private volatile boolean flushScheduled;

    Reloader(Instrumentation inst, boolean redefineItself) {
        this.inst = inst;
        this.redefineItself = redefineItself;
        this.executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "mydebug-reloader");
                t.setDaemon(true);
                return t;
            }
        });
        this.loaded = new LoadedClasses(inst);
        this.spring = new SpringReloader(loaded);
        this.mybatis = new MyBatisReloader(spring);
    }

    /** Sınıf dönüştürücüden çağrılır (IDE HotSwap'ı ya da kendi yüklememiz); hızlı dönmeli. */
    void onRedefined(Class<?> type) {
        redefined.add(type);
        if (!flushScheduled) {
            flushScheduled = true;
            executor.schedule(new Runnable() {
                @Override
                public void run() {
                    flush();
                }
            }, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    /** Dosya izleyicisinden: değişen .class ve .xml dosyaları (dosya -> çıktı kökü). */
    void onFilesChanged(final Map<Path, Path> files) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                handleFiles(files);
            }
        });
    }

    private void handleFiles(Map<Path, Path> files) {
        loaded.newBatch();
        Map<String, Path> classFiles = new LinkedHashMap<String, Path>();
        List<Path> xmlFiles = new ArrayList<Path>();
        for (Map.Entry<Path, Path> e : files.entrySet()) {
            Path file = e.getKey();
            String name = file.getFileName().toString();
            if (name.endsWith(".class")) {
                String rel = e.getValue().relativize(file).toString().replace('\\', '/').replace('/', '.');
                classFiles.put(rel.substring(0, rel.length() - ".class".length()), file);
            } else {
                xmlFiles.add(file);
            }
        }
        try {
            List<String> notLoaded = redefineItself ? redefine(classFiles) : notLoaded(classFiles.keySet());
            flush(); // yeniden tanımlananlar için çerçeve güncellemesi
            spring.registerNewClasses(topLevel(notLoaded));
            for (Path xml : xmlFiles) mybatis.onXmlChanged(xml);
        } catch (Throwable t) {
            Log.warn("reload failed", t);
        }
    }

    private void flush() {
        flushScheduled = false;
        if (redefined.isEmpty()) return;
        Set<Class<?>> batch = new LinkedHashSet<Class<?>>(redefined);
        redefined.removeAll(batch);
        loaded.newBatch();
        try {
            spring.onClassesRedefined(batch);
        } catch (Throwable t) {
            Log.warn("Spring refresh failed", t);
        }
    }

    /** Yüklü sınıfları tek {@code redefineClasses} çağrısıyla yükler; yüklü olmayan (yeni) adları döner. */
    private List<String> redefine(Map<String, Path> classFiles) {
        List<String> notLoaded = new ArrayList<String>();
        if (classFiles.isEmpty()) return notLoaded;
        Map<String, List<Class<?>>> byName = loaded.byName(classFiles.keySet());
        List<ClassDefinition> defs = new ArrayList<ClassDefinition>();
        Map<ClassDefinition, String> defNames = new HashMap<ClassDefinition, String>();
        for (Map.Entry<String, Path> e : classFiles.entrySet()) {
            List<Class<?>> classes = byName.get(e.getKey());
            if (classes == null) {
                notLoaded.add(e.getKey());
                continue;
            }
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(e.getValue());
            } catch (IOException ex) {
                continue; // dosya silinmiş ya da hâlâ yazılıyor
            }
            CRC32 crc = new CRC32();
            crc.update(bytes);
            Long previous = lastCrc.put(e.getKey(), crc.getValue());
            if (previous != null && previous == crc.getValue()) continue; // içerik aynı
            for (Class<?> c : classes) {
                ClassDefinition d = new ClassDefinition(c, bytes);
                defs.add(d);
                defNames.put(d, e.getKey());
            }
        }
        if (defs.isEmpty()) return notLoaded;

        long start = System.nanoTime();
        List<String> failed = new ArrayList<String>();
        try {
            inst.redefineClasses(defs.toArray(new ClassDefinition[0]));
        } catch (Throwable all) {
            // Toplu yükleme tek bir sınıf yüzünden düştüyse kalanları tek tek yükle.
            for (ClassDefinition d : defs) {
                try {
                    inst.redefineClasses(d);
                } catch (Throwable one) {
                    String name = defNames.get(d);
                    lastCrc.remove(name);
                    failed.add(shortName(name) + " (" + reason(one) + ")");
                }
            }
        }
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        int ok = defs.size() - failed.size();
        if (ok > 0) Log.info("reloaded " + ok + " class" + (ok == 1 ? "" : "es") + " in " + ms + " ms");
        if (!failed.isEmpty()) Log.info("could not reload " + failed);
        return notLoaded;
    }

    private List<String> notLoaded(Set<String> names) {
        Map<String, List<Class<?>>> byName = loaded.byName(names);
        List<String> result = new ArrayList<String>();
        for (String n : names) if (!byName.containsKey(n)) result.add(n);
        return result;
    }

    private static List<String> topLevel(List<String> names) {
        List<String> result = new ArrayList<String>();
        for (String n : names) if (n.indexOf('$') < 0 && !n.endsWith("package-info") && !n.endsWith("module-info")) result.add(n);
        return result;
    }

    private static String reason(Throwable t) {
        if (t instanceof UnsupportedOperationException) {
            return "structural change: run on JetBrains Runtime to add/remove methods or fields";
        }
        return Log.describe(t);
    }

    private static String shortName(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }
}
