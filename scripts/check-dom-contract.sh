#!/usr/bin/env bash
# Checks that the DOM/JS contract markers the plugin's injected scripts rely on still exist in
# the SPA bundle served by a live OpenCode server. Run this after an OpenCode update, before
# trusting the injections (see AGENTS.md "Validating Against a Real Server").
#
# Usage:
#   OPENCODE_SERVER_PASSWORD=testpw123 scripts/check-dom-contract.sh [base-url]
#
# Default base-url: http://127.0.0.1:4096
#
# 1.18 and CLI 2.x both run this script. CLI 2.x ships session/review/composer slots in lazy
# imports under `/_assets/`; an index-only crawl is not a contract check. Follow both static
# imports/re-exports and dynamic imports, with single, double or backtick quotes.
# Quoted attribute markers (`"data-file"`) also match the backtick form CLI 2.x minifies to.

set -euo pipefail

BASE_URL="${1:-http://127.0.0.1:4096}"
PASSWORD="${OPENCODE_SERVER_PASSWORD:?set OPENCODE_SERVER_PASSWORD}"
AUTH="opencode:${PASSWORD}"

WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

curl -fsu "$AUTH" "$BASE_URL/" -o "$WORKDIR/index.html"

CLI2X=0
ASSET_PREFIX="assets"
if grep -q '/_assets/' "$WORKDIR/index.html"; then
  CLI2X=1
  ASSET_PREFIX="_assets"
fi

mkdir -p "$WORKDIR/js"
: > "$WORKDIR/queue"
index_files=()
while IFS= read -r asset; do
  name="${asset##*/}"
  echo "$name" >> "$WORKDIR/queue"
  index_files+=("$WORKDIR/js/$name")
done < <(grep -oE "/?_?assets/[A-Za-z0-9._-]+\.js" "$WORKDIR/index.html" | sort -u)
if [ ! -s "$WORKDIR/queue" ]; then
  echo "FAIL: no JS assets found in $BASE_URL/ (auth problem or layout change?)" >&2
  exit 1
fi

export AUTH BASE_URL ASSET_PREFIX
JS_DIR="$WORKDIR/js"
export JS_DIR

skip_chunk() {
  case "$1" in
    alert-*|bip-bop-*|nope-*|yup-*|staplebops-*|mermaid-*) return 0 ;;
  esac
  return 1
}

first_round=1
while [ -s "$WORKDIR/queue" ]; do
  sort -u "$WORKDIR/queue" -o "$WORKDIR/queue.uniq"
  : > "$WORKDIR/queue"
  : > "$WORKDIR/this_round"
  files=()
  while IFS= read -r name; do
    [ -n "$name" ] || continue
    [ -f "$JS_DIR/$name" ] && continue
    skip_chunk "$name" && continue
    echo "$name" >> "$WORKDIR/this_round"
    files+=("$JS_DIR/$name")
  done < "$WORKDIR/queue.uniq"
  if [ ! -s "$WORKDIR/this_round" ]; then
    break
  fi
  cat "$WORKDIR/this_round" | xargs -P 8 -I{} sh -c \
    'curl --connect-timeout 5 --max-time 30 -fsu "$AUTH" "$BASE_URL/$ASSET_PREFIX/$1" -o "$JS_DIR/$1" || { echo "FAIL fetch $ASSET_PREFIX/$1" >&2; exit 1; }' _ '{}'
  {
    # 2.0.25 moved Markdown into a statically imported chunk. Only following import(`...`)
    # misses its DOM markers even though the browser loads them normally. Scan each round in
    # one grep process: a complete 2.x graph has over a thousand chunks.
    grep -hoE "(from[[:space:]]*|import[[:space:]]*\\(?[[:space:]]*)[\"'\`]\\./[A-Za-z0-9._-]+\\.js[\"'\`]" "${files[@]}" 2>/dev/null \
      | sed -E 's#.*\./##; s/.$//' || true
    if [ "$first_round" = 1 ]; then
      grep -hoE '_?assets/[A-Za-z0-9._-]+\.js' "${index_files[@]}" 2>/dev/null | sed 's#.*/##' \
        | grep -E '^(route-|file-|shell-|screen-|session-|command-|dialog-|incompatible-|composer-|panel-|home-|titlebar-|new-session-|server-|select-|loader-)' \
        || true
    fi
  } | sort -u >> "$WORKDIR/queue"
  first_round=0
done

