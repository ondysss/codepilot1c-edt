---
gsd_state_version: 1.0
milestone: v0.1.10
milestone_name: Managed Form Event Handlers
current_phase: 1.10
status: Awaiting next milestone
stopped_at: "Phase 09 Plan 02 Task 3 (checkpoint:human-verify, gate=blocking) -- awaiting user's live-EDT smoke confirmation"
last_updated: "2026-07-14T06:35:35.258Z"
last_activity: 2026-07-14
last_activity_desc: Milestone v0.1.10 completed and archived
progress:
  total_phases: 4
  completed_phases: 4
  total_plans: 10
  completed_plans: 10
  percent: 100
current_phase_name: qwen-priming-regression-tests-live-edt-smoke-closure
---

# GSD State — v0.1.10 Managed Form Event Handlers

Roadmap created (Phases 6–9). v0.1.9 shipped and archived on 2026-07-13; numbering continues from Phase 6. The agent will wire managed-form event handlers — setting the event on the EMF form model AND generating the matching BSL handler stub, atomically — for base config and 1C extensions, by extending the existing `mutate_form_model` tool under the `MUTATE_FORM_MODEL` validation-token flow.

## Project Reference

See: `.planning/PROJECT.md` (updated 2026-07-13)

**Core value (milestone):** The agent can safely wire managed-form event handlers — setting the event on the EMF form model AND generating the matching BSL handler stub, atomically — on both base-configuration and 1C-extension forms, via EDT-native tooling (not `.form`/`.mdo`/`Module.bsl` XML patching).
**Current focus:** Phase 09 — qwen-priming-regression-tests-live-edt-smoke-closure

## Current Position

Phase: Milestone v0.1.10 complete
Plan: —
Status: Awaiting next milestone
Last activity: 2026-07-14 — Milestone v0.1.10 completed and archived

## Performance Metrics

**Velocity:**

- Total plans completed: 3
- Average duration: —
- Total execution time: —

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| 06 | 3 | - | - |

**Recent Trend:**

- Last 5 plans: —
- Trend: —

*Updated after each plan completion*
| Phase 06 P01 | 55min | 3 tasks | 9 files |
| Phase 06 P02 | 40min | 2 tasks | 3 files |
| Phase 06 P03 | 35min | 2 tasks | 2 files |
| Phase 07 P01 | 30min | 2 tasks | 3 files |
| Phase 07 P02 | 20min | 2 tasks | 3 files |
| Phase 07 P03 | 25min | 2 tasks | 2 files |
| Phase 08 P01 | 25min | 2 tasks | 2 files |
| Phase 08-extension-form-support P02 | 45min | 2 tasks | 2 files |
| Phase 09 P01 | 12min | 2 tasks | 1 files |
| Phase 09 P02 | 15min | 2/3 tasks (Task 3 pending) | 1 files |

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table. Recent decisions affecting current work:

- Extend the existing `mutate_form_model` tool with add/set/remove event-handler ops (reuse `MUTATE_FORM_MODEL` token) — no new tool, no new `ValidationOperation`.
- `core` stays independent of EDT UI packages — EDT UI generators (`ProcedureDirective`/`GotoEventHandlerHandler`) are barred; the BSL stub is hand-rolled deterministic text (the stub-body exception, not metadata editing).
- BM-commit and FS-export are separate phases — write the stub after force-export, treat export/derived-data timeout as non-fatal, and verify artifact existence rather than trusting the sync return.
- Handlers live only on `Form`/`FormField`/`Table` (implement `EventHandlerContainer`); button/group wiring is rejected with a redirect to `add_command`.
- Live-EDT verification is a human gate (BM model not CLI-reachable); the user is verifier of record for the Phase 9 closure smoke.
- [Phase ?]: EventHandlerTargetResolver uses FormVisualEntity (not FormItem) as the guard parameter type — verified via javap that Form does not implement FormItem in the actual EDT platform model
- [Phase ?]: MANIFEST Import-Package for com._1c.g5.v8.dt.form.service landed in Task 1 (not Task 2) since FormItemInformationEventCatalog cannot compile without it
- [Phase ?]: resolveEventHandlerFormItem returns FormVisualEntity (not FormItem): verified via javap that Form implements FormVisualEntity but not FormItem, matching EventHandlerTargetResolver's 06-01 signature exactly
- [Phase ?]: add_event_handler/set_event_handler route through one upsert-by-(target,event) wireEventHandler implementation; EventHandlerTargetResolver's catalog constructor widened to public for cross-package test injection
- [Phase ?]: eventHandlers surfacing in inspect_form_layout requires includeInvisible=true in test fixtures — EMF unset visible EBoolean defaults to false, not null
- [Phase ?]: collectEventHandlerInfos mirrors 06-02's wireEventHandler as the read-path counterpart, both keyed off EventHandlerContainer.getHandlers()
- [Phase ?]: Directive derivation gates on Event.isServerCallWithContextNotAllowed() first, then Environments.containsAny(SERVER/ALL_CLIENTS) with an Environments.ALL null-fallback -- no name-suffix heuristic (STUB-02/04)
- [Phase ?]: Widest ParamSet selected by largest getMaxParams(), never Version-keyed -- corrects the original CONTEXT.md assumption per RESEARCH
- [Phase ?]: BslKeywords kept package-private as an internal literal-map detail of BslHandlerStubGenerator, not a public API
- [Phase 07]: ModuleFileWriter injectable seam (interface + IFile-backed default impl + test-only Map-backed fakes) makes the write-failure/rollback-signal path unit-testable headlessly -- Wave-0's biggest design decision
- [Phase 07]: Existing-procedure detection collapses non-empty-body/different-signature/exact-match into a single SKIPPED_EXISTING_WARN outcome, per Pitfall 3 leave-untouched-only-warn guidance
- [Phase ?]: PendingStub stores the resolved event's EN name (String), not the live Event EMF object, so the entire post-commit tail re-resolves fresh (stronger than Pitfall 2's minimum bar)
- [Phase ?]: 2-arg applyFormModelOperations(Form, List) preserved as a delegate to the new 3-arg overload so EventHandlerWiringTest's reflection lookup resolves unchanged
- [Phase 08]: ExtendedMethodCallType.getName() duplicates getLiteral() on the compiled-against platform version; used inherited Enum.name() for the Java-constant-name half of the call_type dual match instead
- [Phase 08]: wireEventHandler returns a new WiredEventHandler carrier record (handler, callType, adopted, baseHandlerExists) instead of bare EventHandler, so the summary can echo call_type/base_handler_exists for adopted targets
- [Phase 08]: baseHandlerExists degrades to false when IModelObjectAdopter is unavailable (EDT_SERVICE_UNAVAILABLE), since it is observability-only and must not fail the primary EClass/call_type wiring
- [Phase ?]: Followed CONTEXT.md DECISION OVERRIDE: no Qwen-family gating APIs exist; priming is a single flat, provider-neutral addition (no XML/JSON-by-model-family branching).
- [Phase ?]: generate_stub excluded from priming (confirmed zero matches in core src; stub generation is unconditional/always-on).
- [Phase ?]: No new test authored for QA-02 - EventHandlerPayloadSymmetryTest (b9b0272) already closes the op-normalization round-trip gap.
- [Phase ?]: [Phase 09]: Full reactor build BUILD SUCCESS (qualifier 0.1.7.20260714-0435), p2 site at repositories/com.codepilot1c.update/target/repository ready to install.
- [Phase ?]: [Phase 09]: 09-SMOKE-CHECKLIST.md authored covering base-config + extension-adopted forms; Task 2's plan verifier conflicted with its own action text on the 1C:Naparnik string -- resolved by rewording to avoid the literal forbidden substring while preserving intent.

### Pending Todos

None yet.

### Blockers/Concerns

- **Phase 8 (research flag):** extension default `ExtendedMethodCallType` and whether a base event must pre-exist before adoption are UNVERIFIED live — the payload must accept an explicit `call_type`; verify defaults in EDT during Phase 8 (highest residual risk).
- **Phase 7 (research flag):** per-item event literal spellings (EN/RU) are UNVERIFIED — resolve at runtime via `getAllowedEvents(item)`; `getAllowedEventNames(EClass, Version)` `Version` provider must be identified; edition-variable signatures pinned to project platform `Version`.
- **Codebase correction:** Qwen priming target is the `mutate_form_model` case in `BackendToolSurfaceRewriteContributor` — NOT `QwenToolCallExamples.inferExampleParams()` (does not exist). MANIFEST needs `com._1c.g5.v8.dt.form.service` Import-Package for `FormItemInformationService`.
- Phase 09 Plan 02 Task 3 (checkpoint:human-verify, gate=blocking): full reactor build is BUILD SUCCESS (qualifier 0.1.7.20260714-0435) and 09-SMOKE-CHECKLIST.md is authored -- milestone closure (v0.1.10) awaits the user's live-EDT smoke pass/fail confirmation on both base-config and extension-adopted forms.

## Deferred Items

Items acknowledged and carried forward from previous milestone close:

| Category | Item | Status | Deferred At |
|----------|------|--------|-------------|
| Migration | Full deletion/move workflow (base removal after verified extension-native replacement) | Backlog (MIG-01) | v0.1.9 close |
| Migration | Complete semantic reference rewriting in arbitrary BSL modules | Backlog (MIG-02) | v0.1.9 close |
| UI | Visual migration wizard UI | Backlog (MIG-03) | v0.1.9 close |

## Session Continuity

Last session: 2026-07-14T04:39:05.426Z
Stopped at: Phase 09 Plan 02 Task 3 (checkpoint:human-verify, gate=blocking) -- awaiting user's live-EDT smoke confirmation
Resume file: .planning/phases/09-qwen-priming-regression-tests-live-edt-smoke-closure/09-SMOKE-CHECKLIST.md

## Operator Next Steps

- Start the next milestone with /gsd-new-milestone
