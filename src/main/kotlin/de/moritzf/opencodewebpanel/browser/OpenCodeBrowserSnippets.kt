package de.moritzf.opencodewebpanel.browser

import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import de.moritzf.opencodewebpanel.server.OpenCodeWireProtocol

internal object OpenCodeBrowserSnippets {

    const val EVENT_STREAM_STALL_TIMEOUT_MILLIS =
        OpenCodeBrowserScriptSupport.EVENT_STREAM_STALL_TIMEOUT_MILLIS
    const val RENDERER_HEARTBEAT_INTERVAL_MILLIS =
        OpenCodeBrowserScriptSupport.RENDERER_HEARTBEAT_INTERVAL_MILLIS
    const val OPENCODE_TAB_POPOVER_OPEN_DELAY_MILLIS =
        OpenCodeBrowserScriptSupport.OPENCODE_TAB_POPOVER_OPEN_DELAY_MILLIS
    const val PATH_HOVER_PREVIEW_DELAY_MILLIS =
        OpenCodeBrowserScriptSupport.PATH_HOVER_PREVIEW_DELAY_MILLIS
    const val MIN_EVENT_STREAM_STALL_TIMEOUT_MILLIS =
        OpenCodeBrowserScriptSupport.MIN_EVENT_STREAM_STALL_TIMEOUT_MILLIS

    /**
     * Maps a CSS cursor computed value to the closest AWT predefined cursor type. Custom `url(...)`
     * cursors resolve through their keyword fallback; CSS values without an AWT counterpart (help,
     * copy, zoom-in, ...) fall back to the default arrow.
     */
    fun awtCursorTypeForCss(cssCursor: String?): Int =
        OpenCodeAppearanceScripts.awtCursorTypeForCss(cssCursor)

    /**
     * Seeds the opencode SPA's project state for [projectBasePath].
     *
     * Inject from `onLoadStart` so `lastProject` is set before the SPA bundle reads localStorage.
     * Session choice is left to OpenCode (tabs / lastProjectSession).
     *
     * Auto-port loopback origins are reused across IDE projects. OpenCode 2 persists session tabs
     * by origin (`opencode.window.browser.dat:tabs`), so a previous occupant's `ses_` ids reopen as
     * "This session cannot be found". Drop those tabs when this origin's project worktree changes.
     */
    fun buildOpenProjectScript(
        projectBasePath: String?,
        serverUrl: String? = null,
    ): String? = OpenCodeStorageScripts.buildOpenProjectScript(projectBasePath, serverUrl)

    /**
     * User-invoked escape hatch (not an injection feature): wipes the page's localStorage and
     * sessionStorage so a bad persisted value — a corrupt seeded project state or a mirrored
     * snapshot that keeps getting restored — can be cleared without digging into the JCEF profile.
     * The caller clears the IDE-side snapshot and reloads the page afterwards.
     */
    fun buildClearOpenCodeWebStateScript(): String =
        OpenCodeStorageScripts.buildClearOpenCodeWebStateScript()

    fun buildRestoreOpenCodeLocalStorageScript(snapshot: String?): String? =
        OpenCodeStorageScripts.buildRestoreOpenCodeLocalStorageScript(snapshot)

    fun buildSyncOpenCodeLocalStorageScript(openStorageCallback: String?): String? =
        OpenCodeStorageScripts.buildSyncOpenCodeLocalStorageScript(openStorageCallback)

    /** Dispatches a remapped IntelliJ action through OpenCode's current page-local command map. */
    fun buildShortcutDispatchScript(
        newLayoutKeybinds: List<String>,
        classicKeybinds: List<String>,
    ): String? =
        OpenCodeNavigationScripts.buildShortcutDispatchScript(newLayoutKeybinds, classicKeybinds)

    fun buildExternalLinkHandlerScript(enabled: Boolean, openExternalCallback: String?): String? =
        OpenCodeNavigationScripts.buildExternalLinkHandlerScript(enabled, openExternalCallback)

    fun buildCodeNavigationScript(enabled: Boolean, openCodeCallback: String?): String? =
        OpenCodeNavigationScripts.buildCodeNavigationScript(enabled, openCodeCallback)

