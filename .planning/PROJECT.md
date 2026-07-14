# CodePilot1C OSS — Project Planning

## What This Is

Eclipse RCP/OSGi plugin suite for 1C:EDT. The active product area is the desktop chat UI, provider/tool execution loop, EDT integrations, metadata mutation tools, extension tooling, and profile-driven agent workflows.

## Current State

**Shipped: v0.1.10 — Managed Form Event Handlers (2026-07-14).**

- The agent safely wires managed-form event handlers via the existing `mutate_form_model` tool (reusing the `MUTATE_FORM_MODEL` validation-token flow): `add_event_handler` / `set_event_handler` / `remove_event_handler` set the EMF handler slot AND atomically generate the matching BSL handler procedure stub in `Module.bsl`.
- Full base-config event coverage (form/field/table incl. differentiators + drag-and-drop). Stub directive derived from `Event.isServerCallWithContextNotAllowed()`/`Environments` (never a name-suffix heuristic), verbatim widest-`ParamSet` signature in the configuration's RU/EN `ScriptVariant`, and true both-or-neither atomicity via a compensating `executeWrite` rollback after force-export.
- Extension (расширения) support: adopted forms get `EventHandlerExtension` with an explicit-or-defaulted (`BEFORE`) `call_type`, name-matched `base_handler_exists`, and unprefixed handler procedure names.
- Provider-neutral Qwen priming for the new ops in `BackendToolSurfaceRewriteContributor`; 9-class regression matrix green; full reactor build + p2 site (`0.1.7.20260714-0435`).
- **Closure basis:** the human-gated live-EDT smoke (base + extension forms) was performed and verified by the user on 2026-07-14. The live EDT/BM model is not CLI-reachable, so closure rests on that manual verification.
- Codebase: OSGi bundles `com.codepilot1c.core` (agent loop, tools, providers, MCP, EDT integrations) and `com.codepilot1c.ui` (workbench/chat UI); tests in `*.tests` bundles; Tycho reactor build (`mvn -DskipTests package`).

<details>
<summary>Prior: v0.1.9 — EDT Extension Native Migration Tooling (shipped 2026-07-13)</summary>

- Low-level EDT metadata mutation primitives fixed (TypeDescription fields incl. `commandParameterType`/`EventSubscription.source`, CommonCommand modules, StandardCommandGroup aliases, Bot adoption + role-right diagnostics); validation surface exposes effective extension names/FQNs; dry-run-first `migrate_to_extension_native` planner; regression tests + live-EDT smoke closure (user-verified 2026-07-13).

</details>

## Core Value

Predictable, typed, diagnosable, and safe EDT-native mutation of 1C:EDT metadata — extension-native migration AND managed-form authoring (layout + event-handler wiring with matching BSL stubs) — driven by BM/EDT APIs and the validation-token flow, never by manual `.mdo`/`.form`/`Module.bsl` text patching.

## Requirements

### Validated

- ✓ EDTEXT-01 — Extension adoption diagnostics and Bot support boundary — v0.1.9
- ✓ EDTEXT-02 — Generic TypeDescription mutation primitive — v0.1.9
- ✓ EDTEXT-03 — Validation exposes extension effective names/FQNs — v0.1.9
- ✓ EDTEXT-04 — CommonCommand module artifact semantics — v0.1.9
- ✓ EDTEXT-05 — Standard command group aliases and candidates — v0.1.9
- ✓ EDTEXT-06 — Extension role/config rights diagnostics — v0.1.9
- ✓ EDTEXT-07 — Clone/migration plan for complex metadata objects — v0.1.9
- ✓ EDTEXT-08 — Regression tests and EDT smoke proof — v0.1.9
- ✓ Wire form-level event handlers (OnCreateAtServer, OnOpen, BeforeClose, …) on managed forms — v0.1.10
- ✓ Wire item-level event handlers (field OnChange/StartChoice, table OnActivateRow + drag-and-drop, …) — v0.1.10
- ✓ Wiring generates the BSL handler procedure stub with correct client/server directive + verbatim signature, atomically — v0.1.10
- ✓ Event-handler operations added to `mutate_form_model` under the existing validation-token flow (no new tool/ValidationOperation) — v0.1.10
- ✓ Event handlers work for forms inside 1C extensions (расширения), not only base configuration — v0.1.10
- ✓ `inspect_form_layout` surfaces existing event handlers — v0.1.10

### Active

_No active milestone — run `/gsd-new-milestone` to define the next milestone's requirements._

