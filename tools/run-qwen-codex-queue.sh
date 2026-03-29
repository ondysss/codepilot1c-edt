#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FLOW_SCRIPT="${FLOW_SCRIPT:-$ROOT_DIR/tools/run-qwen-codex-flow.sh}"
FLOW_RUNS_ROOT="${FLOW_RUNS_ROOT:-$ROOT_DIR/.runs/qwen-codex-flow}"
QUEUE_ROOT="${QUEUE_ROOT:-$ROOT_DIR/.runs/qwen-codex-queue}"
QUEUE_DIR="${QUEUE_DIR:-$QUEUE_ROOT/queue}"
QUEUE_RUNS_DIR="${QUEUE_RUNS_DIR:-$QUEUE_ROOT/runs}"
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
RUN_DIR="$QUEUE_RUNS_DIR/$RUN_ID"
TASK_RUNS_DIR="$RUN_DIR/tasks"
MAX_TASKS="${MAX_TASKS:-0}"

FLOW_KEEP_WORKTREE="${FLOW_KEEP_WORKTREE:-true}"
FLOW_CLEAN_WORKTREE_ON_SUCCESS="${FLOW_CLEAN_WORKTREE_ON_SUCCESS:-false}"
FLOW_MAX_ROUNDS="${FLOW_MAX_ROUNDS:-3}"
BASE_BRANCH="${BASE_BRANCH:-}"
AUTO_GENERATE_REVIEW_FOLLOWUPS="${AUTO_GENERATE_REVIEW_FOLLOWUPS:-true}"
FOLLOWUP_GENERATOR="${FOLLOWUP_GENERATOR:-$ROOT_DIR/tools/generate-qwen-codex-followups.py}"
FOLLOWUP_MAX_FINDINGS="${FOLLOWUP_MAX_FINDINGS:-0}"

PROCESSED_COUNT=0
APPROVED_COUNT=0
NO_CHANGES_COUNT=0
NEEDS_HUMAN_COUNT=0
FAILED_COUNT=0
FOLLOWUP_COUNT=0

usage() {
    cat <<'EOF'
Usage:
  bash tools/run-qwen-codex-queue.sh
  QUEUE_DIR=/abs/path/to/queue bash tools/run-qwen-codex-queue.sh

Queue layout:
  <queue>/
    todo/
    in_progress/
    approved/
    no_changes/
    needs_human/
    failed/

Task files:
  Place one markdown task per file into todo/.
  Files are processed in lexicographic order.

Key env vars:
  QUEUE_DIR=/abs/path/to/queue
  RUN_ID=custom-run-id
  MAX_TASKS=10
  BASE_BRANCH=main
  FLOW_MAX_ROUNDS=3
  FLOW_KEEP_WORKTREE=true|false
  FLOW_CLEAN_WORKTREE_ON_SUCCESS=true|false
  AUTO_GENERATE_REVIEW_FOLLOWUPS=true|false
  FOLLOWUP_MAX_FINDINGS=0

Artifacts:
  .runs/qwen-codex-queue/runs/<run-id>/
EOF
}

log() {
    printf '[implementer-codex-queue] %s\n' "$*" >&2
}

fail() {
    log "ERROR: $*"
    exit 1
}

slugify() {
    printf '%s' "$1" \
        | tr '[:upper:]' '[:lower:]' \
        | sed -E 's/[^a-z0-9]+/-/g; s/^-+//; s/-+$//; s/-+/-/g'
}

ensure_layout() {
    mkdir -p "$QUEUE_DIR/todo" \
             "$QUEUE_DIR/in_progress" \
             "$QUEUE_DIR/approved" \
             "$QUEUE_DIR/no_changes" \
             "$QUEUE_DIR/needs_human" \
             "$QUEUE_DIR/failed" \
             "$RUN_DIR" \
             "$TASK_RUNS_DIR"
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "Required command not found: $1"
}

