# Feature Research

**Domain:** 1C managed-form event-handler wiring in an Eclipse/EDT metadata-mutation tool (CodePilot1C OSS, milestone v0.1.10 "Managed Form Event Handlers")
**Researched:** 2026-07-13
**Confidence:** HIGH (1C managed-form event model is platform-stable across 8.3; signatures below are the canonical platform signatures)

## Purpose Of This Document

Downstream this is consumed by a roadmapper + phase planner. The load-bearing artifact is the **directive-decision table**: for each event we give the Russian name, English name, **execution context** (which decides whether the generated BSL stub carries `&НаКлиенте`, `&НаСервере`, or `&НаСервереБезКонтекста`), and the **exact parameter signature** (which the stub generator must reproduce verbatim, including out-params like `СтандартнаяОбработка`).

### How execution context maps to the BSL directive

| Execution context | BSL directive (RU / EN) | Notes |
|---|---|---|
| Client | `&НаКлиенте` / `&AtClient` | Default for almost all UI/item events and most form lifecycle events. The `.form` model stores the handler name; the procedure lives in the form module. |
| Server | `&НаСервере` / `&AtServer` | The `...НаСервере` family. These are NOT independently wireable in the item-event grid — the platform invokes them by fixed name from the form's own machinery, but they still need the stub + directive generated. |
| Server without context | `&НаСервереБезКонтекста` / `&AtServerNoContext` | NEVER auto-generated for a form/item event. This is an author optimization for helper procs called from client handlers. The generator must NOT emit it for an event stub. Flagged as an anti-feature below. |

**Critical distinction the generator must encode:** two different *kinds* of "server" events exist:
1. **Fixed-name form module procedures** (`ПриСозданииНаСервере`, `ПриЧтенииНаСервере`, `ПриЗаписиНаСервере`, `ПередЗаписьюНаСервере`, `ОбработкаПроверкиЗаполненияНаСервере`) — the platform calls these by their exact reserved name. They are attached to the FORM but are **not** stored as an assignable "event handler" string in the `.form` XML the way `ПриОткрытии` is; EDT surfaces them in the form's event list but their identity is the reserved name. Stub gets `&НаСервере`.
2. **Assignable client events** (`ПриОткрытии`, `ПередЗакрытием`, item `ПриИзменении`, …) — stored as a handler-name attribute on the form/item event slot. Stub gets `&НаКлиенте`.

This 1-vs-2 split is the single biggest correctness driver and must be a per-event flag in the context table, not inferred from the name suffix alone.

---

## Feature Landscape

Below, "Feature" = one wireable event (or event group). Complexity is the cost to support it in the stub generator + model mutation, not the cost to the end user.

### Table Stakes (Users Expect These)

Missing any of these makes the feature feel broken — these are the events a developer wires every day.

#### Form-level events (owner = `Form`)

