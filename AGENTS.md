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
- Tests live under `src/test/kotlin/de/moritzf/opencodewebpanel`, mirroring the main packages.
- Packages, by dependency direction (each may depend on the ones before it, never after):
  - `server` — OpenCode process lifecycle, protocol/REST helpers, and the JVM `/global/event` stream: `SharedOpenCodeServerManager`, `OpenCodeServerProtocol`, `OpenCodeGlobalEventStream`, lifecycle/log/terminator/MCP-startup support.
  - `browser` — page-injection mechanics: `OpenCodeBrowserSnippets` (all injected JS builders), `OpenCodeBrowserScriptScheduler`.
  - `features` — the individual enhancements: agent-status tracking, system notifications, interrupted-session recovery, VCS refresh, IDE navigation, chat input, file drop, localStorage mirror.
  - `toolWindow` — JCEF/tool-window glue: factory, content, title/gear actions, status panels, request/shortcut handlers.
  - `settings` — settings state, secure password storage, settings UI (referenced by all of the above; only its restart-confirmation dialog reaches back into `toolWindow`).
- Plugin metadata lives in `src/main/resources/META-INF/plugin.xml`.
- Icons live in `src/main/resources/icons`.
- README screenshot lives at `docs/opencode-web-panel.png`.

## Implementation Conventions

- Keep one shared OpenCode server process per IDE application.
- Keep browser/tool-window state project-scoped, except the mirrored OpenCode web-session settings store.
- The mirrored OpenCode web-session settings store is intentionally application-global by design; it should preserve OpenCode's own settings across embedded sessions and projects unless this decision is explicitly revisited.
- Put command construction, auth helpers, URL helpers, path detection, and route encoding in `OpenCodeServerProtocol`; injected-JS builders belong in `OpenCodeBrowserSnippets`, not the protocol.
- Keep OpenCode process lifecycle in `SharedOpenCodeServerManager`.
- Keep JCEF/tool-window integration in the `toolWindow` package (`OpenCodeWebToolWindowFactoryImpl` and `OpenCodeWebToolWindowContent`).
- Tool-window title-bar actions and gear-menu actions live in `OpenCodeToolWindowActions` and are wired in the factory. Title actions must stay few and icon-only because IntelliJ clips them on narrow panels; the gear menu must duplicate every title action.
- Keep settings state, secure password storage, and settings UI in the `settings` package.
- Store secrets only in IntelliJ `PasswordSafe`; do not persist passwords in XML or project files.

## OpenCode Behavior

- Start OpenCode with `opencode serve --hostname 127.0.0.1 --port <port> --print-logs`.
- Default host is `127.0.0.1`.
- Default port mode is `Auto select`, implemented as `--port 0`.
- Fixed port mode sanitizes values to `1..65535`; default fixed port is `4096`.
- Basic auth username is `opencode`.
- If settings that affect the process change, stop the running server and let the next tool-window load restart it.
- OpenCode uses a `(min-width: 768px)` `matchMedia` query to switch between compact (mobile) and wide (desktop) layouts. To force compact layout, `window.matchMedia` must be patched **before** the SPA bundle loads (`onLoadStart`), so `createMediaQuery` initializes with `matches: false` and never subscribes to real resize events.

## Browser Connection Budget

- Chromium allows only **six concurrent HTTP/1.1 connections per host**, shared across **all** JCEF browsers in the IDE. The local OpenCode server is plain HTTP, so this limit applies, and every open project window's panel draws from the same pool.
- Each embedded OpenCode SPA already holds one persistent `/global/event` stream. When the pool is exhausted, further requests (model lists, session data) queue forever with **no errors**: the page loads, chat input works, but selectors never finish loading.
- Injected scripts must therefore never open event streams or other long-lived connections. The plugin consumes OpenCode events **JVM-side**: `OpenCodeGlobalEventStream` (owned by `SharedOpenCodeServerManager`) holds the single plugin `/global/event` reader and publishes parsed events on the `OpenCodeGlobalEventListener` application topic, with a `connected()` signal after each (re)connect for consumers that must re-seed reduced state via REST. Subscribe new event consumers to that topic instead of injecting page-side readers.

## Settings