unique_path() {
    local target="$1"
    if [[ ! -e "$target" ]]; then
        printf '%s\n' "$target"
        return 0
    fi
    local dir base stem ext candidate idx
    dir="$(dirname "$target")"
    base="$(basename "$target")"
    stem="${base%.*}"
    ext=""
    if [[ "$base" == *.* ]]; then
        ext=".${base##*.}"
    fi
    idx=1
    while :; do
        candidate="$dir/$stem-$idx$ext"
        if [[ ! -e "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return 0
        fi
        idx=$((idx + 1))
    done
}

claim_task() {
    local task_path="$1"
    local target
    if [[ ! -e "$task_path" ]]; then
        return 1
    fi
    target="$(unique_path "$QUEUE_DIR/in_progress/$(basename "$task_path")")"
    mv "$task_path" "$target" || return 1
    printf '%s\n' "$target"
}

write_task_result() {
    local result_path="$1"
    local task_name="$2"
    local task_path="$3"
    local status="$4"
    local exit_code="$5"
    local flow_run_id="$6"
    local flow_artifact_dir="$7"
    local followups_json_path="${8:-}"
    python3 - "$result_path" "$task_name" "$task_path" "$status" "$exit_code" "$flow_run_id" "$flow_artifact_dir" "$followups_json_path" <<'PY'
import json
import sys
from pathlib import Path

result_path, task_name, task_path, status, exit_code, flow_run_id, flow_artifact_dir, followups_json_path = sys.argv[1:9]
payload = {
    "task_name": task_name,
    "task_path": task_path,
    "status": status,
    "exit_code": int(exit_code),
    "flow_run_id": flow_run_id,
    "flow_artifact_dir": flow_artifact_dir,
}
if followups_json_path:
    path = Path(followups_json_path)
    if path.exists():
        payload["followups"] = json.loads(path.read_text(encoding="utf-8"))
with open(result_path, "w", encoding="utf-8") as handle:
    json.dump(payload, handle, ensure_ascii=False, indent=2)
    handle.write("\n")
PY
}

maybe_generate_followups() {
    local status="$1"
    local task_dir="$2"
    local final_task="$3"
    local flow_artifact_dir="$4"
    local followups_json_path="$task_dir/followups.json"

    if [[ "$status" != "needs_human" ]]; then
        return 0
    fi
    if [[ ! "$AUTO_GENERATE_REVIEW_FOLLOWUPS" =~ ^(1|true|TRUE|True|yes|YES|Yes|on|ON|On)$ ]]; then
        return 0
    fi

    python3 "$FOLLOWUP_GENERATOR" \
        --queue-dir "$QUEUE_DIR" \
        --flow-artifact-dir "$flow_artifact_dir" \
        --original-task "$final_task" \
        --output-json "$followups_json_path" \
        --max-findings "$FOLLOWUP_MAX_FINDINGS" >/dev/null

    if [[ -f "$followups_json_path" ]]; then
        local created_count
        created_count="$(python3 - "$followups_json_path" <<'PY'
import json
import sys
with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)
print(int(payload.get("generated_count", 0)))
PY
)"
        FOLLOWUP_COUNT=$((FOLLOWUP_COUNT + created_count))
    fi
}

