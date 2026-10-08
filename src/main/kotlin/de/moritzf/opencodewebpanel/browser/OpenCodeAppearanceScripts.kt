package de.moritzf.opencodewebpanel.browser

import org.intellij.lang.annotations.Language

internal object OpenCodeAppearanceScripts {
    fun awtCursorTypeForCss(cssCursor: String?): Int {
        val keyword =
            cssCursor
                ?.split(',')
                ?.map { it.trim().lowercase() }
                ?.lastOrNull { it.isNotBlank() && !it.startsWith("url(") }
                ?: return java.awt.Cursor.DEFAULT_CURSOR
        return when (keyword) {
            "pointer" -> java.awt.Cursor.HAND_CURSOR
            "text",
            "vertical-text" -> java.awt.Cursor.TEXT_CURSOR
            "wait",
            "progress" -> java.awt.Cursor.WAIT_CURSOR
            "crosshair",
            "cell" -> java.awt.Cursor.CROSSHAIR_CURSOR
            "move",
            "grab",
            "grabbing",
            "all-scroll" -> java.awt.Cursor.MOVE_CURSOR
            "n-resize" -> java.awt.Cursor.N_RESIZE_CURSOR
            "s-resize",
            "ns-resize",
            "row-resize" -> java.awt.Cursor.S_RESIZE_CURSOR
            "e-resize" -> java.awt.Cursor.E_RESIZE_CURSOR
            "w-resize",
            "ew-resize",
            "col-resize" -> java.awt.Cursor.W_RESIZE_CURSOR
            "ne-resize",
            "nesw-resize" -> java.awt.Cursor.NE_RESIZE_CURSOR
            "nw-resize",
            "nwse-resize" -> java.awt.Cursor.NW_RESIZE_CURSOR
            "se-resize" -> java.awt.Cursor.SE_RESIZE_CURSOR
            "sw-resize" -> java.awt.Cursor.SW_RESIZE_CURSOR
            else -> java.awt.Cursor.DEFAULT_CURSOR
        }
    }

    fun buildCompactLayoutScript(enabled: Boolean): String? {
        return buildMatchMediaPatchScript(compact = enabled, theme = false, dark = false)
    }

    fun buildCompactHomeLayoutScript(enabled: Boolean): String? {
        if (!enabled) return null
        @Language("JavaScript")
        val script =
            """
            (() => {
              if (window.__opencodeIntellijCompactHomeLayoutInstalled) return;
              window.__opencodeIntellijCompactHomeLayoutInstalled = true;
              const STYLE_ID = 'opencode-intellij-compact-home-layout';
              // V1 and native desktop Home retain an aside; only widen the sidebar-free compact grid.
              const CSS = 'div:has(> section [data-component="home-session-search"]):not(:has(> aside))' +
                ' { max-width: none !important; grid-template-columns: minmax(0, 1fr) !important; }';
              const ensureStyle = () => {
                const parent = document.head || document.documentElement;
                if (!parent) return;
                let style = document.getElementById(STYLE_ID);
                if (!style) {
                  style = document.createElement('style');
                  style.id = STYLE_ID;
                  style.textContent = CSS;
                }
                if (!style.isConnected) parent.appendChild(style);
              };
              ensureStyle();
              // CSS handles route changes and resizing. Only repair a stylesheet removed with <head>.
              let ensureQueued = false;
              const observer = new MutationObserver(() => {
                if (ensureQueued) return;
                ensureQueued = true;
                window.requestAnimationFrame(() => {
                  ensureQueued = false;
                  ensureStyle();
                });
              });
              observer.observe(document.documentElement || document, { childList: true, subtree: true });
              document.addEventListener('DOMContentLoaded', ensureStyle, { once: true });
            })();
        """
        return script.trimIndent()
    }

