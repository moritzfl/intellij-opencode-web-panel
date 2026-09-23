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
curl -fsSL https://opencode.ai/v2/install -o /tmp/opencode-v2-install.sh || exit 1
if bash /tmp/opencode-v2-install.sh --no-modify-path && test -x "$HOME/.opencode/bin/opencode"; then exit 0; fi
sleep 2
if bash /tmp/opencode-v2-install.sh --no-modify-path && test -x "$HOME/.opencode/bin/opencode"; then exit 0; fi
os=$(uname -s | tr A-Z a-z)
case "$os" in darwin) ;; *) os=linux ;; esac
arch=$(uname -m)
case "$arch" in aarch64) arch=arm64 ;; x86_64) arch=x64 ;; esac
ver=$(curl -fsSL "https://registry.npmjs.org/@opencode%2fcli-${os}-${arch}/latest" | sed -n "s/.*\"version\":\"\\([^\"]*\\)\".*/\\1/p" | head -1)
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
  printf '%s' "$1" | openssl dgst -sha256 -r | awk '{print substr($1,1,12)}'
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

parse_spec() {
  local file="$1"
  local list=""
  local host=""
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%"${line##*[![:space:]]}"}"
    [[ -z "$line" ]] && continue
    if [[ "$line" =~ ^[[:space:]]*-[[:space:]]*host:[[:space:]]*(.*)$ ]]; then
      host="$(yaml_unquote "${BASH_REMATCH[1]}")"
      list="extraMounts"
      continue
    fi
    if [[ "$line" =~ ^[[:space:]]*sandbox:[[:space:]]*(.*)$ && -n "$host" ]]; then
      MOUNT_HOSTS+=("$host")
      MOUNT_SANDBOXES+=("$(yaml_unquote "${BASH_REMATCH[1]}")")
      MOUNT_READONLY+=("0")
      host=""
      continue
    fi
    if [[ "$line" =~ ^[[:space:]]*-[[:space:]]+(.*)$ && -n "$list" && "$list" != extraMounts ]]; then
      local item
      item="$(yaml_unquote "${BASH_REMATCH[1]}")"
      case "$list" in
        kits) KITS+=("$item") ;;
      esac
      continue
    fi
    if [[ "$line" =~ ^([A-Za-z][A-Za-z0-9_]*):[[:space:]]*(.*)$ ]]; then
      local key="${BASH_REMATCH[1]}"
      local raw="${BASH_REMATCH[2]}"
      raw="${raw%"${raw##*[![:space:]]}"}"
      local val
      val="$(yaml_unquote "$raw")"
      list="$key"
      host=""
      case "$key" in
        canonicalDirectory) CANONICAL="$val" ;;
        name) NAME="$val" ;;
        memory) MEMORY="$val" ;;
        cpus) CPUS="$val" ;;
        hostPort) HOST_PORT="$val" ;;
        shareHostOpencodeConfig) SHARE_CONFIG="$val" ;;
        openCodeVersion)
          case "$val" in
            2|2.x|v2|V2) OPENCODE_VERSION="2.x" ;;
            *) OPENCODE_VERSION="1.x" ;;
          esac
          ;;
        installOpenCodeV2)
          if [[ -z "$OPENCODE_VERSION" ]]; then
            if [[ "$val" == "true" ]]; then
              OPENCODE_VERSION="2.x"
            else
              OPENCODE_VERSION="1.x"
            fi
          fi
          ;;
        protectSandboxFiles) PROTECT_FILES="$val" ;;
        persistSandboxSessions) PERSIST_SESSIONS="$val" ;;
        kits)
          if [[ "$raw" == "["* ]]; then
            if [[ "$raw" != *"]" ]]; then
              echo "opencode-sbx: invalid kits list in spec" >&2
              exit 1
            fi
            parse_flow_kits "$raw"
          fi
          ;;
        setupCommands|networkAllows|networkAllowPresets|extraNetworkAllows|extraMounts) ;;
      esac
    fi
  done < "$file"
}

