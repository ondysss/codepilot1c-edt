#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMPLATES_DIR="${TEMPLATES_DIR:-$ROOT_DIR/tasks/qwen-codex-queue/templates}"
QUEUE_DIR="${QUEUE_DIR:-$ROOT_DIR/.runs/qwen-codex-queue/queue}"
TODO_DIR="$QUEUE_DIR/todo"

usage() {
    cat <<'EOF'
Usage:
  bash tools/new-qwen-codex-task.sh --list
  bash tools/new-qwen-codex-task.sh <template> "<task slug>"

Examples:
  bash tools/new-qwen-codex-task.sh bugfix-minimal "fix metadata sync null guard"
  bash tools/new-qwen-codex-task.sh review-followup "address codex review finding"
EOF
}

fail() {
    printf '[new-qwen-codex-task] ERROR: %s\n' "$*" >&2
    exit 1
}

slugify() {
    printf '%s' "$1" \
        | tr '[:upper:]' '[:lower:]' \
        | sed -E 's/[^a-z0-9]+/-/g; s/^-+//; s/-+$//; s/-+/-/g'
}

list_templates() {
    find "$TEMPLATES_DIR" -maxdepth 1 -type f -name '*.md' -print \
        | xargs -n1 basename \
        | sed 's/\.md$//' \
        | sort
}

next_prefix() {
    python3 - "$QUEUE_DIR" <<'PY'
from pathlib import Path
import re
import sys

queue_dir = Path(sys.argv[1])
max_num = 0
for path in queue_dir.glob("*/*.md"):
    match = re.match(r"(\d+)-", path.name)
    if match:
        max_num = max(max_num, int(match.group(1)))
print(f"{max_num + 1:03d}")
PY
}

main() {
    if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
        usage
        exit 0
    fi
    if [[ "${1:-}" == "--list" ]]; then
        list_templates
        exit 0
    fi

    local template_name="${1:-}"
    local task_slug_raw="${2:-}"
    [[ -n "$template_name" && -n "$task_slug_raw" ]] || fail "template and task slug are required"

    local template_path="$TEMPLATES_DIR/$template_name.md"
    [[ -f "$template_path" ]] || fail "template not found: $template_name"

    mkdir -p "$TODO_DIR"

    local prefix task_slug output_path
    prefix="$(next_prefix)"
    task_slug="$(slugify "$task_slug_raw")"
    [[ -n "$task_slug" ]] || fail "task slug resolved to empty"
    output_path="$TODO_DIR/$prefix-$task_slug.md"

    cp "$template_path" "$output_path"
    python3 - "$output_path" "$task_slug_raw" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
title = sys.argv[2]
text = path.read_text(encoding="utf-8")
text = text.replace("{{TITLE}}", title)
path.write_text(text, encoding="utf-8")
PY
    printf '%s\n' "$output_path"
}

main "$@"
