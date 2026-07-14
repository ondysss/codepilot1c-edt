# Requirements: CodePilot1C OSS — v0.1.10 Managed Form Event Handlers

**Defined:** 2026-07-13
**Core Value (this milestone):** The agent can safely wire managed-form event handlers — setting the event on the EMF form model AND generating the matching BSL handler stub, atomically — on both base-configuration and 1C-extension forms.

Scope locked with the user: full event coverage (form-level + field + table, including differentiators and edge cases), wire-and-generate-stub, extend the existing `mutate_form_model` tool (reuse `MUTATE_FORM_MODEL` token), base config + extensions in this milestone.

## v1 Requirements

Requirements for v0.1.10. Each maps to a roadmap phase (numbering continues from Phase 6).

### Tool Operations

- [x] **OPS-01**: Agent can add an event handler to a Form/FormField/Table via a new `add_event_handler` operation of `mutate_form_model`, under the existing `MUTATE_FORM_MODEL` validation-token flow.
- [x] **OPS-02**: Agent can upsert an existing handler via `set_event_handler` (idempotent re-wire) and remove one via `remove_event_handler` (never auto-deletes a non-empty handler body).
- [x] **OPS-03**: The tool rejects `owner=button`/group event wiring with an actionable redirect to `add_command`, and on an unknown/invalid event returns the item's allowed events (resolved via `getAllowedEvents`) instead of a generic error.
- [x] **OPS-04**: New operation payloads round-trip symmetrically through `MUTATE_FORM_MODEL` normalization on both validate and consume (no new `ValidationOperation`).

### Event Coverage

- [x] **EVT-01**: Full form-level event set wired on `Form` — lifecycle (`ПриСозданииНаСервере`, `ПриОткрытии`, `ПередЗакрытием`, `ПриЗакрытии`, `ПриПовторномОткрытии`, `ОбработкаОповещения`), write family (`ПередЗаписью`, `ПриЗаписиНаСервере`, `ПослеЗаписи`), server differentiators (`ПриЧтенииНаСервере`, `ПередЗаписьюНаСервере`, `ОбработкаПроверкиЗаполненияНаСервере`), and form-level `ОбработкаВыбора` / `ОбработкаНавигационнойСсылки`.
- [x] **EVT-02**: Full field event set wired on `FormField` — `ПриИзменении`, `НачалоВыбора`, `НачалоВыбораИзСписка`, `ОбработкаВыбора`, `Очистка`, `ОкончаниеВводаТекста`, `Открытие`, decoration `Нажатие`, and other events resolvable for the field kind.
- [x] **EVT-03**: Full table event set wired on `Table` — `ПриАктивизацииСтроки`, `ПередНачаломДобавления`, `ПередНачаломИзменения`, `ПередУдалением`, `ПриИзменении`, `Выбор`, `ПриАктивизацииЯчейки`, and the drag-and-drop event family.
- [x] **EVT-04**: Events and their concrete `mcore.Event` instances are resolved at runtime via `FormItemInformationService.getAllowedEvents(item)` and matched by name (RU/EN) — no hardcoded event-literal spellings; owner-kind classifier drives Form/Field/Table dispatch.

### BSL Stub Generation

- [x] **STUB-01**: Wiring an event atomically generates the matching handler procedure stub in the form `Module.bsl` — both the model slot and the stub, or neither. Stub insertion is idempotent (no duplicate procedure of the same name) with an empty `// TODO` body.
- [x] **STUB-02**: The stub directive (`&НаКлиенте` / `&НаСервере` / `&НаСервереБезКонтекста`) is derived from the resolved `Event.getEnvironments()` — never from event-name suffix heuristics (e.g. `ПриАктивизацииСтроки` stays client).
- [x] **STUB-03**: The stub parameter signature reproduces the event's exact `ParamSet` verbatim, including out-parameters (`СтандартнаяОбработка`, etc.); keywords/identifiers respect the configuration `ScriptVariant` (RU/EN).
- [x] **STUB-04**: The `&НаСервереБезКонтекста` special case (`ПриПолученииДанныхНаСервере`) is generated correctly via a dedicated generator branch.
- [x] **STUB-05**: Edition-variable signatures (`ПередНачаломДобавления`, `ПриИзмененииДанныхВладельца`, …) match the target project's platform edition. **Mechanism (corrected by Phase 7 research, javap-verified):** `FormItemInformationService.getAllowedEvents(item)` already resolves the *version-correct* `Event` upstream, and `AbstractMethod.actualParamSet(int)` selects a `ParamSet` by parameter arity (overload variants), NOT by a separate `Version` lookup — so the correct edition signature is obtained by choosing the widest `ParamSet` of the already-version-resolved `Event`, not by a bespoke `Version`-pinning step.
- [x] **STUB-06**: BM-commit and FS-export are separate phases — the stub is written after force-export, export/derived-data timeout is treated as non-fatal, and artifact existence is verified rather than trusting the sync return.

