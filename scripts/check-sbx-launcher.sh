#!/usr/bin/env bash
# Launcher smoke tests. Uses a fake sbx, never creates a VM or calls a provider.
# bash scripts/check-sbx-launcher.sh [path/to/opencode-sbx.sh]
# POSIX script(1) adds PTY checks; other cases also run under Git Bash.
set -euo pipefail
SOURCE="${1:-$(cd "$(dirname "$0")/.." && pwd)/src/main/resources/de/moritzf/opencodewebpanel/server/opencode-sbx.sh}"
SOURCE="$(cd "$(dirname "$SOURCE")" && pwd)/$(basename "$SOURCE")"
ROOT="$(mktemp -d)"
trap 'rm -rf -- "$ROOT"' EXIT
ROOT="$(cd "$ROOT" && pwd -P)"
PASSED=0
FAILED=0
SKIPPED=0

host_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -a -m -l -C UTF8 "$1"; else printf '%s' "$1"; fi
}
guest_path() {
  local p
  p="$(host_path "$1")"
  if [[ "$p" == [A-Za-z]:/* ]]; then
    printf '/%s%s' "$(printf '%s' "${p:0:1}" | tr A-Z a-z)" "${p:2}"
  else
    printf '%s' "$p"
  fi
}

fixture() {
  CASE="$ROOT/$1"
  PROJECT="$CASE/sample repo"
  mkdir -p "$PROJECT/opencode-sbx" "$PROJECT/app space" "$CASE/home" "$CASE/log" "$CASE/bin"
  LAUNCHER="$PROJECT/opencode-sbx/opencode-sbx.sh"
  cp "$SOURCE" "$LAUNCHER"
  cat > "$PROJECT/opencode-sbx/opencode-sbx.yaml" <<'YAML'
canonicalDirectory: ./
workingDirectory: ./app space
persistSandboxSessions: false
YAML
  local canonical name
  canonical="$(host_path "$PROJECT")"
  if command -v sha256sum >/dev/null 2>&1; then
    name="$(printf '%s' "$canonical" | sha256sum | cut -c1-12)"
  else
    name="$(printf '%s' "$canonical" | shasum -a 256 | cut -c1-12)"
  fi
  export SMOKE_LOG="$CASE/log"
  export SMOKE_INVENTORY="{\"sandboxes\":[{\"name\":\"ide-ocwp-$name\",\"status\":\"running\",\"workspaces\":[\"$canonical\"]}]}"
  export HOME="$CASE/home" OCWP_CONFIG_DIR="$CASE/config" OCWP_DATA_DIR="$CASE/data"
  export OCWP_SBX="$CASE/bin/sbx"
  unset BASH_ENV ENV XDG_CONFIG_HOME XDG_DATA_HOME OPENCODE_SERVER_PASSWORD SMOKE_LS_FAIL SMOKE_TTY SMOKE_WEB
  cat > "$OCWP_SBX" <<'SBX'
#!/usr/bin/env bash
set -eu
printf '%s\n' "$1" >> "$SMOKE_LOG/calls"
case "$1" in
  daemon)
    if [[ "${SMOKE_TTY:-0}" != 1 ]]; then cat > "$SMOKE_LOG/setup-input"; fi
    echo 'daemon setup noise'
    ;;
  ls)
    if [[ "${SMOKE_LS_FAIL:-0}" == 1 ]]; then echo 'inventory unavailable' >&2; exit 9; fi
    printf '%s\n' "$SMOKE_INVENTORY"
    ;;
  create) printf '%s\0' "$@" > "$SMOKE_LOG/create-argv"; echo 'create failed' >&2; exit 23 ;;
  exec)
    printf '%s\0' "$@" > "$SMOKE_LOG/argv"
    for arg in "$@"; do
      if [[ "$arg" == *'version=$(timeout -k 2 10'* ]]; then
        printf '%s\n' probe >> "$SMOKE_LOG/probes"
        exit 0
      fi
    done
    # V2's sh -c dispatch contains a separate "opencode" argv marker as well.
    while [[ $# -gt 0 && "$1" != opencode ]]; do shift; done
    shift
    if [[ $# -gt 0 ]]; then printf '%s\0' "$@" > "$SMOKE_LOG/native-args"; else : > "$SMOKE_LOG/native-args"; fi
    if [[ "${SMOKE_TTY:-0}" != 1 ]]; then cat > "$SMOKE_LOG/input"; fi
    if [[ "${SMOKE_WEB:-0}" == 1 ]]; then
      for ((i=0; i<100; i++)); do [[ -f "$SMOKE_LOG/health" ]] && break; sleep 0.01; done
    fi
    printf '{"ok":true}\n'
    exit 19
    ;;
  ports) printf '[{"host_port":49123}]\n' ;;
  *) echo "Unexpected command: $1" >&2; exit 97 ;;
esac
SBX
  chmod +x "$OCWP_SBX"
  cat > "$CASE/bin/curl" <<'CURL'
#!/usr/bin/env bash
touch "$SMOKE_LOG/health"
printf '{"healthy":true}\n'
CURL
  chmod +x "$CASE/bin/curl"
  export PATH="$CASE/bin:$PATH"
  : > "$CASE/input"
}

invoke() {
  set +e
  (cd "$PROJECT" && bash "$LAUNCHER" "$@") < "$CASE/input" > "$CASE/stdout" 2> "$CASE/stderr"
  STATUS=$?
  set -e
}

expect_args() {
  [[ "$STATUS" -eq 19 ]] || return 1
  if [[ $# -gt 0 ]]; then printf '%s\0' "$@" > "$CASE/expected"; else : > "$CASE/expected"; fi
  cmp -s "$CASE/expected" "$SMOKE_LOG/native-args" &&
    cmp -s "$CASE/input" "$SMOKE_LOG/input" &&
    [[ ! -s "$SMOKE_LOG/setup-input" ]] &&
    [[ "$(cat "$CASE/stdout")" == '{"ok":true}' ]] &&
    [[ "$(cat "$SMOKE_LOG/calls")" == $'daemon\nls\nexec' ]]
}

result() {
  local name="$1"; shift
  if "$@"; then
    PASSED=$((PASSED + 1)); printf 'passed: %s\n' "$name"
  else
    FAILED=$((FAILED + 1)); printf 'failed: %s (exit %s)\n' "$name" "$STATUS"
    cat "$CASE/stderr" >&2
  fi
}

wait_for_url() {
  # print_url is a child process; let its final echo finish after the fake serve.
  local attempt
  for ((attempt=0; attempt<50; attempt++)); do
    grep -q 'http://127.0.0.1:49123' "$CASE/stderr" && return 0
    sleep 0.1
  done
  return 1
}

fixture default-tui
invoke
result default-tui expect_args

fixture run-pipe
printf 'piped prompt\n' > "$CASE/input"
invoke run 'a "quote"; $(false) * \ ä' --model fixture/model
result run-pipe expect_args run 'a "quote"; $(false) * \ ä' --model fixture/model

fixture export-json
invoke export ses_fixture
result export-json expect_args export ses_fixture

fixture native-acp
printf '{"id":1}\n' > "$CASE/input"
invoke --print-logs --log-level ERROR acp
result native-acp expect_args --print-logs --log-level ERROR acp

fixture acp-alias
printf '{"id":1}\n' > "$CASE/input"
invoke --acp --print-logs
result acp-alias expect_args acp --print-logs

fixture project-after-options
invoke --model fixture/model -c './app space'
result project-after-options expect_args --model fixture/model -c "$(guest_path "$PROJECT/app space")"

fixture project-first
invoke './app space' --continue
result project-first expect_args "$(guest_path "$PROJECT/app space")" --continue

fixture raw-serve
invoke --print-logs serve --port 5000
result raw-serve expect_args --print-logs serve --port 5000

fixture future-command
invoke future-command --new-option '' --web
result future-command expect_args future-command --new-option '' --web

fixture separator
invoke -- --web
result separator expect_args --web

fixture native-help
invoke --help
result native-help expect_args --help

fixture native-version
invoke --version
result native-version expect_args --version

fixture version-two
printf 'openCodeVersion: 2.x\n' >> "$PROJECT/opencode-sbx/opencode-sbx.yaml"
invoke --version
result version-two-exit test "$STATUS" -eq 19
result version-two-dispatch grep -aq 'HOME/.opencode/bin/opencode' "$SMOKE_LOG/argv"
result version-two-probe test "$(cat "$SMOKE_LOG/probes")" = probe

fixture web-help
invoke --web --help
result web-help expect_args serve --hostname 0.0.0.0 --port 4096 --print-logs --help
result web-help-no-poller test ! -e "$SMOKE_LOG/health"

fixture web-publish
export SMOKE_WEB=1
invoke --web
result web-publish-exit test "$STATUS" -eq 19
result web-publish-health test -e "$SMOKE_LOG/health"
result web-publish-url wait_for_url

fixture web-invalid-port
invoke --web --port 12345
result web-invalid-port test "$STATUS" -eq 2
result web-invalid-port-no-sbx test ! -e "$SMOKE_LOG/calls"

fixture directory-equals
invoke "--sbx-directory=$PROJECT" --version
result directory-equals expect_args --version

fixture malformed-inventory
export SMOKE_INVENTORY='unavailable'
invoke
result malformed-inventory test "$STATUS" -eq 1
result malformed-inventory-no-create test "$(cat "$SMOKE_LOG/calls")" = $'daemon\nls'

fixture foreign-vm
export SMOKE_INVENTORY="${SMOKE_INVENTORY/$(host_path "$PROJECT")/unmounted-project}"
invoke
result foreign-vm test "$STATUS" -eq 1
result foreign-vm-no-exec test ! -e "$SMOKE_LOG/argv"

fixture launcher-help
invoke --sbx-help
result launcher-help test "$STATUS" -eq 0
result help-no-sbx test ! -e "$SMOKE_LOG/calls"

fixture missing-dir
invoke --sbx-directory
result missing-dir test "$STATUS" -eq 2

fixture failed-inventory
export SMOKE_LS_FAIL=1
invoke
result failed-inventory test "$STATUS" -ne 0
result inventory-no-create test "$(cat "$SMOKE_LOG/calls")" = $'daemon\nls'

# Host mount interpolation is literal, portable and read-only for configuration.
for mount_case in unset empty custom literal; do
  fixture "mount-$mount_case"
  export SMOKE_INVENTORY='{"sandboxes":[]}'
  unset OCWP_SMOKE_GRADLE_HOME
  expected="$HOME/.gradle"
  case "$mount_case" in
    empty) export OCWP_SMOKE_GRADLE_HOME='' ;;
    custom) expected="$CASE/Gradle home ä"; export OCWP_SMOKE_GRADLE_HOME="$(host_path "$expected")" ;;
    literal) expected="$CASE/\$(touch SHOULD_NOT_EXIST)"; export OCWP_SMOKE_GRADLE_HOME="$(host_path "$expected")" ;;
  esac
  mkdir -p "$expected"
  cat >> "$PROJECT/opencode-sbx/opencode-sbx.yaml" <<'YAML'
extraMounts:
  - host: '${OCWP_SMOKE_GRADLE_HOME:-~/.gradle}'
    sandbox: /home/agent/.gradle-host
    readOnly: true
YAML
  invoke --version
  result "mount-$mount_case-create" test "$STATUS" -eq 23
  result "mount-$mount_case-path" grep -aqF -- "$(host_path "$expected"):ro" "$SMOKE_LOG/create-argv"
  result "mount-$mount_case-no-eval" test ! -e "$PROJECT/SHOULD_NOT_EXIST"
done
fixture mount-missing-variable
unset OCWP_SMOKE_GRADLE_HOME
cat >> "$PROJECT/opencode-sbx/opencode-sbx.yaml" <<'YAML'
extraMounts:
  - host: '${OCWP_SMOKE_GRADLE_HOME}'
    sandbox: /home/agent/.gradle-host
YAML
invoke
result mount-missing-variable test "$STATUS" -ne 0
result mount-missing-no-create test ! -e "$SMOKE_LOG/create-argv"

# Use a real PTY for the launcher while keeping sbx fake. GNU/util-linux script
# supports -e (return child exit); macOS/Git Bash still run all non-PTY cases.
if command -v script >/dev/null 2>&1 && script --help 2>&1 | grep -q -- '--return'; then
  for tty_case in terminal redirected-output piped-input acp-global; do
    fixture "pty-$tty_case"
    export SMOKE_TTY=1
    printf -v quoted '%q ' bash "$LAUNCHER"
    expected_tty=1
    case "$tty_case" in
      redirected-output) quoted="$quoted > /dev/null"; expected_tty=0 ;;
      piped-input) quoted="printf prompt | $quoted run prompt"; expected_tty=0 ;;
      acp-global) quoted="$quoted --print-logs acp"; expected_tty=0 ;;
    esac
    set +e
    (cd "$PROJECT" && script -q -e -c "$quoted" /dev/null) </dev/null > "$CASE/stdout" 2> "$CASE/stderr"
    STATUS=$?
    set -e
    actual_tty=0
    while IFS= read -r -d '' arg; do [[ "$arg" != -t ]] || actual_tty=1; done < "$SMOKE_LOG/argv"
    result "pty-$tty_case-exit" test "$STATUS" -eq 19
    result "pty-$tty_case-tty" test "$actual_tty" -eq "$expected_tty"
  done
else
  SKIPPED=4
  printf 'unrun: four PTY cases require util-linux script(1)\n'
fi
printf '\nSmoke result: %s passed, %s failed, %s unrun\n' "$PASSED" "$FAILED" "$SKIPPED"
[[ "$FAILED" -eq 0 ]]
