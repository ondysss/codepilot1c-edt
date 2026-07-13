# Roadmap — CodePilot1C OSS

## Milestones

- ✅ **v0.1.9 — EDT Extension Native Migration Tooling** — Phases 1–5 (shipped 2026-07-13) — full detail in [milestones/v0.1.9-ROADMAP.md](milestones/v0.1.9-ROADMAP.md)
- 🚧 **v0.1.10 — Managed Form Event Handlers** — Phases 6–9 (in progress)

**Milestone Goal (v0.1.10):** The agent can safely wire managed-form event handlers — setting the event on the EMF form model AND generating the matching BSL handler stub, atomically — on both base-configuration and 1C-extension (расширения) forms, by extending the existing `mutate_form_model` tool under the `MUTATE_FORM_MODEL` validation-token flow.

## Phases

<details>
<summary>✅ v0.1.9 EDT Extension Native Migration Tooling (Phases 1–5) — SHIPPED 2026-07-13</summary>

- [x] Phase 1: Research EDT API Contracts and Lock Failure Reproductions (1/1 plan)
- [x] Phase 2: Low-Level EDT Tooling Fixes (1/1 plan)
- [x] Phase 3: Native Extension Migration Planner (1/1 plan)
- [x] Phase 4: Live EDT Audit Remediation (1/1 plan)
- [x] Phase 5: Release Install and Live EDT Closure Smoke (1/1 plan) — completed 2026-07-13 (live-verified in EDT by user)

Full phase details, outcomes, and verification: [milestones/v0.1.9-ROADMAP.md](milestones/v0.1.9-ROADMAP.md).
Requirements traceability: [milestones/v0.1.9-REQUIREMENTS.md](milestones/v0.1.9-REQUIREMENTS.md).

</details>

### 🚧 v0.1.10 Managed Form Event Handlers (In Progress)

- [ ] **Phase 6: Event-Handler API Spine, Operation Plumbing & Inspect Surfacing** - The 3 event-handler ops are accepted end-to-end through `mutate_form_model`, events resolve at runtime, and `inspect_form_layout` reports existing handlers (no stub yet).
- [ ] **Phase 7: BSL Handler Stub Generation (Base Configuration)** - Wiring an event atomically emits a correctly-directived, correctly-signed empty stub in `Module.bsl` for the full base-config event catalog (form/field/table).
- [ ] **Phase 8: Extension (Расширения) Form Support** - Handlers wire correctly on extension-adopted forms/items via `EventHandlerExtension` + explicit `call_type`, with unprefixed procedure names and correct adopted-form FQN resolution.
- [ ] **Phase 9: Qwen Priming, Regression Tests & Live-EDT Smoke Closure** - Qwen priming covers the new ops, the regression suite is green, the reactor build produces the p2 site, and the human-gated live-EDT smoke validates wiring + stubs on base and extension forms.

## Phase Details

### Phase 6: Event-Handler API Spine, Operation Plumbing & Inspect Surfacing
**Goal**: Establish the verified EMF event-handler spine and get the three operations accepted end-to-end through the existing validation-token flow, with existing handlers made observable — before any BSL stub work.
**Depends on**: Nothing (first phase of this milestone; continues after Phase 5)
**Requirements**: OPS-01, OPS-02, OPS-03, OPS-04, EVT-04, INSP-01
**Success Criteria** (what must be TRUE):
  1. The agent can call `add_event_handler`, `set_event_handler`, and `remove_event_handler` as operations of `mutate_form_model`, and each round-trips symmetrically through `MUTATE_FORM_MODEL` normalization on both validate and consume (no new `ValidationOperation`).
  2. Wiring an event on a `Form`, `FormField`, or `Table` sets the handler slot on the EMF model; the event and its concrete `mcore.Event` are resolved at runtime via `FormItemInformationService.getAllowedEvents(item)` and matched by name (RU/EN), with no hardcoded event spellings.
  3. Wiring an event on a button/group is rejected with an actionable redirect to `add_command`, and an unknown/invalid event returns the item's allowed events instead of a generic error.
  4. `inspect_form_layout` surfaces existing event handlers per node as `{event, handlerName}`, so the agent can read current state to drive idempotent set/remove and base-handler detection.
**Plans**: TBD
**UI hint**: yes

