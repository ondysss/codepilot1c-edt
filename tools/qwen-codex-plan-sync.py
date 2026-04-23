#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass
from pathlib import Path

TASK_LINE_RE = re.compile(
    r"^(?P<prefix>\s*-\s+`(?P<id>QWEN-\d+)`\s+`(?P<priority>P\d+)`\s+`)(?P<status>todo|in_progress|done|blocked)(?P<suffix>`\s+.*)$"
)
PHASE_RE = re.compile(r"^##\s+Phase\s+(?P<num>\d+)\.\s+(?P<title>.+?)\s*$")
SLICE_ID_RE = re.compile(r"^\d+\.\s+`(?P<id>QWEN-\d+)`\s+")
META_RE = re.compile(r"^<!--\s*(?P<key>[a-z0-9_-]+):\s*(?P<value>.*?)\s*-->\s*$")


@dataclass
class TaskEntry:
    task_id: str
    priority: str
    status: str
    title: str
    line_index: int
    phase_num: str
    phase_title: str
    details: dict[str, str]
    order_index: int


def slugify(value: str) -> str:
    value = value.lower()
    value = re.sub(r"[^a-z0-9]+", "-", value)
    value = re.sub(r"-{2,}", "-", value).strip("-")
    return value or "task"


def queue_next_prefix(queue_dir: Path) -> str:
    max_num = 0
    for path in queue_dir.glob("*/*.md"):
        match = re.match(r"(\d+)-", path.name)
        if match:
            max_num = max(max_num, int(match.group(1)))
    return f"{max_num + 1:03d}"


def read_lines(path: Path) -> list[str]:
    return path.read_text(encoding="utf-8").splitlines(keepends=True)


def write_lines(path: Path, lines: list[str]) -> None:
    path.write_text("".join(lines), encoding="utf-8")


def parse_backlog(backlog_path: Path) -> tuple[list[str], list[TaskEntry]]:
    lines = read_lines(backlog_path)
    phase_num = ""
    phase_title = ""
    tasks: list[TaskEntry] = []
    order_index = 0

    index = 0
    while index < len(lines):
        line = lines[index]
        phase_match = PHASE_RE.match(line.strip())
        if phase_match:
            phase_num = f"{int(phase_match.group('num')):02d}"
            phase_title = phase_match.group("title").strip()
            index += 1
            continue

        task_match = TASK_LINE_RE.match(line)
        if not task_match:
            index += 1
            continue

        details: dict[str, str] = {}
        detail_index = index + 1
        while detail_index < len(lines):
            next_line = lines[detail_index]
            if TASK_LINE_RE.match(next_line) or PHASE_RE.match(next_line.strip()) or next_line.startswith("### "):
                break
            stripped = next_line.strip()
            if ":" in stripped:
                key, value = stripped.split(":", 1)
                key = key.strip().lower()
                value = value.strip()
                if key in {"files", "done when"}:
                    details[key] = value
            detail_index += 1

        title = task_match.group("suffix").strip()
        if title.startswith("`"):
            title = title[1:].lstrip()
        if title.endswith("."):
            title = title[:-1]

        tasks.append(
            TaskEntry(
                task_id=task_match.group("id"),
                priority=task_match.group("priority"),
                status=task_match.group("status"),
                title=title,
                line_index=index,
                phase_num=phase_num,
                phase_title=phase_title,
                details=details,
                order_index=order_index,
            )
        )
        order_index += 1
        index = detail_index

    return lines, tasks


def slice_order(execution_slice_path: Path) -> list[str]:
    if not execution_slice_path.exists():
        return []
    ids: list[str] = []
    for line in execution_slice_path.read_text(encoding="utf-8").splitlines():
        match = SLICE_ID_RE.match(line.strip())
        if match:
            ids.append(match.group("id"))
    return ids


def phase_plan_map(plan_root: Path) -> dict[str, Path]:
    result: dict[str, Path] = {}
    phases_dir = plan_root / "phases"
    if not phases_dir.exists():
        return result
    for path in sorted(phases_dir.glob("*/PLAN.md")):
        prefix = path.parent.name.split("-", 1)[0]
        if prefix.isdigit():
            result[f"{int(prefix):02d}"] = path
    return result


