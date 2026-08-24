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
- Child `HTTP_PROXY`/`HTTPS_PROXY` follow the Server-tab **HTTP Proxy** setting (`OpenCodeProxyMode`): IDE proxy (manual, or auto-detect/PAC resolved via `JdkProxyProvider` for `https://example.com`), inherited env, or stripped. Destination-specific PAC rules cannot be expressed as one env var. Loopback is always added to `NO_PROXY` when a proxy is set.
- Port: default mode `Auto select` (`--port 0`); fixed mode sanitizes to `1..65535`, default fixed port `4096`.
- When settings that affect the process change, stop the server and let the next tool-window load restart it.
- Compact layout: OpenCode switches compact(mobile)/wide(desktop) on a `(min-width: 768px)` `matchMedia` query. To force compact, patch `window.matchMedia` for that query and `(max-width: 767px)` **before** the SPA bundle loads (`onLoadStart`) so `createMediaQuery` initializes with compact matches and never subscribes to real resize events. No CSS class overrides — layout is query-driven only. `forceCompactLayout` defaults **on**, so the panel normally renders the mobile layout (classic `session-review-*`/`data-file` review panel); the redesigned v2 review panel (`session-review-v2-*`) appears only with it off (desktop).

## Settings UI

- Path `Settings > Tools > OpenCode Web Panel`; server group label `OpenCode Server`; order: binary, port, password, HTTP proxy.
- Two pages sit under Tools: the application one (`OpenCode Web Panel`) and the project-scoped one (`OpenCode Web Panel (Project)`, just the project directory). The suffix keeps the tree from showing the same label twice; `plugin.xml`'s `displayName` supplies the label before the class loads, so it must match `PROJECT_SETTINGS_DISPLAY_NAME`.
- The application page is a tabbed pane sized to the **selected** tab (`OpenCodeSettingsTabbedPane`) — `JTabbedPane` otherwise reports the tallest tab and gives a short tab the long tab's scrollbar.
- Binary: `Auto detect` or `OpenCode path` (with a `Detect` action that fills an editable path).
- Password controls: edit, generate, show, copy.

## Injection Safeguards

UI-behavior settings (`openFileLinksInIde`, `enableCodeNavigation`, `openDiffsInIde`, `enableChatFileDrop`, `forceCompactLayout`, `hideWebsiteButton`, …) gate **browser-side JS/CSS injection** into the embedded web app. They are **safeguards**, not cosmetics: if an injected behavior breaks the OpenCode UI or conflicts with an update, the user must be able to disable it and get back a clean, unmodified web app.

- Disabled ⇒ generate/inject/schedule nothing; the script builder returns `null`.
- Toggled **off** at runtime ⇒ reload the page so listeners/patches/stylesheets are fully removed — never inject a "disable" script.
- Toggled **on** at runtime ⇒ inject immediately and/or reload as needed.
- Scripts that must run before the SPA bundle (e.g. `forceCompactLayout`, event-stream watchdog) are registered with Chromium `Page.addScriptToEvaluateOnNewDocument` before navigation, and still injected from `onLoadStart` as a fallback. `executeJavaScript` from `onLoadStart` is queued and can lose the race with the SPA bundle.
- Never open event streams or other long-lived connections from injected scripts; consume OpenCode events JVM-side instead (see Event & REST Contract).
- Add unit tests asserting disabled builders return `null` and that a toggle-off reloads rather than injects a "disable" script.
- Validate injected JS against a real page before claiming it works (see Validating Against a Real Server) — unit tests only check the script *text*, never real DOM behavior.
- Prefer **locale- and design-independent DOM signals** over label text or Tailwind utility classes: `data-slot`/`data-component` attributes, `href` targets, and sprite-icon references (`use[href="#opencode-icon-<name>"]` — both toast generations render icon *names* into the DOM this way). The project-switch toast suppression matches the permission/question toast purely structurally: toast container + icon slot containing the `checklist`/`bubble-5` sprite icon + an action row; never translated strings.
- Early injection is centralized: `EarlyInjectedFeature` instances (seed → matchMedia → hide-website → event-stream watchdog, order matters) are combined into the document-start script and also run through `injectEarlyFeature` from `onLoadStart` with early-series retries; builders are re-invoked per attempt and must be idempotent in-page. Compact layout and IDE theme share one `matchMedia` wrapper. Post-load features use `InjectedFeature`/`scheduleFeatureScript`. Add new injections to one of these lists — do not hand-roll per-feature flags.
- A newly created empty JCEF browser has no renderer/DevTools page target even after `onAfterCreated`. `OpenCodeDocumentStartInjector` briefly loads `about:blank`, waits for a main document, registers the combined document-start script, and only then lets the caller navigate to OpenCode. Do not use a non-null `devToolsClient` as readiness proof, and do not reuse this transient bootstrap for the stopped-state card (the Windows Stop→Start path must keep the existing document alive).
- Injected MutationObservers must be cheap in steady state: hiding is done by an installed stylesheet, and the observer only re-attaches the `<style>` (rAF-debounced) if the SPA replaces `<head>` — never per-mutation `querySelectorAll` work on the whole document.

