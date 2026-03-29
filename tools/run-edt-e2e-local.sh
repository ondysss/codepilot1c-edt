#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_RUNS_DIR="$ROOT_DIR/.runs/edt-e2e"

BUILD_ENABLED="${BUILD_ENABLED:-true}"
BUILD_CMD="${BUILD_CMD:-mvn -DskipTests package}"
SKIP_P2_INSTALL="${SKIP_P2_INSTALL:-false}"
SKIP_MCP_SMOKE="${SKIP_MCP_SMOKE:-false}"
RUN_QA="${RUN_QA:-true}"
KEEP_EDT_RUNNING="${KEEP_EDT_RUNNING:-false}"
KILL_PROCESS_ON_PORT="${KILL_PROCESS_ON_PORT:-false}"
P2_USE_XVFB="${P2_USE_XVFB:-auto}"

EDT_HOME="${EDT_HOME:-}"
EDT_EXECUTABLE="${EDT_EXECUTABLE:-}"
EDT_WORKSPACE="${EDT_WORKSPACE:-$HOME/edt-workspaces/codepilot-e2e}"
EDT_PROJECT_PATHS="${EDT_PROJECT_PATHS:-}"
SKIP_PROJECT_IMPORT="${SKIP_PROJECT_IMPORT:-false}"
EDT_HEADLESS="${EDT_HEADLESS:-true}"
EDT_APPLICATION_ID="${EDT_APPLICATION_ID:-}"

P2_REPOSITORY="${P2_REPOSITORY:-$ROOT_DIR/repositories/com.codepilot1c.update/target/repository}"
P2_INSTALL_IU="${P2_INSTALL_IU:-com.codepilot1c.feature.feature.group}"

MCP_BIND="${MCP_BIND:-127.0.0.1}"
MCP_PORT="${MCP_PORT:-8765}"
MCP_PROTOCOL_VERSION="${MCP_PROTOCOL_VERSION:-2025-06-18}"
MCP_WAIT_TIMEOUT_SECONDS="${MCP_WAIT_TIMEOUT_SECONDS:-180}"
MCP_MUTATION_POLICY="${MCP_MUTATION_POLICY:-ALLOW}"
MCP_EXPOSED_TOOLS="${MCP_EXPOSED_TOOLS:-*}"
MCP_URL="${MCP_URL:-http://$MCP_BIND:$MCP_PORT/mcp}"
MCP_BEARER_TOKEN="${MCP_BEARER_TOKEN:-}"

QA_CONFIG_PATH="${QA_CONFIG_PATH:-tests/qa/qa-config.json}"
QA_PROJECT_NAME="${QA_PROJECT_NAME:-}"
QA_USE_EDT_RUNTIME="${QA_USE_EDT_RUNTIME:-true}"
QA_UPDATE_DB="${QA_UPDATE_DB:-true}"
QA_VALIDATE_PORTS="${QA_VALIDATE_PORTS:-false}"
QA_TIMEOUT_SECONDS="${QA_TIMEOUT_SECONDS:-3600}"
QA_STATUS_FAIL_ON_WARNING="${QA_STATUS_FAIL_ON_WARNING:-false}"
QA_TAGS_INCLUDE="${QA_TAGS_INCLUDE:-}"
QA_TAGS_EXCLUDE="${QA_TAGS_EXCLUDE:-}"
QA_FEATURES="${QA_FEATURES:-}"
QA_SCENARIOS="${QA_SCENARIOS:-}"

RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
RUNS_DIR="${RUNS_DIR:-$DEFAULT_RUNS_DIR}"
RUN_DIR="$RUNS_DIR/$RUN_ID"
ARTIFACTS_DIR="$RUN_DIR/artifacts"
TRACE_DIR="$RUN_DIR/agent-runs"

EDT_PID=""
SESSION_ID=""

