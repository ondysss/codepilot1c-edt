# EDT Extension Migration Live Audit — 2026-07-06

## Source

EDT workspace logs inspected from `/Volumes/T9/workspace/do`:

- `.metadata/.log`
- `.metadata/.plugins/com.codepilot1c.core/vibe.log`

## Verdict

FAIL: live EDT smoke found blocking issues after the local implementation/build passed.

## Passed Evidence

- `migrate_to_extension_native` dry-run is available and returned `SUCCESS`, `operations=22`.
- `migrate_to_extension_native` apply mode is gated and refused unsafe apply with `KNOWLEDGE_REQUIRED`.
- Extension validation emits `effectiveName`, `effectiveFqn`, and `autoPrefixed=true` for unprefixed extension names.
- `ensure_module_artifact` created `CommandModule.bsl` for `CommonCommand`.
- `get_diagnostics` for the checked extension object returned `0 errors, 0 warnings` in one targeted check.

## Blocking Findings

### LIVE-01 — CommonCommand commandParameterType TypeDescription resolution fails

Severity: HIGH
Area: EDT metadata mutation / TypeDescription
Tools: `create_metadata`, `update_metadata`
Evidence:

- `.metadata/.log:11627-11636`
- `.metadata/.log:11870-11879`
- `.metadata/.log:12029-12038`

Actual:

- `Type not found for field 'commandParameterType': CatalogRef.Контрагенты (INVALID_PROPERTY_VALUE)`
- `Type not found for field 'commandParameterType': CatalogRef.ДокументыПредприятия (INVALID_PROPERTY_VALUE)`

Expected:

- Valid catalog reference type specs in the live EDT workspace should resolve into a `TypeDescription` or fail during validation with a deterministic candidates/hint payload.

### LIVE-02 — StandardCommandGroup is treated as metadata FQN during create/update

Severity: HIGH
Area: EDT metadata mutation / enum/reference normalization
Tools: `create_metadata`, `update_metadata`
Evidence:

- `.metadata/.log:12515` validation normalized update payload to `group=FormCommandBarImportant`.
- `.metadata/.log:12611-12623` update path resolved `FormCommandBarImportant` as metadata and failed with `METADATA_PARENT_NOT_FOUND`.
- `.metadata/.log:13388-13406` create path resolved `StandardCommandGroup.FormCommandBarImportant` as top-level metadata and failed with `METADATA_NOT_FOUND`.

Actual:

- `findTopLevel: unsupported top-level type token: StandardCommandGroup`
- `Referenced metadata object not found: StandardCommandGroup.FormCommandBarImportant`

Expected:

- Standard command group aliases should be normalized and applied as EDT enum values, not resolved as metadata references.

### LIVE-03 — Role-right diagnostic check used the wrong validation operation

Severity: MEDIUM
Area: audit scenario / validation-token flow
Tool: `mutate_role_rights`
Evidence:

- `.metadata/.log:13499-13538`

Actual:

- Agent validated `operation=update_metadata`, then called `mutate_role_rights`.
- Tool correctly failed with `INVALID_VALIDATION_TOKEN`.

Expected:

- Retest with `edt_validate_request(operation=mutate_role_rights)` and pass the resulting token unchanged to `mutate_role_rights`.

### LIVE-04 — Final live diagnostics are not clean

Severity: HIGH
Area: live EDT diagnostics
Tools: `get_diagnostics`
Evidence:

- `.metadata/.log:13804-13823`

Actual:

- `ДО.Артель`: `9 errors, 0 warnings`
- `ДО`: `30 errors, 0 warnings`

Expected:

- After live mutation smoke, extension-relevant diagnostics should be clean or known unrelated diagnostics must be explicitly classified.

## Non-blocking Findings

### LIVE-05 — BSL module context waited 30 seconds for BmAwareResourceSetProvider

Severity: LOW
Area: EDT service availability / performance
Evidence:

- `.metadata/.log:12451-12458`

Actual:

- Warning: `EDT service not available after wait (30000 ms): BmAwareResourceSetProvider`
- Tool still ended with `SUCCESS`.

### LIVE-06 — EDT has stale broken p2 repository entries

Severity: LOW
Area: local EDT update-site configuration
Evidence:

- `.metadata/.log:9491-9639`

Actual:

- EDT tried to load missing old zip repositories under Downloads/Desktop and failed with `NoSuchFileException`.

## Recommended GSD Action

Reopen the milestone as `live-audit-failed` and add follow-up phases:

1. Phase 4 — fix live EDT primitive failures found by audit.
2. Phase 5 — rerun release/update-site install and live EDT smoke, then close milestone only with clean evidence.