    fun buildMatchMediaPatchScript(compact: Boolean, theme: Boolean, dark: Boolean): String? {
        if (!compact && !theme) return null
        val compactLiteral = compact.toString()
        val themeLiteral = theme.toString()
        val darkLiteral = dark.toString()
        @Language("JavaScript")
        val script =
            """
            (() => {
              const compact = $compactLiteral;
              const theme = $themeLiteral;
              const dark = $darkLiteral;
              const WIDE_KEY = '(min-width:768px)';
              const NARROW_KEY = '(max-width:767px)';
              const THEME_KEY = '(prefers-color-scheme:dark)';
              const THEME_MEDIA = '(prefers-color-scheme: dark)';
              const keyOf = (q) => String(q || '').replace(/\s+/g, '').toLowerCase();
              if (window.__opencodeIntellijMatchMediaInstalled && theme && window.__opencodeIntellijThemeMql) {
                if (window.__opencodeIntellijThemeDark !== dark) {
                  window.__opencodeIntellijThemeDark = dark;
                  window.__opencodeIntellijThemeMql.matches = dark;
                  window.__opencodeIntellijThemeMql.dispatchEvent(new MediaQueryListEvent('change', { matches: dark, media: THEME_MEDIA }));
                }
                return;
              }
              window.__opencodeIntellijMatchMediaInstalled = true;
              window.__opencodeIntellijCompactInstalled = compact;
              window.__opencodeIntellijThemeInstalled = theme;
              const orig = window.__opencodeIntellijOrigMatchMedia || window.matchMedia.bind(window);
              window.__opencodeIntellijOrigMatchMedia = orig;
              const stub = (media, matches) => ({ matches, media, onchange: null, addEventListener: () => {}, removeEventListener: () => {}, addListener: () => {}, removeListener: () => {}, dispatchEvent: () => false });
              let themeMql = null;
              if (theme) {
                window.__opencodeIntellijThemeDark = dark;
                themeMql = {
                  matches: dark,
                  media: THEME_MEDIA,
                  onchange: null,
                  __listeners: new Set(),
                  addEventListener(type, listener) { if (type === 'change' && typeof listener === 'function') this.__listeners.add(listener); },
                  removeEventListener(type, listener) { if (type === 'change') this.__listeners.delete(listener); },
                  addListener(listener) { if (typeof listener === 'function') this.__listeners.add(listener); },
                  removeListener(listener) { this.__listeners.delete(listener); },
                  dispatchEvent(event) {
                    if (typeof this.onchange === 'function') this.onchange.call(this, event);
                    for (const listener of this.__listeners) { try { listener.call(this, event); } catch (_) {} }
                    return true;
                  },
                };
                window.__opencodeIntellijThemeMql = themeMql;
              }
              window.matchMedia = (q) => {
                const key = keyOf(q);
                if (compact && key === WIDE_KEY) return stub(q, false);
                if (compact && key === NARROW_KEY) return stub(q, true);
                if (theme && key === THEME_KEY) return themeMql;
                return orig(q);
              };
            })();
        """
        return script.trimIndent()
    }

    fun isInPlaceDialogRepaintEvent(type: String): Boolean {
        return type == "permission.asked" ||
            type == "permission.replied" ||
            type == "question.asked" ||
            type == "question.replied" ||
            type == "question.rejected"
    }

    fun buildViewportRasterNudgeScript(): String {
        @Language("JavaScript")
        val script =
            """
            (() => {
              const nudge = () => {
                const root = document.documentElement;
                if (!root) return;
                const style = root.style;
                const previous = style.getPropertyValue('transform');
                style.setProperty('transform', 'translate(1px, 0)');
                void root.offsetHeight;
                if (previous) style.setProperty('transform', previous);
                else style.removeProperty('transform');
                window.dispatchEvent(new Event('resize'));
              };
              requestAnimationFrame(() => requestAnimationFrame(nudge));
            })();
        """
        return script.trimIndent()
    }

