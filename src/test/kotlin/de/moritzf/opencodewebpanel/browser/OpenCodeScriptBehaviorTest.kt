package de.moritzf.opencodewebpanel.browser

import com.google.gson.Gson
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Executes policy-level JS. Actual DOM/SPA integration remains in the real-server browser gate. */
class OpenCodeScriptBehaviorTest {
    @Test
    fun projectSeedPreservesMetadataAndSessionSelectionAndDropsOnlyForeignTabs() {
        runJavaScript(
            mapOf(
                "seed" to
                    OpenCodeBrowserSnippets.buildOpenProjectScript(
                        "C:/Work/Repo",
                        "http://127.0.0.1:4096",
                    )
            ),
            """
            const key = 'opencode.global.dat:server';
            const layout = 'opencode.global.dat:layout.page';
            localStorage.setItem(key, JSON.stringify({ projects: { local: [
              { worktree: 'c:\\work\\repo', expanded: false, extra: 42 },
              { worktree: 'C:/WORK/REPO/' }, { worktree: '/other' }
            ] }, lastProject: { remote: '/remote' }, unknown: 'kept' }));
            localStorage.setItem(layout, JSON.stringify({ lastProjectSession: { sessionID: 'ses_keep' } }));
            localStorage.setItem('opencode.window.browser.dat:tabs', 'foreign tabs');
            eval(scripts.seed);
            const seeded = JSON.parse(localStorage.getItem(key));
            assert.deepEqual(seeded.projects.local, [
              { worktree: 'C:/Work/Repo', expanded: false, extra: 42 }, { worktree: '/other' }
            ]);
            assert.equal(seeded.lastProject.local, 'C:/Work/Repo');
            assert.equal(seeded.lastProject.remote, '/remote');
            assert.equal(seeded.unknown, 'kept');
            assert.equal(localStorage.getItem('opencode.window.browser.dat:tabs'), null);
            assert.equal(JSON.parse(localStorage.getItem(layout)).lastProjectSession.sessionID, 'ses_keep');
            localStorage.setItem('opencode.window.browser.dat:tabs', 'current tabs');
            eval(scripts.seed);
            assert.equal(localStorage.getItem('opencode.window.browser.dat:tabs'), 'current tabs');
            localStorage.setItem(key, '[]');
            eval(scripts.seed);
            assert.equal(localStorage.getItem(key), '[]');
            window.location.origin = 'http://127.0.0.1:9999';
            localStorage.clear();
            eval(scripts.seed);
            assert.equal(localStorage.length, 0);
            """,
        )
    }

    @Test
    fun storageMirrorRestoresOnlyMissingPreferencesAndKeepsWorkspaceStatePrivate() {
        runJavaScript(
            mapOf(
                "restore" to
                    OpenCodeBrowserSnippets.buildRestoreOpenCodeLocalStorageScript(
                        """{"settings.v3":"old","opencode-theme-id":"dark","opencode.global.dat:server":"foreign","opencode.window.browser.dat:tabs":"foreign"}"""
                    ),
                "sync" to
                    OpenCodeBrowserSnippets.buildSyncOpenCodeLocalStorageScript(
                        "reports.push(JSON.parse(payload))"
                    ),
            ),
            """
            const reports = [];
            localStorage.setItem('settings.v3', 'existing');
            localStorage.setItem('opencode.window.browser.dat:tabs', 'local tabs');
            eval(scripts.restore);
            assert.equal(localStorage.getItem('settings.v3'), 'existing');
            assert.equal(localStorage.getItem('opencode-theme-id'), 'dark');
            assert.equal(localStorage.getItem('opencode.global.dat:server'), null);
            assert.equal(localStorage.getItem('opencode.window.browser.dat:tabs'), 'local tabs');
            eval(scripts.sync);
            assert.deepEqual(reports.pop(), { 'settings.v3': 'existing', 'opencode-theme-id': 'dark' });
            localStorage.setItem('settings.v3', 'changed');
            flushTimers();
            assert.equal(reports.pop()['settings.v3'], 'changed');
            localStorage.setItem('opencode.window.browser.dat:tabs', 'more local tabs');
            flushTimers();
            assert.equal(reports.length, 0);
            localStorage.setItem('settings.v3', 'x'.repeat(131073));
            flushTimers();
            assert.equal('settings.v3' in reports.pop(), false);
            """,
        )
    }