- Settings path: `Settings > Tools > OpenCode Web Panel`.
- Settings group label: `OpenCode Server`.
- Settings order: binary, port, password.
- Binary setting supports `Auto detect` and `OpenCode path`.
- `OpenCode path` includes a `Detect` action that fills an editable path value from auto-detection.
- Password controls support edit, generate, show, and copy.

## UI Behavior Settings and Injection Safeguards

- UI behavior settings (`openFileLinksInIde`, `enableCodeNavigation`, `openDiffsInIde`, `enableChatFileDrop`, `forceCompactLayout`) control **browser-side JavaScript/CSS injection** into the embedded OpenCode web app.
- These settings are not cosmetic toggles — they are **safeguards**. If an injected behavior breaks the OpenCode UI or conflicts with an OpenCode update, the user must be able to disable it and get back to a clean, unmodified web app.
- When a setting is **disabled**, no JavaScript or CSS for that behavior may be generated, injected, or scheduled. Script builders must return `null` when disabled.
- When a setting is toggled **off** at runtime, the browser must reload the page so any previously installed listeners, patches, or stylesheets are fully removed — never inject a "disable" script.
- When a setting is toggled **on** at runtime, inject the script immediately and/or reload the page as needed.
- Settings that require early injection (before the SPA bundle loads, e.g. `forceCompactLayout`) must be injected in `onLoadStart`, not `onLoadEnd`.
- Add unit tests verifying that disabled builders return `null` and that toggling triggers a reload, not a "disable" injection.
- Before integrating or claiming fixes for browser-side JS/CSS injection, validate the planned script in a real OpenCode page with Playwright. Start `opencode serve --hostname 127.0.0.1 --port <port> --print-logs` with test auth if needed, navigate to the served app, inject the planned script there, and exercise representative DOM/user interactions. Do not rely only on `about:blank`, synthetic pages, or unit tests for injection behavior.

### Diff navigation DOM contract (`openDiffsInIde`)

The Alt+Click "open diff in IDE" feature (`OpenCodeBrowserSnippets.buildDiffNavigationScript` → `features.OpenCodeDiffNavigation`) is the most DOM-coupled injection: it maps a click to a `(messageID, filePath)` pair by reading OpenCode SPA internals, none of which are a stable API and some of which have already shifted between releases. Re-verify these against a real 1.17.15+ page (Playwright recipe below) whenever diffs stop opening:

- **Message id** — the nearest `[data-message-id]` ancestor. There is exactly one per session turn and it holds the turn's **user** message id, which is what the diff endpoint is keyed by (see the REST contract). Older/`dev` builds used `[data-message]`; the script matches both. **Every** target resolves its id this way — never send an empty or assistant id (both yield an empty diff).
- **Changes/review row** — `[data-file]` carries the file path directly.
- **"Changed files" turn-summary row** — `[data-slot="session-turn-diff-trigger"]`; it has no `data-file`, so the path is reconstructed from `[data-slot="session-turn-diff-directory"]` + `[data-slot="session-turn-diff-filename"]`.
- **Chat edit/write block** — `[data-component="edit-tool"|"write-tool"]`; path from `[data-slot="message-part-directory"]` + `[data-slot="message-part-title-filename"]`.
- **Diff indicator** (fallback → whole turn, all files) — `[data-component="diff-changes"]`.

The file-link handler (`buildFileLinkHandlerScript`) deliberately early-returns on `event.altKey`, reserving Alt for this gesture. The unit tests only assert the injected script *contains* these selectors — they cannot detect an OpenCode rename, so a real-page check is the only true guard.

## Playwright Validation Against a Real OpenCode Server

Follow this recipe exactly; getting auth wrong has repeatedly cost debugging loops.

1. Start the server with a known password and a fixed port:
   `OPENCODE_SERVER_PASSWORD=testpw123 opencode serve --hostname 127.0.0.1 --port <port> --print-logs`