| Event (RU / EN) | Context → directive | Signature (params) | Complexity | Notes |
|---|---|---|---|---|
| `ПриСозданииНаСервере` / `OnCreateAtServer` | Server → `&НаСервере` | `(Отказ, СтандартнаяОбработка)` | MEDIUM | Reserved-name kind (#1). Two out-params. The most-used form event. `Отказ`=Boolean, `СтандартнаяОбработка`=Boolean. |
| `ПриОткрытии` / `OnOpen` | Client → `&НаКлиенте` | `(Отказ)` | LOW | Assignable kind (#2). Single out-param `Отказ`. |
| `ПередЗакрытием` / `BeforeClose` | Client → `&НаКлиенте` | `(Отказ, ЗавершениеРаботы, ТекстПредупреждения, СтандартнаяОбработка)` | MEDIUM | 4 params, 3 are out/inout. Non-trivial signature — generator must match exactly. |
| `ПриЗакрытии` / `OnClose` | Client → `&НаКлиенте` | `(ЗавершениеРаботы)` | LOW | Single in-param (Boolean). |
| `ПриПовторномОткрытии` / `OnReopen` | Client → `&НаКлиенте` | `()` | LOW | No params. |
| `ОбработкаОповещения` / `NotificationProcessing` | Client → `&НаКлиенте` | `(ИмяСобытия, Параметр, Источник)` | LOW | 3 in-params. Backbone of `Оповестить`/`НачатьВыполнение` async patterns. |
| `ПередЗаписью` / `BeforeWrite` | Client → `&НаКлиенте` | `(Отказ, ПараметрыЗаписи)` | LOW | Client-side pre-write. Only on object/record-set forms (has a `Записать` command). |
| `ПриЗаписиНаСервере` / `OnWriteAtServer` | Server → `&НаСервере` | `(Отказ, ТекущийОбъект, ПараметрыЗаписи)` | MEDIUM | Reserved-name kind (#1). `ТекущийОбъект` is the mutable object. Only on data-bound forms. |
| `ПослеЗаписи` / `AfterWrite` | Client → `&НаКлиенте` | `(ПараметрыЗаписи)` | LOW | Client post-write. |

#### Field / input events (owner = `FormField`, table-column fields included)

| Event (RU / EN) | Context → directive | Signature (params) | Complexity | Notes |
|---|---|---|---|---|
| `ПриИзменении` / `OnChange` | Client → `&НаКлиенте` | `(Элемент)` | LOW | THE most-used item event. `Элемент` = the form item. |
| `НачалоВыбора` / `StartChoice` | Client → `&НаКлиенте` | `(Элемент, ДанныеВыбора, СтандартнаяОбработка)` | MEDIUM | `СтандартнаяОбработка` out-param — set `Ложь` to override system choice. Non-trivial. |
| `НачалоВыбораИзСписка` / `StartListChoice` | Client → `&НаКлиенте` | `(Элемент, СтандартнаяОбработка)` | LOW | Out-param `СтандартнаяОбработка`. |
| `ОбработкаВыбора` / `ChoiceProcessing` | Client → `&НаКлиенте` | `(Элемент, ВыбранноеЗначение, СтандартнаяОбработка)` | MEDIUM | Item-level (distinct from form-level `ОбработкаВыбора`). Out-param. |
| `Очистка` / `Clearing` | Client → `&НаКлиенте` | `(Элемент, СтандартнаяОбработка)` | LOW | Out-param `СтандартнаяОбработка`. |
| `ОкончаниеВводаТекста` / `TextEditEnd` | Client → `&НаКлиенте` | `(Элемент, Текст, ЗначениеВыбрано, СтандартнаяОбработка)` | MEDIUM | 4 params, 2 out. Non-trivial. |
| `АвтоПодбор` / `AutoComplete` | Client → `&НаКлиенте` | `(Элемент, Текст, ДанныеВыбора, Параметры, Ожидание, СтандартнаяОбработка)` | HIGH | 6 params, several out. Most complex field signature — flag for the generator. |
| `Открытие` / `Opening` | Client → `&НаКлиенте` | `(Элемент, СтандартнаяОбработка)` | LOW | Fires on the "open value" (F4/lens) action. Out-param. |

#### Table (dynamic list / value table) events (owner = `FormTable`)

| Event (RU / EN) | Context → directive | Signature (params) | Complexity | Notes |
|---|---|---|---|---|
| `ПриАктивизацииСтроки` / `OnActivateRow` | Client → `&НаКлиенте` | `(Элемент)` | LOW | Client-only by rule — the platform forbids `&НаСервере` form methods here; a wrong directive causes a diagnostic. |
| `ПередНачаломДобавления` / `BeforeAddRow` | Client → `&НаКлиенте` | `(Элемент, Отказ, Копирование, Родитель, Группа, Параметр)` | HIGH | 6 params, `Отказ`/`Копирование`/`Родитель`/`Группа` matter for hierarchy. Signature varies by platform edition — verify against target platform. |
| `ПередНачаломИзменения` / `BeforeRowChange` | Client → `&НаКлиенте` | `(Элемент, Отказ)` | LOW | Out-param `Отказ`. |
| `ПередУдалением` / `BeforeDeleteRow` | Client → `&НаКлиенте` | `(Элемент, Отказ)` | LOW | Out-param `Отказ`. |
| `ПриИзменении` / `OnChange` | Client → `&НаКлиенте` | `(Элемент)` | LOW | Same shape as field `OnChange` but owner is the table. |
| `Выбор` / `Selection` | Client → `&НаКлиенте` | `(Элемент, ВыбраннаяСтрока, Поле, СтандартнаяОбработка)` | MEDIUM | Double-click / Enter on a row. Out-param `СтандартнаяОбработка`. |
| `ПриАктивизацииЯчейки` / `OnActivateCell` | Client → `&НаКлиенте` | `(Элемент)` | LOW | Fires on cell focus. |

### Differentiators (Rarer, But Real Coverage Wins)

Support these once the table-stakes stub generator is solid. They exercise the same machinery, so incremental cost is low, and covering them means "any event the developer sees in the EDT property grid, the agent can wire."

| Event (RU / EN) | Owner | Context → directive | Signature | Complexity | Notes |
|---|---|---|---|---|---|
| `ПриЧтенииНаСервере` / `OnReadAtServer` | Form | Server → `&НаСервере` | `(ТекущийОбъект)` | MEDIUM | Reserved-name kind (#1). Fires only for existing objects. |
| `ПередЗаписьюНаСервере` / `BeforeWriteAtServer` | Form | Server → `&НаСервере` | `(Отказ, ТекущийОбъект, ПараметрыЗаписи)` | MEDIUM | Reserved-name kind (#1). |
| `ОбработкаПроверкиЗаполненияНаСервере` / `FillCheckProcessingAtServer` | Form | Server → `&НаСервере` | `(Отказ, ПроверяемыеРеквизиты)` | MEDIUM | Reserved-name kind (#1). |
| `ОбработкаВыбора` (form-level) / `ChoiceProcessing` | Form | Client → `&НаКлиенте` | `(ВыбранноеЗначение, ИсточникВыбора)` | MEDIUM | Distinct from item-level `ОбработкаВыбора` (different signature). Fires when THIS form was opened as a choice form and the child returns a value. Name collision with the field event — generator must key on owner, not name. |
| `ПриИзмененииДанныхВладельца` / `OnOwnerDataChange` | Form | Client → `&НаКлиенте` | `()` (varies; treat as `(Объект)` on subordinate forms) | MEDIUM | Rare; used on subordinate/linked forms. Verify signature against platform before generating. |
| `ОбработкаНавигационнойСсылки` / `URLProcessing` | Decoration (label), FormField | Client → `&НаКлиенте` | `(Элемент, НавигационнаяСсылкаФорматированнойСтроки, СтандартнаяОбработка)` | MEDIUM | On labels/decorations with formatted-string links and on hyperlink fields. Out-param. |
| `Нажатие` / `Click` | Decoration (hyperlink label), hyperlink FormField | Client → `&НаКлиенте` | `(Элемент)` | LOW | Click on a hyperlink decoration or hyperlink-styled field. NOT a button — see anti-features. |
| `Перетаскивание` / `Drag`, `НачалоПеретаскивания` / `DragStart`, `ПроверкаПеретаскивания` / `DragCheck`, `ОкончаниеПеретаскивания` / `DragEnd` | FormTable, FormField | Client → `&НаКлиенте` | `(Элемент, ПараметрыПеретаскивания, СтандартнаяОбработка, …)` | HIGH | Drag-and-drop family. Multi-param, several out-params. Low demand; defer. |
| `ПередРазворачиванием` / `BeforeExpand`, `ПередСворачиванием` / `BeforeCollapse` | FormTable (tree/hierarchical) | Client → `&НаКлиенте` | `(Элемент, Строка, СтандартнаяОбработка)` | MEDIUM | Only meaningful on hierarchical dynamic lists / trees. |
| `ПриПолученииДанныхНаСервере` / `OnGetDataAtServer` | FormTable (dynamic list) | Server without context → `&НаСервереБезКонтекста` | `(ИмяЭлемента, Настройки, Строки)` | HIGH | **The one legitimate `&НаСервереБезКонтекста` event.** Dynamic-list row-formatting hook. If supported, this is the ONLY case where the generator emits `&НаСервереБезКонтекста` for an event stub — treat as a special-cased exception, not the default. Defer to v2. |

### Anti-Features (Skip / Do Not Generate)

| Feature | Why Requested | Why Problematic | Alternative |
|---|---|---|---|
| Button `Нажатие` / button Click as an "event" | Feels symmetric with other item clicks | Button clicks are **commands**, already covered by `add_command` (FormCommand → CommandHandler → procedure). Modeling a button click as an event would create a duplicate wiring path and two procedures for one action. | Explicitly document: button actions go through `add_command`; the event tool refuses `owner=FormButton` and points the caller to `add_command`. |
| Emitting `&НаСервереБезКонтекста` as a general event-stub directive | "Server events should be no-context for perf" | For actual form events (`ПриСозданииНаСервере`, `ПриЗаписиНаСервере`, …) the platform passes context-bound data (`ТекущийОбъект`, form attributes); `&НаСервереБезКонтекста` would break them and not compile against the event contract. | Only ever emit `&НаСервере` for reserved server events. Reserve `&НаСервереБезКонтекста` exclusively for the `ПриПолученииДанныхНаСервере` dynamic-list exception (v2), never as a default. |
| Auto-inventing handler names / auto-wiring "all events" on an item | "Just wire everything so I don't have to ask" | Orphan procedures, module bloat, and event slots pointing at empty stubs; also makes idempotency/rename brittle. | Wire exactly the events the caller names; one operation = one event = one stub. |
| Editing `.form` XML or `Module.bsl` as raw text to attach the handler | Simpler than the EMF/BSL API | Violates the project's "no primary `.mdo`/model text-patching" rule; loses export/sync post-checks; desyncs the BM model from disk. | Use the EDT form model API to set the event handler and the BSL insertion mechanism used for commands; keep BM-commit and FS-export as separate phases. |
| Generating a body (logic) inside the stub | "Make it do something useful" | The agent can't know intent; a wrong body is worse than an empty stub and hides that the developer still must implement. | Generate an empty, correctly-signed, correctly-directived stub with a `// TODO` marker; leave the body to the developer/agent's next turn. |

---

## Feature Dependencies

```
[Directive-decision table (event → context → signature)]
    └──requires──> [Owner-kind classifier (Form vs FormField vs FormTable vs Decoration vs Button-reject)]
                       └──requires──> [Reserved-name vs assignable-slot distinction]

[BSL stub generator]
    └──requires──> [Directive-decision table]
    └──requires──> [Existing command BSL-insertion mechanism (reuse from add_command)]

[mutate_form_model event operations (add/set/remove event handler)]
    └──requires──> [BSL stub generator]  (atomic: model-wire + stub in one apply)
    └──requires──> [Existing validation-token two-phase flow]

[Extension (расширения) support]
    └──enhances──> [mutate_form_model event operations]
    └──requires──> [Extension adopted-form/module semantics + directive annotations (&Вместо/&Перед/&После/&ИзменениеИКонтроль)]

[inspect_form_layout surfaces existing handlers]
    └──enhances──> [mutate_form_model event operations]  (read current state → idempotent set/remove)

[Qwen tool-call examples for new operations]
    └──requires──> [Final operation names + flat schema]  (QwenToolCallExamples.inferExampleParams)
```

### Dependency Notes

- **Stub generator requires the directive table:** the directive and the exact parameter list are data, not code branches. Model it as a per-event descriptor so adding differentiator events later is a data edit, not a code change.
- **Event operations require atomic wire+stub:** setting the event slot without generating the stub (or vice versa) produces the #1 pitfall (handler↔method desync). The apply phase must do both or neither.
- **Extension support enhances base support:** the base (config) path must exist and be solid first; extension adoption adds directive-annotation and adopted-module concerns on top.
- **inspect surfacing enhances set/remove:** without reading existing handlers, `set`/`remove` can't be idempotent and can't detect the "base handler already exists" extension case.
- **Qwen examples require frozen operation names:** finalize the operation vocabulary (e.g. `add_event_handler`) before writing examples; renaming later invalidates the priming XML.

---

## MVP Definition

### Launch With (v1) — the milestone's committed scope

- [ ] **Owner-kind classifier + directive-decision table** — the data model that maps every table-stakes event to (owner, context, directive, signature). Essential: everything else reads from it.
- [ ] **Form-level table-stakes events** — `ПриСозданииНаСервере`, `ПриОткрытии`, `ПередЗакрытием`, `ПриЗакрытии`, `ПриПовторномОткрытии`, `ОбработкаОповещения`, `ПередЗаписью`, `ПриЗаписиНаСервере`, `ПослеЗаписи`. Covers the daily form lifecycle.
- [ ] **Field table-stakes events** — `ПриИзменении`, `НачалоВыбора`, `ОбработкаВыбора`, `Очистка`, `НачалоВыбораИзСписка`, `ОкончаниеВводаТекста`, `Открытие`. Covers input handling.
- [ ] **Table table-stakes events** — `ПриАктивизацииСтроки`, `ПередНачаломДобавления`, `ПередНачаломИзменения`, `ПередУдалением`, `ПриИзменении`, `Выбор`, `ПриАктивизацииЯчейки`.
- [ ] **BSL stub generator** — inserts a correctly-signed, correctly-directived, empty `// TODO` procedure into `Module.bsl`, reusing the command-handler insertion path.
- [ ] **Three event operations** — `add_event_handler` / `set_event_handler` / `remove_event_handler` under the existing `MUTATE_FORM_MODEL` validation-token flow; flat Qwen-friendly schema.
- [ ] **Button-click rejection** — refuse `owner=button`, redirect to `add_command`.
- [ ] **Extension (расширения) support** — event handlers on adopted forms/items, correct extension handler directive, base-handler-exists detection, name-prefix awareness.
- [ ] **inspect_form_layout surfaces existing handlers** — so set/remove is idempotent and readable.
- [ ] **Qwen examples** for the three new operations in `QwenToolCallExamples.inferExampleParams()`.

### Add After Validation (v1.x)

- [ ] **Server differentiator events** — `ПриЧтенииНаСервере`, `ПередЗаписьюНаСервере`, `ОбработкаПроверкиЗаполненияНаСервере`. Trigger: users hit read/validation flows the v1 set doesn't cover.
- [ ] **Form-level `ОбработкаВыбора` + `ОбработкаНавигационнойСсылки` + decoration `Нажатие`** — Trigger: choice-form and hyperlink-decoration workflows requested.
- [ ] **Hierarchical table events** (`ПередРазворачиванием`, `ПередСворачиванием`). Trigger: tree/hierarchical dynamic-list usage.

### Future Consideration (v2+)

- [ ] **Drag-and-drop event family** — defer: high signature complexity, low demand.
- [ ] **`ПриПолученииДанныхНаСервере` (`&НаСервереБезКонтекста`)** — defer: the one no-context special case; needs its own generator branch and dynamic-list awareness.
- [ ] **`ПриИзмененииДанныхВладельца`** — defer: rare, signature is subordinate-form-specific.

## Feature Prioritization Matrix

| Feature | User Value | Implementation Cost | Priority |
|---|---|---|---|
| Directive-decision table + owner classifier | HIGH | MEDIUM | P1 |
| Field `ПриИзменении` / `ОбработкаВыбора` / `НачалоВыбора` | HIGH | LOW | P1 |
| Form `ПриСозданииНаСервере` / `ПриОткрытии` / `ПередЗакрытием` | HIGH | MEDIUM | P1 |
| Table `ПриАктивизацииСтроки` / `Выбор` / `ПередНачалом*` | HIGH | MEDIUM | P1 |
| BSL stub generator (reuse command path) | HIGH | MEDIUM | P1 |
| Extension (расширения) event support | HIGH | HIGH | P1 (milestone-committed) |
| inspect_form_layout handler surfacing | MEDIUM | LOW | P1 |
| Server differentiator events (Read/BeforeWriteAtServer/FillCheck) | MEDIUM | MEDIUM | P2 |
| Decoration `Нажатие` / `ОбработкаНавигационнойСсылки` | MEDIUM | MEDIUM | P2 |
| Hierarchical table expand/collapse | LOW | MEDIUM | P2 |
| Drag-and-drop family | LOW | HIGH | P3 |
| `ПриПолученииДанныхНаСервере` (`&НаСервереБезКонтекста`) | LOW | HIGH | P3 |

**Priority key:** P1 = must have for v0.1.10 launch · P2 = should have, add when possible · P3 = future.

## Comparison To The Existing `mutate_form_model` Operations

| Aspect | Existing `add_command` (button) | New event operations | Our approach |
|---|---|---|---|
| Wiring target | FormCommand → CommandHandler → proc name | Form/FormItem event slot → handler name → proc | Reuse the model-set + BSL-insert path; add per-event signature/directive descriptor. |
| Directive | Command handlers are client-directed by convention | Varies per event (client / server) | Drive from directive-decision table, never a fixed default. |
| Signature | Command handler `(Команда)` | Per-event, some with out-params | Generate from the descriptor; match out-params exactly. |
| Idempotency | Command name uniqueness | Event-slot single-occupancy per event | Read current state (inspect) before set/remove; atomic wire+stub. |

## Sources

- 1C platform managed-form event model (platform-stable 8.3): handler signatures and client/server execution context are the canonical platform syntax-assistant definitions.
- [Обработчики событий объектов в 1С:Предприятие — TopKoder](https://topkoder.ru/stati/obrabotchiki-sobytij-obektov-v-sisteme-1c-predpriyatie/)
- [Жизненный цикл управляемой формы. Шпаргалка разработчика — Infostart](https://infostart.ru/1c/articles/849540/)
- [Последовательность вызова обработчиков событий при открытии формы объекта — Okolokompa](https://okolokompa.com/katalog/programmistam/1s-predprijatie-8/posledovatelnost-vyzova-obrabotchikov-sobytij-pri-otkrytii-formy-obekta-v-1s-8-3-8-2-upravlyaemye-formy/)
- [Событие «Обработка выбора» — ИТС 1С (Разработка интерфейса)](https://its.1c.ru/db/pubv8devui/content/259/hdoc)
- [Переопределение выбора в поле ввода — ИТС 1С](https://its.1c.ru/db/content/metod8dev/src/platform81/metod/form/i8102205.htm)
- [Обработка Выбора в поле ввода формы — master1c8 (Руководство разработчика, гл. 7 Формы)](https://master1c8.ru/platforma-1s-predpriyatie-8/rukovodstvo-razrabottchika/glava-7-form/6325/)
- CodePilot1C internal: existing `add_command` handler-wiring path in `mutate_form_model` (closest analog — see PITFALLS.md for exact file paths from codebase reconnaissance).

---
*Feature research for: 1C managed-form event-handler wiring (v0.1.10)*
*Researched: 2026-07-13*
