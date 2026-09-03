<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# OpenCode Web Panel Changelog

## [Unreleased]

### Fixed

- Reload the panel once when a lazy-loaded OpenCode chunk fails to fetch, instead of leaving
  OpenCode's error boundary on screen until a manual restart.

## [1.13.4] - 2026-09-02

### Fixed

- Restart OpenCode Server also recreates the embedded browser, so a stuck or crashed panel can recover.
- Opening the panel no longer fails when JCEF reports no document yet (`RemoteBrowser.hasDocument` NPE).

## [1.13.3] - 2026-08-30

### Added

- Cycle Agent Reverse (Ctrl+Shift+., Cmd+Shift+. on macOS) selects the previous OpenCode agent.

## [1.13.2] - 2026-08-28

### Fixed

- On Windows, permission and question prompts no longer leave leftover pixels after they
  appear or dismiss.

## [1.13.1] - 2026-08-28

### Fixed

- Auto-Accept Permissions now covers nested subagent sessions, including prompts that arrive
  as soon as a child session is created.

## [1.13.0] - 2026-08-27

### Removed

- "Open the most recent conversation on startup". The panel binds the project directory
  and leaves the conversation to OpenCode.

### Fixed

- The panel no longer logs an error on Windows when it opens after being closed.

## [1.12.1] - 2026-08-26

### Fixed

- The stopped state is a centered empty card with one Start button. The status strip no longer
  repeats Start.

## [1.12.0] - 2026-08-26

### Added

- Gear menu: New Session, Project Directory, and Keyboard Shortcuts.
- A stopped-state card with a Start button instead of a blank panel.

### Fixed

- Pasting or dropping a screenshot into chat no longer locks typing in the rest of the IDE.

## [1.11.1] - 2026-08-24

### Changed

- Choose Model also accepts Ctrl+Shift+# (Cmd+Shift+# on macOS), so German layouts can type the
  same apostrophe chord OpenCode shows in the tooltip.

## [1.11.0] - 2026-08-24

### Changed

- New Session in the panel also accepts Ctrl+N (Cmd+N on macOS), matching OpenCode's current v2 default.
- Close Tab (Ctrl+Alt+W, Cmd+Option+W on macOS) closes the current OpenCode conversation or
  file tab. Remap it under Keymap if you want a different chord; Cmd+W still hides the panel.

### Fixed

- Turning off file drop and paste into chat discards any queued IDE-to-chat text instead of
  sending it the next time the setting is turned back on.

## [1.10.9] - 2026-08-23

### Fixed

- Dropping files into chat no longer locks typing in the rest of the IDE.

## [1.10.8] - 2026-08-21

### Changed

- Chat diffs also open on Ctrl+Click (Cmd+Click on macOS), not only Alt+Click. Alt is the
  Windows menu-bar mnemonic and often never reaches the embedded page.

### Fixed

- Chat file references that put the line outside the code span (`Main.kt`:L42, `Foo.java` (L10))
  open at that line. `Class.method()` opens the type when the workspace match is unique.
- Leaving the embedded page no longer keeps a stale mouse cursor on the rest of the IDE.

## [1.10.7] - 2026-08-20

### Fixed

- The embedded panel no longer flickers on Windows after session or permission UI
  changes. Repaint recovery now re-rasters the page instead of resizing the host.
- Alt+Click on a chat edit or patch opens that tool's change, not the whole turn's snapshot.
- File references in chat with a line location (`Main.kt:42`, `Foo.java:L10-20`,
  `file.ts#L12-L18`, `file.go(12,3)`) open in the IDE when the workspace match is unique.
- Stack-trace paths in bash and tool output open the matching file.
- On Windows, attaching a file from the panel no longer opens the file dialog behind the IDE.
  The IDE file chooser is used instead of Chromium's.

## [1.10.6] - 2026-08-18

### Changed

- First-open session restore now uses one JVM navigation instead of a page-script
  `location.assign` retry loop.
- Compact layout and IDE theme share a single `matchMedia` wrapper.
- Root-relative chat links are opened in the IDE only when the last path segment
  looks like a file, so unknown SPA routes are not intercepted.

## [1.10.5] - 2026-08-18

### Fixed

- After a fresh IDE start, sending a follow-up no longer fails with
  "Failed to send prompt / Unable to retrieve session" when the IDE project path and
  OpenCode's stored session directory differ (symlinks, `/var` vs `/private/var`).