2. **Basic auth covers everything**, including the SPA's static assets (`/assets/*.js`, `/assets/*.css`) and all API routes. Only `/site.webmanifest` and the web-app-manifest PNGs are public.
3. Do **not** rely on the `?auth_token=<base64(user:pass)>` query parameter. It authenticates only the initial HTML document request; the subsequent asset requests carry no credentials, so in headless Playwright (no auth prompt) they hang forever. Symptoms: navigation times out, page title is `OpenCode` but `#root` stays empty, zero console errors, and the JS/CSS requests show no response status. This looks like a broken SPA but is purely an auth problem.
4. The fix is to send the `Authorization` header on **every** request — exactly what the plugin itself does natively via `onBeforeResourceLoad`:
   `await page.setExtraHTTPHeaders({ Authorization: 'Basic ' + btoa('opencode:testpw123') })`
   (or create the context with `httpCredentials: { username: 'opencode', password: 'testpw123' }`). Set the headers **before** the first `page.goto(...)`.
5. Username is always `opencode`. For `opencode:testpw123` the header value is `Basic b3BlbmNvZGU6dGVzdHB3MTIz`.
6. To validate early-injection scripts (e.g. compact layout), use `page.addInitScript(script)` before `goto` — this mirrors the plugin's `onLoadStart` injection. For post-load scripts, `page.evaluate(script)` after the app mounted mirrors `onLoadEnd`.
7. Session routes use base64url-encoded directories: `/<base64url(dir)>/session`. Encode with `printf '%s' "<dir>" | base64 | tr '+/' '-_' | tr -d '='`.
8. A healthy server answers `curl -u opencode:testpw123 http://127.0.0.1:<port>/api/health` with `{"healthy":true}`. Use curl with `-u` first when the page misbehaves to separate auth issues from app issues.
9. The served UI is localized (e.g. German button labels on a German system); do not assert English-only UI strings when exercising the DOM.

## Event Stream and REST Contract

The OpenCode server is written in TypeScript and publishes an **OpenAPI 3.1 spec** at `GET /doc` while running. The JS SDK (`@opencode-ai/sdk`) is auto-generated from that spec; its generated types are the reference contract but can lag behind the server — always cross-check against a live server or the source at `/Users/moritz/Desktop/git/opencode` (`packages/schema/src/`).

The JVM-side event consumers (`server.OpenCodeGlobalEventStream`, agent-status seeding, notification session lookup) depend on the wire shapes below instead of DOM structure. After touching them — or when an OpenCode update changes event behavior — re-verify the contract against a real server (last verified against the source at `/Users/moritz/Desktop/git/opencode`, post-`a26722208` schema refactor):

1. Start a server and wait for `{"healthy":true}`:
   `OPENCODE_SERVER_PASSWORD=testpw123 opencode serve --hostname 127.0.0.1 --port 41973 --print-logs`
   `curl -u opencode:testpw123 http://127.0.0.1:41973/api/health`
2. Subscribe to the event stream in one terminal (this is exactly what `OpenCodeGlobalEventStream` does):
   `curl -N -u opencode:testpw123 -H "Accept: text/event-stream" http://127.0.0.1:41973/global/event`
3. Trigger events from another terminal by creating a session for some directory:
   `curl -u opencode:testpw123 -X POST "http://127.0.0.1:41973/session?directory=<url-encoded dir>" -H 'Content-Type: application/json' -d '{}'`
4. Expected event framing: SSE blocks whose `data:` payload is `{"directory": "...", "payload": {"id": "evt_...", "type": "...", "properties": {...}}}`. Events without a `directory` (e.g. `server.connected`) are intentionally dropped by `OpenCodeGlobalEventStream.parseGlobalEvent`. The server emits `server.heartbeat` every 10s on the stream. Consumed types (v1, actively published — see `packages/schema/src/v1/`):
   - `session.status` → `{sessionID, status: {type: "busy"|"retry"|"idle", ...}}`
   - `session.idle` (deprecated predecessor of `session.status`) → `{sessionID}`
   - `session.error` → `{sessionID?, error?}`
   - `permission.asked` → `{id, sessionID, permission, patterns, metadata, always, tool?}` (NOT `permission.updated` — that only exists in the stale legacy v1 SDK gen file; the real type is `permission.asked`)
   - `permission.replied` → `{sessionID, requestID, reply}` where `reply ∈ {"once","always","reject"}` (NOT `{sessionID, permissionID, response}` — the legacy SDK gen is stale)
   - `question.asked` → `{id, sessionID, questions: QuestionInfo[], tool?}`
   - `question.replied` → `{sessionID, requestID, answers: string[][]}`
   - `question.rejected` → `{sessionID, requestID}`
