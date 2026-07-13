# Project Research Summary

**Project:** CodePilot1C OSS — v0.1.10 "Managed Form Event Handlers"
**Domain:** Eclipse RCP/OSGi plugin for 1C:EDT — EMF form-model mutation + BSL module stub generation
**Researched:** 2026-07-13
**Confidence:** HIGH (EDT EMF names verified against `edt-javadoc` MCP; integration points read from source; two codebase corrections surfaced)

## Executive Summary

This milestone extends the existing `mutate_form_model` tool so the agent can wire managed-form event handlers on both base-configuration and 1C-extension (расширения) forms. "Wiring" is two artifacts, not one: (a) setting the event on the EMF form model (`EventHandlerContainer.getHandlers()`), and (b) generating the matching handler procedure stub in the form `Module.bsl` with the correct client/server directive and exact parameter signature. Both must happen together or neither — the single biggest failure mode is a form event slot pointing at a procedure that doesn't exist (or the reverse). No new tool is created; everything reuses the existing `MUTATE_FORM_MODEL` validation-token two-phase flow.

The EDT API spine is fully verified. Handlers live only on `Form`, `FormField`, and `Table` (which implement `EventHandlerContainer`); `FormGroup` and `Button` do NOT — button behavior goes through the existing `add_command` path, so the event tool must reject `owner=button`. Base-config forms use a plain `EventHandler`; extension-adopted forms use `EventHandlerExtension` with a `setCallType(ExtendedMethodCallType)`. Critically, `Event` is a `com._1c.g5.v8.dt.mcore.Event` **model object, not a Java enum** — the concrete instance (with its parameters and client/server `Environments`) is resolved at runtime via `FormItemInformationService.getAllowedEvents(item)`, and the BSL directive is derived from `Event.getEnvironments()`, never from name-suffix heuristics.

The hard part is BSL stub generation. EDT's native procedure generator (`ProcedureDirective`/`ProcedureParameters`/`GotoEventHandlerHandler`) lives in UI packages, barred by the "core independent of UI" rule, and there is **no headless EDT service that emits handler text**. So `core` must hand-roll the procedure text itself — deterministic and testable, but greenfield: no per-procedure BSL insertion primitive exists today (`BslSemanticService` is read-only; `ensureModuleArtifact` only sets initial content). Key risks: wrong client/server directive (esp. client-only rules like `ПриАктивизацииСтроки`), incomplete out-param signatures, RU/EN language mismatch, BM-commit-vs-FS-export ordering, and extension `CallType`/base-handler-detection semantics that need live-EDT verification.

## Key Findings

### Recommended Stack

The "stack" is EDT EMF packages already on the bundle classpath (this is not an npm project). Every EDT name is `edt-javadoc`-VERIFIED. Only one MANIFEST change is required: add `com._1c.g5.v8.dt.form.service` to `Import-Package` for `FormItemInformationService`. See STACK.md.

**Core technologies:**
- `com._1c.g5.v8.dt.form.model` — `EventHandlerContainer.getHandlers()`, `EventHandler` (base) / `EventHandlerExtension` + `setCallType`, `FormFactory.createEventHandler[Extension]()`. On Form/FormField/Table only.
- `com._1c.g5.v8.dt.form.service.FormItemInformationService` — `getAllowedEvents(item)` resolves the concrete `Event`; `getEventHandlers(obj)` reads active handlers for inspect. **Requires new Import-Package.**
- `com._1c.g5.v8.dt.mcore` — `Event extends AbstractMethod, Environmental`; params via `getParamSet().get(0).getParams()` → `Parameter.getName()/getNameRu()`; directive via `Environments` (`SERVER`/`ALL_CLIENTS`/…).
- `ExtendedMethodCallType` (enum) — `BEFORE`/`AFTER`/`OVERRIDE`/`CHANGE_AND_VALIDATE` for extension handlers. Default `BEFORE` unconfirmed live.
- `Configuration.getScriptVariant()` — RU vs EN keyword/identifier selection for the stub (already used in-repo).

