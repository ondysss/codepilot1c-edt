#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage:
  bash tools/run-claude-host.sh --cwd /abs/path --stdout /abs/stdout.log --stderr /abs/stderr.log -- command arg...

Description:
  Launches a Claude CLI command in macOS Terminal so the Claude process runs
  outside the caller's filesystem sandbox, while this wrapper waits for the
  exit code and writes stdout/stderr to explicit files.

Env:
  CLAUDE_HOST_TIMEOUT_SEC=7200
EOF
}

fail() {
    printf '[claude-host] ERROR: %s\n' "$*" >&2
    exit 1
}

shell_quote() {
    printf '%q' "$1"
}

[[ "${1:-}" != "-h" && "${1:-}" != "--help" ]] || {
    usage
    exit 0
}

CWD=""
STDOUT_PATH=""
STDERR_PATH=""

while (($# > 0)); do
    case "$1" in
        --cwd)
            CWD="${2:-}"
            shift 2
            ;;
        --stdout)
            STDOUT_PATH="${2:-}"
            shift 2
            ;;
        --stderr)
            STDERR_PATH="${2:-}"
            shift 2
            ;;
        --)
            shift
            break
            ;;
        *)
            fail "Unknown argument: $1"
            ;;
    esac
done

[[ -n "$CWD" ]] || fail "--cwd is required"
[[ -d "$CWD" ]] || fail "Working directory does not exist: $CWD"
[[ -n "$STDOUT_PATH" ]] || fail "--stdout is required"
[[ -n "$STDERR_PATH" ]] || fail "--stderr is required"
(($# > 0)) || fail "Command is required after --"

command -v osascript >/dev/null 2>&1 || fail "osascript is required"
mkdir -p "$(dirname "$STDOUT_PATH")" "$(dirname "$STDERR_PATH")"

RUN_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/claude-host.XXXXXX")"
STATUS_PATH="$RUN_ROOT/exit_code"
SCRIPT_PATH="$RUN_ROOT/run.sh"

cleanup() {
    rm -rf "$RUN_ROOT"
}
trap cleanup EXIT

{
    printf '#!/usr/bin/env bash\n'
    printf 'set -euo pipefail\n'
    printf 'cd %s\n' "$(shell_quote "$CWD")"
    printf 'set +e\n'
    for arg in "$@"; do
        printf '%s ' "$(shell_quote "$arg")"
    done
    printf '>%s 2>%s\n' "$(shell_quote "$STDOUT_PATH")" "$(shell_quote "$STDERR_PATH")"
    printf 'rc=$?\n'
    printf 'set -e\n'
    printf 'printf "%%s\\n" "$rc" >%s\n' "$(shell_quote "$STATUS_PATH")"
    printf 'exit "$rc"\n'
} >"$SCRIPT_PATH"

chmod 700 "$SCRIPT_PATH"

TERMINAL_COMMAND="/bin/bash $(shell_quote "$SCRIPT_PATH")"
osascript - "$TERMINAL_COMMAND" <<'APPLESCRIPT'
on run argv
    set terminalCommand to item 1 of argv
    tell application "Terminal"
        activate
        do script terminalCommand
    end tell
end run
APPLESCRIPT

timeout_sec="${CLAUDE_HOST_TIMEOUT_SEC:-7200}"
elapsed=0
while [[ ! -f "$STATUS_PATH" ]]; do
    if (( elapsed >= timeout_sec )); then
        fail "Timed out waiting for Claude host command after ${timeout_sec}s"
    fi
    sleep 1
    elapsed=$((elapsed + 1))
done

exit_code="$(tr -d '[:space:]' <"$STATUS_PATH")"
[[ "$exit_code" =~ ^[0-9]+$ ]] || fail "Invalid exit code produced by host command: ${exit_code:-<empty>}"
exit "$exit_code"
