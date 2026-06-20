# AGENTS.md

Project-specific guidance for future implementation work.

## Project

- Product name: `OpenCode Web Panel`.
- Plugin ID: `de.moritzf.opencodewebpanel`.
- Kotlin package root: `de.moritzf.opencodewebpanel`.
- Repository URL: `https://github.com/moritzfl/intellij-opencode-web-panel`.
- Keep public labels, documentation, and Marketplace metadata aligned with `OpenCode Web Panel`.
- Do not reintroduce old fork identifiers such as `opencode-web-ui`, `OpenCodeWeb` as a public label, `com.github.xausky.opencodewebui`, or `https://github.com/xausky/opencode-web-ui`.

## Source Layout

- Main Kotlin sources live under `src/main/kotlin/de/moritzf/opencodewebpanel`.
- Tests live under `src/test/kotlin/de/moritzf/opencodewebpanel`.
- Plugin metadata lives in `src/main/resources/META-INF/plugin.xml`.
- Icons live in `src/main/resources/icons`.
- README screenshot lives at `docs/opencode-web-panel.png`.

## Implementation Conventions

- Keep one shared OpenCode server process per IDE application.
- Keep browser/tool-window state project-scoped.
- Put command construction, auth helpers, URL helpers, path detection, and route encoding in `OpenCodeServerProtocol`.
- Keep OpenCode process lifecycle in `SharedOpenCodeServerManager`.
- Keep JCEF/tool-window integration in `OpenCodeWebToolWindowFactory`.
- Keep settings state, secure password storage, and settings UI in the `settings` package.
- Store secrets only in IntelliJ `PasswordSafe`; do not persist passwords in XML or project files.

## OpenCode Behavior

- Start OpenCode with `opencode serve --hostname 127.0.0.1 --port <port> --print-logs`.
- Default host is `127.0.0.1`.
- Default port mode is `Auto select`, implemented as `--port 0`.
- Fixed port mode sanitizes values to `1..65535`; default fixed port is `4096`.
- Basic auth username is `opencode`.
- If settings that affect the process change, stop the running server and let the next tool-window load restart it.

## Settings

- Settings path: `Settings > Tools > OpenCode Web Panel`.
- Settings group label: `OpenCode Server`.
- Settings order: binary, port, password.
- Binary setting supports `Auto detect` and `OpenCode path`.
- `OpenCode path` includes a `Detect` action that fills an editable path value from auto-detection.
- Password controls support edit, generate, show, and copy.

## Verification

- Primary command: `./gradlew check`.
- Use `rtk ./gradlew check` when working in this environment.
- Run full checks after Kotlin, Gradle, plugin descriptor, settings, protocol, or lifecycle changes.
- README-only changes do not require Gradle checks unless they affect the plugin description block between `<!-- Plugin description -->` markers.

## Git

- Do not commit unless explicitly asked.
- Before committing, inspect `git status`, `git diff`, and recent commits.
- Stage only intended files.
- Do not revert unrelated local changes.
