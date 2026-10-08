package de.moritzf.opencodewebpanel.browser

import de.moritzf.opencodewebpanel.browser.OpenCodeBrowserScriptSupport.DECODE_ROUTE_DIRECTORY_JS
import de.moritzf.opencodewebpanel.browser.OpenCodeBrowserScriptSupport.POINTER_CURSOR_KIT_JS
import de.moritzf.opencodewebpanel.browser.OpenCodeBrowserScriptSupport.VISIT_MATCHING_ELEMENTS_JS
import de.moritzf.opencodewebpanel.browser.OpenCodeBrowserScriptSupport.escapeJavaScript
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import org.intellij.lang.annotations.Language

internal object OpenCodeNavigationScripts {
    fun buildShortcutDispatchScript(
        newLayoutKeybinds: List<String>,
        classicKeybinds: List<String>,
    ): String? {
        if (newLayoutKeybinds.isEmpty() && classicKeybinds.isEmpty()) return null
        val newLayout =
            newLayoutKeybinds.joinToString(", ", prefix = "[", postfix = "]") {
                "'${escapeJavaScript(it)}'"
            }
        val classic =
            classicKeybinds.joinToString(", ", prefix = "[", postfix = "]") {
                "'${escapeJavaScript(it)}'"
            }
        val configs =
            if (newLayoutKeybinds == classicKeybinds) {
                newLayout
            } else {
                "document.querySelector('[data-slot=\"titlebar-v2\"]') ? $newLayout : $classic"
            }
        @Language("JavaScript")
        val script =
            $$"""
            (() => {
              const configs = $${configs};
              const isMac = /(Mac|iPod|iPhone|iPad)/.test(navigator.platform);
              const namedKeys = {
                comma: ',', plus: '+', space: ' ', escape: 'Escape', esc: 'Escape',
                enter: 'Enter', return: 'Enter', tab: 'Tab', backspace: 'Backspace',
                delete: 'Delete', insert: 'Insert', home: 'Home', end: 'End',
                pageup: 'PageUp', pagedown: 'PageDown', arrowup: 'ArrowUp',
                arrowdown: 'ArrowDown', arrowleft: 'ArrowLeft', arrowright: 'ArrowRight'
              };
              const codeFor = (key) => {
                if (/^[a-z]$/.test(key)) return 'Key' + key.toUpperCase();
                if (/^[0-9]$/.test(key)) return 'Digit' + key;
                if (/^F(?:[1-9]|1[0-9]|2[0-4])$/.test(key)) return key;
                return ({
                  "'": 'Quote', '.': 'Period', ',': 'Comma', ';': 'Semicolon',
                  '/': 'Slash', '\\': 'Backslash', '-': 'Minus', '=': 'Equal',
                  '+': 'NumpadAdd', ' ': 'Space', Escape: 'Escape'
                })[key] || key;
              };
              const parse = (chord) => {
                const binding = { key: '', ctrlKey: false, metaKey: false, altKey: false, shiftKey: false };
                for (const part of chord.trim().toLowerCase().split('+').filter(Boolean)) {
                  if (part === 'mod') {
                    if (isMac) binding.metaKey = true;
                    else binding.ctrlKey = true;
                  } else if (part === 'meta' || part === 'cmd' || part === 'command') {
                    binding.metaKey = true;
                  } else if (part === 'ctrl' || part === 'control') {
                    binding.ctrlKey = true;
                  } else if (part === 'alt' || part === 'option') {
                    binding.altKey = true;
                  } else if (part === 'shift') {
                    binding.shiftKey = true;
                  } else if (!binding.key) {
                    binding.key = namedKeys[part] || (/^f[0-9]+$/.test(part) ? part.toUpperCase() : part);
                  } else {
                    return null;
                  }
                }
                if (!binding.key) return null;
                return { ...binding, code: codeFor(binding.key) };
              };
              const target = document.activeElement instanceof Element ? document.activeElement : document;
              for (const config of configs) {
                for (const chord of config.split(',')) {
                  const binding = parse(chord);
                  if (!binding) continue;
                  const event = new KeyboardEvent('keydown', {
                    ...binding,
                    bubbles: true,
                    cancelable: true,
                    composed: true
                  });
                  target.dispatchEvent(event);
                  if (event.defaultPrevented) return;
                }
              }
            })();
        """
        return script.trimIndent()
    }

