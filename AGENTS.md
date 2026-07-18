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
- Browser/tool-window state is project-scoped, except the mirrored OpenCode web-session settings store, which is intentionally application-global so OpenCode's own settings persist across embedded sessions and projects.
- Browser shortcuts use component-local IntelliJ actions: reserve remappable IDs with `EmptyAction` in `plugin.xml`, register runtime handlers on the JCEF component with the reserved action's live `shortcutSet`, and inherit generic edit chords from `IdeActions`. Never use an application-wide key dispatcher or manually match incoming AWT key events.
- OpenCode-specific actions forward through a page-local `KeyboardEvent`, resolving OpenCode's current `settings.v3` keybind first. New Session maps to `tab.new` (v2) / `session.new` (classic); Home maps to `home.toggle` / `sidebar.toggle`, selected by `[data-slot="titlebar-v2"]`. Do not construct AWT events for `CefBrowser.sendKeyEvent` — their missing Windows native scan codes break command delivery.
- Put command construction, auth/URL helpers, path detection, and route encoding in `OpenCodeServerProtocol`; injected-JS builders belong in `OpenCodeBrowserSnippets`.
- JCEF/tool-window integration stays in `toolWindow` (`OpenCodeWebToolWindowFactoryImpl`, `OpenCodeWebToolWindowContent`). Title-bar and gear actions live in `OpenCodeToolWindowActions`; title actions stay few and icon-only (IntelliJ clips them on narrow panels) and the gear menu must duplicate every title action.
- Settings state, secure password storage, and settings UI stay in `settings`. Store secrets only in IntelliJ `PasswordSafe` — never in XML or project files.

## OpenCode Server

- **Minimum supported OpenCode version: 1.18.0** (`OpenCodeServerProtocol.MINIMUM_SUPPORTED_OPENCODE_VERSION`). Below that, warn once; do not invent compatibility shims for pre-1.18 routes.
- Launch: `opencode serve --hostname 127.0.0.1 --port <port> --print-logs`. Default host `127.0.0.1`; basic-auth username is always `opencode`.
- Port: default mode `Auto select` (`--port 0`); fixed mode sanitizes to `1..65535`, default fixed port `4096`.
- When settings that affect the process change, stop the server and let the next tool-window load restart it.
- Compact layout: OpenCode switches compact(mobile)/wide(desktop) on a `(min-width: 768px)` `matchMedia` query. To force compact, patch `window.matchMedia` for that query and `(max-width: 767px)` **before** the SPA bundle loads (`onLoadStart`) so `createMediaQuery` initializes with compact matches and never subscribes to real resize events. No CSS class overrides — layout is query-driven only. `forceCompactLayout` defaults **on**, so the panel normally renders the mobile layout (classic `session-review-*`/`data-file` review panel); the redesigned v2 review panel (`session-review-v2-*`) appears only with it off (desktop).

## Settings UI

- Path `Settings > Tools > OpenCode Web Panel`; server group label `OpenCode Server`; order: binary, port, password.
- Binary: `Auto detect` or `OpenCode path` (with a `Detect` action that fills an editable path).
- Password controls: edit, generate, show, copy.

## Injection Safeguards

UI-behavior settings (`openFileLinksInIde`, `enableCodeNavigation`, `openDiffsInIde`, `enableChatFileDrop`, `forceCompactLayout`, `hideWebsiteButton`, …) gate **browser-side JS/CSS injection** into the embedded web app. They are **safeguards**, not cosmetics: if an injected behavior breaks the OpenCode UI or conflicts with an update, the user must be able to disable it and get back a clean, unmodified web app.

