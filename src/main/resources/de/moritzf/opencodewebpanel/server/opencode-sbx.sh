#!/usr/bin/env bash
# OpenCode Docker Sandbox launcher (no IDE required).
#
# Same sandbox as the IntelliJ plugin: reads opencode-sbx/opencode-sbx.yaml,
# creates the VM if needed, then runs OpenCode inside it with sbx exec
# (never sbx run).
#
# Usage:
#   ./opencode-sbx/opencode-sbx.sh [--web] [directory]   Start opencode serve
#   ./opencode-sbx/opencode-sbx.sh --cli [directory]     Start the OpenCode TUI
#   ./opencode-sbx/opencode-sbx.sh --acp [directory]     Start OpenCode ACP (stdio JSON-RPC)
#   ./opencode-sbx/opencode-sbx.sh --init [directory]    Write the spec, then start
#   ./opencode-sbx/opencode-sbx.sh --recreate [--web|--cli|--acp] [directory]
#   ./opencode-sbx/opencode-sbx.sh --rm [directory]      Delete the sandbox
#   ./opencode-sbx/opencode-sbx.sh --web|--cli|--acp --oc-args [opencode-args...]
#
# Default is --web. Extra args after --oc-args are passed to opencode.
# Web prints http://127.0.0.1:<port> when healthy.
# ACP is for editor/agent hosts (stdin/stdout JSON-RPC; no TTY).
# Optional: export OPENCODE_SERVER_PASSWORD for --web basic auth (user opencode).
# No password means OpenCode's default unauthenticated serve.
# Requires Docker Sandboxes (sbx) on PATH, or set OCWP_SBX.
# Provider credentials belong to sbx secret or a separate OpenCode login in the sandbox.
#
set -euo pipefail

case "$(uname -s 2>/dev/null || true)" in
  MINGW*|MSYS*|CYGWIN*)
    # Git Bash rewrites /-leading argv and env for native programs such as sbx.exe:
    # /home/agent/... would become C:/Program Files/Git/home/agent/...
    export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' MSYS2_ENV_CONV_EXCL='*'
    ;;
esac

CONTROL_DIR="opencode-sbx"
SPEC_NAME="opencode-sbx.yaml"
IN_VM_PORT=4096
AGENT="opencode"

# Guest scripts are also read from these delimited blocks by SbxCli.
# Keep heredocs outside $(...): macOS Bash 3.2 misparses nested case patterns.
v2_install_script() {
  cat <<'OCWP_V2_INSTALL'
#!/bin/sh
# opencode.ai latest can precede this architecture's tarball; retry, then pin npm latest.
curl -fsSL --connect-timeout 15 --max-time 120 https://opencode.ai/v2/install -o /tmp/opencode-v2-install.sh || exit 1
if bash /tmp/opencode-v2-install.sh --no-modify-path && test -x "$HOME/.opencode/bin/opencode"; then exit 0; fi
sleep 2
if bash /tmp/opencode-v2-install.sh --no-modify-path && test -x "$HOME/.opencode/bin/opencode"; then exit 0; fi
os=$(uname -s | tr A-Z a-z)
case "$os" in darwin) ;; *) os=linux ;; esac
arch=$(uname -m)
case "$arch" in aarch64) arch=arm64 ;; x86_64) arch=x64 ;; esac
ver=$(curl -fsSL --connect-timeout 15 --max-time 60 "https://registry.npmjs.org/@opencode%2fcli-${os}-${arch}/latest" | sed -n "s/.*\"version\":\"\\([^\"]*\\)\".*/\\1/p" | head -1)
test -n "$ver" || exit 1
bash /tmp/opencode-v2-install.sh --no-modify-path --version "$ver" || exit $?
test -x "$HOME/.opencode/bin/opencode"
OCWP_V2_INSTALL
}

v2_version_script() {
  cat <<'OCWP_V2_VERSION'
#!/bin/sh
# Exit 44 means absent; all invalid installed binaries fail with 45.
binary="$HOME/.opencode/bin/opencode"
if [ ! -e "$binary" ] && [ ! -L "$binary" ]; then exit 44; fi
if [ ! -x "$binary" ]; then
  printf 'OpenCode 2.x binary is not executable: %s\n' "$binary" >&2
  exit 45
fi
if version=$(timeout -k 2 10 "$binary" --version 2>&1); then :; else
  status=$?
  printf 'OpenCode 2.x version check failed (exit %s): %s\n%s\n' "$status" "$binary" "$version" >&2
  exit 45
fi
if ! printf '%s\n' "$version" | grep -Eq '^(opencode[[:space:]]+)?v?2\.[0-9]+\.[0-9]+([-+][[:alnum:].-]+)?[[:space:]]*$'; then
  printf 'Expected OpenCode 2.x at %s; got:\n%s\n' "$binary" "$version" >&2
  exit 45
fi
printf '%s\n' "$version"
OCWP_V2_VERSION
}

usage() {
  echo "Usage: opencode-sbx.sh [--web|--cli|--acp] [--init] [--recreate] [--rm] [directory]" >&2
  echo "  --web   Start opencode serve for the browser (default)." >&2
  echo "  --cli   Start the OpenCode TUI in the sandbox (sbx exec, not sbx run)." >&2
  echo "  --acp   Start OpenCode ACP over stdin/stdout (sbx exec -i, no TTY)." >&2
  echo "  --init  Write opencode-sbx/opencode-sbx.yaml if missing, then start." >&2
  echo "  --recreate  Delete and recreate the sandbox, then start." >&2
  echo "  --rm    Delete the sandbox and exit." >&2
  echo "  --oc-args  Extra arguments for opencode (everything after this flag)." >&2
  echo "Optional directory: project path (default: parent of opencode-sbx/, this script's directory, or cwd)." >&2
  exit 2
}