ASSET_COUNT="$(find "$JS_DIR" -type f -name '*.js' | wc -l | tr -d ' ')"
cat "$JS_DIR"/*.js > "$WORKDIR/bundle.js"

# Shared markers: present on both 1.18 and CLI 2.x after lazy chunks are included.
# Generic attrs stay in their compiled quoted form so `data-file` does not match `data-filename`.
MARKERS_COMMON=(
  '"data-message-id"'
  '"data-file"'
  'apply-patch-trigger-content'
  'apply-patch-directory'
  'apply-patch-filename'
  'edit-tool'
  'write-tool'
  'apply-patch-tool'
  'diff-changes'
  'tool-part-wrapper'
  '"data-timeline-part-id"'
  'inlineCodeKind'
  'data-component=markdown'
  '"data-path"'
  'session-review-view-button'
  'session-review-file-info'
  'session-review-directory'
  'session-review-filename'
  'session-review-v2-file-title'
  'session-review-v2-file-name'
  'session-review-v2-file-path'
  'session-review-v2-sidebar'
  'session-review-v2-file-header'
  'toast-icon'
  'toast-v2-icon'
  'toast-v2-actions'
  'toast-close-button'
  'toast-v2-close-button'
  'checklist'
  'bubble-5'
  '(min-width: 768px)'
  '(max-width: 767px)'
  'prefers-color-scheme: dark'
  'tab.new'
  'mod+t'
  'mod+n'
  'tab.close'
  'mod+w'
  'session.new'
  'mod+shift+s'
  'home.toggle'
  'sidebar.toggle'
  'mod+b'
  'titlebar-v2'
  'model.choose'
  "mod+'"
  'agent.cycle'
  'mod+.'
  'agent.cycle.reverse'
  'shift+mod+.'
  'model.variant.cycle'
  'shift+mod+d'
  'file.attach'
  'mod+u'
  'opencode.global.dat'
  'lastProject'
  'https://opencode.ai'
  'session-tab-popover-trigger'
  'home-project-row'
  'home-session-row'
  'home-session-search'
  'home-session-search-row'
  'data-session-id'
  'location.directory'
)

# 1.18-only. Absent on CLI 2.x (composer-editor, toast-v2 / opencode-v2-icon, window tabs).
MARKERS_V1=(
  'session-turn-diff-trigger'
  'session-turn-diff-directory'
  'session-turn-diff-filename'
  'session-turn-diff-meta'
  'toast-action'
  'opencode-icon-'
  'prompt-input'
  'lastProjectSession'
  'layout.page'
)

# CLI 2.x-only. Dual-selector injections already cover these alongside the 1.18 names.
# `home-session-project-name` has no 1.18 counterpart (that title span only exists on CLI 2.x
# Home rows); the shared session/search rows and 1.18 sidebar `data-session-id` live in COMMON.
MARKERS_V2=(
  'composer-editor'
  'home-session-project-name'
  'mobile-tabs-trigger'
  'mobile-tab-title'
  'opencode-v2-icon'
  'file-tree-v2-row'
  'select-v2'
  'select-v2-listbox'
  'session-review-v2-sidebar-header'
)

# 2.0.12 introduced inert local Markdown links. Older CLI 2.x uses href/target instead.
if [ "$CLI2X" = 1 ]; then
  if curl -fsu "$AUTH" "$BASE_URL/api/info" | node --input-type=module -e '
import { readFileSync } from "node:fs";
try {
  const version = JSON.parse(readFileSync(0, "utf8")).version?.match(/(\d+)\.(\d+)\.(\d+)/);
  if (!version) process.exit(1);
  const [major, minor, patch] = version.slice(1).map(Number);
  process.exit(major > 2 || (major === 2 && (minor > 0 || patch >= 12)) ? 0 : 1);
} catch { process.exit(1); }
'; then
    MARKERS_V2+=('data-local-link')
  fi
fi

marker_present() {
  local marker="$1"
  if grep -qF -- "$marker" "$WORKDIR/bundle.js"; then
    return 0
  fi
  case "$marker" in
    \"*\")
      local inner="${marker#\"}"
      inner="${inner%\"}"
      grep -qF -- "\`$inner\`" "$WORKDIR/bundle.js"
      return
      ;;
  esac
  return 1
}

check_markers() {
  local marker
  for marker in "$@"; do
    if ! marker_present "$marker"; then
      echo "MISSING: $marker"
      MISSING=$((MISSING + 1))
    fi
  done
}

MISSING=0
if [ "$CLI2X" = 1 ]; then
  check_markers "${MARKERS_COMMON[@]}" "${MARKERS_V2[@]}"
  TOTAL=$((${#MARKERS_COMMON[@]} + ${#MARKERS_V2[@]}))
else
  check_markers "${MARKERS_COMMON[@]}" "${MARKERS_V1[@]}"
  TOTAL=$((${#MARKERS_COMMON[@]} + ${#MARKERS_V1[@]}))
fi

# Persist keys: 1.18 minifies to .global("key"); CLI 2.x uses backticks. Classify every
# direct Persist.global/window/workspace/server key; extras fail until allowlisted.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
set +e
node "$SCRIPT_DIR/classify-persist-keys.mjs" "$WORKDIR/bundle.js" "$WORKDIR/persist.count"
persist_rc=$?
set -e
read -r PERSIST_COUNT PERSIST_FAILED < "$WORKDIR/persist.count"
MISSING=$((MISSING + PERSIST_FAILED))

if [ "$MISSING" -gt 0 ]; then
  echo "FAIL: $MISSING contract check(s) failed for $BASE_URL bundle ($TOTAL DOM markers checked, $ASSET_COUNT asset(s))" >&2
  exit 1
fi
if [ "$CLI2X" = 1 ]; then
  echo "OK: all $TOTAL CLI 2.x contract markers present in $BASE_URL bundle ($ASSET_COUNT asset(s))"
else
  echo "OK: all $TOTAL contract markers present in $BASE_URL bundle ($ASSET_COUNT asset(s))"
fi
echo "OK: all ${PERSIST_COUNT:-0} directly declared Persist keys classified"