usage() {
    cat <<'EOF'
Usage:
  tools/run-edt-e2e-local.sh

Required environment:
  EDT_HOME=/path/to/1cedt-install

Recommended environment:
  EDT_WORKSPACE=/path/to/dedicated/workspace
  EDT_PROJECT_PATHS=/abs/project1:/abs/project2
  QA_PROJECT_NAME=MyProject

Optional toggles:
  BUILD_ENABLED=true|false
  SKIP_P2_INSTALL=true|false
  SKIP_MCP_SMOKE=true|false
  RUN_QA=true|false
  KEEP_EDT_RUNNING=true|false
  KILL_PROCESS_ON_PORT=true|false

Optional MCP settings:
  MCP_BIND=127.0.0.1
  MCP_PORT=8765
  MCP_BEARER_TOKEN=<token>
  MCP_PROTOCOL_VERSION=2025-06-18

Optional QA settings:
  QA_CONFIG_PATH=tests/qa/qa-config.json
  QA_PROJECT_NAME=MyProject
  QA_USE_EDT_RUNTIME=true|false
  QA_UPDATE_DB=true|false
  QA_TIMEOUT_SECONDS=3600
  QA_TAGS_INCLUDE=@smoke,@critical
  QA_TAGS_EXCLUDE=@manual
  QA_FEATURES=feature1.feature,feature2.feature
  QA_SCENARIOS=Scenario A,Scenario B

Artifacts:
  .runs/edt-e2e/<run-id>/
EOF
}

log() {
    printf '[run-edt-e2e] %s\n' "$*" >&2
}

fail() {
    log "ERROR: $*"
    exit 1
}

bool_true() {
    case "${1:-}" in
        1|true|TRUE|True|yes|YES|Yes|on|ON|On) return 0 ;;
        *) return 1 ;;
    esac
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "Required command not found: $1"
}

resolve_edt_executable() {
    if [[ -n "$EDT_EXECUTABLE" ]]; then
        printf '%s\n' "$EDT_EXECUTABLE"
        return
    fi
    if [[ -n "$EDT_HOME" && -x "$EDT_HOME/1cedt" ]]; then
        printf '%s\n' "$EDT_HOME/1cedt"
        return
    fi
    fail "Set EDT_HOME to your dedicated 1C:EDT installation"
}

cleanup() {
    local exit_code=$?
    collect_workspace_logs || true
    if [[ -n "$EDT_PID" ]] && ! bool_true "$KEEP_EDT_RUNNING"; then
        stop_edt || true
    fi
    if [[ $exit_code -eq 0 ]]; then
        log "Run completed successfully: $RUN_DIR"
    else
        log "Run failed. Artifacts: $RUN_DIR"
    fi
}

stop_edt() {
    if [[ -z "$EDT_PID" ]]; then
        return 0
    fi
    if kill -0 "$EDT_PID" >/dev/null 2>&1; then
        log "Stopping EDT process $EDT_PID"
        kill "$EDT_PID" >/dev/null 2>&1 || true
        for _ in $(seq 1 20); do
            if ! kill -0 "$EDT_PID" >/dev/null 2>&1; then
                EDT_PID=""
                return 0
            fi
            sleep 1
        done
        kill -9 "$EDT_PID" >/dev/null 2>&1 || true
    fi
    EDT_PID=""
}

create_run_layout() {
    mkdir -p "$ARTIFACTS_DIR"
    : >"$RUN_DIR/summary.log"
    {
        printf 'run_id=%s\n' "$RUN_ID"
        printf 'run_dir=%s\n' "$RUN_DIR"
        printf 'root_dir=%s\n' "$ROOT_DIR"
        printf 'started_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        printf 'edt_workspace=%s\n' "$EDT_WORKSPACE"
        printf 'mcp_url=%s\n' "$MCP_URL"
    } >"$RUN_DIR/run.env"
}

collect_workspace_logs() {
    if [[ ! -d "$EDT_WORKSPACE/.metadata" ]]; then
        return 0
    fi
    mkdir -p "$ARTIFACTS_DIR/workspace-metadata"
    if [[ -f "$EDT_WORKSPACE/.metadata/.log" ]]; then
        cp "$EDT_WORKSPACE/.metadata/.log" "$ARTIFACTS_DIR/workspace-metadata/eclipse.log"
    fi
    if [[ -d "$EDT_WORKSPACE/.metadata/.plugins" ]]; then
        cp -R "$EDT_WORKSPACE/.metadata/.plugins" "$ARTIFACTS_DIR/workspace-metadata/plugins" 2>/dev/null || true
    fi
}