INIT=0
RECREATE=0
REMOVE=0
MODE=""
DIR=""
OPENCODE_ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --init) INIT=1 ;;
    --recreate) RECREATE=1 ;;
    --rm) REMOVE=1 ;;
    --web|--cli|--acp)
      [[ -z "$MODE" || "$MODE" == "${1#--}" ]] || usage
      MODE="${1#--}"
      ;;
    -h|--help) usage ;;
    --oc-args) shift; OPENCODE_ARGS=("$@"); break ;;
    -*) usage ;;
    *) DIR="$1" ;;
  esac
  shift
done

MODE="${MODE:-web}"

if [[ "$MODE" == acp ]]; then
  # Preserve the editor's first JSON-RPC request. sbx setup commands can read
  # stdin even without -i; provisioning output must not enter the protocol stream.
  exec 3<&0 4>&1
  exec </dev/null 1>&2
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
if [[ -z "$DIR" ]]; then
  DIR="$(pwd)"
  if [[ -f "$SCRIPT_DIR/$SPEC_NAME" || -f "$SCRIPT_DIR/opencode.sbx.yaml" || -f "$SCRIPT_DIR/opencode-web-panel.sbx.yaml" ]]; then
    if [[ "$(basename "$SCRIPT_DIR")" == "$CONTROL_DIR" ]]; then
      DIR="$(cd "$SCRIPT_DIR/.." && pwd -P)"
    else
      DIR="$SCRIPT_DIR"
    fi
  elif [[ -f "$SCRIPT_DIR/$CONTROL_DIR/$SPEC_NAME" ]]; then
    DIR="$SCRIPT_DIR"
  fi
fi
if [[ ! -d "$DIR" ]]; then
  echo "opencode-sbx: not a directory: $DIR" >&2
  exit 1
fi
CANONICAL="$(cd "$DIR" && pwd -P)"

identity_path() {
  local p="$1"
  local os drive
  os="$(uname -s 2>/dev/null || true)"
  case "$os" in
    MINGW*|MSYS*|CYGWIN*)
      if command -v cygpath >/dev/null 2>&1; then
        p="$(cygpath -u "$p" 2>/dev/null || printf '%s' "$p")"
      fi
      p="${p//\\//}"
      if [[ "$p" =~ ^/([a-zA-Z])(/.*)$ ]]; then
        drive="$(printf '%s' "${BASH_REMATCH[1]}" | tr 'a-z' 'A-Z')"
        p="${drive}:${BASH_REMATCH[2]}"
      elif [[ "$p" =~ ^[a-zA-Z]: ]]; then
        drive="$(printf '%s' "${p:0:1}" | tr 'a-z' 'A-Z')"
        p="${drive}${p:1}"
      fi
      ;;
  esac
  printf '%s' "$p"
}

