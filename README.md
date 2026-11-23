# MyDebug HotReload

Save a file while debugging and see the change in the running JVM instantly — no restart.

MyDebug HotReload is an IntelliJ IDEA plugin (2024.3+) that compiles what you saved and HotSwaps the new
bytecode into every active Java debug session. On JetBrains Runtime / DCEVM it also enables structural changes
(adding or removing methods and fields).

| Change | Standard JDK | JBR / DCEVM (flag added by the plugin) | + HotswapAgent |
|---|---|---|---|
| Method body | ✅ | ✅ | ✅ |
| Add/remove methods or fields, signature changes | ❌ | ✅ | ✅ |
| Class hierarchy changes | ❌ | ✅ (most cases) | ✅ |
| Spring beans, Hibernate mappings, resources | ❌ | ❌ | ✅ |

## How it works

```
Ctrl+S ─► SaveListener ─► HotReloadService (batches saves) ─► incremental compile ─► HotSwap (JDI redefineClasses)

Debug run ─► EnhancedHotSwapPatcher ─► probes the JDK once; if supported adds -XX:+AllowEnhancedClassRedefinition
                                      (+ optional -XX:HotswapAgent=external -javaagent:<jar>)
```

The JDK is probed with `java -XX:+AllowEnhancedClassRedefinition -version`; JDKs that reject the flag are left
untouched, so the application always starts.

## Usage

- Start your app with **Debug**, edit, save. The status bar shows `MyDebug: reloaded Foo.java · 240 ms`.
- **Run › MyDebug: Reload on Save** toggles automatic reload.
- **Run › MyDebug: Compile and Reload Now** (`Ctrl+Alt+F9`) triggers it manually.
- **Settings › Tools › MyDebug HotReload**: compile mode, batch delay, JBR flag, HotswapAgent jar path.

For structural changes, run the application on **JetBrains Runtime** (Project Structure › SDKs › Download JDK › JetBrains Runtime).

## Build

```powershell
$env:JAVA_HOME="$env:USERPROFILE\.jdks\ms-25.0.4.1"; .\gradlew.bat buildPlugin
```

Output: `build/distributions/mydebug-<version>.zip`. Try it in a sandbox IDE with `.\gradlew.bat runIde`,
check compatibility with `.\gradlew.bat verifyPlugin`.

## License

Apache License 2.0 — see [LICENSE](LICENSE).

---

## Türkçe notlar

### Bu makinede derleme (ev-pc)
- IntelliJ 2026.2 platformu Java 25 bytecode'u olduğu için Gradle toolchain 25; eklenti bytecode'u 21 hedefler (2024.3+ uyumu).
- Norton TLS denetimi yüzünden Gradle komutlarına `"-Djavax.net.ssl.trustStoreType=Windows-ROOT"` ekleyin.

### JetBrains Marketplace'e yayın
1. **Hesap:** plugins.jetbrains.com'da JetBrains hesabıyla oturum açın, *Upload plugin* ekranında vendor
   olarak **Optona** organizasyonu oluşturun (ilk yayında bir kez).
2. **İlk sürüm elle yüklenir:** `build/distributions/mydebug-0.1.0.zip` dosyasını web arayüzünden yükleyin,
   lisans olarak Apache-2.0'ı ve kaynak kodu linkini (GitHub repo) girin. JetBrains ilk sürümü elle inceler
   (genelde birkaç iş günü).
3. **Sonraki sürümler:** profil › *My Tokens* altından bir token oluşturun, sonra:
   ```powershell
   $env:JETBRAINS_MARKETPLACE_TOKEN="<token>"; .\gradlew.bat publishPlugin
   ```
   Sürüm numarasını `gradle.properties` içindeki `pluginVersion`, notları `changeNotes` ile güncelleyin.
   Token repoya yazılmaz.
4. Eklenti kimliği `com.optona.mydebug` yayından sonra **değiştirilemez**.