**What NOT to use:** `bsl.ui.event.ProcedureDirective`/`ProcedureParameters`/`GotoEventHandlerHandler` (UI packages); treating `Event` as an enum; editing `.form`/`.mdo`/`Module.bsl` XML/text as the primary mechanism; serializing a hand-built `BslFactory` AST (Xtext headless serialization is fragile → use text append).

### Expected Features

The load-bearing artifact is a **per-event descriptor table** mapping each event → `{owner, execution-context → directive, exact signature}`. The directive comes from the descriptor, never from string heuristics. See FEATURES.md for the full RU/EN + signature catalog.

**Must have (v1 table stakes):**
- Owner-kind classifier + directive-decision table (everything reads from it).
- Form-level events — `ПриСозданииНаСервере`, `ПриОткрытии`, `ПередЗакрытием`, `ПриЗакрытии`, `ПриПовторномОткрытии`, `ОбработкаОповещения`, `ПередЗаписью`, `ПриЗаписиНаСервере`, `ПослеЗаписи`.
- Field events — `ПриИзменении`, `НачалоВыбора`, `ОбработкаВыбора`, `Очистка`, `НачалоВыбораИзСписка`, `ОкончаниеВводаТекста`, `Открытие`.
- Table events — `ПриАктивизацииСтроки`, `ПередНачаломДобавления`, `ПередНачаломИзменения`, `ПередУдалением`, `ПриИзменении`, `Выбор`, `ПриАктивизацииЯчейки`.
- BSL stub generator (correctly-signed, correctly-directived, empty `// TODO` body).
- `add_event_handler` / `set_event_handler` (upsert) / `remove_event_handler` ops under `MUTATE_FORM_MODEL`.
- Button-click rejection → redirect to `add_command`.
- Extension (расширения) support (milestone-committed).
- `inspect_form_layout` surfaces existing handlers (enables idempotent set/remove + base-handler detection).

**Should have (v1.x differentiators):**
- Server differentiators — `ПриЧтенииНаСервере`, `ПередЗаписьюНаСервере`, `ОбработкаПроверкиЗаполненияНаСервере`.
- Form-level `ОбработкаВыбора` (name collides with field event — key on owner), `ОбработкаНавигационнойСсылки`, decoration `Нажатие`.

**Defer (v2+):**
- Drag-and-drop event family (high signature complexity, low demand).
- `ПриПолученииДанныхНаСервере` — the ONLY legitimate `&НаСервереБезКонтекста` event; needs a special-cased generator branch.
- `ПриИзмененииДанныхВладельца` (subordinate-form-specific signature).

### Architecture Approach

Extend `mutate_form_model` (no new tool). Two phases across the BM-commit boundary: **Phase A** (inside `executeWrite` tx) resolves the item as an `EventHandlerContainer`, resolves the `Event` via `FormItemInformationService`, creates the handler (`EventHandler` or `EventHandlerExtension`+`CallType`), adds it, and records a `stubPlan`; **Phase B** (after tx commit + force-export) ensures `Module.bsl` exists and appends the generated procedure text idempotently. See ARCHITECTURE.md.

**Major components:**
1. `MutateFormModelTool` — add 3 ops to SCHEMA enum; unchanged token flow.
2. `MetadataRequestValidationService.normalizeUpdateFormModelPayload` — normalize new ops symmetrically on validate + consume (reuse `MUTATE_FORM_MODEL`; register nothing new).
3. `EdtMetadataService.applyFormModelOperations` — dispatch new cases → new `wireEventHandler(...)` (BM phase, beside `addCommandToForm`).
4. `EventSignatureResolver` (new) — `Event`→directive via `Environments`, `Event`→param names via `ParamSet`, RU/EN via `ScriptVariant`.
5. `FormModuleStubWriter` (new) — text append of directive+signature+empty body; idempotent name-presence check.
6. `inspect_form_layout` collectors + `InspectFormLayoutResult` — surface `eventHandlers:[{event, handlerName}]` per node.
7. MANIFEST — add `com._1c.g5.v8.dt.form.service` Import-Package.

### Critical Pitfalls

