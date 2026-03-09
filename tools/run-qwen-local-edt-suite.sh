#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE_QWEN_HOME="$HOME/.qwen"
DEFAULT_EDT_APP="/Applications/1C/1CE/components/1c-edt-2025.1.5+34-x86_64/1cedt (2025.1.5+34).app"

PROJECT_DIR="${PROJECT_DIR:-}"
PROJECT_FIXTURE_DIR="${PROJECT_FIXTURE_DIR:-}"
PROJECT_COPY_NAME="${PROJECT_COPY_NAME:-}"
SUITE_PATH="${SUITE_PATH:-$ROOT_DIR/evals/qwen/suite-smoke.json}"
SCENARIO_ID="${SCENARIO_ID:-}"
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
RUNS_DIR="${RUNS_DIR:-$ROOT_DIR/.runs/qwen-local-edt}"
RUN_DIR="$RUNS_DIR/$RUN_ID"

EDT_SOURCE_APP="${EDT_SOURCE_APP:-$DEFAULT_EDT_APP}"
EDT_TEST_ROOT="${EDT_TEST_ROOT:-$HOME/.codepilot1c/edt-test}"
EDT_TEST_APP="${EDT_TEST_APP:-$EDT_TEST_ROOT/1cedt-test.app}"
EDT_WORKSPACE="${EDT_WORKSPACE:-$RUN_DIR/edt-workspace}"

MCP_BIND="${MCP_BIND:-127.0.0.1}"
MCP_PORT="${MCP_PORT:-8765}"
MCP_URL="http://$MCP_BIND:$MCP_PORT/mcp"
MCP_BEARER_TOKEN="${MCP_BEARER_TOKEN:-}"
LOCAL_MCP_SERVER_NAME="${LOCAL_MCP_SERVER_NAME:-codepilot1clocal}"

BUILD_ENABLED="${BUILD_ENABLED:-true}"
BUILD_CMD="${BUILD_CMD:-mvn verify}"
SKIP_P2_INSTALL="${SKIP_P2_INSTALL:-false}"
KEEP_EDT_RUNNING="${KEEP_EDT_RUNNING:-false}"
SKIP_PROJECT_IMPORT="${SKIP_PROJECT_IMPORT:-}"
EDT_HEADLESS="${EDT_HEADLESS:-true}"
EDT_APPLICATION_ID="${EDT_APPLICATION_ID:-}"
ATTACH_ONLY="${ATTACH_ONLY:-false}"
COPY_WORKSPACE="${COPY_WORKSPACE:-}"
QWEN_MODEL="${QWEN_MODEL:-}"
QWEN_AUTH_TYPE="${QWEN_AUTH_TYPE:-}"
QWEN_TIMEOUT_SECONDS="${QWEN_TIMEOUT_SECONDS:-1800}"
QWEN_EXTRA_ARGS="${QWEN_EXTRA_ARGS:-}"

usage() {
    cat <<'EOF'
Usage:
  PROJECT_DIR=/abs/path/to/edt-project bash tools/run-qwen-local-edt-suite.sh

Optional env:
  PROJECT_FIXTURE_DIR=/abs/path/to/seed-project
  PROJECT_COPY_NAME=clean-smoke
  SUITE_PATH=/abs/path/to/suite.json
  SCENARIO_ID=WH-001
  EDT_SOURCE_APP=/Applications/.../1cedt.app
  EDT_TEST_APP=$HOME/.codepilot1c/edt-test/1cedt-test.app
  EDT_WORKSPACE=/abs/path/to/isolated-workspace
  EDT_HEADLESS=true|false
  EDT_APPLICATION_ID=com._1c.g5.v8.dt.platform.platformui.application
  BUILD_ENABLED=true|false
  SKIP_P2_INSTALL=true|false
  KEEP_EDT_RUNNING=true|false
  SKIP_PROJECT_IMPORT=true|false
  ATTACH_ONLY=true|false
  COPY_WORKSPACE=true|false
EOF
}

log() {
    printf '[run-qwen-local-edt] %s\n' "$*" >&2
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

ensure_token() {
    if [[ -n "$MCP_BEARER_TOKEN" ]]; then
        return 0
    fi
    if bool_true "$ATTACH_ONLY"; then
        log "No MCP_BEARER_TOKEN provided in attach-only mode; proceeding without bearer auth"
        return 0
    fi
    MCP_BEARER_TOKEN="$(python3 - <<'PY'
import secrets
print(secrets.token_hex(32))
PY
)"
}

prepare_layout() {
    mkdir -p "$RUN_DIR"
    mkdir -p "$EDT_TEST_ROOT"
}

