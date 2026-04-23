#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
RUNS_ROOT="${RUNS_ROOT:-$ROOT_DIR/.runs/qwen-codex-flow}"
WORKTREE_PARENT="${WORKTREE_PARENT:-$RUNS_ROOT/_worktrees}"
MAX_ROUNDS="${MAX_ROUNDS:-3}"
KEEP_WORKTREE="${KEEP_WORKTREE:-true}"
CLEAN_WORKTREE_ON_SUCCESS="${CLEAN_WORKTREE_ON_SUCCESS:-false}"
IMPLEMENTER="${IMPLEMENTER:-qwen}"
BRANCH_PREFIX="${BRANCH_PREFIX:-codex/${IMPLEMENTER}-flow}"

QWEN_BIN="${QWEN_BIN:-qwen}"
QWEN_MODEL="${QWEN_MODEL:-}"
QWEN_AUTH_TYPE="${QWEN_AUTH_TYPE:-}"
QWEN_APPROVAL_MODE="${QWEN_APPROVAL_MODE:-auto-edit}"
QWEN_OUTPUT_FORMAT="${QWEN_OUTPUT_FORMAT:-stream-json}"
QWEN_MAX_SESSION_TURNS="${QWEN_MAX_SESSION_TURNS:-}"
CLAUDE_BIN="${CLAUDE_BIN:-claude}"
CLAUDE_MODEL="${CLAUDE_MODEL:-claude-sonnet-4-6}"
CLAUDE_OUTPUT_FORMAT="${CLAUDE_OUTPUT_FORMAT:-stream-json}"
CLAUDE_PERMISSION_MODE="${CLAUDE_PERMISSION_MODE:-acceptEdits}"
CLAUDE_ALLOWED_TOOLS="${CLAUDE_ALLOWED_TOOLS:-Read,Edit,MultiEdit,Write,Glob,Grep,LS,Bash}"
CLAUDE_EFFORT="${CLAUDE_EFFORT:-medium}"
CLAUDE_MAX_BUDGET_USD="${CLAUDE_MAX_BUDGET_USD:-}"
CLAUDE_LAUNCH_MODE="${CLAUDE_LAUNCH_MODE:-auto}"
CLAUDE_HOST_LAUNCHER="${CLAUDE_HOST_LAUNCHER:-$ROOT_DIR/tools/run-claude-host.sh}"
CLAUDE_SOURCE_HOME="${CLAUDE_SOURCE_HOME:-$HOME/.claude}"

CODEX_BIN="${CODEX_BIN:-codex}"
CODEX_MODEL="${CODEX_MODEL:-}"
CODEX_SOURCE_HOME="${CODEX_SOURCE_HOME:-${CODEX_HOME:-$HOME/.codex}}"
CODEX_REVIEW_ISOLATE_HOME="${CODEX_REVIEW_ISOLATE_HOME:-true}"

ARTIFACT_DIR="$RUNS_ROOT/$RUN_ID"
PROMPTS_DIR="$ARTIFACT_DIR/prompts"
LOGS_DIR="$ARTIFACT_DIR/logs"
REVIEWS_DIR="$ARTIFACT_DIR/reviews"
SNAPSHOTS_DIR="$ARTIFACT_DIR/snapshots"
TASK_DIR="$ARTIFACT_DIR/task"
REVIEW_SCHEMA_PATH="$ARTIFACT_DIR/review-schema.json"
CLAUDE_HOME_DIR="${CLAUDE_HOME_DIR:-$ARTIFACT_DIR/claude-home}"
CODEX_REVIEW_HOME_DIR="$ARTIFACT_DIR/codex-home"

WORKTREE_DIR=""
BRANCH_NAME=""
BASE_BRANCH="${BASE_BRANCH:-}"
BASE_REF=""
TASK_PATH="$TASK_DIR/task.md"
TASK_SLUG="${TASK_SLUG:-}"
IMPLEMENTER_SESSION_ID=""
IMPLEMENTER_BASE_ARGS=()

