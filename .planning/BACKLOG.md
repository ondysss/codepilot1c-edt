# Backlog: CodePilot1C Refactoring & Optimization

**Обновлён:** 2026-03-22
**Источники:** REFACTORING-ROADMAP.md, bsl-tools-optimization-plan.md, tool-architecture-refactoring-plan.md, tool-count-optimization-plan.md, sub-agent-orchestration-plan.md, qwen35-optimization-plan-v2.md

## Статусы

- `todo` — не начато
- `in_progress` — в работе
- `done` — завершено
- `blocked` — заблокировано зависимостью

## Приоритеты

- `P0` — критический блокер: accuracy деградация, сломанная фильтрация, runtime bug
- `P1` — высокий: значительное улучшение качества агента
- `P2` — средний: архитектурное улучшение, tech debt
- `P3` — низкий: nice-to-have, будущие возможности

## Статус-снимок на 2026-03-22

- `Wave A` — в коде в основном реализована: profile gate, domain profiles, context gating.
- `Wave B` — минимальный ближайший scope закрыт: typed BSL hot path без reflection, `isUsed`/docs/pragmas добавлены, `bsl_module_context` и `bsl_module_exports` реализованы; дальше остаётся deeper semantic enrichment.
- `Wave C` — закрыта в текущем planned scope: built-in tools идут через metadata-first path, `ToolDescriptorRegistry.registerDefaults()` удалён, статическая `BuiltinToolTaxonomy.TAXONOMY` удалена, runtime/tool-surface sync подтверждён тестами.
- `Wave D` — закрыта в текущем planned scope: реализованы `OrchestratorProfile`, `ProfileRouter`, `delegate_to_agent`, auto/domain routing в `task`, desktop UI auto-routing и delegation tests.
- `Wave Q` — foundation и full-surface verification закрыты: dual-mode transport подтверждён тестами, schema/example audit покрывает весь backend-visible tool surface, large-result fallback подтверждён; дальше остаётся prompt/skill sync (`Q-08`).

## Текущая очередь выполнения

Текущий execution slice (`NX-01..NX-11`) и следующий follow-up slice (`Q-09`, `B2-06`, `E-01`, `C3`) завершены.

Следующая очередь после закрытия этого follow-up slice:

- `Q-08` `P2` Досинхронизировать prompt/skill layer с backend-only Qwen rules без дублирования transport logic.
- `E-03` `P2` Batch export через `forceExport(project, List<fqn>)` для mutation tools.
- `E-04` `P2` Event-driven DD waiting через `waitAllComputations(timeout)`.
- `E-05` `P2` Перевести form tools на `IFormItemManagementService`.
- `E-06` `P2` Вынести `BmObjectHelper` и централизовать safe BM helpers.

- `NX-01` `P0` `done` Восстановить локальный test path для `bundles/com.codepilot1c.core` и `bundles/com.codepilot1c.core.tests`, чтобы можно было верифицировать уже внедрённые изменения.
  Files: build/test infrastructure, `bundles/com.codepilot1c.core.tests`
  Done when: можно стабильно запускать targeted tests по `core` и `core.tests`.
  Notes: reactor path `mvn -pl bundles/com.codepilot1c.core.tests -am ...` подтверждён; устранён compile blocker в `QaRunTool`.

- `NX-02` `P0` `done` Закрыть verification `Wave A`: прогнать `AgentProfileRegistryTest`, `ToolContextGateTest` и добавить integration test на `AgentRunner.buildRequest()`.
  Files: `AgentProfileRegistryTest.java`, `ToolContextGateTest.java`, новый/дополненный test для `AgentRunner`
  Depends on: `NX-01`
  Notes: добавлен `AgentRunnerBuildRequestTest`; plain JUnit path стабилизирован для `ToolContextGate` и `AgentRunner`.

- `NX-03` `P0` `done` Довести `Wave B1` до реального typed BSL path без reflective traversal в method/param/pragmas hot path.
  Files: `BslSemanticService.java`
  Depends on: `NX-02`
  Notes: `collectMethods()/collectParams()/collectPragmas()` переведены на typed EDT API; `getEStructuralFeature(...)` и `getChildrenOfType(...)` удалены из hot path.

