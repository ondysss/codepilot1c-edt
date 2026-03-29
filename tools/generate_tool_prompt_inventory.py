#!/usr/bin/env python3
from __future__ import annotations

import re
import zipfile
from pathlib import Path
from typing import Iterable
from xml.sax.saxutils import escape


ROOT = Path(__file__).resolve().parents[1]
CORE_SRC = ROOT / "bundles/com.codepilot1c.core/src"
UI_SRC = ROOT / "bundles/com.codepilot1c.ui/src"
REPORTS_DIR = ROOT / "docs/reports"


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def java_unescape(text: str) -> str:
    result: list[str] = []
    i = 0
    while i < len(text):
        ch = text[i]
        if ch != "\\":
            result.append(ch)
            i += 1
            continue
        if i + 1 >= len(text):
            result.append("\\")
            break
        nxt = text[i + 1]
        mapping = {
            "n": "\n",
            "r": "\r",
            "t": "\t",
            '"': '"',
            "'": "'",
            "\\": "\\",
        }
        if nxt in mapping:
            result.append(mapping[nxt])
            i += 2
            continue
        if nxt == "u" and i + 5 < len(text):
            try:
                result.append(chr(int(text[i + 2 : i + 6], 16)))
                i += 6
                continue
            except ValueError:
                pass
        result.append(nxt)
        i += 2
    return "".join(result)


def find_matching_brace(text: str, open_index: int) -> int:
    depth = 0
    in_string = False
    in_char = False
    in_line_comment = False
    in_block_comment = False
    escape_next = False
    i = open_index
    while i < len(text):
        ch = text[i]
        nxt = text[i + 1] if i + 1 < len(text) else ""
        if in_line_comment:
            if ch == "\n":
                in_line_comment = False
            i += 1
            continue
        if in_block_comment:
            if ch == "*" and nxt == "/":
                in_block_comment = False
                i += 2
                continue
            i += 1
            continue
        if in_string:
            if escape_next:
                escape_next = False
            elif ch == "\\":
                escape_next = True
            elif ch == '"':
                in_string = False
            i += 1
            continue
        if in_char:
            if escape_next:
                escape_next = False
            elif ch == "\\":
                escape_next = True
            elif ch == "'":
                in_char = False
            i += 1
            continue
        if ch == "/" and nxt == "/":
            in_line_comment = True
            i += 2
            continue
        if ch == "/" and nxt == "*":
            in_block_comment = True
            i += 2
            continue
        if ch == '"':
            in_string = True
            i += 1
            continue
        if ch == "'":
            in_char = True
            i += 1
            continue
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    raise ValueError("matching brace not found")


def find_method_source(java_text: str, method_name: str) -> str:
    match = re.search(
        rf"(?ms)(?:public|protected|private)\s+[^\n{{;]*\b{re.escape(method_name)}\s*\([^)]*\)\s*\{{",
        java_text,
    )
    if not match:
        return ""
    open_index = java_text.find("{", match.start())
    close_index = find_matching_brace(java_text, open_index)
    return java_text[match.start() : close_index + 1].strip()


def extract_return_expression(method_source: str) -> str:
    if not method_source:
        return ""
    match = re.search(r"\breturn\b", method_source)
    if not match:
        return ""
    i = match.end()
    while i < len(method_source) and method_source[i].isspace():
        i += 1
    start = i
    depth_paren = 0
    depth_bracket = 0
    depth_brace = 0
    in_string = False
    in_char = False
    in_text_block = False
    in_line_comment = False
    in_block_comment = False
    escape_next = False
    while i < len(method_source):
        ch = method_source[i]
        nxt = method_source[i + 1] if i + 1 < len(method_source) else ""
        if in_line_comment:
            if ch == "\n":
                in_line_comment = False
            i += 1
            continue
        if in_block_comment:
            if ch == "*" and nxt == "/":
                in_block_comment = False
                i += 2
                continue
            i += 1
            continue
        if in_text_block:
            if method_source.startswith('"""', i):
                in_text_block = False
                i += 3
                continue
            i += 1
            continue
        if in_string:
            if escape_next:
                escape_next = False
            elif ch == "\\":
                escape_next = True
            elif ch == '"':
                in_string = False
            i += 1
            continue
        if in_char:
            if escape_next:
                escape_next = False
            elif ch == "\\":
                escape_next = True
            elif ch == "'":
                in_char = False
            i += 1
            continue
        if ch == "/" and nxt == "/":
            in_line_comment = True
            i += 2
            continue
        if ch == "/" and nxt == "*":
            in_block_comment = True
            i += 2
            continue
        if method_source.startswith('"""', i):
            in_text_block = True
            i += 3
            continue
        if ch == '"':
            in_string = True
            i += 1
            continue
        if ch == "'":
            in_char = True
            i += 1
            continue
        if ch == "(":
            depth_paren += 1
        elif ch == ")":
            depth_paren = max(0, depth_paren - 1)
        elif ch == "[":
            depth_bracket += 1
        elif ch == "]":
            depth_bracket = max(0, depth_bracket - 1)
        elif ch == "{":
            depth_brace += 1
        elif ch == "}":
            depth_brace = max(0, depth_brace - 1)
        elif ch == ";" and depth_paren == 0 and depth_bracket == 0 and depth_brace == 0:
            return method_source[start:i].strip()
        i += 1
    return ""


