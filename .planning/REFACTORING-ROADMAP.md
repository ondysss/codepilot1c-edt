# Roadmap рефакторинга CodePilot1C

**Дата:** 2026-03-22
**Статус:** Утверждён
**Источники исследований:**
- `tool-architecture-refactoring-plan.md` — архитектура tool-системы
- `bsl-tools-optimization-plan.md` — BSL semantic layer
- `tool-count-optimization-plan.md` — оптимизация кол-ва tools
- `sub-agent-orchestration-plan.md` — sub-agent оркестрация
- `qwen35-optimization-plan-v2.md` — backend/Qwen dual-mode transport и provider gating

---

## Обзор: 3 стратегических направления

```
 НАПРАВЛЕНИЕ A                НАПРАВЛЕНИЕ B              НАПРАВЛЕНИЕ C
 Tool Surface &               BSL Semantic               Tool Architecture
 Agent Orchestration          Layer                       & Code Quality
 ─────────────────            ─────────────              ──────────────────
 Фаза 1: Profile gate fix    Фаза 3: Typed BSL API      Фаза 5: ITool metadata
 Фаза 2: Domain profiles     Фаза 4: Новые BSL tools    Фаза 6: ToolRegistry decomp
 Фаза 7: Context gating      Фаза 8: Structural analysis Фаза 9: Package reorg
 Фаза 10: Sub-agent orchestration
```

Фазы пронумерованы по приоритету выполнения. Некоторые выполняются параллельно.

### Параллельный трек Q: Backend / Qwen compatibility

Помимо трёх стратегических направлений, в проекте уже идёт параллельный provider-level трек:
- capability-driven provider gating;
- Qwen dual-mode transport;
- XML priming + content/streaming repair для CodePilot backend.

Этот трек не заменяет roadmap A/B/C, а поддерживает их: уменьшенный tool surface и более строгие профили особенно критичны для Qwen-моделей.

---

## Фаза 1: Починить profile-based filtering

**Приоритет:** КРИТИЧЕСКИЙ — 1 день
**Risk:** Low
**Зависимости:** нет
**Файлы:** `AgentRunner.java`

### Проблема

`AgentProfile.getAllowedTools()` определён в каждом профиле, но **не проверяется** в `AgentRunner.buildRequest()`. Все 67+ tools передаются в каждый LLM-запрос.

### Задачи

| # | Задача | Файл |
|---|--------|------|
| 1.1 | Добавить проверку `profile.getAllowedTools()` в `buildRequest()` | `AgentRunner.java` |
| 1.2 | Добавить trace-лог: кол-во tools до/после фильтрации | `AgentRunner.java` |
| 1.3 | Тест: Explore profile видит только свои 27 tools | `AgentRunnerTest.java` |
| 1.4 | Тест: Build profile видит все свои tools | `AgentRunnerTest.java` |

### Изменение

```java
// AgentRunner.buildRequest() — добавить после resolveProfile()
Set<String> profileAllowed = profile.getAllowedTools();

for (ITool tool : toolRegistry.getAllTools()) {
    String name = tool.getName();

    // NEW: Profile gate
    if (!profileAllowed.isEmpty() && !profileAllowed.contains(name)) {
        continue;
    }

    if (!config.isToolAllowed(name)) continue;
    if (!graphFilter.allows(name)) continue;

    tools.add(toolRegistry.getToolDefinition(tool, context));
}
```

### Результат

| Профиль | До | После |
|---------|-----|-------|
| Explore | ~67 | 27 |
| Plan | ~67 | 27 |
| Build | ~67 | 67 (без изменений — нужна фаза 2) |

### Критерий завершения

- [ ] Profile gate работает — Explore/Plan видят только свои tools
- [ ] Логирование показывает filtered count
- [ ] Тесты проходят

---

## Фаза 2: Разбить Build на domain-профили

**Приоритет:** HIGH — 3–5 дней
**Risk:** Low
**Зависимости:** Фаза 1
**Файлы:** `profiles/*.java` (новые), `AgentRunner.java`, `AgentConfig.java`

### Проблема