    fun buildExternalLinkHandlerScript(enabled: Boolean, openExternalCallback: String?): String? {
        if (!enabled || openExternalCallback == null) return null
        @Language("JavaScript")
        val script =
            """
            (() => {
              if (window.__opencodeIntellijExternalLinksInstalled) return;
              window.__opencodeIntellijExternalLinksInstalled = true;
              const externalHttpUrl = (rawHref, baseHref) => {
                if (!rawHref || rawHref.trim().startsWith('#')) return '';
                let url;
                try {
                  url = new URL(rawHref, baseHref || window.location.href);
                } catch (_) {
                  return '';
                }
                if (url.protocol !== 'http:' && url.protocol !== 'https:') return '';
                if (url.origin === window.location.origin) return '';
                return url.href;
              };
              const openExternal = (href) => {
                try {
                  $openExternalCallback;
                } catch (error) {
                  if (window.console && window.console.warn) {
                    window.console.warn('Failed to forward external link to IntelliJ', error);
                  }
                }
              };
              const nativeWindowOpen = window.open;
              window.__opencodeIntellijNativeWindowOpen = nativeWindowOpen;
              window.open = function(url, target, features) {
                const href = externalHttpUrl(typeof url === 'string' ? url : String(url == null ? '' : url));
                if (href) {
                  openExternal(href);
                  return null;
                }
                return nativeWindowOpen ? nativeWindowOpen.apply(window, arguments) : null;
              };
              document.addEventListener('click', (event) => {
                if (event.defaultPrevented) return;
                const link = event.target && event.target.closest ? event.target.closest('a') : null;
                if (!link) return;
                const href = externalHttpUrl(link.getAttribute('href'), link.href);
                if (!href) return;
                event.preventDefault();
                event.stopImmediatePropagation();
                openExternal(href);
              }, true);
            })();
        """
        return script.trimIndent()
    }

