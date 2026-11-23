package com.optona.mydebug;

import com.intellij.debugger.DebuggerManagerEx;
import com.intellij.debugger.impl.DebuggerSession;
import com.intellij.debugger.ui.HotSwapStatusListener;
import com.intellij.debugger.ui.HotSwapUI;
import com.intellij.debugger.ui.HotSwapUIImpl;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.notification.NotificationAction;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Kaydetme → (debounce) → derleme → HotSwap akışını yöneten proje servisi.
 * Aynı anda tek bir yükleme çalışır; o sırada gelen kaydetmeler biriktirilip hemen ardından işlenir.
 */
@Service(Service.Level.PROJECT)
public final class HotReloadService implements Disposable {
    private static final Logger LOG = Logger.getInstance(HotReloadService.class);
    private static final Set<String> CODE_EXTENSIONS = Set.of("java", "kt", "groovy", "scala");

    private final Project project;
    private final Object lock = new Object();
    private final Set<VirtualFile> pending = new LinkedHashSet<>();
    private ScheduledFuture<?> scheduled;
    private boolean running;
    private volatile boolean disposed;

    public HotReloadService(Project project) {
        this.project = project;
    }

    public static HotReloadService getInstance(Project project) {
        return project.getService(HotReloadService.class);
    }

    /** Kaydedilen dosyaları kuyruğa alır; aktif, HotSwap destekli bir debug oturumu yoksa hiçbir şey yapmaz. */
    void onSaved(Collection<VirtualFile> files) {
        if (disposed || hotSwappableSessions().isEmpty()) return;
        synchronized (lock) {
            pending.addAll(files);
            if (!running) scheduleLocked();
        }
    }

    /** Elle tetikleme (eylem): bekleyen bir şey olmasa da artımlı derleme + yükleme yapar. */
    void reloadNow() {
        List<DebuggerSession> sessions = hotSwappableSessions();
        if (sessions.isEmpty()) {
            notify(NotificationType.INFORMATION, "No active Java debug session.");
            return;
        }
        synchronized (lock) {
            if (running) return;
            running = true;
        }
        long start = System.nanoTime();
        ApplicationManager.getApplication().invokeLater(
                () -> reloadSessions(sessions, 0, true, start, "manual reload"), project.getDisposed());
    }

    private void scheduleLocked() {
        if (scheduled != null) scheduled.cancel(false);
        int delay = Math.max(0, MyDebugSettings.settings().debounceMs);
        scheduled = AppExecutorUtil.getAppScheduledExecutorService().schedule(this::fire, delay, TimeUnit.MILLISECONDS);
    }

    private void fire() {
        List<VirtualFile> files;
        synchronized (lock) {
            scheduled = null;
            if (disposed || running || pending.isEmpty()) return;
            files = new ArrayList<>(pending);
            pending.clear();
            running = true;
        }
        ApplicationManager.getApplication().invokeLater(() -> start(files), project.getDisposed());
    }

    private void start(List<VirtualFile> files) {
        List<VirtualFile> valid = files.stream().filter(VirtualFile::isValid).toList();
        List<DebuggerSession> sessions = hotSwappableSessions();
        if (valid.isEmpty() || sessions.isEmpty()) {
            finished();
            return;
        }
        long startNanos = System.nanoTime();
        String label = describe(valid);

        List<VirtualFile> code = valid.stream()
                .filter(f -> CODE_EXTENSIONS.contains(String.valueOf(f.getExtension()).toLowerCase(Locale.ROOT)))
                .toList();
        boolean onlyCode = code.size() == valid.size();

        if (MyDebugSettings.settings().compileOnlySavedFiles && onlyCode) {
            // Hızlı yol: yalnızca kaydedilen dosyalar derlenir; IDE derleme bitince oturumlara kendisi yükler.
            HotSwapUI ui = HotSwapUI.getInstance(project);
            for (DebuggerSession session : sessions) {
                ui.compileAndReload(session, code.toArray(VirtualFile.EMPTY_ARRAY));
            }
            showStatus("MyDebug: compiling and reloading " + label + "…");
            finished();
            return;
        }
        // Güvenli yol: artımlı proje derlemesi (bağımlı sınıflar da derlenir) + değişen sınıfların yüklenmesi.
        reloadSessions(sessions, 0, true, startNanos, label);
    }