Build profile содержит 114 tools — далеко за порогом деградации (30–50, Anthropic). Для Qwen-моделей это особенно критично.

### Новые профили

| Профиль | ID | Tools | Назначение |
|---------|-----|-------|-----------|
| `CodeBuildProfile` | `code-build` | ~20 | BSL-код: редактирование, навигация, типизация |
| `MetadataBuildProfile` | `metadata-build` | ~25 | Метаданные + формы: CRUD объектов конфигурации |
| `QABuildProfile` | `qa-build` | ~18 | Тестирование: Vanessa, YAXUnit, smoke |
| `DCSBuildProfile` | `dcs-build` | ~14 | Система компоновки данных |
| `ExtensionBuildProfile` | `extension-build` | ~16 | Расширения + внешние обработки |
| `RecoveryProfile` | `recovery` | ~12 | Диагностика, smoke, recovery |

### Задачи

| # | Задача | Файл |
|---|--------|------|
| 2.1 | Создать `CodeBuildProfile` (files + bsl + git + edt_content_assist) | `profiles/CodeBuildProfile.java` |
| 2.2 | Создать `MetadataBuildProfile` (files + metadata + forms + edt_validate) | `profiles/MetadataBuildProfile.java` |
| 2.3 | Создать `QABuildProfile` (files + qa_* + smoke) | `profiles/QABuildProfile.java` |
| 2.4 | Создать `DCSBuildProfile` (files + dcs_* + scan_metadata) | `profiles/DCSBuildProfile.java` |
| 2.5 | Создать `ExtensionBuildProfile` (files + extension_* + external_*) | `profiles/ExtensionBuildProfile.java` |
| 2.6 | Создать `RecoveryProfile` (files + smoke + trace + analyze_error) | `profiles/RecoveryProfile.java` |
| 2.7 | Зарегистрировать все профили в profile registry | `AgentRunner.java` или ProfileRegistry |
| 2.8 | Сохранить `BuildAgentProfile` как fallback (если профиль не определён) | `profiles/BuildAgentProfile.java` |
| 2.9 | Тесты для каждого профиля: whitelist корректен | `profiles/*Test.java` |

### Результат

Build 114 tools → 12–25 tools в зависимости от домена.

### Критерий завершения

- [ ] 6 новых профилей созданы и зарегистрированы
- [ ] Каждый профиль содержит только релевантные tools
- [ ] Старый Build profile сохранён как fallback
- [ ] Тесты подтверждают whitelist каждого профиля

---

## Фаза 3: Миграция BslSemanticService на Typed BSL API

**Приоритет:** HIGH — 3–5 дней
**Risk:** Low (API совместим, меняется только внутренняя реализация)
**Зависимости:** нет (параллельно с фазами 1–2)
**Файлы:** `BslSemanticService.java`, `META-INF/MANIFEST.MF`

### Проблема

`BslSemanticService` (1028 строк) использует 100% EMF рефлексию вместо typed BSL model API:
- `eClass().getEStructuralFeature("methods")` вместо `Module.allMethod()`
- `getStringFeature(method, "name")` вместо `method.getName()`
- `getBooleanFeature(method, "export")` вместо `method.isExport()`
- `eClass().getName().contains("procedure")` вместо `method instanceof Procedure`

### Задачи