def string_literals(expression: str) -> list[str]:
    return [java_unescape(m.group(1)) for m in re.finditer(r'"((?:\\.|[^"\\])*)"', expression, re.S)]


def extract_string_constant(java_text: str, constant_name: str) -> str:
    text_block = re.search(
        rf'(?s)\b{re.escape(constant_name)}\s*=\s*"""(.*?)"""\s*;',
        java_text,
    )
    if text_block:
        value = text_block.group(1)
        if value.startswith("\n"):
            value = value[1:]
        return value
    assignment = re.search(
        rf"(?s)\b{re.escape(constant_name)}\s*=\s*(.+?);",
        java_text,
    )
    if not assignment:
        return ""
    return "".join(string_literals(assignment.group(1)))


def extract_string_return(java_text: str, method_name: str) -> str:
    method_source = find_method_source(java_text, method_name)
    expression = extract_return_expression(method_source)
    if not expression:
        return ""
    text_block = re.fullmatch(r'"""\s*(.*?)\s*"""', expression, re.S)
    if text_block:
        value = text_block.group(1)
        if value.startswith("\n"):
            value = value[1:]
        return value
    if re.fullmatch(r"[A-Z0-9_]+", expression):
        return extract_string_constant(java_text, expression)
    return "".join(string_literals(expression))


def extract_bool_return(java_text: str, method_name: str, default: bool) -> bool:
    method_source = find_method_source(java_text, method_name)
    if not method_source:
        return default
    expression = extract_return_expression(method_source)
    if expression == "true":
        return True
    if expression == "false":
        return False
    return default


def extract_toolmeta_block(java_text: str) -> str:
    match = re.search(r"@ToolMeta\s*\((.*?)\)\s*(?:public\s+)?(?:final\s+)?class\s+", java_text, re.S)
    return match.group(1) if match else ""


def extract_toolmeta_string(java_text: str, attribute: str) -> str:
    block = extract_toolmeta_block(java_text)
    if not block:
        return ""
    match = re.search(rf"\b{re.escape(attribute)}\s*=\s*\"((?:\\.|[^\"\\])*)\"", block, re.S)
    return java_unescape(match.group(1)) if match else ""


def extract_toolmeta_bool(java_text: str, attribute: str, default: bool = False) -> bool:
    block = extract_toolmeta_block(java_text)
    if not block:
        return default
    match = re.search(rf"\b{re.escape(attribute)}\s*=\s*(true|false)", block)
    if not match:
        return default
    return match.group(1) == "true"


def package_name(java_text: str) -> str:
    match = re.search(r"(?m)^package\s+([a-zA-Z0-9_.]+)\s*;", java_text)
    return match.group(1) if match else ""


def public_class_name(java_text: str) -> str:
    match = re.search(r"(?m)^public\s+(?:final\s+)?class\s+([A-Za-z0-9_]+)\b", java_text)
    return match.group(1) if match else ""


def load_builtin_registrations() -> list[str]:
    registry_text = read_text(CORE_SRC / "com/codepilot1c/core/tools/ToolRegistry.java")
    return re.findall(r"register\(new\s+([A-Za-z0-9_]+)\([^;]*\)\);", registry_text)


def load_ui_dynamic_registrations() -> list[str]:
    plugin_text = read_text(UI_SRC / "com/codepilot1c/ui/internal/VibeUiPlugin.java")
    return re.findall(r"registerDynamicTool\(new\s+([A-Za-z0-9_]+)\(\)\);", plugin_text)


