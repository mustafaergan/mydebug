package com.optona.mydebug.agent;

import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;

/**
 * MyDebug çalışma zamanı ajanı.
 * <p>Argümanlar ({@code |} ile ayrılır): {@code mode=run|debug}, {@code dirs=<derleme çıktı klasörlerini listeleyen dosya>}.
 * <ul>
 *   <li><b>run</b>: çıktı klasörlerindeki değişen .class dosyalarını ajan kendisi yükler (debugger gerekmez).</li>
 *   <li><b>debug</b>: sınıfları IDE HotSwap ile yükler; ajan yalnızca çerçeveleri (Spring, MyBatis) yeniler.</li>
 * </ul>
 */
public final class MyDebugAgent {
    private MyDebugAgent() {}

    public static void premain(String args, Instrumentation inst) {
        start(args, inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        start(args, inst);
    }

    private static void start(String args, Instrumentation inst) {
        try {
            String mode = "run";
            String dirsFile = null;
            if (args != null) {
                for (String part : args.split("\\|")) {
                    int eq = part.indexOf('=');
                    if (eq <= 0) continue;
                    String key = part.substring(0, eq).trim();
                    String value = part.substring(eq + 1).trim();
                    if (key.equals("mode")) mode = value;
                    else if (key.equals("dirs")) dirsFile = value;
                }
            }
            boolean redefineItself = !"debug".equals(mode) && inst.isRedefineClassesSupported();
            final Reloader reloader = new Reloader(inst, redefineItself);
            inst.addTransformer(new ClassFileTransformer() {
                @Override
                public byte[] transform(ClassLoader loader, String name, Class<?> redefined,
                                        ProtectionDomain domain, byte[] bytes) {
                    if (redefined != null) reloader.onRedefined(redefined);
                    return null;
                }
            });
            List<Path> dirs = outputDirs(dirsFile);
            if (!dirs.isEmpty()) new OutputWatcher(dirs, reloader).start();
            Log.info("agent ready (" + mode + ", watching " + dirs.size() + " output folder"
                    + (dirs.size() == 1 ? "" : "s") + ")");
        } catch (Throwable t) {
            // Ajan hiçbir koşulda uygulamanın açılmasını engellememeli.
            Log.warn("agent disabled", t);
        }
    }

    private static List<Path> outputDirs(String dirsFile) {
        List<String> raw = new ArrayList<String>();
        if (dirsFile != null) {
            try {
                raw.addAll(Files.readAllLines(Paths.get(dirsFile), Charset.forName("UTF-8")));
            } catch (Exception e) {
                Log.warn("cannot read " + dirsFile, e);
            }
        }
        if (raw.isEmpty()) {
            for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
                raw.add(entry);
                raw.addAll(manifestClassPath(entry));
            }
        }
        List<Path> dirs = new ArrayList<Path>();
        for (String entry : raw) {
            entry = entry.trim();
            if (entry.isEmpty()) continue;
            try {
                Path p = Paths.get(entry).toAbsolutePath().normalize();
                if (Files.isDirectory(p) && !dirs.contains(p)) dirs.add(p);
            } catch (Exception ignored) {
                // geçersiz yol
            }
        }
        return dirs;
    }

    /**
     * Uzun komut satırlarında IDE'ler (VS Code, Eclipse, IntelliJ) sınıf yolunu yalnızca manifest içeren bir jar'a koyar;
     * onun Class-Path girdilerini (URL biçiminde) dosya yollarına çevirir.
     */
    private static List<String> manifestClassPath(String entry) {
        List<String> result = new ArrayList<String>();
        if (!entry.endsWith(".jar")) return result;
        java.util.jar.JarFile jar = null;
        try {
            jar = new java.util.jar.JarFile(entry);
            java.util.jar.Manifest mf = jar.getManifest();
            String cp = mf == null ? null : mf.getMainAttributes().getValue("Class-Path");
            if (cp == null) return result;
            File base = new File(entry).getAbsoluteFile().getParentFile();
            for (String item : cp.trim().split("\\s+")) {
                try {
                    java.net.URI uri = new java.net.URI(item);
                    result.add(uri.isAbsolute() ? new File(uri).getPath() : new File(base, item).getPath());
                } catch (Exception e) {
                    result.add(new File(base, item).getPath());
                }
            }
        } catch (Exception ignored) {
            // jar okunamadı
        } finally {
            if (jar != null) {
                try {
                    jar.close();
                } catch (Exception ignored) {
                    // önemsiz
                }
            }
        }
        return result;
    }
}
