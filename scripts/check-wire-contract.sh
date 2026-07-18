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

root = pathlib.Path(sys.argv[1])
load = lambda name: json.loads((root / name).read_text())
doc = load("doc.json")

operations = {
    ("/global/event", "get"): "global.event",
    ("/session/status", "get"): "session.status",
    ("/permission/{requestID}/reply", "post"): "permission.reply",
    ("/api/session", "get"): "v2.session.list",
    ("/api/session/{sessionID}/message", "get"): "v2.session.messages",
    ("/api/session/{sessionID}/prompt", "post"): "v2.session.prompt",
}

failures = []
for (path, method), operation_id in operations.items():
    operation = doc.get("paths", {}).get(path, {}).get(method)
    if not operation:
        failures.append(f"missing {method.upper()} {path}")
        continue
    if operation.get("operationId") != operation_id:
        failures.append(f"{method.upper()} {path}: operationId={operation.get('operationId')!r}")
    if operation.get("deprecated") is True:
        failures.append(f"{method.upper()} {path}: deprecated")

health = load("health.json")
if not isinstance(health, dict) or health.get("healthy") is not True or not isinstance(health.get("version"), str):
    failures.append("/global/health root/version shape changed")
if not isinstance(load("status.json"), dict):
    failures.append("/session/status root is not object")
if not isinstance(load("permission.json"), list):
    failures.append("/permission root is not array")
if not isinstance(load("question.json"), list):
    failures.append("/question root is not array")
sessions = load("sessions.json")
if not isinstance(sessions, dict) or not isinstance(sessions.get("data"), list) or not isinstance(sessions.get("cursor"), dict):
    failures.append("/api/session envelope changed")

doc_text = (root / "doc.json").read_text()
for event in (
    "session.status", "session.idle", "session.error",
    "permission.asked", "permission.replied",
    "question.asked", "question.replied", "question.rejected",
):
    if event not in doc_text:
        failures.append(f"event missing from /doc: {event}")

if failures:
    for failure in failures:
        print(f"FAIL: {failure}", file=sys.stderr)
    raise SystemExit(1)

print(f"OK: {len(operations)} operations, 4 live response roots, 8 event types; OpenCode {health['version']}")
PY