    /**
     * Forces OpenCode's compact (mobile) layout by stubbing the breakpoint media queries the SPA
     * uses for layout. No CSS class overrides: layout is driven by `createMediaQuery` on
     * `(min-width: 768px)` / `(max-width: 767px)`, so patching those is enough and stays free of
     * Tailwind class names that change with redesigns.
     *
     * Must run before the SPA bundle initializes media queries (`onLoadStart`).
     */
    fun buildCompactLayoutScript(enabled: Boolean): String? =
        OpenCodeAppearanceScripts.buildCompactLayoutScript(enabled)

    /**
     * V2 Home removes its project sidebar in compact mode, but its CSS still reserves the desktop
     * grid columns at wide viewport sizes. Let the remaining session list use the whole panel. This
     * accompanies the compact media-query patch and is removed by the same toggle-off reload.
     */
    fun buildCompactHomeLayoutScript(enabled: Boolean): String? =
        OpenCodeAppearanceScripts.buildCompactHomeLayoutScript(enabled)

    fun buildMatchMediaPatchScript(compact: Boolean, theme: Boolean, dark: Boolean): String? =
        OpenCodeAppearanceScripts.buildMatchMediaPatchScript(compact, theme, dark)

    /**
     * Gives the SPA's `/global/event` reader the stall detection it does not have, so a socket the
     * OS severed silently (laptop sleep, VPN/adapter change — common on Windows, where a half-open
     * TCP connection is not reset) cannot leave the page permanently deaf.
     *
     * OpenCode's stream loop (`packages/app/src/context/server-sdk.tsx`) reconnects only when the
     * response iterator *ends or throws*; it has no read timeout and its only resume hook is
     * `pageshow` with `event.persisted`, which never fires for a live JCEF page. A half-open socket
     * therefore delivers neither bytes nor an error and `for await` blocks forever: the page keeps
     * its last state, never learns about `permission.replied` (so an IDE-answered permission prompt
     * stays on screen) and cannot start a new turn until a manual reload.
     *
     * The fix stays outside SPA internals: `window.fetch` is wrapped so the event-stream response
     * body is piped through a reader that aborts the request after [stallTimeoutMillis] without a
     * single byte. The abort surfaces as a normal stream error, which is exactly the signal
     * OpenCode's own reconnect loop already handles. The server emits `server.heartbeat` every 10s,
     * so silence well past that is unambiguous evidence of a dead transport.
     *
     * Must run before the SPA bundle captures `window.fetch` (`onLoadStart`).
     */
    fun buildEventStreamWatchdogScript(
        enabled: Boolean,
        stallTimeoutMillis: Int = EVENT_STREAM_STALL_TIMEOUT_MILLIS,
        wireProtocol: OpenCodeWireProtocol = OpenCodeWireProtocol.UNKNOWN,
    ): String? =
        OpenCodeRecoveryScripts.buildEventStreamWatchdogScript(
            enabled,
            stallTimeoutMillis,
            wireProtocol,
        )

    /**
     * Drops the page's event stream immediately so OpenCode's reconnect loop reopens it. Used when
     * the IDE already knows the transport cannot have survived (resume from system suspend),
     * instead of waiting out the watchdog's silence budget.
     *
     * No-op when the watchdog is not installed, so it is safe to fire unconditionally.
     */
    fun buildForceEventReconnectScript(): String =
        OpenCodeRecoveryScripts.buildForceEventReconnectScript()

    fun isInPlaceDialogRepaintEvent(type: String): Boolean =
        OpenCodeAppearanceScripts.isInPlaceDialogRepaintEvent(type)

    /**
     * Forces Chromium to re-raster the viewport after an in-page layout change without resizing the
     * Swing host. A 1px host bounds change reallocates the OSR surface and flashes on Windows.
     * Toggle a 1px CSS translate on `documentElement` (no OpenCode selectors) after two animation
     * frames, then fire `resize` so the SPA relayouts.
     */
    fun buildViewportRasterNudgeScript(): String =
        OpenCodeAppearanceScripts.buildViewportRasterNudgeScript()

    /**
     * Hides OpenCode's floating "open the website" control (help / marketing link out to
     * opencode.ai). Inside the embedded IDE panel it only overlaps the composer.
     *
     * Selectors prefer durable signals (`href` to opencode.ai + icon-button / fixed chrome) over
     * English aria labels and Tailwind position utilities. Style is kept alive with a permanent
     * MutationObserver because the SPA can replace `<head>` after early injection.
     */
    fun buildHideWebsiteButtonScript(enabled: Boolean): String? =
        OpenCodeAppearanceScripts.buildHideWebsiteButtonScript(enabled)