usage() {
    cat <<'EOF'
Usage:
  bash tools/run-qwen-codex-flow.sh /abs/path/to/task.md
  echo "Fix the failing test in FooTest and keep scope minimal." | bash tools/run-qwen-codex-flow.sh -

Description:
  Runs a single task through this loop:
    1. create isolated git worktree
    2. implement with the selected coding agent
    3. review the diff with codex
    4. if defects are found, send the review back to the same implementer for another fix round

Key env vars:
  IMPLEMENTER=qwen|claude
  BASE_BRANCH=main|master|feature/base
  MAX_ROUNDS=3
  KEEP_WORKTREE=true|false
  CLEAN_WORKTREE_ON_SUCCESS=true|false
  RUN_ID=custom-run-id
  QWEN_MODEL=...
  QWEN_AUTH_TYPE=openai|qwen-oauth|...
  QWEN_APPROVAL_MODE=auto-edit|yolo|...
  CLAUDE_MODEL=claude-sonnet-4-6
  CLAUDE_PERMISSION_MODE=acceptEdits|bypassPermissions|auto|...
  CLAUDE_ALLOWED_TOOLS=Read,Edit,MultiEdit,Write,Glob,Grep,LS,Bash
  CLAUDE_LAUNCH_MODE=auto|direct|host
  CODEX_MODEL=...
  CODEX_REVIEW_ISOLATE_HOME=true|false

Artifacts:
  .runs/qwen-codex-flow/<run-id>/

Exit codes:
  0  approved by codex
  1  script/setup/runtime failure
  2  implementer produced no code changes
  3  codex still reports defects after MAX_ROUNDS
EOF
}

log() {
    printf '[%s-codex-flow] %s\n' "$IMPLEMENTER" "$*" >&2
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

validate_implementer() {
    case "$IMPLEMENTER" in
        qwen|claude) ;;
        *) fail "Unsupported IMPLEMENTER: $IMPLEMENTER (expected qwen or claude)" ;;
    esac
}

slugify() {
    printf '%s' "$1" \
        | tr '[:upper:]' '[:lower:]' \
        | sed -E 's/[^a-z0-9]+/-/g; s/^-+//; s/-+$//; s/-+/-/g'
}

detect_base_branch() {
    local remote_head=""
    remote_head="$(git -C "$ROOT_DIR" symbolic-ref --quiet --short refs/remotes/origin/HEAD 2>/dev/null || true)"
    if [[ -n "$remote_head" ]]; then
        printf '%s\n' "${remote_head#origin/}"
        return 0
    fi
    for candidate in main master; do
        if git -C "$ROOT_DIR" rev-parse --verify --quiet "$candidate^{commit}" >/dev/null; then
            printf '%s\n' "$candidate"
            return 0
        fi
        if git -C "$ROOT_DIR" rev-parse --verify --quiet "origin/$candidate^{commit}" >/dev/null; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    git -C "$ROOT_DIR" branch --show-current 2>/dev/null || printf 'main\n'
}