- `NX-04` `P0` `done` Добавить regression tests для BSL semantic tools: `bsl_list_methods`, `bsl_get_method_body`, `bsl_symbol_at_position`, `bsl_type_at_position`.
  Files: новые/дополненные BSL tests
  Depends on: `NX-03`
  Notes: добавлен `BslSemanticToolsContractTest`; зафиксированы JSON contract/error semantics и `used/documentation` fields.

- `NX-05` `P1` `done` Реализовать `bsl_module_context`.
  Files: новый tool, `BslSemanticService.java`, `ToolRegistry.java`, profiles
  Depends on: `NX-03`
  Notes: tool возвращает module type, owner, default pragmas и method counters.

- `NX-06` `P1` `done` Реализовать `bsl_module_exports`.
  Files: новый tool, `BslSemanticService.java`, `ToolRegistry.java`, profiles
  Depends on: `NX-05`
  Notes: tool возвращает export surface модуля через `Method.isExport()` и `BslMethodInfo`.

- `NX-07` `P1` `done` Закрыть verification `Wave Q`: audit tool coverage against `QwenToolCallExamples.inferExampleParams()`.
  Files: `QwenToolCallExamples.java`, новые/изменённые tool-классы
  Depends on: `NX-02`
  Notes: добавлены explicit example patterns для `bsl_symbol_at_position`, `bsl_type_at_position`, `bsl_scope_members`, `bsl_list_methods`, `bsl_get_method_body`, `bsl_module_context`, `bsl_module_exports`.

- `NX-08` `P1` `done` Добавить targeted Qwen tests на schema/example drift, content fallback, incomplete JSON repair, index collision, resolved-model flow.
  Files: provider/config tests
  Depends on: `NX-07`
  Notes: добавлен `QwenToolingCompatibilityTest` для dual-mode transport и example/schema alignment на новых BSL tools.

- `NX-09` `P1` `done` Проверить и при необходимости доработать large-tool-result fallback policy для backend/Qwen follow-up запросов.
  Files: `OpenAiModelCompatibilityPolicy.java`
  Depends on: `NX-08`
  Notes: policy подтверждён targeted test на `>50k` tool result.

- `NX-10` `P2` `done` Завершить `Wave C`: довести remaining tools до `AbstractTool` и закрыть переход к единому source of truth для `ToolDescriptorRegistry`/`BuiltinToolTaxonomy`.
  Files: `tools/*`, `ToolDescriptorRegistry.java`, `BuiltinToolTaxonomy.java`
  Depends on: `NX-04`, `NX-09`
  Notes: все built-in tools идут через metadata/runtime path; `ToolDescriptorRegistry.registerDefaults()` удалён; `BuiltinToolTaxonomy` больше не хранит статическую `TAXONOMY`; metadata-first resolution подтверждён targeted tests и full `core.tests`.

- `NX-11` `P3` `done` Реализовать `Wave D` orchestration поверх стабилизированного foundation.
  Files: `TaskTool.java`, `DelegateToAgentTool.java`, `OrchestratorProfile.java`, `ProfileRouter.java`, `AgentViewAdapter.java`
  Depends on: `NX-10`
  Notes: добавлены `OrchestratorProfile`, `delegate_to_agent`, `ProfileRouter`, auto/domain routing в `task`, context passing, delegation error handling и desktop UI auto-routing; verification закрыта через `ProfileRouterTest`, `TaskToolTest`, `DelegateToAgentToolTest`, `AgentProfileRegistryTest`, `PromptSnapshotTest`, `QwenToolingCompatibilityTest`.

---

## Wave A: Tool Surface & Agent Accuracy (P0)

Цель: снизить кол-во видимых tools с 67–114 до 10–25. Прямое влияние на accuracy агента.

### A1. Profile Gate Fix

> Баг: `AgentProfile.getAllowedTools()` определён, но не проверяется в `AgentRunner.buildRequest()`

- `A1-01` `P0` `done` Добавить проверку `profile.getAllowedTools()` в `AgentRunner.buildRequest()` после `resolveProfile()`.
  Files: `AgentRunner.java`
  Done when: Explore/Plan профили видят только свои 27 tools, а не все 67.

- `A1-02` `P0` `done` Добавить trace-лог кол-ва tools до/после profile фильтрации.
  Files: `AgentRunner.java`
  Done when: в логах видно `Tools: 67 total → 27 after profile gate`.