write_init_spec() {
  mkdir -p "$CANONICAL/$CONTROL_DIR"
  cat > "$CANONICAL/$CONTROL_DIR/$SPEC_NAME" <<EOF
schemaVersion: 1
canonicalDirectory: ./
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
fi
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

entry_has_workspace() {
  local obj="$1" path="$2" needle
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

"$SBX" daemon start >/dev/null 2>&1 || true

serve_env=()
PASSWORD="${OPENCODE_SERVER_PASSWORD:-}"
if [[ "$MODE" == web && -n "$PASSWORD" ]]; then
  export OPENCODE_SERVER_PASSWORD="$PASSWORD"
  serve_env+=( -e OPENCODE_SERVER_PASSWORD )
fi
HOST_CONFIG_DIR="${XDG_CONFIG_HOME:-${HOME}/.config}/opencode"
if [[ "$SHARE_CONFIG" == "true" ]]; then
  export XDG_CONFIG_HOME="$(guest_bind_path "${HOST_CONFIG_DIR%/opencode}")"
  serve_env+=( -e XDG_CONFIG_HOME )
fi
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

link_mount() {
  local host sandbox replace script
  host="$(guest_bind_path "$1")"
  sandbox="$2"
  replace="${3:-}"
  if [[ "$replace" == replace ]]; then
    script='mkdir -p -- "$(dirname -- "$2")"
if [ -d "$2" ] && [ ! -L "$2" ]; then
  mkdir -p -- "$1"
  cp -a -- "$2"/. "$1"/ || exit 1
  rm -rf -- "$2"
fi
ln -sfn -- "$1" "$2"'
  else
    script='mkdir -p -- "$(dirname -- "$2")" && ln -sfn -- "$1" "$2"'
  fi
  "$SBX" exec "$NAME" sh -c "$script" opencode-link "$host" "$sandbox"
}

CREATED=0
if ! sandbox_entry_for_name "$NAME" >/dev/null; then
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
  if [[ "$SHARE_CONFIG" == "true" && -d "$HOST_CONFIG_DIR" ]]; then
    MOUNT_HOSTS+=("$HOST_CONFIG_DIR")
    MOUNT_SANDBOXES+=("$HOST_CONFIG_DIR")
    MOUNT_READONLY+=("1")
  fi
  for ((i = 0; i < ${#MOUNT_HOSTS[@]}; i++)); do
    host="$(resolve_host_path "${MOUNT_HOSTS[$i]}")"
    MOUNT_HOSTS[$i]="$host"
    if [[ -n "$host" && "$host" != "$CANONICAL" && -e "$host" ]]; then
      if [[ "${MOUNT_READONLY[$i]:-0}" == "1" ]]; then
        create+=( "$host:ro" )
      else
        create+=( "$host" )
      fi
    fi
  done
  "${create[@]}"
  for ((i = 0; i < ${#MOUNT_HOSTS[@]}; i++)); do
    host="${MOUNT_HOSTS[$i]}"
    sandbox="${MOUNT_SANDBOXES[$i]}"
    if [[ -n "$sandbox" && "$host" != "$sandbox" ]]; then
      link_mount "$host" "$sandbox"
    fi
  done
fi

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

healthy() {
  local url="$1" body
  if [[ -n "$PASSWORD" ]]; then
    body="$(curl -fsS --connect-timeout 1 --max-time 2 -u "opencode:${PASSWORD}" "$url" 2>/dev/null)" || return 1
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
  if "$SBX" exec "$NAME" sh -c "$guest_v2_version_script" >/dev/null; then
    :
  else
    version_status=$?
    if [[ "$version_status" -ne 44 ]]; then
      echo "opencode-sbx: guest OpenCode 2.x validation failed. Reinstall OpenCode 2.x in the sandbox, then retry." >&2
      exit 1
    fi
    echo "opencode-sbx: installing OpenCode 2.x…" >&2
    guest_v2_install_script="$(v2_install_script)"
    "$SBX" exec "$NAME" sh -c "$guest_v2_install_script"
    "$SBX" exec "$NAME" sh -c "$guest_v2_version_script" >/dev/null || {
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
launch+=( -w "$CANONICAL" "$NAME" "${guest_opencode[@]}" ${opencode_cmd[@]+"${opencode_cmd[@]}"} )
if [[ "$MODE" == acp ]]; then
  exec 0<&3 1>&4 3<&- 4>&-
fi
exec "${launch[@]}"
