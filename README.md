# MyDebug HotReload

Save a file and see the change in the running Java application instantly — no restart, with or without the debugger.

MyDebug HotReload compiles what you saved and reloads it into the running JVM. A small bundled agent (Java 8+, no
dependencies) refreshes Spring and MyBatis so the change is live right away.

| IDE | Install | Source |
|---|---|---|
| IntelliJ IDEA 2024.3+ | [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/36412-mydebug-hotreload) | [`intellij/`](intellij) |
| VS Code (Debugger for Java) | [VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=optona.mydebug-hotreload) | [`vscode/`](vscode) |
| Eclipse 2024-09+ (Java 21) | [Eclipse Marketplace](https://marketplace.eclipse.org/content/mydebug-hotreload) · update site `https://mustafaergan.github.io/mydebug/eclipse/` | [`eclipse/`](eclipse) |

All three share the same agent ([`agent/`](agent)). Setup video (Turkish): https://mustafaergan.github.io/mydebug/

| Change | Standard JDK | JetBrains Runtime / DCEVM |
|---|---|---|
| Method body | ✅ | ✅ |
| Add/remove methods or fields, signature changes | ❌ | ✅ |
| New Spring endpoint in an existing controller | ❌ | ✅ |
| New `@Autowired` / `@Value` field in an existing bean | ❌ | ✅ |
| Brand-new `@Service` / `@RestController` class | ✅ | ✅ |
| MyBatis mapper XML (SQL, result maps) | ✅ | ✅ |
| Hibernate entity changes, new `@Bean` methods | ❌ restart | ❌ restart |

Measured on Spring Boot 4.1 + MyBatis 4.1, JetBrains Runtime 21: method body 68 ms, new endpoint + injected fields
150 ms, mapper XML 29 ms.

## How it works

```
Ctrl+S ─► SaveListener ─► HotReloadService (batches saves) ─► incremental compile
   Debug: ─► IDE HotSwap (JDI) ──────────────┐
   Run:   ─► agent sees new .class files ────┴─► one batched redefineClasses ─► Spring / MyBatis refresh

Launch ─► EnhancedHotSwapPatcher ─► -XX:+AllowEnhancedClassRedefinition (if the JDK supports it)
                                   ─► -javaagent:mydebug-agent.jar (bundled)
```

- The agent watches build output folders with native file notifications (one tree watch on Windows), no polling.
- Spring: caches are cleared, changed singletons are re-injected in place (`autowireBean`, identity kept), controller
  mappings are unregistered and re-detected, new `@Component` classes inside the component-scan packages are registered.
  Contexts are found via Spring Boot's shutdown hook registry, `ContextLoader` or `LiveBeansView`.
- MyBatis: only the edited mapper's namespace is removed from the `Configuration` and the XML is parsed again.
- The JDK is probed with `java -XX:+AllowEnhancedClassRedefinition -version`; JDKs that reject the flag are left
  untouched, so the application always starts. The agent never stops the application from starting either.

## Usage

- Start your app with **Run** or **Debug**, edit, save. The status bar shows the compile time; the application console
  shows lines such as `[MyDebug] Spring: refreshed helloController - 2 endpoints mapped`.
- **Run › MyDebug: Reload on Save** toggles automatic reload.
- **Run › MyDebug: Compile and Reload Now** (`Ctrl+Alt+F9`) triggers it manually.
- **Settings › Tools › MyDebug HotReload**: compile mode, batch delay, JBR flag, bundled agent.
- `-Dmydebug.verbose=true` on the application prints agent details.

For structural changes, run the application on **JetBrains Runtime** (Project Structure › SDKs › Download JDK › JetBrains Runtime).

## Build

```powershell
$env:JAVA_HOME="$env:USERPROFILE\.jdks\ms-25.0.4.1"; .\gradlew.bat :agent:jar :intellij:buildPlugin
```

- IntelliJ: `intellij/build/distributions/mydebug-<version>.zip`; sandbox IDE `.\gradlew.bat :intellij:runIde`,
  compatibility `.\gradlew.bat :intellij:verifyPlugin`.
- VS Code: `cd vscode; npm install; npx vsce package --no-dependencies` (needs `:agent:jar` first).
- Eclipse: `cd eclipse; mvn clean verify` (Tycho, needs `:agent:jar` first); update site in
  `eclipse/com.optona.mydebug.eclipse.site/target/repository`.

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
