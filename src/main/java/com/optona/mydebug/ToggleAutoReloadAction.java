package com.optona.mydebug;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareToggleAction;
import org.jetbrains.annotations.NotNull;

/** Run menüsü ve debug araç çubuğu: kaydedince otomatik HotSwap aç/kapat. */
public final class ToggleAutoReloadAction extends DumbAwareToggleAction {
    @Override
    public boolean isSelected(@NotNull AnActionEvent e) {
        return MyDebugSettings.settings().enabled;
    }

    @Override
    public void setSelected(@NotNull AnActionEvent e, boolean state) {
        MyDebugSettings.settings().enabled = state;
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
