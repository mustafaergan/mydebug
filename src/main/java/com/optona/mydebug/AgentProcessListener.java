package com.optona.mydebug;

import com.intellij.execution.ExecutionListener;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/** Ajanla Run modunda çalışan süreçleri izler; kaydedince yalnızca derleme yapılması için. */
public final class AgentProcessListener implements ExecutionListener {
    private final Project project;

    public AgentProcessListener(Project project) {
        this.project = project;
    }

    @Override
    public void processStarted(@NotNull String executorId, @NotNull ExecutionEnvironment env, @NotNull ProcessHandler handler) {
        if (AgentRuns.isMarked(env.getRunProfile())) {
            HotReloadService.getInstance(project).agentProcessStarted(handler);
        }
    }

    @Override
    public void processTerminated(@NotNull String executorId, @NotNull ExecutionEnvironment env,
                                  @NotNull ProcessHandler handler, int exitCode) {
        HotReloadService.getInstance(project).agentProcessTerminated(handler);
    }
}
