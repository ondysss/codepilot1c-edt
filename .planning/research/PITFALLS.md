# Pitfalls Research

**Domain:** 1C managed-form event-handler wiring in the CodePilot1C OSS EDT tooling (v0.1.10). Extending `mutate_form_model` to (a) set the event on the form/item model AND (b) generate the handler procedure stub in `Module.bsl` with the correct client/server directive and signature — for base config AND 1C extensions (расширения).
**Researched:** 2026-07-13
**Confidence:** HIGH (grounded in codebase reconnaissance of the actual `mutate_form_model` / command-wiring / validation-token / extension code, plus the platform-stable 1C event model)

## Codebase Facts That Drive These Pitfalls

Verified in this session (paths absolute):

- **`mutate_form_model` tool:** `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/forms/MutateFormModelTool.java`. Ops: `set_form_props, add_group, add_field, add_table, add_command, add_button, set_item, remove_item, move_item`.
- **Operation dispatch:** `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/EdtMetadataService.java`, `updateFormModel(...)` — normalized `switch(op)` around lines 821–1036.
- **Command handler wiring (the closest analog):** `EdtMetadataService.java` lines ~1198–1203 — builds `CommandHandler` → `FormCommandHandlerContainer` → `formCommand.setAction(...)` and calls `handler.setName(actionHandler)`. **It sets the handler NAME on the EMF model ONLY. It does NOT generate any procedure in `Module.bsl`.** (Confirmed: no `insertMethod`/`generateProcedure`/module-write near command creation.)
- **BSL stub generation does not exist yet.** `BslSemanticService` (`bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/lang/BslSemanticService.java`) is **read-only** (`getMethodBody`, `listMethods`, `analyzeMethod`, `getModuleContext`, `getModuleExports`) — there is no BSL insertion/mutation API. The only module-file touch in `EdtMetadataService` (lines ~9780) is **deletion** during form cleanup.
- **Module materialization primitive exists:** `EnsureModuleArtifactTool` (`bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/metadata/EnsureModuleArtifactTool.java`) + `EdtMetadataService.ensureModuleArtifact(...)` (line ~3251) can ensure `Module.bsl` exists and set INITIAL content — but cannot insert a single procedure into an existing module.
- **Text-level file writers exist:** `EditFileTool` / `WriteTool` (`bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/file/`). These are BSL-unaware.
- **Validation-token flow:** `ValidationOperation.MUTATE_FORM_MODEL` in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/validation/ValidationOperation.java`; two-phase issue/consume in `MetadataRequestValidationService.java`. Payload: `{project, form_fqn, operations:[...], validation_token}`; token is single-use.
- **`inspect_form_layout`:** `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/forms/InspectFormLayoutTool.java` surfaces items + commands (with `action`/handler name) but **NOT** item/form event handlers.
- **Extension adoption:** `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/extension/EdtExtensionService.java` (`adoptObject(...)`). Directive annotations (`&Вместо`/`Instead`, `&Перед`/`Before`, `&После`/`After`, `Around`, `&ИзменениеИКонтроль`/`ChangeAndValidate`) are parsed at the BSL `Pragma` level (`BslSemanticService` ~979–997); there is **no centralized directive enum**.
- **Qwen priming location differs from CLAUDE.md.** `QwenToolCallExamples.java` and `inferExampleParams()` **do NOT exist** in the codebase. The live Qwen tool-call priming for `mutate_form_model` lives as a Russian string in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/tools/surface/BackendToolSurfaceRewriteContributor.java` (case `"mutate_form_model"`). Any plan that says "add examples to `QwenToolCallExamples.inferExampleParams()`" will fail to find the file — the real edit target is the surface rewrite contributor.

---

## Critical Pitfalls

### Pitfall 1: Wrong client/server directive for the event's execution context

**What goes wrong:**
The generated stub carries `&НаКлиенте` for a server-only event (or vice versa), or emits `&НаСервереБезКонтекста` for a context-bound form event. Result: the handler either doesn't compile, throws at runtime ("method not available in this context"), or the platform silently never calls it — and an SU diagnostic appears.

**Why it happens:**
Developers infer the directive from the name suffix ("has `НаСервере` in it → server"). That works for `ПриСозданииНаСервере` but breaks for `ПриАктивизацииСтроки` (client-only by platform rule — you cannot call `&НаСервере` form methods there) and for `ПередЗакрытием`/`ОбработкаОповещения` (client). It also breaks the two distinct "server" KINDS: reserved-name form procs (`ПриСозданииНаСервере`) vs the illegitimate `&НаСервереБезКонтекста` default.

