package de.moritzf.opencodewebpanel.toolWindow

import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.jcef.JBCefBrowser
import de.moritzf.opencodewebpanel.browser.OpenCodeBrowserSnippets
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.SharedOpenCodeServerManager
import de.moritzf.opencodewebpanel.settings.OpenCodeSettingsState
import java.net.URI
import org.cef.browser.CefFrame

internal const val OPEN_CODE_ZOOM_IN_ACTION_ID = "OpenCodeWebPanel.ZoomIn"
internal const val OPEN_CODE_ZOOM_OUT_ACTION_ID = "OpenCodeWebPanel.ZoomOut"
internal const val OPEN_CODE_RESET_ZOOM_ACTION_ID = "OpenCodeWebPanel.ResetZoom"

internal enum class OpenCodeBrowserCommand(
    val intellijActionID: String,
    val newLayoutBindings: List<OpenCodeCommandBinding>,
    val classicBindings: List<OpenCodeCommandBinding> = newLayoutBindings,
    val composerOnly: Boolean = false,
) {
    NEW_SESSION(
        "OpenCodeWebPanel.NewSession",
        newLayoutBindings = listOf(OpenCodeCommandBinding("tab.new", "mod+t")),
        classicBindings = listOf(OpenCodeCommandBinding("session.new", "mod+shift+s")),
    ),
    TOGGLE_HOME(
        "OpenCodeWebPanel.ToggleHome",
        newLayoutBindings = listOf(OpenCodeCommandBinding("home.toggle", "mod+b")),
        classicBindings = listOf(OpenCodeCommandBinding("sidebar.toggle", "mod+b")),
    ),
    CHOOSE_MODEL(
        "OpenCodeWebPanel.ChooseModel",
        newLayoutBindings = listOf(OpenCodeCommandBinding("model.choose", "mod+'")),
        composerOnly = true,
    ),
    CYCLE_AGENT(
        "OpenCodeWebPanel.CycleAgent",
        newLayoutBindings = listOf(OpenCodeCommandBinding("agent.cycle", "mod+.")),
        composerOnly = true,
    ),
    CYCLE_THINKING_EFFORT(
        "OpenCodeWebPanel.CycleThinkingEffort",
        newLayoutBindings = listOf(OpenCodeCommandBinding("model.variant.cycle", "shift+mod+d")),
        composerOnly = true,
    ),
    ATTACH_FILES(
        "OpenCodeWebPanel.AttachFiles",
        newLayoutBindings = listOf(OpenCodeCommandBinding("file.attach", "mod+u")),
        composerOnly = true,
    ),
    CANCEL_OR_STOP(
        "OpenCodeWebPanel.CancelOrStop",
        newLayoutBindings = listOf(OpenCodeCommandBinding(null, "escape")),
    ),
}

internal data class OpenCodeCommandBinding(val commandID: String?, val defaultKeybind: String)

internal data class OpenCodeResolvedKeybinds(
    val newLayout: List<String>,
    val classic: List<String>,
)

/**
 * Installs component-local actions so OpenCode shortcuts participate in IntelliJ's Keymap without
 * stealing the same chords from editors or other tool windows.
 */
