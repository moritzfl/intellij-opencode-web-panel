# OpenCode Web Panel

[![Build](https://github.com/moritzfl/intellij-opencode-web-panel/actions/workflows/build.yml/badge.svg)](https://github.com/moritzfl/intellij-opencode-web-panel/actions/workflows/build.yml)

## Overview

OpenCode Web Panel is a JetBrains IDE plugin for IntelliJ IDEA, PyCharm, WebStorm, and other JetBrains IDEs. It provides a convenient embedded Web UI integration for OpenCode.

![OpenCode Web Panel running inside a JetBrains IDE](docs/opencode-web-panel.png)

<!-- Plugin description -->

OpenCode Web Panel embeds the OpenCode web app directly inside JetBrains IDEs, keeping the panel focused on the IDE project where it was opened.

> **Note:** This is an unofficial plugin for OpenCode, maintained by the community and not affiliated with OpenCode.

### What it does

- **Embedded OpenCode UI** - Opens OpenCode in a right-side IDE tool window instead of a separate browser tab.
- **Project-coupled startup** - Starts `opencode serve` on `127.0.0.1`, authenticates automatically, and opens the current IDE project.
- **Secure local auth** - Generates and stores the server password in IntelliJ Password Safe.
- **State persistence** - Preserves selected OpenCode UI state across IDE restarts and dynamic port changes.
- **IDE navigation** - Opens local file links and clickable code references from chat directly in the IDE.
- **Chat file drop** - Lets you drag files from the IDE or desktop into the embedded OpenCode chat.
- **Panel-friendly layout** - Keeps the OpenCode UI in compact mode and suppresses cross-project approval prompts by default.
- **Configurable safeguards** - Browser-side integrations can be disabled independently if an OpenCode update conflicts with them.

### Good for

- Working with OpenCode without leaving IntelliJ IDEA, PyCharm, WebStorm, or other JetBrains IDEs.
- Keeping OpenCode sessions aligned with the project currently open in the IDE.
- Using OpenCode-generated file paths, code references, and attachments as IDE-native actions.
<!-- Plugin description end -->

## Installation

- Install from the IDE plugin marketplace:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search "OpenCode Web Panel"</kbd> >
  <kbd>Install</kbd>

- Install from JetBrains Marketplace:

  Visit the JetBrains Marketplace page for OpenCode Web Panel and install the plugin.

  You can also download the latest version from JetBrains Marketplace and install it with
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Settings</kbd> > <kbd>Install Plugin from Disk...</kbd>

- Install manually:

  Download the [latest release](https://github.com/moritzfl/intellij-opencode-web-panel/releases/latest), then install it with
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Settings</kbd> > <kbd>Install Plugin from Disk...</kbd>

## Usage

1. **Install the OpenCode CLI**  
   The plugin must be able to find the `opencode` command in your system `PATH`. If OpenCode is not installed, install it from the official OpenCode documentation.

2. **Open the plugin tool window**  
   Find the "OpenCode Web Panel" icon in the right IDE sidebar and click it.

3. **Start the service automatically**  
   The plugin generates a temporary password, starts the OpenCode service, and lets OpenCode choose an available port:
   ```
   OPENCODE_SERVER_PASSWORD=<generated> \
   opencode serve --hostname 127.0.0.1 --port 0 --print-logs
   ```

4. **Use the Web UI**  
   After the service starts, the tool window authenticates automatically and opens the OpenCode Web UI directly on the current IDE project.

## Configuration

The plugin uses the following defaults:
- Host: `127.0.0.1`
- Port: automatically selected by OpenCode
- Password: generated each time the plugin starts the OpenCode service

## Troubleshooting

**Problem: the plugin cannot find the `opencode` command**

- Make sure the OpenCode CLI is installed correctly.
- Make sure the `opencode` executable is available in your system `PATH`.
- Run `opencode --version` in a terminal to verify the installation.

**Problem: the service fails to start**

- Check the IDE log for error details.
- Run the startup command manually to diagnose the issue.

## Development

This project is based on the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).

Useful development commands:

```bash
./gradlew test
./gradlew runIde
./gradlew runIdeForUiTests
```

- `test` runs deterministic JVM and IntelliJ Platform tests for the advertised server startup, authentication, monitoring, sidebar registration, project working directory, and cleanup behavior.
- `runIde` launches a sandbox IDE with the plugin installed for manual testing.
- `runIdeForUiTests` launches the sandbox IDE with the JetBrains Robot Server enabled on `http://127.0.0.1:8082` for UI investigation and future UI robot tests.

## License

This project is licensed under the MIT License.

## Disclaimer

This plugin is an unofficial community plugin for OpenCode and is not affiliated with OpenCode. Please report plugin issues through GitHub Issues.

---

If you find this plugin useful, please consider giving it a star.