| # | Задача | Детали |
|---|--------|--------|
| 3.1 | Добавить `com._1c.g5.v8.dt.bsl.model` в `MANIFEST.MF` Import-Package | Импорт пакета модели |
| 3.2 | `resolveModuleContext()`: cast `EObject` → `Module` | `Module module = (Module) root;` |
| 3.3 | `collectMethods()`: `Module.allMethod()` вместо EMF рефлексии | Заменить `getEObjectList(module, "methods")` |
| 3.4 | `collectMethods()`: `method.getName()` вместо `getStringFeature` | Прямой вызов typed API |
| 3.5 | `collectMethods()`: `method.isExport()`, `isAsync()`, `isEvent()` | Заменить `getBooleanFeature` |
| 3.6 | `collectParams()`: `method.getFormalParam()` | Заменить `getEObjectList(method, "formalParams")` |
| 3.7 | `collectParams()`: `param.getName()`, `param.isByValue()` | Typed API |
| 3.8 | `methodKind()`: `instanceof Procedure/Function` | Вместо `eClass().getName().contains(...)` |
| 3.9 | `symbolKind()`: pattern matching по BSL типам | `instanceof Method`, `Variable`, `Invocation` |
| 3.10 | `getSymbolAtPosition()`: typed access к name/container | Вместо `getStringFeature(element, "name")` |
| 3.11 | Удалить helper-методы `getStringFeature()`, `getBooleanFeature()`, `getEObjectList()`, `getEObjectFeature()` | ~80 строк мёртвого кода |
| 3.12 | `computeTypes()`: environment-aware TypesComputer | `Environmental.environments()` вместо `Environments.ALL` |
| 3.13 | `ModuleContext`: заменить `EObject module` → `Module module` | Type safety в record |
| 3.14 | Тесты: все BSL tools возвращают те же результаты | Regression tests |

### Пример: collectMethods() до и после

**До (текущие 48 строк):**
```java
private List<ResolvedMethod> collectMethods(EObject module, String text, LineIndex lineIndex) {
    List<EObject> methods = getEObjectList(module, "methods");
    if (methods.isEmpty()) {
        methods = getEObjectList(module, "allMethods");
    }
    for (EObject method : methods) {
        String name = firstNonBlank(getStringFeature(method, "name"), getStringFeature(method, "nameRu"));
        String kind = methodKind(method);  // eClass().getName().contains("procedure")
        boolean isExport = getBooleanFeature(method, "export");
        boolean isAsync = getBooleanFeature(method, "async");
        boolean isEvent = getBooleanFeature(method, "event");
        List<BslMethodParamInfo> params = collectParams(method);  // getEObjectList(method, "formalParams")
        // ...
    }
}
```

**После (~30 строк):**
```java
private List<ResolvedMethod> collectMethods(Module module, String text, LineIndex lineIndex) {
    List<ResolvedMethod> result = new ArrayList<>();
    for (Method method : module.allMethod()) {
        String name = method.getName();
        String kind = method instanceof Procedure ? "procedure" : "function";
        boolean isExport = method.isExport();
        boolean isAsync = method.isAsync();
        boolean isEvent = method.isEvent();
        List<BslMethodParamInfo> params = collectParams(method);
        // ...
    }
    return result;
}

private List<BslMethodParamInfo> collectParams(Method method) {
    List<BslMethodParamInfo> result = new ArrayList<>();
    for (FormalParam param : method.getFormalParam()) {
        String name = param.getName();
        boolean byValue = param.isByValue();
        String defaultText = param.getDefaultValue() != null
            ? extractLiteralText(param.getDefaultValue()) : null;
        result.add(new BslMethodParamInfo(name, byValue, defaultText));
    }
    return result;
}
```

### Результат

- Удаление ~80 строк EMF reflection helpers
- Compile-time safety вместо runtime string lookups
- Подготовка к фазам 4, 8 (новые BSL tools, structural analysis)

### Критерий завершения

- [ ] Ни одного вызова `eClass().getEStructuralFeature()` в `BslSemanticService`
- [ ] Ни одного вызова `getStringFeature()`, `getBooleanFeature()`, `getEObjectList()`
- [ ] `ModuleContext.module` имеет тип `Module`, не `EObject`
- [ ] Все существующие BSL-тесты проходят без изменений

---

## Фаза 4: Обогащение BSL tools (pragmas, docs, module context)

**Приоритет:** HIGH — 3–5 дней
**Risk:** Low
**Зависимости:** Фаза 3
**Файлы:** `BslSemanticService.java`, `BslMethodInfo.java`, новые result-классы

### Задачи

