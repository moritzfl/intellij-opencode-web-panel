package de.moritzf.opencodewebpanel.browser

import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import org.intellij.lang.annotations.Language

/** Shared literal fragments and escaping for feature-specific script builders. */
internal object OpenCodeBrowserScriptSupport {

    /**
     * How long the embedded page's event stream may stay byte-silent before it is treated as dead.
     * OpenCode emits `server.heartbeat` every 10 seconds, so this allows three to four missed beats
     * — the same budget the JVM-side reader uses for its read timeout.
     */
    const val EVENT_STREAM_STALL_TIMEOUT_MILLIS = 45_000

    /** Page-side heartbeat cadence; the JVM watchdog treats several missed beats as a stall. */
    const val RENDERER_HEARTBEAT_INTERVAL_MILLIS = 5_000

    /** OpenCode session-tab popover (`titlebar-tab-popover` `OPEN_DELAY`). */
    const val OPENCODE_TAB_POPOVER_OPEN_DELAY_MILLIS = 2_000

    /** Panel hover delay for that popover and the injected project-row path preview. */
    const val PATH_HOVER_PREVIEW_DELAY_MILLIS = 250

    /**
     * Floor that keeps a misconfigured timeout from reconnect-looping through normal heartbeats.
     */
    const val MIN_EVENT_STREAM_STALL_TIMEOUT_MILLIS = 15_000

    /**
     * Decodes a base64url route segment back to the project directory. Shared by the builders
     * below.
     */
    @Language("JavaScript")
    val DECODE_ROUTE_DIRECTORY_JS =
        """
        const decodeRouteDirectory = (value) => {
          try {
            const base64 = value.replace(/-/g, '+').replace(/_/g, '/');
            const padded = base64 + '='.repeat((4 - base64.length % 4) % 4);
            const binary = atob(padded);
            const bytes = new Uint8Array(binary.length);
            for (let index = 0; index < binary.length; index += 1) {
              bytes[index] = binary.charCodeAt(index);
            }
            return new TextDecoder().decode(bytes);
          } catch (_) {
            return '';
          }
        };
        """
            .trimIndent()

    /** Initial scan once; MutationObservers pass only added subtrees thereafter. */
    @Language("JavaScript")
    val VISIT_MATCHING_ELEMENTS_JS =
        """
        const visitMatchingElements = (root, selector, visit) => {
          if (!root || (root.nodeType !== 1 && root.nodeType !== 9)) return;
          if (root.nodeType === 1 && root.matches(selector)) visit(root);
          root.querySelectorAll(selector).forEach(visit);
        };
        """
            .trimIndent()

    /**
     * Hovered interactive elements get the pointer cursor; the cursor mirror reads computed styles,
     * so the embedded panel cursor follows automatically. Callers wire their own mouseover listener
     * that calls `markHovered(elementOrNull)`.
     */
    @Language("JavaScript")
    val POINTER_CURSOR_KIT_JS =
        """
        const POINTER_ATTR = 'data-opencode-intellij-pointer';
        const POINTER_STYLE_ID = 'opencode-intellij-pointer-cursor';
        const POINTER_CSS = '[' + POINTER_ATTR + '], [' + POINTER_ATTR + '] * { cursor: pointer !important; }';
        const ensurePointerCursorStyle = () => {
          const parent = document.head || document.documentElement;
          if (!parent) return;
          let style = document.getElementById(POINTER_STYLE_ID);
          if (!style) {
            style = document.createElement('style');
            style.id = POINTER_STYLE_ID;
          }
          if (style.textContent !== POINTER_CSS) style.textContent = POINTER_CSS;
          if (!style.isConnected) parent.appendChild(style);
        };
        let hoveredElement = null;
        const markHovered = (element) => {
          if (element === hoveredElement) return;
          if (hoveredElement) hoveredElement.removeAttribute(POINTER_ATTR);
          hoveredElement = element;
          if (hoveredElement) {
            ensurePointerCursorStyle();
            hoveredElement.setAttribute(POINTER_ATTR, '');
          }
        };
        document.addEventListener('mouseout', (event) => {
          if (!event.relatedTarget) markHovered(null);
        }, true);
        """
            .trimIndent()