    fun buildCodeNavigationScript(enabled: Boolean, openCodeCallback: String?): String? {
        if (!enabled || openCodeCallback == null) return null
        @Language("JavaScript")
        val script =
            $$"""
            (() => {
              if (window.__opencodeIntellijCodeNavInstalled) return;
              window.__opencodeIntellijCodeNavInstalled = true;
              const hasExtension = /\.[a-zA-Z][a-zA-Z0-9]{0,8}(?::L?\d+(?:-L?\d+)?|:\d+:\d+|#L?\d+(?:-L?\d+)?|\(L?\d+(?:\s*,\s*\d+)?\))?$/i;
              const hasPathLocator = /[\\/].*(?::L?\d+(?:-L?\d+)?|:\d+:\d+|#L?\d+(?:-L?\d+)?|\(L?\d+(?:\s*,\s*\d+)?\))$/i;
              const fileLocWithDir = /(?:[A-Za-z]:)?(?:[^\s<>"'`()]+[\/\\])+[^\s\/\\():]+\.[A-Za-z][A-Za-z0-9]{0,8}(?::L?\d+(?:-L?\d+)?|:\d+:\d+|#L?\d+(?:-L?\d+)?|\(L?\d+(?:\s*,\s*\d+)?\))?/i;
              const fileLocBare = /[^\s\/\\():]+\.[A-Za-z][A-Za-z0-9]{0,8}(?::L?\d+(?:-L?\d+)?|:\d+:\d+|#L?\d+(?:-L?\d+)?|\(L?\d+(?:\s*,\s*\d+)?\))/i;
              const locatorAtStart = /^\s*(:L?\d+(?:-L?\d+)?|:\d+:\d+|#L?\d+(?:-L?\d+)?|\(L?\d+(?:\s*,\s*\d+)?\))/i;
              // A bare filename in prose has no locator. Restrict it to known extensions so
              // dotted identifiers and domains do not steal clicks.
              const bareFileName = /^[^\s\/\\().:]+\.(?:kt|kts|java|ts|tsx|js|jsx|py|xml|xsd|xsl|xslt|wsdl|yml|yaml|json|md|sql|properties|gradle)(?=$|[),;!?])/i;
              const isUrl = /^[a-z][a-z0-9+.-]*:\/\//i;
              const isPascalCase = /^[A-Z][a-zA-Z0-9_]*$/;
              const isQualifiedClass = /^(?:[a-zA-Z_][a-zA-Z0-9_]*\.)+[A-Z][a-zA-Z0-9_]*$/;
              const isTypeMember = /^(?:[a-zA-Z_][a-zA-Z0-9_]*\.)*[A-Z][a-zA-Z0-9_]*(?:[.#][a-z_][a-zA-Z0-9_]*)?\(.*\)$/;
              const isTypeMemberBare = /^(?:[a-zA-Z_][a-zA-Z0-9_]*\.)*[A-Z][a-zA-Z0-9_]*[.#][a-z_][a-zA-Z0-9_]*$/;
              const fileExt = /\.(kt|kts|java|ts|tsx|js|jsx|mjs|cjs|py|rb|go|rs|c|h|cc|cpp|hpp|cs|swift|md|json|yml|yaml|xml|xsd|xsl|xslt|wsdl|toml|txt)$/i;
              const isSnakeCase = /^[a-z][a-z0-9]*_[a-z0-9_]+$/;
              const looksLikeCodeRef = (text) => {
                const t = text.trim();
                if (t.length < 2 || t.length > 512) return false;
                if (t.includes('\n')) return false;
                if (isUrl.test(t)) return false;
                if (isTypeMember.test(t)) return true;
                if (isTypeMemberBare.test(t) && !fileExt.test(t)) return true;
                if (hasExtension.test(t)) return true;
                if (t.includes(' ')) return false;
                if (hasPathLocator.test(t)) return true;
                if (isPascalCase.test(t)) return true;
                if (isQualifiedClass.test(t)) return true;
                if (isSnakeCase.test(t)) return true;
                return false;
              };
              const adjacentLocator = (el) => {
                let node = el.nextSibling;
                while (node && node.nodeType === Node.TEXT_NODE && !/\S/.test(node.textContent || '')) {
                  node = node.nextSibling;
                }
                if (!node || node.nodeType !== Node.TEXT_NODE) return '';
                const match = locatorAtStart.exec(node.textContent || '');
                return match ? match[1] : '';
              };
              const withAdjacentLocator = (text, el) => {
                if (!text) return '';
                const loc = adjacentLocator(el);
                return loc ? text + loc : text;
              };
              const extractRef = (codeEl) => {
                if (!codeEl.closest('[data-component="markdown"]')) return '';
                if (codeEl.closest('pre') || codeEl.closest('a')) return '';
                const text = (codeEl.textContent || '').trim();
                const kind = codeEl.getAttribute('data-inline-code-kind');
                if (kind === 'url') return '';
                if (kind === 'path') {
                  return text.length > 0 && text.length <= 512 ? withAdjacentLocator(text, codeEl) : '';
                }
                if (!looksLikeCodeRef(text)) return '';
                const parent = codeEl.parentElement;
                if (!parent) return withAdjacentLocator(text, codeEl);
                const path = parent.getAttribute('data-path') || parent.getAttribute('data-file');
                if (path) return withAdjacentLocator(path, codeEl);
                return withAdjacentLocator(text, codeEl);
              };
              const inOutput = (el) => !!(el && el.closest && el.closest('pre, [data-slot="bash-pre"], [data-component="tool-output"], [data-component="tool-loaded-file"]'));
              const skipEmptyText = (node) => {
                let current = node;
                while (current && current.nodeType === Node.TEXT_NODE && !/\S/.test(current.textContent || '')) {
                  current = current.previousSibling;
                }
                return current;
              };
              const codeBesideLocator = (event) => {
                try {
                  const caret = document.caretRangeFromPoint
                    ? document.caretRangeFromPoint(event.clientX, event.clientY)
                    : null;
                  const node = caret && caret.startContainer;
                  if (!node || node.nodeType !== Node.TEXT_NODE) return null;
                  const prev = skipEmptyText(node.previousSibling);
                  if (!prev || prev.nodeName !== 'CODE') return null;
                  const loc = adjacentLocator(prev);
                  if (!loc || !extractRef(prev)) return null;
                  const offset = Math.min(caret.startOffset, (node.textContent || '').length);
                  const lead = (/^\s*/.exec(node.textContent || '') || [''])[0].length;
                  if (offset < lead || offset > lead + loc.length + 1) return null;
                  return prev;
                } catch (_) {
                  return null;
                }
              };
              const fileLocAtEvent = (event) => {
                const selection = window.getSelection && window.getSelection();
                if (selection && !selection.isCollapsed) return '';
                const caret = document.caretRangeFromPoint
                  ? document.caretRangeFromPoint(event.clientX, event.clientY)
                  : null;
                const node = caret && caret.startContainer;
                if (!node || node.nodeType !== Node.TEXT_NODE) return '';
                const parent = node.parentElement;
                if (!parent || parent.closest('a, [contenteditable="true"]')) return '';
                const output = inOutput(parent);
                if (!output && !parent.closest('[data-component="markdown"]')) return '';
                if (!output && parent.closest('code')) return '';
                const text = node.textContent || '';
                const offset = Math.min(caret.startOffset, text.length);
                const py = /File\s+"([^"\n]+)",\s+line\s+(\d+)/ig;
                let python;
                while ((python = py.exec(text))) {
                  if (offset >= python.index && offset <= python.index + python[0].length) {
                    const path = python[1];
                    if (path && !isUrl.test(path)) return path + ':' + python[2];
                  }
                }
                const isBreak = (ch) => /[\s<>"'`]/.test(ch);
                let start = offset;
                let end = offset;
                while (start > 0 && !isBreak(text[start - 1])) start -= 1;
                while (end < text.length && !isBreak(text[end])) end += 1;
                const token = text.slice(start, end).replace(/:+$/, '');
                if (isUrl.test(token)) return '';
                const glueAfter = () => {
                  const rest = text.slice(end).replace(/^[\s<>"'`]+/, '');
                  const glued = locatorAtStart.exec(rest);
                  return glued ? token + glued[1] : token;
                };
                const glueBefore = () => {
                  let prevEnd = start;
                  while (prevEnd > 0 && isBreak(text[prevEnd - 1])) prevEnd -= 1;
                  let prevStart = prevEnd;
                  while (prevStart > 0 && !isBreak(text[prevStart - 1])) prevStart -= 1;
                  return text.slice(prevStart, prevEnd).replace(/:+$/, '') + token;
                };
                const pick = (value) => {
                  const cleaned = (value || '').replace(/:+$/, '');
                  return (fileLocWithDir.exec(cleaned) || fileLocBare.exec(cleaned) || bareFileName.exec(cleaned) || [])[0] || '';
                };
                const candidate = pick(token) ? token : glueAfter();
                return pick(candidate) || pick(glueBefore());
              };
              document.addEventListener('click', (event) => {
                if (event.defaultPrevented) return;
                const code = (event.target && event.target.closest && event.target.closest('code')) || codeBesideLocator(event);
                const ref = (!inOutput(event.target) && code && extractRef(code)) || fileLocAtEvent(event);
                if (!ref) return;
                event.preventDefault();
                event.stopImmediatePropagation();
                $${openCodeCallback};
              }, true);
              $$POINTER_CURSOR_KIT_JS
              document.addEventListener('mouseover', (event) => {
                const code = (event.target && event.target.closest && event.target.closest('code')) || codeBesideLocator(event);
                markHovered(code && extractRef(code) ? code : null);
              }, true);
            })();
        """
        return script.trimIndent()
    }