**How to avoid:**
Drive the directive from the **per-event descriptor table** in FEATURES.md — never from string heuristics. Each event descriptor carries `{owner, context, directive, signature}`. `&НаСервереБезКонтекста` is emitted ONLY for the single `ПриПолученииДанныхНаСервере` dynamic-list exception (deferred to v2), never as a form/item event default. Reject any event not in the descriptor table rather than guessing.

**Warning signs:**
Post-mutation diagnostics report "procedure with `&НаСервере` cannot be an event handler for a client event", or `ПриАктивизацииСтроки` handler shows a "server call not allowed" marker. A stub with `&НаСервереБезКонтекста` on `ПриСозданииНаСервере`.

**Phase to address:**
Phase that builds the **directive-decision table + stub generator** (first implementation phase). Verification: generate stubs for one client event, one reserved-server event, one client-only-table event; run diagnostics; expect zero directive markers.

---

### Pitfall 2: Handler-name ↔ module-method desync (event points at a missing procedure; orphans on remove/rename)

**What goes wrong:**
The event slot in the `.form` model names a procedure that doesn't exist in `Module.bsl` (empty event with a red "unknown handler" marker), OR the stub is generated but the model slot never gets set, OR `remove_event_handler`/`remove_item` clears the slot but leaves an orphan procedure (and the reverse: renames leave a dangling old proc).

**Why it happens:**
The existing command path (`EdtMetadataService` ~1198–1203) sets `CommandHandler.name` on the model and **generates no BSL** — so copying that pattern gives you a model slot pointing at nothing. There is no existing atomic "set slot + write proc" primitive. And `remove_item`/`move_item` today have **no handler/orphan cleanup**, so the same gap exists for events.

**How to avoid:**
Make the apply phase **atomic: model-slot set AND stub insert happen together or neither happens** (single BM-write unit for the model side; and the FS/module write must be reconciled — see Pitfall 5). For `set_event_handler` on an already-wired event, read current state first (via inspect surfacing, Pitfall 8) and be **idempotent**: if the target proc already exists with the right signature, don't duplicate it; if renaming, update the slot and leave the old proc only with an explicit `// orphaned` marker (do not silently delete BSL the user may have written into). Never auto-delete a non-empty handler body on `remove`.

**Warning signs:**
"Unknown form-module procedure" markers on the event; two procedures with the same base name; an event slot that is blank after a "successful" apply; a stub whose name doesn't exactly match the slot (case/prefix mismatch).

**Phase to address:**
Stub-generator phase (atomicity + idempotency) and the remove/rename phase. Verification: wire → assert both slot and proc exist and names match; remove → assert slot cleared and no dangling reference marker; re-wire same event twice → assert single proc.

---

### Pitfall 3: Wrong or incomplete parameter signature (missing `Элемент`, `СтандартнаяОбработка`, out-params)

**What goes wrong:**
The stub is generated with `()` or a partial parameter list, so the platform's call fails to bind, or the developer's later code can't set the out-param that controls behavior (e.g., `СтандартнаяОбработка = Ложь`). Events with out-params (`НачалоВыбора`, `ОбработкаВыбора`, `Очистка`, `ОкончаниеВводаТекста`, `АвтоПодбор`, `Выбор`, `ПередЗакрытием`, table `ПередНачаломДобавления`) are the ones that silently misbehave.

**Why it happens:**
Signatures are non-trivial and vary per event; a generic "one param `Элемент`" template is tempting but wrong for ~half the events. `АвтоПодбор` has 6 params; `ПередЗакрытием` has 4 (three out/inout); `ПередНачаломДобавления` has 6 and its shape varies by platform edition.

**How to avoid:**
The stub generator reads the **exact parameter list from the event descriptor** (FEATURES.md gives them verbatim) and reproduces it literally, including out-params. Do not truncate. For events flagged "signature varies by platform edition" (`ПередНачаломДобавления`, `ПриИзмененииДанныхВладельца`), pin the signature to the target project's platform version or verify before generating. Use the **localized identifiers that match the project language** (see Pitfall 7).

**Warning signs:**
Diagnostic "number of parameters of the event handler does not match"; developer reports "setting `СтандартнаяОбработка` does nothing" (param missing); F1/property-grid shows the event unbound despite a proc existing.

