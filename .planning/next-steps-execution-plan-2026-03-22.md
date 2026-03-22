# План следующих шагов по CodePilot1C

**Дата:** 2026-03-22
**Статус:** execution plan закрыт
**Основание:** актуализированные `.planning/BACKLOG.md`, `.planning/REFACTORING-ROADMAP.md`, `.planning/qwen35-optimization-plan-v2.md`

---

## Цель

Закрыть ближайшие технические шаги в правильном порядке:
- сначала подтвердить уже внедрённые изменения tool surface;
- затем довести BSL semantic layer до консистентного typed состояния;
- после этого закрепить Qwen dual-mode transport verification;
- затем завершить унификацию tool metadata;
- orchestration начинать только на стабилизированном foundation.

## Прогресс на 2026-03-22

- Фаза 1 закрыта: test path восстановлен, verification `Wave A` подтверждена targeted и full `core.tests` прогоном.
- Фаза 2 закрыта: `BslSemanticService` переведён на typed hot path без reflective traversal в method/param/pragmas flow.
- Фаза 3 закрыта: реализованы `bsl_module_context` и `bsl_module_exports`, они протянуты через registry/profiles/taxonomy/graph/context gate.
- Фаза 4 закрыта в минимально необходимом scope: dual-mode Qwen transport подтверждён targeted tests, example coverage для новых BSL tools добавлен, large-result fallback проверен.
- Фаза 5 закрыта: ручные `ToolDescriptorRegistry.registerDefaults()` и `BuiltinToolTaxonomy.TAXONOMY` удалены, а metadata-first tool surface подтверждён targeted tests и full `core.tests`.
- Фаза 6 закрыта: реализованы `OrchestratorProfile`, `ProfileRouter`, `delegate_to_agent`, auto/domain routing в `task`, desktop UI auto-routing и delegation contract tests.
- Фаза 7 закрыта: выполнены `Q-08`, `E-03..E-06` и `Wave B3` (`bsl_analyze_method`, structural warnings, call graph, prompt/Qwen/profile sync).
- Верификация execution slice завершена: `mvn -pl bundles/com.codepilot1c.core.tests -am test -q` и `mvn -DskipTests package -q` проходят.

---

## Принципы выполнения

1. Не начинать `Wave D` до стабилизации `Wave A`, `Wave B1/B2` и `Wave Q`.
2. Любой шаг, помеченный в backlog как `done`, должен быть подтверждён тестом или явной code-level verification.
3. Для BSL-слоя критичен отказ от reflective traversal в hot path, а не только косметическая миграция импортов.
4. Для Qwen backend источником истины считать dual-mode strategy:
   - structured tools как primary channel;
   - XML priming как secondary guidance;
   - content/streaming repair как safety-net.

---

## Фаза 1. Verification Wave A

**Приоритет:** P0
**Оценка:** 1 день
**Зависимости:** нет

### Objective

Подтвердить, что profile gate, domain profiles и context gate не только присутствуют в коде, но и реально формируют ожидаемый tool surface.

### Задачи

1. Восстановить локальный test path для `bundles/com.codepilot1c.core` и `bundles/com.codepilot1c.core.tests`.
2. Прогнать `AgentProfileRegistryTest`.
3. Прогнать `ToolContextGateTest`.
4. Добавить интеграционный test case для `AgentRunner.buildRequest()`:
   - profile filter;
   - context exclusion;
   - config-level allow/deny;
   - graph filter interaction.
5. Зафиксировать ожидаемые tool counts для `explore`, `plan`, `code`, `metadata`, `qa`, `dcs`, `extension`, `recovery`.

### Done When

- тесты проходят;
- есть хотя бы один test на фактический filtered tool list в `AgentRunner`;
- можно доказать, что `Wave A` не только реализована, но и verified.

### Риски

- локальная сборка может по-прежнему упираться в Tycho/dependency resolution;
- текущий рабочий tree грязный, поэтому важно не спутать инфраструктурную проблему сборки с логической проблемой filtering.

---

## Фаза 2. Finish Wave B1

