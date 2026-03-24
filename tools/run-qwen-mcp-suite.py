#!/usr/bin/env python3
"""Run Qwen MCP evaluation suites against an already running EDT MCP host."""

from __future__ import annotations

import argparse
import json
import os
import re
import shlex
import subprocess
import sys
import textwrap
import time
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ROOT_DIR = Path(__file__).resolve().parent.parent
DEFAULT_SUITE = ROOT_DIR / "evals" / "qwen" / "suite.json"
DEFAULT_RUNS_DIR = ROOT_DIR / ".runs" / "qwen-suite"
MUTATING_TOOLS = {
    "create_metadata",
    "create_form",
    "add_metadata_child",
    "update_metadata",
    "mutate_form_model",
    "delete_metadata",
    "qa_prepare_form_context",
}
DEFAULT_TIMEOUT_SECONDS = 1800


@dataclass
class QwenMcpConfig:
    name: str
    url: str | None
    headers: dict[str, str]


def default_qwen_home(home_dir: Path | None = None) -> Path:
    return (home_dir or Path.home()) / ".qwen"


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def sanitize_id(text: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "-", text).strip("-") or "run"


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def parse_json_or_text(raw: bytes) -> Any:
    text = raw.decode("utf-8", "replace")
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return {"raw_text": text}


def run_subprocess(
    cmd: list[str],
    cwd: Path,
    stdout_path: Path,
    stderr_path: Path,
    timeout: int | None,
    env: dict[str, str] | None = None,
) -> int:
    stdout_path.parent.mkdir(parents=True, exist_ok=True)
    stderr_path.parent.mkdir(parents=True, exist_ok=True)
    with stdout_path.open("w", encoding="utf-8") as stdout_handle, stderr_path.open("w", encoding="utf-8") as stderr_handle:
        process = subprocess.Popen(
            cmd,
            cwd=str(cwd),
            stdout=stdout_handle,
            stderr=stderr_handle,
            text=True,
            env=env,
        )
        try:
            return process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
            return 124


def read_qwen_settings(settings_path: Path) -> dict[str, Any]:
    if not settings_path.exists():
        raise FileNotFoundError(f"Qwen settings not found: {settings_path}")
    return load_json(settings_path)


def resolve_mcp_config(settings: dict[str, Any], server_name: str, settings_path: Path) -> QwenMcpConfig:
    servers = settings.get("mcpServers") or {}
    if server_name not in servers:
        raise KeyError(f"MCP server '{server_name}' not found in {settings_path}")
    server = servers[server_name] or {}
    url = server.get("httpUrl") or server.get("url")
    headers = server.get("headers") or {}
    return QwenMcpConfig(name=server_name, url=url, headers={str(k): str(v) for k, v in headers.items()})


def preflight_mcp(config: QwenMcpConfig, output_path: Path) -> None:
    if not config.url:
        raise RuntimeError(f"MCP server '{config.name}' has no httpUrl/url in settings")
    payload = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "initialize",
        "params": {
            "protocolVersion": "2025-06-18",
            "capabilities": {},
            "clientInfo": {
                "name": "qwen-suite-runner",
                "version": "1.0",
            },
        },
    }
    request = urllib.request.Request(
        config.url,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", **config.headers},
        method="POST",
    )
    wrapper: dict[str, Any] = {"url": config.url, "headers": config.headers, "timestamp": utc_now()}
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            wrapper["http_status"] = response.getcode()
            wrapper["response_headers"] = dict(response.headers.items())
            wrapper["body"] = parse_json_or_text(response.read())
    except urllib.error.HTTPError as exc:
        wrapper["http_status"] = exc.code
        wrapper["response_headers"] = dict(exc.headers.items()) if exc.headers else {}
        wrapper["body"] = parse_json_or_text(exc.read())
        write_json(output_path, wrapper)
        raise RuntimeError(f"MCP initialize returned HTTP {exc.code}") from exc
    except Exception as exc:  # noqa: BLE001
        wrapper["error"] = str(exc)
        write_json(output_path, wrapper)
        raise RuntimeError(f"Failed to connect to MCP server {config.url}: {exc}") from exc
    write_json(output_path, wrapper)