**Phase to address:**
Stub-generator phase. Verification: for each catalogued event, generated signature string-equals the descriptor signature; a golden-file test per event family; diagnostics show no "parameter count mismatch".

---

### Pitfall 4: Extension (расширения) handler directives and adopted-module semantics

**What goes wrong:**
On an ADOPTED form in an extension, the tool generates a plain `&НаКлиенте`/`&НаСервере` handler where the extension actually requires an extension directive (`&Вместо`/`Instead`, `&Перед`/`Before`, `&После`/`After`, `&ИзменениеИКонтроль`/`ChangeAndValidate`) because a base handler already exists for that event. Or it generates a second procedure that collides with the base one. Or it applies the extension name prefix to the handler proc name (it must not — see below) or fails to account for the prefix on the adopted form's top-level name.

**Why it happens:**
Extension adoption (`EdtExtensionService.adoptObject`) creates an extension-specific module where procedures can override/augment base procedures, but the correct directive depends on whether the base already wires that event. There is **no centralized directive enum** — directives are raw BSL pragmas — so it's easy to emit the base-config form of the directive by default. Also, `create_metadata` auto-prepends the extension's `Configuration` name prefix to top-level object names (children excluded); a naive reuse of that logic could wrongly prefix a handler procedure name or mis-resolve the adopted form's FQN.

**How to avoid:**
Before generating, detect: (1) is the form ADOPTED in an extension (vs. a new extension-owned form)? (2) does a base handler already exist for this event (query base module)? If base handler exists and the intent is to extend it, generate the extension directive form (`&Перед`/`&После`) or `&Вместо` for replacement — the operation payload should let the caller specify the extension directive, defaulting sensibly. Handler PROCEDURE names are NOT prefixed (only top-level metadata names are); do not run them through the name-prefix logic. Resolve the adopted form's FQN using the extension's effective (prefixed) name via the existing validation surface. For extension-owned (non-adopted) forms, behave exactly like base config.

**Warning signs:**
"Procedure already defined in base module" collision; extension handler never fires (wrong directive → not linked to base event); handler proc name shows an unexpected `ар_аи_` style prefix; adopted-form FQN not found because the prefix was mis-handled.

**Phase to address:**
Dedicated **extension-support phase** (after base config works). Verification: live-EDT smoke on an adopted form — wire an event that has a base handler and one that doesn't; confirm correct directive and no collision; confirm proc name unprefixed.

---

### Pitfall 5: BM-commit vs filesystem-export separation — stub written to a stale/nonexistent module

**What goes wrong:**
The event slot is set in the BM model (committed), but the `Module.bsl` on disk isn't there yet (form just created, module not materialized) or isn't refreshed, so the stub write targets a missing/stale file — or the two land out of order and a diagnostic run sees a slot with no proc (transient false failure), or the FS write is assumed synchronous and the follow-up diagnostic runs before export completes.

