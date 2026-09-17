# OpenCode Web Panel

<table align="center">
  <tr>
    <td align="center">
      <a href="https://plugins.jetbrains.com/plugin/32384">
        <img src="src/main/resources/META-INF/pluginIcon.svg" alt="OpenCode Web Panel on JetBrains Marketplace" width="96" />
      </a>
      <br />
      <strong><a href="https://plugins.jetbrains.com/plugin/32384">OpenCode Web Panel on JetBrains Marketplace</a></strong>
      <br />
      Embed OpenCode inside JetBrains IDEs.
      <br />
      <br />
      <a href="https://plugins.jetbrains.com/plugin/32384">
        <img src="https://img.shields.io/jetbrains/plugin/v/32384" alt="JetBrains Marketplace version" />
      </a>
      <a href="https://plugins.jetbrains.com/plugin/32384">
        <img src="https://img.shields.io/jetbrains/plugin/d/32384" alt="JetBrains Marketplace downloads" />
      </a>
      <a href="https://plugins.jetbrains.com/plugin/32384">
        <img src="https://img.shields.io/jetbrains/plugin/r/rating/32384" alt="JetBrains Marketplace rating" />
      </a>
    </td>
  </tr>
</table>

![OpenCode Web Panel running inside a JetBrains IDE](docs/opencode-web-panel.png)

<!-- Plugin description -->

OpenCode Web Panel brings the official OpenCode web UI into JetBrains IDEs. It opens OpenCode in a right-side tool window, keeps it focused on the project you are working on, and adds small IDE conveniences on top of the normal OpenCode experience. Host CLI and Docker Sandbox work with **OpenCode 1.18** and **CLI 2.x**.

> **Note:** This is an unofficial community plugin for OpenCode and is not affiliated with OpenCode.

### Why use it

- **OpenCode where you code** - Use OpenCode beside your editor instead of switching to the terminal, desktop app, or standalone web app.
- **Official web UI** - The embedded panel loads OpenCode's own web app, so the core experience stays familiar.
- **Project-aware sessions** - OpenCode starts on the directory configured for the current IDE project.
- **Optional Docker Sandboxes** - Run OpenCode inside a per-project Docker Sandbox (`sbx`) instead of a host `opencode serve`. Native CLI remains the default.
- **IDE file navigation** - Click local file links and code references from chat to open them in the IDE.
- **Chat file drop and paste** - Drag or paste project files into chat as `@relative/path` references, or attach dropped files.
- **Send code to chat** - Add files or the current selection to the OpenCode chat from the editor and project view context menus.
- **External links stay outside** - HTTP links open in your system browser instead of taking over the panel.
- **IDE notifications** - OpenCode browser notifications can appear as JetBrains IDE notifications, including Allow/Deny actions for agent permission requests.
- **Agent status at a glance** - The tool window icon shows when the agent is working or waiting for your input.
- **Panel controls in the title bar** - Zoom the panel and restart the OpenCode server directly from the tool window.
- **Recovery built in** - Failed or crashed servers surface a clear error panel with recent logs, retry, and settings shortcuts, and the panel recovers automatically where possible.
- **Configurable safeguards** - Browser-side convenience features can be disabled if an OpenCode update conflicts with them.

<!-- Plugin description end -->

## Requirements

- A JetBrains IDE compatible with this plugin.
- The OpenCode CLI installed on your machine (Host runtime, the default): **1.18** or **CLI 2.x**.
- The `opencode` command available on `PATH`, or configured manually in the plugin settings.
- Optional: Docker Sandboxes (`sbx`) and a Docker account when **Runtime** is set to Docker Sandbox.

## Installation

- Install from the IDE plugin marketplace:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search "OpenCode Web Panel"</kbd> > <kbd>Install</kbd>