- `A1-03` `P1` `done` Тест: Explore profile получает только свои whitelisted tools.
  Files: `AgentProfileRegistryTest.java`

- `A1-04` `P1` `done` Тест: Build profile получает все tools без ограничения (fallback).
  Files: `AgentProfileRegistryTest.java`

- `A1-05` `P0` `done` Integration test: `AgentRunner.buildRequest()` реально применяет profile gate при построении tool surface.
  Files: `AgentRunner` tests
  Depends on: `NX-01`

### A2. Domain-Specific Build Profiles

> Build profile содержит 114 tools — за порогом деградации (30–50, Anthropic)

- `A2-01` `P0` `done` Создать `CodeBuildProfile` (~22 tools: files + bsl + git + edt_content_assist + inspect_platform).
  Files: `profiles/CodeBuildProfile.java`

- `A2-02` `P0` `done` Создать `MetadataBuildProfile` (~33 tools: files + metadata CRUD + forms + DCS + edt_validate).
  Files: `profiles/MetadataBuildProfile.java`

- `A2-03` `P1` `done` Создать `QABuildProfile` (~22 tools: files + qa_* + smoke + author_yaxunit).
  Files: `profiles/QABuildProfile.java`

- `A2-04` `P1` `done` Создать `DCSBuildProfile` (~17 tools: files + dcs_* + scan_metadata).
  Files: `profiles/DCSBuildProfile.java`

- `A2-05` `P1` `done` Создать `ExtensionBuildProfile` (~22 tools: files + extension_* + external_*).
  Files: `profiles/ExtensionBuildProfile.java`

- `A2-06` `P2` `done` Создать `RecoveryProfile` (~13 tools: files + smoke + trace + analyze_error).
  Files: `profiles/RecoveryProfile.java`

- `A2-07` `P1` `done` Зарегистрировать все новые профили в AgentProfileRegistry.
  Files: `AgentProfileRegistry.java`

- `A2-08` `P2` `done` Тесты: каждый профиль содержит корректный whitelist, subset-проверка, tool count range.
  Files: `AgentProfileRegistryTest.java`

### A3. Context-Aware Gating

> Tools показываются агенту даже когда бесполезны (нет DCS, нет расширений, нет QA)

- `A3-01` `P1` `done` Создать `ToolContextGate.computeExcludedTools()` с workspace сканированием.
  Files: `ToolContextGate.java`

- `A3-02` `P1` `done` Gate: нет DCS-схемы → исключить `dcs_*` (−6 tools).
  Files: `ToolContextGate.java`

- `A3-03` `P1` `done` Gate: нет расширений → исключить `extension_*` (−6 tools).
  Files: `ToolContextGate.java`

- `A3-04` `P1` `done` Gate: нет внешних обработок → исключить `external_*` (−6 tools).
  Files: `ToolContextGate.java`

- `A3-05` `P1` `done` Gate: нет QA-конфига → исключить `qa_*` кроме `qa_init_config` (−10 tools).
  Files: `ToolContextGate.java`

- `A3-06` `P2` `done` Gate: нет открытого проекта EDT → исключить все EDT/BSL tools.
  Files: `ToolContextGate.java`

- `A3-07` `P1` `done` Интегрировать `ToolContextGate` в `AgentRunner.buildRequest()`.
  Files: `AgentRunner.java`

- `A3-08` `P2` `done` Кэширование gate-результата на 5 минут (TTL, `invalidateCache()`).
  Files: `ToolContextGate.java`

- `A3-09` `P2` `done` Тесты: excluded not null, qa_init_config never excluded, core tools never excluded, cache invalidation.
  Files: `ToolContextGateTest.java`

- `A3-10` `P1` `done` Integration test: `AgentRunner.buildRequest()` учитывает `ToolContextGate` вместе с profile/config/graph filtering.
  Files: `AgentRunner` tests
  Depends on: `NX-01`

---

## Wave B: BSL Semantic Layer (P0/P1)

Цель: перейти с EMF рефлексии на typed API, обогатить контекст для агента.

### B1. Typed BSL API Migration

> `BslSemanticService` использует 100% EMF рефлексию вместо typed BSL model

