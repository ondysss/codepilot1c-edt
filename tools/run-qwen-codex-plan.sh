#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLAN_ROOT_DEFAULT="$ROOT_DIR/.planning/local/qwen-runtime-surface"
PLAN_ROOT="${PLAN_ROOT:-}"
PLAN_SYNC_SCRIPT="${PLAN_SYNC_SCRIPT:-$ROOT_DIR/tools/qwen-codex-plan-sync.py}"
QUEUE_SCRIPT="${QUEUE_SCRIPT:-$ROOT_DIR/tools/run-qwen-codex-queue.sh}"
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
PLAN_KEY="${PLAN_KEY:-}"
PLAN_RUNS_ROOT="${PLAN_RUNS_ROOT:-}"
RUN_DIR=""
QUEUE_DIR="${QUEUE_DIR:-}"
QUEUE_RUNS_DIR="${QUEUE_RUNS_DIR:-}"
FLOW_RUNS_ROOT="${FLOW_RUNS_ROOT:-}"
WORKTREE_PARENT="${WORKTREE_PARENT:-}"
ORDERING="${ORDERING:-slice}"
MAX_TASKS="${MAX_TASKS:-0}"

APPROVED_PLAN_STATUS="${APPROVED_PLAN_STATUS:-done}"
NO_CHANGES_PLAN_STATUS="${NO_CHANGES_PLAN_STATUS:-blocked}"
NEEDS_HUMAN_PLAN_STATUS="${NEEDS_HUMAN_PLAN_STATUS:-blocked}"
FAILED_PLAN_STATUS="${FAILED_PLAN_STATUS:-blocked}"

usage() {
    cat <<'EOF'
Usage:
  bash tools/run-qwen-codex-plan.sh
  bash tools/run-qwen-codex-plan.sh /abs/path/to/planning-root
EOF
}

log() {
    printf '[implementer-codex-plan] %s\n' "$*" >&2
}

fail() {
    log "ERROR: $*"
    exit 1
}

main() {
    if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
        usage
        exit 0
    fi

    PLAN_ROOT="${PLAN_ROOT:-${1:-$PLAN_ROOT_DEFAULT}}"
    PLAN_KEY="${PLAN_KEY:-$(basename "$PLAN_ROOT")}"
    PLAN_RUNS_ROOT="${PLAN_RUNS_ROOT:-$ROOT_DIR/.runs/qwen-codex-plan/$PLAN_KEY}"
    RUN_DIR="$PLAN_RUNS_ROOT/runs/$RUN_ID"
    QUEUE_DIR="${QUEUE_DIR:-$PLAN_ROOT/queue}"
    QUEUE_RUNS_DIR="${QUEUE_RUNS_DIR:-$PLAN_RUNS_ROOT/queue-runs}"
    FLOW_RUNS_ROOT="${FLOW_RUNS_ROOT:-$PLAN_RUNS_ROOT/flow-runs}"
    WORKTREE_PARENT="${WORKTREE_PARENT:-$PLAN_RUNS_ROOT/_worktrees}"

    command -v python3 >/dev/null 2>&1 || fail "python3 is required"
    [[ -f "$PLAN_SYNC_SCRIPT" ]] || fail "Plan sync script not found: $PLAN_SYNC_SCRIPT"
    [[ -x "$QUEUE_SCRIPT" ]] || fail "Queue script is not executable: $QUEUE_SCRIPT"
    [[ -d "$PLAN_ROOT" ]] || fail "Planning root not found: $PLAN_ROOT"
    [[ -f "$PLAN_ROOT/BACKLOG.md" ]] || fail "Missing BACKLOG.md in planning root: $PLAN_ROOT"

    mkdir -p "$RUN_DIR" "$QUEUE_DIR"

    local enqueue_json="$RUN_DIR/enqueue.json"
    local apply_json="$RUN_DIR/apply-results.json"

    log "Enqueuing backlog items from $PLAN_ROOT"
    python3 "$PLAN_SYNC_SCRIPT" enqueue \
        --plan-root "$PLAN_ROOT" \
        --queue-dir "$QUEUE_DIR" \
        --ordering "$ORDERING" \
        --max-tasks "$MAX_TASKS" \
        --output-json "$enqueue_json"

    local enqueued_count
    enqueued_count="$(python3 - "$enqueue_json" <<'PY'
import json
import sys
with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)
print(int(payload.get("enqueued_count", 0)))
PY
)"

    if [[ "$enqueued_count" == "0" ]]; then
        log "No todo tasks were enqueued from the planning bundle"
        exit 0
    fi

    local queue_exit=0
    log "Running queue flow for $enqueued_count planning tasks"
    set +e
    RUN_ID="$RUN_ID" \
    QUEUE_DIR="$QUEUE_DIR" \
    QUEUE_RUNS_DIR="$QUEUE_RUNS_DIR" \
    FLOW_RUNS_ROOT="$FLOW_RUNS_ROOT" \
    WORKTREE_PARENT="$WORKTREE_PARENT" \
    MAX_TASKS="$MAX_TASKS" \
    bash "$QUEUE_SCRIPT"
    queue_exit=$?
    set -e

    log "Applying queue results back into planning bundle"
    python3 "$PLAN_SYNC_SCRIPT" apply-results \
        --plan-root "$PLAN_ROOT" \
        --queue-run-dir "$QUEUE_RUNS_DIR/$RUN_ID" \
        --approved-status "$APPROVED_PLAN_STATUS" \
        --no-changes-status "$NO_CHANGES_PLAN_STATUS" \
        --needs-human-status "$NEEDS_HUMAN_PLAN_STATUS" \
        --failed-status "$FAILED_PLAN_STATUS" \
        --output-json "$apply_json"

    cat >"$RUN_DIR/SUMMARY.md" <<EOF
# Implementer Codex Plan Run Summary

- run_id: \`$RUN_ID\`
- plan_root: \`$PLAN_ROOT\`
- queue_dir: \`$QUEUE_DIR\`
- queue_run_dir: \`$QUEUE_RUNS_DIR/$RUN_ID\`
- flow_runs_root: \`$FLOW_RUNS_ROOT\`
- enqueue_json: \`$enqueue_json\`
- apply_results_json: \`$apply_json\`
- queue_exit: \`$queue_exit\`
EOF

    exit "$queue_exit"
}

main "$@"