### Deferred (backlog — carried from v0.1.9 Future Requirements)

- [ ] Full deletion/move workflow that removes base objects only after verified extension-native replacements and explicit user confirmation.
- [ ] Complete semantic reference rewriting inside arbitrary BSL modules (v0.1.9 started with metadata references and explicit copy lists).
- [ ] Visual migration wizard UI (CLI/tool API shipped first).

### Out of Scope

- Manual `.mdo` XML patching as the main migration mechanism.
- Changing EDT platform behavior or suppressing valid SU diagnostics instead of creating the correct module type/path.

## Key Decisions

- ✓ Keep EDT runtime access behind `EdtMetadataService`, `EdtExtensionService`, `EdtRoleRightsService`, `BslSemanticService`, and gateway/service layers; tools remain thin validation-token adapters. (Held through v0.1.9.)
- ✓ Do not primary-edit `.mdo` XML to fix metadata — mutation support uses BM/EDT APIs and preserves export/synchronization post-checks.
- ✓ Add general `TypeDescription` support once and reuse it (constants, command parameters, `EventSubscription.source`, migration clone paths).
- ✓ Extension-native migration is dry-run-first; destructive deletes stay out of scope until a confirmed, verified clone exists.
- ✓ Tool errors distinguish unsupported kind/API limitation from missing object, and list actionable alternatives/available values where possible.
- — Live-EDT verification is a human gate (not CLI-automatable); the user is verifier of record for closure smokes. (Pending: revisit if a headless EDT harness becomes available.)
- ✓ Event-handler wiring extends `mutate_form_model` (reuses `MUTATE_FORM_MODEL`); no new tool, no new `ValidationOperation`. (v0.1.10)
- ✓ BSL stub atomicity ("both or neither") uses a compensating second `executeWrite` (fresh EMF re-resolution) after force-export, since BM commits are not retroactively undoable. (v0.1.10, STUB-01)
- ✓ Stub directive derives from `Event.isServerCallWithContextNotAllowed()`/`Environments`, never a name-suffix heuristic; the `ParamSet` is arity-widest (overload variants), NOT `Version`-keyed — `getAllowedEvents` already resolves the version-correct `Event`. (v0.1.10, research-corrected)
- ✓ Extension wiring auto-detects adoption via `getMdForm().getObjectBelonging()==ADOPTED` (uniform Form/Field/Table signal, not the per-item `ExtensionAdoptedProperty`); `call_type` defaults to bytecode-verified `BEFORE`; no `generateExternalPropertyFqn`/`attachTopObject` needed for already-adopted forms. (v0.1.10, EXT-03 override, user-approved)
- ⚠️ Qwen priming is provider-neutral in the code — the CLAUDE.md Qwen-native branch (`isQwenNative`/`getResolvedModelFamily`/`QwenFunctionCallingTransport`) was generalized into `OpenAiCompatibilityProfileResolver` and no longer exists. **CLAUDE.md's Qwen Optimization Rules are stale and should be revisited/rewritten.** (v0.1.10, QA-01 override, user-approved)

## Current Milestone

_None active. v0.1.10 shipped 2026-07-14 (Phases 6–9). Run `/gsd-new-milestone` to define the next._

**Carried backlog (candidates for the next milestone):**
- Deferred migration tooling: full deletion/move workflow (base removal only after verified extension-native replacement + confirmation), complete BSL semantic reference rewriting, visual migration wizard UI.
- v0.1.10 tech debt (from `milestones/v0.1.10-MILESTONE-AUDIT.md`): add a log line on `baseHandlerExists`' `EDT_SERVICE_UNAVAILABLE` degrade; surface `call_type`/adoption in `inspect_form_layout`'s `EventHandlerInfo`.
- Doc debt: rewrite CLAUDE.md's stale Qwen Optimization Rules to match the generalized provider-neutral `OpenAiCompatibilityProfileResolver` architecture.

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition:**
1. Requirements invalidated? Move to Out of Scope with reason.
2. Requirements validated? Move to Validated with milestone reference.
3. New requirements emerged? Add to Active.
4. Decisions to log? Add to Key Decisions.
5. "What This Is" / "Current State" still accurate? Update if drifted.

**After each milestone:**
1. Full review of all sections.
2. Core value check.
3. Audit Out of Scope.
4. Update Current State with shipped version.

_Last updated: 2026-07-14 after v0.1.10 (Managed Form Event Handlers) milestone completion_