internal class OpenCodeBrowserShortcutHandler(
    private val browser: JBCefBrowser,
    private val serverManager: SharedOpenCodeServerManager,
    private val parentDisposable: Disposable,
) {
    fun install() {
        // JBCefBrowser already installs these native edit actions on macOS. Mirror that support on
        // Windows/Linux, including each user's remapped IDE shortcuts.
        if (!SystemInfo.isMac) {
            registerEditAction(IdeActions.ACTION_CUT) { it.cut() }
            registerEditAction(IdeActions.ACTION_COPY) { it.copy() }
            registerEditAction(IdeActions.ACTION_PASTE) { it.paste() }
            registerEditAction(IdeActions.ACTION_SELECT_ALL) { it.selectAll() }
            registerEditAction(IdeActions.ACTION_UNDO) { it.undo() }
            registerEditAction(IdeActions.ACTION_REDO) { it.redo() }
        }

        OpenCodeBrowserCommand.entries.forEach(::registerOpenCodeCommand)
        registerZoomAction(OPEN_CODE_ZOOM_IN_ACTION_ID) { OpenCodeZoom.apply(OpenCodeZoom::zoomedIn) }
        registerZoomAction(OPEN_CODE_ZOOM_OUT_ACTION_ID) { OpenCodeZoom.apply(OpenCodeZoom::zoomedOut) }
        registerZoomAction(OPEN_CODE_RESET_ZOOM_ACTION_ID) {
            OpenCodeZoom.apply { OpenCodeSettingsState.DEFAULT_UI_ZOOM_PERCENT }
        }
    }

    private fun registerEditAction(actionID: String, operation: (CefFrame) -> Unit) {
        registerShortcut(
            actionID,
            object : DumbAwareAction() {
                override fun actionPerformed(e: AnActionEvent) {
                    operation(browser.cefBrowser.focusedFrame ?: browser.cefBrowser.mainFrame)
                }
            },
        )
    }

    private fun registerOpenCodeCommand(command: OpenCodeBrowserCommand) {
        registerShortcut(
            command.intellijActionID,
            object : DumbAwareAction() {
                override fun actionPerformed(e: AnActionEvent) {
                    val serverUrl = serverManager.getServerUrl() ?: return
                    val pageUrl = browser.cefBrowser.url
                    if (!isCommandAvailable(command, serverUrl, pageUrl)) return
                    val keybinds = resolveOpenCodeKeybinds(
                        command,
                        OpenCodeSettingsState.getInstance().openCodeLocalStorageSnapshot,
                    )
                    val script = OpenCodeBrowserSnippets.buildShortcutDispatchScript(
                        keybinds.newLayout,
                        keybinds.classic,
                    ) ?: return
                    browser.cefBrowser.executeJavaScript(script, OpenCodeServerProtocol.buildServerRootUrl(serverUrl), 0)
                }

                override fun update(e: AnActionEvent) {
                    val serverUrl = serverManager.getServerUrl()
                    e.presentation.isEnabled = isCommandAvailable(command, serverUrl, browser.cefBrowser.url)
                }

                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            },
        )
    }

    private fun registerZoomAction(actionID: String, operation: () -> Unit) {
        registerShortcut(
            actionID,
            object : DumbAwareAction() {
                override fun actionPerformed(e: AnActionEvent) = operation()
            },
        )
    }

    private fun registerShortcut(actionID: String, action: AnAction) {
        val source = checkNotNull(ActionManager.getInstance().getAction(actionID)) {
            "Missing shortcut action $actionID"
        }
        action.registerCustomShortcutSet(source.shortcutSet, browser.component, parentDisposable)
    }

    internal companion object {
        fun resolveOpenCodeKeybinds(command: OpenCodeBrowserCommand, snapshot: String?): OpenCodeResolvedKeybinds {
            val custom = runCatching {
                val snapshotObject = JsonParser.parseString(snapshot.orEmpty())
                    .takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?: return@runCatching emptyMap()
                val settingsValue = snapshotObject.get("settings.v3")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString
                    ?: return@runCatching emptyMap()
                val settings = JsonParser.parseString(settingsValue)
                    .takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?: return@runCatching emptyMap()
                val keybinds = settings.get("keybinds")
                    ?.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?: return@runCatching emptyMap()
                keybinds.entrySet().mapNotNull { (commandID, value) ->
                    value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                        ?.asString
                        ?.let { commandID to it }
                }.toMap()
            }.getOrDefault(emptyMap())

            fun resolve(bindings: List<OpenCodeCommandBinding>): List<String> = bindings.mapNotNull { binding ->
                val keybind = binding.commandID?.let(custom::get) ?: binding.defaultKeybind
                keybind.takeUnless { it.isBlank() || it.equals("none", ignoreCase = true) }
            }

            return OpenCodeResolvedKeybinds(
                newLayout = resolve(command.newLayoutBindings),
                classic = resolve(command.classicBindings),
            )
        }

        fun isCommandAvailable(command: OpenCodeBrowserCommand, serverUrl: String?, pageUrl: String?): Boolean {
            return OpenCodeServerProtocol.isOpenCodeServerPage(serverUrl, pageUrl) &&
                (!command.composerOnly || isComposerRoute(pageUrl))
        }

        private fun isComposerRoute(pageUrl: String?): Boolean {
            val path = runCatching { URI(pageUrl.orEmpty()).path }.getOrNull() ?: return false
            return path == "/new-session" || Regex("/session(?:/|$)").containsMatchIn(path)
        }
    }
}