link_projects_into_workspace() {
    mkdir -p "$EDT_WORKSPACE"
    if bool_true "$SKIP_PROJECT_IMPORT"; then
        log "Skipping project import into workspace (SKIP_PROJECT_IMPORT=true)"
        return 0
    fi
    if [[ -z "$EDT_PROJECT_PATHS" ]]; then
        return 0
    fi
    log "Linking projects into workspace"
    IFS=':' read -r -a project_paths <<<"$EDT_PROJECT_PATHS"
    for project_path in "${project_paths[@]}"; do
        [[ -n "$project_path" ]] || continue
        [[ -d "$project_path" ]] || fail "Project path does not exist: $project_path"
        local link_name
        link_name="$(basename "$project_path")"
        ln -sfn "$project_path" "$EDT_WORKSPACE/$link_name"
    done
}

write_json_file_uri() {
    python3 - "$1" <<'PY'
import pathlib
import sys
print(pathlib.Path(sys.argv[1]).resolve().as_uri())
PY
}

run_build() {
    if ! bool_true "$BUILD_ENABLED"; then
        log "Skipping build"
        return 0
    fi
    log "Building reactor: $BUILD_CMD"
    (
        cd "$ROOT_DIR"
        bash -lc "$BUILD_CMD"
    ) >"$RUN_DIR/build.log" 2>&1 || {
        tail -n 200 "$RUN_DIR/build.log" >&2 || true
        fail "Build failed"
    }
}

maybe_start_xvfb() {
    local display_var=""
    if [[ "$OSTYPE" != linux* ]]; then
        printf '%s\n' "$display_var"
        return 0
    fi
    if [[ "$P2_USE_XVFB" == "false" ]]; then
        printf '%s\n' "$display_var"
        return 0
    fi
    if [[ "$P2_USE_XVFB" == "true" || "$P2_USE_XVFB" == "auto" ]]; then
        if command -v Xvfb >/dev/null 2>&1; then
            display_var=":99"
            Xvfb "$display_var" -screen 0 1024x768x24 -nolisten tcp >"$RUN_DIR/xvfb.log" 2>&1 &
            local xvfb_pid=$!
            echo "$xvfb_pid" >"$RUN_DIR/xvfb.pid"
            sleep 3
        elif [[ "$P2_USE_XVFB" == "true" ]]; then
            fail "P2_USE_XVFB=true but Xvfb is not installed"
        fi
    fi
    printf '%s\n' "$display_var"
}

stop_xvfb_if_started() {
    if [[ -f "$RUN_DIR/xvfb.pid" ]]; then
        local xvfb_pid
        xvfb_pid="$(cat "$RUN_DIR/xvfb.pid")"
        kill "$xvfb_pid" >/dev/null 2>&1 || true
        rm -f "$RUN_DIR/xvfb.pid"
    fi
}

run_p2_director_command() {
    local log_file="$1"
    shift
    "$@" >"$log_file" 2>&1 &
    local director_pid=$!
    local rc=0
    while kill -0 "$director_pid" >/dev/null 2>&1; do
        if grep -Eq 'Операция завершена|Operation completed' "$log_file" 2>/dev/null; then
            sleep 1
            if kill -0 "$director_pid" >/dev/null 2>&1; then
                kill "$director_pid" >/dev/null 2>&1 || true
            fi
            wait "$director_pid" >/dev/null 2>&1 || true
            return 0
        fi
        if grep -Eq 'There were errors|Установка не выполнена|Не удается завершить установку|Cannot complete the install' "$log_file" 2>/dev/null; then
            kill "$director_pid" >/dev/null 2>&1 || true
            wait "$director_pid" >/dev/null 2>&1 || true
            return 1
        fi
        sleep 1
    done
    wait "$director_pid" || rc=$?
    if [[ $rc -eq 0 ]]; then
        return 0
    fi
    return "$rc"
}