### Extensions (расширения)

- [x] **EXT-01**: Handlers on extension-adopted forms/items use `EventHandlerExtension` with an `ExtendedMethodCallType`; the operation payload accepts an explicit `call_type` with a sensible default (no hard-coded guess where the default is UNVERIFIED live).
- [x] **EXT-02**: Base-handler-exists detection for adopted items; generated handler procedure names are NOT prefixed with the extension name prefix (prefix applies to top-level metadata names only).
- [x] **EXT-03**: Adopted-form FQN resolution is correct for extension forms (wiring targets the right adopted object).

### Inspection

- [x] **INSP-01**: `inspect_form_layout` surfaces existing event handlers per node as `{event, handlerName}`, enabling idempotent set/remove and base-handler detection.

### Quality & Closure

- [x] **QA-01**: Qwen tool-call priming for the new operations is added in `BackendToolSurfaceRewriteContributor` (the `mutate_form_model` case — the real target; `QwenToolCallExamples.inferExampleParams()` from CLAUDE.md does not exist); tool description stays under 200 chars and the schema stays flat.
- [x] **QA-02**: Regression tests cover op normalization, directive-from-`Environments`, RU/EN stub text, wire+stub atomicity/idempotency, base-vs-extension EClass selection, and button rejection.
- [x] **QA-03**: Full reactor build (`mvn -DskipTests package`) is green with the p2 update site produced, and the live-EDT smoke closure (human gate) validates wiring + generated stub on base and extension forms.

## v2 Requirements

Full form-event coverage was pulled into v0.1.10 by user decision, so no form-event capabilities are deferred. Non-form-event backlog carried from v0.1.9 remains future work (tracked in PROJECT.md → Deferred):

### Migration Tooling (unrelated to this milestone)

- **MIG-01**: Full deletion/move workflow removing base objects only after verified extension-native replacements + explicit confirmation.
- **MIG-02**: Complete semantic reference rewriting inside arbitrary BSL modules.
- **MIG-03**: Visual migration wizard UI.

## Out of Scope

Explicitly excluded for v0.1.10. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| Button / FormGroup event handlers via `mutate_form_model` | `Button`/`FormGroup` do not implement `EventHandlerContainer`; button behavior goes through the existing `add_command` path (redirect, not duplicate). |
| Editing `.form` / `.mdo` / `Module.bsl` XML/text as the primary mechanism | Project rule: mutate via BM/EDT APIs; hand-rolled BSL text is the stub-body exception, not metadata editing. |
| Writing handler bodies (business logic) | The tool generates a correctly-signed empty `// TODO` stub only; body logic is authored separately. |
| Visual form-event editor UI | CLI/tool API ships first; UI is future (mirrors migration-wizard deferral). |
| Reusing EDT UI generators (`ProcedureDirective`/`GotoEventHandlerHandler`) | Live in UI packages, barred by the "core independent of UI" rule. |

## Traceability

Which phases cover which requirements. Populated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| OPS-01 | Phase 6 | Complete |
| OPS-02 | Phase 6 | Complete |
| OPS-03 | Phase 6 | Complete |
| OPS-04 | Phase 6 | Complete |
| EVT-01 | Phase 7 | Complete |
| EVT-02 | Phase 7 | Complete |
| EVT-03 | Phase 7 | Complete |
| EVT-04 | Phase 6 | Complete |
| STUB-01 | Phase 7 | Complete |
| STUB-02 | Phase 7 | Complete |
| STUB-03 | Phase 7 | Complete |
| STUB-04 | Phase 7 | Complete |
| STUB-05 | Phase 7 | Complete |
| STUB-06 | Phase 7 | Complete |
| EXT-01 | Phase 8 | Complete |
| EXT-02 | Phase 8 | Complete |
| EXT-03 | Phase 8 | Complete |
| INSP-01 | Phase 6 | Complete |
| QA-01 | Phase 9 | Complete |
| QA-02 | Phase 9 | Complete |
| QA-03 | Phase 9 | Complete |

**Coverage:**

- v1 requirements: 21 total
- Mapped to phases: 21 ✓
- Unmapped: 0 ✓

**Per-phase distribution:**

- Phase 6 (API spine + ops + inspect): OPS-01, OPS-02, OPS-03, OPS-04, EVT-04, INSP-01 (6)
- Phase 7 (BSL stub gen, base config): EVT-01, EVT-02, EVT-03, STUB-01, STUB-02, STUB-03, STUB-04, STUB-05, STUB-06 (9)
- Phase 8 (extension form support): EXT-01, EXT-02, EXT-03 (3)
- Phase 9 (Qwen priming + tests + smoke): QA-01, QA-02, QA-03 (3)

---
*Requirements defined: 2026-07-13*
*Last updated: 2026-07-13 after roadmap creation (traceability populated, Phases 6–9)*