is_absolute() {
  [[ "$1" == /* || "$1" =~ ^[A-Za-z]:(/|\\) ]]
}

guest_bind_path() {
  local p="$1"
  p="${p//\\//}"
  if [[ "$p" =~ ^[A-Za-z]:/ ]]; then
    local drive
    drive="$(printf '%s' "${p:0:1}" | tr 'A-Z' 'a-z')"
    printf '/%s%s' "$drive" "${p:2}"
  else
    printf '%s' "$p"
  fi
}

hash12() {
  if command -v openssl >/dev/null 2>&1; then
    printf '%s' "$1" | openssl dgst -sha256 -r | awk '{print substr($1,1,12)}'
  elif command -v shasum >/dev/null 2>&1; then
    printf '%s' "$1" | shasum -a 256 | awk '{print substr($1,1,12)}'
  else
    printf '%s' "$1" | sha256sum | awk '{print substr($1,1,12)}'
  fi
}
CANONICAL="$(identity_path "$CANONICAL")"
NAME="ide-ocwp-$(hash12 "$CANONICAL")"

SPEC=""
for candidate in \
  "$CANONICAL/$CONTROL_DIR/$SPEC_NAME" \
  "$CANONICAL/$SPEC_NAME" \
  "$CANONICAL/opencode.sbx.yaml" \
  "$CANONICAL/opencode-web-panel.sbx.yaml" \
  "${OCWP_CONFIG_DIR:-${HOME}/.config/opencode-web-panel}/sbx/${NAME}.yaml"; do
  if [[ -f "$candidate" ]]; then
    SPEC="$candidate"
    break
  fi
done

SBX="${OCWP_SBX:-sbx}"
if ! command -v "$SBX" >/dev/null 2>&1; then
  echo "opencode-sbx: sbx not found. Install Docker Sandboxes and ensure sbx is on PATH." >&2
  exit 1
fi
if [[ "$SBX" == */* ]] && ! is_absolute "$SBX"; then
  SBX="$(pwd)/$SBX"
fi

yaml_unquote() {
  local v="$1" quote="" escaped=0 i ch
  # Only whitespace-separated hashes outside a quoted scalar begin YAML comments.
  for ((i = 0; i < ${#v}; i++)); do
    ch="${v:i:1}"
    if [[ "$escaped" -eq 1 ]]; then
      escaped=0
    elif [[ "$quote" == '"' && "$ch" == '\' ]]; then
      escaped=1
    elif [[ "$quote" == "'" && "$ch" == "'" && "${v:i+1:1}" == "'" ]]; then
      i=$((i + 1))
    elif [[ -n "$quote" ]]; then
      [[ "$ch" == "$quote" ]] && quote=""
    elif [[ "$i" -eq 0 && ( "$ch" == '"' || "$ch" == "'" ) ]]; then
      quote="$ch"
    elif [[ "$ch" == '#' ]] && { [[ "$i" -eq 0 ]] || [[ "${v:i-1:1}" == [[:space:]] ]]; }; then
      v="${v:0:i}"
      break
    fi
  done
  v="${v%"${v##*[![:space:]]}"}"
  if [[ "$v" == \"*\" && "$v" == *\" ]]; then
    v="${v#\"}"
    v="${v%\"}"
    v="${v//\\\"/\"}"
    v="${v//\\\\/\\}"
  elif [[ "$v" == \'*\' ]]; then
    v="${v#\'}"
    v="${v%\'}"
    quote="'"
    v="${v//$quote$quote/$quote}"
  fi
  printf '%s' "$v"
}

MEMORY="4g"
CPUS="2"
WORKDIR="./"
SHARE_CONFIG="false"
OPENCODE_VERSION=""
PROTECT_FILES="true"
PERSIST_SESSIONS="true"
HOST_PORT=""
KITS=()
MOUNT_HOSTS=()
MOUNT_SANDBOXES=()
MOUNT_READONLY=()

parse_flow_kits() {
  local raw="$1" inner item="" quote="" escaped=0 i ch
  inner="${raw#\[}"
  inner="${inner%\]}"
  KITS=()
  inner="${inner#"${inner%%[![:space:]]*}"}"
  inner="${inner%"${inner##*[![:space:]]}"}"
  [[ -z "$inner" ]] && return 0
  for ((i = 0; i < ${#inner}; i++)); do
    ch="${inner:i:1}"
    if [[ "$escaped" -eq 1 ]]; then
      item+="$ch"
      escaped=0
    elif [[ "$quote" == '"' && "$ch" == '\' ]]; then
      item+="$ch"
      escaped=1
    elif [[ "$quote" == "'" && "$ch" == "'" && "${inner:i+1:1}" == "'" ]]; then
      item+="''"
      i=$((i + 1))
    elif [[ -n "$quote" ]]; then
      item+="$ch"
      [[ "$ch" == "$quote" ]] && quote=""
    elif [[ "$ch" == '"' || "$ch" == "'" ]]; then
      quote="$ch"
      item+="$ch"
    elif [[ "$ch" == "," ]]; then
      item="${item#"${item%%[![:space:]]*}"}"
      item="$(yaml_unquote "$item")"
      [[ -n "$item" ]] && KITS+=("$item")
      item=""
    else
      item+="$ch"
    fi
  done
  item="${item#"${item%%[![:space:]]*}"}"
  item="$(yaml_unquote "$item")"
  [[ -n "$item" ]] && KITS+=("$item")
}

spec_error() {
  echo "opencode-sbx: invalid spec $SPEC: $*" >&2
  exit 1
}

lower() {
  printf '%s' "$1" | tr 'A-Z' 'a-z'
}

# Same rules as SbxLaunchSpec.parseYamlResult: anything outside the subset the plugin writes
# makes the spec invalid instead of being skipped. Sets FLAG_VALUE (no subshell, so
# spec_error can exit the launcher).
spec_flag() {
  FLAG_VALUE="$(lower "$2")"
  case "$FLAG_VALUE" in
    true|false) ;;
    *) spec_error "$1 must be true or false" ;;
  esac
}

finish_mount() {
  [[ "$MOUNT_OPEN" -eq 1 ]] || return 0
  MOUNT_OPEN=0
  [[ -n "$MOUNT_HOST" ]] || spec_error "every extraMounts entry needs a host"
  local host="${MOUNT_HOST//\\//}" ro="0"
  if [[ "$(lower "$host")" == *:ro && "${#host}" -gt 3 ]]; then
    host="${host:0:${#host}-3}"
    ro="1"
  fi
  case "$(lower "$MOUNT_RO")" in
    "") ;;
    true) ro="1" ;;
    false) ;;
    *) spec_error "readOnly must be true or false" ;;
  esac
  local sandbox="${MOUNT_SANDBOX//\\//}"
  [[ -n "$sandbox" ]] || sandbox="$host"
  MOUNT_HOSTS+=("$host")
  MOUNT_SANDBOXES+=("$sandbox")
  MOUNT_READONLY+=("$ro")
}

set_mount_key() {
  local key="$1" val="$2"
  case "$key" in
    host) [[ -z "$MOUNT_HOST_SET" ]] || spec_error "duplicate host in mount"; MOUNT_HOST_SET=1; MOUNT_HOST="$val" ;;
    sandbox) [[ -z "$MOUNT_SANDBOX_SET" ]] || spec_error "duplicate sandbox in mount"; MOUNT_SANDBOX_SET=1; MOUNT_SANDBOX="$val" ;;
    readOnly) [[ -z "$MOUNT_RO_SET" ]] || spec_error "duplicate readOnly in mount"; MOUNT_RO_SET=1; MOUNT_RO="$val" ;;
  esac
}

parse_spec() {
  local file="$1" section="" seen=" " line content key raw val g1 g3
  local version_raw="" install_v2=""
  MOUNT_OPEN=0
  CANONICAL_SET=0
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    line="${line%"${line##*[![:space:]]}"}"
    [[ -z "$line" ]] && continue
    [[ "$line" =~ ^([[:blank:]]*)(.*)$ ]]
    local indent_text="${BASH_REMATCH[1]}"
    content="${BASH_REMATCH[2]}"
    [[ "$content" == "#"* ]] && continue
    [[ "$indent_text" == *$'\t'* ]] && spec_error "tabs are not allowed for indentation"
    if [[ -z "$indent_text" ]]; then
      finish_mount
      [[ "$content" =~ ^([A-Za-z][A-Za-z0-9_]*):([[:space:]]+(.*))?$ ]] || spec_error "expected \"key: value\": $content"
      key="${BASH_REMATCH[1]}"
      raw="${BASH_REMATCH[3]}"
      [[ "$seen" == *" $key "* ]] && spec_error "duplicate key $key"
      seen="$seen$key "
      section="$key"
      val="$(yaml_unquote "$raw")"
      case "$key" in
        kits)
          if [[ "$raw" == "["* ]]; then
            [[ "$raw" == *"]" ]] || spec_error "invalid kits list"
            parse_flow_kits "$raw"
          elif [[ -n "$val" ]]; then
            spec_error "kits must be a list"
          fi
          ;;
        extraMounts)
          [[ -z "$val" || "$val" == "[]" ]] || spec_error "extraMounts must be a block list of host/sandbox entries"
          ;;
        setupCommands|networkAllows|networkAllowPresets|extraNetworkAllows) ;;
        *)
          case "$val" in
            "|"|">"|"{"*|"["*) spec_error "$key must be a plain value" ;;
          esac
          case "$key" in
            schemaVersion) [[ "$val" == 1 ]] || spec_error "unsupported schemaVersion $val" ;;
            canonicalDirectory) CANONICAL="$val"; CANONICAL_SET=1 ;;
            workingDirectory) WORKDIR="$val" ;;
            name) [[ -n "$val" ]] && NAME="$val" ;;
            memory)
              val="$(lower "$val")"
              [[ "$val" =~ ^[1-9][0-9]*[gm]$ ]] || spec_error "memory must look like 4g or 512m"
              MEMORY="$val"
              ;;
            cpus)
              [[ "$val" =~ ^[0-9]+$ ]] && [[ "$((10#$val))" -ge 1 && "$((10#$val))" -le 32 ]] || spec_error "cpus must be 1 to 32"
              CPUS="$((10#$val))"
              ;;
            hostPort)
              if [[ -n "$val" ]]; then
                [[ "$val" =~ ^[0-9]+$ ]] && [[ "$((10#$val))" -ge 1 && "$((10#$val))" -le 65535 ]] || spec_error "hostPort must be 1 to 65535"
                HOST_PORT="$((10#$val))"
              fi
              ;;
            shareHostOpencodeConfig) spec_flag "$key" "$val"; SHARE_CONFIG="$FLAG_VALUE" ;;
            protectSandboxFiles) spec_flag "$key" "$val"; PROTECT_FILES="$FLAG_VALUE" ;;
            persistSandboxSessions) spec_flag "$key" "$val"; PERSIST_SESSIONS="$FLAG_VALUE" ;;
            useSandbox|enableIntellijMcp) spec_flag "$key" "$val" ;;
            installOpenCodeV2) spec_flag "$key" "$val"; install_v2="$FLAG_VALUE" ;;
            openCodeVersion)
              version_raw="$(lower "$val")"
              case "$version_raw" in
                1|1.x|v1|2|2.x|v2) ;;
                *) spec_error "openCodeVersion must be 1.x or 2.x" ;;
              esac
              ;;
          esac
          ;;
      esac
      continue
    fi
    case "$section" in
      "") spec_error "indented line outside a list: $content" ;;
      kits)
        [[ "$content" =~ ^-[[:space:]]+(.*)$ ]] || spec_error "expected \"- kit\": $content"
        val="$(yaml_unquote "${BASH_REMATCH[1]}")"
        [[ -n "$val" ]] && KITS+=("$val")
        ;;
      extraMounts)
        if [[ "$content" =~ ^-[[:space:]]+(host|sandbox|readOnly):([[:space:]]+(.*))?$ ]]; then
          g1="${BASH_REMATCH[1]}"
          g3="${BASH_REMATCH[3]}"
          finish_mount
          MOUNT_OPEN=1
          MOUNT_HOST="" MOUNT_SANDBOX="" MOUNT_RO=""
          MOUNT_HOST_SET="" MOUNT_SANDBOX_SET="" MOUNT_RO_SET=""
        elif [[ "$content" =~ ^(host|sandbox|readOnly):([[:space:]]+(.*))?$ ]]; then
          g1="${BASH_REMATCH[1]}"
          g3="${BASH_REMATCH[3]}"
          [[ "$MOUNT_OPEN" -eq 1 ]] || spec_error "mount entries start with \"- host:\""
        else
          spec_error "expected host, sandbox or readOnly: $content"
        fi
        set_mount_key "$g1" "$(yaml_unquote "$g3")"
        ;;
      setupCommands|networkAllows|networkAllowPresets|extraNetworkAllows) ;;
      schemaVersion|canonicalDirectory|workingDirectory|name|memory|cpus|openCodeVersion|hostPort|shareHostOpencodeConfig|protectSandboxFiles|persistSandboxSessions|useSandbox|enableIntellijMcp|installOpenCodeV2)
        spec_error "$section must be a plain value"
        ;;
    esac
  done < "$file"
  finish_mount
  [[ "$CANONICAL_SET" -eq 1 && -n "$CANONICAL" ]] || spec_error "canonicalDirectory is required"
  case "$version_raw" in
    2|2.x|v2) OPENCODE_VERSION="2.x" ;;
    1|1.x|v1) OPENCODE_VERSION="1.x" ;;
    *) [[ "$install_v2" == true ]] && OPENCODE_VERSION="2.x" || OPENCODE_VERSION="1.x" ;;
  esac
  local i
  for ((i = 0; i < ${#KITS[@]}; i++)); do
    KITS[$i]="${KITS[$i]//\\//}"
    if [[ "${KITS[$i]}" == "~" || "${KITS[$i]}" == "~/"* ]]; then
      KITS[$i]="${HOME}${KITS[$i]:1}"
    fi
  done
}

write_init_spec() {
  mkdir -p "$CANONICAL/$CONTROL_DIR"
  cat > "$CANONICAL/$CONTROL_DIR/$SPEC_NAME" <<EOF
schemaVersion: 1
canonicalDirectory: ./
workingDirectory: ./
name: $NAME
memory: 4g
cpus: "2"
kits: []
extraMounts: []
shareHostOpencodeConfig: false
openCodeVersion: 1.x
enableIntellijMcp: true
protectSandboxFiles: true
persistSandboxSessions: true
EOF
}

if [[ "$INIT" -eq 1 ]]; then
  if [[ -n "$SPEC" ]]; then
    echo "opencode-sbx: spec already exists at $SPEC" >&2
  else
    write_init_spec
    echo "Wrote $CANONICAL/$CONTROL_DIR/$SPEC_NAME" >&2
    SPEC="$CANONICAL/$CONTROL_DIR/$SPEC_NAME"
  fi
fi

if [[ -z "$SPEC" || ! -f "$SPEC" ]]; then
  echo "opencode-sbx: no $CONTROL_DIR/$SPEC_NAME in $CANONICAL (and no machine copy for $NAME)." >&2
  echo "Ask a teammate to commit $CONTROL_DIR/$SPEC_NAME, or run: opencode-sbx.sh --init" >&2
  exit 1
fi
parse_spec "$SPEC"
OPENCODE_VERSION="${OPENCODE_VERSION:-1.x}"

spec_dir="$(cd "$(dirname "$SPEC")" && pwd -P)"
if [[ "$(basename "$spec_dir")" == "$CONTROL_DIR" && "$(basename "$SPEC")" == "$SPEC_NAME" ]]; then
  spec_base="$(cd "$spec_dir/.." && pwd -P)"
else
  spec_base="$spec_dir"
fi
if ! is_absolute "$CANONICAL"; then
  resolved="$(cd "$spec_base/$CANONICAL" && pwd -P)" || exit 1
  CANONICAL="$(identity_path "$resolved")"
  NAME="ide-ocwp-$(hash12 "$CANONICAL")"
else
  resolved="$(cd "$CANONICAL" && pwd -P)" || exit 1
  CANONICAL="$(identity_path "$resolved")"
  if [[ "$CANONICAL" != "$(identity_path "$spec_base")" ]]; then
    echo "opencode-sbx: note: $SPEC mounts $CANONICAL read-write as the workspace." >&2
  fi
fi
WORKDIR="${WORKDIR//\\//}"
if [[ -z "$WORKDIR" || "$WORKDIR" == "~"* || "$WORKDIR" =~ ^[A-Za-z]: ]] || is_absolute "$WORKDIR"; then
  spec_error "workingDirectory must be a relative path inside the workspace"
fi
if [[ ! -d "$CANONICAL/$WORKDIR" ]]; then
  spec_error "workingDirectory does not exist inside the workspace: $WORKDIR"
fi
resolved_workdir="$(cd "$CANONICAL/$WORKDIR" && pwd -P)"
resolved_workdir="$(identity_path "$resolved_workdir")"
case "$resolved_workdir" in
  "$CANONICAL"|"$CANONICAL/"*) WORKDIR="$resolved_workdir" ;;
  *) spec_error "workingDirectory must be inside the workspace: $WORKDIR" ;;