maybe_copy_workspace() {
    local should_copy=false
    if bool_true "$COPY_WORKSPACE"; then
        should_copy=true
    elif bool_true "$ATTACH_ONLY" && [[ -n "$EDT_WORKSPACE" && -d "$EDT_WORKSPACE/.metadata" && "$EDT_WORKSPACE" != "$RUN_DIR/"* ]]; then
        should_copy=true
    fi
    if ! bool_true "$should_copy"; then
        return 0
    fi
    [[ -n "$EDT_WORKSPACE" ]] || fail "EDT_WORKSPACE is required to copy workspace"
    [[ -d "$EDT_WORKSPACE" ]] || fail "EDT_WORKSPACE not found: $EDT_WORKSPACE"
    local source_workspace="$EDT_WORKSPACE"
    local target_workspace="$RUN_DIR/edt-workspace"
    if bool_true "$ATTACH_ONLY"; then
        target_workspace="$RUN_DIR/edt-workspace-snapshot"
        log "Creating EDT workspace snapshot in run directory: $target_workspace"
        rm -rf "$target_workspace"
        ditto "$source_workspace" "$target_workspace"
        return 0
    fi
    log "Copying EDT workspace into run directory: $target_workspace"
    rm -rf "$target_workspace"
    ditto "$source_workspace" "$target_workspace"
    if [[ -n "$PROJECT_DIR" ]]; then
        PROJECT_DIR="$(python3 - "$source_workspace" "$target_workspace" "$PROJECT_DIR" <<'PY'
import os
import sys

source_ws, target_ws, project_dir = sys.argv[1:4]
source_ws = os.path.realpath(source_ws)
target_ws = os.path.realpath(target_ws)
project_dir = os.path.realpath(project_dir)
if project_dir.startswith(source_ws + os.sep):
    rel = os.path.relpath(project_dir, source_ws)
    print(os.path.join(target_ws, rel))
else:
    print(project_dir)
PY
)"
    fi
    EDT_WORKSPACE="$target_workspace"
    SKIP_PROJECT_IMPORT="true"
}

maybe_skip_import_for_existing_workspace() {
    if [[ -n "$SKIP_PROJECT_IMPORT" ]]; then
        return 0
    fi
    if [[ -n "$EDT_WORKSPACE" && -d "$EDT_WORKSPACE/.metadata" && "$EDT_WORKSPACE" != "$RUN_DIR/"* ]]; then
        SKIP_PROJECT_IMPORT="true"
        log "Detected existing workspace; skipping project import into EDT"
    fi
}

rewrite_project_name() {
    local project_dir="$1"
    local new_name="$2"
    [[ -n "$new_name" ]] || return 0
    [[ -f "$project_dir/.project" ]] || fail "Missing .project in copied fixture: $project_dir"
    python3 - "$project_dir/.project" "$new_name" <<'PY'
from pathlib import Path
import re
import sys

path = Path(sys.argv[1])
new_name = sys.argv[2]
text = path.read_text(encoding="utf-8")
updated, count = re.subn(r"(<name>)(.*?)(</name>)", rf"\1{new_name}\3", text, count=1, flags=re.DOTALL)
if count != 1:
    raise SystemExit("Failed to update <name> in .project")
path.write_text(updated, encoding="utf-8")
PY
}

prepare_project_copy_if_needed() {
    if [[ -n "$PROJECT_DIR" ]]; then
        printf '%s\n' "$PROJECT_DIR"
        return 0
    fi
    [[ -n "$PROJECT_FIXTURE_DIR" ]] || fail "Either PROJECT_DIR or PROJECT_FIXTURE_DIR is required"
    [[ -d "$PROJECT_FIXTURE_DIR" ]] || fail "PROJECT_FIXTURE_DIR not found: $PROJECT_FIXTURE_DIR"
    [[ -f "$PROJECT_FIXTURE_DIR/.project" ]] || fail "PROJECT_FIXTURE_DIR must point to an EDT project with .project"
    local copy_name="${PROJECT_COPY_NAME:-$(basename "$PROJECT_FIXTURE_DIR")}"
    local target_dir="$RUN_DIR/workspace-projects/$copy_name"
    mkdir -p "$RUN_DIR/workspace-projects"
    log "Copying clean EDT fixture into run directory: $target_dir"
    rm -rf "$target_dir"
    ditto "$PROJECT_FIXTURE_DIR" "$target_dir"
    rewrite_project_name "$target_dir" "$copy_name"
    printf '%s\n' "$target_dir"
}

ensure_test_edt_copy() {
    [[ -d "$EDT_SOURCE_APP" ]] || fail "EDT_SOURCE_APP not found: $EDT_SOURCE_APP"
    if [[ -d "$EDT_TEST_APP" ]]; then
        log "Using existing test EDT copy: $EDT_TEST_APP"
        return 0
    fi
    log "Creating user-writable EDT test copy from $EDT_SOURCE_APP"
    mkdir -p "$(dirname "$EDT_TEST_APP")"
    ditto "$EDT_SOURCE_APP" "$EDT_TEST_APP"
}

