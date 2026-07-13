# Research Summary — v0.1.9 EDT Extension Native Migration Tooling

## Input

The 1C agent reported 10 tooling defects/limitations from attempting to move `аи_Артель` objects from base project `ДО` into extension `ДО.Артель`: Bot adopt lookup, constant type creation, invisible extension auto-prefixing, incomplete complex-object clone support, CommonCommand `CommandModule.bsl`, wrong BSL module context, `commandParameterType`, standard command groups, extension role config rights, and lack of high-level migration flow.

## Current code/API findings

1. Extension adoption currently looks up source objects through `EdtExtensionService.findSourceObject()` and fixed collection switches. `MetadataKind` already includes `BOT`, and `create_metadata` schema exposes `Bot`, but `EdtExtensionService.collectKnownTopLevelObjects()` / `findTopLevel()` omit `configuration.getBots()` while including many other top-level collections. This explains why `Bot.аи_МастерБотАртель` can exist elsewhere but `adopt` returns `METADATA_NOT_FOUND`.
   - Sources: `EdtExtensionService.java:86`, `EdtExtensionService.java:454`, `EdtExtensionService.java:563`, `MetadataKind.java:55`.

2. Type mutation exists only for `BasicFeature.type`. `update_metadata` collects type strings and `applyFieldValue()` special-cases `fieldName=type && target instanceof BasicFeature`; `setAttributeType()` builds a `TypeDescription`. Constants and CommonCommand `commandParameterType` are containment `TypeDescription`-like references, so generic `applyReferenceValue()` rejects them as containment updates.
   - Sources: `EdtMetadataService.java:3027`, `EdtMetadataService.java:6234`, `EdtMetadataService.java:6285`, `EdtMetadataService.java:7813`.

3. Extension name prefixing is applied in the `CreateMetadataTool` adapter after validation consumes the token. Validation normalizes the requested name, but the effective extension-prefixed name is not visible to the caller before mutation.
   - Sources: `CreateMetadataTool.java:168`, `CreateMetadataTool.java:188`, `CreateMetadataTool.java:191`.

4. `ensure_module_artifact` supports only `AUTO`, `OBJECT`, `MANAGER`, `MODULE`. Unknown `command` fails in `ModuleArtifactKind.fromString()`. `moduleFileName(AUTO, CommonCommand)` falls through to `ObjectModule.bsl`; `module_kind=module` creates `Module.bsl`. There is no path for `CommandModule.bsl`.
   - Sources: `ModuleArtifactKind.java:8`, `ModuleArtifactKind.java:24`, `EdtMetadataService.java:3926`, `EdtMetadataService.java:3983`.

5. `bsl_module_context` returns whatever EDT parser resource reports via `context.module().getModuleType()`. If the wrong artifact (`Module.bsl`/`ObjectModule.bsl`) is created under CommonCommands, EDT naturally reports common/object semantics, which leads to incorrect SU diagnostics for command directives.
   - Sources: `BslSemanticService.java:326`, `BslSemanticService.java:345`, `BslSemanticService.java:349`.

6. `edt_field_type_candidates` is TypeProvider-oriented. It expects an `EReference` field and calls `TypeProviderService.getTypeDescriptionInfoWithTypeInfo(...)`. A standard command group field is an enum/reference/value-list style placement field, not a TypeDescription candidate field, so returning empty is expected for the current implementation.
   - Sources: `EdtMetadataService.java:3084`, `EdtMetadataService.java:3108`, `EdtMetadataService.java:3116`.

7. `mutate_role_rights(set_config_right)` calls `IRightInfosService.getEClassRights(txConfiguration, Configuration.eClass())` and reports `METADATA_NOT_FOUND` when the requested right is absent. It does not distinguish extension projects or return the available right names.
   - Sources: `EdtRoleRightsService.java:190`, `EdtRoleRightsService.java:220`, `EdtRoleRightsService.java:222`.

8. Complex-object migration is not currently a single scenario. Existing primitives create top-level objects, create children, update simple fields, create forms, materialize modules, mutate forms, and mutate role rights. There is no orchestrator that introspects an existing source object, computes native extension effective names, copies children/modules/forms/rights, and rewrites references.
   - Sources: `EdtMetadataService.java:231`, `EdtMetadataService.java:706`, `EdtMetadataService.java:3009`, `EdtMetadataService.java:3229`, `EdtRoleRightsService.java:151`.

## Recommended implementation options

### Option A — Minimal patches plus explicit unsupported diagnostics
Fix only the exact reported low-level gaps: add Bot to extension lookup; add Constant and CommonCommand TypeDescription setters; add command module kind; add StandardCommandGroup alias; add available-right diagnostics. Defer high-level migration orchestration.

Pros: fast, lower risk, directly unblocks manual migration.
Cons: still forces agents to stitch many operations manually; future migrations will rediscover ordering/reference pitfalls.

### Option B — Shared metadata mutation primitives plus migration planner (recommended)
First build generic, tested primitives for `TypeDescription`, module artifact kinds, effective extension naming, enum/reference aliases, and diagnostics. Then add a dry-run-first migration planner that composes these primitives but keeps apply mode conservative.

Pros: fixes root causes and creates a reusable migration workflow; high-level tool can expose unsupported/skipped operations safely.
Cons: requires careful phase split and strong tests to avoid overbroad EDT model mutation changes.

### Option C — XML/file copier for `.mdo` and `.bsl`
Copy source folders and patch XML/file names directly.

Pros: superficially quick for one workspace.
Cons: violates project rules, risks UUID/export corruption, bypasses BM validation, and should remain emergency-only.

Recommendation: Option B.

## Planning guidance

Phase 1 should be mostly RED tests/research seams. Phase 2 should change existing primitives with small blast radius. Phase 3 should introduce the high-level `migrate_to_extension_native` planner only after the low-level operations are reliable.

## Risks / open questions

1. EDT `Configuration` API for Bots may differ by platform version; if `getBots()` is unavailable in the compile target, Bot support may need reflective collection discovery plus explicit unsupported diagnostics.
2. CommonCommand module type may require creating the correct file path plus EDT/Xtext resource service recognition; file naming alone may not be sufficient in headless tests.
3. `StandardCommandGroup` may be modeled as enum literal, platform object, or string serialization depending on field; implementation should inspect `EStructuralFeature` type rather than hardcoding only one setter.
4. Extension project config rights may genuinely omit `ThinClient`; if so the product must report `UNSUPPORTED_IN_EXTENSION` with available rights, not fake success.
5. Full reference rewriting is broad; initial migration planner should emit a clear dry-run diff and support only proven reference categories in apply mode.

## Sources consulted

- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/extension/EdtExtensionService.java`
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java`
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/MetadataKind.java`
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/MetadataChildKind.java`
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/ModuleArtifactKind.java`
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/rights/EdtRoleRightsService.java`
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/lang/BslSemanticService.java`
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/metadata/CreateMetadataTool.java`
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/metadata/EnsureModuleArtifactTool.java`
- `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/metadata/EdtFieldTypeCandidatesTool.java`