- Disabled ⇒ generate/inject/schedule nothing; the script builder returns `null`.
- Toggled **off** at runtime ⇒ reload the page so listeners/patches/stylesheets are fully removed — never inject a "disable" script.
- Toggled **on** at runtime ⇒ inject immediately and/or reload as needed.
- Scripts that must run before the SPA bundle (e.g. `forceCompactLayout`) inject in `onLoadStart`, not `onLoadEnd`.
- Never open event streams or other long-lived connections from injected scripts; consume OpenCode events JVM-side instead (see Event & REST Contract).
- Add unit tests asserting disabled builders return `null` and that a toggle-off reloads rather than injects a "disable" script.
- Validate injected JS against a real page before claiming it works (see Validating Against a Real Server) — unit tests only check the script *text*, never real DOM behavior.
- Prefer **locale- and design-independent DOM signals** over label text or Tailwind utility classes: `data-slot`/`data-component` attributes, `href` targets, and sprite-icon references (`use[href="#opencode-icon-<name>"]` — both toast generations render icon *names* into the DOM this way). The project-switch toast suppression matches the permission/question toast purely structurally: toast container + icon slot containing the `checklist`/`bubble-5` sprite icon + an action row; never translated strings.
- Early injection is centralized: `EarlyInjectedFeature` instances (seed → theme → compact → hide-website, order matters) run through `injectEarlyFeature` from `onLoadStart` with early-series retries; builders are re-invoked per attempt and must be idempotent in-page. Post-load features use `InjectedFeature`/`scheduleFeatureScript`. Add new injections to one of these lists — do not hand-roll per-feature flags.
- Injected MutationObservers must be cheap in steady state: hiding is done by an installed stylesheet, and the observer only re-attaches the `<style>` (rAF-debounced) if the SPA replaces `<head>` — never per-mutation `querySelectorAll` work on the whole document.

### Diff navigation DOM contract (`openDiffsInIde`)

The Alt+Click "open diff in IDE" feature (`OpenCodeBrowserSnippets.buildDiffNavigationScript` → `features.OpenCodeDiffNavigation`) is the most DOM-coupled injection: it maps a click to a `(messageID, filePath)` pair from unstable OpenCode SPA internals. Its targets live in the chat timeline — the shared `session-ui` `MessagePart`, rendered the same in compact and desktop — so the gesture is layout-independent. Re-verify the selectors against a live page whenever diffs stop opening:

- **Message id** — nearest `[data-message-id]` ancestor (one per session turn; holds the turn's **user** message id, which is what the diff endpoint is keyed by). Never send an empty or assistant id (both yield an empty diff). (Pre-1.18 `[data-message]` no longer exists in the bundle; the fallback was removed.)
- **Changes/review row** — `[data-file]` (path directly).
- **"Changed files" turn-summary row** — `[data-slot="session-turn-diff-trigger"]` (no `data-file`; reconstruct path from `[data-slot="session-turn-diff-directory"]` + `[data-slot="session-turn-diff-filename"]`).
- **Chat edit/write/patch block** — `[data-component="edit-tool"|"write-tool"|"apply-patch-tool"]` (path from `[data-slot="message-part-directory"]` + `[data-slot="message-part-title-filename"]`; multi-file patch rows from `[data-slot="apply-patch-trigger-content"]` → `apply-patch-directory` + `apply-patch-filename`).
- **Diff indicator** (fallback → whole turn, all files) — `[data-component="diff-changes"]`.

The file-link handler (`buildFileLinkHandlerScript`) early-returns on `event.altKey`, reserving Alt for this gesture.

### Review-panel file links (`openFileLinksInIde`)

Two review panels exist, selected by width: the plugin's default forced-compact mode shows the **classic** panel; with `forceCompactLayout` **off** (desktop) OpenCode shows the redesigned **v2** panel (`session-ui/src/v2/components/session-review-v2*`, a sidebar tree + preview pane). `buildFileLinkHandlerScript` must resolve "open in IDE" in both:

- **Classic** — the per-file "open" button (`[data-slot="session-review-view-button"]` only — no locale-specific aria/title fallbacks); path from a `[data-file]`/`[data-path]`/`session-review-accordion-item` ancestor, else the `session-review-file-info` spans (`session-review-directory` + `session-review-filename`).
- **v2** — no per-file button; resolve from the **preview header** (`[data-slot="session-review-v2-file-name"]` + optional `[data-slot="session-review-v2-file-path"]`, inside `[data-slot="session-review-v2-file-title"]`). Do **not** hijack the sidebar rows (`[data-slot="session-review-v2-sidebar-tree"] button[data-path]`) — those are the SPA's own preview navigation.

Diff Alt+Click from either review panel is inherently empty (session-scoped, no `[data-message-id]` ancestor); the meaningful diff nav is the chat timeline.

**SPA routes are not files.** The capture-phase file-link handler treats many `href`s that start with `/` as local paths. Keep `isOpenCodeAppRoute` (JS) and `isOpenCodeSessionRouteHref` (JVM) in sync and exclude at least:

- `/server/<key>/session[/<id>]` — 1.18 session routes (task/subagent cards, notifications, boot)
- `/new-session…`
- `/` and bare `/<base64url(dir)>` project roots when the segment decodes to an absolute filesystem path
- legacy `/<base64url(dir)>/session[/<id>]` when the segment decodes to an absolute path

Missing an exclusion → subagent cards and sidebar session links get `preventDefault` and never navigate.

### Open-project navigation contract (OpenCode 1.18+)

OpenCode 1.18's SPA is **directoryless** for sessions. Canonical routes:

| Route | Role |
|---|---|
| `/server/<base64url(origin)>/session/<ses_…>` | Real session (SPA route requires `:id`) |
| `/server/<base64url(origin)>/session` | Id-less shell only — not a lasting SPA destination |
| `/new-session?draftId=…` | Draft composer |
| `/<base64url(dir)>/session[/<id>]` | Legacy; SPA may still emit it (sidebar/notifications) and redirects to the server form |

Which project a directoryless URL shows is **not** in the path — only in the SPA's `opencode.global.dat:server` localStorage (`lastProject` / `projects`), which lives in the **application-shared, persistent** JCEF profile. Cold-loading the legacy bare directory route **without** a session id crashes the SPA error boundary ("Unable to retrieve session" on every send). Consequences in `buildOpenProjectScript` / `OpenCodeWebToolWindowContent` / `OpenCodeServerProtocol`:

- **Boot URL** — `buildServerSessionUrl(serverUrl, sessionId?)`, never `buildProjectUrl` (legacy directory route) and never the bare server root alone.
- **Most recent conversation** — when the setting is on, resolve the latest **parent** session via `GET /api/session` (`max(time.updated)`, skip `parentID`) **before** the first load when possible, boot with that id, and pass the same id into the open-project navigate series (do not re-fetch for the same page load). Boot intent is **one-shot**: clear it once the target session is open so later SPA navigations only re-seed and never yank the user back.
- **Open-project seed** — inject seed-only (`lastProject` + `projects` for this panel's directory into `opencode.global.dat:server`) from **`onLoadStart`** (before the SPA bundle reads localStorage). Post-load inject alone races the shared browser profile and can leave the panel on another IDE project's workspace. Worktree dedupe must treat Windows separators/drive case as equal.
- **Open-project navigate** — schedule from **`onLoadEnd`** only when the one-shot boot intent still has a session id. Navigate **once** to `/server/<key>/session/<id>`. Skip only when already on that **target** session (or the sessionStorage guard `opencode.intellij.project.opened:<dir>` already records that target) — **not** when any `/session/<otherId>` is open. Cancel prior open-project delay series on `onLoadStart`; stop retrying once the target session id is in the URL.
- **Never** treat every `/server/…` URL as "already at the destination" JVM-side for project identity (`routeDirectoryFromUrl` returns null for directoryless routes; `isOpenCodeProjectDestination` only matches legacy directory-encoded paths). Project binding is the seed, not the path.
- **Notifications "Show in OpenCode"** — open via `buildServerSessionUrl(serverUrl, sessionID)`; `isOpenCodeRouteAlreadyOpen` treats the same `ses_…` under legacy vs server path shapes as already open (unless the target pins a different query).
- A renamed project directory that keeps showing its **old path/name** is opencode's server-side stale `project.worktree` ([anomalyco/opencode#35240](https://github.com/anomalyco/opencode/issues/35240)) — not fixable from the plugin. Workaround: update `project.worktree` (and old sessions' `directory`/`path`) in `~/.local/share/opencode/opencode.db` while no opencode instance runs.

## Validating Against a Real Server

Injection fixes and wire-contract checks must be validated against a live server — not `about:blank`, synthetic pages, or unit tests. DOM/wire contracts below last validated vs **opencode 1.18.2**; re-verify on OpenCode updates.

- **Gate order** — after every OpenCode update, run the DOM gate first, then the wire gate, then feature-specific Playwright checks. Missing/unclassified markers, operations, or shapes block release until the matching integration is re-validated.
- **DOM gate** — `OPENCODE_SERVER_PASSWORD=testpw123 scripts/check-dom-contract.sh http://127.0.0.1:<port>` checks every DOM/JS marker used by injected scripts plus directly declared OpenCode Persist keys. The 1.18.2 baseline is **56 markers + 14 classified Persist keys**. Run it after changing selectors, SPA storage keys, media queries, icon names, command shortcuts, or injection targets. Add/change the marker or Persist classification in the same commit as the integration.
- **Wire gate** — `OPENCODE_SERVER_PASSWORD=testpw123 scripts/check-wire-contract.sh http://127.0.0.1:<port> <project-directory>` checks every consumed OpenAPI operation/operation ID for presence and deprecation, required event names, and live REST root/envelope shapes. The 1.18.2 baseline is **13 operations + 5 live roots + 8 event types**; the script self-tests every required operation with a negative mutation. Run it after changing REST/event consumers or protocol parsers, and update its operation/event/probe inventory in the same commit.
- **Presence is not semantics** — passing gates cannot prove selector meaning, private storage shape, event payload semantics, or synthetic-event acceptance. Always run feature-specific Playwright against the real page after touching diff navigation, project seeding, file-link/code interception, toast suppression, compact/theme patches, or chat paste/drop. Unit tests only check generated script text and JVM state machines.

- Start: `OPENCODE_SERVER_PASSWORD=testpw123 opencode serve --hostname 127.0.0.1 --port <port> --print-logs`. Health: `curl -u opencode:testpw123 http://127.0.0.1:<port>/api/health` ⇒ `{"healthy":true}` (curl with `-u` first to separate auth from app problems).
- **Basic auth covers everything** — the SPA's static assets (`/assets/*.js|css`) and all API routes; only `/site.webmanifest` and the web-app-manifest PNGs are public.
- Do **not** use the `?auth_token=<base64(user:pass)>` query param: it authenticates only the initial HTML, so asset requests then hang forever in headless Playwright (symptom: navigation times out, title is `OpenCode`, `#root` stays empty, no console errors — looks like a broken SPA but is pure auth). Instead send the `Authorization` header on **every** request (what the plugin does via `onBeforeResourceLoad`): `page.setExtraHTTPHeaders({ Authorization: 'Basic ' + btoa('opencode:testpw123') })` (or `httpCredentials`), set **before** the first `goto`. For `opencode:testpw123` the header is `Basic b3BlbmNvZGU6dGVzdHB3MTIz`.
- Early-injection scripts: `page.addInitScript(script)` before `goto` (mirrors `onLoadStart`); post-load: `page.evaluate(script)` after mount (mirrors `onLoadEnd`).
- **Session route (1.18):** `/server/<base64url(origin)>/session/<ses_…>` — `serverKey` is base64url of the origin (no padding), same encoding as `encodeDirectory`. Example: origin `http://127.0.0.1:4096` → path `/server/<key>/session/ses_…`. Prefer this over the legacy `/<base64url(dir)>/session[/<id>]` form (still accepted by the SPA as a redirect).
- Encode helper: `printf '%s' "<value>" | base64 | tr '+/' '-_' | tr -d '='`.
- The served UI is localized (e.g. German labels on a German system); don't assert English-only UI strings.

## Event & REST Contract

The server publishes an **OpenAPI 3.1 spec** at `GET /doc` while running; the JS SDK (`@opencode-ai/sdk`) is generated from it but can lag, so cross-check against a live server or the source at `/Users/moritz/Desktop/git/opencode` (`packages/schema/src/`). The plugin consumes events **JVM-side**: `OpenCodeGlobalEventStream` (owned by `SharedOpenCodeServerManager`) holds the single `/global/event` reader and publishes parsed events on the `OpenCodeGlobalEventListener` application topic, emitting `connected()` after each (re)connect so consumers can re-seed reduced state via REST. Subscribe new consumers there; REST parsers live in `OpenCodeServerProtocol` with adjacent unit tests. Re-verify wire shapes against a real server after touching them or on OpenCode updates.

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

Many other types exist but are unconsumed; durable events also re-emit as `sync` wrappers (`syncEvent.type = "<type>.<version>"`). See `packages/schema/src/session-event.ts`. There is NO `lsp.client.diagnostics` — only `lsp.updated`.

**REST shapes** (all take `?directory=`):

- `GET /session/status` → `{"ses_...": {"type": "busy"|"retry"|"idle"}, ...}` (`parseBusySessionIds`).
- `GET /permission`, `GET /question` → array of request objects with `id` (`parsePendingRequestIds`).
- `GET /session/{id}` → session object with `title` and optional `parentID`, bare or wrapped in `{"data": {...}}` (`parseSessionInfo`).
- `GET /api/session?order=desc&limit=N` → `{"data": [{"id": "ses_...", "parentID"?, "time": {"created","updated"}}, ...], "cursor": {...}}` (`parseSessionList`). Creation-ordered (**not** by `time.updated`) and includes subagent children, so "most recent activity" callers must pick `max(time.updated)` themselves and skip entries with a `parentID`. The server canonicalizes directories (macOS `/var/...` → `/private/var/...`); query with the same directory value used elsewhere.
- `GET /api/session/{sessionID}/message?order=desc&limit=N` (v2) → `{"data": [SessionMessage...], "cursor": {...}}` (`fetchLastMessageJson` → `extractFirstDataObject`, for interrupted-session recovery). Each `SessionMessage` is a discriminated union on top-level `type`; `isInterruptedLastMessage`/`isSuspendSeveredLastMessage`/`isUnsettledTurnFromBefore` read `type` (`"user"`/`"assistant"`), `error`, `time.{created,completed}`, and `content[]` tool parts (`type:"tool"`, `state.status ∈ pending|running|completed|error`). **Validation caveat:** this v2 store populates only for sessions created through the SPA's v2 API (`POST /api/session` + `POST /api/session/{id}/prompt`); `opencode run --attach` leaves messages only in the v1 `/session/{id}/message` store (v2 returns `[]`) — generate test turns natively, not via `--attach`.
- `GET /session/{sessionID}/diff?directory=<dir>&messageID=<msg_...>` → `Array<SnapshotFileDiff>`, `SnapshotFileDiff = {file?, patch?, additions, deletions, status?: "added"|"deleted"|"modified"}` (`fetchSessionDiff`/`parseSessionDiff`). `patch` is a unified diff string; there is **no `before`/`after` field** (the legacy gen showing them is wrong). The same shape rides the `session.diff` event. **Keyed by the turn's *user* message id**: omitted or assistant `messageID` → `[]`; only the user `msg_...` returns that turn's diffs. Diffs come from persisted git snapshots (`<data>/snapshot/<projectID>/…`) and **survive a server restart**. Alt+Click resolves the user message id from the DOM; `OpenCodeDiffNavigation` reconstructs before/after from each `patch` via `OpenCodeUnifiedDiff`.
- `POST /permission/{requestID}/reply?directory=<dir>` body `{"reply": "once"|"always"|"reject"}` (op `permission.reply`) — the write endpoint for permissions (`replyToPermission`; `requestID` is the `per_...` id from `permission.asked`/`.replied`). Successor to deprecated `POST /session/{sessionID}/permissions/{permissionID}` + `{"response": ...}` (op `permission.respond`). Don't regress: a `{"response": ...}` body → `400 Missing key ["reply"]`; unknown id → `404 PermissionNotFoundError`.

If a shape changed, fix the matching parser and its unit test, then update this section.

## Verification

- Primary: `./gradlew check` (use `rtk ./gradlew check` in this environment). Run after Kotlin, Gradle, plugin-descriptor, settings, protocol, or lifecycle changes.
- README-only changes need no Gradle check unless they touch the plugin-description block between the `<!-- Plugin description -->` markers.

## Git

- Don't commit unless explicitly asked. Before committing, inspect `git status`, `git diff`, and recent commits; stage only intended files; don't revert unrelated local changes.