| # | Задача | EDT API | Эффект для агента |
|---|--------|---------|------------------|
| 4.1 | Добавить `pragmas` в `BslMethodInfo` | `Method.getPragmas()` → `Pragma.getSymbol()` | Агент знает server/client |
| 4.2 | Добавить `documentation` в `BslMethodInfo` | `BslMultiLineCommentDocumentationProvider.getDocumentation()` | JSDoc-комментарии |
| 4.3 | Добавить `isUsed` в `BslMethodInfo` | `Method.isUsed()` | Dead code detection |
| 4.4 | Новый tool `bsl_module_context` | `Module.getModuleType()`, `getOwner()`, `getDefaultPragmas()`, `allDeclareStatements()` | Контекст модуля |
| 4.5 | Новый tool `bsl_module_exports` | `IBslModuleContextDefService.getContextDef()` → `allMethods()`, `allProperties()` | Inter-module resolution |
| 4.6 | Обогатить `bsl_symbol_at_position` deep resolve | `FeatureEntry.getFeature()`, `getEnvironments()` | "Go to definition" |
| 4.7 | Environment-aware TypesComputer | `Environmental.environments()` вместо `Environments.ALL` | Точные типы |
| 4.8 | Зарегистрировать новые tools в `ToolRegistry` | — | — |
| 4.9 | Добавить новые tools в соответствующие профили | `CodeBuildProfile`, `ExploreAgentProfile` | — |
| 4.10 | Тесты для новых tools | — | — |

### Новый tool: bsl_module_context

```json
// Параметры:
{ "projectName": "string", "filePath": "string" }

// Результат:
{
  "moduleType": "FORM_MODULE",
  "owner": "Справочник.Товары.Форма.ФормаЭлемента",
  "defaultPragmas": ["НаКлиенте"],
  "methodCount": 12,
  "exportMethodCount": 3,
  "variables": [
    {"name": "ТекущийТовар", "isExport": false},
    {"name": "РежимРедактирования", "isExport": true}
  ],
  "hasAsyncMethods": true,
  "implicitVariableCount": 5
}
```

### Обогащённый BslMethodInfo

```json
// До:
{"name": "Загрузить", "kind": "procedure", "isExport": true, "startLine": 10, "endLine": 25}

// После:
{
  "name": "Загрузить",
  "kind": "procedure",
  "pragmas": ["НаСервереБезКонтекста"],
  "isExport": true,
  "isUsed": true,
  "isAsync": false,
  "documentation": "Загружает данные из файла.\nПараметры:\n  Путь - путь к файлу",
  "params": [{"name": "Путь", "byValue": true, "default": null}],
  "startLine": 10,
  "endLine": 25
}
```

### Результат

Агент получает полный контекст: тип модуля, доступные директивы, документацию методов, экспорты соседних модулей. Качество генерации кода значительно повышается.

### Критерий завершения

- [ ] `bsl_list_methods` возвращает pragmas и documentation
- [ ] `bsl_module_context` возвращает moduleType, owner, defaultPragmas
- [ ] `bsl_module_exports` возвращает экспортируемые методы/свойства
- [ ] `bsl_symbol_at_position` проходит через FeatureEntry для deep resolve
- [ ] Новые tools зарегистрированы в ToolRegistry и профилях

---

## Фаза 5: Unified tool metadata (@ToolMeta)

**Приоритет:** MEDIUM — 3–5 дней
**Risk:** Medium
**Зависимости:** нет (параллельно с фазами 3–4)
**Файлы:** `ITool.java`, `ToolRegistry.java`, `ToolDescriptorRegistry.java`, `BuiltinToolTaxonomy.java`, все tool-классы

### Проблема

Тройная дупликация метаданных: `ToolRegistry` + `ToolDescriptorRegistry` + `BuiltinToolTaxonomy`. При добавлении нового tool — 3 файла + профили + графы.

### Текущий прогресс на 2026-03-22

- `@ToolMeta`, `AbstractTool` и `ToolParameters` уже внедрены.
- Runtime registration уже синхронизирует `ToolDescriptorRegistry` из `ITool` metadata.
- Public tool surface уже переведён на metadata-first resolution через `getSurfaceCategory()` / `getCategory()` с legacy fallback для совместимости.
- Следующий незакрытый шаг — убрать обязательность ручных `registerDefaults()` и статической `TAXONOMY`.

