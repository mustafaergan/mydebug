# MyDebug HotReload for VS Code

Save a Java file and see the change in the running application instantly — no restart, with or without the debugger.

MyDebug attaches a small agent (Java 8+, no dependencies) to your Java launches. The Java language server already
compiles on save; the agent picks up the new classes, reloads them into the running JVM and refreshes Spring and MyBatis.

- **Reload on save in Run mode** — "Run Without Debugging" works too: the agent watches the build output and redefines
  changed classes itself.
- **Spring** — new or changed `@GetMapping`/`@PostMapping` endpoints are mapped, new `@Autowired`/`@Value` fields are
  injected into existing beans, brand-new `@Service`/`@RestController` classes are registered.
- **MyBatis** — edited mapper XML files are re-parsed into the running configuration.
- **Structural changes** — on JetBrains Runtime / DCEVM, adding or removing methods and fields reloads too.

## Usage

1. Install the extension (it requires *Debugger for Java*, which is installed with it).
2. Start your application with **Run** or **Debug** as usual. The debug console shows
   `[MyDebug] agent ready (...)`.
3. Edit and save. The console shows lines such as `[MyDebug] Spring: refreshed helloController - 2 endpoints mapped`.

When debugging, set `"java.debug.settings.hotCodeReplace": "auto"` so VS Code reloads classes on save; MyDebug then
refreshes the frameworks. With the default `"manual"`, MyDebug reloads the classes itself.

## Settings

| Setting | Default | |
|---|---|---|
| `mydebug.enabled` | `true` | Attach the agent to Java launches (also: status bar item, *MyDebug: Toggle Reload Agent*). |
| `mydebug.enhancedRedefinition` | `auto` | Add `-XX:+AllowEnhancedClassRedefinition`. `auto` adds it only when the launch sets `"javaExec"` to a JVM that supports it. |
| `mydebug.verbose` | `false` | Print agent details. |

For structural changes, run on JetBrains Runtime: set `"javaExec"` in `launch.json` to its `java` executable.

## Also available

IntelliJ IDEA: [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/36412-mydebug-hotreload) ·
Source: [github.com/mustafaergan/mydebug](https://github.com/mustafaergan/mydebug) · Apache-2.0