1. **Wrong client/server directive** — infer directive from the per-event descriptor's `Event.getEnvironments()`, never from name suffix. `ПриАктивизацииСтроки` is client-only; `&НаСервереБезКонтекста` is never a form/item event default.
2. **Handler-name ↔ module-method desync / orphans** — make model-slot-set and stub-insert atomic (both or neither). Be idempotent on re-wire; never auto-delete a non-empty handler body on remove.
3. **Wrong/incomplete signature** — reproduce the descriptor's exact param list verbatim including out-params (`СтандартнаяОбработка`, etc.); no generic `(Элемент)` template.
4. **Extension directives / adopted-module semantics** — detect adopted-vs-owned and whether a base handler already exists; emit the extension `CallType`; never prefix handler procedure names (prefix applies to top-level metadata names only). Needs live-EDT.
5. **BM-commit vs FS-export separation** — ensure module exists → write stub → export (separate phase) → refresh → then diagnose. Treat "timed out waiting for export/derived-data" as non-fatal; verify artifact existence, don't trust sync return.

### Two Codebase Corrections (surfaced by research — must reach the planner)

- **Qwen priming target:** CLAUDE.md names `QwenToolCallExamples.inferExampleParams()`, but that class/method **does not exist** in this codebase. The real Qwen tool-call priming for `mutate_form_model` is a Russian string in `BackendToolSurfaceRewriteContributor.java` (case `"mutate_form_model"`). Any plan targeting the former will fail; edit the latter.
- **No BSL insertion primitive today:** stub generation is greenfield. `BslSemanticService` is read-only; `ensureModuleArtifact` only sets initial content; the only existing module-file write is deletion during form cleanup. Build the guarded, idempotent text-insertion path — don't look for an existing one.

## Implications for Roadmap

Phase numbering continues from Phase 6 (v0.1.9 ended at Phase 5). Suggested structure follows ARCHITECTURE.md's build order, splitting on the natural verification boundaries the pitfalls demand.

### Phase 6: API spine + operation plumbing + inspect surfacing
**Rationale:** Establish the verified EMF spine and get ops accepted end-to-end before any BSL work; inspect surfacing must precede idempotent set/remove (Pitfall 8).
**Delivers:** 3 ops on `MutateFormModelTool` SCHEMA; symmetric `normalizeUpdateFormModelPayload`; MANIFEST `form.service` import; `wireEventHandler` BM-phase (create/add/remove `EventHandler`, resolve `Event` via `getAllowedEvents`, item-kind guard incl. button rejection); `inspect_form_layout` reports existing handlers.
**Addresses:** ops under `MUTATE_FORM_MODEL`; inspect surfacing (both milestone requirements).
**Avoids:** Pitfall 6 (token round-trip / flat schema / <200-char description), Pitfall 8 (blind set/remove), button double-wiring.
**Note:** at this phase EDT will show "method not found" (no stub yet) — expected.

### Phase 7: BSL stub generation (directive + signature + language) for base config
**Rationale:** The milestone's core value; depends on the descriptor table and the resolved `Event`.
**Delivers:** per-event descriptor table; `EventSignatureResolver` (`Environments`→directive, `ParamSet`→params, `ScriptVariant`→RU/EN); `FormModuleStubWriter` idempotent text append; atomic wire+stub across the BM/FS boundary.
**Uses:** `Event.getEnvironments()`, `getParamSet()`, `Configuration.getScriptVariant()`.
**Avoids:** Pitfalls 1 (directive), 2 (atomicity/idempotency), 3 (signature fidelity), 5 (export ordering), 7 (RU/EN). Also fixes the Qwen priming location in `BackendToolSurfaceRewriteContributor`.

### Phase 8: Extension (расширения) form support
**Rationale:** Base config must be solid first; extension adds `EventHandlerExtension`/`CallType`, base-handler detection, and name-prefix awareness on top.
**Delivers:** `createEventHandlerExtension` + default `CallType` when `isExternalProject`/extension; base-handler-exists detection; unprefixed handler proc names; correct adopted-form FQN resolution.
**Avoids:** Pitfall 4 (extension directive collisions, wrong prefix).

### Phase 9: Qwen examples + regression tests + live-EDT smoke closure
**Rationale:** Freeze op vocabulary before priming; close on the human-gated live smoke (verifier of record).
**Delivers:** Qwen priming in `BackendToolSurfaceRewriteContributor`; regression tests (op normalization, directive-from-`Environments`, RU/EN stub text, idempotency, base-vs-extension EClass); full reactor `mvn -DskipTests package`; live-EDT smoke.

