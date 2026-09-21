#!/usr/bin/env bash
# Checks the OpenAPI operations and live response roots consumed by the plugin.
# Usage: OPENCODE_SERVER_PASSWORD=testpw123 scripts/check-wire-contract.sh [base-url] [directory]

set -euo pipefail

BASE_URL="${1:-http://127.0.0.1:4096}"
DIRECTORY="${2:-$PWD}"
PASSWORD="${OPENCODE_SERVER_PASSWORD:?set OPENCODE_SERVER_PASSWORD}"
AUTH="opencode:${PASSWORD}"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

cli_identity_ok() {
  local path="$1"
  local out="$2"
  curl -fsu "$AUTH" "$BASE_URL$path" -o "$out" && python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); assert isinstance(d.get("pid"), int) and isinstance(d.get("version"), str)' "$out" 2>/dev/null
}

PROTOCOL="${3:-}"
if [ -z "$PROTOCOL" ]; then
  if cli_identity_ok /api/info "$WORKDIR/status-probe.json" || cli_identity_ok /api/status "$WORKDIR/status-probe.json"; then
    PROTOCOL=v2
  else
    PROTOCOL=v1
  fi
fi

if [ "$PROTOCOL" = "v2" ]; then
  IDENTITY=""
  if cli_identity_ok /api/info "$WORKDIR/status.json"; then
    IDENTITY=/api/info
  elif cli_identity_ok /api/status "$WORKDIR/status.json"; then
    IDENTITY=/api/status
  else
    echo "FAIL: CLI 2.x identity missing (/api/info or /api/status)" >&2
    exit 1
  fi
  curl -fsu "$AUTH" "$BASE_URL/openapi.json" -o "$WORKDIR/doc.json"
  curl -fsu "$AUTH" "$BASE_URL/api/session/active" -o "$WORKDIR/active.json"
  curl -fsu "$AUTH" "$BASE_URL/api/permission/request" -o "$WORKDIR/permission.json"
  curl -fsu "$AUTH" "$BASE_URL/api/form" -o "$WORKDIR/form.json"
  python3 - "$WORKDIR" "$IDENTITY" <<'PY'
import json, pathlib, sys
root = pathlib.Path(sys.argv[1])
identity = sys.argv[2]
doc = json.loads((root / "doc.json").read_text())
status = json.loads((root / "status.json").read_text())
failures = []
ops = {
    (identity, "get"): None,
    ("/api/event", "get"): None,
    ("/api/session", "get"): None,
    ("/api/session/active", "get"): None,
    ("/api/session/{sessionID}", "get"): None,
    ("/api/session/{sessionID}/message", "get"): None,
    ("/api/session/{sessionID}/diff", "get"): None,
    ("/api/session/{sessionID}/prompt", "post"): None,
    ("/api/permission/request", "get"): None,
    ("/api/session/{sessionID}/permission/{requestID}/reply", "post"): None,
}
for (path, method), _ in ops.items():
    if not (doc.get("paths") or {}).get(path, {}).get(method):
        failures.append(f"missing {method.upper()} {path}")
if not isinstance(status.get("pid"), int) or not isinstance(status.get("version"), str):
    failures.append(f"{identity} shape changed")
active = json.loads((root / "active.json").read_text())
if not isinstance(active, dict) or not isinstance(active.get("data"), dict):
    failures.append("/api/session/active envelope changed")
perm = json.loads((root / "permission.json").read_text())
if not isinstance(perm, dict) or not isinstance(perm.get("data"), list):
    failures.append("/api/permission/request envelope changed")
form = json.loads((root / "form.json").read_text())
if not isinstance(form, dict) or not isinstance(form.get("data"), list):
    failures.append("/api/form envelope changed")
if failures:
    for f in failures:
        print(f"FAIL: {f}", file=sys.stderr)
    raise SystemExit(1)
print(f"OK: CLI 2.x {len(ops)} operations; OpenCode {status['version']}")
PY
  exit 0
fi