- `B1-01` `P0` `done` Добавить `com._1c.g5.v8.dt.bsl.model` в `MANIFEST.MF` Import-Package.
  Files: `META-INF/MANIFEST.MF`

- `B1-02` `P0` `done` `resolveModuleContext()`: cast root `EObject` → `Module`, обновить `ModuleContext` record.
  Files: `BslSemanticService.java`

- `B1-03` `P0` `done` `collectMethods()`: typed EMF traversal через `getChildrenOfType()` с fallback по feature names.
  Files: `BslSemanticService.java`

- `B1-04` `P0` `done` `collectMethods()`: `method.getName()` вместо `getStringFeature`.
  Files: `BslSemanticService.java`

- `B1-05` `P0` `done` `collectMethods()`: `method.isExport()/isAsync()/isEvent()` вместо `getBooleanFeature`.
  Files: `BslSemanticService.java`

- `B1-06` `P0` `done` `collectParams()`: typed `FormalParam` traversal.
  Files: `BslSemanticService.java`

- `B1-07` `P0` `done` `collectParams()`: `param.getName()`, `param.isByValue()`, `param.getDefaultValue()`.
  Files: `BslSemanticService.java`

- `B1-08` `P1` `done` `methodKind()`: удалён, заменён на inline `instanceof Procedure`.
  Files: `BslSemanticService.java`

- `B1-09` `P1` `done` `symbolKind()`: pattern matching по typed BSL classes.
  Files: `BslSemanticService.java`

- `B1-10` `P1` `done` `getSymbolAtPosition()`: typed `extractName()` вместо `getStringFeature`.
  Files: `BslSemanticService.java`

- `B1-11` `P1` `done` Удалены helper-методы: `getStringFeature()`, `getBooleanFeature()`, `getEObjectList()`, `getEObjectFeature()`, `methodKind()` (~80 строк).
  Files: `BslSemanticService.java`

- `B1-12` `P1` `done` `computeTypes()`: environment-aware TypesComputer через `findContainerOfType(Environmental.class)`.
  Files: `BslSemanticService.java`

- `B1-13` `P1` `done` Regression tests: core BSL semantic tools и новые module-level tools зафиксированы contract tests.
  Files: `BslSemanticToolsContractTest.java`

- `B1-14` `P0` `done` Убрать reflective traversal из `collectMethods()/collectParams()/collectPragmas()` hot path.
  Files: `BslSemanticService.java`
  Done when: method/param/pragma flow больше не использует `eClass().getEStructuralFeature(...)`.

- `B1-15` `P1` `done` Зафиксировать, что typed hot path работает без reflection; residual fallback ограничен node-model/general helpers, а не EMF feature traversal.
  Files: `BslSemanticService.java`, tests
  Done when: compatibility fallback локализован и hot path остаётся typed-only.

### B2. BSL Tool Enrichment

> Агент не знает server/client контекст, не видит документацию, не может cross-module resolve

- `B2-01` `P0` `done` Добавить `pragmas: List<String>` в `BslMethodInfo`, заполнять через typed Pragma traversal.
  Files: `BslSemanticService.java`, `BslMethodInfo.java`

- `B2-02` `P1` `done` Добавить `documentation: String` в `BslMethodInfo` через extractDocumentation() из // комментариев.
  Files: `BslSemanticService.java`, `BslMethodInfo.java`

- `B2-03` `P1` `done` Добавить `isUsed: boolean` в `BslMethodInfo` — `Method.isUsed()` доступен в текущей версии EDT SDK и уже используется.
  Files: `BslSemanticService.java`, `BslMethodInfo.java`

- `B2-04` `P0` `done` Новый tool `bsl_module_context`: moduleType, owner, defaultPragmas, methodCount/counters.
  Files: новый tool, `BslSemanticService.java`
  EDT API: `Module.getModuleType()`, `getOwner()`, `getDefaultPragmas()`, `allMethods()`
  Done when: агент знает тип модуля и доступные директивы перед генерацией кода.

- `B2-05` `P1` `done` Новый tool `bsl_module_exports`: экспортируемые методы модуля через typed method surface.
  Files: новый tool, `BslSemanticService.java`
  EDT API: `Module.allMethods()` + `Method.isExport()`
  Done when: агент видит API соседних модулей без открытия файлов.