esac
# NAME is joined into host paths below (persist, 2.x binary, --recreate cleanup).
# Same rule as SbxCli.isValidSandboxName.
case "$NAME" in
  [Dd][Ee][Ff][Aa][Uu][Ll][Tt]) valid_name=0 ;;
  *) valid_name=1 ;;
esac
if [[ "$valid_name" -ne 1 || ! "$NAME" =~ ^[A-Za-z0-9][A-Za-z0-9.-]+$ ]]; then
  echo "opencode-sbx: invalid sandbox name in spec: $NAME" >&2
  exit 1
fi
# Local kit and mount paths are project-relative, including for the machine launcher.
cd "$CANONICAL"

# Inventory reads below need a running daemon; a stopped one is not proof of absence.
"$SBX" daemon start >/dev/null 2>&1 || true

sandbox_json() {
  "$SBX" ls --json 2>/dev/null || true
}

json_sandbox_objects() {
  printf '%s' "$1" | awk '
    {
      line = line $0
    }
    END {
      start = index(line, "\"sandboxes\"")
      if (start == 0) exit
      rest = substr(line, start)
      br = index(rest, "[")
      if (br == 0) exit
      rest = substr(rest, br + 1)
      depth = 0
      obj = ""
      in_str = 0
      esc = 0
      for (i = 1; i <= length(rest); i++) {
        c = substr(rest, i, 1)
        if (in_str) {
          obj = obj c
          if (esc) esc = 0
          else if (c == "\\") esc = 1
          else if (c == "\"") in_str = 0
          continue
        }
        if (c == "\"") { in_str = 1; if (depth > 0) obj = obj c; continue }
        if (c == "{") { depth++; obj = obj c; continue }
        if (c == "}") {
          obj = obj c
          depth--
          if (depth == 0 && obj != "") { print obj; obj = "" }
          continue
        }
        if (depth > 0) obj = obj c
        if (c == "]" && depth == 0) break
      }
    }
  '
}

