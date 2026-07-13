# EDT Extension Migration Tooling Audit Report — Bot Live Smoke

## Summary

- Overall status: FAIL
- EDT workspace: `/Volumes/T9/repo_edt/artel`, projects `ДО`, `ДО.Артель`
- Main project: `ДО`
- Extension project: `ДО.Артель`
- Checked at: 2026-07-06
- Extension/build version if visible: not visible from available tools

Report file created by bot:

- `ДО.Артель/.planning/audits/edt-extension-migration-live-audit-2026-07-06.md`

## Checklist

### EDTEXT-01 Bot adopt / lookup

Status: PASS

Evidence: Base Bot `Bot.аи_МастерБотАртель` exists. `extension_manage(command=adopt)` returned adopted object payload, not `METADATA_NOT_FOUND`: `adoptedObjectFqn=Bot.аи_МастерБотАртель`, `kind=Bot`, `alreadyAdopted=false`.

Commands/tools used: `edt_metadata_details`, `edt_validate_request`, `extension_manage`, `list_files`

Notes: Safe smoke mutated extension by adopting Bot; source object was not deleted.

### EDTEXT-02 Generic TypeDescription mutation

Status: FAIL

Evidence: `create_metadata(Constant)` with `properties.type="Boolean"` succeeded and metadata shows `TypeDescription ... types=[Type.Boolean]`. However `create_metadata(CommonCommand)` and `update_metadata(CommonCommand.commandParameterType)` failed for a candidate type returned by `edt_field_type_candidates`: `CatalogRef.ДокументыПредприятия` produced `[INVALID_PROPERTY_VALUE] Type not found for field 'commandParameterType'`.

Commands/tools used: `edt_validate_request`, `create_metadata`, `update_metadata`, `edt_metadata_details`, `edt_field_type_candidates`, `get_diagnostics`

Notes: Constant smoke object created: `Constant.ар_AuditTypeDescription20260706`. Diagnostics for this object were clean.

### EDTEXT-03 effectiveName/effectiveFqn validation

Status: FAIL

Evidence: Normal validation in extension project returned `effectiveName`, `effectiveFqn`, `autoPrefixed=true`. But validation with `allow_auto_prefix=false` still returned `valid=true` and auto-prefixed name.

Commands/tools used: `edt_validate_request`

Notes: Prefix detection itself works; rejection gate for `allow_auto_prefix=false` is ignored.

### EDTEXT-04 CommonCommand module semantics

Status: PASS

Evidence: `ensure_module_artifact` for `CommonCommand.ар_аи_ОтправитьНаАнализИИ` in auto mode created `ДО.Артель/src/CommonCommands/ар_аи_ОтправитьНаАнализИИ/CommandModule.bsl`. `bsl_module_context` returned `moduleType=COMMAND_MODULE`.

Commands/tools used: `edt_validate_request`, `ensure_module_artifact`, `bsl_module_context`, `get_diagnostics`, `list_files`

Notes: No SU79/SU220 diagnostics observed for this object.

### EDTEXT-05 StandardCommandGroup aliases/candidates

Status: FAIL

Evidence: Candidates surface returned standard command groups. But creating a CommonCommand with `group="StandardCommandGroup.FormCommandBarImportant"` failed with `[METADATA_NOT_FOUND]`. Updating existing CommonCommand group failed with `[METADATA_PARENT_NOT_FOUND]`. Unknown group produced generic error without available-values hint.

Commands/tools used: `edt_field_type_candidates`, `edt_validate_request`, `create_metadata`, `update_metadata`

Notes: Existing diagnostics already show SU112 missing/invalid group on two CommonCommands in `ДО.Артель`.

### EDTEXT-06 extension role/config rights diagnostics

Status: FAIL

Evidence: `mutate_role_rights` requires validation token, but `edt_validate_request` has no `mutate_role_rights` operation in exposed schema. Using an `update_metadata` token was rejected with `[INVALID_VALIDATION_TOKEN]`.

Commands/tools used: `inspect_role_rights`, `edt_validate_request`, `mutate_role_rights`

Notes: Required structured extension diagnostic could not be reached because token flow is unavailable.

### EDTEXT-07 migrate_to_extension_native planner

Status: PASS

Evidence: `migrate_to_extension_native(mode=dry_run)` returned 22 ordered operations for InformationRegister, Catalog, HTTPService, CommonCommand, ScheduledJob, Bot, Role. Apply without reviewed dry-run/token was refused with `[KNOWLEDGE_REQUIRED]`.

Commands/tools used: `migrate_to_extension_native`

Notes: Plan includes prefixed target names and skips source deletion.

