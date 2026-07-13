# Stack Research — EDT Managed-Form Event-Handler API Surface

**Domain:** Eclipse RCP/OSGi plugin for 1C:EDT — EMF form-model + BSL-module mutation
**Researched:** 2026-07-13
**Confidence:** HIGH (EDT EMF names verified against `edt-javadoc` MCP; BSL-generation gap explicitly flagged)

> Scope: what EDT EMF types/methods/enums the v0.1.10 "Managed Form Event Handlers" milestone
> must use. Every EDT name below is tagged **VERIFIED** (confirmed in edt-javadoc) or **UNVERIFIED**.
> This is not an npm stack; the "technologies" are EDT EMF packages already on the bundle classpath.

---

## Core Technologies (EDT EMF packages)

| Package | Purpose | Status | On classpath? |
|---------|---------|--------|---------------|
| `com._1c.g5.v8.dt.form.model` | Form/item EMF model + `FormFactory` + `EventHandler(Container)` | **VERIFIED** | Yes (MANIFEST L56) |
| `com._1c.g5.v8.dt.form.service` | `FormItemInformationService` — allowed-events + existing-handlers | **VERIFIED** | **NO — must add Import-Package** |
| `com._1c.g5.v8.dt.mcore` | `Event`, `AbstractMethod`, `ParamSet`, `Parameter`, `Environmental` | **VERIFIED** | Yes (MANIFEST L75) |
| `com._1c.g5.v8.dt.mcore.util` | `Environments` (client/server sets → directive) | **VERIFIED** | Yes (MANIFEST L76) |
| `com._1c.g5.v8.dt.bsl.model` | `BslFactory`, `Module`, `Procedure`, `Pragma` (only if AST-injection chosen) | **VERIFIED** | Yes (MANIFEST L65) |
| `com._1c.g5.v8.dt.platform.version` | `Version` — arg to `getAllowedEventNames` | **VERIFIED** | Yes (MANIFEST L55) |

**Build note:** adding `com._1c.g5.v8.dt.form.service` to `Import-Package` is the one required MANIFEST change
(new cross-bundle package → follow the "new cross-bundle package needs Export/Import-Package" build rule).
If you avoid `FormItemInformationService` and enumerate events by EClass yourself, you skip this import — but the
service is the sanctioned source of allowed events (incl. ExtInfo-contributed events), so importing it is recommended.

---

## The Event-Handler Model (VERIFIED)

### Where handlers live: `EventHandlerContainer`

`com._1c.g5.v8.dt.form.model.EventHandlerContainer` — **VERIFIED**
- `EList<EventHandler> getHandlers()` — the containment list of active handlers.

The following form-model types **implement `EventHandlerContainer`** (so `x.getHandlers()` is valid):

| Item EClass | Implements EventHandlerContainer? | Status | Notes |
|-------------|-----------------------------------|--------|-------|
| `Form` | **YES** | **VERIFIED** | Form-level events (OnCreateAtServer, OnOpen, …) |
| `FormField` | **YES** | **VERIFIED** | Field events (OnChange, StartChoice, …) |
| `Table` | **YES** | **VERIFIED** | Table events (OnActivateRow, BeforeAddRow, …) |
| `FormGroup` | **NO** | **VERIFIED** | Does NOT implement it → groups carry no standard event handlers |
| `Button` | **NO** | **VERIFIED** | Buttons use commands (`FormCommandHandlerContainer`), not events |

> **Scoping consequence:** the milestone's "groups/buttons where applicable" reduces to *no standard event
> handlers on Button/FormGroup*. Buttons wire behavior via commands (existing `add_command`/`add_button` path,
> `FormCommandHandlerContainer` + `CommandHandler`). Keep event ops limited to Form / FormField / Table.
> (Some specialized ExtInfo types may expose extra events — see `getAllowedEvents` below — but the *containment*
> still requires the owning object to be an `EventHandlerContainer`, which for standard managed forms is Form/Field/Table.)

### The handler object: `EventHandler`