    /** Oturumları sırayla yükler: ilki derlemeyi de yapar, kalanlar yalnızca yükler. */
    private void reloadSessions(List<DebuggerSession> sessions, int index, boolean compile, long startNanos, String label) {
        if (index >= sessions.size() || project.isDisposed()) {
            finished();
            return;
        }
        DebuggerSession session = sessions.get(index);
        HotSwapStatusListener listener = new HotSwapStatusListener() {
            @Override
            public void onSuccess(List<DebuggerSession> s) {
                if (MyDebugSettings.settings().notifyOnSuccess) {
                    long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                    showStatus("MyDebug: reloaded " + label + " · " + ms + " ms");
                }
                next();
            }

            @Override
            public void onNothingToReload(List<DebuggerSession> s) {
                showStatus("MyDebug: no bytecode changes to reload (" + label + ")");
                next();
            }

            @Override
            public void onFailure(List<DebuggerSession> s) {
                failureNotification(label);
                next();
            }

            @Override
            public void onCancel(List<DebuggerSession> s) {
                next();
            }

            private void next() {
                ApplicationManager.getApplication().invokeLater(
                        () -> reloadSessions(sessions, index + 1, false, startNanos, label), project.getDisposed());
            }
        };
        try {
            HotSwapUI.getInstance(project).reloadChangedClasses(session, compile, listener);
        } catch (Throwable t) {
            LOG.warn("MyDebug reload failed", t);
            finished();
        }
    }

    private void finished() {
        synchronized (lock) {
            running = false;
            if (!disposed && !pending.isEmpty()) scheduleLocked();
        }
    }

    private List<DebuggerSession> hotSwappableSessions() {
        if (project.isDisposed()) return List.of();
        Collection<DebuggerSession> all = DebuggerManagerEx.getInstanceEx(project).getSessions();
        List<DebuggerSession> result = new ArrayList<>();
        for (DebuggerSession s : all) {
            if (HotSwapUIImpl.canHotSwap(s)) result.add(s);
        }
        return result;
    }

    private static String describe(List<VirtualFile> files) {
        if (files.size() == 1) return files.get(0).getName();
        return files.get(0).getName() + " +" + (files.size() - 1) + " more";
    }

    private void showStatus(String text) {
        ApplicationManager.getApplication().invokeLater(() -> {
            StatusBar bar = WindowManager.getInstance().getStatusBar(project);
            if (bar != null) bar.setInfo(text);
        }, project.getDisposed());
    }

    private void failureNotification(String label) {
        String msg = "Could not reload <b>" + label + "</b>. See the IDE's HotSwap notification for details.<br>"
                + "<i>Structural</i> changes (adding/removing methods or fields, signature or hierarchy changes) "
                + "need the application to be debugged on JetBrains Runtime (JBR); MyDebug then adds "
                + "<code>-XX:+AllowEnhancedClassRedefinition</code> automatically.";
        NotificationGroupManager.getInstance().getNotificationGroup("MyDebug")
                .createNotification("MyDebug HotSwap", msg, NotificationType.WARNING)
                .addAction(NotificationAction.createSimpleExpiring("Settings",
                        () -> ShowSettingsUtil.getInstance().showSettingsDialog(project, MyDebugConfigurable.class)))
                .notify(project);
    }

    void notify(NotificationType type, String msg) {
        NotificationGroupManager.getInstance().getNotificationGroup("MyDebug")
                .createNotification("MyDebug", msg, type)
                .notify(project);
    }

    @Override
    public void dispose() {
        synchronized (lock) {
            disposed = true;
            if (scheduled != null) scheduled.cancel(false);
            pending.clear();
        }
    }
}
