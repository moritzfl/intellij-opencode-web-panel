<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# OpenCode Web Panel Changelog

## [Unreleased]

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

[Unreleased]: https://github.com/moritzfl/intellij-opencode-web-panel/compare/1.2.7...HEAD
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
