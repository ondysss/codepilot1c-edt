# Architecture Research — Integration of Form Event Handlers into `mutate_form_model`

**Domain:** EDT EMF form-model mutation + BSL module stub generation, inside an existing tool/validation stack
**Researched:** 2026-07-13
**Confidence:** HIGH for integration points (read from source); MEDIUM for BSL-generation ergonomics (no headless EDT generator exists — we build our own)

> Goal: extend the existing `mutate_form_model` tool with add/set/remove event-handler operations that (a) mutate the
> form model's `EventHandlerContainer.getHandlers()` in BM, and (b) generate the matching handler procedure stub in the
> form `Module.bsl` with the correct directive + signature — for base and extension forms — under the existing
> `MUTATE_FORM_MODEL` validation-token flow. **No new tool.**

---

## Standard Architecture

### System Overview (where new logic slots in)

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Tool layer  (bundles/.../tools/forms/)                                    │
│  MutateFormModelTool  ── SCHEMA op enum += add_event_handler /             │
│                          set_event_handler / remove_event_handler          │
│      │ consumeToken(MUTATE_FORM_MODEL)  (UNCHANGED two-phase flow)         │
├──────┴───────────────────────────────────────────────────────────────────┤
│  Validation  (edt/validation/MetadataRequestValidationService)             │
│  normalizeUpdateFormModelPayload()  += normalize new ops                    │
│  MUTATE_FORM_MODEL case (L1377)      (UNCHANGED enum)                       │
├──────┬───────────────────────────────────────────────────────────────────┤
│  Facade  (edt/forms/EdtFormService) → delegates to EdtMetadataService       │
├──────┴───────────────────────────────────────────────────────────────────┤
│  Backend  (edt/metadata/EdtMetadataService)                                 │
│                                                                            │
│   Phase A: BM mutation (inside executeWrite tx)                            │
│     updateFormModel() → applyFormModelOperations()  ← add cases here       │
│        resolve item (Form/FormField/Table = EventHandlerContainer)         │
│        resolve Event via FormItemInformationService.getAllowedEvents()     │
│        FormFactory.createEventHandler[Extension]()                          │
│        container.getHandlers().add(handler)                                 │
│        record {handlerName, directive, params} for Phase B                 │
│                                                                            │
│   Phase B: FS export + BSL stub (AFTER tx commit)                         │
│     forceExportTopLevelObject()  (existing)                               │
│     ensureModuleArtifact(Forms/{Form}/Module.bsl)  (existing, empty ok)   │
│     append generated procedure text to Module.bsl (NEW: BslStubWriter)    │
│     refreshProjectSafely()                                                 │
└──────────────────────────────────────────────────────────────────────────┘
```

### Component Responsibilities

| Component | Responsibility | File / method |
|-----------|----------------|---------------|
| `MutateFormModelTool` | Add 3 ops to SCHEMA enum; unchanged token flow | `tools/forms/MutateFormModelTool.java` (SCHEMA `op.enum` L50) |
| `MetadataRequestValidationService` | Normalize new op payloads; reuse `MUTATE_FORM_MODEL` | `edt/validation/MetadataRequestValidationService.java` (`normalizeUpdateFormModelPayload` L893; case L1377) |
| `applyFormModelOperations` | Dispatch new op cases (BM phase) | `EdtMetadataService.java` L821 `switch(op)` |
| new: `wireEventHandler(...)` | Create/set/remove `EventHandler`; resolve `Event` | `EdtMetadataService.java` (near `addCommandToForm` L1182) |
| new: `EventDirectiveResolver` | `Event.getEnvironments()` → directive + param names | new helper class in `edt/forms/` or `edt/lang/` |
| new: `FormModuleStubWriter` | Append procedure text to `Module.bsl` (FS phase) | new class in `edt/forms/`; reuse `ensureModuleArtifact` plumbing |
| `updateFormModel` | Sequence Phase A (tx) then Phase B (export+stub) | `EdtMetadataService.java` L438 |
| `collectFormItemNodes`/`collectFormCommandNodes` | Surface existing handlers in inspect | `EdtMetadataService.java` (~L2770) + `InspectFormLayoutResult` |

---

## New Operations (payload shapes)

Add to `MutateFormModelTool` SCHEMA `op.enum` (currently `["set_form_props","add_group","add_field","add_table","add_command","add_button","set_item","remove_item","move_item"]`):

### `add_event_handler` (also serves "set" — upsert by (target,event))
```json
{
  "op": "add_event_handler",
  "target": "form",                    // "form" | item ref
  "item_id": 12,                       // when target is an item (from inspect_form_layout); omit for form
  "item_name": "ПолеЦена",             // alt to item_id (same batch)
  "event": "OnChange",                 // EN or RU name; validated vs getAllowedEvents(item)
  "handler_name": "ПолеЦенаПриИзменении", // optional; default = EDT-style {ItemName}{EventRu}
  "generate_stub": true,               // default true — write procedure into Module.bsl
  "call_type": "Before"                // extension-adopted forms only → ExtendedMethodCallType
}
```
Behavior: resolve item → `EventHandlerContainer`; resolve `Event`; if a handler for that event exists, replace its
`name` (set semantics); else `FormFactory.createEventHandler[Extension]()`, `setEvent`, `setName`, add to `getHandlers()`.
Then (Phase B) generate stub unless `generate_stub=false`.

### `remove_event_handler`
```json
{ "op": "remove_event_handler", "target": "form", "item_id": 12, "event": "OnChange",
  "remove_stub": false }   // default false — do NOT delete BSL (destructive; keep out of scope, mirror v0.1.9)