resolve_base_ref() {
    local candidate
    for candidate in "$BASE_BRANCH" "origin/$BASE_BRANCH"; do
        if git -C "$ROOT_DIR" rev-parse --verify --quiet "$candidate^{commit}" >/dev/null; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

prepare_layout() {
    mkdir -p "$ARTIFACT_DIR" "$PROMPTS_DIR" "$LOGS_DIR" "$REVIEWS_DIR" "$SNAPSHOTS_DIR" "$TASK_DIR" "$WORKTREE_PARENT"
}

copy_claude_home_entry() {
    local name="$1"
    local source_path="$CLAUDE_SOURCE_HOME/$name"
    local target_path="$CLAUDE_HOME_DIR/$name"
    if [[ ! -e "$source_path" ]]; then
        return 0
    fi
    if [[ -d "$source_path" ]]; then
        mkdir -p "$target_path"
        cp -R "$source_path/." "$target_path/"
        return 0
    fi
    mkdir -p "$(dirname "$target_path")"
    cp "$source_path" "$target_path"
}

prepare_claude_home() {
    if [[ "$IMPLEMENTER" != "claude" ]]; then
        return 0
    fi
    if [[ "$CLAUDE_HOME_DIR" == "$CLAUDE_SOURCE_HOME" ]]; then
        return 0
    fi

    mkdir -p "$CLAUDE_HOME_DIR"

    copy_claude_home_entry "settings.json"
    copy_claude_home_entry "settings.local.json"
    copy_claude_home_entry ".mcp.json"
    copy_claude_home_entry "sessions"
    copy_claude_home_entry "agents"
    copy_claude_home_entry "hooks"
    copy_claude_home_entry "plugins"
    copy_claude_home_entry "commands"
    copy_claude_home_entry "scripts"
}

prepare_codex_review_home() {
    if ! bool_true "$CODEX_REVIEW_ISOLATE_HOME"; then
        return 0
    fi

    mkdir -p "$CODEX_REVIEW_HOME_DIR"

    if [[ -f "$CODEX_SOURCE_HOME/auth.json" ]]; then
        cp "$CODEX_SOURCE_HOME/auth.json" "$CODEX_REVIEW_HOME_DIR/auth.json"
        chmod 600 "$CODEX_REVIEW_HOME_DIR/auth.json" || true
    fi
    cat >"$CODEX_REVIEW_HOME_DIR/config.toml" <<'EOF'
personality = "pragmatic"
suppress_unstable_features_warning = true
EOF
}

read_task_input() {
    local input="${1:-}"
    if [[ -z "$input" ]]; then
        fail "Task file is required. Use '-' to read task text from stdin."
    fi
    if [[ "$input" == "-" ]]; then
        cat >"$TASK_PATH"
    else
        [[ -f "$input" ]] || fail "Task file not found: $input"
        cp "$input" "$TASK_PATH"
    fi
    [[ -s "$TASK_PATH" ]] || fail "Task input is empty"
}

derive_task_slug() {
    local base_name=""
    base_name="$(basename "$TASK_PATH")"
    base_name="${base_name%.*}"
    TASK_SLUG="${TASK_SLUG:-$(slugify "$base_name")}"
    if [[ "$TASK_SLUG" == "task" || -z "$TASK_SLUG" ]]; then
        local first_heading=""
        first_heading="$(sed -n '1{/^#\{1,\} /s/^#\{1,\} //p; q;}' "$TASK_PATH")"
        TASK_SLUG="$(slugify "${first_heading:-task}")"
    fi
    TASK_SLUG="${TASK_SLUG:-task}"
}

prepare_git_context() {
    git -C "$ROOT_DIR" rev-parse --show-toplevel >/dev/null 2>&1 || fail "Repository root is not a git checkout: $ROOT_DIR"
    BASE_BRANCH="${BASE_BRANCH:-$(detect_base_branch)}"
    BASE_REF="$(resolve_base_ref)" || fail "Base branch not found locally or on origin: $BASE_BRANCH"
    BRANCH_NAME="${BRANCH_PREFIX%/}/${TASK_SLUG}-${RUN_ID}"
    WORKTREE_DIR="$WORKTREE_PARENT/${TASK_SLUG}-${RUN_ID}"
    IMPLEMENTER_SESSION_ID="$(python3 - <<'PY'
import uuid
print(uuid.uuid4())
PY
)"
}

create_worktree() {
    [[ ! -e "$WORKTREE_DIR" ]] || fail "Worktree path already exists: $WORKTREE_DIR"
    log "Creating worktree $WORKTREE_DIR from $BASE_REF on branch $BRANCH_NAME"
    git -C "$ROOT_DIR" worktree add -b "$BRANCH_NAME" "$WORKTREE_DIR" "$BASE_REF" >"$LOGS_DIR/worktree-add.log" 2>&1
}

cleanup_worktree() {
    if [[ -z "$WORKTREE_DIR" || ! -d "$WORKTREE_DIR" ]]; then
        return 0
    fi
    if ! git -C "$ROOT_DIR" worktree list | grep -Fq "$WORKTREE_DIR"; then
        return 0
    fi
    log "Removing worktree $WORKTREE_DIR"
    git -C "$ROOT_DIR" worktree remove --force "$WORKTREE_DIR" >"$LOGS_DIR/worktree-remove.log" 2>&1 || true
}

maybe_cleanup_on_success() {
    if bool_true "$CLEAN_WORKTREE_ON_SUCCESS"; then
        cleanup_worktree
    fi
}

