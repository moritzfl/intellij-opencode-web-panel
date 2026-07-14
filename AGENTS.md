# AGENTS.md

Project-specific guidance for future implementation work.

## Project

- Product name: `OpenCode Web Panel`.
- Plugin ID / Kotlin package root: `de.moritzf.opencodewebpanel`.
- Repository: `https://github.com/moritzfl/intellij-opencode-web-panel`.
- Keep public labels, docs, and Marketplace metadata aligned with `OpenCode Web Panel`.
- Never reintroduce old fork identifiers: `opencode-web-ui`, `OpenCodeWeb` (as a public label), `com.github.xausky.opencodewebui`, `https://github.com/xausky/opencode-web-ui`.

## Source Layout

- Main sources: `src/main/kotlin/de/moritzf/opencodewebpanel`; tests mirror them under `src/test/kotlin/...`.
- Packages, by dependency direction (each may depend on the earlier ones, never the later):
  - `server` — process lifecycle, protocol/REST helpers, and the JVM `/global/event` reader: `SharedOpenCodeServerManager`, `OpenCodeServerProtocol`, `OpenCodeGlobalEventStream`, plus lifecycle/log/terminator/MCP-startup support.
  - `browser` — page-injection mechanics: `OpenCodeBrowserSnippets` (all injected-JS builders), `OpenCodeBrowserScriptScheduler`.
  - `features` — the enhancements: agent-status, notifications, interrupted-session recovery, VCS refresh, IDE navigation, diff navigation, chat input, file drop, localStorage mirror.
  - `toolWindow` — JCEF/tool-window glue: factory, content, title/gear actions, status panels, request/shortcut handlers.
  - `settings` — settings state, secure password storage, settings UI (used by all of the above; only its restart-confirmation dialog reaches back into `toolWindow`).
- Metadata: `src/main/resources/META-INF/plugin.xml`. Icons: `src/main/resources/icons`. README screenshot: `docs/opencode-web-panel.png`.

## Conventions

- One shared OpenCode server process per IDE application; `SharedOpenCodeServerManager` owns its lifecycle.
- Browser/tool-window state is project-scoped, except the mirrored OpenCode web-session settings store, which is intentionally application-global so OpenCode's own settings persist across embedded sessions and projects (don't change without revisiting that decision).
- Put command construction, auth/URL helpers, path detection, and route encoding in `OpenCodeServerProtocol`; injected-JS builders belong in `OpenCodeBrowserSnippets`.
- JCEF/tool-window integration stays in `toolWindow` (`OpenCodeWebToolWindowFactoryImpl`, `OpenCodeWebToolWindowContent`). Title-bar and gear actions live in `OpenCodeToolWindowActions`; title actions stay few and icon-only (IntelliJ clips them on narrow panels) and the gear menu must duplicate every title action.
- Settings state, secure password storage, and settings UI stay in `settings`. Store secrets only in IntelliJ `PasswordSafe` — never in XML or project files.

## OpenCode Server

- Launch: `opencode serve --hostname 127.0.0.1 --port <port> --print-logs`. Default host `127.0.0.1`; basic-auth username is always `opencode`.
- Port: default mode `Auto select` (`--port 0`); fixed mode sanitizes to `1..65535`, default fixed port `4096`.
- When settings that affect the process change, stop the server and let the next tool-window load restart it.
- Compact layout: OpenCode switches compact(mobile)/wide(desktop) on a `(min-width: 768px)` `matchMedia` query. To force compact, patch `window.matchMedia` **before** the SPA bundle loads (`onLoadStart`) so `createMediaQuery` initializes with `matches: false` and never subscribes to real resize events.

## Settings UI

- Path `Settings > Tools > OpenCode Web Panel`; server group label `OpenCode Server`; order: binary, port, password.
- Binary: `Auto detect` or `OpenCode path` (with a `Detect` action that fills an editable path).
- Password controls: edit, generate, show, copy.

## Injection Safeguards

UI-behavior settings (`openFileLinksInIde`, `enableCodeNavigation`, `openDiffsInIde`, `enableChatFileDrop`, `forceCompactLayout`, …) gate **browser-side JS/CSS injection** into the embedded web app. They are **safeguards**, not cosmetics: if an injected behavior breaks the OpenCode UI or conflicts with an update, the user must be able to disable it and get back a clean, unmodified web app.