prepare_temp_qwen_home() {
    local temp_home="$RUN_DIR/qwen-home"
    local temp_qwen="$temp_home/.qwen"
    mkdir -p "$temp_qwen"
    [[ -f "$BASE_QWEN_HOME/oauth_creds.json" ]] || fail "Base qwen oauth creds not found: $BASE_QWEN_HOME/oauth_creds.json"
    cp "$BASE_QWEN_HOME/oauth_creds.json" "$temp_qwen/oauth_creds.json"
    [[ -f "$BASE_QWEN_HOME/installation_id" ]] && cp "$BASE_QWEN_HOME/installation_id" "$temp_qwen/installation_id"
    [[ -f "$BASE_QWEN_HOME/output-language.md" ]] && cp "$BASE_QWEN_HOME/output-language.md" "$temp_qwen/output-language.md"
    python3 - "$BASE_QWEN_HOME/settings.json" "$temp_qwen/settings.json" "$LOCAL_MCP_SERVER_NAME" "$MCP_URL" "$MCP_BEARER_TOKEN" <<'PY'
import json
import pathlib
import sys

src, dst, server_name, mcp_url, bearer = sys.argv[1:6]
payload = {}
src_path = pathlib.Path(src)
if src_path.exists():
    payload = json.loads(src_path.read_text(encoding="utf-8"))
server_payload = {"httpUrl": mcp_url}
if bearer:
    server_payload["headers"] = {"Authorization": f"Bearer {bearer}"}
payload["mcpServers"] = {server_name: server_payload}
dst_path = pathlib.Path(dst)
dst_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
PY
    printf '%s\n' "$temp_home"
}

launch_local_edt() {
    local edt_executable="$EDT_TEST_APP/Contents/MacOS/1cedt"
    local edt_home="$EDT_TEST_APP/Contents/Eclipse"
    [[ -x "$edt_executable" ]] || fail "EDT executable not found: $edt_executable"
    [[ -d "$edt_home" ]] || fail "EDT home not found: $edt_home"

    log "Launching local EDT MCP host"
    BUILD_ENABLED="$BUILD_ENABLED" \
    BUILD_CMD="$BUILD_CMD" \
    SKIP_P2_INSTALL="$SKIP_P2_INSTALL" \
    SKIP_PROJECT_IMPORT="$SKIP_PROJECT_IMPORT" \
    RUN_QA=false \
    SKIP_MCP_SMOKE=true \
    KEEP_EDT_RUNNING=true \
    EDT_HEADLESS="$EDT_HEADLESS" \
    EDT_APPLICATION_ID="$EDT_APPLICATION_ID" \
    EDT_HOME="$edt_home" \
    EDT_EXECUTABLE="$edt_executable" \
    EDT_WORKSPACE="$EDT_WORKSPACE" \
    EDT_PROJECT_PATHS="$PROJECT_DIR" \
    MCP_BIND="$MCP_BIND" \
    MCP_PORT="$MCP_PORT" \
    MCP_BEARER_TOKEN="$MCP_BEARER_TOKEN" \
    bash "$ROOT_DIR/tools/run-edt-e2e-local.sh" >"$RUN_DIR/launch-edt.log" 2>&1 || {
        tail -n 200 "$RUN_DIR/launch-edt.log" >&2 || true
        fail "Failed to launch local EDT MCP host"
    }
}

run_qwen_suite() {
    local temp_home="$1"
    local cmd=(
        python3 "$ROOT_DIR/tools/run-qwen-mcp-suite.py"
        --home-dir "$temp_home"
        --qwen-home "$temp_home/.qwen"
        --settings-path "$temp_home/.qwen/settings.json"
        --mcp-server "$LOCAL_MCP_SERVER_NAME"
        --suite "$SUITE_PATH"
        --workdir "$PROJECT_DIR"
        --timeout-seconds "$QWEN_TIMEOUT_SECONDS"
    )
    cmd+=(--keep-going)
    if [[ -n "$SCENARIO_ID" ]]; then
        cmd+=(--scenario-id "$SCENARIO_ID")
    fi
    if [[ -n "$QWEN_MODEL" ]]; then
        cmd+=(--model "$QWEN_MODEL")
    fi
    if [[ -n "$QWEN_AUTH_TYPE" ]]; then
        cmd+=(--auth-type "$QWEN_AUTH_TYPE")
    fi
    if [[ -n "$QWEN_EXTRA_ARGS" ]]; then
        cmd+=(--extra-args "$QWEN_EXTRA_ARGS")
    fi
    "${cmd[@]}" >"$RUN_DIR/qwen-suite.log" 2>&1 || {
        tail -n 200 "$RUN_DIR/qwen-suite.log" >&2 || true
        fail "Qwen suite failed"
    }
    cat "$RUN_DIR/qwen-suite.log"
}

main() {
    if [[ "${1:-}" == "--help" ]]; then
        usage
        exit 0
    fi
    require_command python3
    require_command qwen
    require_command ditto
    ensure_token
    prepare_layout
    maybe_copy_workspace
    maybe_skip_import_for_existing_workspace
    PROJECT_DIR="$(prepare_project_copy_if_needed)"
    [[ -d "$PROJECT_DIR" ]] || fail "Resolved PROJECT_DIR not found: $PROJECT_DIR"
    local temp_home
    temp_home="$(prepare_temp_qwen_home)"
    if bool_true "$ATTACH_ONLY"; then
        log "Attach-only mode: skipping EDT launch"
    else
        ensure_test_edt_copy
        launch_local_edt
    fi
    run_qwen_suite "$temp_home"
    if bool_true "$KEEP_EDT_RUNNING"; then
        log "Local EDT left running intentionally"
    fi
}

main "$@"