### Задачи

| # | Задача | Детали |
|---|--------|--------|
| 5.1 | Расширить `ITool` default-методами: `getCategory()`, `isMutating()`, `requiresValidationToken()`, `getTags()` | Обратно-совместимые defaults |
| 5.2 | Создать аннотацию `@ToolMeta` | `name`, `category`, `mutating`, `requiresValidationToken`, `tags`, `surfaceCategory` |
| 5.3 | Создать `AbstractTool` base class | `doExecute(ToolParameters)` + auto CompletableFuture + exception handling |
| 5.4 | Создать `ToolParameters` wrapper | `requireString()`, `optString()`, `requireInt()`, `optInt()`, `requireObject()` |
| 5.5 | Мигрировать 5 пилотных tools на `AbstractTool` + `@ToolMeta` | `ReadFileTool`, `GrepTool`, `GlobTool`, `ListFilesTool`, `EditFileTool` |
| 5.6 | Генерировать `ToolDescriptorRegistry` из `@ToolMeta` / `ITool.getCategory()` | Убрать ручную регистрацию |
| 5.7 | Генерировать `BuiltinToolTaxonomy` из `@ToolMeta` / `ITool.getCategory()` | Убрать ручную Map |
| 5.8 | Мигрировать оставшиеся tools порциями (по 10–15 за итерацию) | Постепенная миграция |
| 5.9 | Удалить `ToolDescriptorRegistry.registerDefaults()` и `BuiltinToolTaxonomy.TAXONOMY` | После полной миграции |

### Пример: tool до и после

**До:**
```java
// 1. ReadFileTool.java
public class ReadFileTool implements ITool {
    @Override public String getName() { return "read_file"; }
    @Override public CompletableFuture<ToolResult> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> {
            String path = (String) params.get("path");
            if (path == null) return ToolResult.failure("Missing 'path'");
            // ...
        });
    }
}

// 2. ToolDescriptorRegistry.java — отдельный файл
register(ToolDescriptor.builder("read_file").category(ToolCategory.FILES).build());

// 3. BuiltinToolTaxonomy.java — ещё один файл
Map.entry("read_file", ToolCategory.FILES_READ_SEARCH)
```

**После:**
```java
@ToolMeta(name = "read_file", category = ToolCategory.FILES,
          surfaceCategory = ToolCategory.FILES_READ_SEARCH)
public class ReadFileTool extends AbstractTool {
    @Override
    protected ToolResult doExecute(ToolParameters params) {
        String path = params.requireString("path");
        // ... нет boilerplate CompletableFuture, нет ручного cast
    }
}
// ToolDescriptorRegistry и BuiltinToolTaxonomy — генерируются автоматически
```

### Результат

- Единый источник правды для tool metadata
- Удаление ~300 строк ручной регистрации
- Compile-time validation параметров
- Добавление нового tool = 1 файл вместо 4

### Критерий завершения

- [ ] `@ToolMeta` аннотация создана
- [ ] `AbstractTool` и `ToolParameters` реализованы
- [ ] Минимум 5 пилотных tools мигрированы
- [ ] `ToolDescriptorRegistry` генерируется из ITool
- [ ] `BuiltinToolTaxonomy` генерируется из ITool

---

## Фаза 6: Декомпозиция ToolRegistry

**Приоритет:** MEDIUM — 3–5 дней
**Risk:** Medium
**Зависимости:** Фаза 5
**Файлы:** `ToolRegistry.java` → 4 новых файла

### Проблема

`ToolRegistry` (~670 строк) — god object: registration + lookup + JSON parsing + execution + tracing + provider resolution.

### Задачи

| # | Задача | Новый файл | Строки из ToolRegistry |
|---|--------|-----------|----------------------|
| 6.1 | Извлечь JSON-парсинг аргументов | `ToolArgumentParser.java` | ~150 строк |
| 6.2 | Извлечь execution + tracing | `ToolExecutionService.java` | ~100 строк |
| 6.3 | Извлечь provider context resolution | `ProviderContextResolver.java` | ~50 строк |
| 6.4 | Оставить в ToolRegistry только registration + lookup | `ToolRegistry.java` | ~100 строк |
| 6.5 | Обновить зависимости в `AgentRunner`, `ChatView` | — | — |
| 6.6 | Тесты: JSON parsing edge cases | `ToolArgumentParserTest.java` | — |