5. Additional event types available on the stream (defined but not yet consumed by the plugin): `session.created`/`updated`/`deleted`, `session.diff`, `session.compacted`, `message.updated`/`removed`, `message.part.updated`/`removed`/`delta`, `file.edited`, `file.watcher.updated`, `vcs.branch.updated`, `todo.updated`, `command.executed`, `project.updated`/`project.directories.updated`, `pty.created`/`updated`/`exited`/`deleted`, `lsp.updated`, `workspace.ready`/`failed`/`status`, `worktree.ready`/`failed`, `mcp.tools.changed`/`mcp.browser.open.failed`, `plugin.added`, `reference.updated`, `integration.updated`/`connection.updated`, `catalog.updated`, `models-dev.refreshed`, `global.disposed`, `server.instance.disposed`, and the v2 `session.next.*` family (32 types, see `packages/schema/src/session-event.ts`). Durable events are also re-emitted as `sync` wrapper events with `syncEvent.type = "<type>.<version>"`. There is NO `lsp.client.diagnostics` — only `lsp.updated` (with empty properties).
6. Expected REST shapes (all take `?directory=`; parsers live in `server.OpenCodeServerProtocol` with unit tests next to them):
   - `GET /session/status` → `{"ses_...": {"type": "busy"|"retry"|"idle"}, ...}` (`parseBusySessionIds`)
   - `GET /permission`, `GET /question` → JSON array of request objects with `id` (`parsePendingRequestIds`)
   - `GET /session/{id}` → session object with `title` and optional `parentID`, bare or wrapped in `{"data": {...}}` (`parseSessionInfo`)
   - `GET /api/session?order=desc&limit=N` → `{"data": [{"id": "ses_...", "parentID"?, "time": {"created", "updated"}}, ...], "cursor": {...}}` (`parseSessionList`). The listing is creation-ordered, **not** `time.updated`-ordered, and includes subagent child sessions; "most recent activity" callers must select `max(time.updated)` themselves and skip entries with a `parentID`. The server canonicalizes directories (macOS `/var/...` → `/private/var/...`), so query with the same directory value used elsewhere in the plugin.
   - `GET /session/{sessionID}/diff?directory=<dir>&messageID=<msg_...>` → `Array<SnapshotFileDiff>` where `SnapshotFileDiff = {file?: string, patch?: string, additions: number, deletions: number, status?: "added"|"deleted"|"modified"}` (`fetchSessionDiff`/`parseSessionDiff`). `patch` is a unified diff string; **there is no `before`/`after` field** (the stale legacy v1 SDK gen showing `before`/`after` is wrong and was never regenerated after the schema refactor). The same `SnapshotFileDiff` shape rides the `session.diff` event (`{sessionID, diff: Array<SnapshotFileDiff>}`). **The endpoint is keyed by the turn's *user* message id** (verified against opencode 1.17.15): an omitted `messageID` **or** an assistant message id both return `[]`; only the `msg_...` id of the user turn returns that turn's file diffs. The diffs are computed from persisted git snapshots (`<data>/snapshot/<projectID>/…`), so they **survive a server restart** — a fresh process returns the same diffs. This is why the Alt+Click open-diff feature resolves the turn's user message id from the DOM (see the diff-navigation DOM contract) and never sends an empty/assistant id; `OpenCodeDiffNavigation` then reconstructs before/after from each `patch` via `OpenCodeUnifiedDiff` for the IDE viewer.
   - `POST /permission/{requestID}/reply?directory=<dir>` with body `{"reply": "once"|"always"|"reject"}` (op `permission.reply`) is the write endpoint the plugin uses to answer permissions (`replyToPermission`; `requestID` is the `per_...` id from `permission.asked`/`permission.replied`). This is the **non-deprecated successor** to `POST /session/{sessionID}/permissions/{permissionID}` + `{"response": ...}` (op `permission.respond`), which is the **only** operation marked `deprecated` in the `GET /doc` OpenAPI spec (verified against opencode 1.17.15). Do not regress to the `response`/`permissions` form: the reply endpoint rejects a `{"response": ...}` body with `400 Missing key ["reply"]` and returns `404 PermissionNotFoundError` for an unknown `requestID`.
7. If a shape changed, fix the matching parser and its unit test, then update the version note and the cross-reference in this section.

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