run_p2_install() {
    if bool_true "$SKIP_P2_INSTALL"; then
        log "Skipping p2 install"
        return 0
    fi
    [[ -d "$P2_REPOSITORY" ]] || fail "P2 repository not found: $P2_REPOSITORY"
    local edt_executable
    edt_executable="$(resolve_edt_executable)"
    local repository_uri
    repository_uri="$(write_json_file_uri "$P2_REPOSITORY")"
    local display_var=""
    display_var="$(maybe_start_xvfb)"
    log "Installing plugin into EDT test home"
    local -a director_base=(
        "$edt_executable"
        -application org.eclipse.equinox.p2.director
        -noSplash
        -repository "$repository_uri"
        -destination "$EDT_HOME"
    )
    local -a install_cmd=("${director_base[@]}" -installIU "$P2_INSTALL_IU")
    local -a uninstall_cmd=("${director_base[@]}" -uninstallIU "$P2_INSTALL_IU")
    if [[ -n "$display_var" ]]; then
        DISPLAY="$display_var" run_p2_director_command "$RUN_DIR/p2-uninstall.log" "${uninstall_cmd[@]}" || true
        DISPLAY="$display_var" run_p2_director_command "$RUN_DIR/p2-install.log" "${install_cmd[@]}" || {
                stop_xvfb_if_started
                tail -n 200 "$RUN_DIR/p2-install.log" >&2 || true
                fail "p2 director install failed"
            }
    else
        run_p2_director_command "$RUN_DIR/p2-uninstall.log" "${uninstall_cmd[@]}" || true
        run_p2_director_command "$RUN_DIR/p2-install.log" "${install_cmd[@]}" || {
                stop_xvfb_if_started
                tail -n 200 "$RUN_DIR/p2-install.log" >&2 || true
                fail "p2 director install failed"
            }
    fi
    stop_xvfb_if_started
}

patch_bundles_info() {
    local bundles_info="$EDT_HOME/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
    [[ -f "$bundles_info" ]] || fail "bundles.info not found: $bundles_info"
    if grep -q '^com\.codepilot1c\.core,.*4,true$' "$bundles_info"; then
        log "bundles.info already patched"
        return 0
    fi
    if grep -q '^com\.codepilot1c\.core,.*4,false$' "$bundles_info"; then
        python3 - "$bundles_info" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
old = "com.codepilot1c.core,"
lines = []
changed = False
for line in text.splitlines():
    if line.startswith(old) and line.endswith(",false"):
        line = line[:-5] + ",true"
        changed = True
    lines.append(line)
path.write_text("\n".join(lines) + "\n", encoding="utf-8")
if not changed:
    raise SystemExit(2)
PY
        log "Patched bundles.info for com.codepilot1c.core auto-start"
        return 0
    fi
    fail "Could not locate com.codepilot1c.core entry in bundles.info"
}

ensure_port_available() {
    local pids=""
    if command -v lsof >/dev/null 2>&1; then
        pids="$(lsof -tiTCP:"$MCP_PORT" -sTCP:LISTEN || true)"
    fi
    if [[ -z "$pids" ]]; then
        return 0
    fi
    if bool_true "$KILL_PROCESS_ON_PORT"; then
        log "Killing existing listeners on port $MCP_PORT: $pids"
        while read -r pid; do
            [[ -n "$pid" ]] || continue
            kill "$pid" >/dev/null 2>&1 || true
        done <<<"$pids"
        sleep 2
        return 0
    fi
    fail "Port $MCP_PORT is already in use. Set KILL_PROCESS_ON_PORT=true to stop the listener automatically"
}

generate_bearer_token_if_needed() {
    if [[ -n "$MCP_BEARER_TOKEN" ]]; then
        return 0
    fi
    MCP_BEARER_TOKEN="$(python3 - <<'PY'
import secrets
print(secrets.token_hex(32))
PY
)"
    log "Generated ephemeral MCP bearer token for this run"
}