def collect_tool_rows() -> list[dict[str, str]]:
    builtin_classes = load_builtin_registrations()
    builtin_order = {name: index + 1 for index, name in enumerate(builtin_classes)}
    ui_dynamic_classes = set(load_ui_dynamic_registrations())
    rows: list[dict[str, str]] = []
    for src_root in (CORE_SRC, UI_SRC):
        for path in sorted(src_root.rglob("*.java")):
            text = read_text(path)
            if "implements ITool" not in text and "extends AbstractTool" not in text:
                continue
            class_name = public_class_name(text)
            if not class_name:
                continue
            fqcn = f"{package_name(text)}.{class_name}"
            tool_name = extract_string_return(text, "getName")
            if not tool_name:
                tool_name = extract_toolmeta_string(text, "name")
            description = extract_string_return(text, "getDescription")
            schema = extract_string_return(text, "getParameterSchema")
            if class_name == "McpToolAdapter":
                tool_name = "mcp_<server>_<tool>"
                description = "[MCP:<server>] <dynamic tool description>"
                schema = "<dynamic MCP input schema>"
            requires_confirmation = extract_bool_return(
                text,
                "requiresConfirmation",
                extract_toolmeta_bool(text, "mutating", False),
            )
            is_destructive = extract_bool_return(
                text,
                "isDestructive",
                extract_toolmeta_bool(text, "mutating", False),
            )
            registration = "not_default_registered"
            if class_name in builtin_order:
                registration = "builtin_default"
            elif class_name in ui_dynamic_classes:
                registration = "ui_dynamic"
            elif class_name == "McpToolAdapter":
                registration = "dynamic_mcp_adapter"
            bundle = path.relative_to(ROOT).parts[1]
            rows.append(
                {
                    "tool_name": tool_name,
                    "class_name": class_name,
                    "fqcn": fqcn,
                    "bundle": bundle,
                    "registration_kind": registration,
                    "registration_order": str(builtin_order.get(class_name, "")),
                    "requires_confirmation": str(requires_confirmation).lower(),
                    "is_destructive": str(is_destructive).lower(),
                    "description": description,
                    "parameter_schema": schema,
                    "source_file": str(path),
                }
            )
    rows.sort(key=lambda row: (row["registration_order"] == "", row["registration_order"] or "999", row["tool_name"], row["class_name"]))
    return rows


def collect_profile_rows() -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    profiles_dir = CORE_SRC / "com/codepilot1c/core/agent/profiles"
    builder_map = {
        "buildBuildPrompt": "build",
        "buildPlanPrompt": "plan",
        "buildExplorePrompt": "explore",
        "buildOrchestratorPrompt": "orchestrator",
    }
    for path in sorted(profiles_dir.glob("*Profile.java")):
        if path.name == "AgentProfile.java":
            continue
        text = read_text(path)
        class_name = public_class_name(text)
        if not class_name:
            continue
        profile_id = extract_string_constant(text, "ID") or extract_string_return(text, "getId")
        profile_name = extract_string_return(text, "getName")
        description = extract_string_return(text, "getDescription")
        read_only = str(extract_bool_return(text, "isReadOnly", False)).lower()
        can_execute_shell = str(extract_bool_return(text, "canExecuteShell", False)).lower()
        max_steps = extract_return_expression(find_method_source(text, "getMaxSteps"))
        timeout_ms = extract_return_expression(find_method_source(text, "getTimeoutMs"))
        prompt_method = ""
        prompt_name = ""
        prompt_method_match = re.search(
            r"AgentPromptTemplates\.(build[A-Za-z0-9_]+)\s*\(",
            find_method_source(text, "getSystemPromptAddition"),
        )
        if prompt_method_match:
            prompt_method = prompt_method_match.group(1)
            prompt_name = builder_map.get(prompt_method, prompt_method)
        allowed_tools_block = re.search(
            r"private\s+static\s+final\s+Set<String>\s+ALLOWED_TOOLS\s*=\s*new\s+HashSet<>\(Arrays\.asList\((.*?)\)\);",
            text,
            re.S,
        )
        allowed_tools = []
        if allowed_tools_block:
            allowed_tools = string_literals(allowed_tools_block.group(1))
        rows.append(
            {
                "profile_id": profile_id,
                "profile_name": profile_name,
                "class_name": class_name,
                "description": description,
                "prompt_builder": prompt_method,
                "prompt_name": prompt_name,
                "read_only": read_only,
                "can_execute_shell": can_execute_shell,
                "max_steps": max_steps,
                "timeout_ms_expr": timeout_ms,
                "allowed_tools_count": str(len(allowed_tools)),
                "allowed_tools": ", ".join(sorted(allowed_tools)),
                "source_file": str(path),
            }
        )
    return rows


