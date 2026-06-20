# opencode-web-ui

![Build](https://github.com/xausky/opencode-web-ui/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/30364-opencode-web-ui.svg)](https://plugins.jetbrains.com/plugin/30364-opencode-web-ui)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/30364-opencode-web-ui.svg)](https://plugins.jetbrains.com/plugin/30364-opencode-web-ui)

## Overview

OpenCodeWeb is a JetBrains IDE plugin for IntelliJ IDEA, PyCharm, WebStorm, and other JetBrains IDEs. It provides a convenient embedded Web UI integration for OpenCode.

<!-- Plugin description -->

## Plugin Description

> **Note:** This is an unofficial plugin for OpenCode, maintained by the community and not affiliated with OpenCode.

### Features

- **Auto-start Service** - Click the sidebar icon to automatically check and start the OpenCode server
- **Smart Monitoring** - Periodically check server status and automatically restart failed services
- **Auto Cleanup** - Automatically stop OpenCode service when IDE exits to release resources
- **Sidebar Integration** - Display plugin icon in the right sidebar, click to access OpenCode Web UI
- **Project Sync** - Automatically load the Web interface for the current project

### Use Cases

- Developers who need to use OpenCode Web UI in JetBrains IDE
- Developers who need to automatically manage OpenCode servers
- Users who want to quickly view the AI assistant interface during coding
<!-- Plugin description end -->

## Installation

- Install from the IDE plugin marketplace:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search "open code web"</kbd> >
  <kbd>Install</kbd>

- Install from JetBrains Marketplace:

  Visit [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/30364-opencode-web-ui) and install the plugin.

  You can also download the [latest version](https://plugins.jetbrains.com/plugin/30364-opencode-web-ui/versions) from JetBrains Marketplace and install it with
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Settings</kbd> > <kbd>Install Plugin from Disk...</kbd>

- Install manually:

  Download the [latest release](https://github.com/xausky/opencode-web-ui/releases/latest), then install it with
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Settings</kbd> > <kbd>Install Plugin from Disk...</kbd>

## Usage

1. **Install the OpenCode CLI**  
   The plugin must be able to find the `opencode` command in your system `PATH`. If OpenCode is not installed, install it from the official OpenCode documentation.

2. **Open the plugin tool window**  
   Find the "OpenCodeWeb" icon in the right IDE sidebar and click it.

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
