package de.moritzf.opencodewebpanel.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol

class OpenCodeBrowserSnippetsTest {

    @Test
    fun buildOpenProjectScriptSeedsProjectState() {
        val script = OpenCodeBrowserSnippets.buildOpenProjectScript("/tmp/my 'project'", "http://127.0.0.1:60482/")!!

        assertTrue(script.contains("if (window.location.origin !== 'http://127.0.0.1:60482') return;"))
        assertTrue(script.contains("opencode.global.dat:server"))
        assertTrue(script.contains("state.projects[scope]"))
        assertTrue(script.contains("state.lastProject[scope] = directory"))
        assertTrue(script.contains("!Array.isArray(state.projects)"))
        assertTrue(script.contains("!Array.isArray(state.lastProject)"))
        assertTrue(script.contains("worktree: directory, expanded: true"))
        // Existing entries preserve position, collapse state, and unknown fields across re-seeds.
        assertTrue(script.contains("Object.assign"))
        assertTrue(script.contains("typeof project.expanded === 'boolean' ? project.expanded : true"))
        assertTrue(script.contains("if (!found) nextProjects.unshift"))
        assertTrue(script.contains("if (nextRaw !== raw)"))
        // Foreign-schema guard: a root that parses but is not a plain object is treated as a
        // newer OpenCode schema and skipped (fail soft) instead of being replaced wholesale.
        assertTrue(script.contains("if (!parseFailed && parsed !== null && !isPlainObject)"))
        assertTrue(script.contains("Skipping OpenCode project seed: unrecognized project-state schema"))
        assertFalse(script.contains("state.list ="))
        assertTrue(script.contains("const sameWorktree = (left, right) =>"))
        assertTrue(script.contains("!sameWorktree(project.worktree, directory)"))
        assertTrue(script.contains("next.startsWith('//')) next = next.toLowerCase()"))
        assertTrue(script.contains("const directory = '/tmp/my \\'project\\''"))
        assertTrue(script.contains("opencode-intellij-project"))
        assertTrue(script.contains("if (!previous || !sameWorktree(previous, directory))"))
        assertTrue(script.contains("opencode.window.browser.dat:tabs"))
        assertTrue(script.contains("opencode.window.browser.dat:tabs.recent"))
        assertTrue(script.contains("opencode.window.browser.dat:tabs.info"))
        assertTrue(script.contains("opencode.window.browser.dat:tabs.closed"))
        assertTrue(script.contains("opencode.window.browser.dat:tabs.panes"))
        assertTrue(script.contains("window.localStorage.removeItem(key)"))
        assertFalse(script.contains("window.location.assign"))
        assertFalse(script.contains("lastProjectSession"))
        assertFalse(script.contains("window.location.reload()"))
        assertFalse(script.contains("projectPath"))
        assertFalse(script.contains("findLastProjectSession"))
        assertFalse(script.contains("directorylessProject"))
        assertFalse(script.contains("shouldKeepWaitingForRecentSession"))
        assertFalse(script.contains("onSameProjectRoute"))
    }

    @Test
    fun buildOpenProjectScriptIsMissingWithoutProjectPath() {
        assertNull(OpenCodeBrowserSnippets.buildOpenProjectScript(null))
        assertNull(OpenCodeBrowserSnippets.buildOpenProjectScript(""))
    }

    @Test
    fun buildFileLinkHandlerScriptInterceptsLocalFileLinks() {
        val script = OpenCodeBrowserSnippets.buildFileLinkHandlerScript("/tmp/project", enabled = true)!!

        assertTrue(script.contains("window.__opencodeIntellijFileLinksInstalled"))
        assertTrue(script.contains("target.closest('a')"))
        assertTrue(script.contains("inferredFileLink(link)"))
        assertTrue(script.contains("changedFileButtonLink(target)"))
        assertTrue(script.contains("resolveFileOpenTarget(event.target, changedButtonOnly)"))
        assertTrue(script.contains("session-review-view-button"))
        assertTrue(script.contains("session-review-accordion-item"))
        assertTrue(script.contains("getAttribute('data-file')"))
        // Locale-specific aria/title labels are no longer used as fallbacks.
        assertFalse(script.contains("button[aria-label=\"Open file\"]"))
        assertFalse(script.contains("Datei öffnen"))
        assertTrue(script.contains("session-review-file-info"))
        assertTrue(script.contains("session-review-directory"))
        assertTrue(script.contains("session-review-filename"))
        assertTrue(script.contains("opencode-intellij-open-file"))
        assertTrue(script.contains("message-part-actions"))
        assertTrue(script.contains("apply-patch-trigger-actions"))
        assertTrue(script.contains("session-turn-diff-trigger"))
        assertTrue(script.contains("session-turn-diff-filename"))
        assertTrue(script.contains("session-turn-diff-meta"))
        assertTrue(script.contains("write-trigger"))
        // CLI 2.x edit/write wrap the file accordion in edit-tool/write-tool and reuse
        // apply-patch-trigger-content for the header (no edit-trigger/write-trigger).
        assertTrue(script.contains("edit-tool"))
        assertTrue(script.contains("write-tool"))
        assertTrue(script.contains("apply-patch-trigger-content"))
        assertTrue(script.contains("insertToolOpenIcons"))
        assertTrue(script.contains("MutationObserver"))
        assertTrue(script.contains("data-href"))
        assertTrue(script.contains("data-part-id"))
        assertTrue(script.contains("queueMicrotask"))
        assertTrue(script.contains("pointerEvents"))
        assertTrue(script.contains("stopImmediatePropagation"))
        assertTrue(script.contains("createElementNS"))
        assertTrue(script.contains("window.addEventListener('pointerdown'"))
        assertTrue(script.contains("window.addEventListener('mousedown'"))
        assertFalse(script.contains("isLocalFileLink(iconHref)"))
        assertTrue(script.contains("now - lastOpenedAt < 750"))
        assertTrue(script.contains("data-opencode-intellij-pointer"))
        assertTrue(script.contains("opencode-intellij-pointer-cursor"))
        assertTrue(script.contains("if (!style.isConnected) parent.appendChild(style)"))
        assertTrue(script.contains("cursor: pointer !important"))
        assertTrue(script.contains("document.addEventListener('mouseover'"))
        assertTrue(script.contains("document.addEventListener('mouseout'"))
        assertTrue(script.contains("supportedFileProtocol"))
        assertTrue(script.contains("link.closest('[data-component=\"markdown\"]')"))
        assertTrue(script.contains("link.target !== '_blank'"))
        assertTrue(script.contains("decodeRouteDirectory"))
        assertTrue(script.contains("isOpenCodeAppRoute(href)"))
        // Subagent/task cards link to /server/<key>/session/<id>; that must not be treated as a file path.
        assertTrue(script.contains("/server/"))
        assertTrue(script.contains("new-session"))
        assertTrue(script.contains("lastSegmentLooksLikeFile(href)"))
        assertTrue(script.contains("href.startsWith('/') && !href.startsWith('//')"))
        assertTrue(script.indexOf("lastSegmentLooksLikeFile") < script.indexOf("explicitProtocol.test(href)"))
        assertTrue(script.contains("!href.includes('://')"))
        assertTrue(script.contains("${OpenCodeServerProtocol.OPEN_FILE_LINK_SCHEME}://${OpenCodeServerProtocol.OPEN_FILE_LINK_HOST}"))
    }