- `B2-06` `P1` `done` Deep resolve в `bsl_symbol_at_position`: `FeatureEntry.getFeature()`, `getEnvironments()`.
  Files: `BslSemanticService.java`
  Done when: resolve возвращает реальный target (Method, Variable, Property), а не промежуточный AST-узел.
  Notes: `Invocation`/`FeatureAccess` теперь разворачиваются в semantic target через `FeatureEntry`, выбор entry учитывает `Environments`; добавлены unit tests на environment-aware resolution.

- `B2-07` `P2` `done` Зарегистрировать новые tools в `ToolRegistry` и добавить в `CodeBuildProfile`, `ExploreAgentProfile`, `PlanAgentProfile`, `BuildAgentProfile`, taxonomy/graph/context gate.
  Files: `ToolRegistry.java`, profiles

- `B2-08` `P2` `done` Тесты для новых tools и обогащённых полей.
  Files: тесты

- `B2-09` `P1` `done` Добавить `bsl_module_context` и `bsl_module_exports` в `CodeBuildProfile` и `ExploreAgentProfile` после реализации.
  Files: profiles
  Depends on: `B2-04`, `B2-05`

### B3. Structural Analysis

> Агент получает только текст метода — не может найти антипаттерны автоматически

- `B3-01` `P2` `todo` Создать `BslMethodAnalyzer` — recursive visitor по Statement/Expression subtypes.
  Files: новый `BslMethodAnalyzer.java`

- `B3-02` `P2` `todo` Complexity metrics: branches (IfStatement), loops (While/For), try/catch, LOC, cyclomatic.
  Files: `BslMethodAnalyzer.java`

- `B3-03` `P2` `todo` Server call in loop detection: `Invocation.isIsServerCall()` внутри `LoopStatement`.
  Files: `BslMethodAnalyzer.java`

- `B3-04` `P2` `todo` Empty except detection: `TryExceptStatement.getExceptStatement().isEmpty()`.
  Files: `BslMethodAnalyzer.java`

- `B3-05` `P2` `todo` Unused parameter detection: `FormalParam` vs usage в теле метода.
  Files: `BslMethodAnalyzer.java`

- `B3-06` `P3` `todo` Call graph: `Method.getCallees()`, `Method.getCallers()`.
  Files: `BslMethodAnalyzer.java`

- `B3-07` `P2` `todo` Новый tool `bsl_analyze_method`: параметры (project, file, methodName), результат (complexity + warnings + callGraph).
  Files: новый tool

- `B3-08` `P2` `todo` Зарегистрировать tool, добавить в `CodeBuildProfile`, `ExploreAgentProfile`.
  Files: `ToolRegistry.java`, profiles

- `B3-09` `P2` `todo` Тесты для каждого типа warning.
  Files: тесты

---

## Wave C: Tool Architecture (P2)

Цель: устранить тройную дупликацию метаданных, декомпозировать god object, организовать пакеты.

### C1. Unified Tool Metadata (@ToolMeta)

> Метаданные tools дублируются в ToolRegistry + ToolDescriptorRegistry + BuiltinToolTaxonomy

- `C1-01` `P2` `done` Расширить `ITool` default-методами: `getCategory()`, `isMutating()`, `requiresValidationToken()`, `getTags()`.
  Files: `ITool.java`

- `C1-02` `P2` `done` Создать аннотацию `@ToolMeta(name, category, mutating, requiresValidationToken, tags, surfaceCategory)`.
  Files: `ToolMeta.java`

- `C1-03` `P2` `done` Создать `ToolParameters` wrapper: `requireString()`, `optString()`, `requireInt()`, `optInt()`, `optBoolean()`, `optStringList()`, `has()`.
  Files: `ToolParameters.java`

- `C1-04` `P2` `done` Создать `AbstractTool` base class: `doExecute(ToolParameters)` + auto CompletableFuture + exception handling + @ToolMeta auto-read.
  Files: `AbstractTool.java`

- `C1-05` `P2` `done` Мигрировать 5 пилотных tools: `ReadFileTool`, `ListFilesTool`, `GrepTool`, `GlobTool`, `EditFileTool`.
  Files: 5 tool-классов

- `C1-06` `P2` `done` Генерировать `ToolDescriptorRegistry` из `@ToolMeta` / `ITool.getCategory()`.
  Files: `ToolDescriptorRegistry.java`
  Note: registry bootstraps from runtime `ToolRegistry`, merge-ит metadata без деградации уже более точных builtin descriptors.