### Phase Ordering Rationale
- Inspect surfacing before set/remove idempotency (Pitfall 8 feeds Pitfalls 2 & 4).
- API/ops plumbing before BSL generation so the token round-trip is proven independent of stub complexity.
- Base config before extension: extension is strictly additive and its `CallType` defaults are UNVERIFIED (isolate the live-EDT risk).
- Qwen/tests/smoke last: op names must be frozen before priming XML is written.

### Research Flags

Phases likely needing deeper research during planning:
- **Phase 8 (Extension):** UNVERIFIED default `CallType` and whether a base event must pre-exist before adoption — needs live-EDT. Highest residual risk.
- **Phase 7 (Stub gen):** exact per-item event literal spellings (EN/RU) are UNVERIFIED — resolve at runtime via `getAllowedEvents`; `ПередНачаломДобавления` signature varies by platform edition; `getAllowedEventNames(EClass, Version)` `Version` wiring UNVERIFIED.

Phases with standard patterns (lighter research):
- **Phase 6:** mirrors the existing `add_command` wiring + validation-token flow; well-trodden.
- **Phase 9:** established regression-test + reactor-build + human-smoke pattern from v0.1.9.

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | Every EDT EMF name verified in `edt-javadoc`; only BSL-generation gap (no headless generator) explicitly flagged. |
| Features | HIGH | 1C managed-form event model is platform-stable across 8.3; canonical signatures. Two events flagged edition-variable. |
| Architecture | HIGH (integration) / MEDIUM (BSL ergonomics) | Integration points read from source; stub generation is greenfield hand-rolled text. |
| Pitfalls | HIGH | Grounded in codebase reconnaissance of actual command-wiring/validation/extension code + platform model. |

**Overall confidence:** HIGH

### Gaps to Address
- **Extension `CallType` default + base-event precondition:** live-EDT verification during Phase 8; do not guess in code — make the op payload allow explicit `call_type` with a sensible default.
- **Per-item event literal spellings:** never hardcode; resolve via `getAllowedEvents(item)` and return actionable "available events" on mismatch.
- **`getAllowedEventNames(EClass, Version)` Version source:** identify the project platform `Version` provider during Phase 7.
- **Edition-variable signatures (`ПередНачаломДобавления`, `ПриИзмененииДанныхВладельца`):** pin to the target project's platform version or verify before generating.
- **Live-EDT closure is a human gate** (BM model not CLI-reachable) — Phase 9 closes on user verification of record, per PROJECT decision.

## Sources

### Primary (HIGH confidence)
- `edt-javadoc` MCP (source of truth) — `EventHandler(Container)`, `EventHandlerExtension`, `ExtendedMethodCallType`, `Form`/`FormField`/`Table`/`Button`/`FormGroup`, `FormFactory`, `FormItemInformationService`, `mcore.Event`/`AbstractMethod`/`ParamSet`/`Parameter`/`Environmental`/`Environments`, `BslFactory`, `BslFormEventHandlerService`, `platform.version.Version`.
- CodePilot1C OSS codebase — `MutateFormModelTool.java`, `EdtMetadataService.java` (updateFormModel/dispatch/addCommandToForm/ensureModuleArtifact/inspect collectors), `MetadataRequestValidationService.java`, `ValidationOperation.java`, `BslSemanticService.java`, `InspectFormLayoutTool/Result`, `EdtExtensionService.java`, `BackendToolSurfaceRewriteContributor.java`, `MANIFEST.MF`.
- 1C platform managed-form event model (platform-stable 8.3) — canonical directive/context/signature definitions.

### Secondary (MEDIUM confidence)
- ITS 1С / Infostart / TopKoder / master1c8 articles on event handler sequence, choice processing, input-field override — event catalog cross-reference.

### Tertiary (LOW confidence)
- Per-item EN/RU event literal spellings and extension `CallType` defaults — inferred; resolve at runtime / verify live.

---
*Research completed: 2026-07-13*
*Ready for roadmap: yes*