sandbox_entry_for_name() {
  local name="$1" json obj needle
  if [[ $# -ge 2 ]]; then json="$2"; else json="$(sandbox_json)"; fi
  needle="\"$name\""
  while IFS= read -r obj; do
    [[ -z "$obj" ]] && continue
    case "$obj" in
      *"\"name\":${needle}"*|*"\"name\": ${needle}"*)
        printf '%s' "$obj"
        return 0
        ;;
    esac
  done < <(json_sandbox_objects "$json")
  return 1
}

# sbx prints Go JSON: `&` is \u0026 and Windows paths use escaped backslashes.
json_unescape_paths() {
  printf '%s' "$1" | sed -e 's/\\u0026/\&/g' -e 's/\\u003c/</g' -e 's/\\u003e/>/g' \
    -e 's#\\/#/#g' -e 's#\\\\#/#g'
}

entry_has_workspace() {
  local obj path needle
  obj="$(json_unescape_paths "$1")"
  path="${2//\\//}"
  needle="\"$path\""
  case "$obj" in
    *"${needle},"*|*"${needle}]"*|*"${needle} "* ) return 0 ;;
  esac
  return 1
}

workspace_has() {
  local obj
  obj="$(sandbox_entry_for_name "$NAME")" || return 1
  entry_has_workspace "$obj" "$1"
}

if [[ "$REMOVE" -eq 1 ]]; then
  if ! workspace_has "$CANONICAL"; then
    echo "opencode-sbx: no owned sandbox $NAME for $CANONICAL" >&2
    exit 1
  fi
  if ! "$SBX" rm --force "$NAME"; then
    echo "opencode-sbx: could not remove sandbox $NAME" >&2
    exit 1
  fi
  echo "Removed sandbox $NAME"
  exit 0
fi