### Phase 7: BSL Handler Stub Generation (Base Configuration)
**Goal**: Deliver the milestone's core value for base configuration — wiring an event also generates the matching BSL handler procedure stub with the correct client/server directive and exact signature, atomically with the model slot.
**Depends on**: Phase 6
**Requirements**: EVT-01, EVT-02, EVT-03, STUB-01, STUB-02, STUB-03, STUB-04, STUB-05, STUB-06
**Success Criteria** (what must be TRUE):
  1. Wiring any event in the full base-config catalog — form-level (lifecycle, write family, server differentiators, `ОбработкаВыбора`/`ОбработкаНавигационнойСсылки`), field, and table (including the drag-and-drop family) — produces a matching handler procedure stub in `Module.bsl`, or wiring fails and neither the model slot nor the stub is written.
  2. Each generated stub carries the directive derived from the resolved `Event.getEnvironments()` (never from a name-suffix heuristic — e.g. `ПриАктивизацииСтроки` stays client), including the `&НаСервереБезКонтекста` special case for `ПриПолученииДанныхНаСервере`.
  3. Each generated stub reproduces the event's exact `ParamSet` signature verbatim (including out-parameters like `СтандартнаяОбработка`), with keywords/identifiers in the configuration's `ScriptVariant` (RU/EN) and edition-variable signatures pinned to the project's platform `Version`.
  4. Stub insertion is idempotent (re-wiring the same event produces no duplicate procedure and never overwrites a non-empty body), and the stub is written after force-export with export/derived-data timeout treated as non-fatal and artifact existence verified rather than trusting the sync return.
**Plans**: TBD
**UI hint**: yes

### Phase 8: Extension (Расширения) Form Support
**Goal**: Extend event-handler wiring to extension-adopted forms and items, adding the extension-specific EClass, call-type semantics, name-prefix awareness, and adopted-form FQN resolution on top of the solid base-config path.
**Depends on**: Phase 7
**Requirements**: EXT-01, EXT-02, EXT-03
**Success Criteria** (what must be TRUE):
  1. Wiring a handler on an extension-adopted form/item creates an `EventHandlerExtension` with an `ExtendedMethodCallType`, where the operation payload accepts an explicit `call_type` with a sensible default (no hard-coded guess baked into behavior the user cannot override).
  2. Generated handler procedure names for extension handlers are NOT prefixed with the extension name prefix (prefix applies to top-level metadata names only), and base-handler-exists detection works for adopted items.
  3. Wiring on an extension form targets the correct adopted object via correct adopted-form FQN resolution.
**Plans**: TBD
**UI hint**: yes

### Phase 9: Qwen Priming, Regression Tests & Live-EDT Smoke Closure
**Goal**: Freeze the operation vocabulary, prime the Qwen tool surface for the new ops, prove behavior with regression tests, and close the milestone on the human-gated live-EDT smoke for both base and extension forms.
**Depends on**: Phase 8
**Requirements**: QA-01, QA-02, QA-03
**Success Criteria** (what must be TRUE):
  1. Qwen tool-call priming for the new operations is added in `BackendToolSurfaceRewriteContributor` (the `mutate_form_model` case), with the tool description staying under 200 chars and the schema staying flat.
  2. The regression suite is green and covers op normalization, directive-from-`Environments`, RU/EN stub text, wire+stub atomicity/idempotency, base-vs-extension EClass selection, and button rejection.
  3. The full reactor build (`mvn -DskipTests package`) is green with the p2 update site produced, and the user (verifier of record) confirms the live-EDT smoke validates wiring + generated stub on base and extension forms.
**Plans**: TBD

## Progress

**Execution Order:**
Phases execute in numeric order: 6 → 7 → 8 → 9

| Phase | Milestone | Plans Complete | Status | Completed |
|-------|-----------|----------------|--------|-----------|
| 1. Research EDT API Contracts | v0.1.9 | 1/1 | Complete | 2026-07-13 |
| 2. Low-Level EDT Tooling Fixes | v0.1.9 | 1/1 | Complete | 2026-07-13 |
| 3. Native Extension Migration Planner | v0.1.9 | 1/1 | Complete | 2026-07-13 |
| 4. Live EDT Audit Remediation | v0.1.9 | 1/1 | Complete | 2026-07-13 |
| 5. Release Install and Live EDT Closure Smoke | v0.1.9 | 1/1 | Complete | 2026-07-13 |
| 6. Event-Handler API Spine, Operation Plumbing & Inspect Surfacing | v0.1.10 | 0/TBD | Not started | - |
| 7. BSL Handler Stub Generation (Base Config) | v0.1.10 | 0/TBD | Not started | - |
| 8. Extension (Расширения) Form Support | v0.1.10 | 0/TBD | Not started | - |
| 9. Qwen Priming, Regression Tests & Live-EDT Smoke Closure | v0.1.10 | 0/TBD | Not started | - |

_Per-phase timeline and outcomes for v0.1.9 are preserved in the milestone archive. v0.1.10 phases 6–9 are the active milestone._