- `C1-07` `P2` `done` Генерировать `BuiltinToolTaxonomy` из `@ToolMeta`.
  Files: `BuiltinToolTaxonomy.java`
  Note: category resolution теперь metadata-first (`surfaceCategory` -> inferred runtime category -> fallback), статическая карта имён удалена.

- `C1-08` `P2` `done` Мигрировать оставшиеся tools порциями (10–15 за итерацию).
  Files: все tool-классы
  Note: remaining built-in tools сведены к `AbstractTool`/runtime-metadata path; test harness и snapshot tests адаптированы к новой модели.

- `C1-09` `P2` `done` Удалить `ToolDescriptorRegistry.registerDefaults()` и `BuiltinToolTaxonomy.TAXONOMY` после полной миграции.
  Files: `ToolDescriptorRegistry.java`, `BuiltinToolTaxonomy.java`
  Note: удалено из runtime bootstrap; coverage подтверждена targeted tests и full `core.tests`.

### C2. ToolRegistry Decomposition

> `ToolRegistry` (~670 строк) — god object: registration + parsing + execution + tracing + provider resolution

- `C2-01` `P2` `done` Извлечь `ToolArgumentParser` (~177 строк JSON-парсинга). ToolRegistry 680→503 строк.
  Files: `ToolArgumentParser.java`, `ToolRegistry.java`

- `C2-02` `P2` `done` Извлечь `ToolExecutionService` (~185 строк execution + tracing). ToolRegistry 503→408 строк.
  Files: новый `ToolExecutionService.java`

- `C2-03` `P2` `done` Извлечь `ProviderContextResolver` (~96 строк provider config resolution). ToolRegistry 408→353 строк.
  Files: новый `ProviderContextResolver.java`

- `C2-04` `P2` `done` Сократить `ToolRegistry` 680→353 строк. Остаток — registration list (~70 строк) + lookup/definitions.
  Files: `ToolRegistry.java`

- `C2-05` `P2` `done` Обновить зависимости в `AgentRunner`, `ChatView` — backward compatibility через delegation, изменения не требуются.
  Files: `AgentRunner.java`, `ChatView.java`

- `C2-06` `P2` `done` Тесты: JSON parsing edge cases (fragmented JSON, nested objects, fallback parser).
  Files: `ToolArgumentParserTest.java`

### C3. Package Reorganization

> 70+ tool-классов в одном плоском пакете

- `C3-01` `P3` `done` Создать пакеты: `tools/{file,git,bsl,metadata,forms,dcs,extension,external,qa,diagnostics,workspace}/`.
  Files: новые пакеты

- `C3-02` `P3` `done` Переместить tool-классы в соответствующие пакеты.
  Files: все tool-классы
  Notes: bulk move завершён для file/git/bsl/metadata/forms/dcs/extension/external/qa/diagnostics/workspace tool implementations; orchestration/core support classes остались в корневом `tools`.

- `C3-03` `P3` `done` Обновить импорты в `ToolRegistry` и `MANIFEST.MF`.
  Files: `ToolRegistry.java`, `MANIFEST.MF`
  Notes: import paths переписаны по всему repo; `ToolRegistry` и `Export-Package` синхронизированы с новыми subpackages.

- `C3-04` `P3` `done` Верификация: `mvn -DskipTests package`.
  Notes: `mvn -pl bundles/com.codepilot1c.core.tests -am test -q` и полный reactor `mvn -DskipTests package -q` зелёные после reorg.

---

## Wave D: Sub-Agent Orchestration (P3)

Цель: orchestrator agent (8 tools) делегирует domain-tasks sub-агентам (14–25 tools каждый).

### D1. Orchestrator

- `D1-01` `P3` `done` Создать `OrchestratorProfile` (~8 tools: read_file, list_files, grep, glob, delegate_to_agent, task, skill).
  Files: `profiles/OrchestratorProfile.java`

- `D1-02` `P3` `done` Создать `DelegateToAgentTool`: запуск sub-AgentRunner с domain-профилем.
  Files: новый `DelegateToAgentTool.java`

- `D1-03` `P3` `done` Создать `ProfileRouter`: keyword-based routing (prompt → profile name).
  Files: новый `ProfileRouter.java`