    fun buildFileLinkHandlerScript(
        projectBasePath: String?,
        enabled: Boolean,
        openFileCallback: String? = null,
    ): String? {
        if (!enabled) return null
        if (projectBasePath.isNullOrBlank()) return null
        val directory = escapeJavaScript(projectBasePath)

        @Language("JavaScript")
        val openFileFallback =
            "window.location.assign('${OpenCodeServerProtocol.OPEN_FILE_LINK_SCHEME}://${OpenCodeServerProtocol.OPEN_FILE_LINK_HOST}?href=' + encodeURIComponent(rawHref) + '&base=' + encodeURIComponent(directory))"
        val openFileAction =
            openFileCallback?.let { callback ->
                @Language("JavaScript")
                val action =
                    """
                    try {
                      $callback;
                      return;
                    } catch (error) {
                      if (window.console && window.console.warn) {
                        window.console.warn('Failed to forward file link to IntelliJ', error);
                      }
                    }
                    $openFileFallback;
                """
                action.trimIndent()
            } ?: openFileFallback

        @Language("JavaScript")
        val script =
            $$"""
            (() => {
              if (window.__opencodeIntellijFileLinksInstalled) return;
              window.__opencodeIntellijFileLinksInstalled = true;
              const directory = '$${directory}';
              const explicitProtocol = /^[a-zA-Z][a-zA-Z0-9+.-]*:/;
              const supportedFileProtocol = /^(file|sandbox):/i;
              const absoluteFilePath = /^(\/|\\\\|[A-Za-z]:[\\/])/;
              $$DECODE_ROUTE_DIRECTORY_JS
              const openCodeRoutePath = (value) => {
                const text = (value || '').trim();
                if (text.startsWith('/')) return text;
                if (!/^https?:\/\//i.test(text)) return '';
                try {
                  const url = new URL(text);
                  return url.origin === window.location.origin ? url.pathname : '';
                } catch (_) {
                  return '';
                }
              };
              // SPA routes must never be treated as local files. The 1.18 layout uses
              // /server/<key>/session/<id> (e.g. task/subagent cards); the legacy layout uses a
              // base64url-encoded project directory as the first segment. Bare project roots and
              // "/" are also SPA destinations (home / project switch), not filesystem paths.
              const isOpenCodeAppRoute = (value) => {
                const path = openCodeRoutePath(value);
                if (!path) return false;
                if (path === '/' || path === '') return true;
                if (/^\/server(?:\/|[/?#]|$)/.test(path)) return true;
                if (/^\/new-session(?:\/|[/?#]|$)/.test(path)) return true;
                const match = /^\/([^/?#]+)(?:\/session(?:[/?#]|$)|[/?#]|$)/.exec(path);
                if (!match) return false;
                return absoluteFilePath.test(decodeRouteDirectory(match[1]));
              };
              const looksLikeFilePath = (value) => {
                if (!value) return false;
                const text = value.trim();
                return text.length > 0 && text.length < 512 && !/\s/.test(text) && /[./\\]/.test(text);
              };
              const inferredFileLink = (link) => {
                const row = link.closest ? link.closest('tr') : null;
                const cell = link.closest ? link.closest('td,th') : null;
                if (!row || !cell) return '';
                const cells = Array.from(row.children);
                const index = cells.indexOf(cell);
                if (index <= 0) return '';
                for (const candidate of cells.slice(0, index).reverse()) {
                  const text = (candidate.textContent || '').trim();
                  if (looksLikeFilePath(text)) return text;
                }
                return '';
              };
              let lastOpenedHref = '';
              let lastOpenedAt = 0;
              const cleanDisplayedPath = (value) => (value || '').replace(/[\u202A-\u202E]/g, '').trim();
              // Durable slot only — no locale-specific aria/title labels.
              const changedFileButtonSelector = '[data-slot="session-review-view-button"]';
              const changedFileButtonLink = (target) => {
                const button = target && target.closest ? target.closest(changedFileButtonSelector) : null;
                if (!button) return '';
                const item = button.closest ? button.closest('[data-file], [data-path], [data-slot="session-review-accordion-item"]') : null;
                const dataPath = item ? (item.getAttribute('data-file') || item.getAttribute('data-path') || '') : '';
                if (dataPath) return dataPath;
                const info = (button.closest && button.closest('[data-slot="session-review-file-info"]')) ||
                  (item && item.querySelector ? item.querySelector('[data-slot="session-review-file-info"]') : null);
                const directory = cleanDisplayedPath(info && info.querySelector ? info.querySelector('[data-slot="session-review-directory"]')?.textContent : '');
                const fileName = cleanDisplayedPath(info && info.querySelector ? info.querySelector('[data-slot="session-review-filename"]')?.textContent : '');
                if (!fileName) return '';
                return directory ? directory.replace(/[\\/]?$/, '/') + fileName : fileName;
              };
              // The redesigned (v2) review panel (default new layout on desktop, i.e. when
              // forceCompactLayout is off) drops the per-file "open" button for an in-app sidebar
              // tree + preview. The sidebar rows (button[data-path]) are the SPA's own preview
              // navigation, so hijacking them would break it; the safe "open in IDE" surface is the
              // non-interactive preview header file name/path (session-review-v2-file-*).
              const reviewV2FileButtonSelector = '[data-slot="session-review-v2-file-title"]';
              const reviewV2FileSelector = reviewV2FileButtonSelector + ', [data-slot="session-review-v2-file-name"], [data-slot="session-review-v2-file-path"]';
              const reviewV2FileLink = (target) => {
                const title = target && target.closest ? target.closest(reviewV2FileSelector) : null;
                if (!title) return '';
                const header = (title.closest && title.closest('[data-slot="session-review-v2-file-header"]')) || title;
                const fileName = cleanDisplayedPath(header.querySelector ? header.querySelector('[data-slot="session-review-v2-file-name"]')?.textContent : '');
                if (!fileName) return '';
                const directory = cleanDisplayedPath(header.querySelector ? header.querySelector('[data-slot="session-review-v2-file-path"]')?.textContent : '');
                return directory ? directory.replace(/[\\/]?$/, '/') + fileName : fileName;
              };
              const lastSegmentLooksLikeFile = (value) => {
                const path = String(value || '').split('?')[0].split('#')[0].replace(/[\\/]+$/, '');
                const last = (path.split(/[\\/]/).filter(Boolean).pop() || '').replace(/:\d+(?::\d+)?$/, '');
                return /\.[a-zA-Z0-9]{1,8}$/.test(last);
              };
              const isLocalFileLink = (href) => {
                if (!href || href.startsWith('#')) return false;
                if (isOpenCodeAppRoute(href)) return false;
                if (/^(\\\\|[A-Za-z]:[\\/])/.test(href)) return true;
                if (href.startsWith('/') && !href.startsWith('//')) return lastSegmentLooksLikeFile(href);
                if (explicitProtocol.test(href)) return supportedFileProtocol.test(href);
                if (href.startsWith('./') || href.startsWith('../')) return true;
                return !href.startsWith('//') && !href.includes('://');
              };
              const openFileInIde = (rawHref, partID) => {
                const now = Date.now();
                if (rawHref === lastOpenedHref && now - lastOpenedAt < 750) return;
                lastOpenedHref = rawHref;
                lastOpenedAt = now;
                partID = partID || '';
                $${openFileAction};
              };
              const toolOpenIconSelector = '[data-slot="opencode-intellij-open-file"]';
              const toolOpenRootSelector = '[data-component="edit-trigger"], [data-component="write-trigger"], [data-component="edit-tool"], [data-component="write-tool"], [data-slot="apply-patch-trigger-content"], [data-slot="session-turn-diff-trigger"]';
              const closestElement = (node, selector) => {
                let el = node;
                while (el && el.nodeType !== 1) el = el.parentNode;
                while (el) {
                  if (el.matches && el.matches(selector)) return el;
                  if (typeof el.closest === 'function') return el.closest(selector);
                  el = el.parentElement;
                }
                return null;
              };
              const pathFromToolRoot = (root) => {
                if (!root || !root.querySelector) return '';
                const fileName = cleanDisplayedPath(root.querySelector('[data-slot="message-part-title-filename"], [data-slot="apply-patch-filename"], [data-slot="session-turn-diff-filename"]')?.textContent);
                if (!fileName) return '';
                const directory = cleanDisplayedPath(root.querySelector('[data-slot="message-part-directory"], [data-slot="apply-patch-directory"], [data-slot="session-turn-diff-directory"]')?.textContent);
                return directory ? directory.replace(/[\\/]?$/, '/') + fileName : fileName;
              };
              const toolOpenIconLink = (target) => {
                const icon = closestElement(target, toolOpenIconSelector);
                if (!icon) return '';
                const stored = icon.getAttribute('data-href');
                if (stored) return stored;
                const root = closestElement(icon, toolOpenRootSelector);
                return pathFromToolRoot(root);
              };
              const filesBrowserRow = (node) => {
                const row = closestElement(node, '[data-slot="file-tree-v2-row"]');
                // Directory rows own expansion, including modified clicks.
                if (!row || row.hasAttribute('aria-expanded')) return null;
                const path = row.getAttribute('data-path') || '';
                if (!path) return null;
                const sidebar = row.closest('[data-slot="session-review-v2-sidebar"]');
                if (!sidebar || sidebar.querySelector('[data-component="select-v2"]')) return null;
                return row;
              };
              const resolveFileOpenTarget = (target, changedButtonOnly) => {
                const iconHref = toolOpenIconLink(target);
                if (iconHref) {
                  return { element: closestElement(target, toolOpenIconSelector), href: iconHref };
                }
                if (!changedButtonOnly) {
                  const filesRow = filesBrowserRow(target);
                  if (filesRow) return { element: filesRow, href: filesRow.getAttribute('data-path') || '' };
                }
                const changedFileHref = changedFileButtonLink(target);
                const reviewV2Href = changedFileHref ? '' : reviewV2FileLink(target);
                if (changedButtonOnly && !changedFileHref && !reviewV2Href) return null;
                const link = !changedFileHref && !reviewV2Href && target && target.closest ? target.closest('a') : null;
                // V2's Markdown sanitizer makes local links inert and records their path in
                // data-local-link. Older pages expose outbound href/target="_blank" links.
                const localHref = link ? link.getAttribute('data-local-link') : '';
                if (link && (!link.closest('[data-component="markdown"]') || (!localHref && link.target !== '_blank'))) return null;
                const rawHref = changedFileHref || reviewV2Href || localHref || (link ? (link.getAttribute('href') || inferredFileLink(link)) : '');
                if (!isLocalFileLink(rawHref)) return null;
                const element = changedFileHref
                  ? target.closest(changedFileButtonSelector)
                  : (reviewV2Href ? target.closest(reviewV2FileButtonSelector) : link);
                return { element: element, href: rawHref };
              };
              const partIdOf = (node) => {
                const el = node && node.closest ? node.closest('[data-timeline-part-id]') : null;
                return el ? (el.getAttribute('data-timeline-part-id') || '') : '';
              };
              const insertToolOpenIcons = (roots) => {
                for (const root of roots) {
                  if (!root.isConnected) continue;
                  const href = pathFromToolRoot(root);
                  if (!href) continue;
                  const actions = root.querySelector('[data-slot="message-part-actions"], [data-slot="apply-patch-trigger-actions"], [data-slot="session-turn-diff-meta"]');
                  if (!actions) continue;
                  const partID = partIdOf(root);
                  const existing = root.querySelector(toolOpenIconSelector);
                  if (existing) {
                    existing.setAttribute('data-href', href);
                    existing.setAttribute('data-part-id', partID);
                    continue;
                  }
                  const icon = document.createElement('span');
                  icon.setAttribute('data-slot', 'opencode-intellij-open-file');
                  icon.setAttribute('data-href', href);
                  icon.setAttribute('data-part-id', partID);
                  icon.setAttribute('role', 'button');
                  icon.setAttribute('tabindex', '0');
                  icon.style.cssText = 'display:inline-flex;align-items:center;flex:0 0 auto;width:14px;height:14px;margin-inline-end:6px;color:inherit;opacity:0.72;';
                  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
                  svg.setAttribute('viewBox', '0 0 20 20');
                  svg.setAttribute('width', '14');
                  svg.setAttribute('height', '14');
                  svg.setAttribute('aria-hidden', 'true');
                  svg.style.pointerEvents = 'none';
                  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
                  path.setAttribute('d', 'M11.7 4.6H15.4V8.3M15.2 4.8L10 10M4.6 6.2V15.4H13.8V11.2');
                  path.setAttribute('fill', 'none');
                  path.setAttribute('stroke', 'currentColor');
                  path.setAttribute('stroke-linecap', 'square');
                  svg.appendChild(path);
                  icon.appendChild(svg);
                  actions.insertBefore(icon, actions.firstChild);
                }
              };
              $$VISIT_MATCHING_ELEMENTS_JS
              const dirtyToolRoots = new Set();
              let insertQueued = false;
              const queueInsertToolOpenIcons = (root) => {
                if (!root.isConnected) return;
                dirtyToolRoots.add(root);
                if (insertQueued) return;
                insertQueued = true;
                queueMicrotask(() => {
                  insertQueued = false;
                  const roots = Array.from(dirtyToolRoots);
                  dirtyToolRoots.clear();
                  insertToolOpenIcons(roots);
                });
              };
              new MutationObserver((mutations) => {
                for (const mutation of mutations) {
                  const root = closestElement(mutation.target, toolOpenRootSelector);
                  if (root) queueInsertToolOpenIcons(root);
                  mutation.addedNodes.forEach(node => visitMatchingElements(node, toolOpenRootSelector, queueInsertToolOpenIcons));
                }
              }).observe(document.documentElement, { childList: true, subtree: true, characterData: true });
              visitMatchingElements(document, toolOpenRootSelector, queueInsertToolOpenIcons);
              const handleFileOpenEvent = (event, changedButtonOnly) => {
                const icon = closestElement(event.target, toolOpenIconSelector);
                if (icon) {
                  event.preventDefault();
                  event.stopPropagation();
                  event.stopImmediatePropagation();
                  if (event.type !== 'mousedown') {
                    const iconHref = toolOpenIconLink(icon);
                    if (iconHref) openFileInIde(iconHref, icon.getAttribute('data-part-id') || partIdOf(icon));
                  }
                  return;
                }
                if (event.defaultPrevented) return;
                // Alt and Ctrl/Cmd+Click are reserved for the IDE diff gesture
                // (buildDiffNavigationScript), except Files-tab tree rows (open the file).
                const filesRow = filesBrowserRow(event.target);
                const modified = event.altKey || (/Mac|iPhone|iPod|iPad/.test(navigator.platform) ? event.metaKey : event.ctrlKey);
                if (filesRow && !modified) return;
                if (modified && !filesRow) return;
                const resolved = resolveFileOpenTarget(event.target, changedButtonOnly);
                if (!resolved) return;
                event.preventDefault();
                event.stopImmediatePropagation();
                openFileInIde(resolved.href);
              };
              window.addEventListener('pointerdown', (event) => handleFileOpenEvent(event, true), true);
              window.addEventListener('mousedown', (event) => handleFileOpenEvent(event, true), true);
              window.addEventListener('click', (event) => handleFileOpenEvent(event, false), true);
              window.addEventListener('keydown', (event) => {
                if (event.key === 'Enter' && closestElement(event.target, 'a[data-local-link]')) {
                  handleFileOpenEvent(event, false);
                }
              }, true);
              $$POINTER_CURSOR_KIT_JS
              document.addEventListener('mouseover', (event) => {
                const target = event.target && event.target.nodeType === 1 ? event.target : null;
                const resolved = target ? resolveFileOpenTarget(target, false) : null;
                markHovered(resolved ? resolved.element : null);
              }, true);
            })();
        """
        return script.trimIndent()
    }