    /**
     * OpenCode localStorage keys mirrored into the IDE-side settings store.
     *
     * Isolated serve processes (one per IDE project) do not share sessions, so only user
     * settings/prefs are restored: `settings.v3`, theme, language, and model favorites. Tabs,
     * layout, home.servers, workspace, and notification lists stay with the live origin.
     */
    @Language("JavaScript")
    val PERSISTED_STORAGE_KEY_FILTER_JS =
        $$"""
        const exactKeys = new Set([
          '$${OpenCodeServerProtocol.OPEN_CODE_THEME_ID_STORAGE_KEY}',
          '$${OpenCodeServerProtocol.OPEN_CODE_COLOR_SCHEME_STORAGE_KEY}',
          'opencode-theme-css-light',
          'opencode-theme-css-dark',
          'settings.v3',
        ]);
        const globalKeys = /^opencode\.global\.dat:(language|model)$/;
        const shouldPersistKey = (key) => typeof key === 'string' && (exactKeys.has(key) || globalKeys.test(key));
        // Settings values should not embed server origins; keep the rewrite so a
        // leftover loopback URL in an older snapshot cannot pin a dead port.
        const LOOPBACK_ORIGIN_RE = /^https?:\/\/(?:127\.0\.0\.1|localhost)(?::\d+)?$/i;
        const LOOPBACK_ORIGIN_IN_TEXT_RE = /https?:\/\/(?:127\.0\.0\.1|localhost)(?::\d+)?/gi;
        const encodeServerKey = (value) => {
          const bytes = new TextEncoder().encode(value);
          let binary = '';
          bytes.forEach((byte) => binary += String.fromCharCode(byte));
          return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
        };
        const decodeServerKey = (value) => {
          try {
            const base64 = value.replace(/-/g, '+').replace(/_/g, '/');
            const padded = base64 + '='.repeat((4 - base64.length % 4) % 4);
            const binary = atob(padded);
            const bytes = new Uint8Array(binary.length);
            for (let index = 0; index < binary.length; index += 1) {
              bytes[index] = binary.charCodeAt(index);
            }
            return new TextDecoder().decode(bytes);
          } catch (_) {
            return '';
          }
        };
        const rewriteLoopbackServerRefs = (text) => {
          if (typeof text !== 'string' || !text) return text;
          const origin = window.location.origin;
          if (!LOOPBACK_ORIGIN_RE.test(origin)) return text;
          let next = text.replace(LOOPBACK_ORIGIN_IN_TEXT_RE, origin);
          const currentKey = encodeServerKey(origin);
          next = next.replace(/[A-Za-z0-9_-]{16,64}/g, (token) => {
            const decoded = decodeServerKey(token);
            if (!decoded || !LOOPBACK_ORIGIN_RE.test(decoded)) return token;
            return currentKey;
          });
          return next;
        };
    """
            .trimIndent()

    fun escapeJavaScript(value: String): String {
        val builder = StringBuilder(value.length + 8)
        for (char in value) {
            when (char) {
                '\\' -> builder.append("\\\\")
                '\'' -> builder.append("\\'")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                // Escape '<' so an interpolated value can never break out of an inline <script>
                // context.
                '<' -> builder.append("\\u003C")
                // U+2028/U+2029 are valid line terminators inside JS string literals and must be
                // escaped.
                '\u2028' -> builder.append("\\u2028")
                '\u2029' -> builder.append("\\u2029")
                else ->
                    if (char.code < 0x20) {
                        builder.append("\\u").append(char.code.toString(16).padStart(4, '0'))
                    } else {
                        builder.append(char)
                    }
            }
        }
        return builder.toString()
    }
}