    /**
     * Shortens OpenCode's session-tab path popover (Kobalte `openDelay` 2000ms) to
     * [PATH_HOVER_PREVIEW_DELAY_MILLIS], and adds the same styled preview on home project rows and
     * home session rows so a session's git worktree is visible before it is opened.
     *
     * Tab delay: Kobalte schedules `window.setTimeout(..., 2000)` when the pointer enters
     * `[data-component="session-tab-popover-trigger"]`. The clamp applies only to two-argument
     * 2000ms timers scheduled within a short window of such a pointerenter, so unrelated page
     * timers with the same 2000ms delay (copy-state reset, typewriter cursor) are untouched; the
     * skip-window path uses 0 and is left alone. Home project rows do not put the worktree in the
     * DOM. 1.18 maps row order onto `opencode.global.dat:server` `projects` and skips when
     * project-row counts do not match. Session rows are not that list: a session directory can be a
     * linked worktree of the selected project. The preview reads `data-session-id` (or a search
     * row's `data-key`) and the directory captured from the SPA's own `GET /api/session` / `GET
     * /session` responses (`location.directory`, else `directory`). It does not open its own
     * requests. Ambiguous rows show nothing rather than the project root. The preview mirrors the
     * session-tab popover: owning project label (matched like OpenCode's `projectForSession` —
     * `worktree`/`sandboxes`, then `projectID` — so a linked-worktree session keeps its parent
     * project's name), session title, then the session's full directory path. The overlay reuses
     * OpenCode's `session-tab-popover` slots so it picks up the page CSS. CLI 2.x compact titlebar:
     * `[data-slot="mobile-tabs-trigger"]` shows the current session's title but has no hover
     * preview of its own. Hovering it shows the current route session's preview; the session is
     * read from the `/server/.../session/<ses_>` route and the SPA-captured session fetches, so a
     * draft or home route shows nothing. The "Tabs" drawer rows (`[data-slot="titlebar-tab-item"]`
     * inside `[data-slot="mobile-drawer-content"]`) keep their session id in the tab link's href
     * even though their Kobalte popover is suppressed while the drawer is open, so they get the
     * same preview — placed above the row when the drawer sits at the viewport bottom. Must be
     * removable by reload (safeguard); the builder returns null when disabled.
     */
    fun buildPathHoverPreviewScript(enabled: Boolean): String? =
        OpenCodePathHoverScripts.buildPathHoverPreviewScript(enabled)

    fun buildIdeThemeSyncScript(enabled: Boolean, dark: Boolean): String? =
        OpenCodeAppearanceScripts.buildIdeThemeSyncScript(enabled, dark)

    /**
     * Signals the IDE that the page raised a failed lazy-chunk import — the error OpenCode's own
     * error boundary presents as "Failed to fetch dynamically imported module". CEF only reports
     * main-frame failures to the JVM, so a chunk that times out (e.g. the panel was on a dead
     * origin after a server restart, or a hung first-run Windows server stalls delivery) leaves the
     * SPA stuck behind its error boundary until a manual reload. Chromium also caches the failed
     * import per renderer, so retrying the import keeps failing; only a full reload recovers it.
     *
     * The listener only signals; the JVM side decides whether and when to reload. It must run
     * before the SPA bundle (`onLoadStart`/document-start), because a boot chunk can already be the
     * failing one. Errors are delivered through capture-phase `error` (module-script src) and
     * `unhandledrejection` (uncaught `import()`). Solid's error boundary often *catches* the
     * rejected lazy() promise (route chunks such as `new-session-*.js`), so those events never fire
     * — the same TypeError is then copied into the error-page details field (engine text, not a
     * localized label). Scan that field with `setTimeout` retries: `requestAnimationFrame` does not
     * run in a hidden JCEF tool window, and Kobalte may assign `textarea.value` after the first
     * frame. Only readOnly fields count as the error page: editable inputs can hold the same pasted
     * engine text, and reloading a healthy session out from under the user is worse than missing
     * the scan. Every page gets at most one signal.
     */
    fun buildChunkLoadRecoveryScript(enabled: Boolean, fatalCallback: String?): String? =
        OpenCodeRecoveryScripts.buildChunkLoadRecoveryScript(enabled, fatalCallback)