**Why it happens:**
The project rule is explicit: **BM commit and filesystem export are SEPARATE phases; `forceExport`/sync is NOT guaranteed synchronous.** The existing code only ever DELETES module files; there's no precedent for the correct "ensure module exists → write proc → export → verify" ordering. A newly created form's `Module.bsl` may not exist until materialized (that's exactly what `EnsureModuleArtifactTool`/`ensureModuleArtifact` is for).

**How to avoid:**
Sequence explicitly: (1) set the event slot on the model and commit BM; (2) ensure `Module.bsl` exists (reuse `ensureModuleArtifact` semantics) BEFORE writing the stub; (3) insert the proc into the (now-existing) module; (4) trigger/await export as a SEPARATE phase; (5) refresh project and only THEN re-run diagnostics. Treat a "timed out waiting for export/derived-data" as **non-fatal** (per the known EDT derived-data caveat) — verify the artifact actually exists rather than trusting the sync return. Do not assume the same transaction covers both the model and the file.

**Warning signs:**
Intermittent "module not found"/"procedure missing" that disappears on a second run; stub written to a file that then gets overwritten by materialization; diagnostics flapping between pass/fail across runs.

**Phase to address:**
Stub-generator phase (ordering) + a verification step in every phase. Verification: create a brand-new form, wire an event on it, assert the proc lands in the materialized module after export (not before), no flapping across two runs.

---

### Pitfall 6: New operations don't round-trip the validation-token flow / schema not Qwen-flat / description over budget

**What goes wrong:**
The new `add/set/remove_event_handler` operations are added to the dispatch but the payload isn't normalized/validated through `MUTATE_FORM_MODEL`, so the single-use token round-trip breaks (validate issues a token for a payload shape the apply phase re-normalizes differently → token mismatch/consume failure). Or the operation schema uses nested objects that reduce Qwen tool-call accuracy, or the tool description grows past the ~200-char budget.

**Why it happens:**
Events reuse the SAME `MUTATE_FORM_MODEL` token op (they extend the existing tool, not add a new one), so the normalize step in `MetadataRequestValidationService.normalizeUpdateFormModelPayload()` must learn the new op shapes on BOTH the validate and consume sides identically — easy to update one and not the other. Qwen rules mandate flat schema + short description.

**How to avoid:**
Extend `normalizeUpdateFormModelPayload` once so validate and consume produce byte-identical normalized payloads for the new ops. Keep the operation schema FLAT: `{op:"add_event_handler", item_id|item_name, event, handler, [directive]}` — no nested objects. Register nothing new in `ValidationOperation` (reuse `MUTATE_FORM_MODEL`). Keep the `mutate_form_model` description under 200 chars; put the event-op detail in the Qwen priming string in `BackendToolSurfaceRewriteContributor.java` (case `"mutate_form_model"`), NOT in the tool description.

**Warning signs:**
"Validation token invalid/expired" on a well-formed second call; Qwen emits malformed nested args for event ops; the tool description string balloons; token consume throws payload-mismatch.

**Phase to address:**
The operations/validation phase. Verification: validate→apply round-trip for each new op with the returned token; a schema-flatness assertion; a description-length assertion (<200 chars).

---

### Pitfall 7: Localized vs English event identifiers in the `.form` model and BSL

**What goes wrong:**
The tool writes an English event id (`OnChange`) or English directive (`&AtClient`) into a `.form`/module whose project language is Russian (or the reverse), producing an unrecognized handler binding or a stub the platform won't link, or a mixed-language module that fails SU checks.

**Why it happens:**
The EDT form model and BSL both have localized (RU) and English identifier forms; the correct one depends on the project's script variant. The catalog in FEATURES.md lists both names precisely so it's tempting to pick the wrong one for the target project. `inspect_form_layout` today doesn't surface which the project uses.

**How to avoid:**
Detect the project script language once and emit the matching identifier set consistently for BOTH the event name in the model and the directive/keywords in the stub (`&НаКлиенте` vs `&AtClient`, `Процедура`/`КонецПроцедуры` vs `Procedure`/`EndProcedure`). Never mix. Prefer the project's existing modules as the language oracle. The event descriptor should carry both name forms; a language flag selects which to write.

**Warning signs:**
Handler unbound despite correct signature; SU diagnostic on keyword language mismatch; a module mixing `Процедура` and `Function`.

**Phase to address:**
Stub-generator phase (language selection) — same phase as signature generation. Verification: on a RU project the stub uses RU keywords/event id; on an EN project, EN; no mixed-language module.

---

### Pitfall 8: `inspect_form_layout` doesn't surface existing event handlers → non-idempotent set/remove and blind extension detection

**What goes wrong:**
Because `inspect_form_layout` currently reports items/commands but NOT event handlers, the agent can't see what's already wired, so it re-wires an event that's already bound (duplicate proc), removes the wrong thing, or in the extension case can't detect that a base handler already exists (feeding Pitfall 4).

**Why it happens:**
`InspectFormLayoutResult.FormItemNode` has no event-handler field (only commands carry `action`). The read side simply wasn't built to expose events.

**How to avoid:**
Extend `inspect_form_layout` (and its result record) to surface, per item and per form, the wired events with their handler proc names — BEFORE or alongside the mutation ops, so set/remove can read current state and be idempotent, and so extension logic can detect base handlers. This is a milestone-committed requirement, not optional.

**Warning signs:**
Duplicate handler procs after repeated set; the agent "can't tell" what's wired and asks the user; extension wiring picks the wrong directive because base handlers were invisible.

**Phase to address:**
An inspection-surfacing phase, ordered BEFORE (or with) the set/remove-idempotency work so the latter can depend on it.

---

## Technical Debt Patterns

| Shortcut | Immediate Benefit | Long-term Cost | When Acceptable |
|---|---|---|---|
| Write the stub via generic `EditFileTool`/`WriteTool` text append instead of a BSL-aware insertion | Ships fast; no new BSL-mutation primitive | Text-append can land inside another proc, break on CRLF/BOM, or duplicate on re-run; no structural idempotency | Acceptable for the FIRST slice IF the insertion is guarded (parse module, insert at module-methods region, verify no dup) — otherwise never |
| Copy the command path (set model name, skip BSL) and defer stub generation | Reuses proven wiring | Recreates Pitfall 2 by design — every wired event is an orphan | Never — stub generation is the whole point of the milestone |
| Hardcode English (or Russian) identifiers | Simpler generator | Breaks on projects of the other language (Pitfall 7) | Only in a spike; never in shipped code |
| Emit a fixed `(Элемент)` signature for all item events | One template | Wrong for out-param events (Pitfall 3) | Never |
| Treat `forceExport`/sync as synchronous and diagnose immediately | Fewer steps | Flapping false failures (Pitfall 5) | Never |

## Integration Gotchas

| Integration | Common Mistake | Correct Approach |
|---|---|---|
| EDT form EMF model | Assuming a ready-made `FormItem.getEventHandlers()` collection exists like `Form.getFormCommands()` | The event-handler container may need EMF dynamic feature access or a wrapper; verify the exact EClass/feature before coding (the sibling researcher owns `edt-javadoc` — get the API names from them, don't call it concurrently) |
| `Module.bsl` materialization | Writing a proc before the module file exists (new form) | Reuse `ensureModuleArtifact` to guarantee the file, THEN insert |
| Validation-token store | Updating normalize on the apply side only | Update `normalizeUpdateFormModelPayload` so validate and consume are byte-identical |
| Qwen priming | Editing a nonexistent `QwenToolCallExamples.inferExampleParams()` | Edit the `"mutate_form_model"` case in `BackendToolSurfaceRewriteContributor.java` |
| Extension modules | Applying the config name prefix to handler proc names | Prefix applies to top-level metadata names only; proc names are never prefixed |
| BslSemanticService | Expecting a `insertMethod`/mutation API | It's read-only; stub insertion is greenfield — build it, don't look for it |

## Security / Safety Mistakes

| Mistake | Risk | Prevention |
|---|---|---|
| Auto-deleting a non-empty handler body on `remove_event_handler`/rename | Destroys developer-written logic | Only clear the model slot; leave the proc (mark `// orphaned`) unless empty and generated-by-us |
| Silently overwriting an existing proc of the same name on `set` | Clobbers hand-written code | Idempotency check: if proc exists, do not overwrite its body; only ensure the signature/directive match, else surface a conflict |
| Editing `.form`/`.mdo` XML or `Module.bsl` as raw text as the PRIMARY mechanism | Violates the "no primary model text-patching" rule; desyncs BM from disk | Set events via the EDT form model API; write BSL through a guarded, verified insertion; keep BM-commit and FS-export as separate, checked phases |

## UX Pitfalls

| Pitfall | User Impact | Better Approach |
|---|---|---|
| Modeling button-click as an "event" | Two wiring paths for one action; duplicate procs | Reject `owner=button`; redirect to `add_command` (buttons already covered) |
| Generating a "helpful" stub body | Wrong logic looks authoritative; hides that the dev must still implement | Empty stub with `// TODO`; correct signature + directive only |
| Wiring "all events" on an item at once | Module bloat, orphan slots | One operation = one event = one stub |
| Cryptic error when an event isn't in the descriptor table | Agent guesses and generates a bad stub | Fail with the list of supported events for that owner (mirror the existing "available kinds" pattern) |

## "Looks Done But Isn't" Checklist

- [ ] **Directive correctness:** Often missing the client-only-table rule — verify `ПриАктивизацииСтроки` got `&НаКлиенте`, not `&НаСервере`.
- [ ] **Signature fidelity:** Often missing out-params — verify `НачалоВыбора`/`ОбработкаВыбора`/`ПередЗакрытием`/`АвтоПодбор` stubs include `СтандартнаяОбработка` and all params, string-equal to the descriptor.
- [ ] **Atomicity:** Often missing the "both or neither" guarantee — verify a wire leaves NO orphan slot and NO orphan proc.
- [ ] **Idempotency:** Often missing re-run safety — verify wiring the same event twice yields one proc, not two.
- [ ] **Export separation:** Often missing — verify the proc exists in the materialized module AFTER export, with no flapping across two diagnostic runs.
- [ ] **Extension directive:** Often missing base-handler detection — verify an adopted form with an existing base handler gets `&Перед`/`&После`/`&Вместо`, not a colliding plain handler; proc name unprefixed.
- [ ] **Inspection surfacing:** Often missing — verify `inspect_form_layout` reports the newly wired event + handler name.
- [ ] **Language:** Often missing — verify a RU project gets RU keywords/event ids and no mixed-language module.
- [ ] **Token round-trip:** Often missing symmetric normalize — verify validate→apply succeeds for each new op.
- [ ] **Qwen priming:** Often missing — verify the `BackendToolSurfaceRewriteContributor` `mutate_form_model` case documents the event ops, and the tool description stays <200 chars.
- [ ] **Button rejection:** Verify `owner=button` is refused with a redirect to `add_command`.

## Recovery Strategies

| Pitfall | Recovery Cost | Recovery Steps |
|---|---|---|
| Orphan proc / dangling slot (Pitfall 2) | LOW | Re-run inspect (once it surfaces events); remove the orphan slot or regenerate the missing proc; add the idempotency guard so it can't recur |
| Wrong directive/signature shipped (Pitfalls 1,3) | MEDIUM | Regenerate the stub from the descriptor; if the dev already filled the body, only rewrite the header line + params, preserve the body |
| Stub written to stale/missing module (Pitfall 5) | MEDIUM | Ensure module artifact, re-insert, force export, refresh, re-diagnose; verify artifact existence rather than trusting sync return |
| Extension collision / wrong directive (Pitfall 4) | HIGH | Live-EDT: identify base handler; convert the extension proc to the correct directive or remove the duplicate; re-adopt if the module state is inconsistent |
| Token round-trip broken (Pitfall 6) | LOW | Align validate/consume normalize; re-issue token; add the symmetric-normalize test |

## Pitfall-to-Phase Mapping

| Pitfall | Prevention Phase | Verification |
|---|---|---|
| 1 — Wrong directive | Directive-table + stub-generator phase | Diagnostics clean for client/reserved-server/client-only-table samples |
| 2 — Handler↔method desync / orphans | Stub-generator + remove/rename phase | Wire→both exist & names match; remove→no dangling; re-wire→single proc |
| 3 — Wrong signature | Stub-generator phase | Per-event golden signature test; no "param count" diagnostic |
| 4 — Extension directives/adoption | Extension-support phase | Live-EDT adopted-form smoke: correct directive, no collision, unprefixed proc |
| 5 — BM/FS export separation | Stub-generator phase (ordering) + all phases | New-form wire lands post-export, no flapping across 2 runs |
| 6 — Token/schema/description | Operations + validation phase | Validate→apply round-trip; flat-schema + <200-char assertions |
| 7 — Localized identifiers | Stub-generator phase | RU project → RU keywords/ids; EN → EN; no mixed module |
| 8 — Inspection surfacing | Inspection-surfacing phase (before set/remove idempotency) | `inspect_form_layout` reports wired events + handler names |

## Sources

- CodePilot1C OSS codebase reconnaissance (this session): `MutateFormModelTool.java`, `EdtMetadataService.java` (command wiring ~1198–1203, dispatch ~821–1036, module cleanup ~9780, `ensureModuleArtifact` ~3251), `BslSemanticService.java` (read-only API, pragmas ~979–997), `MetadataRequestValidationService.java` / `ValidationOperation.java`, `InspectFormLayoutTool.java` / `InspectFormLayoutResult.java`, `EdtExtensionService.java`, `EnsureModuleArtifactTool.java`, `BackendToolSurfaceRewriteContributor.java` (actual Qwen priming location).
- Project rules: `CLAUDE.md` / `AGENTS.md` (BM-commit vs FS-export separation; re-run diagnostics; Qwen flat-schema + <200-char description; no primary `.mdo`/model text-patching).
- Project memory: EDT derived-data timeout = non-fatal false failure; EDT extension name-prefix (SU189) caveat (top-level names only); EDT diagnostics architecture.
- 1C platform managed-form event model (platform-stable 8.3) for directive/context/signature facts — cross-referenced in FEATURES.md.

---
*Pitfalls research for: 1C managed-form event-handler wiring (v0.1.10)*
*Researched: 2026-07-13*