if [[ "$RECREATE" -eq 1 ]]; then
  # Inventory errors are not proof that the VM is absent. Only drop its persisted
  # binary after an owned VM was removed successfully, or verified absent.
  recreate_inventory="$("$SBX" ls --json)" || exit 1
  if [[ "$recreate_inventory" != *'"sandboxes"'* ]]; then
    echo "opencode-sbx: cannot verify sandbox ownership before recreate." >&2
    exit 1
  fi
  if recreate_entry="$(sandbox_entry_for_name "$NAME" "$recreate_inventory")"; then
    if ! entry_has_workspace "$recreate_entry" "$CANONICAL"; then
      echo "opencode-sbx: refusing to recreate sandbox $NAME for another workspace." >&2
      exit 1
    fi
    if ! "$SBX" rm --force "$NAME"; then
      echo "opencode-sbx: could not remove sandbox $NAME; persisted OpenCode kept." >&2
      exit 1
    fi
  fi
fi

serve_env=()
PASSWORD="${OPENCODE_SERVER_PASSWORD:-}"
if [[ "$MODE" == web && -n "$PASSWORD" ]]; then
  export OPENCODE_SERVER_PASSWORD="$PASSWORD"
  serve_env+=( -e OPENCODE_SERVER_PASSWORD )
fi
HOST_CONFIG_DIR="${XDG_CONFIG_HOME:-${HOME}/.config}/opencode"
HOST_CONFIG_DIR="${HOST_CONFIG_DIR//\\//}"
PERSIST_GUEST="/home/agent/.local/share/opencode"
GUEST_OPENCODE_GUEST="/home/agent/.opencode"
uname_s="$(uname -s 2>/dev/null || true)"
if [[ -n "${OCWP_DATA_DIR:-}" ]]; then
  PERSIST_ROOT="${OCWP_DATA_DIR//\\//}"
elif [[ "$uname_s" == MINGW* || "$uname_s" == MSYS* || "$uname_s" == CYGWIN* ]]; then
  PERSIST_ROOT="${LOCALAPPDATA:-$HOME}/opencode-web-panel"
  PERSIST_ROOT="${PERSIST_ROOT//\\//}"
else
  PERSIST_ROOT="${XDG_DATA_HOME:-${HOME}/.local/share}/opencode-web-panel"
fi
PERSIST_HOME="$PERSIST_ROOT/sbx/$NAME"
GUEST_OPENCODE_HOME="$PERSIST_ROOT/sbx-opencode/$NAME"
if [[ "$RECREATE" -eq 1 ]]; then
  rm -rf -- "$GUEST_OPENCODE_HOME"
fi
if [[ "$PERSIST_SESSIONS" == "true" ]]; then
  mkdir -p -- "$PERSIST_HOME"
fi
if [[ "$OPENCODE_VERSION" == "2.x" ]]; then
  mkdir -p -- "$GUEST_OPENCODE_HOME"
fi

resolve_host_path() {
  local host="$1"
  if [[ "$host" == "~"* ]]; then
    host="${HOME}${host:1}"
  fi
  if ! is_absolute "$host"; then
    host="$CANONICAL/${host#./}"
  fi
  if [[ -d "$host" ]]; then
    host="$(identity_path "$(cd "$host" && pwd -P)")"
  elif [[ -e "$host" ]]; then
    host="$(identity_path "$(cd "$(dirname -- "$host")" && pwd -P)/$(basename -- "$host")")"
  fi
  printf '%s' "$host"
}

# Same guest scripts as SbxCli.extraMountLinkScript. The persist/2.x replace variant copies
# guest data onto the host only when the host store is still empty.
link_mount() {
  local host sandbox replace script
  host="$(guest_bind_path "$1")"
  sandbox="$2"
  replace="${3:-}"
  if [[ "$replace" == replace ]]; then
    script='mkdir -p -- "$(dirname -- "$2")"
if [ -d "$2" ] && [ ! -L "$2" ]; then
  mkdir -p -- "$1"
  if [ -z "$(ls -A -- "$1" 2>/dev/null)" ]; then
    cp -a -- "$2"/. "$1"/ || exit 1
  fi
  rm -rf -- "$2"
fi
ln -sfn -- "$1" "$2"'
  else
    script='mkdir -p -- "$(dirname -- "$2")" || exit 1
if [ -d "$2" ] && [ ! -L "$2" ]; then
  echo "opencode-link: $2 already exists as a directory in the sandbox" >&2
  exit 1
fi
ln -sfn -- "$1" "$2"'
  fi
  "$SBX" exec -w / "$NAME" sh -c "$script" opencode-link "$host" "$sandbox"
}