- Install from JetBrains Marketplace:

  Visit [OpenCode Web Panel on JetBrains Marketplace](https://plugins.jetbrains.com/plugin/32384) and install the plugin.

- Install manually:

  Download the [latest version from JetBrains Marketplace](https://plugins.jetbrains.com/plugin/32384/versions), then install it with <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Settings</kbd> > <kbd>Install Plugin from Disk...</kbd>

## Getting Started

1. Install the OpenCode CLI.
2. Open a project in your JetBrains IDE.
3. Click the **OpenCode Web Panel** tool window on the right sidebar.
4. Start using OpenCode in the embedded panel.

The plugin starts a local OpenCode server when needed, authenticates the embedded web UI automatically, and opens the configured project directory.

## Tool Window Controls

The tool window title bar offers quick controls (also available in the tool window's gear menu on narrow panels):

- **Zoom out / Zoom in** - Scale the embedded OpenCode UI in 10% steps without reloading. Cmd/Ctrl with <kbd>+</kbd>, <kbd>-</kbd>, and <kbd>0</kbd> work inside the panel too.
- **Reload Page** - Reload the embedded OpenCode UI. The server stays running.
- **Restart Server** - Stop and restart OpenCode, and recreate the embedded browser. Recovers a stuck or crashed panel. Host CLI: shared by all open projects. Docker Sandbox: this project only. Also on **OpenCode Web Panel (Project)**.
- The gear menu additionally offers **Reset Zoom**, **View Server Log**, and **OpenCode Web Panel Settings**.

### Context Menu Actions

- **Add to OpenCode Chat** (project view, editor tabs) - Insert `@path` references for the selected project files into the chat.
- **Add Selection to OpenCode Chat** (editor) - Insert the file reference plus the selected lines as a code snippet.

Both actions open the panel and deliver the input once OpenCode has finished loading. They follow the **Enable file drop and paste into chat** setting.

## Settings

Open <kbd>Settings/Preferences</kbd> > <kbd>Tools</kbd> > <kbd>OpenCode Web Panel</kbd>.

### OpenCode Server Setup

- Per project (**OpenCode Web Panel (Project)**): **Runtime** Host or Docker Sandbox, this project's server status/restart/log/port, plus mounts and kits. Stored in `opencode-sbx/opencode-sbx.yaml` (source of truth; the panel hydrates from it). Apply also writes `opencode-sbx/opencode-sbx.sh` so a teammate can run `./opencode-sbx/opencode-sbx.sh` (web), `./opencode-sbx/opencode-sbx.sh --cli` (TUI), or `./opencode-sbx/opencode-sbx.sh --acp` (ACP stdio) without the plugin. On Windows, run the `.sh` from Git Bash.
- Application settings: Host CLI binary, password, HTTP proxy, `sbx` path, and network-policy consent.
- **Share host OpenCode config and file secrets** mounts the host config directory read-only, including JSON/JSONC, skills, agents, commands, and plugins. The sandbox can use those files and cannot change them. Sandbox sessions and browser preferences remain separate.
- **Protect sandbox files** overlays the `opencode-sbx/` folder (spec, launchers, extra-network kit) and other local kit directories read-only on top of the writable project mount (on by default). Reset the sandbox to apply to an existing VM.
- **Persist sandbox sessions across Reset** keeps this sandbox's conversations on the host in a plugin data directory (not Host CLI's `opencode.db`). Reset recreates the VM and remounts the same store.
- Choose whether the plugin should auto-detect `opencode` or use a custom executable path.
- Per project, let OpenCode select a port automatically, or set a fixed port. Host CLI binds that loopback port; Docker Sandbox republishes VM 4096 to it without recreating the sandbox.
- Edit, generate, show, or copy the local server password stored in IntelliJ Password Safe.
- Choose how the OpenCode server reaches the network: the IDE HTTP Proxy, environment
  variables, or no proxy.
- Restart this project's OpenCode server from the tool window or **OpenCode Web Panel (Project)**.
- View recent OpenCode server output from **OpenCode Web Panel (Project)**.

### OpenCode UI Settings

- Open local file links in the IDE.
- Open external HTTP links in the system browser.
- Enable click-to-navigate for code references in chat.
- Enable file drop and paste into chat, using `@relative/path` for project file references.
- Lock OpenCode to compact layout for panel-friendly use.
- Hide OpenCode's floating website/help button in the panel.
- Sync OpenCode's system color scheme with the IDE theme.
- Suppress project-switch prompts that are not useful inside the embedded panel.
- Forward OpenCode browser notifications to the IDE, optionally with Allow/Deny actions for permission requests.
- Show agent status on the tool window icon.
- Wait briefly for IntelliJ MCP server readiness before launching OpenCode.

### Project Settings

Project-specific settings are stored with the IDE project.

- Use **Auto detect** to open the IDE project root in OpenCode.
- Use **Custom Directory** to point OpenCode at another directory, such as a monorepo root or a subproject.
- Use **Detect** to fill the custom directory with the auto-detected project root.

## Troubleshooting

**OpenCode does not start**

- Check that the OpenCode CLI is installed.
- Run `opencode --version` in a terminal.
- The panel shows an error view with recent server output plus **Retry**, **Open Settings**, and **View Full Log** actions.
- Open the plugin settings and use **Detect** or set the OpenCode executable path manually.

**The panel shows a failed server state**

- Click **Retry** in the tool-window status strip or on the error view.
- If it still fails, review the server output shown on the error view.
- With a fixed port, the error view detects port conflicts and offers **Use Automatic Port**.
- Verify the configured OpenCode project directory exists.

**Provider API calls fail behind a proxy**

- Open <kbd>Settings</kbd> > <kbd>Tools</kbd> > <kbd>OpenCode Web Panel</kbd> and set **HTTP Proxy**.
- **Use IDE HTTP Proxy** forwards the IDE proxy
  (<kbd>Settings</kbd> > <kbd>Appearance & Behavior</kbd> > <kbd>System Settings</kbd> > <kbd>HTTP Proxy</kbd>),
  including auto-detect and PAC. PAC is resolved for a generic HTTPS target, because OpenCode
  accepts only a single `HTTP_PROXY` value.
- **Use environment variables** keeps `HTTP_PROXY` / `HTTPS_PROXY` from the environment the IDE was started with.
- **No proxy** removes proxy variables from the OpenCode process.
- Restart the OpenCode server after changing proxy settings.

**The panel is stuck, blank, or frozen on “Opening the OpenCode page…”**

- Use **Restart Server** in the tool window title bar, gear menu, or **OpenCode Web Panel (Project)** settings. Host CLI and Docker Sandbox restarts are this project only.
- **Reload Page** reloads the UI without restarting the server; use Restart if that is not enough.

**The embedded UI behaves unexpectedly after an OpenCode update**

- Disable the affected convenience feature under **OpenCode UI Settings**.
- Reload or reopen the tool window.
- Report the issue with the OpenCode version and plugin version.

## Development

Useful development commands:

```bash
./gradlew test
./gradlew runIde
./gradlew runIdeForUiTests
```

- `test` runs JVM and IntelliJ Platform tests.
- `runIde` launches a sandbox IDE with the plugin installed.
- `runIdeForUiTests` launches the sandbox IDE with the JetBrains Robot Server enabled.

Repository: https://github.com/moritzfl/intellij-opencode-web-panel

## Acknowledgements

This project started as a fork of an early draft by xausky: https://github.com/xausky/intellij-opencode-web-ui/

## License

This project is licensed under the MIT License.

## Disclaimer

This plugin is an unofficial community plugin for OpenCode and is not affiliated with OpenCode. Please report plugin issues through GitHub Issues.

---

If you find this plugin useful, please consider giving it a star.
