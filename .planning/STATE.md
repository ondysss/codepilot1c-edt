---
gsd_state_version: 1.0
milestone: v0.1.10
milestone_name: Managed Form Event Handlers
status: planning
last_updated: "2026-07-13T00:00:00.000Z"
last_activity: 2026-07-13
progress:
  total_phases: 4
  completed_phases: 0
  total_plans: 0
  completed_plans: 0
  percent: 0
---

# GSD State — v0.1.10 Managed Form Event Handlers

Roadmap created (Phases 6–9). v0.1.9 shipped and archived on 2026-07-13; numbering continues from Phase 6. The agent will wire managed-form event handlers — setting the event on the EMF form model AND generating the matching BSL handler stub, atomically — for base config and 1C extensions, by extending the existing `mutate_form_model` tool under the `MUTATE_FORM_MODEL` validation-token flow.

## Project Reference

See: `.planning/PROJECT.md` (updated 2026-07-13)

**Core value (milestone):** The agent can safely wire managed-form event handlers — setting the event on the EMF form model AND generating the matching BSL handler stub, atomically — on both base-configuration and 1C-extension forms, via EDT-native tooling (not `.form`/`.mdo`/`Module.bsl` XML patching).
**Current focus:** Phase 6 — Event-Handler API Spine, Operation Plumbing & Inspect Surfacing.

## Current Position

Phase: 6 of 9 (Event-Handler API Spine, Operation Plumbing & Inspect Surfacing)
Plan: — of — (not yet planned)
Status: Ready to plan
Last activity: 2026-07-13 — Roadmap created; 21/21 v1 requirements mapped to Phases 6–9

Progress: [░░░░░░░░░░] 0%

## Performance Metrics

**Velocity:**
- Total plans completed: 0
- Average duration: —
- Total execution time: —

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| - | - | - | - |

**Recent Trend:**
- Last 5 plans: —
- Trend: —

*Updated after each plan completion*

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table. Recent decisions affecting current work:

- Extend the existing `mutate_form_model` tool with add/set/remove event-handler ops (reuse `MUTATE_FORM_MODEL` token) — no new tool, no new `ValidationOperation`.
- `core` stays independent of EDT UI packages — EDT UI generators (`ProcedureDirective`/`GotoEventHandlerHandler`) are barred; the BSL stub is hand-rolled deterministic text (the stub-body exception, not metadata editing).
- BM-commit and FS-export are separate phases — write the stub after force-export, treat export/derived-data timeout as non-fatal, and verify artifact existence rather than trusting the sync return.
- Handlers live only on `Form`/`FormField`/`Table` (implement `EventHandlerContainer`); button/group wiring is rejected with a redirect to `add_command`.
- Live-EDT verification is a human gate (BM model not CLI-reachable); the user is verifier of record for the Phase 9 closure smoke.

### Pending Todos

None yet.

### Blockers/Concerns

- **Phase 8 (research flag):** extension default `ExtendedMethodCallType` and whether a base event must pre-exist before adoption are UNVERIFIED live — the payload must accept an explicit `call_type`; verify defaults in EDT during Phase 8 (highest residual risk).
- **Phase 7 (research flag):** per-item event literal spellings (EN/RU) are UNVERIFIED — resolve at runtime via `getAllowedEvents(item)`; `getAllowedEventNames(EClass, Version)` `Version` provider must be identified; edition-variable signatures pinned to project platform `Version`.
- **Codebase correction:** Qwen priming target is the `mutate_form_model` case in `BackendToolSurfaceRewriteContributor` — NOT `QwenToolCallExamples.inferExampleParams()` (does not exist). MANIFEST needs `com._1c.g5.v8.dt.form.service` Import-Package for `FormItemInformationService`.

## Deferred Items

Items acknowledged and carried forward from previous milestone close:

| Category | Item | Status | Deferred At |
|----------|------|--------|-------------|
| Migration | Full deletion/move workflow (base removal after verified extension-native replacement) | Backlog (MIG-01) | v0.1.9 close |
| Migration | Complete semantic reference rewriting in arbitrary BSL modules | Backlog (MIG-02) | v0.1.9 close |
| UI | Visual migration wizard UI | Backlog (MIG-03) | v0.1.9 close |

## Session Continuity

Last session: 2026-07-13
Stopped at: Roadmap for v0.1.10 created (ROADMAP.md, REQUIREMENTS.md traceability, STATE.md); 21/21 requirements mapped to Phases 6–9.
Resume file: None — next step is `/gsd-plan-phase 6`.