process_single_task() {
    local source_task="$1"
    local claimed_task task_base task_name task_slug task_dir
    local flow_run_id flow_artifact_dir flow_log exit_code status target_dir final_task result_path followups_json_path

    if ! claimed_task="$(claim_task "$source_task")"; then
        log "Skipping task that is no longer available: $source_task"
        return 0
    fi
    task_base="$(basename "$claimed_task")"
    task_name="${task_base%.*}"
    task_slug="$(slugify "$task_name")"
    task_slug="${task_slug:-task}"
    task_dir="$TASK_RUNS_DIR/$task_slug"
    mkdir -p "$task_dir"

    flow_run_id="${RUN_ID}-${task_slug}"
    flow_artifact_dir="$FLOW_RUNS_ROOT/$flow_run_id"
    flow_log="$task_dir/flow.log"

    log "Processing task $task_base"
    set +e
    RUN_ID="$flow_run_id" \
    RUNS_ROOT="$FLOW_RUNS_ROOT" \
    KEEP_WORKTREE="$FLOW_KEEP_WORKTREE" \
    CLEAN_WORKTREE_ON_SUCCESS="$FLOW_CLEAN_WORKTREE_ON_SUCCESS" \
    MAX_ROUNDS="$FLOW_MAX_ROUNDS" \
    BASE_BRANCH="$BASE_BRANCH" \
    bash "$FLOW_SCRIPT" "$claimed_task" >"$flow_log" 2>&1
    exit_code=$?
    set -e

    case "$exit_code" in
        0)
            status="approved"
            target_dir="$QUEUE_DIR/approved"
            APPROVED_COUNT=$((APPROVED_COUNT + 1))
            ;;
        2)
            status="no_changes"
            target_dir="$QUEUE_DIR/no_changes"
            NO_CHANGES_COUNT=$((NO_CHANGES_COUNT + 1))
            ;;
        3)
            status="needs_human"
            target_dir="$QUEUE_DIR/needs_human"
            NEEDS_HUMAN_COUNT=$((NEEDS_HUMAN_COUNT + 1))
            ;;
        *)
            status="failed"
            target_dir="$QUEUE_DIR/failed"
            FAILED_COUNT=$((FAILED_COUNT + 1))
            ;;
    esac

    final_task="$(unique_path "$target_dir/$task_base")"
    mv "$claimed_task" "$final_task"

    followups_json_path="$task_dir/followups.json"
    maybe_generate_followups "$status" "$task_dir" "$final_task" "$flow_artifact_dir"
    result_path="$task_dir/result.json"
    write_task_result "$result_path" "$task_name" "$final_task" "$status" "$exit_code" "$flow_run_id" "$flow_artifact_dir" "$followups_json_path"
    cp "$result_path" "${final_task%.*}.result.json"
    PROCESSED_COUNT=$((PROCESSED_COUNT + 1))
}

write_summary() {
    local summary_path="$RUN_DIR/SUMMARY.md"
    cat >"$summary_path" <<EOF
# Implementer -> Codex Queue Summary

- run_id: \`$RUN_ID\`
- queue_dir: \`$QUEUE_DIR\`
- processed: \`$PROCESSED_COUNT\`
- approved: \`$APPROVED_COUNT\`
- no_changes: \`$NO_CHANGES_COUNT\`
- needs_human: \`$NEEDS_HUMAN_COUNT\`
- failed: \`$FAILED_COUNT\`
- generated_followups: \`$FOLLOWUP_COUNT\`

Artifacts:

- queue run dir: \`$RUN_DIR\`
- per-task logs/results: \`$TASK_RUNS_DIR\`
EOF
}

main() {
    if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
        usage
        exit 0
    fi

    require_command bash
    require_command python3
    [[ -x "$FLOW_SCRIPT" ]] || fail "Flow script is not executable: $FLOW_SCRIPT"
    [[ -f "$FOLLOWUP_GENERATOR" ]] || fail "Follow-up generator not found: $FOLLOWUP_GENERATOR"

    ensure_layout

    local -a tasks=()
    while IFS= read -r task; do
        tasks+=("$task")
    done < <(find "$QUEUE_DIR/todo" -maxdepth 1 -type f \( -name '*.md' -o -name '*.markdown' \) | sort)

    if [[ "${#tasks[@]}" -eq 0 ]]; then
        write_summary
        log "No tasks found in $QUEUE_DIR/todo"
        exit 0
    fi

    local task
    for task in "${tasks[@]}"; do
        if (( MAX_TASKS > 0 && PROCESSED_COUNT >= MAX_TASKS )); then
            break
        fi
        process_single_task "$task"
    done

    write_summary

    if (( NEEDS_HUMAN_COUNT > 0 || FAILED_COUNT > 0 )); then
        exit 2
    fi
}

main "$@"