def collect_profile_tool_matrix_rows() -> list[dict[str, str]]:
    rows: list[dict[str, str]] = []
    for profile in collect_profile_rows():
        allowed_tools = [item.strip() for item in profile["allowed_tools"].split(",") if item.strip()]
        for tool_name in allowed_tools:
            rows.append(
                {
                    "profile_id": profile["profile_id"],
                    "profile_name": profile["profile_name"],
                    "prompt_name": profile["prompt_name"],
                    "read_only": profile["read_only"],
                    "tool_name": tool_name,
                }
            )
    rows.sort(key=lambda row: (row["profile_id"], row["tool_name"]))
    return rows


def collect_call_hierarchy_rows() -> list[dict[str, str]]:
    return [
        {
            "flow": "desktop_chat_direct",
            "stage_order": "1",
            "component": "ChatView.sendMessage/buildRequestWithTools",
            "source_file": str(UI_SRC / "com/codepilot1c/ui/views/ChatView.java"),
            "role": "UI chat builds system prompt, conversation history, and raw tool surface from ToolRegistry.",
            "next_stage": "Provider.complete/streamComplete",
        },
        {
            "flow": "desktop_chat_direct",
            "stage_order": "2",
            "component": "ILlmProvider.complete|streamComplete",
            "source_file": str(CORE_SRC / "com/codepilot1c/core/provider"),
            "role": "Provider receives messages and tool definitions; Qwen path augments system prompt with XML tool examples.",
            "next_stage": "LlmResponse.toolCalls",
        },
        {
            "flow": "desktop_chat_direct",
            "stage_order": "3",
            "component": "ChatView.processToolCalls",
            "source_file": str(UI_SRC / "com/codepilot1c/ui/views/ChatView.java"),
            "role": "UI inspects tool calls, separates previewable edit_file calls, asks confirmation for destructive tools.",
            "next_stage": "ToolRegistry.execute",
        },
        {
            "flow": "desktop_chat_direct",
            "stage_order": "4",
            "component": "ToolRegistry -> ToolExecutionService -> ITool.execute",
            "source_file": str(CORE_SRC / "com/codepilot1c/core/tools/ToolExecutionService.java"),
            "role": "Runtime parses JSON args, logs/traces the tool call, executes implementation, wraps ToolResult.",
            "next_stage": "LlmMessage.toolResult",
        },
        {
            "flow": "desktop_chat_direct",
            "stage_order": "5",
            "component": "ChatView.continueAfterToolCalls",
            "source_file": str(UI_SRC / "com/codepilot1c/ui/views/ChatView.java"),
            "role": "Tool results are appended into conversation and sent back to the provider for the next iteration.",
            "next_stage": "Final assistant content",
        },
        {
            "flow": "agent_runner_remote",
            "stage_order": "1",
            "component": "AgentSessionController.submitPrompt",
            "source_file": str(CORE_SRC / "com/codepilot1c/core/remote/AgentSessionController.java"),
            "role": "Remote/desktop orchestration chooses AgentProfile, builds AgentConfig, creates LangGraphAgentRunner.",
            "next_stage": "AgentRunner.run",
        },
        {
            "flow": "agent_runner_remote",
            "stage_order": "2",
            "component": "AgentRunner.buildSystemPrompt",
            "source_file": str(CORE_SRC / "com/codepilot1c/core/agent/AgentRunner.java"),
            "role": "SystemPromptAssembler merges base prompt, profile prompt, AGENTS.md layers, Code.md layers, skills, and prompt overrides.",
            "next_stage": "AgentRunner.buildRequest",
        },
        {
            "flow": "agent_runner_remote",
            "stage_order": "3",
            "component": "AgentRunner.buildRequest",
            "source_file": str(CORE_SRC / "com/codepilot1c/core/agent/AgentRunner.java"),
            "role": "Tool surface is filtered by profile allowlist, ToolGraph, ToolContextGate, deferred loading, and augmentors.",
            "next_stage": "ILlmProvider.complete|streamComplete",
        },
        {
            "flow": "agent_runner_remote",
            "stage_order": "4",
            "component": "AgentRunner.executeSingleToolCall",
            "source_file": str(CORE_SRC / "com/codepilot1c/core/agent/AgentRunner.java"),
            "role": "Every tool call emits ToolCallEvent, optionally asks confirmation, then runs via ToolRegistry/ToolExecutionService.",
            "next_stage": "ToolResultEvent + conversation tool result",
        },
        {
            "flow": "agent_runner_remote",
            "stage_order": "5",
            "component": "ToolGraphRouter.onToolResult",
            "source_file": str(CORE_SRC / "com/codepilot1c/core/agent/graph/ToolGraphRouter.java"),
            "role": "Successful validation/mutation calls advance graph nodes and can change the next visible tool subset.",
            "next_stage": "Next AgentRunner loop iteration",
        },
        {
            "flow": "prompt_assembly",
            "stage_order": "1",
            "component": "AgentPromptTemplates",
            "source_file": str(CORE_SRC / "com/codepilot1c/core/agent/prompts/AgentPromptTemplates.java"),
            "role": "Built-in profile prompt builders: build, plan, explore, subagent, orchestrator.",
            "next_stage": "PromptProviderRegistry",
        },
        {
            "flow": "prompt_assembly",
            "stage_order": "2",
            "component": "PromptProviderRegistry",
            "source_file": str(CORE_SRC / "com/codepilot1c/core/agent/prompts/PromptProviderRegistry.java"),
            "role": "Optional overlay prompt provider from extension point com.codepilot1c.core.promptProvider.",
            "next_stage": "SystemPromptAssembler",
        },
        {
            "flow": "prompt_assembly",
            "stage_order": "3",
            "component": "SystemPromptAssembler",
            "source_file": str(CORE_SRC / "com/codepilot1c/core/agent/prompts/SystemPromptAssembler.java"),
            "role": "Final prompt merge stage with AGENTS.md layers, Code.md layers, skills, and PromptTemplateService post-processing.",
            "next_stage": "Provider request body",
        },
        {
            "flow": "provider_transport",
            "stage_order": "1",
            "component": "QwenFunctionCallingTransport",
            "source_file": str(CORE_SRC / "com/codepilot1c/core/provider/config/QwenFunctionCallingTransport.java"),
            "role": "For Qwen-native providers, injects XML tool call examples into system message and sends structured tools API.",
            "next_stage": "QwenContentToolCallParser/QwenStreamingToolCallParser",
        },
    ]