def update_task_status(lines: list[str], task: TaskEntry, new_status: str) -> None:
    original = lines[task.line_index]
    match = TASK_LINE_RE.match(original)
    if not match:
        raise ValueError(f"Could not update task line for {task.task_id}")
    lines[task.line_index] = f"{match.group('prefix')}{new_status}{match.group('suffix')}\n"


def plan_task_body(task: TaskEntry, *, plan_root: Path, backlog_path: Path, phase_plan_path: Path | None) -> str:
    done_when = task.details.get("done when", "Complete only this backlog item.")
    files = task.details.get("files", "Use repository inspection to determine the exact files.")
    references = [
        f"- Plan root: `{plan_root}`",
        f"- Backlog: `{backlog_path}`",
    ]
    if phase_plan_path:
        references.append(f"- Phase plan: `{phase_plan_path}`")
    references.append(f"- Backlog id: `{task.task_id}`")

    lines = [
        f"<!-- backlog-id: {task.task_id} -->",
        f"<!-- phase-num: {task.phase_num} -->",
        f"<!-- phase-title: {task.phase_title} -->",
        "",
        f"# {task.task_id}: {task.title}",
        "",
        "Goal:",
        "",
        f"- {task.title}.",
        "",
        "Context:",
        "",
        f"- Phase: {task.phase_num} {task.phase_title}",
        f"- Priority: {task.priority}",
        f"- Candidate files: {files}",
        "",
        "Constraints:",
        "",
        "- Keep scope limited to this backlog item.",
        "- Follow repository AGENTS.md and the local planning bundle.",
        "- Do not start unrelated backlog items in the same diff.",
        "- Add focused verification only for the touched behavior.",
        "",
        "Done when:",
        "",
        f"- {done_when}",
        "",
        "References:",
        "",
        *references,
        "",
        "Verification:",
        "",
        "- Run the smallest relevant verification for this task and report the result.",
        "",
    ]
    return "\n".join(lines)


def metadata_from_task_file(task_path: Path) -> dict[str, str]:
    metadata: dict[str, str] = {}
    for line in task_path.read_text(encoding="utf-8").splitlines():
        match = META_RE.match(line)
        if not match:
            if line.startswith("# "):
                break
            continue
        metadata[match.group("key")] = match.group("value")
    return metadata


def set_phase_status(phase_plan_path: Path, status: str) -> None:
    text = phase_plan_path.read_text(encoding="utf-8")
    updated, count = re.subn(r"(?m)^status:\s*.+$", f"status: {status}", text, count=1)
    if count != 1:
        raise ValueError(f"Could not update status in {phase_plan_path}")
    phase_plan_path.write_text(updated, encoding="utf-8")


def phase_status_for_tasks(statuses: list[str]) -> str:
    if statuses and all(item == "done" for item in statuses):
        return "completed"
    if any(item == "in_progress" for item in statuses):
        return "in_progress"
    if any(item in {"done", "blocked"} for item in statuses):
        return "in_progress"
    return "planned"