**Приоритет:** P0
**Оценка:** 2-3 дня
**Зависимости:** Фаза 1

### Objective

Довести `BslSemanticService` до состояния, где typed BSL API используется как основной путь, а reflective traversal не остаётся в core method/param/pragmas flow.

### Задачи

1. Проанализировать остаточные reflective points в `BslSemanticService`.
2. Убрать `eClass().getEStructuralFeature(...)` из path:
   - method collection;
   - formal params collection;
   - pragmas collection.
3. Оставить fallback только там, где typed API реально отсутствует и это обосновано.
4. Проверить `resolveModuleContext()` и `ModuleContext` на согласованность typed contract.
5. Добавить regression tests для:
   - `bsl_list_methods`;
   - `bsl_get_method_body`;
   - `bsl_symbol_at_position`;
   - `bsl_type_at_position`.

### Done When

- BSL semantic hot path больше не зависит от generic reflective helper;
- regression tests покрывают текущий контракт и проходят;
- backlog item `B1-13` можно переводить в `done`.

### Риски

- EDT API может не давать прямой typed доступ ко всем нужным участкам модели;
- часть текущего кода могла быть написана как compatibility layer для разных SDK variants.

---

## Фаза 3. Finish Wave B2 Minimal Valuable Scope

**Приоритет:** P1
**Оценка:** 2 дня
**Зависимости:** Фаза 2

### Objective

Добавить минимально ценные BSL tools, которых сейчас не хватает агенту для модульного и межмодульного контекста.

### Scope

- `bsl_module_context`
- `bsl_module_exports`

### Задачи

1. Реализовать `bsl_module_context`:
   - module type;
   - owner;
   - default pragmas;
   - variables;
   - method count.
2. Реализовать `bsl_module_exports`:
   - exported methods;
   - exported properties;
   - events, если доступны через текущий EDT SDK.
3. Зарегистрировать оба tools в `ToolRegistry`.
4. Добавить их в релевантные профили:
   - `CodeBuildProfile`;
   - `ExploreAgentProfile`.
5. Добавить тесты на оба tools.
6. Не смешивать этот шаг с `isUsed`; `B2-03` оставить blocked до отдельного решения.

### Done When

- оба новых tools доступны агенту;
- их payload deterministic и machine-usable;
- тесты подтверждают основной happy path.

### Риски

- `IBslModuleContextDefService` и связанные EDT contracts могут иметь ограничения по доступности в текущем runtime;
- возможно потребуется отдельная обработка для модулей без export surface.

---

## Фаза 4. Verification Wave Q

**Приоритет:** P1
**Оценка:** 2 дня
**Зависимости:** Фаза 1

### Objective

Закрепить уже реализованный Qwen/backend path и убрать риск drift между tool schema, XML priming и fallback parsing.

### Задачи

1. Выполнить audit всех новых и изменённых tools против `QwenToolCallExamples.inferExampleParams()`.
2. Проверить, что все tools, доступные backend/Qwen path, имеют валидный example generation path.
3. Добавить targeted tests на:
   - schema/example drift;
   - content fallback parsing;
   - incomplete JSON repair;
   - reused index collision;
   - resolved model parsing.
4. Проверить large-tool-result fallback policy в `OpenAiModelCompatibilityPolicy`.
5. Зафиксировать отдельным short note, какие backend-only правила уже enforced в transport, а какие ещё в verification stage.

### Done When

- нет tools без Qwen example coverage в backend/Qwen path;
- tests подтверждают dual-mode transport behavior;
- backlog `Wave Q` можно сужать до остаточных улучшений, а не foundation work.

### Риски

- часть проблем может проявляться только на реальном backend stream, а не на fixtures;
- возможен скрытый drift при добавлении новых tools без обновления Qwen examples.

---

## Фаза 5. Continue Wave C

**Статус:** завершена

**Приоритет:** P2
**Оценка:** 2-3 дня
**Зависимости:** Фаза 1, частично Фаза 4

### Objective

Довести tool metadata architecture до консистентного состояния и убрать половинчатую миграцию.

### Задачи

