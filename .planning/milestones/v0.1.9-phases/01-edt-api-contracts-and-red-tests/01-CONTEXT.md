# Phase 1 Context — Research EDT API Contracts and Lock Failure Reproductions

## Goal

Convert the 1C-agent migration findings into precise service contracts and RED tests before implementing fixes.

## User-provided findings to preserve

- `extension_manage(adopt)` cannot find existing `Bot.аи_МастерБотАртель` and should either support Bot or return explicit unsupported kind.
- `create_metadata(Constant, properties.type=Boolean)` fails because `type` is a containment reference and there is no TypeDescription tool path.
- Extension-native create auto-prefixes names (`аи_...` → `ар_аи_...`) without validation exposing the effective name/FQN.
- Top-level creates do not clone complex objects: registers, catalogs, HTTP services, CommonCommands, ScheduledJobs.
- CommonCommand needs `CommandModule.bsl`; current module artifact tooling creates `Module.bsl` / `ObjectModule.bsl` and BSL context becomes `COMMON_MODULE`.
- `update_metadata` cannot set `commandParameterType`.
- Standard command group full names are rejected while short serialized names work.
- `mutate_role_rights` cannot set `ThinClient` config right in extension and reports missing right instead of extension-specific unsupported/available-rights diagnostics.
- A high-level `migrate_to_extension_native(...)` dry-run/apply flow is needed.

## Research conclusions

See `.planning/research/SUMMARY.md`.

## Scope decisions

- RED tests first; avoid direct `.mdo` edits.
- Prefer service-level helpers/fakes over live EDT workspaces for unit coverage.
- Use live EDT smoke later only for behavior that depends on platform resource/module recognition.
