package com.optona.mydebug;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

/** Uygulama düzeyinde MyDebug ayarları (mydebug.xml). */
@Service(Service.Level.APP)
@State(name = "MyDebugSettings", storages = @Storage("mydebug.xml"))
public final class MyDebugSettings implements PersistentStateComponent<MyDebugSettings.State> {

    public static final class State {
        /** Kaydetme anında otomatik derle + HotSwap. */
        public boolean enabled = true;
        /** true: yalnızca kaydedilen dosyaları derle (en hızlı). false: artımlı proje derlemesi (en güvenli). */
        public boolean compileOnlySavedFiles = false;
        /** Ardışık kaydetmeleri tek yüklemede toplamak için bekleme (ms). */
        public int debounceMs = 300;
        /** Başarılı yüklemeyi durum çubuğunda göster. */
        public boolean notifyOnSuccess = true;
        /** Debug başlatılırken JVM destekliyorsa -XX:+AllowEnhancedClassRedefinition ekle. */
        public boolean patchEnhancedRedefinition = true;
        /** HotswapAgent jar yolu (Spring, Hibernate vb. çerçeve yenilemeleri için). Boşsa kullanılmaz. */
        public String hotswapAgentJar = "";
    }

    private State state = new State();

    public static MyDebugSettings getInstance() {
        return ApplicationManager.getApplication().getService(MyDebugSettings.class);
    }

    public static State settings() {
        return getInstance().getState();
    }

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State loaded) {
        state = loaded;
    }
}