write_metadata() {
    cat >"$ARTIFACT_DIR/run.env" <<EOF
RUN_ID=$RUN_ID
ROOT_DIR=$ROOT_DIR
BASE_BRANCH=$BASE_BRANCH
BASE_REF=$BASE_REF
BRANCH_NAME=$BRANCH_NAME
WORKTREE_DIR=$WORKTREE_DIR
TASK_PATH=$TASK_PATH
IMPLEMENTER=$IMPLEMENTER
IMPLEMENTER_SESSION_ID=$IMPLEMENTER_SESSION_ID
MAX_ROUNDS=$MAX_ROUNDS
KEEP_WORKTREE=$KEEP_WORKTREE
CLEAN_WORKTREE_ON_SUCCESS=$CLEAN_WORKTREE_ON_SUCCESS
QWEN_BIN=$QWEN_BIN
QWEN_MODEL=$QWEN_MODEL
QWEN_AUTH_TYPE=$QWEN_AUTH_TYPE
QWEN_APPROVAL_MODE=$QWEN_APPROVAL_MODE
QWEN_OUTPUT_FORMAT=$QWEN_OUTPUT_FORMAT
CLAUDE_BIN=$CLAUDE_BIN
CLAUDE_MODEL=$CLAUDE_MODEL
CLAUDE_OUTPUT_FORMAT=$CLAUDE_OUTPUT_FORMAT
CLAUDE_PERMISSION_MODE=$CLAUDE_PERMISSION_MODE
CLAUDE_ALLOWED_TOOLS=$CLAUDE_ALLOWED_TOOLS
CLAUDE_EFFORT=$CLAUDE_EFFORT
CLAUDE_MAX_BUDGET_USD=$CLAUDE_MAX_BUDGET_USD
CLAUDE_LAUNCH_MODE=$CLAUDE_LAUNCH_MODE
CLAUDE_HOST_LAUNCHER=$CLAUDE_HOST_LAUNCHER
CLAUDE_SOURCE_HOME=$CLAUDE_SOURCE_HOME
CLAUDE_HOME_DIR=$CLAUDE_HOME_DIR
CODEX_BIN=$CODEX_BIN
CODEX_MODEL=$CODEX_MODEL
CODEX_SOURCE_HOME=$CODEX_SOURCE_HOME
CODEX_REVIEW_ISOLATE_HOME=$CODEX_REVIEW_ISOLATE_HOME
CODEX_REVIEW_HOME_DIR=$CODEX_REVIEW_HOME_DIR
EOF
}

write_initial_implementer_prompt() {
    local prompt_path="$PROMPTS_DIR/implementer-implement.md"
    cat >"$prompt_path" <<EOF
Implement the coding task below in the current repository worktree.

Execution rules:
- Keep changes minimal and scoped to the task.
- Follow the repository's AGENTS.md and local conventions.
- Prefer editing existing code over adding new abstractions.
- Run the smallest relevant verification commands before finishing.
- Do not commit, rebase, or push.
- If blocked, state the exact blocker instead of guessing.

Final response format:
- Summary: 1-3 sentences.
- Changed files: explicit list.
- Verification: commands run and results.
- Blockers: only if any remain.

Task:

$(cat "$TASK_PATH")
EOF
}

write_fix_prompt() {
    local round="$1"
    local review_json_path="$2"
    local prompt_path="$PROMPTS_DIR/implementer-fix-round-$round.md"
    cat >"$prompt_path" <<EOF
Codex reviewed your latest diff and found defects.

Apply only the required fixes below.

Rules:
- Keep the existing good changes.
- Do not expand scope beyond the findings.
- Run the smallest relevant verification commands after fixing.
- Do not commit, rebase, or push.

Codex review JSON:

$(cat "$review_json_path")
EOF
}

write_codex_review_prompt() {
    local prompt_path="$PROMPTS_DIR/codex-review.md"
    cat >"$prompt_path" <<'EOF'
Review the current uncommitted changes in this repository as a strict code reviewer.

Start from `git status --short` and review only the changed or untracked files plus the minimum surrounding code needed to judge correctness. Do not expand into broad repository exploration unless a changed contract forces it.

Focus only on:
- correctness defects
- behavioral regressions
- contract violations
- risky edge cases introduced by the change
- missing tests that are necessary for the changed behavior

Review method:
- Inspect the diff and the minimum surrounding code needed to judge correctness.
- Do not run builds, test suites, package managers, or long verification commands during review.
- If extra verification would be useful, list it in `tests` instead of executing it.

Ignore:
- style-only comments
- optional refactors
- naming preferences
- speculative improvements

Return only valid JSON with this exact shape:
{
  "status": "APPROVED" | "NEEDS_FIXES",
  "summary": "short summary",
  "findings": [
    {
      "severity": "high" | "medium" | "low",
      "title": "short finding title",
      "details": "why this is a defect",
      "location": "path:line or n/a",
      "suggested_fix": "concrete next action"
    }
  ],
  "tests": ["required missing test or verification step"]
}

Rules:
- Use "APPROVED" only when no actionable defects remain.
- If status is "APPROVED", findings must be an empty array.
- Keep findings non-duplicative and concrete.
- Return JSON only. No markdown.
EOF
}