```
Behavior: remove the matching `EventHandler` from `getHandlers()`. Leave the module method (safer; deleting BSL is the
deferred "full deletion workflow"). Optionally, later, comment-flag the orphaned method.

> Op-name note: a separate `set_event_handler` is optional — `add_event_handler` with upsert covers both. If the
> planner prefers explicitness, add `set_event_handler` as an alias handled by the same case.

**Qwen rule compliance (CLAUDE.md):** add these ops as examples to `QwenToolCallExamples.inferExampleParams()` (the tool
name `mutate_form_model` already matches a recognized pattern; new *ops* still need XML examples). Keep descriptions <200 chars, schema flat.

---

## Data Flow

### `add_event_handler` request flow
```
LLM → edt_validate_request (MUTATE_FORM_MODEL) → token
    → mutate_form_model{op:add_event_handler,...,validation_token}
       consumeToken() [unchanged]
       EdtFormService.updateFormModel → EdtMetadataService.updateFormModel
         executeWrite(tx):                                   ── PHASE A (BM) ──
           resolve BasicForm → Form (resolveManagedFormModel)
           applyFormModelOperations():
             case add_event_handler:
               item = target=="form" ? formModel : resolveRequiredItem(...)
               require item instanceof EventHandlerContainer  (Form/FormField/Table)
               events = formItemInformationService.getAllowedEvents((FormVisualEntity)item)
               event  = match by getName()/getNameRu() (case-insensitive) or ERROR w/ available list
               handlerName = payload.handler_name ?? defaultName(item,event)
               h = isExtensionForm ? createEventHandlerExtension().setCallType(...) : createEventHandler()
               h.setEvent(event); h.setName(handlerName)
               container.getHandlers().add(h)
               stubPlan.add({handlerName, directiveFrom(event), paramNamesFrom(event)})
           ensureUuidsRecursively(basicForm)
         // tx commit
         forceExportTopLevelObject()  verifyObjectPersisted()  ── PHASE B (FS) ──
         for each stubPlan p (if generate_stub):
           ensureModuleArtifact(Forms/{Form}/Module.bsl, createIfMissing=true)  // existing, empty skeleton
           FormModuleStubWriter.appendProcedure(moduleFile, p)   // NEW text append (idempotent)
         refreshProjectSafely()
