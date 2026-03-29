#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    exec bash "$ROOT_DIR/tools/run-qwen-codex-plan.sh" "$1"
fi

PLAN_ROOT="${1:-${PLAN_ROOT:-$ROOT_DIR/.planning/local/qwen-runtime-surface}}"
PLAN_KEY="${PLAN_KEY:-$(basename "$PLAN_ROOT")}"
PLAN_RUNS_ROOT_DEFAULT="$ROOT_DIR/.runs/claude-codex-plan/$PLAN_KEY"

export PLAN_ROOT
export PLAN_RUNS_ROOT="${PLAN_RUNS_ROOT:-$PLAN_RUNS_ROOT_DEFAULT}"
export QUEUE_SCRIPT="${QUEUE_SCRIPT:-$ROOT_DIR/tools/run-claude-codex-queue.sh}"
export FLOW_RUNS_ROOT="${FLOW_RUNS_ROOT:-$PLAN_RUNS_ROOT/flow-runs}"
export QUEUE_RUNS_DIR="${QUEUE_RUNS_DIR:-$PLAN_RUNS_ROOT/queue-runs}"
export WORKTREE_PARENT="${WORKTREE_PARENT:-$PLAN_RUNS_ROOT/_worktrees}"
export IMPLEMENTER="${IMPLEMENTER:-claude}"
export CLAUDE_MODEL="${CLAUDE_MODEL:-claude-sonnet-4-6}"
export CLAUDE_OUTPUT_FORMAT="${CLAUDE_OUTPUT_FORMAT:-stream-json}"
export CLAUDE_PERMISSION_MODE="${CLAUDE_PERMISSION_MODE:-acceptEdits}"
export CLAUDE_ALLOWED_TOOLS="${CLAUDE_ALLOWED_TOOLS:-Read,Edit,MultiEdit,Write,Glob,Grep,LS,Bash}"
export CLAUDE_EFFORT="${CLAUDE_EFFORT:-medium}"

exec bash "$ROOT_DIR/tools/run-qwen-codex-plan.sh" "$PLAN_ROOT"