write_codex_review_schema() {
    cat >"$REVIEW_SCHEMA_PATH" <<'EOF'
{
  "type": "object",
  "additionalProperties": false,
  "required": ["status", "summary", "findings", "tests"],
  "properties": {
    "status": {
      "type": "string",
      "enum": ["APPROVED", "NEEDS_FIXES"]
    },
    "summary": {
      "type": "string"
    },
    "findings": {
      "type": "array",
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": ["severity", "title", "details", "location", "suggested_fix"],
        "properties": {
          "severity": {
            "type": "string",
            "enum": ["high", "medium", "low"]
          },
          "title": {
            "type": "string"
          },
          "details": {
            "type": "string"
          },
          "location": {
            "type": "string"
          },
          "suggested_fix": {
            "type": "string"
          }
        }
      }
    },
    "tests": {
      "type": "array",
      "items": {
        "type": "string"
      }
    }
  }
}
EOF
}

build_implementer_base_args() {
    IMPLEMENTER_BASE_ARGS=()
    case "$IMPLEMENTER" in
        qwen)
            IMPLEMENTER_BASE_ARGS=(
                "$QWEN_BIN"
                "--approval-mode" "$QWEN_APPROVAL_MODE"
                "--output-format" "$QWEN_OUTPUT_FORMAT"
                "--chat-recording"
                "--channel" "CI"
            )
            if [[ "$QWEN_OUTPUT_FORMAT" == "stream-json" ]]; then
                IMPLEMENTER_BASE_ARGS+=("--include-partial-messages")
            fi
            if [[ -n "$QWEN_MODEL" ]]; then
                IMPLEMENTER_BASE_ARGS+=("--model" "$QWEN_MODEL")
            fi
            if [[ -n "$QWEN_AUTH_TYPE" ]]; then
                IMPLEMENTER_BASE_ARGS+=("--auth-type" "$QWEN_AUTH_TYPE")
            fi
            if [[ -n "$QWEN_MAX_SESSION_TURNS" ]]; then
                IMPLEMENTER_BASE_ARGS+=("--max-session-turns" "$QWEN_MAX_SESSION_TURNS")
            fi
            ;;
        claude)
            IMPLEMENTER_BASE_ARGS=(
                "$CLAUDE_BIN"
                "-p"
                "--output-format" "$CLAUDE_OUTPUT_FORMAT"
                "--permission-mode" "$CLAUDE_PERMISSION_MODE"
            )
            if [[ "$CLAUDE_OUTPUT_FORMAT" == "stream-json" ]]; then
                IMPLEMENTER_BASE_ARGS+=("--include-partial-messages")
            fi
            if [[ -n "$CLAUDE_MODEL" ]]; then
                IMPLEMENTER_BASE_ARGS+=("--model" "$CLAUDE_MODEL")
            fi
            if [[ -n "$CLAUDE_EFFORT" ]]; then
                IMPLEMENTER_BASE_ARGS+=("--effort" "$CLAUDE_EFFORT")
            fi
            if [[ -n "$CLAUDE_ALLOWED_TOOLS" ]]; then
                IMPLEMENTER_BASE_ARGS+=("--allowedTools" "$CLAUDE_ALLOWED_TOOLS")
            fi
            if [[ -n "$CLAUDE_MAX_BUDGET_USD" ]]; then
                IMPLEMENTER_BASE_ARGS+=("--max-budget-usd" "$CLAUDE_MAX_BUDGET_USD")
            fi
            ;;
    esac
}

is_claude_home_writable() {
    local target="$CLAUDE_HOME_DIR"
    if [[ -e "$target" ]]; then
        [[ -w "$target" ]]
        return
    fi
    [[ -w "$(dirname "$target")" ]]
}

should_use_claude_host_launcher() {
    case "$CLAUDE_LAUNCH_MODE" in
        direct)
            return 1
            ;;
        host)
            return 0
            ;;
        auto)
            if is_claude_home_writable; then
                return 1
            fi
            return 0
            ;;
        *)
            fail "Unsupported CLAUDE_LAUNCH_MODE: $CLAUDE_LAUNCH_MODE (expected auto, direct, or host)"
            ;;
    esac
}