launch_edt() {
    local edt_executable
    edt_executable="$(resolve_edt_executable)"
    ensure_port_available
    generate_bearer_token_if_needed
    mkdir -p "$EDT_WORKSPACE"
    log "Launching EDT headless with MCP host"
    local vmargs=(
        "-Dcodepilot.mcp.enabled=true"
        "-Dcodepilot.mcp.host.http.enabled=true"
        "-Dcodepilot.mcp.host.http.bindAddress=$MCP_BIND"
        "-Dcodepilot.mcp.host.http.port=$MCP_PORT"
        "-Dcodepilot.mcp.host.auth.mode=BEARER_ONLY"
        "-Dcodepilot.mcp.host.http.bearerToken=$MCP_BEARER_TOKEN"
        "-Dcodepilot.mcp.host.policy.defaultMutationDecision=$MCP_MUTATION_POLICY"
        "-Dcodepilot.mcp.host.policy.exposedTools=$MCP_EXPOSED_TOOLS"
        "-Dcodepilot1c.agent.trace.dir=$TRACE_DIR"
    )
    if bool_true "$EDT_HEADLESS"; then
        vmargs+=("-Declipse.ignoreApp=true" "-Dosgi.noShutdown=true")
    fi
    if ! bool_true "$SKIP_PROJECT_IMPORT"; then
        vmargs+=("-Dcodepilot1c.workspace.importProjects=$EDT_PROJECT_PATHS")
    fi
    "$edt_executable" \
        ${EDT_APPLICATION_ID:+-application "$EDT_APPLICATION_ID"} \
        -nosplash \
        -data "$EDT_WORKSPACE" \
        -vmargs \
        "${vmargs[@]}" \
        >"$RUN_DIR/edt.out.log" 2>&1 &
    EDT_PID=$!
    echo "$EDT_PID" >"$RUN_DIR/edt.pid"
}

mcp_request() {
    local method="$1"
    local params_json="$2"
    local output_file="$3"
    local session_id="${4:-}"
    local timeout_seconds="${5:-60}"
    python3 - "$MCP_URL" "$MCP_BEARER_TOKEN" "$method" "$params_json" "$session_id" "$timeout_seconds" "$output_file" <<'PY'
import json
import sys
import urllib.error
import urllib.request

url, token, method, params_json, session_id, timeout_seconds, output_file = sys.argv[1:8]
payload = {
    "jsonrpc": "2.0",
    "id": 1,
    "method": method,
}
if params_json and params_json != "null":
    payload["params"] = json.loads(params_json)
headers = {
    "Content-Type": "application/json",
}
if token:
    headers["Authorization"] = f"Bearer {token}"
if session_id:
    headers["Mcp-Session-Id"] = session_id
request = urllib.request.Request(
    url,
    data=json.dumps(payload).encode("utf-8"),
    headers=headers,
    method="POST",
)
wrapper = {
    "http_status": 0,
    "headers": {},
    "body": {},
}
try:
    with urllib.request.urlopen(request, timeout=int(timeout_seconds)) as response:
        wrapper["http_status"] = response.getcode()
        wrapper["headers"] = dict(response.headers.items())
        body_text = response.read().decode("utf-8", "replace")
except urllib.error.HTTPError as exc:
    wrapper["http_status"] = exc.code
    wrapper["headers"] = dict(exc.headers.items()) if exc.headers else {}
    body_text = exc.read().decode("utf-8", "replace")
except Exception as exc:
    wrapper["body"] = {
        "transport_error": str(exc),
    }
    with open(output_file, "w", encoding="utf-8") as handle:
        json.dump(wrapper, handle, ensure_ascii=False, indent=2)
    raise
try:
    wrapper["body"] = json.loads(body_text) if body_text else {}
except json.JSONDecodeError:
    wrapper["body"] = {
        "raw": body_text,
    }
with open(output_file, "w", encoding="utf-8") as handle:
    json.dump(wrapper, handle, ensure_ascii=False, indent=2)
PY
}

extract_session_id() {
    python3 - "$1" <<'PY'
import json
import sys

data = json.load(open(sys.argv[1], encoding="utf-8"))
headers = {k.lower(): v for k, v in (data.get("headers") or {}).items()}
print(headers.get("mcp-session-id", ""))
PY
}