    fun buildHideWebsiteButtonScript(enabled: Boolean): String? {
        if (!enabled) return null
        @Language("JavaScript")
        val script =
            """
            (() => {
              if (window.__opencodeIntellijHideWebsiteButtonInstalled) return;
              window.__opencodeIntellijHideWebsiteButtonInstalled = true;
              const STYLE_ID = 'opencode-intellij-hide-website-button';
              // Prefer href + durable chrome role over locale labels and Tailwind layout classes.
              const SELECTOR = [
                'a[href^="https://opencode.ai"][data-component*="icon-button"]',
                'a[href^="http://opencode.ai"][data-component*="icon-button"]',
              ].join(', ');
              const CSS = SELECTOR + ' { display: none !important; visibility: hidden !important; pointer-events: none !important; }';
              const ensureStyle = () => {
                const parent = document.head || document.documentElement;
                if (!parent) return false;
                let style = document.getElementById(STYLE_ID);
                if (!style) {
                  style = document.createElement('style');
                  style.id = STYLE_ID;
                  style.textContent = CSS;
                }
                if (!style.isConnected) parent.appendChild(style);
                else if (style.textContent !== CSS) style.textContent = CSS;
                return true;
              };
              // The stylesheet alone does the hiding; the observer only re-attaches it if the SPA
              // replaces <head>. Reattachment checks are debounced to one per animation frame so
              // chat streaming (which mutates the DOM constantly) costs nothing measurable.
              ensureStyle();
              let ensureQueued = false;
              const queueEnsureStyle = () => {
                if (ensureQueued) return;
                ensureQueued = true;
                window.requestAnimationFrame(() => {
                  ensureQueued = false;
                  ensureStyle();
                });
              };
              const observer = new MutationObserver(queueEnsureStyle);
              const root = document.documentElement || document;
              observer.observe(root, { childList: true, subtree: true });
              document.addEventListener('DOMContentLoaded', ensureStyle, { once: true });
            })();
        """
        return script.trimIndent()
    }

    fun buildIdeThemeSyncScript(enabled: Boolean, dark: Boolean): String? {
        return buildMatchMediaPatchScript(compact = false, theme = enabled, dark = dark)
    }

    fun buildCursorMirrorScript(enabled: Boolean, cursorCallback: String?): String? {
        if (!enabled || cursorCallback == null) return null
        @Language("JavaScript")
        val script =
            $$"""
            (() => {
              if (window.__opencodeIntellijCursorMirrorInstalled) return;
              window.__opencodeIntellijCursorMirrorInstalled = true;
              let lastSent = '';
              let lastX = -1;
              let lastY = -1;
              const send = (cursor) => {
                if (!cursor || cursor === lastSent) return;
                lastSent = cursor;
                try {
                  const payload = cursor;
                  $${cursorCallback};
                } catch (_) {}
              };
              const effectiveCursor = (x, y, target) => {
                const el = target && target.nodeType === 1 ? target : (target && target.parentElement);
                if (!el) return 'default';
                const cursor = getComputedStyle(el).cursor;
                if (cursor !== 'auto') return cursor;
                if (el.isContentEditable) return 'text';
                const tag = el.tagName;
                if (tag === 'TEXTAREA') return 'text';
                if (tag === 'INPUT' && !/^(button|submit|reset|checkbox|radio|range|file|color|image)$/i.test(el.type)) return 'text';
                // Over selectable text, auto renders as the text I-beam. Point-to-caret APIs
                // snap to the nearest text, so require the point to be inside its element.
                if (document.caretPositionFromPoint || document.caretRangeFromPoint) {
                  const caret = document.caretPositionFromPoint
                    ? document.caretPositionFromPoint(x, y)
                    : document.caretRangeFromPoint(x, y);
                  const node = caret && (caret.offsetNode || caret.startContainer);
                  if (node && node.nodeType === 3 && node.parentElement) {
                    const rect = node.parentElement.getBoundingClientRect();
                    if (x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom) return 'text';
                  }
                }
                return 'default';
              };
              const recomputeAtPointer = () => {
                if (lastX < 0) return;
                const el = document.elementFromPoint(lastX, lastY);
                if (el) send(effectiveCursor(lastX, lastY, el));
              };
              document.addEventListener('pointermove', (event) => {
                lastX = event.clientX;
                lastY = event.clientY;
                if (event.buttons !== 0) return;
                send(effectiveCursor(event.clientX, event.clientY, event.target));
              }, true);
              document.addEventListener('pointerdown', (event) => {
                send(effectiveCursor(event.clientX, event.clientY, event.target));
              }, true);
              document.addEventListener('pointerup', (event) => {
                const el = document.elementFromPoint(event.clientX, event.clientY);
                send(effectiveCursor(event.clientX, event.clientY, el || event.target));
              }, true);
              document.addEventListener('mouseout', (event) => {
                if (!event.relatedTarget) send('default');
              }, true);
              // Content moving under a stationary pointer also changes the cursor in a browser.
              let scrollRecomputeQueued = false;
              window.addEventListener('scroll', () => {
                if (scrollRecomputeQueued) return;
                scrollRecomputeQueued = true;
                window.requestAnimationFrame(() => {
                  scrollRecomputeQueued = false;
                  recomputeAtPointer();
                });
              }, true);
            })();
        """
        return script.trimIndent()
    }