def enqueue(args: argparse.Namespace) -> None:
    plan_root = Path(args.plan_root).resolve()
    backlog_path = plan_root / "BACKLOG.md"
    execution_slice_path = plan_root / "EXECUTION-SLICE.md"
    queue_dir = Path(args.queue_dir).resolve()
    todo_dir = queue_dir / "todo"
    todo_dir.mkdir(parents=True, exist_ok=True)

    lines, tasks = parse_backlog(backlog_path)
    tasks_by_id = {task.task_id: task for task in tasks}
    ordered_ids = slice_order(execution_slice_path) if args.ordering == "slice" else []
    selected: list[TaskEntry] = []
    seen: set[str] = set()

    def maybe_add(task_id: str) -> None:
        if task_id in seen:
            return
        task = tasks_by_id.get(task_id)
        if not task or task.status != "todo":
            return
        seen.add(task_id)
        selected.append(task)

    for task_id in ordered_ids:
        maybe_add(task_id)
    for task in tasks:
        if task.status == "todo":
            maybe_add(task.task_id)

    if args.max_tasks > 0:
        selected = selected[: args.max_tasks]

    phase_paths = phase_plan_map(plan_root)
    enqueued: list[dict[str, str]] = []
    for task in selected:
        prefix = queue_next_prefix(queue_dir)
        filename = f"{prefix}-{slugify(task.task_id)}-{slugify(task.title)}.md"
        output_path = todo_dir / filename
        output_path.write_text(
            plan_task_body(task, plan_root=plan_root, backlog_path=backlog_path, phase_plan_path=phase_paths.get(task.phase_num)),
            encoding="utf-8",
        )
        update_task_status(lines, task, "in_progress")
        enqueued.append(
            {
                "task_id": task.task_id,
                "title": task.title,
                "task_file": str(output_path),
                "phase_num": task.phase_num,
            }
        )

    write_lines(backlog_path, lines)
    Path(args.output_json).write_text(
        json.dumps(
            {
                "plan_root": str(plan_root),
                "queue_dir": str(queue_dir),
                "ordering": args.ordering,
                "enqueued_count": len(enqueued),
                "enqueued": enqueued,
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )


def apply_results(args: argparse.Namespace) -> None:
    plan_root = Path(args.plan_root).resolve()
    backlog_path = plan_root / "BACKLOG.md"
    queue_run_dir = Path(args.queue_run_dir).resolve()
    lines, tasks = parse_backlog(backlog_path)
    tasks_by_id = {task.task_id: task for task in tasks}
    result_files = sorted(queue_run_dir.glob("tasks/*/result.json"))
    updated: list[dict[str, str]] = []
    status_map = {
        "approved": args.approved_status,
        "no_changes": args.no_changes_status,
        "needs_human": args.needs_human_status,
        "failed": args.failed_status,
    }

    for result_path in result_files:
        payload = json.loads(result_path.read_text(encoding="utf-8"))
        task_file = Path(payload["task_path"])
        if not task_file.exists():
            continue
        metadata = metadata_from_task_file(task_file)
        backlog_id = metadata.get("backlog-id")
        if not backlog_id:
            continue
        task = tasks_by_id.get(backlog_id)
        if not task:
            continue
        result_status = str(payload.get("status"))
        new_status = status_map.get(result_status)
        if not new_status:
            continue
        update_task_status(lines, task, new_status)
        updated.append(
            {
                "task_id": backlog_id,
                "from_status": task.status,
                "to_status": new_status,
                "result_status": result_status,
                "task_file": str(task_file),
            }
        )
        task.status = new_status

    write_lines(backlog_path, lines)

    phase_paths = phase_plan_map(plan_root)
    by_phase: dict[str, list[str]] = {}
    for task in tasks:
        if task.phase_num:
            by_phase.setdefault(task.phase_num, []).append(task.status)

    phase_updates: list[dict[str, str]] = []
    for phase_num, statuses in by_phase.items():
        phase_path = phase_paths.get(phase_num)
        if not phase_path:
            continue
        phase_status = phase_status_for_tasks(statuses)
        set_phase_status(phase_path, phase_status)
        phase_updates.append({"phase_num": phase_num, "status": phase_status, "path": str(phase_path)})

    Path(args.output_json).write_text(
        json.dumps(
            {
                "plan_root": str(plan_root),
                "queue_run_dir": str(queue_run_dir),
                "updated_count": len(updated),
                "updated": updated,
                "phase_updates": phase_updates,
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Sync a markdown planning bundle with the qwen-codex queue flow.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    enqueue_parser = subparsers.add_parser("enqueue")
    enqueue_parser.add_argument("--plan-root", required=True)
    enqueue_parser.add_argument("--queue-dir", required=True)
    enqueue_parser.add_argument("--ordering", choices=["slice", "backlog"], default="slice")
    enqueue_parser.add_argument("--max-tasks", type=int, default=0)
    enqueue_parser.add_argument("--output-json", required=True)
    enqueue_parser.set_defaults(func=enqueue)

    apply_parser = subparsers.add_parser("apply-results")
    apply_parser.add_argument("--plan-root", required=True)
    apply_parser.add_argument("--queue-run-dir", required=True)
    apply_parser.add_argument("--approved-status", default="done")
    apply_parser.add_argument("--no-changes-status", default="blocked")
    apply_parser.add_argument("--needs-human-status", default="blocked")
    apply_parser.add_argument("--failed-status", default="blocked")
    apply_parser.add_argument("--output-json", required=True)
    apply_parser.set_defaults(func=apply_results)

    return parser


def main() -> None:
    parser = build_parser()
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