wait_for_mcp() {
    local initialize_file="$RUN_DIR/mcp-initialize.json"
    local deadline=$((SECONDS + MCP_WAIT_TIMEOUT_SECONDS))
    log "Waiting for MCP host at $MCP_URL"
    while (( SECONDS < deadline )); do
        if mcp_request "initialize" "{\"protocolVersion\":\"$MCP_PROTOCOL_VERSION\",\"capabilities\":{},\"clientInfo\":{\"name\":\"run-edt-e2e-local\",\"version\":\"1.0\"}}" "$initialize_file" "" 60 2>/dev/null; then
            SESSION_ID="$(extract_session_id "$initialize_file")"
            if [[ -n "$SESSION_ID" ]]; then
                log "MCP host is ready. Session: $SESSION_ID"
                return 0
            fi
        fi
        sleep 2
    done
    tail -n 200 "$RUN_DIR/edt.out.log" >&2 || true
    fail "Timed out waiting for MCP host"
}

send_initialized_notification() {
    mcp_request "notifications/initialized" "{}" "$RUN_DIR/mcp-initialized.json" "$SESSION_ID" 30 >/dev/null 2>&1 || true
}

assert_tools_present() {
    local tool_file="$RUN_DIR/mcp-tools-list.json"
    mcp_request "tools/list" "{}" "$tool_file" "$SESSION_ID" 60
    if bool_true "$RUN_QA"; then
        python3 - "$tool_file" <<'PY'
import json
import sys

data = json.load(open(sys.argv[1], encoding="utf-8"))
body = data.get("body") or {}
tools = {
    item.get("name")
    for item in ((body.get("result") or {}).get("tools") or [])
    if isinstance(item, dict)
}
required = {"qa_inspect", "qa_run"}
missing = sorted(required - tools)
if missing:
    raise SystemExit("Missing MCP tools: " + ", ".join(missing))
PY
    fi
}

build_qa_inspect_status_params() {
    python3 - "$QA_CONFIG_PATH" "$QA_VALIDATE_PORTS" "$QA_USE_EDT_RUNTIME" "$QA_PROJECT_NAME" <<'PY'
import json
import sys

config_path, validate_ports, use_edt_runtime, project_name = sys.argv[1:5]
params = {
    "command": "status",
    "config_path": config_path,
    "validate_ports": validate_ports.lower() in {"1", "true", "yes", "on"},
    "use_edt_runtime": use_edt_runtime.lower() in {"1", "true", "yes", "on"},
}
project_name = project_name.strip()
if project_name:
    params["project_name"] = project_name
print(json.dumps(params, ensure_ascii=False))
PY
}

build_qa_run_params() {
    python3 - "$QA_CONFIG_PATH" "$QA_USE_EDT_RUNTIME" "$QA_UPDATE_DB" "$QA_TIMEOUT_SECONDS" \
        "$QA_PROJECT_NAME" "$QA_TAGS_INCLUDE" "$QA_TAGS_EXCLUDE" "$QA_FEATURES" "$QA_SCENARIOS" <<'PY'
import json
import sys

(
    config_path,
    use_edt_runtime,
    update_db,
    timeout_seconds,
    project_name,
    tags_include,
    tags_exclude,
    features,
    scenarios,
) = sys.argv[1:10]

def parse_csv(value):
    value = value.strip()
    if not value:
        return []
    return [item.strip() for item in value.split(",") if item.strip()]

params = {
    "config_path": config_path,
    "use_edt_runtime": use_edt_runtime.lower() in {"1", "true", "yes", "on"},
    "update_db": update_db.lower() in {"1", "true", "yes", "on"},
    "timeout_s": int(timeout_seconds),
}
project_name = project_name.strip()
if project_name:
    params["project_name"] = project_name
for raw_value, key in [
    (tags_include, "tags_include"),
    (tags_exclude, "tags_exclude"),
    (features, "features"),
    (scenarios, "scenarios"),
]:
    values = parse_csv(raw_value)
    if values:
        params[key] = values
print(json.dumps(params, ensure_ascii=False))
PY
}