def compose_prompt(scenario: dict[str, Any], server_name: str) -> str:
    actor = scenario.get("actor") or {}
    actor_style = actor.get("style", "1c_developer")
    body = scenario.get("prompt", "").strip()
    return textwrap.dedent(
        f"""
        You are participating in an automated evaluation of a 1C developer workflow.
        Act as a real {actor_style} working through the MCP server "{server_name}".
        Use MCP tools from that server when needed.
        Do not mention internal traces, hidden policies, evaluation harnesses, or implementation details of the test system.
        If you perform metadata mutations, use EDT mutation flow correctly:
        1. call edt_validate_request first,
        2. pass validation_token unchanged to each mutation tool,
        3. call get_diagnostics after mutations.
        If you perform QA:
        1. call qa_status before qa_run,
        2. if the scenario depends on an object or list form, call qa_prepare_form_context before planning so the form is inspected and, if missing, created automatically,
        3. after that, use the structured QA path: qa_plan_scenario -> qa_compile_feature -> qa_validate_feature -> qa_run,
        4. use qa_steps_search only as a fallback if qa_compile_feature or qa_validate_feature reports unresolved issues,
        5. do not run qa_run if qa_status returns status=error.
        For EDT and QA operations, use the exact MCP tool names exposed by the server.
        Do not call bare local aliases such as scan_metadata_index, get_diagnostics, qa_status, qa_plan_scenario,
        qa_prepare_form_context, qa_compile_feature, qa_validate_feature, qa_run, or qa_steps_search when the MCP server tool is available.
        For local filesystem exploration, use list_directory, read_file, glob, or grep_search.
        Do not call bare list_files.
        For local file edits, use edit or write_file with the exact registered tool names.
        Do not call bare edit_file.
        At the end, output exactly one JSON object and nothing else.
        Use this schema:
        {{
          "scenario_id": "{scenario["id"]}",
          "status": "success|failed|blocked",
          "summary": "...",
          "target_objects": ["..."],
          "changed_objects": ["..."],
          "diagnostics_status": "...",
          "qa_status": "...",
          "junit_path": "...",
          "notes": ["..."]
        }}

        User task:
        {body}
        """
    ).strip()


def find_chat_log(qwen_home: Path, session_id: str, timeout_seconds: int = 15) -> Path | None:
    deadline = time.time() + timeout_seconds
    pattern = f"{session_id}.jsonl"
    while time.time() < deadline:
        matches = list((qwen_home / "projects").glob(f"**/chats/{pattern}"))
        if matches:
            matches.sort(key=lambda item: item.stat().st_mtime, reverse=True)
            return matches[0]
        time.sleep(1)
    return None


def extract_tool_name(full_name: str) -> tuple[str | None, str]:
    match = re.fullmatch(r"mcp__([^_]+(?:_[^_]+)*)__([^_].+)", full_name)
    if not match:
        return None, full_name
    return match.group(1), match.group(2)


def parse_json_maybe(value: Any) -> Any:
    if isinstance(value, str):
        try:
            return json.loads(value)
        except json.JSONDecodeError:
            return value
    return value


def extract_json_object(text: str) -> dict[str, Any] | None:
    text = (text or "").strip()
    if not text:
        return None
    candidates = [text]
    fence_match = re.search(r"```json\s*(\{.*\})\s*```", text, flags=re.DOTALL | re.IGNORECASE)
    if fence_match:
        candidates.insert(0, fence_match.group(1).strip())
    generic_fence_match = re.search(r"```\s*(\{.*\})\s*```", text, flags=re.DOTALL)
    if generic_fence_match:
        candidates.insert(0, generic_fence_match.group(1).strip())
    brace_match = re.search(r"(\{.*\})", text, flags=re.DOTALL)
    if brace_match:
        candidates.append(brace_match.group(1).strip())
    seen: set[str] = set()
    for candidate in candidates:
        if candidate in seen:
            continue
        seen.add(candidate)
        try:
            parsed = json.loads(candidate)
        except json.JSONDecodeError:
            continue
        if isinstance(parsed, dict):
            return parsed
    return None