`com._1c.g5.v8.dt.form.model.EventHandler extends IBmObject` — **VERIFIED**
- `Event getEvent()` / `void setEvent(Event)` — reference to the event being handled. **VERIFIED**
- `String getName()` / `void setName(String)` — the **module handler method name** (the BSL procedure to call). **VERIFIED**

Created via `FormFactory.eINSTANCE.createEventHandler()` → `EventHandler`. **VERIFIED**

### Extension-adopted forms: `EventHandlerExtension`

`com._1c.g5.v8.dt.form.model.EventHandlerExtension extends EventHandler` — **VERIFIED**
- adds `ExtendedMethodCallType getCallType()` / `setCallType(ExtendedMethodCallType)`. **VERIFIED**
- Created via `FormFactory.eINSTANCE.createEventHandlerExtension()` → `EventHandlerExtension`. **VERIFIED**

`ExtendedMethodCallType` (enum) literals — **VERIFIED**:
`BEFORE`, `AFTER`, `OVERRIDE` (Instead/«Вместо»), `CHANGE_AND_VALIDATE`.

> **Base-vs-extension rule (API level):** for a form in the base configuration, create a plain `EventHandler`.
> For a form *inside a 1C extension that adopts a base form* (adoptable form), EDT uses `EventHandlerExtension`
> with a `CallType` (interception mode). Default `CallType` for a fresh handler should mirror EDT (`BEFORE` is the
> common default; confirm live). Related linking helper: `BslFormEventHandlerService.getCorrespondingEventContainerHandler(...)`
> (package `com._1c.g5.v8.dt.bsl.extension`) — **VERIFIED** — maps an adoptable-form container to the extension-form container.

### The event itself: `Event` (NOT an enum)

`com._1c.g5.v8.dt.mcore.Event extends AbstractMethod, Environmental` — **VERIFIED**

**Critical:** `Event` is a *model object*, not a Java enum. Events are not free-form literals you `setEvent(SOME_ENUM)`;
you must obtain the concrete `Event` instance that belongs to the target item and match by name.

- Name: via `DuallyNamedElement` → `String getName()` (EN, e.g. `"OnChange"`) + `String getNameRu()` (RU, e.g. `"ПриИзменении"`). **VERIFIED**
- Signature: via `AbstractMethod` → `EList<ParamSet> getParamSet()`; each `ParamSet.getParams()` → `EList<Parameter>`. **VERIFIED**
  - `Parameter` (mcore) → `getName()`/`getNameRu()`, `getType()`, `isOut()`, `isDefaultValue()`. **VERIFIED**
- Client/server nature: via `Environmental` → `Environments getEnvironments()`. **VERIFIED**

### Enumerating valid events per item: `FormItemInformationService`

`com._1c.g5.v8.dt.form.service.FormItemInformationService` (class, no-arg ctor) — **VERIFIED**
- `List<Event> getAllowedEvents(FormVisualEntity item)` — allowed events for a Form/FormField/Table (incl. ExtInfo + extension events). **VERIFIED**
- `Set<String> getAllowedEventNames(EClass eClass, Version version)` — allowed event *names* by item EClass + platform version. **VERIFIED**
- `List<EventHandler> getEventHandlers(EObject object)` — **active** handlers on an object (use for `inspect_form_layout`). **VERIFIED**
- `getAllowedIndexedEvents(...)`, `getAllowedContextDefItem(...)` — auxiliary. **VERIFIED**

> This service is the sanctioned way to (a) validate a requested event name and (b) resolve the concrete `Event`
> instance to pass to `EventHandler.setEvent(...)`. Match requested event by `getName()`/`getNameRu()` (case-insensitive)
> against `getAllowedEvents(item)`.

### Representative event literals (for docs/UX — spellings to confirm live)

The *mechanism* is verified; the exact per-item literal **spellings below are UNVERIFIED** (edt-javadoc exposes the
`Event`-model machinery, not a static literal table). Resolve real spellings at runtime via `getAllowedEvents`/`getAllowedEventNames`.