1. Продолжить перевод remaining tool classes на `AbstractTool`.
2. Довести source of truth для tool metadata до одного механизма.
3. Завершить переход от ручных таблиц к автоматическому построению данных для:
   - `ToolDescriptorRegistry`;
   - `BuiltinToolTaxonomy`.
4. Удалять ручные `registerDefaults()` и статическую taxonomy только после полного покрытия.

### Done When

- metadata не дублируется вручную в нескольких системах;
- built-in tools описываются единообразно;
- `Wave C` движется от framework introduction к actual consolidation.

### Промежуточно подтверждено

- `ToolDescriptorRegistry` уже получает runtime metadata и не теряет более точные existing builtin categories.
- `BuiltinToolTaxonomy` и `ToolRegistry` уже используют metadata-first public surface resolution.
- explicit `surfaceCategory` override уже применён для runtime/smoke tools, где raw `category` слишком грубая.

### Риски

- UI и routing code могут зависеть от старых registries сильнее, чем видно сейчас;
- генерация taxonomy потребует careful compatibility pass.

### Фактически закрыто

- `ToolDescriptorRegistry` bootstraps from runtime metadata.
- `BuiltinToolTaxonomy` больше не зависит от статической карты имён.
- `ToolRegistry.registerDefaults()` и legacy taxonomy path удалены из обязательного runtime bootstrap.
- Verification закрыта через targeted tests, full `core.tests` и full reactor build.

---

## Фаза 6. Start Wave D Only After Stabilization

**Статус:** завершена

**Приоритет:** P3
**Оценка:** 3-5 дней
**Зависимости:** Фазы 1-5

### Objective

Начать orchestration только после стабилизации tool surface, BSL context и Qwen/backend behavior.

### Задачи

1. Решить, расширяется ли существующий `task`, или вводится новый `DelegateToAgentTool`.
2. Создать `OrchestratorProfile`.
3. Добавить routing в domain profiles.
4. Добавить timeout/error handling и context passing.
5. Покрыть delegation round-trip tests.

### Done When

- orchestration работает поверх реальных domain profiles;
- нет дублирования между old `task` semantics и new delegation layer.

### Фактически закрыто

- `OrchestratorProfile` зарегистрирован и покрыт тестом на allowlist/read-only contract.
- `ProfileRouter` маршрутизирует `build/auto` в direct domain profile или `orchestrator`.
- `task` поддерживает `auto` и domain profiles; `delegate_to_agent` добавляет thin orchestration wrapper с `context`.
- Desktop agent UI (`AgentViewAdapter`/`AgentView`) применяет auto-routing для generic `build`.
- Контракты закрыты через `ProfileRouterTest`, `TaskToolTest`, `DelegateToAgentToolTest`, `PromptSnapshotTest`, `QwenToolingCompatibilityTest`.

### Риски

- ранний старт этой фазы зафиксирует нестабильные contracts;
- orchestration поверх ещё не выверенного tool surface даст ложную сложность и плохую диагностируемость.

---

## Итоговое закрытие follow-up scope

Закрыты:

1. `Q-09`: full-surface Qwen example/schema audit.
2. `B2-06`: deep semantic resolve в `bsl_symbol_at_position`.
3. `E-01`: read-only BM tasks для inspection tools.
4. `C3`: package reorg `tools/*`.
5. `Q-08`: prompt/skill sync с backend-only Qwen rules.
6. `E-03`: batch export для mutation tools.
7. `E-04`: event-driven DD waiting.
8. `E-05`: `IFormItemManagementService` для form tools.
9. `E-06`: `BmObjectHelper` centralization.
10. `B3-01..B3-09`: structural analysis и `bsl_analyze_method`.

Текущий execution plan больше не имеет незакрытых пунктов. Следующие шаги должны оформляться уже как новый отдельный planning slice.

---

## Порядок обновления backlog после выполнения

После каждой завершённой фазы:

1. обновить статус соответствующих items в `.planning/BACKLOG.md`;
2. зафиксировать зелёный targeted/full test path и reactor build;
3. открывать следующий planning slice только для нового scope, а не для уже закрытого execution plan.