curl -fsu "$AUTH" "$BASE_URL/doc" -o "$WORKDIR/doc.json"
curl -fsu "$AUTH" "$BASE_URL/global/health" -o "$WORKDIR/health.json"
ENCODED_DIRECTORY="$(python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1], safe=""))' "$DIRECTORY")"
curl -fsu "$AUTH" "$BASE_URL/session/status?directory=$ENCODED_DIRECTORY" -o "$WORKDIR/status.json"
curl -fsu "$AUTH" "$BASE_URL/permission?directory=$ENCODED_DIRECTORY" -o "$WORKDIR/permission.json"
curl -fsu "$AUTH" "$BASE_URL/question?directory=$ENCODED_DIRECTORY" -o "$WORKDIR/question.json"
curl -fsu "$AUTH" "$BASE_URL/api/session?order=desc&limit=1&directory=$ENCODED_DIRECTORY" -o "$WORKDIR/sessions.json"

python3 - "$WORKDIR" <<'PY'
import json
import pathlib
import sys
import copy

root = pathlib.Path(sys.argv[1])
load = lambda name: json.loads((root / name).read_text())
doc = load("doc.json")

operations = {
    ("/api/health", "get"): "v2.health.get",
    ("/global/health", "get"): "global.health",
    ("/global/dispose", "post"): "global.dispose",
    ("/global/event", "get"): "global.event",
    ("/session/status", "get"): "session.status",
    ("/session/{sessionID}", "get"): "session.get",
    ("/session/{sessionID}/children", "get"): "session.children",
    ("/session/{sessionID}/diff", "get"): "session.diff",
    ("/session/{sessionID}/message", "get"): "session.messages",
    ("/permission", "get"): "permission.list",
    ("/question", "get"): "question.list",
    ("/permission/{requestID}/reply", "post"): "permission.reply",
    ("/api/session", "get"): "v2.session.list",
    ("/api/session/{sessionID}/message", "get"): "v2.session.messages",
    ("/api/session/{sessionID}/prompt", "post"): "v2.session.prompt",
}

def operation_failures(candidate):
    result = []
    for (path, method), operation_id in operations.items():
        operation = candidate.get("paths", {}).get(path, {}).get(method)
        if not operation:
            result.append(f"missing {method.upper()} {path}")
            continue
        if operation.get("operationId") != operation_id:
            result.append(f"{method.upper()} {path}: operationId={operation.get('operationId')!r}")
        if operation.get("deprecated") is True:
            result.append(f"{method.upper()} {path}: deprecated")
    return result

failures = operation_failures(doc)

# Negative fixtures: prove each declared operation is genuinely required by this checker.
for path, method in operations:
    mutated = copy.deepcopy(doc)
    mutated.get("paths", {}).get(path, {}).pop(method, None)
    expected = f"missing {method.upper()} {path}"
    if expected not in operation_failures(mutated):
        failures.append(f"checker self-test did not reject missing {method.upper()} {path}")

health = load("health.json")
if not isinstance(health, dict) or health.get("healthy") is not True or not isinstance(health.get("version"), str):
    failures.append("/global/health root/version shape changed")
if not isinstance(load("status.json"), dict):
    failures.append("/session/status root is not object")
permissions = load("permission.json")
questions = load("question.json")
if not isinstance(permissions, list):
    failures.append("/permission root is not array")
if not isinstance(questions, list):
    failures.append("/question root is not array")
for endpoint, requests in (("/permission", permissions), ("/question", questions)):
    if not isinstance(requests, list):
        continue
    for index, request in enumerate(requests):
        if not isinstance(request, dict) or not isinstance(request.get("id"), str) or not isinstance(request.get("sessionID"), str):
            failures.append(f"{endpoint}[{index}] request identity shape changed")
sessions = load("sessions.json")
if not isinstance(sessions, dict) or not isinstance(sessions.get("data"), list) or not isinstance(sessions.get("cursor"), dict):
    failures.append("/api/session envelope changed")

doc_text = (root / "doc.json").read_text()
for event in (
    "session.status", "session.idle", "session.error", "session.created",
    "permission.asked", "permission.replied",
    "question.asked", "question.replied", "question.rejected",
):
    if event not in doc_text:
        failures.append(f"event missing from /doc: {event}")

if failures:
    for failure in failures:
        print(f"FAIL: {failure}", file=sys.stderr)
    raise SystemExit(1)

print(f"OK: {len(operations)} operations, 5 live response roots, 9 event types; OpenCode {health['version']}")
PY