### Результат

`ToolRegistry` сокращается с ~670 до ~100 строк. Каждый компонент отвечает за одну задачу.

### Критерий завершения

- [ ] `ToolRegistry` ≤ 150 строк
- [ ] `ToolArgumentParser` работает автономно с тестами
- [ ] `ToolExecutionService` инкапсулирует tracing/logging
- [ ] Все существующие тесты проходят

---

## Фаза 7: Context-aware gating

**Приоритет:** MEDIUM — 3–5 дней
**Risk:** Low
**Зависимости:** Фаза 1
**Файлы:** Новый `ToolContextGate.java`, `AgentRunner.java`

### Проблема

Tools показываются агенту даже если они бесполезны в текущем контексте (нет DCS-схемы, нет расширений, нет QA-конфига).

### Задачи

| # | Задача | Детали |
|---|--------|--------|
| 7.1 | Создать `ToolContextGate` | Проверки наличия артефактов в проекте |
| 7.2 | Gate: нет DCS-схемы → исключить `dcs_*` (6 tools) | Проверка наличия `.dcs` файлов |
| 7.3 | Gate: нет расширений → исключить `extension_*` (5 tools) | Проверка `IExtensionProjectManager` |
| 7.4 | Gate: нет внешних обработок → исключить `external_*` (5 tools) | Проверка `IExternalObjectProjectManager` |
| 7.5 | Gate: нет QA-конфига → исключить `qa_*` кроме `qa_init_config` (10 tools) | Проверка `.qa/` директории |
| 7.6 | Gate: нет открытого проекта → исключить все EDT tools (44 tools) | Проверка active project |
| 7.7 | Интегрировать в `AgentRunner.buildRequest()` | После profile gate, перед graph gate |
| 7.8 | Кэшировать результат gate на время сессии (5 min TTL) | Не проверять каждый turn |
| 7.9 | Тесты для каждого gate условия | — |

### Результат

Дополнительно −5–44 tools по ситуации, без ручного выбора профиля.

### Критерий завершения

- [ ] `ToolContextGate` создан с 5 gate-условиями
- [ ] Интегрирован в `buildRequest()` pipeline
- [ ] Кэш работает с TTL
- [ ] Тесты покрывают каждое условие

---

## Фаза 8: Structural analysis tool

**Приоритет:** MEDIUM — 5–7 дней
**Risk:** Medium
**Зависимости:** Фаза 3
**Файлы:** Новый `BslMethodAnalyzer.java`, новый `BslAnalyzeMethodTool.java`

### Проблема

Агент получает только текст метода и не может автоматически обнаружить антипаттерны: серверный вызов в цикле, пустой Исключение, неиспользуемые параметры.

### Задачи

| # | Задача | EDT API |
|---|--------|---------|
| 8.1 | Создать `BslMethodAnalyzer` — рекурсивный visitor по AST | `Method.allStatements()`, все Statement подтипы |
| 8.2 | Complexity metrics: branches, loops, try/catch, LOC | `IfStatement`, `WhileStatement`, `ForEachStatement`, `TryExceptStatement` |
| 8.3 | Server call detection в циклах | `Invocation.isIsServerCall()` + `isInsideLoop()` |
| 8.4 | Empty except block detection | `TryExceptStatement.getExceptStatement().isEmpty()` |
| 8.5 | Unused parameter detection | `FormalParam` vs usage analysis |
| 8.6 | Variable flow: assignments, reads | `SimpleStatement.getLeft()/getRight()`, `StaticFeatureAccess` |
| 8.7 | Call graph: callees/callers | `Method.getCallees()`, `Method.getCallers()` |
| 8.8 | Создать `BslAnalyzeMethodTool` | Параметры: project, file, method name |
| 8.9 | Зарегистрировать tool и добавить в профили | `ToolRegistry`, `CodeBuildProfile`, `ExploreAgentProfile` |
| 8.10 | Тесты: detection каждого паттерна | — |