    fun buildProjectSwitchPromptSuppressionScript(enabled: Boolean): String? {
        if (!enabled) return null
        @Language("JavaScript")
        val script =
            """
            (() => {
              if (window.__opencodeIntellijProjectSwitchPromptSuppressionInstalled) return;
              window.__opencodeIntellijProjectSwitchPromptSuppressionInstalled = true;
              // Structural match, no locale-dependent label text: the permission/question toast is
              // the only toast rendered with the "checklist" (permission) or "bubble-5" (question)
              // sprite icon, and it always carries an action row (go-to-session/dismiss buttons).
              // The Icon component renders the sprite name in the DOM as use[href="#opencode-icon-…"],
              // in both the legacy toast and the redesigned toast-v2.
              const toastSelector = '[data-component="toast"], [data-component="toast-v2"]';
              const iconSlotSelector = '[data-slot="toast-icon"], [data-slot="toast-v2-icon"]';
              // Cover both icon sprites: toasts currently use the v1 sprite even inside v2 toast
              // chrome; if icons migrate to the v2 sprite the same names keep matching.
              const promptIconSelector = [
                'use[href="#opencode-icon-checklist"]',
                'use[href="#opencode-icon-bubble-5"]',
                'use[href="#opencode-v2-icon-checklist"]',
                'use[href="#opencode-v2-icon-bubble-5"]',
              ].join(', ');
              const actionSelector = '[data-slot="toast-action"], [data-slot="toast-v2-actions"] button';
              const closeSelector = '[data-slot="toast-close-button"], [data-slot="toast-v2-close-button"]';
              // Bound auto-dismissals per page load: this feature acts on the user's behalf, so a
              // matcher gone wrong after an OpenCode redesign must not silently eat an unbounded
              // stream of toasts. Legit use dismisses a handful per load; hitting the cap disables
              // suppression for this page load and logs once.
              const MAX_DISMISSALS = 20;
              let dismissals = 0;
              let observer = null;
              const dismissIfProjectSwitchPrompt = (toast) => {
                const iconSlot = toast.querySelector(iconSlotSelector);
                if (!iconSlot || !iconSlot.querySelector(promptIconSelector)) return;
                if (!toast.querySelector(actionSelector)) return;
                if (dismissals >= MAX_DISMISSALS) {
                  if (observer) {
                    observer.disconnect();
                    observer = null;
                    if (window.console && window.console.warn) {
                      window.console.warn('OpenCode toast suppression cap reached; disabled until the next page load');
                    }
                  }
                  return;
                }
                dismissals += 1;
                const close = toast.querySelector(closeSelector);
                if (close && typeof close.click === 'function') {
                  close.click();
                } else {
                  toast.remove();
                }
              };
              const scan = (root) => {
                if (!root || root.nodeType !== Node.ELEMENT_NODE) return;
                if (root.matches && root.matches(toastSelector)) dismissIfProjectSwitchPrompt(root);
                if (root.querySelectorAll) root.querySelectorAll(toastSelector).forEach(dismissIfProjectSwitchPrompt);
              };
              const install = () => {
                const target = document.body || document.documentElement;
                if (!target) return;
                observer = new MutationObserver((mutations) => {
                  for (const mutation of mutations) {
                    mutation.addedNodes.forEach(scan);
                  }
                });
                observer.observe(target, { childList: true, subtree: true });
                scan(target);
              };
              if (document.readyState === 'loading') {
                document.addEventListener('DOMContentLoaded', install, { once: true });
              } else {
                install();
              }
            })();
        """
        return script.trimIndent()
    }
}
