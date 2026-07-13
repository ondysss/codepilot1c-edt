# CodePilot1C OSS — Project Planning

## What This Is

Eclipse RCP/OSGi plugin suite for 1C:EDT. The active product area is the desktop chat UI, provider/tool execution loop, EDT integrations, metadata mutation tools, extension tooling, and profile-driven agent workflows.

## Current State

**Shipped: v0.1.9 — EDT Extension Native Migration Tooling (2026-07-13).**

- Low-level EDT metadata mutation primitives fixed: TypeDescription fields (incl. `commandParameterType` and `EventSubscription.source`), CommonCommand command modules, StandardCommandGroup aliases, Bot adoption diagnostics, extension role-right diagnostics.
- Validation surface exposes effective extension-prefixed names/FQNs and explicit unsupported/available-kind responses before mutation.
- High-level dry-run-first `migrate_to_extension_native` planner/tool over the fixed primitives (gated apply, no unsafe source deletion).
- Regression tests across the reported defect classes; live-EDT smoke closure gate.
- **Closure basis:** the live-EDT smoke (install into EDT + audit prompt on `/Volumes/T9/workspace/do`, `ДО` / `ДО.Артель`) was performed and verified by the user on 2026-07-13; the live EDT/BM model is not CLI-reachable, so closure rests on that manual verification, not an automated report. The prior 2026-07-06 audit reports (FAIL) predate the remediation and are retained for history.
- Codebase: OSGi bundles `com.codepilot1c.core` (agent loop, tools, providers, MCP, EDT integrations) and `com.codepilot1c.ui` (workbench/chat UI); tests in `*.tests` bundles; Tycho reactor build (`mvn -DskipTests package`).

## Core Value

Predictable, typed, diagnosable, and safe migration of existing 1C:EDT metadata objects into extension-native objects — driven by EDT-native tooling, never by manual `.mdo` XML patching.

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

### Active (next milestone — carried from v0.1.9 Future Requirements)

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

## Next Milestone Goals

Not yet scoped. Run `/gsd-new-milestone` to define the next milestone from the Active requirements above (questioning → research → requirements → roadmap). Phase numbering continues from Phase 6.

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

_Last updated: 2026-07-13 after v0.1.9 milestone_