### EDTEXT-08 diagnostics/regression smoke

Status: PASS_WITH_WARNINGS

Evidence: Baseline extension diagnostics had 9 errors. Final extension diagnostics still had 9 errors; no new blocking diagnostics attributable to audit-created Constant or CommandModule.

Commands/tools used: `get_diagnostics`, `list_files`, `git_inspect`

Notes: Workspace already had unrelated diagnostics.

## Errors Found

### ERROR-1

- Severity: HIGH
- Area: TypeDescription mutation / CommonCommand
- Tool: `create_metadata`, `update_metadata`
- Reproduction steps:
  1. Call `edt_field_type_candidates` for `CommonCommand.ар_аи_ОтправитьНаАнализИИ`, field `commandParameterType`.
  2. Pick returned candidate `CatalogRef.ДокументыПредприятия`.
  3. Validate and call `update_metadata`.
- Expected: Candidate type is accepted and BM TypeDescription is updated.
- Actual: `[INVALID_PROPERTY_VALUE] Type not found for field 'commandParameterType': CatalogRef.ДокументыПредприятия`
- Raw error / diagnostic payload: `[INVALID_PROPERTY_VALUE] Type not found for field 'commandParameterType': CatalogRef.ДокументыПредприятия`
- Suspected cause: Mutation resolver does not resolve extension/base-project TypeClassifiers like candidates provider.
- Suggested fix: Reuse candidates/type-classifier resolver for TypeDescription mutation.
- Safe workaround, if any: Avoid automated CommonCommand parameter type mutation.

### ERROR-2

- Severity: MEDIUM
- Area: Extension effectiveName/effectiveFqn validation
- Tool: `edt_validate_request`
- Reproduction steps:
  1. Validate `create_metadata` in `ДО.Артель` with name without prefix and `allow_auto_prefix=false`.
- Expected: Request rejected.
- Actual: Request accepted and auto-prefixed.
- Raw error / diagnostic payload: `valid=true`, `autoPrefixed=true` despite `allow_auto_prefix=false`.
- Suspected cause: Validator ignores `allow_auto_prefix`.
- Suggested fix: Enforce flag before issuing token.
- Safe workaround, if any: Caller must manually compare requested/effective name.

### ERROR-3

- Severity: HIGH
- Area: StandardCommandGroup property mutation
- Tool: `create_metadata`, `update_metadata`
- Reproduction steps:
  1. Use `StandardCommandGroup.FormCommandBarImportant` from candidates.
  2. Create/update CommonCommand group.
  3. Try unknown group.
- Expected: Known alias accepted; unknown value fails with available-values hint.
- Actual: Known alias fails with `METADATA_NOT_FOUND` or `METADATA_PARENT_NOT_FOUND`; unknown value has no hint.
- Raw error / diagnostic payload: `[METADATA_NOT_FOUND] Referenced metadata object not found: StandardCommandGroup.FormCommandBarImportant`; `[METADATA_PARENT_NOT_FOUND] Parent FQN must be <Type>.<Name>[.<Marker>.<Name>...]`
- Suspected cause: Generic resolver treats StandardCommandGroup as metadata FQN/child FQN.
- Suggested fix: Add explicit enum/alias handling for CommonCommand.group.
- Safe workaround, if any: Set group via EDT UI until fixed.

### ERROR-4

- Severity: BLOCKER
- Area: Role/config rights validation flow
- Tool: `mutate_role_rights`, `edt_validate_request`
- Reproduction steps:
  1. Inspect role `Role.ар_ОсновнаяРоль`.
  2. Try to get validation token for `mutate_role_rights`.
  3. Call `mutate_role_rights` with `update_metadata` token.
- Expected: Supported validation operation exists and returns structured extension-rights diagnostic.
- Actual: No validation operation for `mutate_role_rights`; token rejected.
- Raw error / diagnostic payload: `[INVALID_VALIDATION_TOKEN] validation_token does not match current operation/project`
- Suspected cause: Validation schema and role-rights mutation tool are out of sync.
- Suggested fix: Add `mutate_role_rights` to validation operation set.
- Safe workaround, if any: Treat role rights mutation as unavailable in extension migration automation.

## Blockers / Untested Areas

- Full extension configuration-right diagnostic is untested because validation token flow for `mutate_role_rights` is unavailable.
- No apply-mode migration was executed; unsafe without explicit confirmation.
- Build/version of CodePilot1C extension was not visible.
- Workspace has pre-existing diagnostics in both projects.
- Safe smoke mutations were performed and not committed.

## Final Verdict

FAIL: blocking issues found, see Errors Found.
