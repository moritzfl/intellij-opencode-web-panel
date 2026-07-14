<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# OpenCode Web Panel Changelog

## [Unreleased]

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

[Unreleased]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.6.5...HEAD
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
