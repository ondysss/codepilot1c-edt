# Phase 4 Context — Live EDT Audit Remediation

## Why this phase exists

The local implementation and focused tests passed, and a full update-site package was built. After installing/updating the plugin in EDT, live workspace audits showed that several low-level primitives still fail in the real EDT runtime.

This is not a new feature phase. It is a corrective phase created from live audit evidence.

## Canonical audits

- `.planning/audits/edt-extension-migration-live-audit-2026-07-06.md` — log-derived audit from `/Volumes/T9/workspace/do`.
- `.planning/audits/edt-extension-migration-live-audit-bot-2026-07-06.md` — bot live-smoke report from `/Volumes/T9/repo_edt/artel` / projects `ДО`, `ДО.Артель`.

## Must-fix findings

- LIVE-01 / EDTEXT-02: `commandParameterType` TypeDescription resolution fails for a type returned by candidates: `CatalogRef.ДокументыПредприятия`.
- LIVE-02 / EDTEXT-03: `allow_auto_prefix=false` is ignored; validation still returns `valid=true`, `autoPrefixed=true`.
- LIVE-03 / EDTEXT-05: StandardCommandGroup is treated as metadata FQN during create/update instead of enum/literal value; unknown groups lack available-values hint.
- LIVE-04 / EDTEXT-06: `mutate_role_rights` has no exposed validation operation/token flow, so structured extension-rights diagnostics are unreachable.
- LIVE-05 / EDTEXT-08: live diagnostics must be classified; extension baseline had pre-existing errors, but closure needs explicit evidence.

## Already-passing live checks

- EDTEXT-01 Bot adopt/lookup: PASS.
- EDTEXT-04 CommonCommand `CommandModule.bsl` creation and BSL `COMMAND_MODULE` context: PASS.
- EDTEXT-07 `migrate_to_extension_native` dry-run and unsafe apply gate: PASS.

## Guardrails

- Preserve validation-token flow.
- Preserve Qwen tool compatibility for changed tool schemas/descriptions.
- Preserve deterministic `ToolResult` payloads.
- Do not manually patch `.mdo` files as the primary fix.
- Keep `core` independent from UI APIs.
