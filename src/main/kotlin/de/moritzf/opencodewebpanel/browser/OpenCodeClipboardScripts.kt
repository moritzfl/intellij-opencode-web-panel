package de.moritzf.opencodewebpanel.browser

import de.moritzf.opencodewebpanel.browser.OpenCodeBrowserScriptSupport.escapeJavaScript
import de.moritzf.opencodewebpanel.server.OpenCodeServerProtocol
import org.intellij.lang.annotations.Language

internal object OpenCodeClipboardScripts {
    fun buildDispatchDroppedFilesScript(
        files: List<OpenCodeServerProtocol.DroppedFilePayload>,
        textPlain: List<String> = emptyList(),
        enabled: Boolean = true,
        batchId: String? = null,
        resultCallback: String? = null,
        focusPrompt: Boolean = false,
    ): String? {
        val textEntries = textPlain.filter { it.isNotBlank() }
        if (!enabled || (files.isEmpty() && textEntries.isEmpty())) return null
        val fileEntries =
            files.joinToString(",\n") { file ->
                @Language("JavaScript")
                val entry =
                    "{ name: '${escapeJavaScript(file.name)}', mime: '${escapeJavaScript(file.mime)}', lastModified: ${file.lastModified}, base64: '${escapeJavaScript(file.base64)}' }"
                entry
            }
        val textDrops =
            textEntries.joinToString("\n") { text ->
                val isStandaloneFileReference =
                    text.startsWith("file:") && !text.contains('\n') && !text.contains('\r')
                val drop =
                    if (isStandaloneFileReference) {
                        "results.push(dispatchDrop((transfer) => transfer.setData('text/plain', '${escapeJavaScript(text)}')));"
                    } else {
                        "results.push(dispatchPaste('${escapeJavaScript(text)}'));"
                    }
                drop
            }
        val fileDrop =
            if (files.isNotEmpty()) {
                @Language("JavaScript")
                val drop =
                    """
                results.push(dispatchDrop((transfer) => {
                  const entries = [
                    $fileEntries
                  ];
                  for (const entry of entries) {
                    transfer.items.add(new File([decode(entry.base64)], entry.name, {
                      type: entry.mime,
                      lastModified: entry.lastModified,
                    }));
                  }
                }));
            """
                drop.trimIndent()
            } else {
                ""
            }
        val escapedBatchId = escapeJavaScript(batchId.orEmpty())
        val focusPromptLiteral = if (focusPrompt) "true" else "false"
        val reportResult =
            resultCallback
                ?.let { callback ->
                    @Language("JavaScript")
                    val report =
                        """
                try {
                  $callback;
                } catch (error) {
                  if (window.console && window.console.warn) {
                    window.console.warn('Failed to report OpenCode drop result to IntelliJ', error);
                  }
                }
            """
                    report.trimIndent()
                }
                .orEmpty()
        @Language("JavaScript")
        val script =
            """
            (() => {
              const batchId = '$escapedBatchId';
              const focusPrompt = $focusPromptLiteral;
              const report = (accepted) => {
                $reportResult
              };
              if (typeof DataTransfer !== 'function' || typeof File !== 'function' ||
                  typeof DragEvent !== 'function' || typeof ClipboardEvent !== 'function') {
                console.warn('OpenCode Web Panel could not dispatch dropped files: browser drag APIs unavailable');
                report(false);
                return;
              }
              const target = Array.from(document.querySelectorAll(
                '[data-component="prompt-input"][contenteditable="true"], [data-component="composer-editor"][contenteditable="true"], [data-slot="composer-editor"][contenteditable="true"]',
              ))
                .find((element) => {
                  const style = window.getComputedStyle(element);
                  return element.isConnected && style.display !== 'none' && style.visibility !== 'hidden';
                });
              if (!target) {
                report(false);
                return;
              }
              const decode = (base64) => {
                const binary = atob(base64);
                const bytes = new Uint8Array(binary.length);
                for (let index = 0; index < binary.length; index += 1) {
                  bytes[index] = binary.charCodeAt(index);
                }
                return bytes;
              };
              const dispatchDrop = (fill) => {
                const transfer = new DataTransfer();
                fill(transfer);
                const options = { bubbles: true, cancelable: true, dataTransfer: transfer };
                target.dispatchEvent(new DragEvent('dragover', options));
                const event = new DragEvent('drop', options);
                target.dispatchEvent(event);
                if (focusPrompt) {
                  requestAnimationFrame(() => { if (document.contains(target)) target.focus(); });
                }
                return event.defaultPrevented;
              };
              const dispatchPaste = (text) => {
                const transfer = new DataTransfer();
                transfer.setData('text/plain', text);
                const event = new ClipboardEvent('paste', {
                  bubbles: true,
                  cancelable: true,
                  clipboardData: transfer,
                });
                target.dispatchEvent(event);
                if (focusPrompt) {
                  requestAnimationFrame(() => { if (document.contains(target)) target.focus(); });
                }
                return event.defaultPrevented;
              };
              const results = [];
              $textDrops
              $fileDrop
              const accepted = results.length > 0 && results.every(Boolean);
              report(accepted);
            })();
        """
        return script.trimIndent()
    }

