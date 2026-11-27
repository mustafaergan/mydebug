package com.optona.mydebug;

import com.intellij.openapi.diagnostic.Logger;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Bir JDK'nın gelişmiş HotSwap yeteneklerini, JVM'i ilgili bayrakla bir kez çalıştırarak yoklar.
 * İsim tahmini yapılmaz: JBR, DCEVM/Trava gibi destekleyen her JVM doğru algılanır,
 * desteklemeyene bayrak eklenmez (aksi halde JVM "Unrecognized VM option" ile açılmazdı).
 */
final class JvmCapabilities {
    private static final Logger LOG = Logger.getInstance(JvmCapabilities.class);
    private static final Map<String, Boolean> CACHE = new ConcurrentHashMap<>();

    private JvmCapabilities() {}

    static boolean supportsEnhancedRedefinition(String jdkHome) {
        return probe(jdkHome, "-XX:+AllowEnhancedClassRedefinition");
    }

    static boolean supportsHotswapAgentOption(String jdkHome) {
        // "external" modu jar'ın -javaagent ile verilmesini bekler; -version ile sorunsuz açılır.
        return probe(jdkHome, "-XX:HotswapAgent=external");
    }

    private static boolean probe(String jdkHome, String flag) {
        if (jdkHome == null) return false;
        return CACHE.computeIfAbsent(jdkHome + "|" + flag, k -> runProbe(jdkHome, flag));
    }

    private static boolean runProbe(String jdkHome, String flag) {
        File java = new File(jdkHome, "bin/" + (isWindows() ? "java.exe" : "java"));
        if (!java.isFile()) return false;
        try {
            Process p = new ProcessBuilder(List.of(java.getPath(), flag, "-version"))
                    .redirectErrorStream(true)
                    .start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            boolean ok = p.exitValue() == 0;
            LOG.info("MyDebug probe " + flag + " @ " + jdkHome + " -> " + ok
                    + (ok ? "" : " (" + new String(out, StandardCharsets.UTF_8).trim() + ")"));
            return ok;
        } catch (Exception e) {
            LOG.warn("MyDebug probe failed for " + jdkHome, e);
            return false;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
    }
}