def collect_registered_tool_rows(all_tools: list[dict[str, str]]) -> list[dict[str, str]]:
    by_class = {row["class_name"]: row for row in all_tools}
    rows: list[dict[str, str]] = []
    for class_name in load_builtin_registrations():
        row = dict(by_class[class_name])
        row["source_registration"] = "ToolRegistry.registerDefaultTools()"
        rows.append(row)
    for class_name in load_ui_dynamic_registrations():
        row = dict(by_class[class_name])
        row["source_registration"] = "VibeUiPlugin.registerUiTools()"
        rows.append(row)
    return rows


def preference_constant_map() -> dict[str, str]:
    text = read_text(CORE_SRC / "com/codepilot1c/core/settings/VibePreferenceConstants.java")
    return {
        const_name: value
        for const_name, value in re.findall(
            r'public\s+static\s+final\s+String\s+([A-Z0-9_]+)\s*=\s*"([^"]+)";',
            text,
        )
    }


def collect_ui_prompt_rows() -> list[dict[str, str]]:
    constants = preference_constant_map()
    text = read_text(CORE_SRC / "com/codepilot1c/core/settings/PromptCatalog.java")
    template_entries = re.findall(
        r'(?s)Map\.entry\(VibePreferenceConstants\.([A-Z0-9_]+),\s*"""(.*?)"""\)',
        text,
    )
    placeholder_map = {
        const_name: ", ".join(string_literals(placeholders))
        for const_name, placeholders in re.findall(
            r"Map\.entry\(VibePreferenceConstants\.([A-Z0-9_]+),\s*Set\.of\((.*?)\)\)",
            text,
            re.S,
        )
    }
    handler_map = collect_prompt_handler_usage()
    rows: list[dict[str, str]] = []
    for const_name, template in template_entries:
        if template.startswith("\n"):
            template = template[1:]
        rows.append(
            {
                "preference_constant": const_name,
                "preference_key": constants.get(const_name, ""),
                "handlers": ", ".join(handler_map.get(const_name, [])),
                "required_placeholders": placeholder_map.get(const_name, ""),
                "template_text": template,
                "source_file": str(CORE_SRC / "com/codepilot1c/core/settings/PromptCatalog.java"),
            }
        )
    return rows


