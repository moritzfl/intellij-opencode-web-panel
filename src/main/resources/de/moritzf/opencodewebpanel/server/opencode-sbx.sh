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
#
set -euo pipefail

CONTROL_DIR="opencode-sbx"
SPEC_NAME="opencode-sbx.yaml"
IN_VM_PORT=4096
AGENT="opencode"

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
CLI=0
WEB=0
ACP=0
DIR=""
OPENCODE_ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --init) INIT=1 ;;
    --recreate) RECREATE=1 ;;
    --rm) REMOVE=1 ;;
    --cli) CLI=1 ;;
    --web) WEB=1 ;;
    --acp) ACP=1 ;;
    -h|--help) usage ;;
    --oc-args) shift; OPENCODE_ARGS=("$@"); break ;;
    -*) usage ;;
    *) DIR="$1" ;;
  esac
  shift
done

if [[ $((CLI + WEB + ACP)) -gt 1 ]]; then
  usage
fi
MODE=web
if [[ "$CLI" -eq 1 ]]; then
  MODE=cli
elif [[ "$ACP" -eq 1 ]]; then
  MODE=acp
fi

if [[ -z "$DIR" ]]; then
  DIR="$(pwd)"
  SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
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
  local p="$1"
  [[ "$p" == /* || "$p" == //* ]] && return 0
  [[ "$p" =~ ^[A-Za-z]:(/|\\) ]] && return 0
  return 1
}

hash12() {
  printf '%s' "$1" | openssl dgst -sha256 -r | awk '{print substr($1,1,12)}'
}
CANONICAL="$(identity_path "$CANONICAL")"
NAME="ide-ocwp-$(hash12 "$CANONICAL")"

config_dir() {
  if [[ -n "${OCWP_CONFIG_DIR:-}" ]]; then
    printf '%s' "$OCWP_CONFIG_DIR"
    return
  fi
  printf '%s' "${HOME}/.config/opencode-web-panel"
}

SPEC=""
if [[ -f "$CANONICAL/$CONTROL_DIR/$SPEC_NAME" ]]; then
  SPEC="$CANONICAL/$CONTROL_DIR/$SPEC_NAME"
elif [[ -f "$CANONICAL/$SPEC_NAME" ]]; then
  SPEC="$CANONICAL/$SPEC_NAME"
elif [[ -f "$CANONICAL/opencode.sbx.yaml" ]]; then
  SPEC="$CANONICAL/opencode.sbx.yaml"
elif [[ -f "$CANONICAL/opencode-web-panel.sbx.yaml" ]]; then
  SPEC="$CANONICAL/opencode-web-panel.sbx.yaml"
elif [[ -f "$(config_dir)/sbx/${NAME}.yaml" ]]; then
  SPEC="$(config_dir)/sbx/${NAME}.yaml"
fi

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

json_string() {
  printf '"%s"' "$1"
}

sandbox_entry_for_name() {
  local name="$1" json obj needle
  json="$(sandbox_json)"
  needle="$(json_string "$name")"
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
  needle="$(json_string "$path")"
  case "$obj" in
    *"${needle},"*|*"${needle}]"*|*"${needle} "* ) return 0 ;;
  esac
  return 1
}

sandbox_owned() {
  local obj
  obj="$(sandbox_entry_for_name "$NAME")" || return 1
  entry_has_workspace "$obj" "$CANONICAL"
}

if [[ "$REMOVE" -eq 1 ]]; then
  if ! sandbox_owned; then
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
  if sandbox_owned; then
    "$SBX" rm --force "$NAME" || true
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
  export XDG_CONFIG_HOME="${HOST_CONFIG_DIR%/opencode}"
  serve_env+=( -e XDG_CONFIG_HOME )
  HOST_AUTH_FILE="${XDG_DATA_HOME:-${HOME}/.local/share}/opencode/auth.json"
  if [[ -f "$HOST_AUTH_FILE" ]]; then
    export OPENCODE_AUTH_CONTENT="$(< "$HOST_AUTH_FILE")"
    serve_env+=( -e OPENCODE_AUTH_CONTENT )
  fi
fi
PERSIST_GUEST="/home/agent/.local/share/opencode"
if [[ "$PERSIST_SESSIONS" == "true" ]]; then
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
  mkdir -p -- "$PERSIST_HOME"
fi

sandbox_exists() {
  sandbox_entry_for_name "$NAME" >/dev/null
}

workspace_has() {
  local obj
  obj="$(sandbox_entry_for_name "$NAME")" || return 1
  entry_has_workspace "$obj" "$1"
}

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
  local host="$1" sandbox="$2" replace="${3:-}"
  local script
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
if ! sandbox_exists; then
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
      kit_host="$kit"
      if [[ "$kit_host" == "~"* ]]; then
        kit_host="${HOME}${kit_host:1}"
      fi
      if [[ "$kit_host" != /* ]]; then
        kit_host="$CANONICAL/${kit_host#./}"
      fi
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
  if [[ "$SHARE_CONFIG" == "true" && -d "$HOST_CONFIG_DIR" ]]; then
    MOUNT_HOSTS+=("$HOST_CONFIG_DIR")
    MOUNT_SANDBOXES+=("$HOST_CONFIG_DIR")
    MOUNT_READONLY+=("1")
  fi
  i=0
  while [[ $i -lt ${#MOUNT_HOSTS[@]} ]]; do
    host="$(resolve_host_path "${MOUNT_HOSTS[$i]}")"
    MOUNT_HOSTS[$i]="$host"
    if [[ -n "$host" && "$host" != "$CANONICAL" && -e "$host" ]]; then
      if [[ "${MOUNT_READONLY[$i]:-0}" == "1" ]]; then
        create+=( "$host:ro" )
      else
        create+=( "$host" )
      fi
    fi
    i=$((i + 1))
  done
  "${create[@]}"
  i=0
  while [[ $i -lt ${#MOUNT_HOSTS[@]} ]]; do
    host="$(resolve_host_path "${MOUNT_HOSTS[$i]}")"
    sandbox="${MOUNT_SANDBOXES[$i]}"
    if [[ -n "$sandbox" && "$host" != "$sandbox" ]]; then
      link_mount "$host" "$sandbox"
    fi
    i=$((i + 1))
  done
fi

if [[ "$PERSIST_SESSIONS" == "true" && -n "${PERSIST_HOME:-}" && -d "$PERSIST_HOME" ]]; then
  if [[ "$CREATED" -eq 1 ]] || workspace_has "$PERSIST_HOME"; then
    link_mount "$PERSIST_HOME" "$PERSIST_GUEST" replace
  fi
fi

healthy() {
  local url="$1"
  if [[ -n "$PASSWORD" ]]; then
    curl -fsS --connect-timeout 1 --max-time 2 -u "opencode:${PASSWORD}" "$url" >/dev/null 2>&1
  else
    curl -fsS --connect-timeout 1 --max-time 2 "$url" >/dev/null 2>&1
  fi
}

print_url() {
  local i json port
  for i in $(seq 1 90); do
    json="$("$SBX" ports "$NAME" --json 2>/dev/null || true)"
    for port in $(printf '%s' "$json" | tr ',' '\n' | sed -n 's/.*"host_port"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p'); do
      if healthy "http://127.0.0.1:${port}/global/health" || healthy "http://127.0.0.1:${port}/api/health"; then
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

opencode_cmd=( opencode )
if [[ "$MODE" == web ]]; then
  opencode_cmd+=( serve --hostname 0.0.0.0 --port "$IN_VM_PORT" --print-logs )
elif [[ "$MODE" == acp ]]; then
  opencode_cmd+=( acp )
fi
if [[ ${#OPENCODE_ARGS[@]} -gt 0 ]]; then
  opencode_cmd+=( "${OPENCODE_ARGS[@]}" )
fi

if [[ "$MODE" == cli || "$MODE" == acp ]]; then
  cli_exec=( "$SBX" exec -i )
  if [[ "$MODE" == cli && -t 1 ]]; then
    cli_exec+=( -t )
  fi
  if [[ ${#serve_env[@]} -gt 0 ]]; then
    cli_exec+=( "${serve_env[@]}" )
  fi
  cli_exec+=( -w "$CANONICAL" "$NAME" "${opencode_cmd[@]}" )
  exec "${cli_exec[@]}"
fi

print_url &
web_exec=( "$SBX" exec )
if [[ ${#serve_env[@]} -gt 0 ]]; then
  web_exec+=( "${serve_env[@]}" )
fi
web_exec+=( -w "$CANONICAL" "$NAME" "${opencode_cmd[@]}" )
exec "${web_exec[@]}"
