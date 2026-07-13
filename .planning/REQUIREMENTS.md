# Requirements: CodePilot1C OSS — v0.1.10 Managed Form Event Handlers

**Defined:** 2026-07-13
**Core Value (this milestone):** The agent can safely wire managed-form event handlers — setting the event on the EMF form model AND generating the matching BSL handler stub, atomically — on both base-configuration and 1C-extension forms.

Scope locked with the user: full event coverage (form-level + field + table, including differentiators and edge cases), wire-and-generate-stub, extend the existing `mutate_form_model` tool (reuse `MUTATE_FORM_MODEL` token), base config + extensions in this milestone.

## v1 Requirements

Requirements for v0.1.10. Each maps to a roadmap phase (numbering continues from Phase 6).

### Tool Operations

- [ ] **OPS-01**: Agent can add an event handler to a Form/FormField/Table via a new `add_event_handler` operation of `mutate_form_model`, under the existing `MUTATE_FORM_MODEL` validation-token flow.
- [ ] **OPS-02**: Agent can upsert an existing handler via `set_event_handler` (idempotent re-wire) and remove one via `remove_event_handler` (never auto-deletes a non-empty handler body).
- [ ] **OPS-03**: The tool rejects `owner=button`/group event wiring with an actionable redirect to `add_command`, and on an unknown/invalid event returns the item's allowed events (resolved via `getAllowedEvents`) instead of a generic error.
- [ ] **OPS-04**: New operation payloads round-trip symmetrically through `MUTATE_FORM_MODEL` normalization on both validate and consume (no new `ValidationOperation`).

### Event Coverage

- [ ] **EVT-01**: Full form-level event set wired on `Form` — lifecycle (`ПриСозданииНаСервере`, `ПриОткрытии`, `ПередЗакрытием`, `ПриЗакрытии`, `ПриПовторномОткрытии`, `ОбработкаОповещения`), write family (`ПередЗаписью`, `ПриЗаписиНаСервере`, `ПослеЗаписи`), server differentiators (`ПриЧтенииНаСервере`, `ПередЗаписьюНаСервере`, `ОбработкаПроверкиЗаполненияНаСервере`), and form-level `ОбработкаВыбора` / `ОбработкаНавигационнойСсылки`.
- [ ] **EVT-02**: Full field event set wired on `FormField` — `ПриИзменении`, `НачалоВыбора`, `НачалоВыбораИзСписка`, `ОбработкаВыбора`, `Очистка`, `ОкончаниеВводаТекста`, `Открытие`, decoration `Нажатие`, and other events resolvable for the field kind.
- [ ] **EVT-03**: Full table event set wired on `Table` — `ПриАктивизацииСтроки`, `ПередНачаломДобавления`, `ПередНачаломИзменения`, `ПередУдалением`, `ПриИзменении`, `Выбор`, `ПриАктивизацииЯчейки`, and the drag-and-drop event family.
- [ ] **EVT-04**: Events and their concrete `mcore.Event` instances are resolved at runtime via `FormItemInformationService.getAllowedEvents(item)` and matched by name (RU/EN) — no hardcoded event-literal spellings; owner-kind classifier drives Form/Field/Table dispatch.

### BSL Stub Generation

- [ ] **STUB-01**: Wiring an event atomically generates the matching handler procedure stub in the form `Module.bsl` — both the model slot and the stub, or neither. Stub insertion is idempotent (no duplicate procedure of the same name) with an empty `// TODO` body.
- [ ] **STUB-02**: The stub directive (`&НаКлиенте` / `&НаСервере` / `&НаСервереБезКонтекста`) is derived from the resolved `Event.getEnvironments()` — never from event-name suffix heuristics (e.g. `ПриАктивизацииСтроки` stays client).
- [ ] **STUB-03**: The stub parameter signature reproduces the event's exact `ParamSet` verbatim, including out-parameters (`СтандартнаяОбработка`, etc.); keywords/identifiers respect the configuration `ScriptVariant` (RU/EN).
- [ ] **STUB-04**: The `&НаСервереБезКонтекста` special case (`ПриПолученииДанныхНаСервере`) is generated correctly via a dedicated generator branch.
- [ ] **STUB-05**: Edition-variable signatures (`ПередНачаломДобавления`, `ПриИзмененииДанныхВладельца`, …) are pinned to the target project's platform `Version`.
- [ ] **STUB-06**: BM-commit and FS-export are separate phases — the stub is written after force-export, export/derived-data timeout is treated as non-fatal, and artifact existence is verified rather than trusting the sync return.

### Extensions (расширения)

- [ ] **EXT-01**: Handlers on extension-adopted forms/items use `EventHandlerExtension` with an `ExtendedMethodCallType`; the operation payload accepts an explicit `call_type` with a sensible default (no hard-coded guess where the default is UNVERIFIED live).
- [ ] **EXT-02**: Base-handler-exists detection for adopted items; generated handler procedure names are NOT prefixed with the extension name prefix (prefix applies to top-level metadata names only).
- [ ] **EXT-03**: Adopted-form FQN resolution is correct for extension forms (wiring targets the right adopted object).

### Inspection

- [ ] **INSP-01**: `inspect_form_layout` surfaces existing event handlers per node as `{event, handlerName}`, enabling idempotent set/remove and base-handler detection.

### Quality & Closure

- [ ] **QA-01**: Qwen tool-call priming for the new operations is added in `BackendToolSurfaceRewriteContributor` (the `mutate_form_model` case — the real target; `QwenToolCallExamples.inferExampleParams()` from CLAUDE.md does not exist); tool description stays under 200 chars and the schema stays flat.
- [ ] **QA-02**: Regression tests cover op normalization, directive-from-`Environments`, RU/EN stub text, wire+stub atomicity/idempotency, base-vs-extension EClass selection, and button rejection.
- [ ] **QA-03**: Full reactor build (`mvn -DskipTests package`) is green with the p2 update site produced, and the live-EDT smoke closure (human gate) validates wiring + generated stub on base and extension forms.

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
| OPS-01 | Phase ? | Pending |
| OPS-02 | Phase ? | Pending |
| OPS-03 | Phase ? | Pending |
| OPS-04 | Phase ? | Pending |
| EVT-01 | Phase ? | Pending |
| EVT-02 | Phase ? | Pending |
| EVT-03 | Phase ? | Pending |
| EVT-04 | Phase ? | Pending |
| STUB-01 | Phase ? | Pending |
| STUB-02 | Phase ? | Pending |
| STUB-03 | Phase ? | Pending |
| STUB-04 | Phase ? | Pending |
| STUB-05 | Phase ? | Pending |
| STUB-06 | Phase ? | Pending |
| EXT-01 | Phase ? | Pending |
| EXT-02 | Phase ? | Pending |
| EXT-03 | Phase ? | Pending |
| INSP-01 | Phase ? | Pending |
| QA-01 | Phase ? | Pending |
| QA-02 | Phase ? | Pending |
| QA-03 | Phase ? | Pending |

**Coverage:**
- v1 requirements: 21 total
- Mapped to phases: 0 (roadmap pending)
- Unmapped: 21 ⚠️

---
*Requirements defined: 2026-07-13*
*Last updated: 2026-07-13 after initial definition*