### Page event-stream watchdog (`recoverStalledEventStream`)

OpenCode's page-side reader (`packages/app/src/context/server-sdk.tsx`) reconnects **only when its response iterator ends or throws**. It has no read timeout, and its only resume hook is `pageshow` with `event.persisted` — which never fires for a live JCEF page. A socket the OS severed without resetting it (sleep, VPN/adapter change; routinely half-open on Windows) therefore delivers neither bytes nor an error and `for await` blocks forever. Verified live against 1.18.10 behind a stalling proxy: the page issues **one** `/global/event` request and then ignores every server-side change until reloaded.

Two user-visible bugs share this single root cause, so treat them as one:

- the panel refuses new messages and looks frozen until a manual reload;
- a permission answered from an IDE notification stays on screen — the SPA removes a request **only** on the `permission.replied` event (`server-session.ts`), never by polling, while the plugin's JVM reply succeeds on its own connection.

`buildEventStreamWatchdogScript` wraps `window.fetch` (and `globalThis.fetch` when it still points at the original) before the SPA bundle captures it — document-start first, `onLoadStart` as fallback. Do not set the installed flag until `fetch`/`AbortController` are actually wrappable, or a too-early attempt permanently no-ops the retries. The OpenCode SDK calls `fetch(new Request(url))`; match `Request.url` and `URL.href` as well as string URLs, and rebuild a `Request` when overriding its signal. The wrapper pipes the event-stream body through a reader that aborts the request after 45s without a byte — three to four missed `server.heartbeat`s (10s cadence), the same budget as the JVM reader's read timeout. The abort surfaces as an ordinary stream error, which is the signal OpenCode's own reconnect loop already handles, so **recovery must stay out of SPA internals — never reload the page or reimplement the stream here.** `buildForceEventReconnectScript` cuts the stream on demand and is fired from the suspend-resume listener; it is a no-op when the watchdog is off.

Reconnect also repairs state missed while dead: the server emits `server.connected` on **every** new connection (verified), and the SPA re-bootstraps active directories on it, re-seeding pending permissions from `GET /permission` and clearing sessions that have none.

Match on `URL.pathname` for `/global/event`, `/event`, and `/api/event` so origin rewrites and `directory`/`workspace` query params cannot bypass the watchdog, and chain the caller's `AbortSignal` into the wrapper or the SPA's own `stop()` leaks the previous connection on every reconnect.

### Diff navigation DOM contract (`openDiffsInIde`)

The Ctrl/Cmd+Click or Alt+Click "open diff in IDE" feature (`OpenCodeBrowserSnippets.buildDiffNavigationScript` → `features.OpenCodeDiffNavigation`) maps a click to `messageID` / `filePath` / `partID`. Chat edit/write/patch targets live in the shared `session-ui` `MessagePart` (layout-independent). Re-verify the selectors against a live page whenever diffs stop opening:

- **Message id** — nearest `[data-message-id]` ancestor (one per session turn; holds the turn's **user** message id). Used by `session.diff` for review/turn-summary/whole-turn. Never send an empty or assistant id (both yield an empty snapshot diff). (Pre-1.18 `[data-message]` no longer exists in the bundle; the fallback was removed.)
- **Part id** — nearest `[data-timeline-part-id]` on `[data-component="tool-part-wrapper"]` (`prt_…`). Chat edit/write/patch use this, not reconstructed title text. There is no GET-by-part (only DELETE); the JVM pages `GET /session/{id}/message?limit=50` via `X-Next-Cursor` / `before` until that id appears in `parts[]`.
- **Changes/review row** — `[data-file]` (path directly) → `session.diff`.
- **"Changed files" turn-summary row** — `[data-slot="session-turn-diff-trigger"]` (no `data-file`; reconstruct path from `[data-slot="session-turn-diff-directory"]` + `[data-slot="session-turn-diff-filename"]`) → `session.diff`.
- **Chat edit/write/single-file patch** — `[data-component="edit-tool"|"write-tool"|"apply-patch-tool"]` → `partID` only. Edit: `state.metadata.filediff.{file,patch}`. Write: no patch on the part (`state.input.filePath` / `metadata.filepath`) then `session.diff` for that file. Apply-patch: `state.metadata.files[]`.
- **Multi-file patch row** — `[data-slot="apply-patch-trigger-content"]` → `partID` plus reconstructed `relativePath` from `[data-slot="apply-patch-directory"]` + `[data-slot="apply-patch-filename"]` to pick the `files[]` row (exact / unique-suffix match against `relativePath`, never the turn snapshot).
- **Diff indicator** (fallback → whole turn, all files) — `[data-component="diff-changes"]` outside a tool block → `session.diff`.

The file-link handler (`buildFileLinkHandlerScript`) early-returns on Alt and Ctrl/Cmd+Click, reserving those for this gesture. Diff-nav is injected first so it wins on overlapping targets.

### Review-panel file links (`openFileLinksInIde`)

Two review panels exist, selected by width: the plugin's default forced-compact mode shows the **classic** panel; with `forceCompactLayout` **off** (desktop) OpenCode shows the redesigned **v2** panel (`session-ui/src/v2/components/session-review-v2*`, a sidebar tree + preview pane). `buildFileLinkHandlerScript` must resolve "open in IDE" in both:

- **Classic** — the per-file "open" button (`[data-slot="session-review-view-button"]` only — no locale-specific aria/title fallbacks); path from a `[data-file]`/`[data-path]`/`session-review-accordion-item` ancestor, else the `session-review-file-info` spans (`session-review-directory` + `session-review-filename`).
- **v2** — no per-file button; resolve from the **preview header** (`[data-slot="session-review-v2-file-name"]` + optional `[data-slot="session-review-v2-file-path"]`, inside `[data-slot="session-review-v2-file-title"]`). Do **not** hijack the sidebar rows (`[data-slot="session-review-v2-sidebar-tree"] button[data-path]`) — those are the SPA's own preview navigation.

Diff Alt+Click from either review panel is inherently empty (session-scoped, no `[data-message-id]` ancestor); the meaningful diff nav is the chat timeline.

**Chat links arrive percent-encoded.** OpenCode renders markdown through marked, whose `cleanUrl` runs `encodeURI(href)` and then restores literal `%` via `.replace(/%25/g, "%")`; models also emit escaped paths themselves. A relative link to a path with a space or any non-ASCII character therefore reaches the JVM as `docs/My%20File.md` / `docs/%C3%9Cmlaut.md`, while plain ASCII paths pass through untouched — the reason only *some* relative links resolved. Rules the resolver must keep:

- `decodeFileLinkPaths` returns the decoded **and** the raw spelling (a name may legitimately contain `%`, and `cleanUrl` passes it through). `file:` URLs take `rawPath`/`rawSchemeSpecificPart` so the single decode is not applied twice.
- Each spelling carries the position that applies when *it* resolves, so `src/Main.kt:42` can be probed stripped (line reference) and whole (a file really named `…:42`) without the line leaking onto the wrong reading. Chat code-nav accepts the same locators plus `:L123-1234`, `#L42-L57`, `(42,13)`, and `(L98)`. A locator immediately after an inline `<code>` span is glued onto the span text (`Main.kt`:L42, `Foo.java` (L10)). `Type.method()` / `pkg.Type.method(…)` resolve to the type. Filename-index fallback only opens a **distinct** workspace match. Stack-trace tokens in `<pre>` / `[data-slot="bash-pre"]` / `[data-component="tool-output"]` use the same locators (any `.[A-Za-z][A-Za-z0-9]{0,8}` extension, not Java-only) plus Python `File "path", line N`; backticks may split path and locator there and are glued across; not PascalCase / bare filenames.
- `file:<relative>` is an **opaque** URI (`path` is null) — the exact form `localFileDropText` writes.
- Root-relative `/src/Main.kt` is absolute on Unix but *not* on Windows, where `resolve` would keep only the drive root. Decide on the href **text** (`startsWith('/')`), never `Path.isAbsolute`. A genuinely absolute path is tried literally first, so a project that also has `etc/`/`tmp/` yields the system file for `/etc/x`.
- Percent-decoding can produce text that is not a legal path (NUL anywhere, `<>?*|"` on Windows); every `Path.of` on link input must be guarded or the exception escapes the click handler.

**Incomplete references are guessed** (`bestGuessFileLinkPath`), only after every exact candidate across every base missed: first drop leading segments (`packages/app/src/x` under a base already inside `packages/app`), then a bounded walk for a file whose trailing segments match, ranked by matched segments → depth → path. The walk prunes `.`-directories plus build/VCS output and is capped (`SEARCH_MAX_DEPTH`, `SEARCH_MAX_VISITED_ENTRIES`). Because this can touch the filesystem broadly, `OpenCodeIdeNavigation.openFileLinkInIde` resolves on a pooled thread — never on the browser callback thread; a newer click invalidates any older in-flight result.

**SPA routes are not files.** The capture-phase file-link handler treats many `href`s that start with `/` as local paths. Keep `isOpenCodeAppRoute` (JS) and `isOpenCodeSessionRouteHref` (JVM) in sync and exclude at least:

- `/server/<key>/session[/<id>]` — 1.18 session routes (task/subagent cards, notifications, boot)
- `/new-session…`
- `/` and bare `/<base64url(dir)>` project roots when the segment decodes to an absolute filesystem path
- legacy `/<base64url(dir)>/session[/<id>]` when the segment decodes to an absolute path

Missing an exclusion → subagent cards and sidebar session links get `preventDefault` and never navigate.

Root-relative `/…` hrefs are files only when the last path segment looks like a file (`Main.kt`, `foo.md`). Unknown SPA paths like `/future-page` are left to the app. Windows drive/UNC paths are unchanged.

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
- **Most recent conversation** — boot the session-less `/server/<key>/session` shell **immediately** so the panel never sits blank waiting on REST. In parallel, resolve the latest **parent** session via `GET /api/session` (`max(time.updated)`, skip `parentID`) and navigate once when that id arrives. Do **not** drop the one-shot navigate intent while the listing is still in flight (`OpenCodeStartupNavigation`). Clear the intent once the target session is open so later SPA navigations only re-seed and never yank the user back. If the main document never reaches a successful `onLoadEnd` within 20s, retry the load up to twice (`OpenCodePageLoadWatchdog`). That watchdog must use its own Alarm — `onLoadStart` cancels `openProjectAlarm`. Do **not** `stopLoad` a navigation that is still inside the 20s budget.

- Headful JCEF tests live under `src/test/kotlin/.../jcef/` and follow JetBrains `JBCefTestHelper` (ApplicationRule, show a JFrame, wait `onLoadEnd`, 60s CEF-init budget). They are opt-in: `rtk ./gradlew test -Pjcef`. `./gradlew check` stays headless.
- **Open-project seed** — inject seed-only (`lastProject` + `projects` for this panel's directory into `opencode.global.dat:server`) from **`onLoadStart`** (before the SPA bundle reads localStorage). Post-load inject alone races the shared browser profile and can leave the panel on another IDE project's workspace. Worktree dedupe must treat Windows separators/drive case as equal. Seed the **OpenCode-canonical** directory (`canonicalOpenCodeDirectory` / realpath, then the spelling from `GET /api/session` `location.directory` when it is the same folder). The SPA's `session.get` is exact `session.directory === sdk().directory`; IntelliJ `basePath` (`/var` vs `/private/var`, symlink aliases) makes every send fail with "Unable to retrieve session".
- **Last-viewed session pointer** — when a target session id is known, also point `opencode.global.dat:layout.page` → `lastProjectSession[<directory>] = {directory, id, at}` at it. `directory` in that value must be the **session's** stored path (`location.directory` / `directory` from the listing), not the IDE project path: `openSession` then `session.get` both require that exact string. The SPA's project bootstrap redirects to that pointer **even when the URL already carries a valid session id**, so without it the SPA silently overrides the navigation with whatever it showed last, and the panel opens the wrong conversation whenever a session gained activity outside the panel. Write it only when a session id is resolved (never on the seed-only injection), skip when the id is unchanged (the retry series must not churn the mirrored snapshot), and apply the same fail-soft rule as the project seed for unrecognized schemas.
- **Open-project navigate** — seed-only from injected JS (`navigate = false`). When the one-shot boot intent has a session id and the page is still on the id-less shell, navigate **once** with JVM `loadURL` to `/server/<key>/session/<id>`. Skip when any `/session/<id>` is already open. Cancel prior open-project delay series on `onLoadStart`.
- **Never** treat every `/server/…` URL as "already at the destination" JVM-side for project identity (`routeDirectoryFromUrl` returns null for directoryless routes; `isOpenCodeProjectDestination` only matches legacy directory-encoded paths). Project binding is the seed, not the path.
- **Notifications "Show in OpenCode"** — open via `buildServerSessionUrl(serverUrl, sessionID)`; `isOpenCodeRouteAlreadyOpen` treats the same `ses_…` under legacy vs server path shapes as already open (unless the target pins a different query).
- A renamed project directory that keeps showing its **old path/name** is opencode's server-side stale `project.worktree` ([anomalyco/opencode#35240](https://github.com/anomalyco/opencode/issues/35240)) — not fixable from the plugin. Workaround: update `project.worktree` (and old sessions' `directory`/`path`) in `~/.local/share/opencode/opencode.db` while no opencode instance runs.

## Validating Against a Real Server

Injection fixes and wire-contract checks must be validated against a live server — not `about:blank`, synthetic pages, or unit tests. DOM/wire contracts below last validated vs **opencode 1.18.22**; re-verify on OpenCode updates.

- **Gate order** — after every OpenCode update, run the DOM gate first, then the wire gate, then feature-specific Playwright checks. Missing/unclassified markers, operations, or shapes block release until the matching integration is re-validated.
- **DOM gate** — `OPENCODE_SERVER_PASSWORD=testpw123 scripts/check-dom-contract.sh http://127.0.0.1:<port>` checks every DOM/JS marker used by injected scripts plus directly declared OpenCode Persist keys. The 1.18.22 baseline is **59 markers + 14 classified Persist keys**. Run it after changing selectors, SPA storage keys, media queries, icon names, command shortcuts, or injection targets. Add/change the marker or Persist classification in the same commit as the integration.
- **Wire gate** — `OPENCODE_SERVER_PASSWORD=testpw123 scripts/check-wire-contract.sh http://127.0.0.1:<port> <project-directory>` checks every consumed OpenAPI operation/operation ID for presence and deprecation, required event names, and live REST root/envelope shapes. The 1.18.22 baseline is **14 operations + 5 live roots + 8 event types**; the script self-tests every required operation with a negative mutation. Run it after changing REST/event consumers or protocol parsers, and update its operation/event/probe inventory in the same commit.
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

**Permission v1/v2 are two disjoint stores — the panel is on v1.** The SPA's `detectServerProtocol` probes `/global/health` first and picks **v1** whenever it answers `{"healthy":true}` (it does on 1.18.10), so the embedded panel uses the v1 prompt path. There, `SessionTools.resolve` hands tools a `Tool.Context.ask` bound to the **v1** `Permission.Service`, which emits `permission.asked`/`permission.replied` and is answered by `POST /permission/{requestID}/reply`. The parallel v2 service (`POST /api/session/{sessionID}/permission`, `…/permission/{requestID}/reply`) emits **only** `permission.v2.asked` and its requests appear **only** in `GET /api/session/{id}/permission` — never in v1 `GET /permission` (verified live on 1.18.10 by creating one). Consequence: v1-only handling is correct today, but if a future server drops `/global/health` the SPA flips to v2 and the plugin would stop seeing permissions entirely rather than degrade. `detectEmbeddedProtocol` mirrors that probe after every start and warns once when it would pick v2. Re-check this before assuming a permission bug is a reply-endpoint problem.
- `question.asked` → `{id, sessionID, questions: QuestionInfo[], tool?}`
- `question.replied` → `{sessionID, requestID, answers: string[][]}`
- `question.rejected` → `{sessionID, requestID}`

Many other types exist but are unconsumed; durable events also re-emit as `sync` wrappers (`syncEvent.type = "<type>.<version>"`). See `packages/schema/src/session-event.ts`. There is NO `lsp.client.diagnostics` — only `lsp.updated`.

**REST shapes** (all take `?directory=`):

- `GET /session/status` → `{"ses_...": {"type": "busy"|"retry"|"idle"}, ...}` (`parseBusySessionIds`).
- `GET /permission`, `GET /question` → array of request objects with `id` (`parsePendingRequestIds`).
- `GET /session/{id}` → session object with `title` and optional `parentID`, bare or wrapped in `{"data": {...}}` (`parseSessionInfo`).
- `GET /api/session?order=desc&limit=N` → `{"data": [{"id": "ses_...", "parentID"?, "time": {"created","updated"}, "location": {"directory"} | "directory"?}, ...], "cursor": {...}}` (`parseSessionList`). Creation-ordered (**not** by `time.updated`) and includes subagent children, so "most recent activity" callers must pick `max(time.updated)` themselves and skip entries with a `parentID`. Prefer `location.directory` (v2) then `directory` for `lastProjectSession.directory`. The server canonicalizes directories (macOS `/var/...` → `/private/var/...`); query with the same directory value used elsewhere.
- Interrupted-session recovery reads the **last message** via `fetchLastMessageJson` (v1 first, v2 fallback) and classifies it with `isInterruptedLastMessage` / `isSuspendSeveredLastMessage` / `isUnsettledTurnFromBefore`. Those classifiers expect a flat shape `{type:"user"|"assistant", time:{created,completed?}, error?, content:[{type:"tool", state:{status ∈ pending|running|completed|error}}…]}`.
  - **v1 (what the embedded SPA actually writes)** — `POST /session` + `POST /session/{id}/prompt_async`; messages at `GET /session/{sessionID}/message?directory=<dir>&limit=1` (op `session.messages`) → bare array of `{info:{role,time,error?}, parts:[…]}`. With `limit=1` the server returns the **newest** message. `normalizeLastMessageForClassification` maps `info.role` → `type` and `parts` → `content`.
  - **v2** — `GET /api/session/{sessionID}/message?order=desc&limit=N` → `{"data":[SessionMessage…],"cursor":{…}}`. Populated only for sessions created through the v2 API; for SPA sessions it returns `{"data":[]}`. Used as a fallback only.
  - Continue: `POST /api/session/{sessionID}/prompt` body `{"prompt":{"text":"Continue"},"resume":true}` works for both v1- and v2-created sessions (returns `delivery:"steer"`).
- `GET /session/{sessionID}/diff?directory=<dir>&messageID=<msg_...>` → `Array<SnapshotFileDiff>`, `SnapshotFileDiff = {file?, patch?, additions, deletions, status?: "added"|"deleted"|"modified"}` (`fetchSessionDiff`/`parseSessionDiff`). `patch` is a unified diff string; there is **no `before`/`after` field** (the legacy gen showing them is wrong). The same shape rides the `session.diff` event. **Keyed by the turn's *user* message id**: omitted or assistant `messageID` → `[]`; only the user `msg_...` returns that turn's diffs. Diffs come from persisted git snapshots (`<data>/snapshot/<projectID>/…`) and **survive a server restart**. Used for review-panel / turn-summary / whole-turn Alt+Click, and as the write-tool fallback. `OpenCodeDiffNavigation` reconstructs before/after from each `patch` via `OpenCodeUnifiedDiff`.
- Chat edit/patch Alt+Click loads the tool part from `GET /session/{sessionID}/message?directory=<dir>&limit=50` (op `session.messages`; further pages via `X-Next-Cursor` + `before`). Bare array of `{info, parts}`. Tool parts live on the **assistant** message; the user `msg_` from `[data-message-id]` does not contain them. No GET `/part/{partID}` exists (that route is DELETE only). Shapes (`fetchToolPartChange` / `parseToolPartChange`):
  - edit: `state.metadata.filediff = {file, patch, additions, deletions}` (`file` is absolute)
  - apply_patch: `state.metadata.files[] = {filePath, relativePath, type, patch, additions, deletions, movePath?}`
  - write: no patch; `state.input.filePath` / `state.metadata.filepath` then `session.diff` for that file
- `POST /permission/{requestID}/reply?directory=<dir>` body `{"reply": "once"|"always"|"reject"}` (op `permission.reply`) — the write endpoint for permissions (`replyToPermission`; `requestID` is the `per_...` id from `permission.asked`/`.replied`). Successor to deprecated `POST /session/{sessionID}/permissions/{permissionID}` + `{"response": ...}` (op `permission.respond`). Don't regress: a `{"response": ...}` body → `400 Missing key ["reply"]`; unknown id → `404 PermissionNotFoundError`.

If a shape changed, fix the matching parser and its unit test, then update this section.

## Verification

- Primary: `./gradlew check` (use `rtk ./gradlew check` in this environment). Run after Kotlin, Gradle, plugin-descriptor, settings, protocol, or lifecycle changes.
- README-only changes need no Gradle check unless they touch the plugin-description block between the `<!-- Plugin description -->` markers.

## Git

- Don't commit unless explicitly asked. Before committing, inspect `git status`, `git diff`, and recent commits; stage only intended files; don't revert unrelated local changes.