- **Form-level (UNVERIFIED spellings):** `OnCreateAtServer`/«ПриСозданииНаСервере», `OnOpen`/«ПриОткрытии»,
  `BeforeClose`/«ПередЗакрытием», `OnClose`/«ПриЗакрытии», `OnReopen`/«ПриПовторномОткрытии»,
  `NotificationProcessing`/«ОбработкаОповещения», `BeforeWrite`/«ПередЗаписью», `OnReadAtServer`/«ПриЧтенииНаСервере».
- **Field-level (UNVERIFIED spellings):** `OnChange`/«ПриИзменении», `StartChoice`/«НачалоВыбора»,
  `ChoiceProcessing`/«ОбработкаВыбора», `AutoComplete`/«АвтоПодбор», `Opening`/«Открытие», `Clearing`/«Очистка».
- **Table-level (UNVERIFIED spellings):** `OnActivateRow`/«ПриАктивизацииСтроки», `BeforeAddRow`/«ПередНачаломДобавления»,
  `BeforeRowChange`/«ПередНачаломИзменения», `OnActivateCell`/«ПриАктивизацииЯчейки», `Selection`/«Выбор».

---

## BSL Handler-Stub Generation (the hard part)

### The directive enum (VERIFIED, but UI-package)

`com._1c.g5.v8.dt.bsl.ui.event.ProcedureDirective` (enum) — **VERIFIED**
- literals: `AT_CLIENT` (`&НаКлиенте`), `AT_SERVER` (`&НаСервере`), `AT_SERVER_NO_CONTEXT` (`&НаСервереБезКонтекста`).
- `getDirectiveAnnotation(boolean isRussian)`, `getSuffix(boolean isRussian)`. **VERIFIED**

`com._1c.g5.v8.dt.bsl.ui.event.ProcedureParameters` (class) — **VERIFIED** — the native "insert procedure into module"
parameter store (directive + annotation + prefix/suffix).

> **⚠ BLOCKING CONSTRAINT:** both `ProcedureDirective` and `ProcedureParameters` — and the native handler-insertion
> logic (`GotoEventHandlerHandler.getProceduresParameters(...)`) — live in **`com._1c.g5.v8.dt.bsl.ui` / `com._1c.g5.v8.dt.form.ui`**.
> `core` MUST stay independent of workbench UI (project rule). Therefore **we cannot reuse EDT's native procedure
> generator from `core`.** There is **no verified headless (non-UI) EDT service that emits handler procedure text.**
> `BslFormEventHandlerService` (`.bsl.extension`, non-UI) only *finds/links* handlers — it does not generate them.

### Consequence: we generate the stub ourselves (text-based)

`core` must produce the procedure text itself. Inputs are all VERIFIED/available headlessly:
- procedure **name** = `EventHandler.getName()` (chosen handler name).
- **directive** = derived from `Event.getEnvironments()` (`Environments.contains(SERVER)` → `&НаСервере` /
  `&НаСервереБезКонтекста`; client → `&НаКлиенте`). `Environments` constants `SERVER`, `ALL_CLIENTS`, `ALL_SERVERS`, `ALL` — **VERIFIED**.
- **parameters** = from `Event.getParamSet().get(0).getParams()` → each `Parameter.getNameRu()` (RU script) / `getName()`. **VERIFIED**.
- Script variant (RU vs EN keywords) = `Configuration.getScriptVariant()` (`ScriptVariant`, already used in codebase). **VERIFIED (in-repo usage)**.

`BslFactory` (`.bsl.model`) can create `Procedure`/`Pragma`/`FormalParam` for an **AST-based** alternative — **VERIFIED** —
but serializing a hand-built AST back to `.bsl` text via Xtext headlessly is fragile; **text generation is recommended** (see ARCHITECTURE.md).

---

## Handler ↔ module-method reconciliation (VERIFIED, informational)