    @Test
    fun buildFileLinkHandlerScriptSupportsRedesignedReviewPanelPreviewHeader() {
        val script = OpenCodeBrowserSnippets.buildFileLinkHandlerScript("/tmp/project", enabled = true)!!

        // The redesigned (v2) review panel — shown on desktop when forceCompactLayout is off —
        // exposes the changed file only through its preview header spans, so the "open in IDE"
        // gesture must resolve the path from there.
        assertTrue(script.contains("reviewV2FileLink(target)"))
        assertTrue(script.contains("session-review-v2-file-title"))
        assertTrue(script.contains("session-review-v2-file-name"))
        assertTrue(script.contains("session-review-v2-file-path"))
        assertTrue(script.contains("session-review-v2-file-header"))
        // The sidebar tree rows are the SPA's own preview navigation; hijacking them would break
        // in-app review, so they must never be an intercept target.
        assertFalse(script.contains("session-review-v2-sidebar-tree"))
    }

    @Test
    fun buildFileLinkHandlerScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeBrowserSnippets.buildFileLinkHandlerScript("/tmp/project", enabled = false))
    }

    @Test
    fun buildFileLinkHandlerScriptCanUseDirectCallback() {
        val script = OpenCodeBrowserSnippets.buildFileLinkHandlerScript(
            "/tmp/project",
            enabled = true,
            openFileCallback = "window.intellijOpenFile(rawHref + '\\n' + directory)",
        )!!

        assertTrue(script.contains("window.intellijOpenFile(rawHref + '\\n' + directory)"))
        assertTrue(script.contains("Failed to forward file link to IntelliJ"))
        assertTrue(script.contains("${OpenCodeServerProtocol.OPEN_FILE_LINK_SCHEME}://${OpenCodeServerProtocol.OPEN_FILE_LINK_HOST}"))
        assertFalse(script.contains("window.location.assign(target)"))
    }

    @Test
    fun buildExternalLinkHandlerScriptInterceptsOnlyExternalHttpLinks() {
        val script = OpenCodeBrowserSnippets.buildExternalLinkHandlerScript(
            enabled = true,
            openExternalCallback = "window.intellijOpenExternal(href)",
        )!!

        assertTrue(script.contains("window.__opencodeIntellijExternalLinksInstalled"))
        assertTrue(script.contains("event.target.closest('a')"))
        assertTrue(script.contains("url.protocol !== 'http:' && url.protocol !== 'https:'"))
        assertTrue(script.contains("url.origin === window.location.origin"))
        assertTrue(script.contains("window.__opencodeIntellijNativeWindowOpen"))
        assertTrue(script.contains("window.open = function(url, target, features)"))
        assertTrue(script.contains("event.preventDefault()"))
        assertTrue(script.contains("event.stopImmediatePropagation()"))
        assertTrue(script.contains("window.intellijOpenExternal(href)"))
    }

    @Test
    fun buildExternalLinkHandlerScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeBrowserSnippets.buildExternalLinkHandlerScript(enabled = false, openExternalCallback = "callback(href)"))
    }

    @Test
    fun buildExternalLinkHandlerScriptIsMissingWithoutCallback() {
        assertNull(OpenCodeBrowserSnippets.buildExternalLinkHandlerScript(enabled = true, openExternalCallback = null))
    }

    @Test
    fun buildRestoreOpenCodeLocalStorageScriptIsMissingWithoutSnapshot() {
        assertNull(OpenCodeBrowserSnippets.buildRestoreOpenCodeLocalStorageScript(null))
        assertNull(OpenCodeBrowserSnippets.buildRestoreOpenCodeLocalStorageScript("{}"))
    }

    @Test
    fun buildRestoreOpenCodeLocalStorageScriptRestoresOpenCodeKeysOnlyWhenMissing() {
        val script = OpenCodeBrowserSnippets.buildRestoreOpenCodeLocalStorageScript(
            "{\"opencode.global.dat:language\":\"{\\\"locale\\\":\\\"de\\\"}\"}",
        )!!

        assertTrue(script.contains("opencode.global.dat:language"))
        assertTrue(script.contains(OpenCodeServerProtocol.OPEN_CODE_THEME_ID_STORAGE_KEY))
        assertTrue(script.contains("opencode-color-scheme"))
        assertTrue(script.contains("opencode\\.global\\.dat:(language|model)"))
        assertTrue(script.contains("'settings.v3'"))
        assertFalse(script.contains("opencode\\.workspace\\."))
        assertFalse(script.contains("opencode\\.window\\.browser\\.dat:tabs"))
        assertFalse(script.contains("home.servers"))
        assertFalse(script.contains("review-panel-v2"))
        assertFalse(script.contains("new-session.provider-tip"))
        assertFalse(script.contains("recent|info|closed"))
        assertFalse(script.contains("opencode.settings.dat:defaultServerUrl"))
        assertTrue(script.contains("window.localStorage.getItem(key) === null"))
        assertTrue(script.contains("rewriteLoopbackServerRefs(value)"))
        assertTrue(script.contains("rewriteLoopbackServerRefs(current)"))
        assertTrue(script.contains("LOOPBACK_ORIGIN_RE"))
        // Still only-if-absent for snapshot inject; rewrite pass covers already-present keys.
        assertTrue(script.contains("window.localStorage.setItem(key, rewriteLoopbackServerRefs(value))"))
    }

    @Test
    fun buildSyncOpenCodeLocalStorageScriptMirrorsOpenCodeKeys() {
        val script = OpenCodeBrowserSnippets.buildSyncOpenCodeLocalStorageScript("window.intellijStore(payload)")!!

        assertTrue(script.contains("window.__opencodeIntellijLocalStorageSyncInstalled"))
        assertTrue(script.contains("Storage.prototype.setItem"))
        assertTrue(script.contains("Storage.prototype.removeItem"))
        assertTrue(script.contains("Storage.prototype.clear"))
        assertTrue(script.contains(OpenCodeServerProtocol.OPEN_CODE_THEME_ID_STORAGE_KEY))
        assertTrue(script.contains("opencode-color-scheme"))
        assertTrue(script.contains("opencode\\.global\\.dat:(language|model)"))
        assertTrue(script.contains("'settings.v3'"))
        assertFalse(script.contains("opencode\\.workspace\\."))
        assertFalse(script.contains("opencode\\.window\\.browser\\.dat:tabs"))
        assertFalse(script.contains("home.servers"))
        assertFalse(script.contains("review-panel-v2"))
        assertFalse(script.contains("new-session.provider-tip"))
        assertFalse(script.contains("recent|info|closed"))
        assertTrue(script.contains("MAX_VALUE_CHARS"))
        assertFalse(script.contains("opencode.settings.dat:defaultServerUrl"))
        assertTrue(script.contains("window.intellijStore(payload)"))
        // Auto-port relaunches: normalize loopback server keys before the IDE snapshot is saved.
        assertTrue(script.contains("rewriteLoopbackServerRefs(value)"))
        assertTrue(script.contains("LOOPBACK_ORIGIN_RE"))
        // Containment contract for the only page-wide API patch: the original method runs
        // first, and the mirror tail is try-caught so a bug in it can never break the SPA's
        // own storage operations.
        assertTrue(script.contains("const result = originalSetItem.apply(this, arguments);"))
        assertEquals(3, Regex("""const result = original\w+\.apply\(this, arguments\);\s*\n\s*try \{""").findAll(script).count())
    }

    @Test
    fun buildClearOpenCodeWebStateScriptWipesBrowserStorage() {
        val script = OpenCodeBrowserSnippets.buildClearOpenCodeWebStateScript()

        assertTrue(script.contains("window.localStorage.clear()"))
        assertTrue(script.contains("window.sessionStorage.clear()"))
        // Each clear is individually guarded so a storage-access failure cannot abort the other.
        assertEquals(2, Regex("""try \{ window\.\w+Storage\.clear\(\); \} catch \(_\) \{\}""").findAll(script).count())
    }

    @Test
    fun buildDispatchDroppedFilesScriptCreatesBrowserDropEvent() {
        val script = OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(
            listOf(
                OpenCodeServerProtocol.DroppedFilePayload(
                    name = "hello 'world'.txt",
                    mime = "text/plain",
                    lastModified = 123,
                    base64 = "aGVsbG8=",
                ),
            ),
        )!!

        assertTrue(script.contains("new DataTransfer()"))
        assertTrue(script.contains("new File([decode(entry.base64)], entry.name"))
        assertTrue(script.contains("[data-component=\"prompt-input\"][contenteditable=\"true\"]"))
        assertTrue(script.contains("[data-component=\"composer-editor\"][contenteditable=\"true\"]"))
        assertTrue(script.contains("const event = new DragEvent('drop'"))
        assertTrue(script.contains("return event.defaultPrevented"))
        assertTrue(script.contains("hello \\'world\\'.txt"))
        assertTrue(script.contains("aGVsbG8="))
        assertTrue(script.contains("const focusPrompt = false"))
        assertTrue(script.contains("if (focusPrompt)"))
    }

    @Test
    fun buildDispatchDroppedFilesScriptFocusesPromptOnlyWhenRequested() {
        val script = OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(
            emptyList(),
            textPlain = listOf("file:src/main/App.kt"),
            enabled = true,
            focusPrompt = true,
        )!!

        assertTrue(script.contains("const focusPrompt = true"))
        assertTrue(script.contains("if (focusPrompt)"))
        assertTrue(script.contains("target.focus()"))
    }

    @Test
    fun buildDispatchDroppedFilesScriptEscapesUnsafeCharactersInFileNames() {
        val script = OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(
            listOf(
                OpenCodeServerProtocol.DroppedFilePayload(
                    name = "a<b\u2028c\u2029d\u0000e",
                    mime = "text/plain",
                    lastModified = 1,
                    base64 = "aGVsbG8=",
                ),
            ),
        )!!

        assertTrue(script.contains("a\\u003Cb\\u2028c\\u2029d\\u0000e"))
        assertFalse(script.contains("a<b"))
        assertFalse(script.contains("\u2028"))
        assertFalse(script.contains("\u0000"))
    }

    @Test
    fun buildDispatchDroppedFilesScriptCanForwardTextPlainDropData() {
        val script = OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(
            emptyList(),
            textPlain = listOf("file:src/main/App.kt"),
            enabled = true,
        )!!

        assertTrue(script.contains("transfer.setData('text/plain', 'file:src/main/App.kt')"))
        assertTrue(script.contains("const event = new DragEvent('drop'"))
    }

    @Test
    fun buildDispatchDroppedFilesScriptDispatchesTextPlainDropsSeparately() {
        val script = OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(
            emptyList(),
            textPlain = listOf("file:CHANGELOG.md", "file:gradle.properties"),
            enabled = true,
        )!!

        assertTrue(script.contains("results.push(dispatchDrop((transfer) => transfer.setData('text/plain', 'file:CHANGELOG.md')))"))
        assertTrue(script.contains("results.push(dispatchDrop((transfer) => transfer.setData('text/plain', 'file:gradle.properties')))"))
    }

    @Test
    fun buildDispatchDroppedFilesScriptPastesGenericTextAndReportsAcceptance() {
        val script = OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(
            emptyList(),
            textPlain = listOf("selected code"),
            batchId = "chat-1",
            resultCallback = "window.intellijResult(batchId, accepted)",
        )!!

        assertTrue(script.contains("new ClipboardEvent('paste'"))
        assertTrue(script.contains("results.push(dispatchPaste('selected code'))"))
        assertTrue(script.contains("const batchId = 'chat-1'"))
        assertTrue(script.contains("window.intellijResult(batchId, accepted)"))
        assertTrue(script.contains("results.every(Boolean)"))
    }

    @Test
    fun buildDispatchDroppedFilesScriptPastesMultilineSelectionBeginningWithFileReference() {
        val selection = """file:src/main/App.kt
            |src/main/App.kt lines 1-2:
            |```kotlin
            |fun main() = Unit
            |```""".trimMargin()

        val script = OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(
            emptyList(),
            textPlain = listOf(selection),
        )!!

        assertTrue(script.contains("results.push(dispatchPaste('file:src/main/App.kt\\n"))
        assertFalse(script.contains("transfer.setData('text/plain', 'file:src/main/App.kt\\n"))
    }

    @Test
    fun buildDispatchDroppedFilesScriptIsMissingWithoutFiles() {
        assertNull(OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(emptyList()))
        assertNull(OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(emptyList(), textPlain = emptyList(), enabled = true))
    }

    @Test
    fun buildDispatchDroppedFilesScriptIsMissingWhenDisabled() {
        assertNull(
            OpenCodeBrowserSnippets.buildDispatchDroppedFilesScript(
                listOf(
                    OpenCodeServerProtocol.DroppedFilePayload(
                        name = "hello.txt",
                        mime = "text/plain",
                        lastModified = 123,
                        base64 = "aGVsbG8=",
                    ),
                ),
                enabled = false,
            ),
        )
    }

    @Test
    fun buildMatchMediaPatchScriptIsMissingWhenBothDisabled() {
        assertNull(OpenCodeBrowserSnippets.buildMatchMediaPatchScript(compact = false, theme = false, dark = true))
    }

    @Test
    fun buildMatchMediaPatchScriptCombinesCompactAndThemeInOneWrapper() {
        val script = OpenCodeBrowserSnippets.buildMatchMediaPatchScript(compact = true, theme = true, dark = true)!!

        assertEquals(1, Regex("window\\.matchMedia = ").findAll(script).count())
        assertTrue(script.contains("(min-width:768px)"))
        assertTrue(script.contains("THEME_KEY"))
        assertTrue(script.contains("window.__opencodeIntellijOrigMatchMedia"))
    }

    @Test
    fun buildCompactLayoutScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeBrowserSnippets.buildCompactLayoutScript(enabled = false))
    }

    @Test
    fun buildCompactLayoutScriptPatchesMatchMediaOnly() {
        val script = OpenCodeBrowserSnippets.buildCompactLayoutScript(enabled = true)!!

        assertTrue(script.contains("window.__opencodeIntellijCompactInstalled"))
        assertTrue(script.contains("window.matchMedia = "))
        assertTrue(script.contains("(min-width:768px)"))
        assertTrue(script.contains("(max-width:767px)"))
        // Whitespace-tolerant match so minifier/formatter changes do not disable the stub.
        assertTrue(script.contains("const keyOf = (q) =>"))
        assertTrue(script.contains("replace(/\\s+/g, '')"))
        assertTrue(script.contains("stub(q, false)"))
        assertTrue(script.contains("stub(q, true)"))
        // No Tailwind class overrides — layout is driven only by the media-query stub.
        assertFalse(script.contains("opencode-intellij-compact-layout"))
        assertFalse(script.contains("md\\\\:flex-row"))
        assertFalse(script.contains("createElement('style')"))
    }

    @Test
    fun buildHideWebsiteButtonScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeBrowserSnippets.buildHideWebsiteButtonScript(enabled = false))
    }

    @Test
    fun buildHideWebsiteButtonScriptUsesDurableHrefSelectors() {
        val script = OpenCodeBrowserSnippets.buildHideWebsiteButtonScript(enabled = true)!!

        assertTrue(script.contains("window.__opencodeIntellijHideWebsiteButtonInstalled"))
        assertTrue(script.contains("href^=\"https://opencode.ai\""))
        assertTrue(script.contains("data-component*=\"icon-button\""))
        // Locale-specific labels and Tailwind layout utilities are not matchers.
        assertFalse(script.contains(".fixed"))
        assertFalse(script.contains("Open the OpenCode website"))
        assertFalse(script.contains("bottom-5"))
        assertFalse(script.contains("right-5"))
    }

    @Test
    fun buildPathHoverPreviewScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeBrowserSnippets.buildPathHoverPreviewScript(enabled = false))
    }

    @Test
    fun buildPathHoverPreviewScriptShortensTabDelayAndOverlaysProjectRows() {
        val script = OpenCodeBrowserSnippets.buildPathHoverPreviewScript(enabled = true)!!

        assertTrue(script.contains("window.__opencodeIntellijPathHoverPreviewInstalled"))
        assertTrue(script.contains("session-tab-popover-trigger"))
        assertTrue(script.contains("home-project-row"))
        assertTrue(script.contains("home-session-row"))
        assertTrue(script.contains("home-session-project-name"))
        assertTrue(script.contains("opencode.global.dat:server"))
        assertTrue(script.contains("const TAB_DELAY = ${OpenCodeBrowserSnippets.OPENCODE_TAB_POPOVER_OPEN_DELAY_MILLIS}"))
        assertTrue(script.contains("const PREVIEW_DELAY = ${OpenCodeBrowserSnippets.PATH_HOVER_PREVIEW_DELAY_MILLIS}"))
        assertTrue(script.contains("data-opencode-intellij-path-preview"))
        // Home also renders session rows: only project rows may participate in the worktree
        // count/order fallback. Session rows instead require an unambiguous project name.
        assertTrue(script.contains("const PROJECT_ROW = '[data-component=\"home-project-row\"]'"))
        assertTrue(script.contains("const HOVER_ROW = PROJECT_ROW + ', [data-component=\"home-session-row\"]'"))
        assertTrue(script.contains("if (!row.matches(PROJECT_ROW)) return ''"))
        assertTrue(script.contains("return node.closest(HOVER_ROW)"))
        assertTrue(script.contains("querySelectorAll(PROJECT_ROW)"))
        // Only Kobalte's pointerenter-scheduled 2000ms timer is clamped; unrelated page timers
        // with the same delay (copy-state reset, typewriter cursor) must keep their timing.
        assertTrue(script.contains("arguments.length === 2"))
        assertTrue(script.contains("ENTER_CLAMP_WINDOW_MILLIS"))
        assertTrue(script.contains("pointerenter"))
        assertFalse(script.contains("Projects"))
    }

    @Test
    fun buildEventStreamWatchdogScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(enabled = false))
    }

    @Test
    fun buildEventStreamWatchdogScriptWatchesEveryEventRouteGeneration() {
        val script = OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(enabled = true)!!

        assertTrue(script.contains("window.__opencodeIntellijEventWatchdogInstalled"))
        // All event route generations the SPA may pick, matched by pathname (not full URL) so
        // origin rewrites and directory/workspace query parameters cannot bypass the watchdog.
        assertTrue(script.contains("'/global/event'"))
        assertTrue(script.contains("'/event'"))
        assertTrue(script.contains("'/api/event'"))
        assertTrue(script.contains("new URL(requestUrl(input), location.href).pathname"))
        assertTrue(script.contains("typeof input.url === 'string'"))
        assertTrue(script.contains("typeof input.href === 'string'"))
        // Recovery works by aborting so OpenCode's own reconnect loop sees a stream error;
        // the plugin must never reimplement the stream or reload the page here.
        assertTrue(script.contains("controller.abort()"))
        assertFalse(script.contains("location.reload"))
    }

    @Test
    fun buildEventStreamWatchdogScriptDoesNotClaimInstallBeforeWrap() {
        val script = OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(enabled = true)!!
        val installedAt = script.indexOf("window.__opencodeIntellijEventWatchdogInstalled = true")
        val fetchGuardAt = script.indexOf("typeof realFetch !== 'function'")
        assertTrue(installedAt > 0)
        assertTrue(fetchGuardAt > 0)
        assertTrue(fetchGuardAt < installedAt)
    }

    @Test
    fun buildEventStreamWatchdogScriptRewrapsRequestInputs() {
        val script = OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(enabled = true)!!
        assertTrue(script.contains("input instanceof Request"))
        assertTrue(script.contains("new Request(input, options)"))
        assertTrue(script.contains("globalThis.fetch = watchedFetch"))
    }

    @Test
    fun buildEventStreamWatchdogScriptChainsTheCallerAbortSignal() {
        val script = OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(enabled = true)!!

        // The SPA cancels its own stream on stop()/cleanup; dropping that signal would leak
        // the previous connection on every reconnect.
        assertTrue(script.contains("outer.addEventListener('abort'"))
        assertTrue(script.contains("outer.removeEventListener('abort'"))
        assertTrue(script.contains("if (outer.aborted) controller.abort();"))
    }

    @Test
    fun buildEventStreamWatchdogScriptTimesOutBeforeResponseHeaders() {
        val script = OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(enabled = true)!!

        val armAt = script.indexOf("arm();")
        val fetchAt = script.indexOf("return realFetch.call")
        assertTrue(armAt > 0)
        assertTrue(fetchAt > armAt)
    }

    @Test
    fun buildEventStreamWatchdogScriptKeepsTimeoutAboveTheHeartbeatInterval() {
        // Heartbeats arrive every 10s; a timeout at or below that would reconnect endlessly on
        // a perfectly healthy stream.
        val script = OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(enabled = true)!!
        assertTrue(script.contains("const STALL_MS = ${OpenCodeBrowserSnippets.EVENT_STREAM_STALL_TIMEOUT_MILLIS};"))

        val clamped = OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(enabled = true, stallTimeoutMillis = 1_000)!!
        assertTrue(clamped.contains("const STALL_MS = ${OpenCodeBrowserSnippets.MIN_EVENT_STREAM_STALL_TIMEOUT_MILLIS};"))
    }

    @Test
    fun buildForceEventReconnectScriptIsANoOpWithoutTheWatchdog() {
        val script = OpenCodeBrowserSnippets.buildForceEventReconnectScript()

        // Fired unconditionally on resume, including when the safeguard is off and the hook
        // was never installed, so it must guard before calling.
        assertTrue(script.contains("typeof force === 'function'"))
        assertTrue(script.contains("window.__opencodeIntellijForceEventReconnect"))
    }

    @Test
    fun buildChunkLoadRecoveryScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeBrowserSnippets.buildChunkLoadRecoveryScript(enabled = false, fatalCallback = "report();"))
    }

    @Test
    fun buildChunkLoadRecoveryScriptIsMissingWithoutACallbackChannel() {
        // The JCEF callback channel can fail to be created (out-of-process CEF on Windows);
        // the feature then injects nothing instead of shipping a broken reporter into the page.
        assertNull(OpenCodeBrowserSnippets.buildChunkLoadRecoveryScript(enabled = true, fatalCallback = null))
    }

    @Test
    fun buildRendererHeartbeatScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeBrowserSnippets.buildRendererHeartbeatScript(enabled = false, heartbeatCallback = "beat();"))
    }

    @Test
    fun buildRendererHeartbeatScriptIsMissingWithoutACallbackChannel() {
        // Without a JCEF callback channel (macOS/Windows out-of-process JCEF can refuse its
        // creation) the heartbeat is pointless — a silence-only watchdog would recover
        // spuriously. Inject nothing then.
        assertNull(OpenCodeBrowserSnippets.buildRendererHeartbeatScript(enabled = true, heartbeatCallback = null))
    }

    @Test
    fun buildRendererHeartbeatScriptIsIdempotentAndReportsVisibility() {
        val script = OpenCodeBrowserSnippets.buildRendererHeartbeatScript(enabled = true, heartbeatCallback = "beat(visibility);")!!

        assertTrue(script.contains("window.__opencodeIntellijRendererHeartbeatInstalled"))
        assertTrue(script.contains("document.visibilityState"))
        assertTrue(script.contains("setInterval"))
        assertTrue(script.contains("addEventListener('visibilitychange', beat)"))
        assertFalse(script.contains("requestAnimationFrame"))
        assertFalse(script.contains("if (!document.hidden)"))
    }

    @Test
    fun buildChunkLoadRecoveryScriptIsIdempotent() {
        val script = OpenCodeBrowserSnippets.buildChunkLoadRecoveryScript(enabled = true, fatalCallback = "report();")!!

        assertTrue(script.contains("window.__opencodeIntellijChunkRecoveryInstalled"))
    }

    @Test
    fun buildChunkLoadRecoveryScriptCoversScriptSrcAndImportFailures() {
        val script = OpenCodeBrowserSnippets.buildChunkLoadRecoveryScript(enabled = true, fatalCallback = "report();")!!

        // Module-script src failures surface as resource error events (event.target.src);
        // import() failures surface as the "dynamically imported module" message the boundary shows.
        // Solid's error boundary often catches the rejected lazy() promise, so the same engine
        // text is scanned from the error-page details field (textarea/input value).
        // Hidden JCEF does not run requestAnimationFrame; scans use setTimeout instead.
        // Only readOnly fields are scanned: editable inputs can hold pasted engine text.
        assertTrue(script.contains("addEventListener('error'"))
        assertTrue(script.contains("addEventListener('unhandledrejection'"))
        assertTrue(script.contains("target.tagName === 'SCRIPT'"))
        assertTrue(script.contains("failed to fetch dynamically imported module"))
        assertTrue(script.contains("querySelectorAll('textarea, input, [data-slot=\"input-input\"]')"))
        assertTrue(script.contains("isReadOnlyField"))
        assertTrue(script.contains("setTimeout"))
        assertTrue(script.contains("visibilitychange"))
        assertFalse(script.contains("requestAnimationFrame"))
        assertTrue(script.contains("assets"))
        assertTrue(script.contains("_?assets"))
        assertFalse(script.contains("location.reload"))
    }

    @Test
    fun buildChunkLoadRecoveryScriptSignalsAtMostOncePerPage() {
        val script = OpenCodeBrowserSnippets.buildChunkLoadRecoveryScript(enabled = true, fatalCallback = "report();")!!

        // The boundary is terminal: after the first report the reload fixes the renderer, so a
        // second signal would only race another reload into the load the first one started.
        assertTrue(script.contains("let notified = false;"))
        assertTrue(script.contains("notified = true;"))
        assertTrue(script.contains("observer.disconnect()"))
    }

    @Test
    fun buildViewportRasterNudgeScriptTogglesTranslateWithoutHostResize() {
        val script = OpenCodeBrowserSnippets.buildViewportRasterNudgeScript()

        assertTrue(script.contains("document.documentElement"))
        assertTrue(script.contains("setProperty('transform', 'translate(1px, 0)')"))
        assertTrue(script.contains("removeProperty('transform')"))
        assertTrue(script.contains("requestAnimationFrame"))
        assertTrue(script.contains("dispatchEvent(new Event('resize'))"))
        assertFalse(script.contains("data-component"))
        assertFalse(script.contains("setBounds"))
        assertFalse(script.contains("1.001"))
    }

    @Test
    fun isInPlaceDialogRepaintEventCoversAskAndDismiss() {
        assertTrue(OpenCodeBrowserSnippets.isInPlaceDialogRepaintEvent("permission.asked"))
        assertTrue(OpenCodeBrowserSnippets.isInPlaceDialogRepaintEvent("permission.replied"))
        assertTrue(OpenCodeBrowserSnippets.isInPlaceDialogRepaintEvent("question.asked"))
        assertTrue(OpenCodeBrowserSnippets.isInPlaceDialogRepaintEvent("question.replied"))
        assertTrue(OpenCodeBrowserSnippets.isInPlaceDialogRepaintEvent("question.rejected"))
        assertFalse(OpenCodeBrowserSnippets.isInPlaceDialogRepaintEvent("session.status"))
        assertFalse(OpenCodeBrowserSnippets.isInPlaceDialogRepaintEvent("session.created"))
    }

    @Test
    fun buildEventStreamWatchdogScriptExposesTheForceReconnectHook() {
        val script = OpenCodeBrowserSnippets.buildEventStreamWatchdogScript(enabled = true)!!

        assertTrue(script.contains("window.__opencodeIntellijForceEventReconnect = () =>"))
        // Finished streams must leave the tracking set or the hook would abort dead controllers
        // and leak them for the lifetime of the page.
        assertTrue(script.contains("active.delete(controller)"))
    }

    @Test
    fun buildCompactLayoutScriptIsIdempotent() {
        val script = OpenCodeBrowserSnippets.buildCompactLayoutScript(enabled = true)!!

        assertTrue(script.contains("window.__opencodeIntellijMatchMediaInstalled"))
    }

    @Test
    fun buildIdeThemeSyncScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeBrowserSnippets.buildIdeThemeSyncScript(enabled = false, dark = true))
    }

    @Test
    fun buildIdeThemeSyncScriptPatchesMatchMediaForPrefersColorScheme() {
        val darkScript = OpenCodeBrowserSnippets.buildIdeThemeSyncScript(enabled = true, dark = true)!!
        val lightScript = OpenCodeBrowserSnippets.buildIdeThemeSyncScript(enabled = true, dark = false)!!

        assertTrue(darkScript.contains("(prefers-color-scheme: dark)"))
        assertTrue(darkScript.contains("const dark = true"))
        assertTrue(lightScript.contains("const dark = false"))
        assertTrue(darkScript.contains("window.__opencodeIntellijMatchMediaInstalled"))
        assertTrue(darkScript.contains("const THEME_KEY = '(prefers-color-scheme:dark)'"))
        assertTrue(darkScript.contains("replace(/\\s+/g, '').toLowerCase()"))
        assertTrue(darkScript.contains("theme && key === THEME_KEY"))
        assertTrue(darkScript.contains("matches: dark"))
        assertFalse(darkScript.contains("window.localStorage.setItem"))
        assertFalse(darkScript.contains("StorageEvent"))
    }

    @Test
    fun buildIdeThemeSyncScriptDispatchesChangeEventOnUpdate() {
        val script = OpenCodeBrowserSnippets.buildIdeThemeSyncScript(enabled = true, dark = true)!!

        assertTrue(script.contains("MediaQueryListEvent('change'"))
        assertTrue(script.contains("window.__opencodeIntellijThemeMql"))
        assertTrue(script.contains("window.__opencodeIntellijThemeDark !== dark"))
    }

    @Test
    fun buildProjectSwitchPromptSuppressionScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeBrowserSnippets.buildProjectSwitchPromptSuppressionScript(enabled = false))
    }

    @Test
    fun buildProjectSwitchPromptSuppressionScriptDismissesGoToSessionNotifications() {
        val script = OpenCodeBrowserSnippets.buildProjectSwitchPromptSuppressionScript(enabled = true)!!

        assertTrue(script.contains("window.__opencodeIntellijProjectSwitchPromptSuppressionInstalled"))
        assertTrue(script.contains("[data-component=\"toast\"], [data-component=\"toast-v2\"]"))
        // Locale-independent structural match: sprite icon names, not translated labels.
        // Both v1 and v2 sprite prefixes are covered so an icon-system migration stays matched.
        assertTrue(script.contains("use[href=\"#opencode-icon-checklist\"]"))
        assertTrue(script.contains("use[href=\"#opencode-icon-bubble-5\"]"))
        assertTrue(script.contains("use[href=\"#opencode-v2-icon-checklist\"]"))
        assertTrue(script.contains("use[href=\"#opencode-v2-icon-bubble-5\"]"))
        assertTrue(script.contains("[data-slot=\"toast-icon\"], [data-slot=\"toast-v2-icon\"]"))
        assertFalse(script.contains("Permission required"))
        assertFalse(script.contains("Go to session"))
        assertTrue(script.contains("[data-slot=\"toast-close-button\"], [data-slot=\"toast-v2-close-button\"]"))
        assertTrue(script.contains("new MutationObserver"))
        // Bounded blast radius: auto-dismissals are capped per page load, and hitting the cap
        // disconnects the observer (suppression off for this load) with a single warning.
        assertTrue(script.contains("const MAX_DISMISSALS = 20;"))
        assertTrue(script.contains("if (dismissals >= MAX_DISMISSALS)"))
        assertTrue(script.contains("observer.disconnect()"))
        assertTrue(script.contains("OpenCode toast suppression cap reached"))
    }

    @Test
    fun buildCursorMirrorScriptIsMissingWhenDisabledOrIncomplete() {
        assertNull(OpenCodeBrowserSnippets.buildCursorMirrorScript(enabled = false, cursorCallback = "cb(payload)"))
        assertNull(OpenCodeBrowserSnippets.buildCursorMirrorScript(enabled = true, cursorCallback = null))
    }

    @Test
    fun buildCursorMirrorScriptTracksHoveredElementCursor() {
        val script = OpenCodeBrowserSnippets.buildCursorMirrorScript(
            enabled = true,
            cursorCallback = "window.intellijCursor(payload)",
        )!!

        assertTrue(script.contains("window.__opencodeIntellijCursorMirrorInstalled"))
        assertTrue(script.contains("getComputedStyle(el).cursor"))
        assertTrue(script.contains("caretPositionFromPoint"))
        assertTrue(script.contains("caretRangeFromPoint"))
        assertTrue(script.contains("addEventListener('pointermove'"))
        assertTrue(script.contains("addEventListener('pointerdown'"))
        assertTrue(script.contains("addEventListener('pointerup'"))
        assertTrue(script.contains("addEventListener('scroll'"))
        assertTrue(script.contains("addEventListener('mouseout'"))
        assertTrue(script.contains("send('default')"))
        assertTrue(script.contains("event.buttons !== 0"))
        assertTrue(script.contains("window.intellijCursor(payload)"))
    }

    @Test
    fun awtCursorTypeCoversCommonCssCursors() {
        assertEquals(java.awt.Cursor.DEFAULT_CURSOR, OpenCodeBrowserSnippets.awtCursorTypeForCss(null))
        assertEquals(java.awt.Cursor.DEFAULT_CURSOR, OpenCodeBrowserSnippets.awtCursorTypeForCss("default"))
        assertEquals(java.awt.Cursor.DEFAULT_CURSOR, OpenCodeBrowserSnippets.awtCursorTypeForCss("auto"))
        assertEquals(java.awt.Cursor.HAND_CURSOR, OpenCodeBrowserSnippets.awtCursorTypeForCss("pointer"))
        assertEquals(java.awt.Cursor.TEXT_CURSOR, OpenCodeBrowserSnippets.awtCursorTypeForCss("text"))
        assertEquals(java.awt.Cursor.WAIT_CURSOR, OpenCodeBrowserSnippets.awtCursorTypeForCss("progress"))
        assertEquals(java.awt.Cursor.S_RESIZE_CURSOR, OpenCodeBrowserSnippets.awtCursorTypeForCss("row-resize"))
        assertEquals(java.awt.Cursor.S_RESIZE_CURSOR, OpenCodeBrowserSnippets.awtCursorTypeForCss("ns-resize"))
        assertEquals(java.awt.Cursor.W_RESIZE_CURSOR, OpenCodeBrowserSnippets.awtCursorTypeForCss("col-resize"))
        assertEquals(java.awt.Cursor.MOVE_CURSOR, OpenCodeBrowserSnippets.awtCursorTypeForCss("grabbing"))
        // Unknown keywords resolve to the default arrow; custom cursors use their keyword fallback.
        assertEquals(java.awt.Cursor.DEFAULT_CURSOR, OpenCodeBrowserSnippets.awtCursorTypeForCss("zoom-in"))
        assertEquals(java.awt.Cursor.HAND_CURSOR, OpenCodeBrowserSnippets.awtCursorTypeForCss("url(\"custom.png\") 4 4, pointer"))
        assertEquals(java.awt.Cursor.DEFAULT_CURSOR, OpenCodeBrowserSnippets.awtCursorTypeForCss("URL(x.cur)"))
    }

    @Test
    fun buildFilePasteSuppressionScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeBrowserSnippets.buildFilePasteSuppressionScript(enabled = false))
    }

    @Test
    fun buildFilePasteSuppressionScriptCancelsFilePasteEvents() {
        val script = OpenCodeBrowserSnippets.buildFilePasteSuppressionScript(enabled = true)!!

        assertTrue(script.contains("window.__opencodeIntellijFilePasteSuppressionInstalled"))
        assertTrue(script.contains("document.addEventListener('paste'"))
        assertFalse(script.contains("__opencodeIntellijSuppressNativeFilePasteUntil"))
        assertTrue(script.contains("item.kind === 'file'"))
        assertTrue(script.contains("includes('Files')"))
        assertTrue(script.contains("event.preventDefault()"))
        assertTrue(script.contains("event.stopImmediatePropagation()"))
    }

    @Test
    fun buildCodeNavigationScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeBrowserSnippets.buildCodeNavigationScript(enabled = false, openCodeCallback = "callback(ref)"))
    }

    @Test
    fun buildCodeNavigationScriptIsMissingWithoutCallback() {
        assertNull(OpenCodeBrowserSnippets.buildCodeNavigationScript(enabled = true, openCodeCallback = null))
    }

    @Test
    fun buildCodeNavigationScriptInstallsClickListenerOnCodeElements() {
        val script = OpenCodeBrowserSnippets.buildCodeNavigationScript(enabled = true, openCodeCallback = "window.intellijOpenCodeRef(ref)")!!

        assertTrue(script.contains("window.__opencodeIntellijCodeNavInstalled"))
        assertTrue(script.contains("event.target.closest('code')"))
        assertTrue(script.contains("codeEl.closest('[data-component=\"markdown\"]')"))
        assertTrue(script.contains("codeEl.closest('pre')"))
        assertTrue(script.contains("codeEl.closest('a')"))
        assertTrue(script.contains("data-inline-code-kind"))
        assertTrue(script.contains("kind === 'url'"))
        assertTrue(script.contains("kind === 'path'"))
        assertTrue(script.contains("isSnakeCase"))
        assertTrue(script.contains("data-opencode-intellij-pointer"))
        assertTrue(script.contains("opencode-intellij-pointer-cursor"))
        assertTrue(script.contains("cursor: pointer !important"))
        assertTrue(script.contains("document.addEventListener('mouseover'"))
        assertTrue(script.contains("document.addEventListener('mouseout'"))
        assertTrue(script.contains("hasExtension"))
        assertTrue(script.contains("hasPathLocator"))
        assertTrue(script.contains("fileLocAtEvent"))
        assertTrue(script.contains("fileLocWithDir"))
        assertTrue(script.contains("fileLocBare"))
        assertTrue(script.contains("bash-pre"))
        assertTrue(script.contains("tool-output"))
        assertTrue(script.contains("tool-loaded-file"))
        assertTrue(script.contains("File"))
        assertTrue(script.contains("isUrl"))
        assertTrue(script.contains("isQualifiedClass"))
        assertTrue(script.contains("isPascalCase"))
        assertTrue(script.contains("isTypeMember"))
        assertTrue(script.contains("isTypeMemberBare"))
        assertTrue(script.contains("fileExt"))
        assertTrue(script.contains("adjacentLocator"))
        assertTrue(script.contains("withAdjacentLocator"))
        assertTrue(script.contains("codeBesideLocator"))
        assertTrue(script.contains("(L?"))
        assertTrue(script.contains("if (event.defaultPrevented) return"))
        assertTrue(script.contains("event.stopImmediatePropagation()"))
        assertTrue(script.contains("window.intellijOpenCodeRef(ref)"))
    }

    @Test
    fun buildFileLinkHandlerScriptStopsAlreadyHandledClicks() {
        val script = OpenCodeBrowserSnippets.buildFileLinkHandlerScript(
            "/tmp/project",
            enabled = true,
            openFileCallback = "window.intellijOpenFile(rawHref)",
        )!!

        assertTrue(script.contains("if (event.defaultPrevented) return"))
        assertTrue(script.contains("event.stopImmediatePropagation()"))
    }

    @Test
    fun buildDiffNavigationScriptIsMissingWhenDisabled() {
        assertNull(OpenCodeBrowserSnippets.buildDiffNavigationScript(enabled = false, openDiffCallback = "cb(payload)"))
    }

    @Test
    fun buildDiffNavigationScriptIsMissingWithoutCallback() {
        assertNull(OpenCodeBrowserSnippets.buildDiffNavigationScript(enabled = true, openDiffCallback = null))
    }

    @Test
    fun buildDiffNavigationScriptInstallsAltClickHandlerForDiffTargets() {
        val script = OpenCodeBrowserSnippets.buildDiffNavigationScript(
            enabled = true,
            openDiffCallback = "window.__openDiff(messageID, filePath, partID)",
        )!!
        assertTrue(script.contains("event.altKey"))
        assertTrue(script.contains("isDiffGesture"))
        assertTrue(script.contains("event.metaKey"))
        assertTrue(script.contains("event.ctrlKey"))
        assertTrue(script.contains("isMac"))
        assertTrue(script.contains(".replace(/\\\\/g, '/')"))
        assertTrue(script.contains("addEventListener('click'"))
        assertTrue(script.contains("[data-file]"))
        assertTrue(script.contains("edit-tool"))
        assertTrue(script.contains("write-tool"))
        assertTrue(script.contains("apply-patch-tool"))
        assertTrue(script.contains("apply-patch-trigger-content"))
        assertTrue(script.contains("apply-patch-filename"))
        assertTrue(script.contains("diff-changes"))
        assertTrue(script.contains("session-turn-diff-trigger"))
        assertTrue(script.contains("session-turn-diff-filename"))
        assertTrue(script.contains("[data-message-id]"))
        assertTrue(script.contains("[data-timeline-part-id]"))
        assertTrue(script.contains("partIdOf(patchRow)"))
        assertTrue(script.contains("partIdOf(editBlock)"))
        assertTrue(script.contains("opencode-intellij-open-file"))
        assertTrue(script.contains("messageIdOf(turnRow)"))
        assertTrue(script.contains("user-message"))
        assertTrue(script.contains("text-part"))
        assertTrue(script.contains("file-tree-v2-row"))
        assertTrue(script.contains("session-review-v2-sidebar"))
        assertTrue(script.contains("select-v2"))
        assertTrue(script.contains("vcsMode: 'working'"))
        assertTrue(script.contains("vcsMode: 'branch'"))
        assertTrue(script.contains("filesBrowserRow"))
        assertTrue(script.contains("window.__openDiff(messageID, filePath, partID)"))
        assertTrue(script.contains("}, true)"))
    }

    @Test
    fun fileLinkHandlerReservesDiffGesture() {
        val script = OpenCodeBrowserSnippets.buildFileLinkHandlerScript("/tmp/project", enabled = true)!!
        assertTrue(script.contains("event.altKey"))
        assertTrue(script.contains("event.metaKey"))
        assertTrue(script.contains("event.ctrlKey"))
        assertTrue(script.contains("filesBrowserRow"))
        assertTrue(script.contains("file-tree-v2-row"))
        assertTrue(script.contains("if (!filesBrowserRow(event.target)) return"))
    }
}