- `D1-04` `P3` `done` Интегрировать desktop UI routing — автовыбор orchestrator vs direct profile для generic `build`.
  Files: `AgentViewAdapter.java`, `AgentView.java`

- `D1-05` `P3` `done` Sub-agent context passing: project, file, conversation context.
  Files: `DelegateToAgentTool.java`
  Note: `context` включается в delegated prompt, покрыто `DelegateToAgentToolTest`.

- `D1-06` `P3` `done` Timeout и error handling для sub-agents.
  Files: `DelegateToAgentTool.java`
  Note: delegation использует `TaskTool` timeout/error path; failure formatting и backend gating покрыты `TaskToolTest`/`DelegateToAgentToolTest`.

- `D1-07` `P3` `done` Тесты: routing accuracy, delegation round-trip, multi-agent scenario.
  Files: тесты
  Note: добавлены `ProfileRouterTest`, расширены `TaskToolTest` и `DelegateToAgentToolTest`, обновлены `AgentProfileRegistryTest`, `PromptSnapshotTest`, `QwenToolingCompatibilityTest`.

---

## Wave E: EDT API Optimizations (P1–P2, параллельные)

Независимые оптимизации EDT API. Могут выполняться в любом порядке.

- `E-01` `P1` `done` Read-only BM tasks для 11 inspection tools (−30% latency).
  Files: `BslSemanticService.java`, `EdtMetadataInspectorService.java`, `EdtContentAssistService.java`, existing read-only inspection paths
  EDT API: `IBmModelManager.executeReadOnlyTask()`
  Notes: BSL inspection reads, metadata details и content assist entrypoint переведены на read-only wrapper с fallback; `scan_metadata_index`, `edt_find_references`, `inspect_form_layout` уже были на read-only path и подтверждены повторной верификацией.

- `E-02` `P1` `done` Retry optimization: 10×300ms → 3× exponential backoff (200ms × 3^attempt).
  Files: `BslSemanticService.java`

- `E-03` `P2` `todo` Batch export: `forceExport(project, List<fqn>)` для mutation tools (−50% export time).
  Files: `create_metadata`, `apply_form_recipe`, `add_metadata_child`

- `E-04` `P2` `todo` Event-driven DD waiting: `waitAllComputations(timeout)` вместо polling.
  Files: `MetadataProjectReadinessChecker`

- `E-05` `P2` `todo` `IFormItemManagementService` для form tools (правильные ID/name через `FormNewItemDescriptor`).
  Files: `ApplyFormRecipeTool`, `MutateFormModelTool`

- `E-06` `P2` `todo` `BmObjectHelper` centralization: `safeTopFqn()`, `safeId()`, `safeUriString()`.
  Files: новый `BmObjectHelper.java`, все EDT tools

---

## Wave Q: Qwen Backend / Dual-Mode Transport Alignment (P0/P1)

Цель: закрепить и довести Qwen-специфичный backend path, где structured tools остаются primary channel, а XML priming и parser repair работают как safety-net и accuracy booster.

- `Q-01` `P0` `done` Capability-driven provider foundation: `ProviderCapabilities`, `ProviderUtils`, `ILlmProvider.getCapabilities()`, resolved model support.
  Files: `ProviderCapabilities.java`, `ProviderUtils.java`, `ILlmProvider.java`, `LlmResponse.java`, `DynamicLlmProvider.java`

- `Q-02` `P0` `done` Отдельный `QwenFunctionCallingTransport` с **dual-mode transport**: structured `tools` + XML priming в system message.
  Files: `QwenFunctionCallingTransport.java`, `QwenToolCallExamples.java`

- `Q-03` `P0` `done` Safety-net parsing: content fallback, streaming repair, JSON repair, finish-reason override.
  Files: `QwenContentToolCallParser.java`, `QwenStreamingToolCallParser.java`, `JsonRepairUtil.java`

- `Q-04` `P1` `done` Интеграция Qwen path в `DynamicLlmProvider` и базовые streaming/resolved-model tests.
  Files: `DynamicLlmProvider.java`, `DynamicLlmProviderStreamingTest.java`, `OpenAiStreamingSessionTest.java`

