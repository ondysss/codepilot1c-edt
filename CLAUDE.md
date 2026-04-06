# CLAUDE

Use this file as the Claude Code project memory for this repository. It mirrors the operational rules in `AGENTS.md`.

## Project Scope
- This repository is an Eclipse RCP/OSGi plugin suite for 1C:EDT, not only MCP Host.
- Main bundles:
  - `bundles/com.codepilot1c.core` for agent loop, tools, provider registries, MCP client/host, EDT integrations.
  - `bundles/com.codepilot1c.ui` for workbench/UI integration only.
  - `bundles/com.codepilot1c.rag` for indexing/chunking/vector storage.
- Build/release stack is Tycho reactor: `targets`, `bundles`, `features`, `repositories`.

## Mandatory Architecture Rules
- Keep `core` independent from workbench-specific UI APIs.
- Register built-in tools only in `ToolRegistry.registerDefaultTools()`.
- Use dynamic tools for MCP/UI/runtime contributions.
- Keep tool payloads deterministic and machine-usable.
- Keep capabilities profile-driven and aligned with actually allowed tools.
- Extend via extension points; do not hardcode a single provider implementation.

## Qwen Optimization Rules (MANDATORY for all new tools)

### Provider-gated architecture
- All Qwen-specific behavior MUST be gated behind `ProviderCapabilities.isQwenNative()`.
- `isQwenNative()` returns `true` ONLY when CodePilot Account is active AND model is from the Qwen family.
- Non-CodePilot providers MUST NOT be affected by any Qwen optimization.

### Dual-mode transport (CodePilot backend)
- Request building for CodePilot backend goes through `QwenFunctionCallingTransport` (not `buildOpenAiRequestBody`).
- Transport uses **dual-mode**: structured API (`tools` param) + XML tool call priming in system message.
- XML priming format is selected by `ProviderCapabilities.getResolvedModelFamily()`:
  - `qwen-coder` → XML `<tool_call><function=NAME><parameter=KEY>value</parameter></function></tool_call>`
  - `qwen-vl` → JSON-in-XML `<tool_call>{"name":"...","arguments":{...}}</tool_call>`
  - `qwen-general` → same as qwen-coder
- Content fallback (`QwenContentToolCallParser`) catches XML tool calls from text if structured API doesn't return them.

### New tool implementation requirements
- Every new `ITool` implementation MUST include Qwen-compatible tool call examples in `QwenToolCallExamples.inferExampleParams()`.
- Tool name MUST follow one of the recognized patterns (`read`, `edit`, `write`, `search`, `shell`, `list`, `task`, `skill`) OR a new pattern must be added to `inferExampleParams()`.
- Tool description MUST be concise (under 200 chars) — Qwen models have limited tool description context budget.
- Tool parameter JSON schema MUST use flat structures when possible — deeply nested schemas reduce Qwen tool call accuracy.
- Required parameters MUST be listed in the `required` array of the JSON schema — Qwen relies on this for argument generation.

### Streaming and compatibility
- `OpenAiModelCompatibilityPolicy` contains Qwen-specific execution plan rules:
  - `temperature=0.3` for all Qwen models.
  - `enable_thinking=false` for tool call requests.
  - Non-stream fallback for large tool results (>50k chars) or large requests (>120k chars).
- `QwenStreamingToolCallParser` handles DashScope streaming quirks (index reuse, false `finish_reason="stop"`).
- `JsonRepairUtil` repairs truncated JSON arguments from streaming.

### Key files
- `provider/ProviderCapabilities.java` — capability flags + `resolveModelFamily()`.
- `provider/config/QwenFunctionCallingTransport.java` — dual-mode request builder.
- `provider/config/QwenToolCallExamples.java` — XML/JSON/text priming examples.
- `provider/config/QwenContentToolCallParser.java` — content fallback parser.
- `provider/config/QwenStreamingToolCallParser.java` — enhanced streaming parser.
- `provider/config/JsonRepairUtil.java` — JSON repair utility.
- `provider/config/OpenAiModelCompatibilityPolicy.java` — model-specific execution plans.

### Testing Qwen changes
- After any change to Qwen transport or tool definitions, verify with full reactor build: `mvn -DskipTests package`.
- For tool call accuracy testing, check that `QwenToolCallExamples.getExamples()` produces valid XML for new tools.
- Content fallback MUST be tested for both qwen-coder (XML) and qwen-vl (JSON) formats.

## EDT Rules
- For BM objects: null-check, normalize to top object with `bmGetTopObject()`, and call `bmGetFqn()` only on top objects.
- Treat BM commit and filesystem export as separate phases.
- Do not assume `forceExport(...)` or synchronization helpers are fully synchronous.
- Use EDT mutation tools and validation-token flow; do not edit `.mdo` files directly unless the user explicitly requests emergency recovery.

## Build/Release Rules
- CI release/build workflows are intentionally disabled; use local flow.
- For deliverable update site builds, ALWAYS run full reactor build from repository root: `mvn -DskipTests package`.
- Do NOT use partial build for release/update delivery (e.g. `-pl repositories/com.codepilot1c.update -am`), because it may produce unresolved/missing feature artifacts or stale site content.
- After any code change that needs testing in EDT, ALWAYS build the full reactor and tell the user the p2 update site is ready.
- p2 update site output: `repositories/com.codepilot1c.update/target/repository` (or zip `repositories/com.codepilot1c.update/target/com.codepilot1c.update-*-SNAPSHOT.zip`).
- Local publish script: `tools/publish-p2-local.sh`.
- After build, verify that versions in `content.jar`/`plugins/` match expected latest qualifier before asking user to update.

### Build Checklist (MANDATORY after code changes)
1. Run `mvn -DskipTests package` from repo root.
2. Confirm BUILD SUCCESS.
3. Verify p2 output exists at `repositories/com.codepilot1c.update/target/repository`.
4. Tell user the p2 update site is ready to install/update in EDT.

## Verification Rules
- Re-run diagnostics after metadata/form mutations.
- Treat persistent type warnings as correctness bugs, not cosmetic issues.

## Local Runbooks
- Read `docs/reports/edt-metadata-uuid-export-runbook.md` before debugging EDT export/import issues.
- If export fails with `uuid=null`, check the root `mdclass` UUID in affected `.mdo` files first.
- Qwen optimization plan: `docs/QWEN_OPTIMIZATION_PLAN.md`.

## Current Flow Guidance
- This repo contains worktree-based implementer -> Codex review loops under `tools/run-*-codex-{flow,queue,plan}.sh`.
- Keep changes minimal, do not commit/push from the automation flow, and prefer the smallest relevant verification.
- When blocked, report the exact blocker instead of guessing.