EDT reconciles the form-model handler name against the module method via reconcile participants, e.g.
`FormBslBmReconcileParticipantEventsUpdater` (internal.ui) and typesystem providers
`FormItemEventsProvider.getEventHandlersContainer(Module)` / `BslEventsService.getEventHandlersContainer(Module)`
(`.bsl.resource`) — **VERIFIED**. These map `handlerName → EObject` after both sides exist. We do **not** call these;
we just guarantee both sides are written (form-model `EventHandler.name` == the generated procedure name) so EDT's
own reconcile/validation is satisfied. If names diverge, EDT raises a "handler not found" form diagnostic — so name
consistency is a correctness requirement, verified via re-run diagnostics (project Verification Rule).

---

## What NOT to use

| Avoid | Why | Use instead |
|-------|-----|-------------|
| `com._1c.g5.v8.dt.bsl.ui.event.ProcedureDirective` / `ProcedureParameters` as runtime deps | UI package → breaks `core`-independent-of-UI rule | Own directive constants + own text generator (mirror literals) |
| `GotoEventHandlerHandler.*` (form.ui.commands) | UI command, needs `Shell`/`ExecutionEvent` | Headless generation in `EdtMetadataService` |
| Treating `Event` as a Java enum / `setEvent(LITERAL)` | `Event` is an EMF model object per item | Resolve via `FormItemInformationService.getAllowedEvents(item)` and match by name |
| Editing `.form`/`.mdo` XML directly to add handlers | Violates "no primary `.mdo` XML edit" rule | BM mutation of `EventHandlerContainer.getHandlers()` |
| Building the BSL body as an AST then serializing | Xtext headless serialization fragile; `ensureModuleArtifact` is already text-based | Append well-formed procedure text to `Module.bsl` |

---

## Stack Patterns by Variant

**If target is a base-configuration form:**
- `FormFactory.createEventHandler()`, add to `container.getHandlers()`. No `CallType`.

**If target is an extension-adopted form (расширение):**
- `FormFactory.createEventHandlerExtension()` with `setCallType(ExtendedMethodCallType.BEFORE)` (confirm default live).
- Container is the extension-form's `EventHandlerContainer`; use `BslFormEventHandlerService` semantics if mapping from the base container.

**If the requested event name is unknown:**
- Validate against `getAllowedEvents(item)` (or `getAllowedEventNames(eClass, version)`); return an actionable
  "unsupported event; available: [...]" error (mirrors v0.1.9 "list actionable alternatives" decision).

---

## Version Compatibility

| Component | Compatible With | Notes |
|-----------|-----------------|-------|
| `getAllowedEventNames(EClass, Version)` | `com._1c.g5.v8.dt.platform.version.Version` | Allowed events are version-gated; pass the project's platform Version. **VERIFIED type; wiring UNVERIFIED** |
| `EventHandlerExtension.CallType` | extension/adoptable forms only | Base forms use plain `EventHandler` (no CallType). **VERIFIED** |

---

## Sources

- `edt-javadoc` MCP (source of truth) — VERIFIED, called sequentially, no connection errors:
  `EventHandler`, `EventHandlerContainer`, `EventHandlerExtension`, `Form`, `FormField`, `Table`, `Button`,
  `FormGroup`, `FormVisualEntity`, `FormItem`, `FormFactory`, `FormItemInformationService`,
  `mcore.Event`, `AbstractMethod`, `ParamSet`, `Parameter`, `DuallyNamedElement`, `Environmental`, `Environments`,
  `ExtendedMethodCallType`, `BslFactory`, `bsl.ui.event.ProcedureDirective`, `ProcedureParameters`,
  `BslFormEventHandlerService`, `platform.version.Version`.
- Repo (integration facts): `EdtMetadataService.java` (form mutation, module-path builder), `MutateFormModelTool.java`,
  `MANIFEST.MF` (Import-Package), `BslSemanticService.java`.

---
*Stack research for: EDT managed-form event-handler API surface (v0.1.10)*
*Researched: 2026-07-13*