- `Q-05` `P1` `done` Audit новых BSL semantic tools против `QwenToolCallExamples.inferExampleParams()` и XML priming examples.
  Files: `QwenToolCallExamples.java`, новые tool-классы

- `Q-06` `P1` `done` Добавить targeted tests на drift между tool schema и example generation для backend/Qwen BSL tools.
  Files: provider/config tests

- `Q-07` `P1` `done` Проверить large-tool-result fallback policy для Qwen/backend follow-up запросов (>50k chars output).
  Files: `OpenAiModelCompatibilityPolicy.java`

- `Q-08` `P2` `todo` Досинхронизировать prompt/skill layer с backend-only правилами без дублирования transport logic в prompts.
  Files: prompt/skills services, `qwen35-optimization-plan-v2.md`

- `Q-09` `P1` `done` Добавить explicit tests на соответствие `ToolDefinition` schema и XML priming examples для всего backend-visible tool surface, а не только для новых BSL tools.
  Files: provider/config tests, `QwenToolCallExamples.java`
  Notes: `QwenToolCallExamples` переведён на schema-driven fallback с curated overrides для key tools; full-surface backend-visible audit зафиксирован тестом.

- `Q-10` `P1` `done` Зафиксировать verification note по backend/Qwen path: что уже enforced в transport и что ещё остаётся policy-level improvement.
  Files: `qwen35-optimization-plan-v2.md`

---

## Граф зависимостей

```
                    A1 (Profile Fix)
                   / |            \
                 A2  A3            \
          (Profiles)(Gating)        \
                 \    |              \
                  \   |          D1 (Orchestrator)
                   \  |
                    \ |
                     MVP

        B1 (Typed BSL API)          C1 (@ToolMeta)
         |        \                   |
        B2         B3               C2 (Registry Decomp)
    (Enrichment) (Analysis)           |
                                    C3 (Package Reorg)

   E-01..E-06 (EDT API) — независимые, параллельные
```

## Исторический критический путь (архив)

Ниже сохранён исходный high-level MVP-план. Для актуального порядка выполнения использовать секцию `Текущая очередь выполнения`.

```
Week 1:
  A1-01..A1-04  Profile gate fix          ██ (1 day)
  B1-01..B1-13  Typed BSL API             ████████████ (3–5 days, parallel)
  E-02          Retry optimization        ██ (1 day, parallel)

Week 2:
  A2-01..A2-08  Domain profiles           ████████████ (3–5 days)
  B2-01..B2-08  BSL enrichment            ████████████ (3–5 days, parallel)
  E-01          Read-only BM tasks        ████ (2 days, parallel)
```

**Результат MVP:** 67→14–25 tools, typed BSL API, pragmas/docs/module context.

## Исторический roadmap (архив)

Ниже сохранён исходный 6-недельный план. Он больше не отражает фактический execution order после частичной реализации `Wave A`, `Wave C` и `Wave Q`.

| Неделя | Фазы | Ключевой результат |
|--------|------|-------------------|
| 1 | A1 + B1 + E-02 | Profile gate работает, EMF рефлексия убрана |
| 2 | A2 + B2 + E-01 | Domain profiles, pragmas/docs/exports |
| 3 | C1 + A3 + E-06 | @ToolMeta, context gating, BmObjectHelper |
| 4 | C2 + B3 + E-03..E-05 | ToolRegistry decomp, structural analysis, batch/forms |
| 5 | C3 + D1 (начало) | Package reorg, orchestrator начат |
| 6 | D1 (завершение) | Sub-agent orchestration |

## Метрики успеха

| Метрика | Текущее | После MVP | После всех фаз |
|---------|---------|-----------|----------------|
| Tools видимых (Explore) | 27 + context gating | 17–27 | 10–15 |
| Tools видимых (Build) | legacy build wide; domain profiles 13–33 | 14–25 | 8 (orch) + 14–25 (sub) |
| EMF reflection в BslSemanticService | residual reflective hot path remains | 0 | 0 |
| Файлов для добавления нового tool | 4 | 4 | 1 |
| BslMethodInfo fields | 10 | 10 | 10 |
| ToolRegistry строк | 353 | 353 | ~100 |
| Max retry latency | 1.3s | 1.3s | 1.3s |
| Structural analysis | нет | нет | call graph + warnings |
| Inter-module resolution | нет | есть | есть |
