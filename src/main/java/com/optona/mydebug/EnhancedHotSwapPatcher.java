package com.optona.mydebug;

import com.intellij.execution.Executor;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.ParametersList;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.runners.JavaProgramPatcher;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.projectRoots.Sdk;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Run ve Debug ile başlatılan Java süreçlerini hazırlar:
 * <ul>
 *   <li>JVM destekliyorsa {@code -XX:+AllowEnhancedClassRedefinition}: metot/alan ekleme-silme, imza ve hiyerarşi değişiklikleri,</li>
 *   <li>gömülü MyDebug ajanı: Spring bean/endpoint ve MyBatis mapper yenileme; Run modunda sınıfları da ajan yükler.</li>
 * </ul>
 */
public final class EnhancedHotSwapPatcher extends JavaProgramPatcher {
    private static final Logger LOG = Logger.getInstance(EnhancedHotSwapPatcher.class);
    static final String ENHANCED = "-XX:+AllowEnhancedClassRedefinition";
    static final String PLUGIN_ID = "com.optona.mydebug";

    @Override
    public void patchJavaParameters(Executor executor, RunProfile configuration, JavaParameters javaParameters) {
        boolean debug = DefaultDebugExecutor.EXECUTOR_ID.equals(executor.getId());
        boolean run = DefaultRunExecutor.EXECUTOR_ID.equals(executor.getId());
        if (!debug && !run) return; // coverage, profiler vb. dokunma
        AgentRuns.unmark(configuration);
        MyDebugSettings.State s = MyDebugSettings.settings();
        ParametersList vm = javaParameters.getVMParametersList();

        if (s.patchEnhancedRedefinition) {
            String home = jdkHome(javaParameters);
            if (home != null && JvmCapabilities.supportsEnhancedRedefinition(home)
                    && vm.getParameters().stream().noneMatch(p -> p.contains("AllowEnhancedClassRedefinition"))) {
                vm.add(ENHANCED);
            }
        }

        if (s.frameworkReload && vm.getParameters().stream().noneMatch(p -> p.contains("mydebug-agent"))) {
            Path agent = agentJar();
            Path dirs = agent == null ? null : writeOutputDirs(javaParameters);
            if (agent != null && dirs != null) {
                vm.add("-javaagent:" + agent + "=mode=" + (debug ? "debug" : "run") + "|dirs=" + dirs);
                if (run) AgentRuns.mark(configuration);
            }
        }
    }

    private static String jdkHome(JavaParameters parameters) {
        try {
            Sdk jdk = parameters.getJdk();
            return jdk == null ? null : jdk.getHomePath();
        } catch (Exception e) {
            return null;
        }
    }

    static Path agentJar() {
        IdeaPluginDescriptor plugin = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID));
        if (plugin == null) return null;
        Path jar = plugin.getPluginPath().resolve("agent").resolve("mydebug-agent.jar");
        return Files.isRegularFile(jar) ? jar : null;
    }

    /** Sınıf yolundaki derleme çıktı klasörlerini geçici bir dosyaya yazar (komut satırı uzunluğu sınırına takılmaz). */
    private static Path writeOutputDirs(JavaParameters parameters) {
        try {
            List<String> dirs = new ArrayList<>();
            for (String entry : parameters.getClassPath().getPathList()) {
                if (new File(entry).isDirectory()) dirs.add(entry);
            }
            if (dirs.isEmpty()) return null;
            Path file = Files.createTempFile("mydebug-dirs", ".txt");
            Files.write(file, dirs, StandardCharsets.UTF_8);
            file.toFile().deleteOnExit();
            return file;
        } catch (Exception e) {
            LOG.warn("MyDebug: cannot write output dirs", e);
            return null;
        }
    }
}