    /**
     * Mirrors the web page's mouse cursor to the IDE. JCEF's off-screen rendering does not reliably
     * propagate Chromium's cursor changes to the Swing component, so the embedded panel never shows
     * text or link cursors and can get stuck with a stale resize cursor. This tracks the hovered
     * element's effective CSS cursor (including the I-beam that browsers render for `cursor: auto`
     * over selectable text) and reports each transition through [cursorCallback]; the IDE applies
     * the matching AWT cursor to the panel. While a button is held the cursor from the drag start
     * is kept, matching Chromium's own behavior during drags.
     */
    fun buildCursorMirrorScript(enabled: Boolean, cursorCallback: String?): String? =
        OpenCodeAppearanceScripts.buildCursorMirrorScript(enabled, cursorCallback)

    fun buildProjectSwitchPromptSuppressionScript(enabled: Boolean): String? =
        OpenCodeAppearanceScripts.buildProjectSwitchPromptSuppressionScript(enabled)

    fun buildDispatchDroppedFilesScript(
        files: List<OpenCodeServerProtocol.DroppedFilePayload>,
        textPlain: List<String> = emptyList(),
        enabled: Boolean = true,
        batchId: String? = null,
        resultCallback: String? = null,
        focusPrompt: Boolean = false,
    ): String? =
        OpenCodeClipboardScripts.buildDispatchDroppedFilesScript(
            files,
            textPlain,
            enabled,
            batchId,
            resultCallback,
            focusPrompt,
        )

    /**
     * Renderer liveness heartbeat for the JVM-side
     * [de.moritzf.opencodewebpanel.toolWindow.OpenCodeRendererWatchdog]. A dead renderer never
     * schedules the timer and never delivers the callback, so staleness is the reliable signal — no
     * pinging back into the page. Reports the page's `visibilityState` on a 5s timer and on every
     * `visibilitychange` (including hide) so the JVM can pause the stall clock. Do not beat from
     * `requestAnimationFrame` — that floods the JCEF IPC channel at display refresh.
     */
    fun buildRendererHeartbeatScript(enabled: Boolean, heartbeatCallback: String?): String? =
        OpenCodeRecoveryScripts.buildRendererHeartbeatScript(enabled, heartbeatCallback)

    /** Capture the destination before background image/file preparation, without changing focus. */
    fun buildCaptureClipboardPasteScript(batchId: String, enabled: Boolean): String? =
        OpenCodeClipboardScripts.buildCaptureClipboardPasteScript(batchId, enabled)

    /**
     * Deliver one IDE clipboard snapshot to its original focused field. A synthetic paste has no
     * browser default insertion, so ordinary inputs use insertText (preserving native undo) when
     * the page does not handle it. Only an explicit `native` result may request a native fallback;
     * stale/partially accepted pastes must never be replayed into a different field or duplicated.
     */
    fun buildClipboardPasteScript(
        files: List<OpenCodeServerProtocol.DroppedFilePayload>,
        text: String?,
        fileReferences: List<String>,
        batchId: String,
        resultCallback: String?,
        enabled: Boolean,
        nativeFallback: Boolean = false,
    ): String? =
        OpenCodeClipboardScripts.buildClipboardPasteScript(
            files,
            text,
            fileReferences,
            batchId,
            resultCallback,
            enabled,
            nativeFallback,
        )

    fun buildFileLinkHandlerScript(
        projectBasePath: String?,
        enabled: Boolean,
        openFileCallback: String? = null,
    ): String? =
        OpenCodeNavigationScripts.buildFileLinkHandlerScript(
            projectBasePath,
            enabled,
            openFileCallback,
        )

    /**
     * Installs a Ctrl/Cmd+Click (and Alt+Click) handler that opens the IDE diff viewer for a diff
     * target in the OpenCode page. Chat edit/write/patch blocks send the tool
     * `[data-timeline-part-id]` (`prt_…`) so the JVM can load that part's `filediff`/`files`;
     * multi-file patch rows also send the reconstructed relative path to pick the row.
     * Review/turn-summary rows and the whole-turn indicator still send the user `messageID` (+
     * optional file path) for `session.diff`. CLI 2.x Changes Git/Branch adds a fourth `vcsMode`
     * line (`working`/`branch`). Forwards `messageID + "\n" + filePath + "\n" + partID + "\n" +
     * vcsMode` (each may be empty) to the JVM via [openDiffCallback]. Returns null when disabled or
     * without a callback.
     */
    fun buildDiffNavigationScript(enabled: Boolean, openDiffCallback: String? = null): String? =
        OpenCodeNavigationScripts.buildDiffNavigationScript(enabled, openDiffCallback)
}