# Resolve mounts like SbxCli.resolveExtraMounts: the same raw host and sandbox path means
# "mount as-is" (no link); `~` and relative sandbox paths are under the guest home.
for ((i = 0; i < ${#MOUNT_HOSTS[@]}; i++)); do
  raw_host="${MOUNT_HOSTS[$i]}"
  raw_sandbox="${MOUNT_SANDBOXES[$i]}"
  host="$(resolve_host_path "$raw_host")"
  if [[ -z "$raw_sandbox" || "$raw_sandbox" == "$raw_host" ]]; then
    sandbox="$host"
  else
    sandbox="$raw_sandbox"
    if [[ "$sandbox" == "~" || "$sandbox" == "~/"* ]]; then
      sandbox="/home/agent${sandbox:1}"
    elif [[ "$sandbox" != /* ]]; then
      sandbox="/home/agent/${sandbox#./}"
    fi
  fi
  MOUNT_HOSTS[$i]="$host"
  MOUNT_SANDBOXES[$i]="$sandbox"
done

EXISTING_ENTRY=""
if EXISTING_ENTRY="$(sandbox_entry_for_name "$NAME")"; then
  if ! entry_has_workspace "$EXISTING_ENTRY" "$CANONICAL"; then
    echo "opencode-sbx: sandbox $NAME exists for another workspace; refusing to use it." >&2
    exit 1
  fi
fi

CREATED=0
SHARE_MOUNTED=0
if [[ -z "$EXISTING_ENTRY" ]]; then
  CREATED=1
  PUBLISH="${IN_VM_PORT}/tcp4"
  if [[ "$HOST_PORT" =~ ^[1-9][0-9]*$ ]] && [[ "$HOST_PORT" -le 65535 ]]; then
    PUBLISH="127.0.0.1:${HOST_PORT}:${IN_VM_PORT}/tcp4"
  fi
  create=( "$SBX" create -q --name "$NAME" --memory "$MEMORY" --cpus "$CPUS" --publish "$PUBLISH" )
  for kit in "${KITS[@]+"${KITS[@]}"}"; do
    [[ -n "$kit" ]] && create+=( --kit "$kit" )
  done
  create+=( "$AGENT" "$CANONICAL" )
  if [[ "$PROTECT_FILES" == "true" ]]; then
    if [[ -d "$CANONICAL/$CONTROL_DIR" && "$CANONICAL/$CONTROL_DIR" != "$CANONICAL" ]]; then
      create+=( "$CANONICAL/$CONTROL_DIR:ro" )
    fi
    for kit in "${KITS[@]+"${KITS[@]}"}"; do
      [[ -z "$kit" ]] && continue
      case "$kit" in
        git+*|*'://'*) continue ;;
        .|..|./*|../*|/*|~*) ;;
        [A-Za-z]:/*) ;;
        *) continue ;;
      esac
      kit_host="$(resolve_host_path "$kit")"
      case "$kit_host" in
        "$CANONICAL/$CONTROL_DIR"|"$CANONICAL/$CONTROL_DIR"/*) continue ;;
      esac
      if [[ -d "$kit_host" && "$kit_host" != "$CANONICAL" ]]; then
        create+=( "$kit_host:ro" )
      fi
    done
  fi
  if [[ "$PERSIST_SESSIONS" == "true" && -n "${PERSIST_HOME:-}" && -d "$PERSIST_HOME" && "$PERSIST_HOME" != "$CANONICAL" ]]; then
    create+=( "$PERSIST_HOME" )
  fi
  if [[ "$OPENCODE_VERSION" == "2.x" && -n "${GUEST_OPENCODE_HOME:-}" && -d "$GUEST_OPENCODE_HOME" && "$GUEST_OPENCODE_HOME" != "$CANONICAL" ]]; then
    create+=( "$GUEST_OPENCODE_HOME" )
  fi
  seen_mounts=$'\n'
  for ((i = 0; i < ${#MOUNT_HOSTS[@]}; i++)); do
    host="${MOUNT_HOSTS[$i]}"
    # First entry per host wins, like SbxCli.extraMountCreateArgs.
    [[ "$seen_mounts" == *$'\n'"$host"$'\n'* ]] && continue
    seen_mounts="$seen_mounts$host"$'\n'
    if [[ -n "$host" && "$host" != "$CANONICAL" && -e "$host" ]]; then
      if [[ "${MOUNT_READONLY[$i]:-0}" == "1" ]]; then
        create+=( "$host:ro" )
      else
        create+=( "$host" )
      fi
    else
      echo "opencode-sbx: skipping mount $host: it does not exist on this machine." >&2
    fi
  done
  # The shared config is mounted exactly at its host spelling (no symlink resolution), so
  # XDG_CONFIG_HOME below names the same path inside the VM.
  if [[ "$SHARE_CONFIG" == "true" && -d "$HOST_CONFIG_DIR" ]]; then
    create+=( "$HOST_CONFIG_DIR:ro" )
    SHARE_MOUNTED=1
  fi
  "${create[@]}"
elif [[ "$SHARE_CONFIG" == "true" ]] && {
  entry_has_workspace "$EXISTING_ENTRY" "$HOST_CONFIG_DIR" || entry_has_workspace "$EXISTING_ENTRY" "$HOST_CONFIG_DIR:ro"
}; then
  SHARE_MOUNTED=1
elif [[ "$SHARE_CONFIG" == "true" ]]; then
  echo "opencode-sbx: this sandbox was created without the shared OpenCode config; run with --recreate to mount it." >&2
fi
if [[ "$SHARE_MOUNTED" -eq 1 ]]; then
  export XDG_CONFIG_HOME="$(guest_bind_path "${HOST_CONFIG_DIR%/opencode}")"
  serve_env+=( -e XDG_CONFIG_HOME )
fi

for ((i = 0; i < ${#MOUNT_HOSTS[@]}; i++)); do
  host="${MOUNT_HOSTS[$i]}"
  sandbox="${MOUNT_SANDBOXES[$i]}"
  [[ -n "$sandbox" && "$host" != "$sandbox" && -e "$host" ]] || continue
  if [[ "$CREATED" -eq 1 ]] || entry_has_workspace "$EXISTING_ENTRY" "$host" || entry_has_workspace "$EXISTING_ENTRY" "$host:ro"; then
    link_mount "$host" "$sandbox"
  fi
done

if [[ "$PERSIST_SESSIONS" == "true" && -n "${PERSIST_HOME:-}" && -d "$PERSIST_HOME" ]]; then
  if [[ "$CREATED" -eq 1 ]] || workspace_has "$PERSIST_HOME"; then
    link_mount "$PERSIST_HOME" "$PERSIST_GUEST" replace
  fi
fi
if [[ "$OPENCODE_VERSION" == "2.x" && -n "${GUEST_OPENCODE_HOME:-}" && -d "$GUEST_OPENCODE_HOME" ]]; then
  if [[ "$CREATED" -eq 1 ]] || workspace_has "$GUEST_OPENCODE_HOME"; then
    link_mount "$GUEST_OPENCODE_HOME" "$GUEST_OPENCODE_GUEST" replace
  fi
fi

# A fixed hostPort edited after creation: publish it, then drop the other loopback mappings,
# like the plugin's live port apply. Mappings on other addresses are left alone.
port_mappings() {
  "$SBX" ports "$NAME" --json 2>/dev/null | tr -d '\n' | tr '}' '\n' |
    sed -n 's/.*"host_ip"[^"]*"\([^"]*\)".*"host_port"[^0-9]*\([0-9][0-9]*\).*"sandbox_port"[^0-9]*\([0-9][0-9]*\).*"protocol"[^"]*"\([^"]*\)".*/\1 \2 \3 \4/p'
}
if [[ "$MODE" == web && "$CREATED" -eq 0 && -n "$HOST_PORT" ]]; then
  mappings="$(port_mappings || true)"
  if ! printf '%s\n' "$mappings" | grep -q "^127\.0\.0\.1 $HOST_PORT $IN_VM_PORT "; then
    if "$SBX" ports "$NAME" --publish "127.0.0.1:${HOST_PORT}:${IN_VM_PORT}/tcp4" >/dev/null; then
      printf '%s\n' "$mappings" | while read -r ip port guest proto; do
        [[ "$ip" == 127.0.0.1 && "$guest" == "$IN_VM_PORT" && "$port" != "$HOST_PORT" ]] || continue
        "$SBX" ports "$NAME" --unpublish "${ip}:${port}:${guest}/${proto:-tcp4}" >/dev/null || true
      done
    else
      echo "opencode-sbx: could not publish 127.0.0.1:${HOST_PORT}; keeping the current port mapping." >&2
    fi
  fi
fi

CURL_PASSWORD="$(printf '%s' "$PASSWORD" | sed 's/[\\"]/\\&/g')"
healthy() {
  local url="$1" body
  if [[ -n "$PASSWORD" ]]; then
    # Credentials go through a curl config on stdin, not argv (visible in ps).
    body="$(printf 'user = "opencode:%s"\n' "$CURL_PASSWORD" |
      curl -fsS --connect-timeout 1 --max-time 2 -K - "$url" 2>/dev/null)" || return 1
  else
    body="$(curl -fsS --connect-timeout 1 --max-time 2 "$url" 2>/dev/null)" || return 1
  fi
  # CLI 2 serves SPA HTML with HTTP 200 on /global/health. Require a JSON
  # object and the endpoint's identity fields before publishing the URL.
  body="$(printf '%s' "$body" | tr -d '\r\n')"
  printf '%s' "$body" | grep -Eq '^[[:space:]]*\{.*\}[[:space:]]*$' || return 1
  case "$url" in
    */api/info|*/api/status)
      printf '%s' "$body" | grep -Eq '"pid"[[:space:]]*:[[:space:]]*[0-9]+[[:space:]]*[,}]' &&
        printf '%s' "$body" | grep -Eq '"version"[[:space:]]*:[[:space:]]*"[^"[:space:]]+"'
      ;;
    *) printf '%s' "$body" | grep -Eq '"healthy"[[:space:]]*:[[:space:]]*true[[:space:]]*[,}]' ;;
  esac
}

