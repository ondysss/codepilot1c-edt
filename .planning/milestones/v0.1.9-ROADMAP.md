# Roadmap — v0.1.9 EDT Extension Native Migration Tooling

## Phases

- [x] **Phase 1: Research EDT API Contracts and Lock Failure Reproductions** - Convert the 10 1C-agent findings into service-level EDT contracts and regression tests.
- [x] **Phase 2: Low-Level EDT Tooling Fixes** - Fix EDT-native mutation primitives that block extension migration.
- [x] **Phase 3: Native Extension Migration Planner** - Add a dry-run-first high-level migration planner/tool over the fixed primitives.
- [x] **Phase 4: Live EDT Audit Remediation** - Fix blocking defects found by live EDT audit after installing the extension.
- [x] **Phase 5: Release Install and Live EDT Closure Smoke** - Rebuild, install into EDT, and rerun live audit to close the milestone with runtime proof. (completed 2026-07-13)

## Phase 1 — Research EDT API Contracts and Lock Failure Reproductions

### Status

completed

### Intent

Create a reliable contract baseline from the external 1C-agent audit so implementation work targets EDT-native semantics instead of prompt-level workarounds.

### Outcomes

- Captured service contracts and regression expectations for:
  - extension object adopt/lookup;
  - generic `TypeDescription` mutation;
  - effective extension names/FQNs;
  - CommonCommand module semantics;
  - StandardCommandGroup aliases and candidates;
  - extension role/config rights diagnostics;
  - high-level dry-run-first migration planning.
- Added/updated focused tests around metadata validation, type contracts, module artifact routing, role rights diagnostics, and extension planner behavior.

## Phase 2 — Low-Level EDT Tooling Fixes

### Status

completed

### Intent

Fix the primitives that actual EDT extension migration depends on.

### Outcomes

- `EdtMetadataService` handles TypeDescription-style property updates beyond the original narrow path.
- `BslSemanticService` recognizes CommonCommand command modules from path/module context.
- `EdtRoleRightsService` reports structured diagnostics instead of opaque right lookup failures.
- Metadata validation normalizes extension names/FQNs and StandardCommandGroup input where applicable.

## Phase 3 — Native Extension Migration Planner

### Status

completed

### Intent

Expose a high-level `migrate_to_extension_native` workflow that plans native EDT extension migration without unsafe direct source deletion.

### Outcomes

- Added `ExtensionMigrationPlanRequest` / `ExtensionMigrationPlanResult`.
- Added `ExtensionMigrationPlanner`.
- Added `MigrateToExtensionNativeTool`.
- Registered the new tool in the tool registry and profile/tool-surface gates.
- Added focused planner tests.
- Verified targeted tests and full local update-site build.

## Phase 4 — Live EDT Audit Remediation

### Status

completed

### Intent

Turn live EDT audit failures into concrete runtime/code fixes before closure.

### Inputs

- `.planning/audits/edt-extension-migration-live-audit-2026-07-06.md`
- `.planning/audits/edt-extension-migration-live-audit-bot-2026-07-06.md`

### Fixed issues

- `EDTEXT-02`: `CommonCommand.commandParameterType` / TypeDescription values from candidate output were not pre-resolved in mutation paths.
- `EDTEXT-03`: `allow_auto_prefix=false` is covered by regression test and rejects unprefixed extension names.
- `EDTEXT-05`: `StandardCommandGroup.*` values are applied as standard command groups instead of metadata FQNs, with available-values diagnostics.
- `EDTEXT-06`: `edt_validate_request` schema now exposes `mutate_role_rights`; token routing test verifies `ValidationOperation.MUTATE_ROLE_RIGHTS`.

### Verification

- Focused Phase 4 regression: `BUILD SUCCESS`, 12 tests.
- Broader focused EDT metadata/extension suite: `BUILD SUCCESS`, 35 tests.
- Full reactor update-site package: `mvn -DskipTests package` → `BUILD SUCCESS`.
- Produced update-site qualifier: `0.1.7.20260706-0415`.

## Phase 5 — Release Install and Live EDT Closure Smoke

### Status

complete (2026-07-13 — live-verified in EDT by user; closure authorized)

### Intent

Close the milestone only after runtime proof from the installed EDT plugin.

### Required closure gates

1. Install/update EDT from:
   - `repositories/com.codepilot1c.update/target/repository`
   - or `repositories/com.codepilot1c.update/target/com.codepilot1c.update-0.1.7-SNAPSHOT.zip`
2. Restart EDT and verify installed CodePilot1C plugin qualifier is `0.1.7.20260706-0415` or newer.
3. Rerun live audit in `/Volumes/T9/repo_edt/artel` for `ДО` / `ДО.Артель`.
4. Confirm no recurrence of the Phase 4 blockers.
5. Classify remaining diagnostics as pre-existing/unrelated or fix them before closing.

## Current Verification Snapshot

- Targeted Phase 4 regression: passed.
- Broader focused EDT metadata/extension suite: passed.
- Full reactor update-site build: passed.
- Live EDT closure smoke: passed (2026-07-13 — user live-verification in `/Volumes/T9/workspace/do`; v0.1.9 closed).