run_claude_command() {
    local stdout_path="$1"
    local stderr_path="$2"
    shift 2
    if should_use_claude_host_launcher; then
        log "Launching Claude via host launcher ($CLAUDE_HOST_LAUNCHER)"
        CLAUDE_HOME_DIR="$CLAUDE_HOME_DIR" bash "$CLAUDE_HOST_LAUNCHER" \
            --cwd "$WORKTREE_DIR" \
            --stdout "$stdout_path" \
            --stderr "$stderr_path" \
            -- "$@"
        return
    fi

    (
        cd "$WORKTREE_DIR"
        CLAUDE_HOME_DIR="$CLAUDE_HOME_DIR" "$@"
    ) >"$stdout_path" 2>"$stderr_path"
}

run_implementer_initial() {
    local prompt
    local stdout_path="$LOGS_DIR/${IMPLEMENTER}-round-0.stdout"
    local stderr_path="$LOGS_DIR/${IMPLEMENTER}-round-0.stderr"
    local -a cmd
    build_implementer_base_args
    cmd=("${IMPLEMENTER_BASE_ARGS[@]}")
    prompt="$(cat "$PROMPTS_DIR/implementer-implement.md")"
    cmd+=("--session-id" "$IMPLEMENTER_SESSION_ID" "$prompt")
    log "Running $IMPLEMENTER implementation round 0"
    if [[ "$IMPLEMENTER" == "claude" ]]; then
        run_claude_command "$stdout_path" "$stderr_path" "${cmd[@]}"
    else
        (
            cd "$WORKTREE_DIR"
            "${cmd[@]}"
        ) >"$stdout_path" 2>"$stderr_path"
    fi
}

run_implementer_fix_round() {
    local round="$1"
    local prompt
    local stdout_path="$LOGS_DIR/${IMPLEMENTER}-round-$round.stdout"
    local stderr_path="$LOGS_DIR/${IMPLEMENTER}-round-$round.stderr"
    local -a cmd
    build_implementer_base_args
    cmd=("${IMPLEMENTER_BASE_ARGS[@]}")
    prompt="$(cat "$PROMPTS_DIR/implementer-fix-round-$round.md")"
    cmd+=("--resume" "$IMPLEMENTER_SESSION_ID" "$prompt")
    log "Running $IMPLEMENTER fix round $round"
    if [[ "$IMPLEMENTER" == "claude" ]]; then
        run_claude_command "$stdout_path" "$stderr_path" "${cmd[@]}"
    else
        (
            cd "$WORKTREE_DIR"
            "${cmd[@]}"
        ) >"$stdout_path" 2>"$stderr_path"
    fi
}

run_codex_review() {
    local round="$1"
    local review_json_path="$REVIEWS_DIR/review-round-$round.json"
    local review_stdout_path="$LOGS_DIR/codex-review-round-$round.jsonl"
    local prompt
    local -a cmd=(
        "$CODEX_BIN" "exec"
        "--json"
        "-o" "$review_json_path"
        "--output-schema" "$REVIEW_SCHEMA_PATH"
    )
    if [[ -n "$CODEX_MODEL" ]]; then
        cmd+=("--model" "$CODEX_MODEL")
    fi
    prompt="$(cat "$PROMPTS_DIR/codex-review.md")"
    cmd+=("$prompt")
    log "Running codex review round $round"
    (
        cd "$WORKTREE_DIR"
        if bool_true "$CODEX_REVIEW_ISOLATE_HOME"; then
            CODEX_HOME="$CODEX_REVIEW_HOME_DIR" "${cmd[@]}"
        else
            "${cmd[@]}"
        fi
    ) >"$review_stdout_path" 2>"$LOGS_DIR/codex-review-round-$round.stderr"
    printf '%s\n' "$review_json_path"
}

ensure_changes_exist() {
    local round_label="$1"
    if git -C "$WORKTREE_DIR" diff --quiet --no-ext-diff && [[ -z "$(git -C "$WORKTREE_DIR" ls-files --others --exclude-standard)" ]]; then
        log "No changes detected after $round_label"
        return 1
    fi
    return 0
}