print_url() {
  local i json port
  for ((i = 0; i < 90; i++)); do
    json="$("$SBX" ports "$NAME" --json 2>/dev/null || true)"
    for port in $(printf '%s' "$json" | tr ',' '\n' | sed -n 's/.*"host_port"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p'); do
      if healthy "http://127.0.0.1:${port}/global/health" ||
         healthy "http://127.0.0.1:${port}/api/info" ||
         healthy "http://127.0.0.1:${port}/api/status" ||
         healthy "http://127.0.0.1:${port}/api/health"; then
        if [[ -n "$PASSWORD" ]]; then
          echo "OpenCode: http://127.0.0.1:${port}  (user opencode)"
        else
          echo "OpenCode: http://127.0.0.1:${port}"
        fi
        return 0
      fi
    done
    sleep 1
  done
  echo "opencode-sbx: serve started but health did not respond in time. Check: sbx ports $NAME --json" >&2
}

if [[ "$OPENCODE_VERSION" == "2.x" ]]; then
  guest_v2_version_script="$(v2_version_script)"
  if "$SBX" exec -w / "$NAME" sh -c "$guest_v2_version_script" >/dev/null; then
    :
  else
    version_status=$?
    if [[ "$version_status" -ne 44 ]]; then
      echo "opencode-sbx: guest OpenCode 2.x validation failed. Reinstall OpenCode 2.x in the sandbox, then retry." >&2
      exit 1
    fi
    echo "opencode-sbx: installing OpenCode 2.x…" >&2
    guest_v2_install_script="$(v2_install_script)"
    "$SBX" exec -w / "$NAME" sh -c "$guest_v2_install_script"
    "$SBX" exec -w / "$NAME" sh -c "$guest_v2_version_script" >/dev/null || {
      echo "opencode-sbx: installer did not produce a runnable OpenCode 2.x binary." >&2
      exit 1
    }
  fi
fi

# 2.x requires the validated $HOME/.opencode/bin; 1.x always uses kit `opencode`.
# $HOME / $@ are evaluated in the guest by sh -c, not on the host.
guest_opencode=(opencode)
if [[ "$OPENCODE_VERSION" == "2.x" ]]; then
  guest_opencode_dispatch='exec "$HOME/.opencode/bin/opencode" "$@"'
  guest_opencode=(sh -c "$guest_opencode_dispatch" opencode)
fi
opencode_cmd=()
if [[ "$MODE" == web ]]; then
  opencode_cmd+=( serve --hostname 0.0.0.0 --port "$IN_VM_PORT" --print-logs )
elif [[ "$MODE" == acp ]]; then
  opencode_cmd+=( acp )
fi
if [[ ${#OPENCODE_ARGS[@]} -gt 0 ]]; then
  opencode_cmd+=( "${OPENCODE_ARGS[@]}" )
fi

launch=( "$SBX" exec )
if [[ "$MODE" == web ]]; then
  print_url &
else
  launch+=( -i )
  if [[ "$MODE" == cli && -t 1 ]]; then
    launch+=( -t )
  fi
fi
if [[ ${#serve_env[@]} -gt 0 ]]; then
  launch+=( "${serve_env[@]}" )
fi
launch+=( -w "$(guest_bind_path "$WORKDIR")" "$NAME" "${guest_opencode[@]}" ${opencode_cmd[@]+"${opencode_cmd[@]}"} )
if [[ "$MODE" == acp ]]; then
  exec 0<&3 1>&4 3<&- 4>&-
fi
exec "${launch[@]}"
