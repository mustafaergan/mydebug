package com.optona.mydebug;

import com.intellij.execution.Executor;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.ParametersList;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.runners.JavaProgramPatcher;
import com.intellij.openapi.projectRoots.Sdk;

import java.io.File;

/**
 * Debug ile başlatılan Java süreçlerine, JVM destekliyorsa yapısal yenileme bayraklarını ekler:
 * <ul>
 *   <li>{@code -XX:+AllowEnhancedClassRedefinition}: metot/alan ekleme-silme, imza ve hiyerarşi değişiklikleri</li>
 *   <li>{@code -XX:HotswapAgent=external -javaagent:...}: Spring, Hibernate vb. çerçeve yeniden yüklemeleri</li>
 * </ul>
 */
public final class EnhancedHotSwapPatcher extends JavaProgramPatcher {

    static final String ENHANCED = "-XX:+AllowEnhancedClassRedefinition";

    @Override
    public void patchJavaParameters(Executor executor, RunProfile configuration, JavaParameters javaParameters) {
        if (!DefaultDebugExecutor.EXECUTOR_ID.equals(executor.getId())) return;
        MyDebugSettings.State s = MyDebugSettings.settings();
        if (!s.patchEnhancedRedefinition) return;

        String home;
        try {
            Sdk jdk = javaParameters.getJdk();
            home = jdk == null ? null : jdk.getHomePath();
        } catch (Exception e) {
            return;
        }
        if (home == null || !JvmCapabilities.supportsEnhancedRedefinition(home)) return;

        ParametersList vm = javaParameters.getVMParametersList();
        if (vm.getParameters().stream().noneMatch(p -> p.contains("AllowEnhancedClassRedefinition"))) {
            vm.add(ENHANCED);
        }

        String agent = s.hotswapAgentJar == null ? "" : s.hotswapAgentJar.trim();
        if (!agent.isEmpty() && new File(agent).isFile()
                && vm.getParameters().stream().noneMatch(p -> p.startsWith("-XX:HotswapAgent") || p.contains("hotswap-agent"))
                && JvmCapabilities.supportsHotswapAgentOption(home)) {
            vm.add("-XX:HotswapAgent=external");
            vm.add("-javaagent:" + agent);
        }
    }
}