snapshot_repo_state() {
    local label="$1"
    git -C "$WORKTREE_DIR" status --short >"$SNAPSHOTS_DIR/$label.status"
    git -C "$WORKTREE_DIR" diff --stat >"$SNAPSHOTS_DIR/$label.diffstat"
    git -C "$WORKTREE_DIR" diff >"$SNAPSHOTS_DIR/$label.diff.patch"
}

validate_review_json() {
    local path="$1"
    python3 - "$path" <<'PY'
import json
import sys

path = sys.argv[1]
with open(path, encoding="utf-8") as handle:
    payload = json.load(handle)
status = payload.get("status")
if status not in {"APPROVED", "NEEDS_FIXES"}:
    raise SystemExit(f"Invalid review status in {path}: {status!r}")
findings = payload.get("findings")
if not isinstance(findings, list):
    raise SystemExit(f"Review findings must be an array in {path}")
if status == "APPROVED" and findings:
    raise SystemExit(f"Approved review must have empty findings in {path}")
tests = payload.get("tests")
if not isinstance(tests, list):
    raise SystemExit(f"Review tests must be an array in {path}")
print(status)
PY
}

write_summary() {
    local status="$1"
    local rounds_used="$2"
    local summary_path="$ARTIFACT_DIR/SUMMARY.md"
    cat >"$summary_path" <<EOF
# Implementer -> Codex Flow Summary

- status: \`$status\`
- implementer: \`$IMPLEMENTER\`
- run_id: \`$RUN_ID\`
- base_branch: \`$BASE_BRANCH\`
- branch_name: \`$BRANCH_NAME\`
- worktree_dir: \`$WORKTREE_DIR\`
- implementer_session_id: \`$IMPLEMENTER_SESSION_ID\`
- rounds_used: \`$rounds_used\`

Artifacts:

- task: \`$TASK_PATH\`
- prompts: \`$PROMPTS_DIR\`
- logs: \`$LOGS_DIR\`
- reviews: \`$REVIEWS_DIR\`
- snapshots: \`$SNAPSHOTS_DIR\`
EOF
}

main() {
    if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
        usage
        exit 0
    fi

    validate_implementer
    require_command git
    require_command python3
    prepare_layout
    case "$IMPLEMENTER" in
        qwen) require_command "$QWEN_BIN" ;;
        claude)
            require_command "$CLAUDE_BIN"
            prepare_claude_home
            if should_use_claude_host_launcher; then
                [[ -x "$CLAUDE_HOST_LAUNCHER" ]] || fail "Claude host launcher is not executable: $CLAUDE_HOST_LAUNCHER"
            fi
            ;;
    esac
    require_command "$CODEX_BIN"

    prepare_codex_review_home
    read_task_input "${1:-}"
    derive_task_slug
    prepare_git_context
    write_metadata
    write_initial_implementer_prompt
    write_codex_review_prompt
    write_codex_review_schema
    create_worktree
    log "Artifacts: $ARTIFACT_DIR"
    log "Worktree: $WORKTREE_DIR"

    run_implementer_initial
    if ! ensure_changes_exist "$IMPLEMENTER round 0"; then
        snapshot_repo_state "round-0-no-changes"
        write_summary "NO_CHANGES" "0"
        if ! bool_true "$KEEP_WORKTREE"; then
            cleanup_worktree
        fi
        exit 2
    fi
    snapshot_repo_state "round-0"

    local round=1
    while (( round <= MAX_ROUNDS )); do
        local review_json_path=""
        local review_status=""
        review_json_path="$(run_codex_review "$round")"
        review_status="$(validate_review_json "$review_json_path")"
        snapshot_repo_state "review-$round"

        if [[ "$review_status" == "APPROVED" ]]; then
            write_summary "APPROVED" "$round"
            maybe_cleanup_on_success
            log "Approved by codex in round $round"
            exit 0
        fi

        if (( round == MAX_ROUNDS )); then
            write_summary "NEEDS_HUMAN" "$round"
            if ! bool_true "$KEEP_WORKTREE"; then
                cleanup_worktree
            fi
            log "Codex still reports defects after $MAX_ROUNDS rounds"
            exit 3
        fi

        write_fix_prompt "$round" "$review_json_path"
        run_implementer_fix_round "$round"
        ensure_changes_exist "$IMPLEMENTER fix round $round" || true
        snapshot_repo_state "post-fix-$round"
        round=$((round + 1))
    done
}

main "$@"