- Disabled ⇒ generate/inject/schedule nothing; the script builder returns `null`.
- Toggled **off** at runtime ⇒ reload the page so listeners/patches/stylesheets are fully removed — never inject a "disable" script.
- Toggled **on** at runtime ⇒ inject immediately and/or reload as needed.
- Scripts that must run before the SPA bundle (e.g. `forceCompactLayout`) inject in `onLoadStart`, not `onLoadEnd`.
- Never open event streams or other long-lived connections from injected scripts; consume OpenCode events JVM-side instead (see Event & REST Contract).
- Add unit tests asserting disabled builders return `null` and that a toggle-off reloads rather than injects a "disable" script.
- Validate injected JS against a real page before claiming it works (see Validating Against a Real Server) — unit tests only check the script *text*, never real DOM behavior.

### Diff navigation DOM contract (`openDiffsInIde`)

The Alt+Click "open diff in IDE" feature (`OpenCodeBrowserSnippets.buildDiffNavigationScript` → `features.OpenCodeDiffNavigation`) is the most DOM-coupled injection: it maps a click to a `(messageID, filePath)` pair from OpenCode SPA internals, none of which are a stable API and some of which have already shifted between releases. Re-verify against a real 1.17.15+ page whenever diffs stop opening:

- **Message id** — nearest `[data-message-id]` ancestor (one per session turn; holds the turn's **user** message id, which is what the diff endpoint is keyed by). `dev` builds used `[data-message]`; the script matches both. Every target resolves its id this way — never send an empty or assistant id (both yield an empty diff).
- **Changes/review row** — `[data-file]` (path directly).
- **"Changed files" turn-summary row** — `[data-slot="session-turn-diff-trigger"]` (no `data-file`; reconstruct path from `[data-slot="session-turn-diff-directory"]` + `[data-slot="session-turn-diff-filename"]`).
- **Chat edit/write block** — `[data-component="edit-tool"|"write-tool"]` (path from `[data-slot="message-part-directory"]` + `[data-slot="message-part-title-filename"]`).
- **Diff indicator** (fallback → whole turn, all files) — `[data-component="diff-changes"]`.

The file-link handler (`buildFileLinkHandlerScript`) early-returns on `event.altKey`, reserving Alt for this gesture. These selectors were validated against both the classic layout and the redesigned one (`body[data-new-layout]`, the "New layout and designs" setting).

### Open-project navigation contract (redesigned layout)

Verified live vs opencode 1.17.18. The redesigned layout (`settings.v3` → `general.newLayoutDesigns`, `body[data-new-layout]`) uses **directoryless routes**: `/server/<base64url(origin)>/session/<ses_...>` and `/new-session?draftId=...`. Directory routes (`/<base64url(dir)>/session[...]`) are accepted on a cold document load — and win over the SPA's own state — but are quickly rewritten to the directoryless form. Which project such a route shows is visible only in the SPA's `opencode.global.dat:server` localStorage (`lastProject`/`projects`), which the SPA does **not** update when it boots from a direct directory route. Consequences, each load-bearing in `buildOpenProjectScript` / `OpenCodeWebToolWindowContent`:

- Panels must load `buildProjectUrl(...)` (the directory route) directly — never the server root, where the SPA restores its own last project from the **application-shared, persistent** JCEF profile (another IDE project's directory, or a stale path after a project-directory rename).
- Never blanket-accept directoryless routes as "already at the destination" JVM-side (`isOpenCodeProjectDestination`); the open-project script must keep running and decide in-page by comparing the **pre-seed** `lastProject` against the panel's directory (the seed itself overwrites it).
- The script must keep seeding `opencode.global.dat:server` because the SPA does not maintain `lastProject` for route-derived project switches.
- A directory absent from that store boots into an **unbound "new project" composer** even on its own directory route (first use of a fresh profile or a new project). Accepted: the open-project script seeds the store and re-navigates, which binds it after one corrective reload. An `onLoadStart` pre-seed was tried and dropped — cosmetic gain only, and it overwrites the pre-seed `lastProject` the script's directoryless check relies on.
- The script's sessionStorage navigation guard (`opencode.intellij.project.opened:<dir>`) is per JCEF tab and deliberately makes the open-project navigation once-per-tab-session: after the initial open, the user may switch projects inside the panel without being dragged back.
- A renamed project directory that keeps showing its **old path/name** despite all of the above is opencode's server-side stale `project.worktree` (anomalyco/opencode#35240) — visible in every client, not fixable from the plugin. Workaround: update `project.worktree` (and old sessions' `directory`/`path`) in `~/.local/share/opencode/opencode.db` while no opencode instance runs.

## Validating Against a Real Server

Injection fixes and wire-contract checks must be validated against a live server — not `about:blank`, synthetic pages, or unit tests. Getting **auth** wrong has repeatedly cost debugging loops.

- Start: `OPENCODE_SERVER_PASSWORD=testpw123 opencode serve --hostname 127.0.0.1 --port <port> --print-logs`. Health: `curl -u opencode:testpw123 http://127.0.0.1:<port>/api/health` ⇒ `{"healthy":true}` (curl with `-u` first to separate auth from app problems).
- **Basic auth covers everything** — the SPA's static assets (`/assets/*.js|css`) and all API routes; only `/site.webmanifest` and the web-app-manifest PNGs are public.
- Do **not** use the `?auth_token=<base64(user:pass)>` query param: it authenticates only the initial HTML, so asset requests then hang forever in headless Playwright (symptom: navigation times out, title is `OpenCode`, `#root` stays empty, no console errors — looks like a broken SPA but is pure auth). Instead send the `Authorization` header on **every** request (what the plugin does via `onBeforeResourceLoad`): `page.setExtraHTTPHeaders({ Authorization: 'Basic ' + btoa('opencode:testpw123') })` (or `httpCredentials`), set **before** the first `goto`. For `opencode:testpw123` the header is `Basic b3BlbmNvZGU6dGVzdHB3MTIz`.
- Early-injection scripts: `page.addInitScript(script)` before `goto` (mirrors `onLoadStart`); post-load: `page.evaluate(script)` after mount (mirrors `onLoadEnd`).
- Session route: `/<base64url(dir)>/session` — encode with `printf '%s' "<dir>" | base64 | tr '+/' '-_' | tr -d '='`.
- The served UI is localized (e.g. German labels on a German system); don't assert English-only UI strings.

## Event & REST Contract

The server publishes an **OpenAPI 3.1 spec** at `GET /doc` while running; the JS SDK (`@opencode-ai/sdk`) is generated from it but can lag, so cross-check against a live server or the source at `/Users/moritz/Desktop/git/opencode` (`packages/schema/src/`). The plugin consumes events **JVM-side**: `OpenCodeGlobalEventStream` (owned by `SharedOpenCodeServerManager`) holds the single `/global/event` reader and publishes parsed events on the `OpenCodeGlobalEventListener` application topic, emitting `connected()` after each (re)connect so consumers can re-seed reduced state via REST. Subscribe new consumers there; REST parsers live in `OpenCodeServerProtocol` with adjacent unit tests. All of this depends on wire shapes, not DOM — re-verify against a real server after touching it or on OpenCode updates (last verified vs source post-`a26722208` schema refactor, and vs live opencode 1.17.15).

Reproduce: start a server (above), then in one terminal `curl -N -u opencode:testpw123 -H "Accept: text/event-stream" http://127.0.0.1:<port>/global/event`, and trigger events from another, e.g. `curl -u opencode:testpw123 -X POST "http://127.0.0.1:<port>/session?directory=<url-encoded dir>" -H 'Content-Type: application/json' -d '{}'`.

**Event framing:** SSE `data:` payloads are `{"directory": "...", "payload": {"id": "evt_...", "type": "...", "properties": {...}}}`. Events without a `directory` (e.g. `server.connected`) are dropped by `parseGlobalEvent`; `server.heartbeat` arrives every 10s. Consumed types (v1, see `packages/schema/src/v1/`):

- `session.status` → `{sessionID, status: {type: "busy"|"retry"|"idle", ...}}`
- `session.idle` (deprecated predecessor of `session.status`) → `{sessionID}`
- `session.error` → `{sessionID?, error?}`
- `permission.asked` → `{id, sessionID, permission, patterns, metadata, always, tool?}` (NOT `permission.updated` — that only exists in the stale legacy SDK gen)
- `permission.replied` → `{sessionID, requestID, reply}`, `reply ∈ {"once","always","reject"}` (NOT `{permissionID, response}` — stale gen)
- `question.asked` → `{id, sessionID, questions: QuestionInfo[], tool?}`
- `question.replied` → `{sessionID, requestID, answers: string[][]}`
- `question.rejected` → `{sessionID, requestID}`

Many other types exist but are unconsumed (`session.created`/`updated`/`deleted`/`diff`/`compacted`, `message.*`, `message.part.*`, `file.edited`, `vcs.branch.updated`, `todo.updated`, `lsp.updated`, `pty.*`, `workspace.*`, the v2 `session.next.*` family, …); durable events also re-emit as `sync` wrappers (`syncEvent.type = "<type>.<version>"`). See `packages/schema/src/session-event.ts`. There is NO `lsp.client.diagnostics` — only `lsp.updated`.

**REST shapes** (all take `?directory=`):

- `GET /session/status` → `{"ses_...": {"type": "busy"|"retry"|"idle"}, ...}` (`parseBusySessionIds`).
- `GET /permission`, `GET /question` → array of request objects with `id` (`parsePendingRequestIds`).
- `GET /session/{id}` → session object with `title` and optional `parentID`, bare or wrapped in `{"data": {...}}` (`parseSessionInfo`).
- `GET /api/session?order=desc&limit=N` → `{"data": [{"id": "ses_...", "parentID"?, "time": {"created","updated"}}, ...], "cursor": {...}}` (`parseSessionList`). Creation-ordered (**not** by `time.updated`) and includes subagent children, so "most recent activity" callers must pick `max(time.updated)` themselves and skip entries with a `parentID`. The server canonicalizes directories (macOS `/var/...` → `/private/var/...`); query with the same directory value used elsewhere.
- `GET /session/{sessionID}/diff?directory=<dir>&messageID=<msg_...>` → `Array<SnapshotFileDiff>`, `SnapshotFileDiff = {file?, patch?, additions, deletions, status?: "added"|"deleted"|"modified"}` (`fetchSessionDiff`/`parseSessionDiff`). `patch` is a unified diff string; there is **no `before`/`after` field** (the legacy gen showing them is wrong). The same shape rides the `session.diff` event. **Keyed by the turn's *user* message id** (verified vs 1.17.15): an omitted or an assistant `messageID` both return `[]`; only the user `msg_...` returns that turn's diffs. Diffs are computed from persisted git snapshots (`<data>/snapshot/<projectID>/…`), so they **survive a server restart**. Hence the Alt+Click feature resolves the user message id from the DOM and `OpenCodeDiffNavigation` reconstructs before/after from each `patch` via `OpenCodeUnifiedDiff` for the IDE viewer.
- `POST /permission/{requestID}/reply?directory=<dir>` body `{"reply": "once"|"always"|"reject"}` (op `permission.reply`) — the write endpoint for permissions (`replyToPermission`; `requestID` is the `per_...` id from `permission.asked`/`.replied`). Non-deprecated successor to `POST /session/{sessionID}/permissions/{permissionID}` + `{"response": ...}` (op `permission.respond`), the **only** operation marked `deprecated` in the spec. Don't regress: the reply endpoint rejects a `{"response": ...}` body with `400 Missing key ["reply"]` and returns `404 PermissionNotFoundError` for an unknown id.

If a shape changed, fix the matching parser and its unit test, then update this section.

## Verification

- Primary: `./gradlew check` (use `rtk ./gradlew check` in this environment). Run after Kotlin, Gradle, plugin-descriptor, settings, protocol, or lifecycle changes.
- README-only changes need no Gradle check unless they touch the plugin-description block between the `<!-- Plugin description -->` markers.

## Git

- Don't commit unless explicitly asked. Before committing, inspect `git status`, `git diff`, and recent commits; stage only intended files; don't revert unrelated local changes.