def collect_prompt_handler_usage() -> dict[str, list[str]]:
    usage: dict[str, list[str]] = {}
    for path in sorted(UI_SRC.rglob("*Handler.java")):
        text = read_text(path)
        if "PromptTemplateService.getInstance().applyTemplate" not in text:
            continue
        for constant in re.findall(r"VibePreferenceConstants\.([A-Z0-9_]+)", text):
            usage.setdefault(constant, []).append(path.stem)
    for handlers in usage.values():
        handlers.sort()
    return usage


def collect_agent_prompt_rows() -> list[dict[str, str]]:
    provider_path = CORE_SRC / "com/codepilot1c/core/mcp/host/prompt/PromptTemplateProvider.java"
    provider_text = read_text(provider_path)
    templates_path = CORE_SRC / "com/codepilot1c/core/agent/prompts/AgentPromptTemplates.java"
    templates_text = read_text(templates_path)
    builder_methods = {
        "build": "buildBuildPrompt",
        "plan": "buildPlanPrompt",
        "explore": "buildExplorePrompt",
        "subagent": "buildSubagentPrompt",
    }
    descriptions = {
        name: description
        for name, description in re.findall(r'new\s+McpPrompt\("([^"]+)",\s*"([^"]+)"\)', provider_text)
    }
    arg_notes = {
        "build": "",
        "plan": "",
        "explore": "",
        "subagent": "profile=mcp(default), description='MCP prompt request', readOnly=true",
    }
    rows: list[dict[str, str]] = []
    for name in ("build", "plan", "explore", "subagent"):
        method_name = builder_methods[name]
        rows.append(
            {
                "prompt_name": name,
                "mcp_description": descriptions.get(name, ""),
                "builder_method": method_name,
                "builder_source_file": str(templates_path),
                "provider_source_file": str(provider_path),
                "prompt_builder_source": find_method_source(templates_text, method_name),
                "runtime_assembly": "AgentPromptTemplates -> SystemPromptAssembler -> PromptTemplateService",
                "default_arguments": arg_notes[name],
            }
        )
    return rows


def collect_prompt_component_rows() -> list[dict[str, str]]:
    components = [
        (
            "AgentPromptTemplates",
            "agent_prompt_builders",
            CORE_SRC / "com/codepilot1c/core/agent/prompts/AgentPromptTemplates.java",
            "Source of built-in build/plan/explore/subagent system prompt builders.",
        ),
        (
            "PromptTemplateProvider",
            "mcp_prompt_provider",
            CORE_SRC / "com/codepilot1c/core/mcp/host/prompt/PromptTemplateProvider.java",
            "Exposes project prompts via MCP endpoints prompts/list and prompts/get.",
        ),
        (
            "SystemPromptAssembler",
            "runtime_prompt_assembler",
            CORE_SRC / "com/codepilot1c/core/agent/prompts/SystemPromptAssembler.java",
            "Builds effective system prompt with AGENTS.md, Code.md, skills, and prompt additions.",
        ),
        (
            "PromptProviderRegistry",
            "prompt_override_registry",
            CORE_SRC / "com/codepilot1c/core/agent/prompts/PromptProviderRegistry.java",
            "Loads optional overlay prompt provider from com.codepilot1c.core.promptProvider.",
        ),
        (
            "IPromptProvider",
            "prompt_provider_contract",
            CORE_SRC / "com/codepilot1c/core/agent/prompts/IPromptProvider.java",
            "Extension contract for per-profile prompt additions.",
        ),
        (
            "PromptTemplateService",
            "prompt_customization_service",
            CORE_SRC / "com/codepilot1c/core/settings/PromptTemplateService.java",
            "Applies user-defined system prefix/suffix and UI prompt template overrides.",
        ),
        (
            "PromptCatalog",
            "ui_prompt_catalog",
            CORE_SRC / "com/codepilot1c/core/settings/PromptCatalog.java",
            "Stores built-in UI command prompt templates and required placeholders.",
        ),
        (
            "PromptsPreferencePage",
            "ui_prompt_editor",
            UI_SRC / "com/codepilot1c/ui/preferences/PromptsPreferencePage.java",
            "Workbench page where prompt templates are reviewed and customized.",
        ),
        (
            "plugin.xml",
            "extension_points",
            ROOT / "bundles/com.codepilot1c.core/plugin.xml",
            "Declares promptProvider extension point for prompt overrides in overlay bundles.",
        ),
    ]
    return [
        {
            "component": name,
            "kind": kind,
            "source_file": str(path),
            "role": role,
        }
        for name, kind, path, role in components
    ]


