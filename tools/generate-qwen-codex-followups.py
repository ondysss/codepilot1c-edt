#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

META_RE = re.compile(r"^<!--\s*(?P<key>[a-z0-9_-]+):\s*(?P<value>.*?)\s*-->\s*$")


def slugify(value: str) -> str:
    value = value.lower()
    value = re.sub(r"[^a-z0-9]+", "-", value)
    value = re.sub(r"-{2,}", "-", value).strip("-")
    return value or "task"


def next_prefix(queue_dir: Path) -> str:
    max_num = 0
    for path in queue_dir.glob("*/*.md"):
        match = re.match(r"(\d+)-", path.name)
        if match:
            max_num = max(max_num, int(match.group(1)))
    return f"{max_num + 1:03d}"


def read_first_heading(task_path: Path) -> str:
    for line in task_path.read_text(encoding="utf-8").splitlines():
        if line.startswith("# "):
            return line[2:].strip()
    return task_path.stem


def latest_review_json(reviews_dir: Path) -> Path | None:
    candidates: list[tuple[int, Path]] = []
    for path in reviews_dir.glob("review-round-*.json"):
        match = re.match(r"review-round-(\d+)\.json$", path.name)
        if match:
            candidates.append((int(match.group(1)), path))
    if not candidates:
        return None
    candidates.sort()
    return candidates[-1][1]


def load_review(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


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


def render_followup_task(
    *,
    title: str,
    finding_text: str,
    files_hint: str,
    verification_command: str,
    original_task_path: Path,
    flow_artifact_dir: Path,
    review_json_path: Path,
    metadata: dict[str, str],
) -> str:
    lines = []
    for key in ("backlog-id", "phase-num", "phase-title"):
        value = metadata.get(key)
        if value:
            lines.append(f"<!-- {key}: {value} -->")
    if lines:
        lines.append("")
    lines.extend([
        f"# {title}",
        "",
        "Goal:",
        "",
        "- Address the specific review finding below and nothing else.",
        "",
        "Review finding:",
        "",
        f"- {finding_text}",
        "",
        "Context:",
        "",
        f"- Files likely involved: {files_hint}",
        "",
        "Constraints:",
        "",
        "- Preserve the existing good changes.",
        "- Fix only the review issue.",
        "- Avoid unrelated edits.",
        "",
        "Done when:",
        "",
        "- The cited defect is fixed.",
        "- The original change still satisfies its goal.",
        "",
        "Verification:",
        "",
        f"- Run: `{verification_command}`",
        "",
        "Source:",
        "",
        f"- Original task: `{original_task_path}`",
        f"- Flow artifacts: `{flow_artifact_dir}`",
        f"- Review JSON: `{review_json_path}`",
        "",
    ])
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate review-followup queue tasks from the latest Codex review JSON.")
    parser.add_argument("--queue-dir", required=True, help="Queue root containing todo/, needs_human/, etc.")
    parser.add_argument("--flow-artifact-dir", required=True, help="Flow artifact dir under .runs/qwen-codex-flow/<run-id>")
    parser.add_argument("--original-task", required=True, help="Path to the task file that ended in needs_human")
    parser.add_argument("--output-json", required=True, help="Path to write generation metadata JSON")
    parser.add_argument("--max-findings", type=int, default=0, help="Optional cap; 0 means all findings")
    args = parser.parse_args()

    queue_dir = Path(args.queue_dir).resolve()
    todo_dir = queue_dir / "todo"
    flow_artifact_dir = Path(args.flow_artifact_dir).resolve()
    original_task = Path(args.original_task).resolve()
    output_json = Path(args.output_json).resolve()

    reviews_dir = flow_artifact_dir / "reviews"
    review_json_path = latest_review_json(reviews_dir)
    generated_tasks: list[str] = []
    payload = {
        "original_task": str(original_task),
        "flow_artifact_dir": str(flow_artifact_dir),
        "source_review": str(review_json_path) if review_json_path else None,
        "generated_count": 0,
        "generated_tasks": generated_tasks,
    }

    if not review_json_path or not review_json_path.exists():
        output_json.parent.mkdir(parents=True, exist_ok=True)
        output_json.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return

    review = load_review(review_json_path)
    findings = review.get("findings") or []
    metadata = metadata_from_task_file(original_task)
    if review.get("status") != "NEEDS_FIXES" or not findings:
        output_json.parent.mkdir(parents=True, exist_ok=True)
        output_json.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return

    todo_dir.mkdir(parents=True, exist_ok=True)
    original_title = read_first_heading(original_task)
    tests = review.get("tests") or []
    verification_command = " ; ".join(str(item) for item in tests if item) or "Run the smallest relevant verification for the touched path."

    if args.max_findings > 0:
        findings = findings[: args.max_findings]

    for finding in findings:
        title = str(finding.get("title") or "review followup").strip()
        location = str(finding.get("location") or "n/a").strip()
        details = str(finding.get("details") or "").strip()
        suggested_fix = str(finding.get("suggested_fix") or "").strip()
        severity = str(finding.get("severity") or "medium").strip()

        finding_text = f"[{severity}] {title}. {details}"
        if suggested_fix:
            finding_text = f"{finding_text} Suggested fix: {suggested_fix}"

        followup_title = f"Follow up: {original_title} - {title}"
        prefix = next_prefix(queue_dir)
        filename = f"{prefix}-review-followup-{slugify(original_title)}-{slugify(title)}.md"
        output_path = todo_dir / filename
        output_path.write_text(
            render_followup_task(
                title=followup_title,
                finding_text=finding_text,
                files_hint=location,
                verification_command=verification_command,
                original_task_path=original_task,
                flow_artifact_dir=flow_artifact_dir,
                review_json_path=review_json_path,
                metadata=metadata,
            ),
            encoding="utf-8",
        )
        generated_tasks.append(str(output_path))

    payload["generated_count"] = len(generated_tasks)
    output_json.parent.mkdir(parents=True, exist_ok=True)
    output_json.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