    fun buildCaptureClipboardPasteScript(batchId: String, enabled: Boolean): String? {
        if (!enabled) return null
        @Language("JavaScript")
        val script =
            """
            (() => {
              const id = '${escapeJavaScript(batchId)}';
              const targets = window.__opencodeIntellijPasteTargets ||= new Map();
              targets.set(id, { target: document.activeElement, href: location.href });
              // A navigation, rejected file, or stopped renderer may never deliver the completion.
              window.setTimeout(() => targets.delete(id), 30000);
            })();
        """
        return script.trimIndent()
    }

    fun buildClipboardPasteScript(
        files: List<OpenCodeServerProtocol.DroppedFilePayload>,
        text: String?,
        fileReferences: List<String>,
        batchId: String,
        resultCallback: String?,
        enabled: Boolean,
        nativeFallback: Boolean = false,
    ): String? {
        if (!enabled || resultCallback == null) return null
        val entries =
            files.joinToString(",") { file ->
                "{name:'${escapeJavaScript(file.name)}',type:'${escapeJavaScript(file.mime)}',base64:'${escapeJavaScript(file.base64)}'}"
            }
        val references = fileReferences.joinToString(",") { "'${escapeJavaScript(it)}'" }
        val textLiteral = text?.let { "'${escapeJavaScript(it)}'" } ?: "null"
        @Language("JavaScript")
        val script =
            """
            (() => {
              const batchId = '${escapeJavaScript(batchId)}';
              const report = (result) => { $resultCallback; };
              const targets = window.__opencodeIntellijPasteTargets;
              const captured = targets?.get(batchId);
              targets?.delete(batchId);
              const target = captured?.target;
              if (!target?.isConnected || captured.href !== location.href || document.activeElement !== target) {
                report('stale');
                return;
              }
              if ($nativeFallback) {
                report('native');
                return;
              }
              const composer = target.matches(
                '[data-component="prompt-input"][contenteditable="true"], [data-component="composer-editor"][contenteditable="true"], [data-slot="composer-editor"][contenteditable="true"]'
              );
              const editable = target.isContentEditable ||
                ((target instanceof HTMLTextAreaElement ||
                  (target instanceof HTMLInputElement && /^(text|search|url|tel|email|password)$/.test(target.type))) &&
                  !target.readOnly && !target.disabled);
              const entries = [$entries];
              const references = [$references];
              const text = $textLiteral;
              if (!editable || (!composer && (entries.length || references.length)) ||
                  typeof DataTransfer !== 'function' || typeof ClipboardEvent !== 'function') {
                report('native');
                return;
              }
              // An empty prepared batch means the JVM already reported unreadable/oversized files.
              if (!entries.length && !references.length && text === null) {
                report('empty');
                return;
              }
              let handled = false;
              try {
                for (const reference of references) {
                  const transfer = new DataTransfer();
                  transfer.setData('text/plain', reference);
                  const options = { bubbles: true, cancelable: true, dataTransfer: transfer };
                  target.dispatchEvent(new DragEvent('dragover', options));
                  const event = new DragEvent('drop', options);
                  target.dispatchEvent(event);
                  if (!event.defaultPrevented) { report(handled ? 'rejected' : 'native'); return; }
                  handled = true;
                }
                if (entries.length || text !== null) {
                  const transfer = new DataTransfer();
                  for (const entry of entries) {
                    const bytes = Uint8Array.from(atob(entry.base64), c => c.charCodeAt(0));
                    transfer.items.add(new File([bytes], entry.name, { type: entry.type }));
                  }
                  if (text !== null) transfer.setData('text/plain', text);
                  const event = new ClipboardEvent('paste', { bubbles: true, cancelable: true, clipboardData: transfer });
                  target.dispatchEvent(event);
                  if (event.defaultPrevented) handled = true;
                  else if (!entries.length && text !== null && document.activeElement === target) {
                    handled = document.execCommand('insertText', false, text) || handled;
                  }
                }
                report(handled ? 'accepted' : 'native');
              } catch (_) {
                // An exception after dispatch cannot prove that nothing was inserted.
                report('rejected');
              }
            })();
        """
        return script.trimIndent()
    }
}