- Provider API calls from the embedded server can use the IDE HTTP Proxy (manual,
  auto-detect, or PAC), inherited environment variables, or no proxy (#11).
- The “Opening the OpenCode page…” strip no longer flashes again after the page is
  already visible, and a server restart no longer leaves it stuck on top of a
  loaded page.
- Stopping or restarting the server opens the same conversation again instead of
  injecting a most-recent-session navigation.
- Inline code path references now use the same best-guess file resolution as
  markdown file links.

## [1.10.4] - 2026-08-16

### Fixed

- The embedded page now waits for its startup safeguards before opening, retries every stalled
  full-page navigation, and no longer loses a newer startup conversation lookup.
- The connection watchdog now also recovers when a connection stalls before response headers,
  cleans up cancelled streams, and never modifies pages outside the local OpenCode server.
- Stopping the server while it is starting no longer replaces the stopped panel with an error.

### Changed

- The opt-in JCEF checks now exercise protected page assets, document-start origin isolation, and
  stalled event-stream recovery; failures can no longer be reported as skipped tests.
- Manual installation now points to the current Marketplace versions instead of an older GitHub release.

## [1.10.3] - 2026-08-16

### Fixed

- The plugin again passes JetBrains’ Marketplace checks for current IDE builds.

## [1.10.2] - 2026-08-16

### Fixed

- Opening a second project window no longer stays on “Opening the OpenCode page…”. A hung first
  load is retried instead of waiting forever.
- The panel no longer cancels a load that is still in progress. A too-short retry was leaving
  the first window stuck on the opening status as well.
- The panel now opens the OpenCode page immediately instead of waiting for the conversation list.
  A slow or paginated listing no longer leaves the tool window blank after the server is already running.
- If the page never finishes loading, the panel retries on its own instead of staying empty until a manual reload.
- The connection watchdog is installed before the OpenCode web app starts, so a dead connection can
  reconnect instead of blocking the next message until the page is refreshed.
- The status strip now stays visible with “Opening the OpenCode page…” until the page actually
  appears, so a running server no longer looks like a blank panel.
- When the server fails to start, every open panel shows the error page — not only the window
  that triggered the start.
- A warning appears if this OpenCode version would hide permission requests from the IDE.

## [1.10.1] - 2026-08-13

### Fixed

- Stopping the OpenCode server and starting it again no longer leaves the panel blank on Windows.
  The stopped state now hides the page with a native placeholder instead of parking Chromium on
  `about:blank`, and a later start waits for the previous process to finish exiting before binding
  a new one.

## [1.10.0] - 2026-08-12

### Added

- A **Stop OpenCode Server** action is available in the tool-window gear menu.
  Health checks will not bring it back; **Start**, **Restart**, Reload, or opening the panel starts it again.

## [1.9.9] - 2026-08-09

### Fixed

- The **Auto-Accept Permissions** menu item is now enabled as soon as a conversation is open. It previously stayed greyed out on the first menu open and only became clickable when the menu was opened a second time.

## [1.9.8] - 2026-08-06

### Added

- The panel menu now has a non-persistent **Auto-Accept Permissions** toggle for the displayed conversation and its subagent children. Each conversation keeps its own in-memory toggle state, and explicit deny rules still apply.

## [1.9.7] - 2026-07-30

### Fixed

- The panel now reconnects on its own when its connection to OpenCode goes quiet. Sleeping the machine or switching network could leave the connection open but dead, and the panel had no way to notice: it kept showing the conversation as it was, ignored anything that happened afterwards, and refused to send the next message until the page was reloaded. This was most common on Windows and after longer breaks.
- Answering a permission request from an IDE notification now clears the request in the panel. When the connection had died in the meantime, the answer still reached OpenCode but the panel never heard about it and left the request on screen.
- Waking the machine from sleep reconnects the panel immediately instead of after a short delay.

## [1.9.6] - 2026-07-29

### Fixed

- Saving settings now reports secure-storage failures instead of restarting OpenCode with credentials that were not saved.
- OpenCode shortcuts on macOS no longer inherit duplicate Control-key variants from the default keymap.
- Explicitly stopping the server can no longer leave the panel stuck in a restarting state when a health check finishes at the same time.
- Agent completion sounds survive temporary event-stream reconnects while still discarding stale state after a server restart.
- Diff navigation prefers exact paths, rejects ambiguous filename-only matches, and respects case-sensitive filesystems.
- File navigation checks every exact location before guessing, handles Windows path case consistently, and ignores stale results from older clicks.
- The startup project seed no longer loses its one-shot conversation target when the page's session pointer cannot be saved early.
- Response notifications are dismissed when their conversation becomes visible through in-panel navigation.

## [1.9.5] - 2026-07-28

### Changed

- The project-specific settings page under Tools is now called "OpenCode Web Panel (Project)", so it is no longer listed with the same name as the main settings page right above it.

### Fixed

- File references in chat now open in the IDE even when the path contains spaces or non-ASCII characters (umlauts, accents). OpenCode escapes those characters in links, and the panel previously failed to find the file, so only plain ASCII paths worked reliably.
- Relative references written with a leading slash (`/src/Main.kt`) or as `file:src/Main.kt` now open as well, and stray spaces around a link no longer prevent it from opening.
- Incomplete references are now resolved as best as possible: a reference to `src/Main.kt` (or just `Main.kt`) opens the matching file even when it actually lives deeper in the project, and a reference that repeats folders the panel is already inside still works. Exact matches always win; build output, `node_modules` and version-control folders are skipped.
- The panel opens the most recent conversation again when that conversation changed outside the panel — for example after another window, another OpenCode client, or the automatic continuation of an interrupted session worked on it. The embedded web app restored its own last-viewed conversation instead, overriding the one the IDE had selected.
- The settings page no longer shows a scrollbar on a tab whose content fits. Both tabs were sized to the longer one, so "OpenCode Server Setup" scrolled even with empty space below it.

## [1.9.4] - 2026-07-27

### Fixed

- Automatic continuation of interrupted sessions works again for conversations started in the panel. The recovery path was reading a message store the embedded OpenCode web app never writes to, so it always saw an empty history and never continued a turn cut off by a restart or suspend.

## [1.9.3] - 2026-07-21

### Fixed

- Saved OpenCode UI state (open tabs, layout, home sidebar) no longer breaks when the local OpenCode server comes back on a different port — for example after an IDE restart with automatic port selection.
- The "agent finished" notification sound no longer plays on IDE startup for conversations that were already idle, and no longer fires twice for the same finished turn.

## [1.9.2] - 2026-07-20

### Changed

- The tool window is now labeled "OpenCode" instead of "OpenCode Web Panel".

### Fixed

- Right after starting the IDE, the automatic continuation of interrupted sessions could mistake a message you had just sent for a conversation cut off by the previous shutdown and inject a spurious "Continue" into it — leading to unexpected notification sounds and extra responses shortly after startup. Messages sent after the current start are now left alone.

## [1.9.1] - 2026-07-19

### Added

- OpenCode notification sounds (agent finished, permission required, errors) now play from the IDE using the sound choices configured in OpenCode's own settings. The embedded browser previously stayed silent for these cues.
- New "Reset OpenCode Web State" option in the tool window's gear menu clears the embedded OpenCode web app's locally stored state and reloads the panel — a one-click recovery if the panel ever gets stuck in a bad state, for example after an OpenCode update.
- New "Open Browser DevTools" option in the gear menu opens Chromium's built-in developer tools (console, network, elements) for the embedded page — useful for diagnosing problems with the panel. If DevTools asks you to sign in, a notification provides the username and a one-click copy of the server password.

### Changed

- The panel is now more careful with OpenCode's own stored browser data: data in an unrecognized format (e.g. from a newer OpenCode version) is left untouched instead of being rewritten, automatic notification-toast handling is bounded, and internal panel errors can no longer interfere with the web app's storage.
- The context menu's "View Page Source" entry has been removed — it never worked in the embedded browser. Use the gear menu's "Open Browser DevTools" for page inspection instead.

### Fixed

- The text caret in the chat input no longer intermittently disappears (typing kept working but the cursor position was invisible, e.g. after switching back to the IDE window or when OpenCode changed pages).

## [1.9.0] - 2026-07-18

### Added

- OpenCode's New Session, Home/sidebar, model, agent, thinking-effort, file-attachment, and stop shortcuts now work inside the panel and can be remapped under the IDE's Keymap settings.
- Pressing Esc while the panel is focused now closes OpenCode popups or stops the running response; remove the shortcut from "OpenCode: Cancel or Stop" in the Keymap to restore the IDE's Esc-to-editor behavior instead.

### Changed

- Panel zoom shortcuts can now be remapped. Cut, copy, paste, select all, undo, and redo follow the active IDE keymap on Windows and Linux; macOS keeps the standard system chords.

### Fixed

- Project selection no longer repeatedly expands or reorders an existing project while the panel starts, and malformed saved project state now repairs itself.
- More OpenCode interface preferences survive browser-profile or server-port changes, including tab history and review-panel state.
- File and code links recover their pointer cursor if OpenCode replaces page styles, and browser enhancements are active immediately after a page loads.
- Slow startup lookups and agent-status refreshes can no longer overwrite a newer project, server, or explicitly opened conversation.
- Files prepared for drag-and-drop are discarded if the panel navigates before preparation finishes, rather than being delivered to the newer page.
- Event-driven status, notifications, and refreshes now recognize macOS path aliases, symlinks, Windows path case, and UNC shares as the same project.
- IDE actions that add file references or selected text now wait for confirmation from OpenCode's real prompt before removing queued input; unavailable prompts and open dialogs no longer lose it silently.
- Generic selected text is inserted through OpenCode's paste path, while file references and attachments use its drop path.
- Diff loading now distinguishes a real empty diff from a server or response failure and reports the latter clearly.
- The OpenCode event connection now recovers within roughly 45 seconds after missed heartbeats instead of remaining silently stalled for up to ten minutes.
- Session-error notifications now display messages from OpenCode's current structured error format.
- Most-recent-session lookup follows paginated results, so heavily used projects do not miss an older conversation with newer activity.
- File and code navigation now acts only on OpenCode's Markdown and review surfaces, so future application routes, unknown link schemes, fenced code, and inline URLs retain their native behavior.
- Interrupted-session recovery now retries transient listing/message failures, stops when the project, server, or setting changes, and reports rejected continuation requests instead of silently treating them as successful.
- System notifications now expire when their server stops or notifications are disabled, and queued events from an older server instance can no longer appear after a restart.
- Browser repaint recovery can no longer leave the embedded panel one pixel too large when navigation or overlapping refreshes interrupt a resize nudge.
- Improved server health detection, session-list paging, and text-cursor behavior in the embedded panel.
- Preparing pasted images and dropped files now runs in the background, keeping the IDE responsive for large or slow files.
- Pending permission and question notifications are reconciled after event-stream reconnects, restoring missed requests and dismissing requests already answered elsewhere.
- Windows drive and network file links now open in the IDE, and editor selections beginning with a file reference are pasted intact instead of being mistaken for one file path.
- Retried IDE-to-chat inputs ignore late acknowledgements from older attempts, and rapid file/image drops retain their submission order.
- Session-error notifications now keep the actual error detail alongside the conversation title, including across server lifecycle changes.
- The tool-window badge no longer lets a delayed server snapshot overwrite a newer idle or attention state.
- Malformed nested project state in OpenCode's browser storage is now repaired instead of being rewritten ineffectively on every retry.
- Permission and question notifications now preserve event order, so an already answered request cannot appear after its reply event.

## [1.8.0] - 2026-07-17

### Added

- A setting to show or hide OpenCode's floating website/help button in the panel (default: hide). Turn it off if an OpenCode update needs that control visible again.

### Fixed

- The "suppress project-switch prompts" option now works in every language: the permission/question notification toast is recognized by its structure and icons instead of English/German label texts.

### Changed

- Compact layout now relies only on the browser media-query breakpoint OpenCode already uses, without extra CSS class overrides that tracked Tailwind class names.
- The website-button hide rule matches more durable signals (opencode.ai link + icon button / fixed chrome) instead of English labels and fixed position utility classes.
- Smoother chat streaming: the panel's page tweaks now stay idle while OpenCode updates the conversation, instead of re-scanning the page on every change.

## [1.7.1] - 2026-07-16

### Fixed

- The panel no longer crashes on IntelliJ IDEA 2026.2 with `NoClassDefFoundError: JBCefBrowser`. JetBrains moved the embedded browser (JCEF) into a separate **Web Browser (JCEF)** plugin; the panel now declares that dependency so the browser classes load again.

### Changed

- Minimum supported IDE version is now **2025.3** (was 2025.2), because the JCEF module dependency is only available from 2025.3.1 onward.

## [1.7.0] - 2026-07-16

### Added

- A **Reload** button in the panel's title bar and gear menu that refreshes the OpenCode web UI without restarting the server, so you can recover from a stuck or glitchy page without interrupting running sessions. Unlike **Restart Server**, it only reloads the panel you triggered it from.
- A warning now explains when the installed OpenCode version is too old for the panel, including the minimum version required (**OpenCode 1.18.0** or newer).

### Changed

- OpenCode's floating "open the website" button (the circled question mark pinned to the bottom-right corner) is now hidden inside the panel, where it only overlapped the message box and linked out to the website. The hide rule is re-applied if OpenCode's SPA replaces the page head, so the button no longer reappears after navigation.

### Fixed

- Fixed frequent "Failed to send prompt / Unable to retrieve session" errors with OpenCode 1.18. The panel was opening on a route that crashes OpenCode 1.18's web UI on load, which left it unable to send messages; the panel now opens on the route OpenCode 1.18 expects and reliably lands on your most recent conversation.
- When "Open the most recent conversation on startup" is enabled, the panel boots on that conversation's 1.18 session URL and navigates to it even if the shared browser profile had restored a different session first.
- Clicking a subagent or task card in chat now opens that session. OpenCode 1.18 links these as `/server/.../session/...` routes; the panel was treating those as local file paths and blocking navigation.
- "Show in OpenCode" on IDE notifications opens the 1.18 session route and no longer force-reloads the panel when you are already viewing that session.
- Sidebar, home, and other in-app SPA links that are not real files are no longer intercepted as IDE file opens.
- The IDE now reliably refreshes its files and version-control view after OpenCode edits, patches, or commits — including after a commit, which previously often left the Changes/Local Changes view stale until a manual refresh. Updates are debounced, so a burst of edits in one turn no longer triggers repeated refreshes.
- This refresh no longer depends on the "Show agent status on the tool window icon" setting; it now works even with that badge turned off.

## [1.6.6] - 2026-07-15

### Fixed

- Clicking the file name in OpenCode's redesigned review panel (desktop layout, when "Lock to compact view" is off) now opens that file in the IDE. The classic compact review panel already had an open-in-IDE control; the redesigned panel did not.

## [1.6.5] - 2026-07-14

### Fixed

- With several IDE projects open, a panel could show another project's OpenCode workspace, because the embedded OpenCode restored its own last-used project from the shared browser profile. Each panel now opens directly on its configured project directory instead of the server home.
- With OpenCode's "New layout and designs" enabled, the panel could stay on the wrong project after OpenCode rewrote the address to its directoryless form. The panel now detects this and re-opens the configured project.
- After a manual restart of the OpenCode server, every open project's panel reliably returns to its own project.

### Note

- If a project keeps opening under its **old path after you renamed its folder on disk**, that is a server-side OpenCode bug ([anomalyco/opencode#35240](https://github.com/anomalyco/opencode/issues/35240)) — OpenCode keeps serving the old project location to every client, so no IDE plugin can correct it. Until it is fixed upstream, repoint the project in OpenCode's own database while no OpenCode instance is running.

## [1.6.4] - 2026-07-13

### Fixed

- Permission and question notifications can no longer reappear after they were already answered: OpenCode events are now processed strictly in order.
- "Automatically continue interrupted conversations" no longer sends prompts to subagent child sessions, and it now finds a still-active conversation even when many subagent sessions were created after it.
- A server restart that was superseded by another stop or restart can no longer briefly launch an extra server process — on Windows this could leave an orphaned server behind.
- The embedded panel now reliably stays on OpenCode: pages reached through redirects, forms, or scripts open in the system browser just like clicked links (controlled by the same setting), and IDE file-open links are only accepted while the panel is showing OpenCode itself.
- Dragging or pasting very large clipboard content into chat no longer risks freezing the IDE: text, images, and files are size-checked before they are processed. A malformed server emitting an endless event line can no longer exhaust memory either.
- Changing the OpenCode project directory of one project no longer restarts the shared server for every open project; only the changed project's panel reloads.
- The agent-status badge no longer misses busy/attention changes that happened exactly while it was refreshing after a reconnect.
- snake_case code references in chat are now clickable for IDE navigation; previously this never worked.
- The settings page no longer stacks duplicate handlers when it is reopened (for example, "Generate" no longer produces two passwords per click), and the show-password toggle is reachable with the keyboard again.

### Changed

- Server log files now rotate once they reach 50 MB, and OpenCode's server output no longer fills the IDE's own log file.
- Plugin settings no longer roam through Settings Sync, since they contain machine-specific paths and local browser state. The per-project OpenCode directory stays shareable with your team as before.

## [1.6.3] - 2026-07-10

### Fixed

- On Windows, browser Basic auth and the graceful server dispose step no longer depend on the launcher process still being alive. After OpenCode's launcher exits (normal there), the panel keeps authenticating and can still ask the server to dispose before force-killing descendants.
- Stopping the OpenCode server no longer freezes the IDE UI for several seconds: dispose and process kill run off the EDT, restarts wait for the previous stop before rebinding, and IDE shutdown still waits with a bound so the process is not orphaned.
- Server URLs advertised in process logs are accepted only for loopback hosts, so auth traffic is never sent to a non-local address.
- Event-stream blocks and REST response bodies are size-capped so a runaway stream or huge diff/list cannot exhaust heap; oversized SSE blocks trigger a reconnect instead.

## [1.6.2] - 2026-07-10

### Fixed

- Before terminating the bundled OpenCode server — on IDE shutdown or when a server-affecting setting changes — the plugin now asks the server to dispose its resources via its global dispose endpoint, giving OpenCode a chance to shut down cleanly instead of only being force-terminated. If the graceful request fails, the process is stopped as before.

## [1.6.1] - 2026-07-09

### Fixed

- Alt+Click now opens the diff for OpenCode's "Patch" (apply_patch) tool as well — previously only chat edits, writes, and "Changed files" rows responded. Opening these diffs is also fixed on Windows: patches with CRLF line endings no longer add stray blank lines, and file paths that differ only by separator or case now match, so diffs open the same on Windows and macOS.

## [1.6.0] - 2026-07-08

### Added

- Alt+Click a diff in a conversation to open it in the IDE's native diff viewer — works on file edits in the chat, rows in the "Changed files" turn summary, and changes-list entries. Alt is reserved for this, so a plain click still opens the file in the editor. Can be turned off with the new "Open diffs in the IDE on Alt+Click" setting.

### Changed

- Agent permission requests are now answered through OpenCode's current permission-reply endpoint instead of the deprecated one it replaced, keeping the IDE-side Allow / Always Allow / Deny actions working against current and future OpenCode releases.

## [1.5.1] - 2026-07-07

### Fixed

- Fixed blank or partially painted areas in the embedded OpenCode page after navigating between conversations and when a permission or question section appears. The off-screen Chromium renderer now gets the extra nudge it needs to redraw after the SPA changes routes, and permission/question prompts trigger the same recovery from the IDE-side event stream, since they render without a route change the previous fix could not see. The backing-surface reallocation that clears frame-buffer mismatches now also survives past one EDT cycle so CEF actually observes the resize.

## [1.5.0] - 2026-07-05

### Added

- "Open the most recent conversation on startup" now really does open the most recent conversation. Instead of picking up whatever session happened to be last shown in the panel, the plugin asks the OpenCode server which conversation was updated most recently — even if the new messages came from another IDE window, the terminal, or an OpenCode agent — and navigates there after a restart.
- Hovering anything the panel opens in the IDE — local file links, changed-file buttons, and code references in chat — now shows the hand cursor, in the page and on the mirrored panel cursor, so clickable IDE targets are recognizable before you click.

### Changed

- The agent-status badge and system notifications are now driven by a single IDE-side connection to the OpenCode event stream instead of scripts injected into every embedded page. The badge stays correct while a page is loading or after a browser crash, notifications appear even when the panel shows an error page, toggling either setting no longer reloads the panel, and the plugin no longer competes with the embedded pages for Chromium's six-connections-per-host budget.
- System notifications are now suppressed while the OpenCode tool window is visible and the IDE window is focused (previously: while the embedded page itself had focus), and bringing the notified session into view dismisses them.

### Fixed

- Fixed blank or partially painted areas in the embedded OpenCode page after navigating between conversations. The off-screen Chromium renderer now gets the extra nudge it needs to redraw after the SPA changes routes.
- The panel now mirrors the web page's mouse cursor like a regular browser: text shows the I-beam, links and buttons show the hand, and resize handles show resize arrows that no longer get stuck, because JCEF's off-screen rendering does not propagate Chromium's cursor changes itself. Can be disabled via the new "Mirror the web page mouse cursor" setting.

## [1.4.11] - 2026-07-03

### Added

- IDE notifications now dismiss themselves when they become obsolete: permission and question notifications close as soon as the request is answered in the OpenCode UI (or from any other client), and "Show in OpenCode" notifications close when the session starts working again or when you interact with an OpenCode page that is showing that session.

### Fixed

- Sessions survive laptop sleep: a failed health check is now confirmed with slower retries before the server is restarted, so a machine that is still busy waking up no longer triggers a spurious restart that kills running sessions.
- Conversations interrupted by system sleep now continue automatically: a turn that lost its provider connection while the machine slept is recognized after wake (started before the sleep, error-settled after the resume) and receives a continuation prompt.
- After a wake-time server restart, the interrupted-session recovery window now spans the sleep gap instead of only the last five minutes, so turns crashed by a long sleep are resumed too.

## [1.4.10] - 2026-07-03

### Fixed

- Session restore can never navigate the panel to another project's session: the remembered session is only used when its own directory matches the panel's project.

## [1.4.9] - 2026-07-03

### Fixed

- The panel no longer navigates away from a conversation you opened: the startup script that restores the most recent session could previously yank the browser back to the remembered session right after a manual navigation.
- Restoring the most recent conversation now works on Windows when the stored project key uses backslashes or a drive-letter root.
- Automatic continuation of interrupted conversations now runs once per server process instead of on every page load, so reloading the panel during a running turn no longer queues spurious "Continue" prompts.
- Interrupted conversations are now detected after a real crash: a hard kill mid-turn leaves the unanswered user prompt as the session's last message, which was previously not recognized.
- Recovery checks now parse server responses structurally instead of with text heuristics, so messages containing code snippets or nested error objects can no longer be misclassified.
- Stopping or restarting the OpenCode server now also works after the launcher process has exited (the normal case on Windows), instead of orphaning the server and causing port conflicts.
- The project file refresh after an agent turn runs asynchronously, avoiding UI freezes in large projects.
- "Add to OpenCode Chat" stays available in the project view and editor tab menus while text is selected in the editor; it only yields to "Add Selection to OpenCode Chat" inside the editor popup itself.
- Startup health checks are paced again while waiting for the server, and the repeated launcher-exited warning on Windows is now logged only once per start.

## [1.4.8] - 2026-07-02

### Added

- "Add to OpenCode Chat" now also appears in the editor context menu when no text is selected, complementing "Add Selection to OpenCode Chat" which shows when a selection exists.

## [1.4.7] - 2026-07-02

### Fixed

- The Show in OpenCode notification action now skips browser navigation when the conversation route is already open, avoiding unnecessary screen refreshes.

## [1.4.6] - 2026-07-02

### Fixed

- Starting OpenCode on Windows no longer treats short-lived launcher processes as a failed server when the authenticated health endpoint is reachable, and the startup wait now consistently uses the full 60-second window.

## [1.4.5] - 2026-07-02

### Fixed

- VCS refresh after agent turn no longer triggers a threading assertion error on non-EDT threads; the refresh now runs entirely on the event dispatch thread.

## [1.4.4] - 2026-07-02

### Added

- Automatically continue interrupted conversations after server recovery: sessions updated within the last 5 minutes with a crashed assistant turn (missing completion time or unsettled tools) receive a continuation prompt. User-initiated stops are not resumed. Own safeguard toggle in settings.
- Refresh project files and VCS change detection when the OpenCode agent finishes a turn, so file modifications and commits appear in the project view and changelists without a manual refresh.

### Removed

- "Hide browser until the OpenCode project page is ready" setting and its associated browser-hiding logic. The lifecycle status panel and faster project-page injection make this workaround unnecessary.

### Changed

- Renamed the "OpenCode Notifications" settings group to "OpenCode Event Handling".

## [1.4.3] - 2026-07-02

### Added

- "Always Allow" action on OpenCode permission notifications, granting the permission permanently alongside the existing "Allow" (once) and "Deny" actions.

## [1.4.2] - 2026-07-02

### Fixed

- The Add Selection to OpenCode Chat context-menu action now sends the selected code lines instead of adding the entire file, combining the file reference and the snippet into a single chat input.

## [1.4.1] - 2026-07-02

### Fixed

- Panels no longer stall with endlessly loading model and session selectors when several project windows are open: the plugin's event bridges now share a single event-stream connection per IDE instead of exhausting the browser's per-host connection limit.
- The Add to OpenCode Chat context-menu icon renders at the normal menu size instead of oversized.

## [1.4.0] - 2026-07-02

### Added

- Context-menu actions to add project files or the current editor selection to the OpenCode chat, using the same references as drag-and-drop; queued input is delivered once the panel finishes loading.
- Allow and Deny actions on OpenCode permission notifications, answering the agent without switching to the panel (own safeguard toggle).
- Agent status on the tool window icon: a live indicator while the agent works and a warning overlay while it awaits input, scoped to the panel's project (own safeguard toggle).
- The settings server status shows the OpenCode version reported by the running server.
- The startup error panel detects fixed-port conflicts and offers a one-click switch to automatic port selection.
- Browser-style keyboard zoom inside the panel (Cmd/Ctrl with plus, minus, and zero).

## [1.3.0] - 2026-07-01

### Added

- Tool-window title-bar controls for zooming the panel and restarting the OpenCode server, with a gear menu that also offers reset zoom, view server log, and settings.
- Native startup error panel with recent server output and direct Retry, Open Settings, and View Full Log actions, replacing the previous static error page.
- Confirmation prompt before manually restarting a running OpenCode server, since a restart interrupts OpenCode work in all open projects.

### Changed

- Zoom changes apply to the live page immediately instead of reloading the panel and losing the chat draft and scroll position.
- Starting the server during the failure backoff window now waits and starts automatically instead of failing immediately.

### Fixed

- Panels reload automatically after the shared server recovers from a crash or is restarted from another project window, instead of staying blank.
- Applying server-affecting settings restarts the server and reloads open panels instead of leaving them on a stopped server.
- Connection errors and browser renderer crashes now trigger immediate recovery instead of showing a stale error page until the next periodic health check.
- Opening a panel while the server is already running no longer flashes a brief "Starting" status in every open panel.
- Compact layout enforcement could silently skip its CSS depending on injection timing, and now also covers OpenCode views that use the inverse desktop media query.
- Mirrored OpenCode web-session state follows current OpenCode storage keys (tab state, app settings) and caps oversized entries instead of discarding the whole snapshot.
- OpenCode idle notifications follow the current OpenCode event format without duplicating notifications on servers that emit both old and new events.
- The configured project now opens even when the initial page load is very slow, and panel scripts never run against pages outside the embedded OpenCode app.

## [1.2.7] - 2026-06-27

### Fixed

- Pasting a screenshot or other image from the clipboard now attaches it to chat instead of doing nothing.
- Dropping an image from another app now attaches it to chat when it cannot be added as a project file reference.
- Keep the existing OpenCode server password when the system keychain is briefly unavailable, instead of replacing it and breaking authentication.
- Avoid a short UI freeze when applying settings while the saved password is read from secure storage.
- Handle file names with unusual characters more reliably when dragging or pasting files into chat.

## [1.2.6] - 2026-06-24

### Changed

- Group settings by related behavior and indent dependent controls to make option relationships clearer.

### Fixed

- Restarting the OpenCode server from settings now starts it again and reloads the tool window.

## [1.2.5] - 2026-06-23

### Fixed

- Resolve Qodana inspection findings in the Kotlin sources changed for the release.

## [1.2.4] - 2026-06-23

### Fixed

- Avoid stale OpenCode authentication prompts after stopping or restarting the embedded server.
- Route OpenCode notifications to the IDE window for the matching open project, and suppress notifications when the project is not open.

## [1.2.3] - 2026-06-23

### Fixed

- Use IntelliJ's public application lifecycle listener for the shutdown guard that avoids late JCEF network access warnings.

## [1.2.2] - 2026-06-23

### Fixed

- Improve OpenCode binary auto-detection on Windows by preferring runnable command shims such as `opencode.cmd`.
- Reduce startup flicker while the embedded OpenCode UI navigates to the current project, with a setting to disable the startup hiding behavior.
- Avoid JCEF browser disposal work during IDE shutdown to prevent late network access warnings.

## [1.2.1] - 2026-06-21

### Fixed

- The changes panel “Open file” action now opens changed files in the IDE instead of only switching tabs inside the embedded OpenCode web app.

## [1.2.0] - 2026-06-21

### Changed

- Dragging files from the active OpenCode project into chat now uses OpenCode's `@relative/path` file-reference behavior, making repository files easier to mention in prompts.
- Pasting copied project files into chat now uses the same `@relative/path` file-reference behavior.
- Dropped files can still be attached when OpenCode treats the drop as a file attachment.
- OpenCode notification actions now say “Show in OpenCode” to better describe that they bring the related item into view.

### Fixed

- Undo and redo shortcuts now work in the embedded OpenCode editor when the browser has focus.

## [1.1.0] - 2026-06-21

### Added

- Show the actual running OpenCode server URL in settings, which makes auto-selected ports visible.
- Persist OpenCode server logs to disk and automatically prune old log files.
- Add a setting to enable or disable writing server logs to disk; logging is enabled by default.
- Add clear start/restart markers to server log files so separate OpenCode runs are easier to distinguish.

### Changed

- Place the server log viewer directly under the server log setting.
- Show an error when no server log file exists instead of silently falling back to temporary in-memory output.

## [1.0.1] - 2026-06-21

### Fixed

- Wait for IntelliJ MCP server readiness without declaring an MCP plugin dependency.
- Remove plugin verifier warnings from MCP integration and tool-window registration.
- Show click-to-navigate as a first-class setting independent of local file-link handling.

## [1.0.0] - 2026-06-20

### Added

- Embedded OpenCode web app in a right-side JetBrains IDE tool window.
- Shared local OpenCode server startup with loopback binding, automatic port selection, and Password Safe-backed authentication.
- Project-coupled startup that opens the current IDE project and can restore its most recent OpenCode conversation.
- Persistence for selected OpenCode web-session settings across embedded sessions and dynamic ports.
- IDE-native local file-link opening, clickable code-reference navigation, and limited file drag-and-drop into chat.
- Configurable browser-side safeguards for injected UI behaviors, compact layout, project-switch prompt suppression, and system notifications.
- IntelliJ notification bridge for OpenCode browser notifications.

[Unreleased]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.13.4...HEAD
[1.13.4]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.13.3...1.13.4
[1.13.3]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.13.2...1.13.3
[1.13.2]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.13.1...1.13.2
[1.13.1]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.13.0...1.13.1
[1.13.0]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.12.1...1.13.0
[1.12.1]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.12.0...1.12.1
[1.12.0]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.11.1...1.12.0
[1.11.1]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.11.0...1.11.1
[1.11.0]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.10.6...1.11.0
[1.10.6]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.10.5...1.10.6
[1.10.5]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.10.4...1.10.5
[1.10.4]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.10.3...1.10.4
[1.10.3]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.10.2...1.10.3
[1.10.2]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.10.1...1.10.2
[1.10.1]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.10.0...1.10.1
[1.10.0]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.9.9...1.10.0
[1.9.9]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.9.8...1.9.9
[1.9.8]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.9.7...1.9.8
[1.9.7]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.9.6...1.9.7
[1.9.6]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.9.5...1.9.6
[1.9.5]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.9.4...1.9.5
[1.9.4]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.9.3...1.9.4
[1.9.3]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.9.2...1.9.3
[1.9.2]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.9.1...1.9.2
[1.9.1]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.9.0...1.9.1
[1.9.0]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.8.0...1.9.0
[1.8.0]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.7.1...1.8.0
[1.7.1]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.7.0...1.7.1
[1.7.0]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.6.6...1.7.0
[1.6.6]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.6.5...1.6.6
[1.6.5]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.6.4...1.6.5
[1.6.4]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.6.3...1.6.4
[1.6.3]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.6.2...1.6.3
[1.6.2]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.6.1...1.6.2
[1.6.1]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.6.0...1.6.1
[1.6.0]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.5.1...1.6.0
[1.5.1]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.5.0...1.5.1
[1.5.0]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.4.11...1.5.0
[1.4.11]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.4.10...1.4.11
[1.4.10]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.4.9...1.4.10
[1.4.9]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.4.8...1.4.9
[1.4.8]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.4.7...1.4.8
[1.4.7]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.4.6...1.4.7
[1.4.6]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.4.5...1.4.6
[1.4.5]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.4.4...1.4.5
[1.4.4]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.4.3...1.4.4
[1.4.3]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.4.2...1.4.3
[1.4.2]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.4.1...1.4.2
[1.4.1]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.4.0...1.4.1
[1.4.0]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.3.0...1.4.0
[1.3.0]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.2.7...1.3.0
[1.2.7]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.2.6...1.2.7
[1.2.6]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.2.5...1.2.6
[1.2.5]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.2.4...1.2.5
[1.2.4]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.2.3...1.2.4
[1.2.3]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.2.2...1.2.3
[1.2.2]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.2.1...1.2.2
[1.2.1]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.2.0...1.2.1
[1.2.0]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.1.0...1.2.0
[1.1.0]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.0.1...1.1.0
[1.0.1]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.0.0...1.0.1
[1.0.0]: https://github.com/moritzfl/intellij-opencode-web-panel/commits/1.0.0