### Результат tool

```json
{
  "method": "ЗагрузитьТовары",
  "pragma": "НаСервере",
  "complexity": {
    "branches": 4,
    "loops": 2,
    "tryCatchBlocks": 1,
    "serverCalls": 3,
    "linesOfCode": 45,
    "cyclomaticComplexity": 7
  },
  "warnings": [
    {"type": "server_call_in_loop", "line": 23,
     "message": "Серверный вызов НайтиПоКоду() внутри цикла Для Каждого"},
    {"type": "empty_except", "line": 38,
     "message": "Пустой блок Исключение — ошибка проглатывается"},
    {"type": "unused_parameter", "line": 10,
     "message": "Параметр 'РежимЗагрузки' не используется в теле метода"}
  ],
  "callGraph": {
    "calls": ["ПроверитьДоступ", "ПрочитатьФайл", "ЗаписатьВБазу"],
    "calledBy": ["КомандаЗагрузкиНажатие", "ОбработкаОповещения"]
  }
}
```

### Критерий завершения

- [ ] `BslMethodAnalyzer` обнаруживает минимум 5 типов проблем
- [ ] Call graph работает через `getCallees()/getCallers()`
- [ ] Tool зарегистрирован и доступен в профилях
- [ ] Тесты на каждый тип warning

---

## Фаза 9: Package reorganization

**Приоритет:** LOW — 2–3 дня
**Risk:** Low (только перемещение файлов)
**Зависимости:** Фаза 5 (чтобы не конфликтовать с @ToolMeta миграцией)
**Файлы:** все `tools/*.java`

### Задачи

| # | Задача |
|---|--------|
| 9.1 | Создать пакеты: `tools/file/`, `tools/git/`, `tools/bsl/`, `tools/metadata/`, `tools/forms/`, `tools/dcs/`, `tools/extension/`, `tools/external/`, `tools/qa/`, `tools/diagnostics/`, `tools/workspace/` |
| 9.2 | Переместить tool-классы в соответствующие пакеты |
| 9.3 | Обновить импорты в `ToolRegistry` |
| 9.4 | Обновить `MANIFEST.MF` Export-Package если нужно |
| 9.5 | Убедиться что сборка проходит: `mvn -DskipTests package` |

### Критерий завершения

- [ ] Каждый пакет содержит только related tools
- [ ] Сборка проходит без ошибок
- [ ] Нет tools в корневом `tools/` пакете (кроме ITool, AbstractTool, ToolRegistry)

---

## Фаза 10: Sub-agent orchestration

**Статус на 2026-03-22:** реализована в текущем planned scope

**Приоритет:** LOW — 1–2 недели
**Risk:** Medium-High
**Зависимости:** Фазы 1, 2, 7
**Файлы:** Новые: `OrchestratorProfile.java`, `DelegateToAgentTool.java`, `ProfileRouter.java`

### Задачи

| # | Задача | Детали |
|---|--------|--------|
| 10.1 | Создать `OrchestratorProfile` | Выполнено |
| 10.2 | Создать `DelegateToAgentTool` | Выполнено |
| 10.3 | Создать `ProfileRouter` | Выполнено |
| 10.4 | Интегрировать desktop routing | Выполнено через `AgentViewAdapter`/`AgentView` |
| 10.5 | Sub-agent context passing | Выполнено (`context` -> delegated prompt) |
| 10.6 | Sub-agent result aggregation | Выполнено через `TaskTool` result formatting |
| 10.7 | Timeout и error handling для sub-agents | Выполнено через `TaskTool` timeout/error path |
| 10.8 | Тесты: routing accuracy | Выполнено |
| 10.9 | Тесты: delegation round-trip | Выполнено |
| 10.10 | Тесты: multi-agent scenario (metadata + qa) | Закрыто в текущем planned scope |

### Когда orchestrator vs direct profile

