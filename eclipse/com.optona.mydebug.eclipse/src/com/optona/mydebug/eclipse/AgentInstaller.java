package com.optona.mydebug.eclipse;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.IVMInstall2;
import org.eclipse.jdt.launching.IVMInstallType;
import org.eclipse.jdt.launching.JavaRuntime;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Adds the MyDebug agent (and, when supported, -XX:+AllowEnhancedClassRedefinition) to the default VM arguments of
 * every installed JRE, so all Java Run and Debug launches get it. The agent is copied to the workspace state folder
 * first, so the path stays valid across plugin updates. Disabling removes exactly what was added.
 */
final class AgentInstaller {
    static final String ENHANCED = "-XX:+AllowEnhancedClassRedefinition";
    private static final String MARKER = "mydebug-agent.jar";
    private static final Map<String, Boolean> PROBES = new ConcurrentHashMap<>();

    private AgentInstaller() {}

    static synchronized void apply(boolean enabled, boolean enhanced) {
        try {
            String agentArg = enabled ? "-javaagent:" + quoteIfNeeded(copyAgent().toString()) : null;
            boolean changed = false;
            for (IVMInstallType type : JavaRuntime.getVMInstallTypes()) {
                for (IVMInstall vm : type.getVMInstalls()) {
                    if (vm instanceof IVMInstall2 vm2) changed |= update(vm, vm2, agentArg, enabled && enhanced);
                }
            }
            if (changed) JavaRuntime.saveVMConfiguration();
        } catch (Exception e) {
            log().log(new Status(Status.WARNING, Activator.ID, "MyDebug: could not update JRE VM arguments", e));
        }
    }

    private static boolean update(IVMInstall vm, IVMInstall2 vm2, String agentArg, boolean enhanced) {
        String current = vm2.getVMArgs() == null ? "" : vm2.getVMArgs();
        List<String> args = new ArrayList<>();
        for (String a : splitArgs(current)) {
            if (a.contains(MARKER) || a.equals(ENHANCED)) continue; // ours: rebuilt below
            args.add(a);
        }
        if (agentArg != null) {
            args.add(agentArg);
            if (enhanced && supportsEnhanced(vm)) args.add(ENHANCED);
        }
        String next = String.join(" ", args).trim();
        if (next.equals(current.trim())) return false;
        vm2.setVMArgs(next.isEmpty() ? null : next);
        return true;
    }

    /** Checks the JVM once by starting it with the flag; JVMs that reject it never get it. */
    private static boolean supportsEnhanced(IVMInstall vm) {
        File home = vm.getInstallLocation();
        if (home == null) return false;
        File java = new File(home, "bin/" + (Platform.getOS().equals(Platform.OS_WIN32) ? "java.exe" : "java"));
        return PROBES.computeIfAbsent(java.getAbsolutePath(), k -> {
            try {
                Process p = new ProcessBuilder(k, ENHANCED, "-version").redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
            } catch (Exception e) {
                return false;
            }
        });
    }

    private static Path copyAgent() throws Exception {
        Bundle bundle = FrameworkUtil.getBundle(AgentInstaller.class);
        URL url = FileLocator.find(bundle, new org.eclipse.core.runtime.Path("agent/" + MARKER), null);
        if (url == null) throw new IllegalStateException("bundled agent not found");
        Path target = Platform.getStateLocation(bundle).toFile().toPath().resolve(MARKER);
        try (InputStream in = url.openStream()) {
            byte[] bytes = in.readAllBytes();
            if (!Files.exists(target) || !Arrays.equals(Files.readAllBytes(target), bytes)) {
                Path tmp = Files.createTempFile(target.getParent(), "agent", ".tmp");
                Files.write(tmp, bytes);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return target;
    }

    private static String quoteIfNeeded(String path) {
        return path.contains(" ") ? "\"" + path + "\"" : path;
    }

    /** Splits on whitespace outside double quotes, keeping the quotes. */
    static List<String> splitArgs(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (char c : s.toCharArray()) {
            if (c == '"') quoted = !quoted;
            if (Character.isWhitespace(c) && !quoted) {
                if (cur.length() > 0) out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static ILog log() {
        return ILog.of(AgentInstaller.class);
    }
}