extract_tool_payload() {
    local response_file="$1"
    local payload_file="$2"
    local json_file="$3"
    python3 - "$response_file" "$payload_file" "$json_file" <<'PY'
import json
import sys

response_file, payload_file, json_file = sys.argv[1:4]
data = json.load(open(response_file, encoding="utf-8"))
body = data.get("body") or {}
if "error" in body:
    raise SystemExit(json.dumps(body["error"], ensure_ascii=False))
result = body.get("result") or {}
if result.get("isError"):
    messages = []
    for item in result.get("content") or []:
        if isinstance(item, dict) and item.get("type") == "text":
            messages.append(item.get("text") or "")
    raise SystemExit("\n".join([m for m in messages if m]) or "MCP tool returned isError=true")
text = ""
for item in result.get("content") or []:
    if isinstance(item, dict) and item.get("type") == "text":
        text = item.get("text") or ""
        break
with open(payload_file, "w", encoding="utf-8") as handle:
    handle.write(text)
try:
    payload = json.loads(text)
except json.JSONDecodeError:
    payload = {"text": text}
with open(json_file, "w", encoding="utf-8") as handle:
    json.dump(payload, handle, ensure_ascii=False, indent=2)
if isinstance(payload, dict) and "status" in payload:
    print(payload["status"])
PY
}

run_tool_call() {
    local tool_name="$1"
    local params_json="$2"
    local prefix="$3"
    local timeout_seconds="$4"
    local response_file="${prefix}.mcp.json"
    local payload_file="${prefix}.payload.txt"
    local json_file="${prefix}.json"
    mcp_request "tools/call" "{\"name\":\"$tool_name\",\"arguments\":$params_json}" "$response_file" "$SESSION_ID" "$timeout_seconds"
    extract_tool_payload "$response_file" "$payload_file" "$json_file"
}

run_mcp_smoke() {
    if bool_true "$SKIP_MCP_SMOKE"; then
        log "Skipping MCP smoke"
        return 0
    fi
    send_initialized_notification
    assert_tools_present
}

run_qa_inspect_status() {
    local params_json
    params_json="$(build_qa_inspect_status_params)"
    local status
    status="$(run_tool_call "qa_inspect" "$params_json" "$RUN_DIR/qa-inspect-status" 180)"
    log "qa_inspect(command=status) result: $status"
    case "$status" in
        ok) return 0 ;;
        warning)
            if bool_true "$QA_STATUS_FAIL_ON_WARNING"; then
                fail "qa_inspect(command=status) returned warning"
            fi
            return 0
            ;;
        *)
            fail "qa_inspect(command=status) returned $status"
            ;;
    esac
}

run_qa_run() {
    local params_json
    params_json="$(build_qa_run_params)"
    local timeout_seconds=$((QA_TIMEOUT_SECONDS + 300))
    local status
    status="$(run_tool_call "qa_run" "$params_json" "$RUN_DIR/qa-run" "$timeout_seconds")"
    log "qa_run result: $status"
    case "$status" in
        passed) return 0 ;;
        *)
            fail "qa_run returned $status"
            ;;
    esac
}

write_summary() {
    {
        printf 'run_dir=%s\n' "$RUN_DIR"
        printf 'trace_dir=%s\n' "$TRACE_DIR"
        printf 'workspace=%s\n' "$EDT_WORKSPACE"
        printf 'mcp_url=%s\n' "$MCP_URL"
        printf 'session_id=%s\n' "$SESSION_ID"
        printf 'edt_pid=%s\n' "$EDT_PID"
    } >>"$RUN_DIR/summary.log"
}

main() {
    if [[ "${1:-}" == "--help" ]]; then
        usage
        exit 0
    fi
    require_command python3
    require_command bash
    require_command grep
    require_command sed
    require_command tail
    require_command mkdir
    create_run_layout
    trap cleanup EXIT
    [[ -n "$EDT_HOME" ]] || fail "EDT_HOME is required"
    [[ -d "$EDT_HOME" ]] || fail "EDT_HOME does not exist: $EDT_HOME"
    resolve_edt_executable >/dev/null
    link_projects_into_workspace
    run_build
    run_p2_install
    patch_bundles_info
    launch_edt
    wait_for_mcp
    run_mcp_smoke
    if bool_true "$RUN_QA"; then
        run_qa_inspect_status
        run_qa_run
    fi
    write_summary
    if bool_true "$KEEP_EDT_RUNNING"; then
        log "EDT left running intentionally. PID: $EDT_PID"
        EDT_PID=""
    fi
}

main "$@"
