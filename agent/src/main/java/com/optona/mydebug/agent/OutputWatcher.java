package com.optona.mydebug.agent;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Derleme çıktı klasörlerini işletim sisteminin dosya bildirimleriyle izler (yoklama yok).
 * Windows'ta tek bir ağaç izleyicisi (FILE_TREE), diğer sistemlerde klasör başına kayıt kullanır.
 * Derleyici dosyaları yazarken gelen olay seli, kısa bir sessizlik penceresiyle tek pakete toplanır.
 */
final class OutputWatcher implements Runnable {
    private static final long QUIET_MS = 60;

    private final java.util.List<Path> roots;
    private final Reloader reloader;
    private final Map<WatchKey, Path[]> keys = new HashMap<WatchKey, Path[]>(); // key -> {izlenen klasör, kök}
    private WatchService service;
    private WatchEvent.Modifier fileTree;

    OutputWatcher(java.util.List<Path> roots, Reloader reloader) {
        this.roots = roots;
        this.reloader = reloader;
    }

    void start() throws IOException {
        service = FileSystems.getDefault().newWatchService();
        fileTree = fileTreeModifier();
        for (Path root : roots) register(root, root);
        Thread t = new Thread(this, "mydebug-watcher");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    @Override
    public void run() {
        try {
            while (true) {
                WatchKey key = service.take();
                Map<Path, Path> changed = new LinkedHashMap<Path, Path>(); // dosya -> kök
                collect(key, changed);
                WatchKey next;
                while ((next = service.poll(QUIET_MS, TimeUnit.MILLISECONDS)) != null) collect(next, changed);
                if (!changed.isEmpty()) reloader.onFilesChanged(changed);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ClosedWatchServiceException ignored) {
            // JVM kapanıyor
        } catch (Throwable t) {
            Log.warn("file watcher stopped", t);
        }
    }

    private void collect(WatchKey key, Map<Path, Path> changed) {
        Path[] info = keys.get(key);
        for (WatchEvent<?> event : key.pollEvents()) {
            if (info == null || event.kind() == StandardWatchEventKinds.OVERFLOW) continue;
            Path file = info[0].resolve((Path) event.context());
            if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && fileTree == null && Files.isDirectory(file)) {
                try {
                    register(file, info[1]);
                    // Yeni klasörün içine kayıttan önce yazılmış dosyaları da al.
                    for (Path p : Files.newDirectoryStream(file)) if (Files.isRegularFile(p)) changed.put(p, info[1]);
                } catch (IOException e) {
                    Log.debug("register " + file + " failed: " + e);
                }
                continue;
            }
            String name = file.getFileName().toString();
            if (name.endsWith(".class") || name.endsWith(".xml")) changed.put(file, info[1]);
        }
        key.reset();
    }

    private void register(Path dir, final Path root) throws IOException {
        WatchEvent.Kind<?>[] kinds = {StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY};
        if (fileTree != null) {
            keys.put(dir.register(service, kinds, fileTree), new Path[]{dir, root});
            return;
        }
        final WatchEvent.Kind<?>[] k = kinds;
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) throws IOException {
                keys.put(d.register(service, k), new Path[]{d, root});
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** com.sun.nio.file.ExtendedWatchEventModifier.FILE_TREE (yalnızca Windows); yoksa null. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static WatchEvent.Modifier fileTreeModifier() {
        if (!System.getProperty("os.name", "").toLowerCase().startsWith("windows")) return null;
        try {
            Class c = Class.forName("com.sun.nio.file.ExtendedWatchEventModifier");
            return (WatchEvent.Modifier) Enum.valueOf(c, "FILE_TREE");
        } catch (Throwable t) {
            return null;
        }
    }
}