def xml_col_name(index: int) -> str:
    result = ""
    value = index
    while value > 0:
        value, remainder = divmod(value - 1, 26)
        result = chr(65 + remainder) + result
    return result


def sheet_xml(headers: list[str], rows: list[dict[str, str]]) -> str:
    columns = headers
    widths: list[int] = []
    for header in columns:
        max_len = len(header)
        for row in rows:
            value = "" if row.get(header) is None else str(row.get(header))
            cell_len = max((len(line) for line in value.splitlines()), default=0)
            max_len = max(max_len, min(cell_len, 80))
        widths.append(min(max(max_len + 2, 12), 60))

    cell_rows: list[str] = []
    header_cells = []
    for col_index, header in enumerate(columns, start=1):
        cell_ref = f"{xml_col_name(col_index)}1"
        header_cells.append(
            f'<c r="{cell_ref}" t="inlineStr" s="1"><is><t>{escape(header)}</t></is></c>'
        )
    cell_rows.append(f'<row r="1" spans="1:{len(columns)}">{"".join(header_cells)}</row>')

    for row_number, row in enumerate(rows, start=2):
        cells = []
        for col_index, header in enumerate(columns, start=1):
            value = "" if row.get(header) is None else str(row.get(header))
            cell_ref = f"{xml_col_name(col_index)}{row_number}"
            value = escape(value)
            if value.startswith((" ", "\n")) or value.endswith((" ", "\n")):
                value = value.replace("\r", "")
                cells.append(
                    f'<c r="{cell_ref}" t="inlineStr" s="2"><is><t xml:space="preserve">{value}</t></is></c>'
                )
            else:
                cells.append(f'<c r="{cell_ref}" t="inlineStr" s="2"><is><t>{value}</t></is></c>')
        cell_rows.append(f'<row r="{row_number}" spans="1:{len(columns)}">{"".join(cells)}</row>')

    cols_xml = "".join(
        f'<col min="{index}" max="{index}" width="{width}" customWidth="1"/>'
        for index, width in enumerate(widths, start=1)
    )
    last_col = xml_col_name(len(columns))
    last_row = max(len(rows) + 1, 1)
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
        f"<dimension ref=\"A1:{last_col}{last_row}\"/>"
        "<sheetViews><sheetView workbookViewId=\"0\">"
        "<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>"
        "</sheetView></sheetViews>"
        "<sheetFormatPr defaultRowHeight=\"15\"/>"
        f"<cols>{cols_xml}</cols>"
        "<sheetData>"
        + "".join(cell_rows)
        + "</sheetData>"
        f'<autoFilter ref="A1:{last_col}{last_row}"/>'
        "</worksheet>"
    )


def workbook_xml(sheet_names: list[str]) -> str:
    sheets = []
    for index, name in enumerate(sheet_names, start=1):
        safe_name = escape(name)
        sheets.append(
            f'<sheet name="{safe_name}" sheetId="{index}" r:id="rId{index}"/>'
        )
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
        'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
        "<sheets>"
        + "".join(sheets)
        + "</sheets>"
        "</workbook>"
    )


def workbook_rels_xml(sheet_count: int) -> str:
    rels = []
    for index in range(1, sheet_count + 1):
        rels.append(
            f'<Relationship Id="rId{index}" '
            'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" '
            f'Target="worksheets/sheet{index}.xml"/>'
        )
    rels.append(
        f'<Relationship Id="rId{sheet_count + 1}" '
        'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" '
        'Target="styles.xml"/>'
    )
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        + "".join(rels)
        + "</Relationships>"
    )