    fun buildDiffNavigationScript(enabled: Boolean, openDiffCallback: String? = null): String? {
        if (!enabled || openDiffCallback == null) return null

        @Language("JavaScript")
        val script =
            $$"""
            (() => {
              if (window.__opencodeIntellijDiffNavInstalled) return;
              window.__opencodeIntellijDiffNavInstalled = true;
              const clean = (value) => (value || '').replace(/[\u202A-\u202E]/g, '').trim();
              const messageIdOf = (node) => {
                const el = node && node.closest ? node.closest('[data-message-id]') : null;
                return el ? (el.getAttribute('data-message-id') || '') : '';
              };
              const partIdOf = (node) => {
                const el = node && node.closest ? node.closest('[data-timeline-part-id]') : null;
                return el ? (el.getAttribute('data-timeline-part-id') || '') : '';
              };
              const pathFrom = (root, dirSel, nameSel) => {
                if (!root || !root.querySelector) return '';
                const dir = clean(root.querySelector(dirSel)?.textContent).replace(/\\/g, '/');
                const name = clean(root.querySelector(nameSel)?.textContent).replace(/\\/g, '/');
                if (!name) return '';
                return dir ? dir.replace(/[\\/]?$/, '/') + name : name;
              };
              const isMac = /Mac|iPhone|iPod|iPad/.test(navigator.platform);
              const isDiffGesture = (event) => event.altKey || (isMac ? event.metaKey : event.ctrlKey);
              const REVIEW_MODE_ATTR = 'data-opencode-intellij-review-mode';
              const reviewModeTriggerSelector = '[data-slot="session-review-v2-sidebar-header"] [data-component="select-v2"]';
              const listboxSelector = '[data-slot="select-v2-listbox"]';
              const listboxTriggers = new WeakMap();
              const syncReviewMode = (listbox) => {
                // Cache while open: aria-controls disappears when selection closes the popup,
                // before the observer delivers its final data-selected mutation.
                let trigger = listboxTriggers.get(listbox);
                if (!trigger) {
                  trigger = Array.from(document.querySelectorAll(reviewModeTriggerSelector)).find(el => {
                    const ids = (el.getAttribute('aria-controls') || '').split(/\s+/);
                    return ids.some(id => document.getElementById(id)?.contains(listbox));
                  });
                  if (trigger) listboxTriggers.set(listbox, trigger);
                }
                if (!trigger || !trigger.isConnected) return;
                const selected = listbox.querySelector('[data-key][data-selected], [data-key][aria-selected="true"]');
                const key = selected && selected.getAttribute('data-key');
                if (key === 'git' || key === 'branch' || key === 'turn') trigger.setAttribute(REVIEW_MODE_ATTR, key);
              };
              $$VISIT_MATCHING_ELEMENTS_JS
              new MutationObserver((mutations) => {
                const listboxes = new Set();
                for (const mutation of mutations) {
                  const target = mutation.target.nodeType === 1 ? mutation.target : mutation.target.parentElement;
                  const listbox = target && target.closest(listboxSelector);
                  if (listbox) listboxes.add(listbox);
                  for (const node of mutation.addedNodes) {
                    visitMatchingElements(node, listboxSelector, el => listboxes.add(el));
                  }
                }
                listboxes.forEach(syncReviewMode);
              }).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['data-selected', 'aria-selected'] });
              visitMatchingElements(document, listboxSelector, syncReviewMode);
              const changesSidebarOf = (node) => {
                if (!node || !node.closest) return null;
                // The preview header is a sibling of the sidebar, not its descendant.
                const panel = node.closest('[data-component="session-review-v2"]');
                const sidebar = node.closest('[data-slot="session-review-v2-sidebar"]') ||
                  (panel && panel.querySelector('[data-slot="session-review-v2-sidebar"]'));
                if (!sidebar || !sidebar.querySelector('[data-component="select-v2"]')) return null;
                return sidebar;
              };
              const filesBrowserRow = (node) => {
                if (!node || !node.closest) return null;
                const row = node.closest('[data-slot="file-tree-v2-row"]');
                if (!row) return null;
                const path = row.getAttribute('data-path') || '';
                if (!path) return null;
                const sidebar = row.closest('[data-slot="session-review-v2-sidebar"]');
                if (!sidebar || sidebar.querySelector('[data-component="select-v2"]')) return null;
                return row;
              };
              const reviewModeOf = (sidebar) => {
                const trigger = sidebar.querySelector('[data-component="select-v2"]');
                const key = trigger ? (trigger.getAttribute(REVIEW_MODE_ATTR) || trigger.getAttribute('data-key') || '') : '';
                if (key === 'git' || key === 'branch' || key === 'turn') return key;
                return 'git';
              };
              const reviewFilePath = (start) => {
                const row = start.closest('[data-slot="file-tree-v2-row"]');
                if (row) return row.getAttribute('data-path') || '';
                const header = start.closest('[data-slot="session-review-v2-file-title"], [data-slot="session-review-v2-file-name"], [data-slot="session-review-v2-file-path"]');
                if (!header) return '';
                const root = (header.closest && header.closest('[data-slot="session-review-v2-file-header"]')) || header;
                const name = clean(root.querySelector ? root.querySelector('[data-slot="session-review-v2-file-name"]')?.textContent : '');
                const dir = clean(root.querySelector ? root.querySelector('[data-slot="session-review-v2-file-path"]')?.textContent : '');
                if (!name) return '';
                return dir ? dir.replace(/[\\\\/]?$/, '/') + name : name;
              };
              // Review/turn-summary/indicator: session.diff is keyed by the turn's *user*
              // message id. Chat edit/write/patch: the tool part id (prt_…) is the stable key;
              // there is no GET-by-part, so the JVM pages session.messages to find it.
              // CLI 2.x Changes tab: Git/Branch → /api/vcs/diff; Last turn → session.diff.
              // Files tab file-tree rows are not diffs — file-link opens them.
              const resolveDiffTarget = (start) => {
                if (!start || !start.closest) return null;
                if (start.closest('[data-slot="opencode-intellij-open-file"]')) return null;
                if (filesBrowserRow(start)) return null;
                const changesSidebar = changesSidebarOf(start);
                if (changesSidebar) {
                  const path = reviewFilePath(start);
                  if (!path) return null;
                  const mode = reviewModeOf(changesSidebar);
                  if (mode === 'git') return { messageID: '', filePath: path, partID: '', vcsMode: 'working' };
                  if (mode === 'branch') return { messageID: '', filePath: path, partID: '', vcsMode: 'branch' };
                  return { messageID: '', filePath: path, partID: '', vcsMode: '' };
                }
                const fileItem = start.closest('[data-file]');
                if (fileItem) return { messageID: messageIdOf(fileItem), filePath: fileItem.getAttribute('data-file') || '', partID: '' };
                const turnRow = start.closest('[data-slot="session-turn-diff-trigger"]');
                if (turnRow) return { messageID: messageIdOf(turnRow), filePath: pathFrom(turnRow, '[data-slot="session-turn-diff-directory"]', '[data-slot="session-turn-diff-filename"]'), partID: '' };
                const patchRow = start.closest('[data-slot="apply-patch-trigger-content"]');
                if (patchRow) return { messageID: messageIdOf(patchRow), filePath: pathFrom(patchRow, '[data-slot="apply-patch-directory"]', '[data-slot="apply-patch-filename"]'), partID: partIdOf(patchRow) };
                const editBlock = start.closest('[data-component="edit-tool"], [data-component="write-tool"], [data-component="apply-patch-tool"]');
                if (editBlock) return { messageID: messageIdOf(editBlock), filePath: '', partID: partIdOf(editBlock) };
                const indicator = start.closest('[data-component="diff-changes"]');
                if (indicator) return { messageID: messageIdOf(indicator), filePath: '', partID: '' };
                const turn = start.closest('[data-component="user-message"], [data-component="text-part"]');
                if (turn) {
                  const messageID = messageIdOf(turn);
                  if (messageID) return { messageID: messageID, filePath: '', partID: '' };
                }
                return null;
              };
              document.addEventListener('click', (event) => {
                if (event.defaultPrevented || !isDiffGesture(event)) return;
                const target = resolveDiffTarget(event.target);
                if (!target) return;
                event.preventDefault();
                event.stopImmediatePropagation();
                const messageID = target.messageID || '';
                const filePath = target.filePath || '';
                const partID = target.partID || '';
                const vcsMode = target.vcsMode || '';
                try {
                  $${openDiffCallback};
                } catch (error) {
                  if (window.console && window.console.warn) {
                    window.console.warn('Failed to forward diff target to IntelliJ', error);
                  }
                }
              }, true);
            })();
        """
        return script.trimIndent()
    }
}