def deep_text(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    if isinstance(value, (int, float, bool)):
        return str(value)
    if isinstance(value, list):
        return " ".join(deep_text(item) for item in value)
    if isinstance(value, dict):
        return " ".join(f"{key} {deep_text(item)}" for key, item in value.items())
    return str(value)


def parse_chat_log(chat_log: Path) -> dict[str, Any]:
    calls: list[dict[str, Any]] = []
    results: dict[str, dict[str, Any]] = {}
    assistant_visible_messages: list[str] = []
    telemetry: list[dict[str, Any]] = []

    with chat_log.open("r", encoding="utf-8") as handle:
        for raw_line in handle:
            raw_line = raw_line.strip()
            if not raw_line:
                continue
            entry = json.loads(raw_line)
            entry_type = entry.get("type")
            if entry_type == "assistant":
                parts = ((entry.get("message") or {}).get("parts") or [])
                visible_parts = []
                for part in parts:
                    if "functionCall" in part:
                        function_call = part["functionCall"]
                        full_name = function_call.get("name", "")
                        server_name, short_name = extract_tool_name(full_name)
                        calls.append(
                            {
                                "call_id": function_call.get("id"),
                                "full_name": full_name,
                                "server_name": server_name,
                                "tool_name": short_name,
                                "args": function_call.get("args") or {},
                            }
                        )
                    elif "text" in part and not part.get("thought"):
                        visible_parts.append(part.get("text", ""))
                if visible_parts:
                    assistant_visible_messages.append("".join(visible_parts).strip())
            elif entry_type == "tool_result":
                parts = ((entry.get("message") or {}).get("parts") or [])
                for part in parts:
                    response = part.get("functionResponse")
                    if not response:
                        continue
                    payload = response.get("response") or {}
                    output = parse_json_maybe(payload.get("output"))
                    error = payload.get("error")
                    results[response.get("id")] = {
                        "call_id": response.get("id"),
                        "full_name": response.get("name", ""),
                        "tool_name": extract_tool_name(response.get("name", ""))[1],
                        "output": output,
                        "error": error,
                    }
            elif entry_type == "system" and entry.get("subtype") == "ui_telemetry":
                ui_event = ((entry.get("systemPayload") or {}).get("uiEvent") or {})
                if ui_event.get("event.name") == "qwen-code.tool_call":
                    telemetry.append(ui_event)

    for call in calls:
        if call["call_id"] in results:
            call["result"] = results[call["call_id"]]

    final_text = assistant_visible_messages[-1] if assistant_visible_messages else ""
    final_json = extract_json_object(final_text)

    mcp_calls = [call for call in calls if call.get("server_name")]
    return {
        "all_calls": calls,
        "mcp_calls": mcp_calls,
        "results": results,
        "telemetry": telemetry,
        "assistant_messages": assistant_visible_messages,
        "final_text": final_text,
        "final_json": final_json,
    }


def contains_subsequence(items: list[str], subsequence: list[str]) -> bool:
    if not subsequence:
        return True
    index = 0
    for item in items:
        if item == subsequence[index]:
            index += 1
            if index == len(subsequence):
                return True
    return False


def find_last_result(calls: list[dict[str, Any]], tool_name: str) -> dict[str, Any] | None:
    for call in reversed(calls):
        if call.get("tool_name") == tool_name and call.get("result"):
            return call["result"]
    return None


def has_later_call(calls: list[dict[str, Any]], after_index: int, tool_name: str) -> bool:
    for index in range(after_index + 1, len(calls)):
        if calls[index].get("tool_name") == tool_name:
            return True
    return False


def evaluate_validation_flow(calls: list[dict[str, Any]]) -> tuple[bool, list[str]]:
    errors: list[str] = []
    issued_tokens: set[str] = set()
    for index, call in enumerate(calls):
        tool_name = call.get("tool_name")
        result = call.get("result") or {}
        output = result.get("output")
        if tool_name == "edt_validate_request" and isinstance(output, dict):
            token = output.get("validation_token") or output.get("validationToken")
            if isinstance(token, str) and token:
                issued_tokens.add(token)
        if tool_name in MUTATING_TOOLS:
            args = call.get("args") or {}
            passed = args.get("validation_token")
            if not issued_tokens:
                errors.append(f"Mutating call #{index + 1} {tool_name} had no preceding edt_validate_request token")
            elif passed not in issued_tokens:
                errors.append(f"Mutating call #{index + 1} {tool_name} used mismatched validation_token")
    return (not errors, errors)


def evaluate_assertions(scenario: dict[str, Any], parsed: dict[str, Any]) -> dict[str, Any]:
    checks: list[dict[str, Any]] = []
    mcp_calls = parsed["mcp_calls"]
    tool_names = [call.get("tool_name", "") for call in mcp_calls]
    all_mcp_text = deep_text(mcp_calls)
    final_text = parsed.get("final_text") or ""
    final_json = parsed.get("final_json") or {}
    final_blob = json.dumps(final_json, ensure_ascii=False) if final_json else final_text
    expected = scenario.get("expected_tool_path") or {}
    assertions = scenario.get("assertions") or {}

    for tool_name in expected.get("required_any_order", []):
        checks.append(
            {
                "name": f"required_tool:{tool_name}",
                "passed": tool_name in tool_names,
                "details": f"Expected MCP tool '{tool_name}' to be called",
            }
        )

    ordered = expected.get("ordered_subsequence", [])
    if ordered:
        checks.append(
            {
                "name": "ordered_subsequence",
                "passed": contains_subsequence(tool_names, ordered),
                "details": f"Expected tool call order to contain subsequence {ordered}",
            }
        )

    allowed = expected.get("allowed", [])
    if allowed:
        disallowed = [name for name in tool_names if name not in allowed]
        checks.append(
            {
                "name": "allowed_tool_set",
                "passed": not disallowed,
                "details": "All MCP tool calls must stay within allowed set",
                "observed": disallowed,
            }
        )

    for tool_name in expected.get("forbidden", []):
        checks.append(
            {
                "name": f"forbidden_tool:{tool_name}",
                "passed": tool_name not in tool_names,
                "details": f"MCP tool '{tool_name}' must not be called",
            }
        )

    tool_behavior = assertions.get("tool_behavior") or {}
    if tool_behavior.get("must_have_validation_flow"):
        ok, errors = evaluate_validation_flow(mcp_calls)
        checks.append(
            {
                "name": "validation_flow",
                "passed": ok,
                "details": "Mutation flow must preserve validation_token",
                "observed": errors,
            }
        )

    if tool_behavior.get("must_have_post_mutation_diagnostics"):
        mutation_indices = [index for index, name in enumerate(tool_names) if name in MUTATING_TOOLS]
        passed = (not mutation_indices) or has_later_call(mcp_calls, mutation_indices[-1], "get_diagnostics")
        checks.append(
            {
                "name": "post_mutation_diagnostics",
                "passed": passed,
                "details": "A get_diagnostics call must happen after the last mutation",
            }
        )

    if tool_behavior.get("must_call_qa_status_first"):
        qa_status_index = tool_names.index("qa_status") if "qa_status" in tool_names else -1
        qa_run_index = tool_names.index("qa_run") if "qa_run" in tool_names else -1
        passed = qa_status_index != -1 and qa_run_index != -1 and qa_status_index < qa_run_index
        checks.append(
            {
                "name": "qa_status_before_qa_run",
                "passed": passed,
                "details": "qa_status must be called before qa_run",
            }
        )

    if "max_mutating_calls" in tool_behavior:
        max_mutating = int(tool_behavior["max_mutating_calls"])
        count = sum(1 for name in tool_names if name in MUTATING_TOOLS)
        checks.append(
            {
                "name": "max_mutating_calls",
                "passed": count <= max_mutating,
                "details": f"Mutation count must be <= {max_mutating}",
                "observed": count,
            }
        )

    qa_status_result = find_last_result(mcp_calls, "qa_status")
    if qa_status_result and isinstance(qa_status_result.get("output"), dict):
        qa_status_value = qa_status_result["output"].get("status")
        checks.append(
            {
                "name": "qa_run_after_error_status",
                "passed": not (qa_status_value == "error" and "qa_run" in tool_names),
                "details": "qa_run must not execute after qa_status=status=error",
            }
        )

    workspace_state = assertions.get("workspace_state") or {}
    def evidence_contains(value: str) -> bool:
        if str(value) in all_mcp_text or str(value) in final_blob:
            return True
        targets = final_json.get("target_objects") or []
        changed = final_json.get("changed_objects") or []
        return str(value) in targets or str(value) in changed

    for key in [
        "must_create_object",
        "must_create_subsystem",
        "must_create_tabular_section",
    ]:
        value = workspace_state.get(key)
        if value:
            checks.append(
                {
                    "name": key,
                    "passed": evidence_contains(str(value)),
                    "details": f"Expected evidence for '{value}' in tool calls or final response",
                }
            )

    for key in [
        "must_create_objects",
        "must_create_attributes",
        "must_create_columns",
        "must_create_dimensions",
        "must_create_resources",
        "must_create_form_for",
    ]:
        values = workspace_state.get(key) or []
        for value in values:
            checks.append(
                {
                    "name": f"{key}:{value}",
                    "passed": evidence_contains(str(value)),
                    "details": f"Expected evidence for '{value}' in tool calls or final response",
                }
            )

    if workspace_state.get("must_produce_qa_results_directory") or workspace_state.get("must_produce_junit_report"):
        qa_run_result = find_last_result(mcp_calls, "qa_run")
        output = qa_run_result.get("output") if qa_run_result else {}
        paths = output.get("paths") if isinstance(output, dict) else {}
        run_dir = Path(paths.get("run_dir", "")) if isinstance(paths, dict) and paths.get("run_dir") else None
        junit_dir = Path(paths.get("junit_dir", "")) if isinstance(paths, dict) and paths.get("junit_dir") else None
        if workspace_state.get("must_produce_qa_results_directory"):
            checks.append(
                {
                    "name": "qa_results_dir_exists",
                    "passed": bool(run_dir and run_dir.exists()),
                    "details": "qa_run should produce a run_dir",
                    "observed": str(run_dir) if run_dir else "",
                }
            )
        if workspace_state.get("must_produce_junit_report"):
            has_junit = bool(junit_dir and junit_dir.exists() and any(junit_dir.glob("*.xml")))
            checks.append(
                {
                    "name": "qa_junit_exists",
                    "passed": has_junit,
                    "details": "qa_run should produce junit XML output",
                    "observed": str(junit_dir) if junit_dir else "",
                }
            )

    if workspace_state.get("diagnostics_error_count_must_decrease"):
        diagnostic_outputs = []
        for call in mcp_calls:
            if call.get("tool_name") == "get_diagnostics":
                result = call.get("result") or {}
                diagnostic_outputs.append(result.get("output"))
        passed = False
        if len(diagnostic_outputs) >= 2:
            first = deep_text(diagnostic_outputs[0]).lower()
            last = deep_text(diagnostic_outputs[-1]).lower()
            first_errors = first.count("error")
            last_errors = last.count("error")
            passed = last_errors < first_errors
        checks.append(
            {
                "name": "diagnostics_error_count_must_decrease",
                "passed": passed,
                "details": "Need evidence that diagnostics improved after remediation",
            }
        )

    final_assertions = assertions.get("final_answer") or {}
    for key, expected_value in final_assertions.items():
        if key == "must_mention":
            for needle in expected_value:
                checks.append(
                    {
                        "name": f"final_must_mention:{needle}",
                        "passed": needle.lower() in final_blob.lower(),
                        "details": f"Final answer must mention '{needle}'",
                    }
                )
        elif key == "must_include_sections":
            for needle in expected_value:
                checks.append(
                    {
                        "name": f"final_section:{needle}",
                        "passed": needle.lower() in final_blob.lower(),
                        "details": f"Final answer must include section or keyword '{needle}'",
                    }
                )
        elif expected_value is True:
            field_map = {
                "must_report_diagnostics_status": "diagnostics_status",
                "must_report_qa_status": "qa_status",
                "must_report_junit_path": "junit_path",
                "must_list_created_objects": "changed_objects",
                "must_report_created_catalogs": "changed_objects",
                "must_identify_modified_object": "changed_objects",
                "must_identify_target_object": "target_objects",
                "must_identify_form_or_module": "target_objects",
                "must_explain_choice": "summary",
                "must_explain_fix": "summary",
                "must_report_before_after_status": "notes",
                "must_describe_document_structure": "summary",
                "must_explain_stock_accounting_scheme": "summary",
                "must_list_created_forms": "changed_objects",
                "must_report_fixed_diagnostics_or_clean_status": "diagnostics_status",
                "must_report_actual_attribute_name": "changed_objects",
                "must_report_permission_denial": "summary",
                "must_not_report_false_success": "status",
                "must_identify_probable_failure_area": "summary",
                "must_reference_qa_artifact_evidence": "notes",
                "must_not_claim_fix_applied": "status",
                "must_identify_change_scope": "summary",
                "must_describe_form_change": "summary",
                "must_report_targeted_test_scope": "summary",
            }
            field_name = field_map.get(key)
            if field_name:
                value = final_json.get(field_name)
                passed = bool(value)
                if key == "must_not_report_false_success":
                    passed = final_json.get("status") != "success"
                elif key == "must_not_claim_fix_applied":
                    passed = "fix_applied" not in final_blob.lower()
                checks.append(
                    {
                        "name": key,
                        "passed": passed,
                        "details": f"Final JSON should satisfy {key}",
                        "observed": value,
                    }
                )

    overall_passed = all(check.get("passed") for check in checks)
    return {
        "passed": overall_passed,
        "checks": checks,
        "tool_names": tool_names,
        "final_json": final_json,
        "final_text": final_text,
    }


def extract_failure_metrics(parsed: dict[str, Any], evaluation: dict[str, Any]) -> dict[str, Any]:
    metrics = {
        "wrong_tool": 0,
        "invalid_args": 0,
        "missed_validation_token": 0,
        "missed_diagnostics": 0,
        "qa_gate": 0,
        "other": 0,
        "details": [],
    }

    for check in evaluation.get("checks") or []:
        if check.get("passed"):
            continue
        name = str(check.get("name") or "")
        details = str(check.get("details") or "")
        if name.startswith("required_tool:") or name.startswith("forbidden_tool:") or name == "allowed_tool_set" or name == "ordered_subsequence":
            metrics["wrong_tool"] += 1
            metrics["details"].append({"category": "wrong_tool", "name": name, "details": details})
        elif name == "validation_flow":
            observed = check.get("observed") or []
            count = len(observed) if isinstance(observed, list) and observed else 1
            metrics["missed_validation_token"] += count
            metrics["details"].append(
                {"category": "missed_validation_token", "name": name, "details": details, "observed": observed}
            )
        elif name in {"post_mutation_diagnostics", "diagnostics_error_count_must_decrease"}:
            metrics["missed_diagnostics"] += 1
            metrics["details"].append({"category": "missed_diagnostics", "name": name, "details": details})
        elif name in {"qa_status_before_qa_run", "qa_run_after_error_status"}:
            metrics["qa_gate"] += 1
            metrics["details"].append({"category": "qa_gate", "name": name, "details": details})
        else:
            metrics["other"] += 1
            metrics["details"].append({"category": "other", "name": name, "details": details})

    for result in (parsed.get("results") or {}).values():
        error_blob = deep_text(result.get("error")).lower()
        output = result.get("output")
        output_error_blob = ""
        if isinstance(output, dict):
            output_error_blob = " ".join(
                deep_text(output.get(key)).lower()
                for key in ["error", "error_message", "message", "details"]
                if output.get(key)
            )
        combined = f"{error_blob} {output_error_blob}".strip()
        if not combined:
            continue
        if any(token in combined for token in [
            "invalid arguments",
            "invalid argument",
            "validation error",
            "schema",
            "required property",
            "unexpected property",
            "json schema",
        ]):
            metrics["invalid_args"] += 1
            metrics["details"].append(
                {
                    "category": "invalid_args",
                    "name": result.get("tool_name") or "tool_result",
                    "details": (result.get("error") or result.get("output") or "")[:500],
                }
            )

    return metrics


def aggregate_metrics(scenario_results: list[dict[str, Any]]) -> dict[str, Any]:
    aggregate = {
        "scenarios_total": len(scenario_results),
        "scenarios_passed": 0,
        "scenarios_failed": 0,
        "wrong_tool": 0,
        "invalid_args": 0,
        "missed_validation_token": 0,
        "missed_diagnostics": 0,
        "qa_gate": 0,
        "other": 0,
        "scenario_breakdown": [],
    }

    for result in scenario_results:
        if result.get("passed"):
            aggregate["scenarios_passed"] += 1
        else:
            aggregate["scenarios_failed"] += 1
        scenario_metrics = result.get("failure_metrics") or {}
        breakdown = {"scenario_id": result.get("scenario_id"), "passed": bool(result.get("passed"))}
        for key in ["wrong_tool", "invalid_args", "missed_validation_token", "missed_diagnostics", "qa_gate", "other"]:
            value = int(scenario_metrics.get(key) or 0)
            aggregate[key] += value
            breakdown[key] = value
        breakdown["details"] = scenario_metrics.get("details") or []
        aggregate["scenario_breakdown"].append(breakdown)

    total = aggregate["scenarios_total"] or 1
    aggregate["pass_rate"] = round(aggregate["scenarios_passed"] / total, 4)
    return aggregate


def build_qwen_command(
    qwen_bin: str,
    prompt: str,
    session_id: str,
    server_name: str,
    workdir: Path,
    scenario_run_dir: Path,
    args: argparse.Namespace,
) -> list[str]:
    cmd = [
        qwen_bin,
        "--allowed-mcp-server-names",
        server_name,
        "--approval-mode",
        args.approval_mode,
        "--output-format",
        "stream-json",
        "--include-partial-messages",
        "--chat-recording",
        "--channel",
        "CI",
        "--session-id",
        session_id,
    ]
    if args.model:
        cmd.extend(["--model", args.model])
    if args.auth_type:
        cmd.extend(["--auth-type", args.auth_type])
    if args.max_session_turns:
        cmd.extend(["--max-session-turns", str(args.max_session_turns)])
    if args.openai_logging:
        cmd.append("--openai-logging")
        cmd.extend(["--openai-logging-dir", str(scenario_run_dir / "api-logs")])
    if args.debug:
        cmd.append("--debug")
    if args.extra_args:
        cmd.extend(shlex.split(args.extra_args))
    cmd.append(prompt)
    return cmd


def load_suite(suite_path: Path, selected_ids: set[str] | None) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    suite = load_json(suite_path)
    scenarios: list[dict[str, Any]] = []
    for relative_path in suite.get("scenarios", []):
        scenario_path = (suite_path.parent / relative_path).resolve()
        scenario = load_json(scenario_path)
        scenario["_path"] = str(scenario_path)
        if selected_ids and scenario["id"] not in selected_ids:
            continue
        scenarios.append(scenario)
    return suite, scenarios


def write_markdown_summary(path: Path, suite: dict[str, Any], scenario_results: list[dict[str, Any]]) -> None:
    lines = [
        f"# {suite.get('title', suite.get('suite_id', 'Qwen Suite'))}",
        "",
        f"- suite_id: `{suite.get('suite_id', '')}`",
        f"- generated_at: `{utc_now()}`",
        "",
        "| Scenario | Result | Checks | Session |",
        "|---|---|---:|---|",
    ]
    for result in scenario_results:
        checks = ((result.get("evaluation") or {}).get("checks") or [])
        failed_checks = sum(1 for item in checks if not item.get("passed"))
        status = "PASS" if result["passed"] else "FAIL"
        lines.append(
            f"| `{result['scenario_id']}` | {status} | {failed_checks} failed | `{result['session_id']}` |"
        )
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_metrics_report(path: Path, suite: dict[str, Any], metrics: dict[str, Any]) -> None:
    lines = [
        f"# {suite.get('title', suite.get('suite_id', 'Qwen Suite'))} Metrics",
        "",
        f"- suite_id: `{suite.get('suite_id', '')}`",
        f"- generated_at: `{utc_now()}`",
        f"- scenarios_total: `{metrics.get('scenarios_total', 0)}`",
        f"- scenarios_passed: `{metrics.get('scenarios_passed', 0)}`",
        f"- scenarios_failed: `{metrics.get('scenarios_failed', 0)}`",
        f"- pass_rate: `{metrics.get('pass_rate', 0)}`",
        "",
        "## Failure Metrics",
        "",
        f"- wrong_tool: `{metrics.get('wrong_tool', 0)}`",
        f"- invalid_args: `{metrics.get('invalid_args', 0)}`",
        f"- missed_validation_token: `{metrics.get('missed_validation_token', 0)}`",
        f"- missed_diagnostics: `{metrics.get('missed_diagnostics', 0)}`",
        f"- qa_gate: `{metrics.get('qa_gate', 0)}`",
        f"- other: `{metrics.get('other', 0)}`",
        "",
        "## Scenario Breakdown",
        "",
        "| Scenario | Pass | wrong_tool | invalid_args | missed_validation_token | missed_diagnostics | qa_gate | other |",
        "|---|---|---:|---:|---:|---:|---:|---:|",
    ]
    for breakdown in metrics.get("scenario_breakdown", []):
        lines.append(
            "| `{scenario_id}` | {passed} | {wrong_tool} | {invalid_args} | {missed_validation_token} | {missed_diagnostics} | {qa_gate} | {other} |".format(
                scenario_id=breakdown.get("scenario_id", ""),
                passed="PASS" if breakdown.get("passed") else "FAIL",
                wrong_tool=breakdown.get("wrong_tool", 0),
                invalid_args=breakdown.get("invalid_args", 0),
                missed_validation_token=breakdown.get("missed_validation_token", 0),
                missed_diagnostics=breakdown.get("missed_diagnostics", 0),
                qa_gate=breakdown.get("qa_gate", 0),
                other=breakdown.get("other", 0),
            )
        )
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run Qwen MCP evaluation suite")
    parser.add_argument("--suite", default=str(DEFAULT_SUITE), help="Path to suite JSON")
    parser.add_argument("--scenario-id", action="append", help="Run only selected scenario id (repeatable)")
    parser.add_argument("--runs-dir", default=str(DEFAULT_RUNS_DIR), help="Directory for run artifacts")
    parser.add_argument("--workdir", default=str(ROOT_DIR), help="Working directory for qwen")
    parser.add_argument("--qwen-bin", default=os.environ.get("QWEN_BIN", "qwen"), help="Path to qwen CLI")
    parser.add_argument("--home-dir", default=os.environ.get("QWEN_HOME_DIR"),
                        help="Override HOME for qwen subprocess")
    parser.add_argument("--qwen-home", default=os.environ.get("QWEN_HOME_PATH"),
                        help="Path to .qwen directory used for settings/chat discovery")
    parser.add_argument("--settings-path", default=os.environ.get("QWEN_SETTINGS_PATH"),
                        help="Path to qwen settings.json")
    parser.add_argument("--mcp-server", default=os.environ.get("QWEN_MCP_SERVER", "codepilot1clocal"),
                        help="Configured MCP server name in ~/.qwen/settings.json")
    parser.add_argument("--model", default=os.environ.get("QWEN_MODEL"), help="Optional qwen model")
    parser.add_argument("--auth-type", default=os.environ.get("QWEN_AUTH_TYPE"), help="Optional auth type")
    parser.add_argument("--approval-mode", default=os.environ.get("QWEN_APPROVAL_MODE", "yolo"),
                        help="Qwen approval mode")
    parser.add_argument("--extra-args", default=os.environ.get("QWEN_EXTRA_ARGS", ""),
                        help="Additional raw qwen CLI args")
    parser.add_argument("--openai-logging", action="store_true", default=bool(os.environ.get("QWEN_OPENAI_LOGGING")),
                        help="Enable qwen API logging into scenario artifact dir")
    parser.add_argument("--max-session-turns", type=int,
                        default=int(os.environ.get("QWEN_MAX_SESSION_TURNS", "0")) or None,
                        help="Optional max session turns")
    parser.add_argument("--timeout-seconds", type=int,
                        default=int(os.environ.get("QWEN_TIMEOUT_SECONDS", str(DEFAULT_TIMEOUT_SECONDS))),
                        help="Per-scenario qwen process timeout")
    parser.add_argument("--skip-preflight", action="store_true",
                        help="Skip manual MCP initialize probe and let qwen validate connectivity")
    parser.add_argument("--keep-going", action="store_true", help="Continue after scenario failure")
    parser.add_argument("--dry-run", action="store_true", help="Do not execute qwen, only materialize commands")
    parser.add_argument("--debug", action="store_true", help="Enable qwen --debug")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    suite_path = Path(args.suite).resolve()
    runs_dir = Path(args.runs_dir).resolve()
    workdir = Path(args.workdir).resolve()
    home_dir = Path(args.home_dir).resolve() if args.home_dir else None
    qwen_home = Path(args.qwen_home).resolve() if args.qwen_home else default_qwen_home(home_dir)
    settings_path = Path(args.settings_path).resolve() if args.settings_path else qwen_home / "settings.json"
    run_id = f"{sanitize_id(suite_path.stem)}-{datetime.now().strftime('%Y%m%d-%H%M%S')}"
    run_dir = runs_dir / run_id
    run_dir.mkdir(parents=True, exist_ok=True)

    settings = read_qwen_settings(settings_path)
    mcp_config = resolve_mcp_config(settings, args.mcp_server, settings_path)
    write_json(run_dir / "qwen-mcp-config.json", {
        "server_name": mcp_config.name,
        "url": mcp_config.url,
        "headers": mcp_config.headers,
        "settings_path": str(settings_path),
        "qwen_home": str(qwen_home),
        "home_dir": str(home_dir) if home_dir else str(Path.home()),
    })

    selected_ids = set(args.scenario_id or [])
    suite, scenarios = load_suite(suite_path, selected_ids if selected_ids else None)
    write_json(run_dir / "suite.json", suite)

    if not args.dry_run and not args.skip_preflight:
        preflight_mcp(mcp_config, run_dir / "mcp-preflight.json")

    scenario_results: list[dict[str, Any]] = []
    overall_passed = True
    qwen_env = os.environ.copy()
    if home_dir is not None:
        qwen_env["HOME"] = str(home_dir)

    for scenario in scenarios:
        scenario_id = scenario["id"]
        session_id = str(uuid.uuid4())
        scenario_dir = run_dir / scenario_id
        scenario_dir.mkdir(parents=True, exist_ok=True)
        write_json(scenario_dir / "scenario.json", scenario)

        prompt = compose_prompt(scenario, args.mcp_server)
        (scenario_dir / "prompt.txt").write_text(prompt + "\n", encoding="utf-8")

        cmd = build_qwen_command(
            qwen_bin=args.qwen_bin,
            prompt=prompt,
            session_id=session_id,
            server_name=args.mcp_server,
            workdir=workdir,
            scenario_run_dir=scenario_dir,
            args=args,
        )
        (scenario_dir / "command.sh").write_text(
            "#!/usr/bin/env bash\n" + " ".join(shlex.quote(part) for part in cmd) + "\n",
            encoding="utf-8",
        )

        scenario_result: dict[str, Any] = {
            "scenario_id": scenario_id,
            "session_id": session_id,
            "run_label": f"{run_id}-{scenario_id}",
            "scenario_path": scenario["_path"],
            "command": cmd,
            "started_at": utc_now(),
        }

        if args.dry_run:
            scenario_result["passed"] = True
            scenario_result["evaluation"] = {"passed": True, "checks": [], "tool_names": []}
            scenario_result["failure_metrics"] = extract_failure_metrics({}, scenario_result["evaluation"])
            scenario_result["dry_run"] = True
            write_json(scenario_dir / "result.json", scenario_result)
            scenario_results.append(scenario_result)
            continue

        exit_code = run_subprocess(
            cmd=cmd,
            cwd=workdir,
            stdout_path=scenario_dir / "qwen-stream.jsonl",
            stderr_path=scenario_dir / "qwen-stderr.log",
            timeout=args.timeout_seconds,
            env=qwen_env,
        )
        scenario_result["qwen_exit_code"] = exit_code
        scenario_result["completed_at"] = utc_now()

        chat_log = find_chat_log(qwen_home, session_id)
        if chat_log is None:
            scenario_result["passed"] = False
            scenario_result["error"] = f"Qwen chat log not found for session_id={session_id}"
            overall_passed = False
            write_json(scenario_dir / "result.json", scenario_result)
            scenario_results.append(scenario_result)
            if not args.keep_going:
                break
            continue

        copied_chat_log = scenario_dir / "qwen-chat.jsonl"
        copied_chat_log.write_text(chat_log.read_text(encoding="utf-8"), encoding="utf-8")
        parsed = parse_chat_log(copied_chat_log)
        write_json(scenario_dir / "parsed-chat.json", parsed)

        evaluation = evaluate_assertions(scenario, parsed)
        failure_metrics = extract_failure_metrics(parsed, evaluation)
        passed = exit_code == 0 and evaluation["passed"]
        scenario_result["evaluation"] = evaluation
        scenario_result["failure_metrics"] = failure_metrics
        scenario_result["passed"] = passed
        if parsed.get("final_json") is not None:
            scenario_result["model_final_json"] = parsed["final_json"]
        else:
            scenario_result["model_final_text"] = parsed.get("final_text", "")
        write_json(scenario_dir / "result.json", scenario_result)
        scenario_results.append(scenario_result)

        if not passed:
            overall_passed = False
            if not args.keep_going:
                break

    metrics = aggregate_metrics(scenario_results)
    summary_payload = {
        "suite_id": suite.get("suite_id"),
        "title": suite.get("title"),
        "run_id": run_id,
        "started_at": utc_now(),
        "workdir": str(workdir),
        "results": scenario_results,
        "metrics": metrics,
        "passed": overall_passed,
    }
    write_json(run_dir / "summary.json", summary_payload)
    write_json(run_dir / "metrics.json", metrics)
    write_markdown_summary(run_dir / "summary.md", suite, scenario_results)
    write_metrics_report(run_dir / "metrics.md", suite, metrics)

    print(f"Run directory: {run_dir}")
    print(f"Overall result: {'PASS' if overall_passed else 'FAIL'}")
    return 0 if overall_passed else 1


if __name__ == "__main__":
    sys.exit(main())