```
Один домен:
  "Создай справочник Товары" → direct MetadataBuildProfile
  "Напиши функцию расчёта" → direct CodeBuildProfile
  "Запусти smoke-тест" → direct RecoveryProfile

Cross-domain:
  "Создай справочник и напиши тесты" → Orchestrator → metadata + qa
  "Анализируй код и предложи рефакторинг" → Orchestrator → code + metadata
  "Настрой DCS-отчёт с формой" → Orchestrator → dcs + metadata
```

### Критерий завершения

- [x] Orchestrator agent видит ≤8 tools
- [x] `delegate_to_agent` запускает sub-agent с правильным профилем
- [x] `ProfileRouter` корректно определяет domain по ключевым словам
- [x] Cross-domain сценарии работают end-to-end в текущем planned scope

---

## Фаза EDT: EDT API оптимизации (параллельно)

**Приоритет:** MEDIUM — по 1–2 дня на каждую
**Risk:** Low–Medium
**Зависимости:** нет (независимые оптимизации)

| # | Оптимизация | Файлы | Эффект |
|---|-------------|-------|--------|
| E.1 | Read-only BM tasks для inspection tools | `EdtMetadataService`, все read tools | −30% latency |
| E.2 | Retry optimization: 10×300ms → 3× exponential backoff + `IDerivedDataManager.waitAllComputations()` | `BslSemanticService` | −2s max latency |
| E.3 | Batch export: `forceExport(project, List<fqn>)` | Mutation tools | −50% export time |
| E.4 | Event-driven DD waiting | `MetadataProjectReadinessChecker` | Устранение polling |
| E.5 | `IFormItemManagementService` для form tools | `ApplyFormRecipeTool`, `MutateFormModelTool` | Правильные IDs |
| E.6 | `BmObjectHelper` centralization | Все EDT tools | Safe null-check patterns |

---

## Сводная таблица: roadmap

```
Неделя 1:
  ├── Фаза 1: Profile gate fix (1 день)          ██
  ├── Фаза 3: Typed BSL API (3–5 дней)           ████████████
  └── Фаза E.2: Retry optimization (1 день)      ██

Неделя 2:
  ├── Фаза 2: Domain profiles (3–5 дней)          ████████████
  ├── Фаза 4: BSL enrichment (3–5 дней)           ████████████
  └── Фаза E.1: Read-only BM tasks (2 дня)       ████

Неделя 3:
  ├── Фаза 5: @ToolMeta + AbstractTool (3–5 дней) ████████████
  ├── Фаза 7: Context gating (3–5 дней)           ████████████
  └── Фаза E.6: BmObjectHelper (1 день)           ██

Неделя 4:
  ├── Фаза 6: ToolRegistry decomposition (3–5 дн)  ████████████
  ├── Фаза 8: Structural analysis (5–7 дней)       ████████████████
  └── Фаза E.3–E.5: Batch/Forms/DD (3 дня)        ██████

Неделя 5:
  ├── Фаза 8: (продолжение)                        ████████
  ├── Фаза 9: Package reorg (2–3 дня)              ██████
  └── Фаза 10: Sub-agent orchestration (начало)     ████████

Неделя 6:
  └── Фаза 10: (завершение + тестирование)          ████████████████████
```

---

## Метрики успеха

| Метрика | Текущее | После фаз 1–4 | После всех фаз |
|---------|---------|---------------|-----------------|
| Tools видимых агенту (Explore) | ~67 | 17–27 | 10–15 |
| Tools видимых агенту (Build) | ~67 | 14–25 | 8 (orch) + 14–25 (sub) |
| EMF reflection calls в BslSemanticService | ~30 | 0 | 0 |
| Метаданные tool — кол-во файлов для обновления | 4 | 1 | 1 |
| BslMethodInfo fields | 7 | 10 (+ pragmas, docs, isUsed) | 10 |
| Структурный анализ кода | нет | нет | call graph + warnings |
| Inter-module resolution | нет | есть (bsl_module_exports) | есть |
| ToolRegistry строк | ~670 | ~670 | ~100 |
| Max retry latency | 3s | 1.3s | 1.3s |
