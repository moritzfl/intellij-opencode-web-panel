#!/usr/bin/env bash
# Checks that the DOM/JS contract markers the plugin's injected scripts rely on still exist in
# the SPA bundle served by a live OpenCode server. Run this after an OpenCode update, before
# trusting the injections (see AGENTS.md "Validating Against a Real Server").
#
# Usage:
#   OPENCODE_SERVER_PASSWORD=testpw123 scripts/check-dom-contract.sh [base-url]
#
# Default base-url: http://127.0.0.1:4096

set -euo pipefail

BASE_URL="${1:-http://127.0.0.1:4096}"
PASSWORD="${OPENCODE_SERVER_PASSWORD:?set OPENCODE_SERVER_PASSWORD}"
AUTH="opencode:${PASSWORD}"

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

curl -fsu "$AUTH" "$BASE_URL/" -o "$WORKDIR/index.html"

# Collect every served script chunk referenced by the entry page. (No mapfile: macOS bash 3.2.)
ASSETS=()
while IFS= read -r asset; do
  ASSETS+=("$asset")
done < <(grep -oE 'assets/[A-Za-z0-9._-]+\.js' "$WORKDIR/index.html" | sort -u)
if [ "${#ASSETS[@]}" -eq 0 ]; then
  echo "FAIL: no JS assets found in $BASE_URL/ (auth problem or layout change?)" >&2
  exit 1
fi
: > "$WORKDIR/bundle.js"
for asset in "${ASSETS[@]}"; do
  curl -fsu "$AUTH" "$BASE_URL/$asset" >> "$WORKDIR/bundle.js"
done

# Contract markers used by the injected scripts. Keep in sync with
# OpenCodeBrowserSnippets and the AGENTS.md DOM-contract sections.
MARKERS=(
  # diff navigation (buildDiffNavigationScript)
  'data-message-id'
  'session-turn-diff-trigger'
  'session-turn-diff-directory'
  'session-turn-diff-filename'
  'apply-patch-trigger-content'
  'apply-patch-directory'
  'apply-patch-filename'
  'edit-tool'
  'write-tool'
  'apply-patch-tool'
  'diff-changes'
  'message-part-directory'
  'message-part-title-filename'
  # file links, classic + v2 review panels (buildFileLinkHandlerScript)
  'session-review-view-button'
  'session-review-file-info'
  'session-review-directory'
  'session-review-filename'
  'session-review-v2-file-title'
  'session-review-v2-file-name'
  'session-review-v2-file-path'
  # project-switch toast suppression (buildProjectSwitchPromptSuppressionScript)
  'toast-icon'
  'toast-v2-icon'
  'toast-action'
  'toast-v2-actions'
  'toast-close-button'
  'toast-v2-close-button'
  'opencode-icon-'
  'checklist'
  'bubble-5'
  # compact layout (buildCompactLayoutScript)
  '(min-width: 768px)'
  '(max-width: 767px)'
  # IDE theme sync (buildIdeThemeSyncScript)
  'prefers-color-scheme: dark'
  # open-project seed (buildOpenProjectScript)
  'opencode.global.dat'
  'lastProject'
  # hide-website button (buildHideWebsiteButtonScript)
  'https://opencode.ai'
)

MISSING=0
for marker in "${MARKERS[@]}"; do
  if ! grep -qF -- "$marker" "$WORKDIR/bundle.js"; then
    echo "MISSING: $marker"
    MISSING=$((MISSING + 1))
  fi
done

TOTAL="${#MARKERS[@]}"
if [ "$MISSING" -gt 0 ]; then
  echo "FAIL: $MISSING/$TOTAL contract markers missing from $BASE_URL bundle" >&2
  exit 1
fi
echo "OK: all $TOTAL contract markers present in $BASE_URL bundle (${#ASSETS[@]} asset(s))"
