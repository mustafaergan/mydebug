package com.optona.mydebug;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Editörden kaydedilen (Ctrl+S ya da otomatik kaydetme) ve projenin kaynak köklerinde
 * (src, resources) duran dosyaları yakalayıp {@link HotReloadService}'e iletir.
 */
public final class SaveListener implements BulkFileListener {
    private final Project project;

    public SaveListener(Project project) {
        this.project = project;
    }

    @Override
    public void after(@NotNull List<? extends VFileEvent> events) {
        if (project.isDisposed() || !MyDebugSettings.settings().enabled) return;
        ProjectFileIndex index = ProjectFileIndex.getInstance(project);
        List<VirtualFile> saved = new ArrayList<>();
        for (VFileEvent event : events) {
            if (!(event instanceof VFileContentChangeEvent change) || !change.isFromSave()) continue;
            VirtualFile file = change.getFile();
            if (file.isValid() && !file.isDirectory() && index.isInSourceContent(file)) {
                saved.add(file);
            }
        }
        if (!saved.isEmpty()) {
            HotReloadService.getInstance(project).onSaved(saved);
        }
    }
}
