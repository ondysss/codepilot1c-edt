#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

export IMPLEMENTER="${IMPLEMENTER:-claude}"
export RUNS_ROOT="${RUNS_ROOT:-$ROOT_DIR/.runs/claude-codex-flow}"
export WORKTREE_PARENT="${WORKTREE_PARENT:-$RUNS_ROOT/_worktrees}"
export BRANCH_PREFIX="${BRANCH_PREFIX:-codex/claude-flow}"
export CLAUDE_MODEL="${CLAUDE_MODEL:-claude-sonnet-4-6}"
export CLAUDE_OUTPUT_FORMAT="${CLAUDE_OUTPUT_FORMAT:-stream-json}"
export CLAUDE_PERMISSION_MODE="${CLAUDE_PERMISSION_MODE:-acceptEdits}"
export CLAUDE_ALLOWED_TOOLS="${CLAUDE_ALLOWED_TOOLS:-Read,Edit,MultiEdit,Write,Glob,Grep,LS,Bash}"
export CLAUDE_EFFORT="${CLAUDE_EFFORT:-medium}"

exec bash "$ROOT_DIR/tools/run-qwen-codex-flow.sh" "$@"