```

### BSL stub generation (Phase B detail)
```
Event → getEnvironments() ─→ contains(SERVER)?  AtServer(NoContext) : AtClient
Event → getParamSet().get(0).getParams() ─→ [Parameter.getNameRu()...]   // signature args
Configuration.getScriptVariant() ─→ RU («Процедура … КонецПроцедуры») vs EN
Compose:
   &НаКлиенте                                   (or &НаСервере / &НаСервереБезКонтекста)
   Процедура {handlerName}({param1}, {param2})
       // TODO
   КонецПроцедуры
Append to Module.bsl (skip if a procedure named {handlerName} already present — idempotency).
```

---

## Architectural Patterns

### Pattern 1: Two-phase BM-commit vs FS-export (PROJECT RULE — mandatory)
**What:** Phase A mutates the EMF form model inside the `executeWrite` transaction (handler added to the model, committed to BM).
Phase B, *after* the tx commits and the form is force-exported, writes the BSL procedure text to `Module.bsl`.
**Why:** the project rule "Treat BM commit and filesystem export as separate phases" and "do not assume `forceExport` is
synchronous". The form-model handler and the module file are two artifacts; sequencing avoids writing a stub for a handler
that failed to commit.
**Trade-off:** a crash between phases leaves a handler with no stub → EDT shows "handler procedure not found" diagnostic;
recoverable by re-running the same op (both steps idempotent). Acceptable and self-healing.

### Pattern 2: Text-based module injection (chosen) vs AST injection
**What:** Read `Module.bsl` text, append a well-formed procedure block; do not build/serialize a BSL AST.
**When:** always, for stub generation.
**Why:** `ensureModuleArtifact` is already text/`IFile`-based (`EdtMetadataService` L3316–3323); `BslSemanticService`
loads modules read-only via a detached `XtextResourceSet` and never saves. There is **no headless EDT service** that
inserts a handler procedure (`ProcedureParameters`/`ProcedureDirective`/`GotoEventHandlerHandler` are all UI-package,
barred by the core-independent-of-UI rule — see STACK.md). Hand-building a `BslFactory` AST and serializing it back to
`.bsl` via Xtext headlessly is fragile.
**Trade-off:** we own directive/keyword formatting (RU/EN, `&НаКлиенте` etc.) — small, testable, deterministic.
Idempotency comes from a name-presence check (grep the module for `Процедура {name}(` / `Procedure {name}(`).

### Pattern 3: Event resolution via `FormItemInformationService` (not a literal table)
**What:** resolve the concrete `mcore.Event` for the target item at runtime; validate the requested event name against it.
**When:** every add/set op.
**Why:** `Event` is a model object per item + platform version (STACK.md), not an enum. `getAllowedEvents(item)` yields
the canonical `Event` (with params + environments) to `setEvent(...)` and to build the signature. Also gives the
"available events" list for actionable errors (mirrors v0.1.9 decision to list alternatives).
**Trade-off:** requires importing `com._1c.g5.v8.dt.form.service` (one MANIFEST `Import-Package` addition).

---

## Base vs Extension (расширения) — API-level differences

| Concern | Base configuration form | Extension-adopted form |
|---------|-------------------------|------------------------|
| Handler EClass | `EventHandler` (`createEventHandler`) | `EventHandlerExtension` (`createEventHandlerExtension`) |
| Extra property | — | `setCallType(ExtendedMethodCallType)` — `BEFORE`/`AFTER`/`OVERRIDE`/`CHANGE_AND_VALIDATE` |
| Container source | the form's own `EventHandlerContainer` | the extension form's container; can map from adoptable form via `BslFormEventHandlerService.getCorrespondingEventContainerHandler(...)` |
| Module path | `src/{TopFolder}/{Top}/Forms/{Form}/Module.bsl` | same relative shape inside the extension project (external project) |
| Detection | `isExternalProject(project)` / no extension prefix | existing `isExternalProject()` (used in `updateFormModel` L449) + extension `Configuration.getNamePrefix()` (see MEMORY: extension name-prefix caveat) |

> The milestone's "extension form support" is primarily: pick `createEventHandlerExtension` + default `CallType` when the
> target form belongs to an extension. The `updateFormModel` path already branches on `externalProject`; reuse it.
> **UNVERIFIED (needs live EDT):** exact default `CallType` EDT assigns and whether adopting a *new* event on an
> extension form requires the base event to pre-exist. Flag for live smoke.

---

## Integration Points (file : method)

| # | Where | Change |
|---|-------|--------|
| 1 | `tools/forms/MutateFormModelTool.java` : SCHEMA (L50 `op.enum`) | add `add_event_handler`, `remove_event_handler` (+optional `set_event_handler`); document `event`/`handler_name`/`target`/`generate_stub`/`call_type` fields (keep <200-char, flat) |
| 2 | `edt/validation/MetadataRequestValidationService.java` : `normalizeUpdateFormModelPayload` (L893) | normalize the new ops into the canonical payload (same map shape the token stores); keep `MUTATE_FORM_MODEL` case (L1377) unchanged |
| 3 | `edt/metadata/EdtMetadataService.java` : `applyFormModelOperations` (L821 switch) | new `case "addeventhandler"/"seteventhandler"/"removeeventhandler"` → call `wireEventHandler(...)`; collect a `stubPlan` list to return alongside summaries |
| 4 | `edt/metadata/EdtMetadataService.java` : new `wireEventHandler(Form/FormItem, op)` | BM-phase logic (resolve item as `EventHandlerContainer`, resolve `Event`, create handler, add). Sits beside `addCommandToForm` (L1182) as the parallel to command wiring |
| 5 | `edt/metadata/EdtMetadataService.java` : `updateFormModel` (L438) | after `forceExportTopLevelObject` (L476), run Phase B stub generation for the collected `stubPlan` (call `ensureModuleArtifact` L3251 + new `FormModuleStubWriter`) |
| 6 | new `edt/forms/FormModuleStubWriter.java` | text append of directive+signature+empty body; idempotent name check; uses `IFile` like `ensureModuleArtifact` |
| 7 | new `edt/forms/EventSignatureResolver.java` (or fold into #4) | `Event`→directive (`Environments`), `Event`→param names (`ParamSet`/`Parameter`), script variant (RU/EN) |
| 8 | `edt/metadata/EdtMetadataService.java` : `collectFormItemNodes`/`collectFormCommandNodes` (~L2770) + `edt/forms/InspectFormLayoutResult.java` | add `eventHandlers:[{event,handlerName}]` per node via `FormItemInformationService.getEventHandlers(obj)` (or read `container.getHandlers()`), so `inspect_form_layout` reports existing handlers |
| 9 | `bundles/com.codepilot1c.core/META-INF/MANIFEST.MF` (L55–76 region) | add `com._1c.g5.v8.dt.form.service` to `Import-Package` (for `FormItemInformationService`) |
| 10 | `provider/config/QwenToolCallExamples.java` | add XML/JSON priming examples for the new ops (CLAUDE.md Qwen rule) |
| 11 | tests bundle | regression tests: op parsing/normalization, directive resolution from `Environments`, RU/EN stub text, idempotency, base-vs-extension handler EClass |

---

## Suggested Build Order

1. **Schema + validation normalization** (#1, #2) — ops accepted end-to-end, token flow green; no behavior yet.
2. **BM phase: `wireEventHandler` add/remove** (#3, #4) + MANIFEST import (#9) — form-model `EventHandler` created/removed, force-exported. Verify the `.form` gains `<events>`/handler entry after export. (No stub yet — EDT will show "method not found" diagnostic, expected.)
3. **Event resolution + directive** (#7) — resolve concrete `Event`, derive directive from `Environments`, param names from `ParamSet`. Unit-test with a couple of known events.
4. **BSL stub generation** (#5, #6) — Phase-B text append; idempotency; RU/EN. Now the "method not found" diagnostic clears (re-run diagnostics per Verification Rule).
5. **inspect_form_layout surfacing** (#8) — read `getEventHandlers` into the inspect result so the agent can see current wiring.
6. **Extension-adopted forms** — switch to `createEventHandlerExtension` + default `CallType` when `isExternalProject`/extension. Live-smoke this branch (UNVERIFIED default CallType).
7. **Qwen examples + regression tests + full reactor build** (#10, #11) — `mvn -DskipTests package`, then live-EDT smoke closure gate (human verifier of record, per PROJECT decision).

---

## Anti-Patterns

### Anti-Pattern 1: Reusing EDT's UI procedure generator from `core`
**Do not** import `com._1c.g5.v8.dt.bsl.ui.event.*` or `form.ui.commands.*`. Breaks core-independent-of-UI. Generate text in `core`.

### Anti-Pattern 2: Writing the stub inside the BM transaction
**Do not** append to `Module.bsl` inside `executeWrite`. Filesystem export is a separate phase; the module file may not
exist/refresh until after `forceExportTopLevelObject`. Do stub work in Phase B.

### Anti-Pattern 3: Treating `event` as a free string set directly on the model
**Do not** `setName(event)` and skip `setEvent`. The model needs the resolved `mcore.Event` reference; a name-only handler
won't reconcile and yields a form diagnostic. Always resolve via `getAllowedEvents`.

### Anti-Pattern 4: Deleting the BSL method on `remove_event_handler`
Keep destructive module edits out of scope (mirrors v0.1.9 "no unsafe deletion"). Remove only the model handler by default.

---

## Integration Points — Internal Boundaries

| Boundary | Communication | Notes |
|----------|---------------|-------|
| Tool ↔ Validation | `MUTATE_FORM_MODEL` token (unchanged) | Two-phase `validateAndIssueToken` → `consumeToken`; payload must round-trip equal |
| EdtFormService ↔ EdtMetadataService | direct facade delegation (`updateFormModel`) | existing |
| BM phase ↔ FS phase | in-memory `stubPlan` list returned from tx | carries `{handlerName, directive, paramNames, generateStub}` across the commit boundary |
| core ↔ EDT UI | **NONE (must stay severed)** | reason we generate BSL text ourselves |

---

## Open / UNVERIFIED (flag for planner + live smoke)

1. **No headless BSL procedure generator** in a `core`-legal package — confirmed absent; we generate text. (Verified gap, not a name.)
2. **Exact per-item event literal spellings** (EN/RU) — resolve at runtime via `getAllowedEvents`; do not hardcode. (UNVERIFIED spellings.)
3. **Extension-adopted form default `CallType`** and whether the base event must pre-exist before adopting — needs live EDT. (UNVERIFIED.)
4. **`getAllowedEventNames(EClass, Version)` wiring** — the `Version` argument source (project platform version) — type verified, call wiring UNVERIFIED.

## Sources

- `edt-javadoc` MCP — form-model + mcore + bsl API (see STACK.md source list). VERIFIED.
- Repo source: `EdtMetadataService.java` (L438 `updateFormModel`, L807 `resolveManagedFormModel`, L821 dispatch,
  L1182 `addCommandToForm`, L2770 inspect collectors, L3251 `ensureModuleArtifact`, L3948 `buildModuleCandidates`),
  `MutateFormModelTool.java` (SCHEMA), `MetadataRequestValidationService.java` (L893/L1377), `BslSemanticService.java`
  (read-only module load), `MANIFEST.MF` (Import-Package). Read directly.

---
*Architecture research for: integrating form event handlers into `mutate_form_model` (v0.1.10)*
*Researched: 2026-07-13*