    @Test
    fun mediaPatchIsIdempotentAndKeepsNativeQueriesAndThemeListenersWorking() {
        runJavaScript(
            mapOf(
                "dark" to OpenCodeBrowserSnippets.buildMatchMediaPatchScript(true, true, true),
                "light" to OpenCodeBrowserSnippets.buildMatchMediaPatchScript(true, true, false),
            ),
            """
            const nativeQueries = [];
            window.matchMedia = media => { nativeQueries.push(media); return { media, matches: 'native' }; };
            global.MediaQueryListEvent = class { constructor(type, init) { this.type = type; Object.assign(this, init); } };
            eval(scripts.dark);
            const wrapped = window.matchMedia;
            assert.equal(window.matchMedia('(min-width: 768px)').matches, false);
            assert.equal(window.matchMedia('(max-width: 767px)').matches, true);
            assert.equal(window.matchMedia('(prefers-reduced-motion: reduce)').matches, 'native');
            assert.deepEqual(nativeQueries, ['(prefers-reduced-motion: reduce)']);
            const theme = window.matchMedia('(prefers-color-scheme: dark)');
            const changes = [];
            theme.addEventListener('change', event => changes.push(event.matches));
            eval(scripts.dark);
            assert.equal(window.matchMedia, wrapped);
            assert.deepEqual(changes, []);
            eval(scripts.light);
            assert.equal(window.matchMedia, wrapped);
            assert.equal(theme.matches, false);
            assert.deepEqual(changes, [false]);
            """,
        )
    }

    @Test
    fun heartbeatReportsVisibilityOncePerInstallAndAtBoundedCadence() {
        runJavaScript(
            mapOf(
                "heartbeat" to
                    OpenCodeBrowserSnippets.buildRendererHeartbeatScript(
                        true,
                        "reports.push(visibility)",
                    )
            ),
            """
            const reports = [];
            const intervals = [];
            window.setInterval = (callback, millis) => intervals.push({ callback, millis });
            eval(scripts.heartbeat);
            eval(scripts.heartbeat);
            assert.deepEqual(reports, ['visible']);
            assert.equal(intervals.length, 1);
            assert.equal(intervals[0].millis, 5000);
            document.visibilityState = 'hidden';
            document.dispatchEvent(new Event('visibilitychange'));
            intervals[0].callback();
            assert.deepEqual(reports, ['visible', 'hidden', 'hidden']);
            """,
        )
    }

    private fun runJavaScript(scripts: Map<String, String?>, scenario: String) {
        scripts.forEach { (name, script) ->
            requireNotNull(script) { "Missing enabled script: $name" }
        }
        val node =
            System.getenv("PATH")
                .orEmpty()
                .split(File.pathSeparator)
                .flatMap { dir -> listOf(File(dir, "node"), File(dir, "node.exe")) }
                .firstOrNull { it.isFile && it.canExecute() }
        assumeTrue("node is not on PATH", node != null)
        val directory = Files.createTempDirectory("opencode-js-behavior-")
        try {
            val source = directory.resolve("scenario.cjs")
            Files.writeString(
                source,
                "const scripts = ${Gson().toJson(scripts)};\n" +
                    """
                    const assert = require('node:assert/strict');
                    class Storage {
                      constructor() { this.entries = new Map(); }
                      get length() { return this.entries.size; }
                      key(index) { return [...this.entries.keys()][index] ?? null; }
                      getItem(key) { return this.entries.get(key) ?? null; }
                      setItem(key, value) { this.entries.set(String(key), String(value)); }
                      removeItem(key) { this.entries.delete(key); }
                      clear() { this.entries.clear(); }
                    }
                    global.Storage = Storage;
                    global.window = new EventTarget();
                    global.document = new EventTarget();
                    document.visibilityState = 'visible';
                    global.localStorage = window.localStorage = new Storage();
                    window.location = { origin: 'http://127.0.0.1:4096' };
                    const timers = new Map();
                    let timerID = 0;
                    window.setTimeout = callback => { timers.set(++timerID, callback); return timerID; };
                    window.clearTimeout = id => timers.delete(id);
                    const flushTimers = () => { const pending = [...timers.values()]; timers.clear(); pending.forEach(fn => fn()); };
                    """
                        .trimIndent() +
                    "\n" +
                    scenario.trimIndent(),
            )
            val output = directory.resolve("output.txt").toFile()
            val process =
                ProcessBuilder(node!!.absolutePath, source.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(output)
                    .start()
            try {
                assertTrue("JavaScript timed out", process.waitFor(15, TimeUnit.SECONDS))
                assertEquals(output.readText(), 0, process.exitValue())
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