def root_rels_xml() -> str:
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" '
        'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" '
        'Target="xl/workbook.xml"/>'
        "</Relationships>"
    )


def content_types_xml(sheet_count: int) -> str:
    overrides = [
        '<Override PartName="/xl/workbook.xml" '
        'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>',
        '<Override PartName="/xl/styles.xml" '
        'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>',
    ]
    for index in range(1, sheet_count + 1):
        overrides.append(
            f'<Override PartName="/xl/worksheets/sheet{index}.xml" '
            'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
        )
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="rels" '
        'ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Default Extension="xml" ContentType="application/xml"/>'
        + "".join(overrides)
        + "</Types>"
    )


def styles_xml() -> str:
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
        '<fonts count="2">'
        '<font><sz val="11"/><name val="Calibri"/></font>'
        '<font><b/><sz val="11"/><name val="Calibri"/></font>'
        "</fonts>"
        '<fills count="3">'
        '<fill><patternFill patternType="none"/></fill>'
        '<fill><patternFill patternType="gray125"/></fill>'
        '<fill><patternFill patternType="solid"><fgColor rgb="FFD9EAF7"/><bgColor indexed="64"/></patternFill></fill>'
        "</fills>"
        '<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>'
        '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>'
        '<cellXfs count="3">'
        '<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>'
        '<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1">'
        '<alignment horizontal="center" vertical="center" wrapText="1"/>'
        "</xf>"
        '<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1">'
        '<alignment vertical="top" wrapText="1"/>'
        "</xf>"
        "</cellXfs>"
        '<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>'
        "</styleSheet>"
    )


def sanitize_sheet_name(name: str) -> str:
    sanitized = re.sub(r"[\[\]\:\*\?/\\]", "_", name)
    return sanitized[:31] if len(sanitized) > 31 else sanitized


def write_workbook(path: Path, sheets: Iterable[tuple[str, list[dict[str, str]]]]) -> None:
    normalized = []
    for name, rows in sheets:
        sheet_name = sanitize_sheet_name(name)
        headers = []
        if rows:
            headers = list(rows[0].keys())
        normalized.append((sheet_name, headers, rows))

    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("[Content_Types].xml", content_types_xml(len(normalized)))
        archive.writestr("_rels/.rels", root_rels_xml())
        archive.writestr("xl/workbook.xml", workbook_xml([name for name, _, _ in normalized]))
        archive.writestr("xl/_rels/workbook.xml.rels", workbook_rels_xml(len(normalized)))
        archive.writestr("xl/styles.xml", styles_xml())
        for index, (_, headers, rows) in enumerate(normalized, start=1):
            archive.writestr(f"xl/worksheets/sheet{index}.xml", sheet_xml(headers, rows))


def summary_rows() -> list[dict[str, str]]:
    registered_tools = collect_registered_tool_rows(collect_tool_rows())
    return [
        {
            "artifact": "tools-inventory.xlsx",
            "contains": "registered tools, all ITool classes, tool registration sources, profile matrix, call hierarchy",
            "row_count": str(len(registered_tools)),
            "path": str(REPORTS_DIR / "tools-inventory.xlsx"),
        },
        {
            "artifact": "prompts-inventory.xlsx",
            "contains": "MCP/agent prompt builders, UI prompt templates, prompt system components",
            "row_count": str(len(collect_agent_prompt_rows()) + len(collect_ui_prompt_rows())),
            "path": str(REPORTS_DIR / "prompts-inventory.xlsx"),
        },
    ]


def main() -> None:
    all_tools = collect_tool_rows()
    registered_tools = collect_registered_tool_rows(all_tools)
    tool_sheets = [
        ("registered_tools", registered_tools),
        ("all_itool_classes", all_tools),
        ("profiles", collect_profile_rows()),
        ("profile_tool_matrix", collect_profile_tool_matrix_rows()),
        ("call_hierarchy", collect_call_hierarchy_rows()),
    ]
    prompt_sheets = [
        ("agent_mcp_prompts", collect_agent_prompt_rows()),
        ("ui_prompt_templates", collect_ui_prompt_rows()),
        ("prompt_components", collect_prompt_component_rows()),
        ("summary", summary_rows()),
    ]
    write_workbook(REPORTS_DIR / "tools-inventory.xlsx", tool_sheets)
    write_workbook(REPORTS_DIR / "prompts-inventory.xlsx", prompt_sheets)


if __name__ == "__main__":
    main()
