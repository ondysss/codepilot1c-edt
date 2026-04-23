# Оркестрация sub-агентов и оптимизация tool surface

**Дата:** 2026-03-22
**Статус:** Реализовано в текущем planned scope
**Зависимости:** tool-count-optimization-plan.md, tool-architecture-refactoring-plan.md

## Implementation Note

На 2026-03-22 поверх этого исследования реализованы:
- `OrchestratorProfile`
- `ProfileRouter`
- `delegate_to_agent`
- auto/domain routing в `task`
- desktop UI auto-routing через `AgentViewAdapter`/`AgentView`

Верификация закрыта через `ProfileRouterTest`, `TaskToolTest`, `DelegateToAgentToolTest`, `AgentProfileRegistryTest`, `PromptSnapshotTest`, `QwenToolingCompatibilityTest`, full `core.tests` и full reactor build.

---

## Проблема

Текущее кол-во tools, видимых агенту:
- **Explore/Plan профиль:** 27 tools (определено, но фильтрация **не работает** — баг)
- **Build профиль:** 114 tools (катастрофа)
- **Фактически видимых:** ~67 built-in + dynamic/MCP

Порог деградации accuracy (Anthropic): **30–50 tools**.
Оптимальная зона: **3–7 tools**.

---

## Критический баг: Profile filtering не работает

### Где

`AgentRunner.buildRequest()` — цикл сборки tools для LLM request.

### Суть

`AgentProfile.getAllowedTools()` **определён** в каждом профиле (Explore: 27, Plan: 27, Build: 114), но **не проверяется** при фильтрации. Проверяются только:
1. `AgentConfig.isToolAllowed(toolName)` — explicit enable/disable
2. `ToolGraphToolFilter.allows(toolName)` — state-machine routing

Profile whitelist игнорируется → все tools попадают в request.

### Fix

```java
// AgentRunner.buildRequest() — добавить profile gate
Set<String> profileAllowed = profile.getAllowedTools();

for (ITool tool : toolRegistry.getAllTools()) {
    String name = tool.getName();

    // Profile gate (NEW)
    if (!profileAllowed.isEmpty() && !profileAllowed.contains(name)) {
        continue;
    }

    // Existing gates
    if (!config.isToolAllowed(name)) continue;
    if (!graphFilter.allows(name)) continue;

    tools.add(toolRegistry.getToolDefinition(tool, context));
}
```

---

## 4-уровневый план оптимизации

### Уровень 1: Починить profile gate (1 день)

**Файлы:** `AgentRunner.java`

**Действие:** Добавить проверку `profile.getAllowedTools()` в `buildRequest()`.

**Результат:**
| Профиль | До (фактически) | После |
|---------|-----------------|-------|
| Explore | ~67 | 27 |
| Plan | ~67 | 27 |
| Build | ~67 | 114 (нужен уровень 2) |

**Risk:** Low — профили уже определяют whitelist, просто не проверяется.

---

### Уровень 2: Разбить Build на domain-профили (3–5 дней)

**Файлы:**
- Новые: `CodeBuildProfile.java`, `MetadataBuildProfile.java`, `QABuildProfile.java`, `DCSBuildProfile.java`, `ExtensionBuildProfile.java`, `RecoveryProfile.java`
- Модифицировать: `AgentRunner.java` (profile resolution), `AgentConfig.java` (новые profile names)

#### CodeBuildProfile (~20 tools)

Работа с BSL-кодом: редактирование модулей, навигация, типизация.

```
read_file, edit_file, write_file, list_files, grep, glob
bsl_symbol_at_position, bsl_type_at_position, bsl_scope_members,
bsl_list_methods, bsl_get_method_body
edt_content_assist, edt_find_references, inspect_platform_reference
git_inspect, git_mutate
ensure_module_artifact
skill
```

#### MetadataBuildProfile (~25 tools)

Создание/изменение объектов метаданных, форм, модулей.

```
read_file, edit_file, write_file, list_files, grep, glob
scan_metadata_index, edt_metadata_details, edt_field_type_candidates,
inspect_platform_reference
edt_validate_request
create_metadata, add_metadata_child, update_metadata, delete_metadata
ensure_module_artifact
create_form, apply_form_recipe, inspect_form_layout, mutate_form_model
git_inspect, git_mutate
skill
```

#### QABuildProfile (~18 tools)

Тестирование: Vanessa, YAXUnit, smoke-тесты.

```
read_file, edit_file, write_file, list_files, grep, glob
qa_init_config, qa_explain_config, qa_status, qa_migrate_config,
qa_run, qa_prepare_form_context, qa_plan_scenario,
qa_compile_feature, qa_validate_feature, qa_steps_search
author_yaxunit_tests
edt_metadata_smoke
```

#### DCSBuildProfile (~14 tools)

Система компоновки данных.

```
read_file, list_files, grep, glob
dcs_get_summary, dcs_list_nodes, dcs_create_main_schema,
dcs_upsert_query_dataset, dcs_upsert_parameter, dcs_upsert_calculated_field
scan_metadata_index, edt_metadata_details
git_inspect, git_mutate
```

#### ExtensionBuildProfile (~16 tools)

Расширения и внешние обработки.

```
read_file, edit_file, write_file, list_files, grep, glob
extension_list_projects, extension_list_objects,
extension_create_project, extension_adopt_object, extension_set_property_state
external_list_projects, external_list_objects, external_get_details,
external_create_report, external_create_processing
```

#### RecoveryProfile (~12 tools)

Диагностика, smoke-тесты, восстановление.

```
read_file, list_files, grep, glob
edt_metadata_smoke, edt_trace_export, edt_extension_smoke,
edt_external_smoke, analyze_tool_error
edt_update_infobase, edt_launch_app
git_inspect
```

**Результат:** Build 114 tools → 12–25 tools в зависимости от домена.

---

### Уровень 3: Sub-agent orchestration (1–2 недели)

**Архитектура:**

```
User Prompt
     │
     ▼
┌────────────────────────────────────────────────┐
│              ORCHESTRATOR AGENT                │
│                                                │
│  Профиль: OrchestratorProfile                  │
│  Tools (~8):                                   │
│  • read_file, list_files, grep, glob           │
│  • delegate_to_agent(agentType, task, context) │
│  • task (управление задачами)                  │
│  • skill                                       │
│                                                │
│  Роль:                                         │
│  1. Понять задачу пользователя                 │
│  2. Декомпозировать на подзадачи               │
│  3. Выбрать подходящий sub-agent               │
│  4. Делегировать выполнение                    │
│  5. Собрать и представить результат             │
│                                                │
│  Ограничения:                                  │
│  • НЕ редактирует файлы напрямую               │
│  • НЕ вызывает domain-specific tools           │
│  • Может читать файлы для анализа задачи       │
└──────────────┬─────────────────────────────────┘
               │
      ┌────────┼────────┬──────────┬──────────┬──────────┐
      ▼        ▼        ▼          ▼          ▼          ▼
┌────────┐┌────────┐┌────────┐┌────────┐┌────────┐┌────────┐
│BSL/Code││Metadata││  QA    ││  DCS   ││Ext/Ext ││Recovery│
│ Agent  ││ Agent  ││ Agent  ││ Agent  ││ Agent  ││ Agent  │
│        ││        ││        ││        ││        ││        │
│~20     ││~25     ││~18     ││~14     ││~16     ││~12    │
│tools   ││tools   ││tools   ││tools   ││tools   ││tools  │
└────────┘└────────┘└────────┘└────────┘└────────┘└────────┘
```

#### Новый tool: `delegate_to_agent`

```json
{
  "name": "delegate_to_agent",
  "description": "Delegate a task to a specialized sub-agent with domain-specific tools",
  "parameters": {
    "agentType": {
      "type": "string",
      "enum": ["code", "metadata", "qa", "dcs", "extension", "recovery"],
      "description": "Type of sub-agent to delegate to"
    },
    "task": {
      "type": "string",
      "description": "Clear description of the task for the sub-agent"
    },
    "context": {
      "type": "string",
      "description": "Additional context: project name, file paths, constraints"
    }
  }
}
```

#### Реализация delegation

```java
public class DelegateToAgentTool implements ITool {

    private static final Map<String, String> AGENT_PROFILES = Map.of(
        "code",      "code-build",
        "metadata",  "metadata-build",
        "qa",        "qa-build",
        "dcs",       "dcs-build",
        "extension", "extension-build",
        "recovery",  "recovery"
    );

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String agentType = (String) params.get("agentType");
        String task = (String) params.get("task");
        String context = (String) params.get("context");

        String profileName = AGENT_PROFILES.get(agentType);

        AgentConfig subConfig = AgentConfig.builder()
            .profileName(profileName)
            .maxSteps(30)
            .timeoutMs(5 * 60 * 1000)
            .toolGraphEnabled(true)
            .toolGraphPolicy(ToolGraphPolicy.ADVISORY)
            .build();

        String subPrompt = buildSubAgentPrompt(task, context);

        AgentRunner subRunner = new AgentRunner(toolRegistry, providerRegistry);
        AgentResult result = subRunner.run(subPrompt, subConfig);

        return ToolResult.success(result.getFinalResponse());
    }
}
```

#### OrchestratorProfile

```java
public class OrchestratorProfile implements AgentProfile {

    private static final Set<String> ALLOWED = Set.of(
        "read_file", "list_files", "grep", "glob",
        "delegate_to_agent", "task", "skill"
    );

    @Override public String getId() { return "orchestrator"; }
    @Override public Set<String> getAllowedTools() { return ALLOWED; }
    @Override public int getMaxSteps() { return 50; }
    @Override public long getTimeoutMs() { return 15 * 60 * 1000; }
    @Override public boolean isReadOnly() { return true; } // сам не пишет
}
```

#### Когда использовать orchestrator vs прямой профиль

| Сценарий | Подход |
|----------|--------|
| "Создай справочник Товары" | Прямой → MetadataBuildProfile |
| "Напиши процедуру загрузки данных" | Прямой → CodeBuildProfile |
| "Создай справочник, форму, и напиши тесты" | Orchestrator → metadata + qa |
| "Проанализируй проект и предложи улучшения" | Orchestrator → code + metadata |
| "Настрой DCS-отчёт с тестами" | Orchestrator → dcs + qa |

**Правило:** Если задача укладывается в один домен → прямой профиль. Если cross-domain → orchestrator.

#### Выбор профиля: автоматический routing

```java
public class ProfileRouter {

    // Ключевые слова → профиль
    private static final Map<String, String> KEYWORD_ROUTES = Map.ofEntries(
        // Code
        Map.entry("процедур", "code-build"),
        Map.entry("функци", "code-build"),
        Map.entry("модул", "code-build"),
        Map.entry("bsl", "code-build"),

        // Metadata
        Map.entry("справочник", "metadata-build"),
        Map.entry("документ", "metadata-build"),
        Map.entry("регистр", "metadata-build"),
        Map.entry("реквизит", "metadata-build"),
        Map.entry("форм", "metadata-build"),

        // QA
        Map.entry("тест", "qa-build"),
        Map.entry("vanessa", "qa-build"),
        Map.entry("yaxunit", "qa-build"),
        Map.entry("сценари", "qa-build"),

        // DCS
        Map.entry("скд", "dcs-build"),
        Map.entry("отчёт", "dcs-build"),
        Map.entry("компоновк", "dcs-build"),

        // Extension
        Map.entry("расширени", "extension-build"),
        Map.entry("обработк", "extension-build")
    );

    public String route(String prompt) {
        String lower = prompt.toLowerCase();
        Set<String> matchedProfiles = new HashSet<>();

        for (var entry : KEYWORD_ROUTES.entrySet()) {
            if (lower.contains(entry.getKey())) {
                matchedProfiles.add(entry.getValue());
            }
        }

        if (matchedProfiles.size() == 1) {
            return matchedProfiles.iterator().next();  // Прямой профиль
        } else if (matchedProfiles.size() > 1) {
            return "orchestrator";  // Cross-domain → orchestrator
        }
        return "code-build";  // Default
    }
}
```

---

### Уровень 4: Context-aware gating (3–5 дней, параллельно)

**Файлы:** Новый `ToolContextGate.java`, модификация `AgentRunner.buildRequest()`

#### Реализация

```java
public class ToolContextGate {

    // Tool groups for gating
    private static final Set<String> DCS_TOOLS = Set.of(
        "dcs_get_summary", "dcs_list_nodes", "dcs_create_main_schema",
        "dcs_upsert_query_dataset", "dcs_upsert_parameter", "dcs_upsert_calculated_field"
    );

    private static final Set<String> EXTENSION_TOOLS = Set.of(
        "extension_list_projects", "extension_list_objects",
        "extension_create_project", "extension_adopt_object", "extension_set_property_state"
    );

    private static final Set<String> EXTERNAL_TOOLS = Set.of(
        "external_list_projects", "external_list_objects", "external_get_details",
        "external_create_report", "external_create_processing"
    );

    private static final Set<String> QA_TOOLS_EXCEPT_INIT = Set.of(
        "qa_explain_config", "qa_status", "qa_migrate_config", "qa_run",
        "qa_prepare_form_context", "qa_plan_scenario", "qa_compile_feature",
        "qa_validate_feature", "qa_steps_search", "author_yaxunit_tests"
    );

    public Set<String> computeExcludedTools(IProject project) {
        Set<String> excluded = new HashSet<>();

        if (project == null) {
            // Нет открытого проекта — исключить ВСЕ EDT tools
            excluded.addAll(DCS_TOOLS);
            excluded.addAll(EXTENSION_TOOLS);
            excluded.addAll(EXTERNAL_TOOLS);
            excluded.addAll(QA_TOOLS_EXCEPT_INIT);
            // + все BSL, metadata, forms tools
            return excluded;
        }

        // Проверки на наличие артефактов в проекте
        if (!hasDcsSchemas(project)) {
            excluded.addAll(DCS_TOOLS);
        }
        if (!hasExtensionProjects(project)) {
            excluded.addAll(EXTENSION_TOOLS);
        }
        if (!hasExternalProjects(project)) {
            excluded.addAll(EXTERNAL_TOOLS);
        }
        if (!hasQaConfig(project)) {
            excluded.addAll(QA_TOOLS_EXCEPT_INIT);
        }

        return excluded;
    }
}
```

#### Интеграция в buildRequest()

```java
// AgentRunner.buildRequest() — финальная версия с 4 уровнями фильтрации
Set<String> profileAllowed = profile.getAllowedTools();
Set<String> contextExcluded = toolContextGate.computeExcludedTools(activeProject);

for (ITool tool : toolRegistry.getAllTools()) {
    String name = tool.getName();

    // Level 1: Profile gate
    if (!profileAllowed.isEmpty() && !profileAllowed.contains(name)) continue;

    // Level 2: Context gate
    if (contextExcluded.contains(name)) continue;

    // Level 3: Config gate (explicit enable/disable)
    if (!config.isToolAllowed(name)) continue;

    // Level 4: Graph gate (state-machine routing)
    if (!graphFilter.allows(name)) continue;

    tools.add(toolRegistry.getToolDefinition(tool, context));
}
```

---

## Влияние на количество видимых tools

### Сценарий: Типичный проект 1С (справочники + документы, нет DCS/расширений/QA)

| Уровень оптимизации | Explore | Build (Code) | Build (Metadata) |
|---------------------|---------|-------------|------------------|
| **Текущее** (все баги) | ~67 | ~67 | ~67 |
| **+Level 1** (profile gate fix) | 27 | 114 | 114 |
| **+Level 2** (split Build) | 27 | 20 | 25 |
| **+Level 3** (orchestrator) | 27 | 8 (orch) + 20 (sub) | 8 (orch) + 25 (sub) |
| **+Level 4** (context gate) | 17 | 8 (orch) + 15 (sub) | 8 (orch) + 20 (sub) |

### Сценарий: Полный проект (DCS + расширения + QA)

| Уровень | Без оптимизации | С оркестрацией |
|---------|----------------|----------------|
| Видимых tools | 67–114 | 8 (orchestrator) |
| Sub-agent tools | — | 14–25 (по домену) |
| Ожидаемая accuracy | ~60% | ~92% |
| Token overhead | ~17k–28k | ~2k (orch) + ~5k (sub) |

---

## Порядок реализации

| Фаза | Описание | Усилие | Эффект | Risk |
|------|----------|--------|--------|------|
| **1** | Fix profile gate в `buildRequest()` | 1 день | 67→27 (Explore/Plan) | Low |
| **2** | Split BuildAgentProfile на 6 domain-profiles | 3–5 дней | 114→14–25 | Low |
| **3** | Context-aware gating (`ToolContextGate`) | 3–5 дней | Ещё −5–20 по ситуации | Low |
| **4** | ProfileRouter (автовыбор профиля по промпту) | 2–3 дня | Правильный профиль без ручного выбора | Low |
| **5** | Orchestrator profile + DelegateToAgentTool | 5–7 дней | 8 tools для cross-domain задач | Medium |
| **6** | Sub-agent execution в AgentRunner | 3–5 дней | Полная изоляция sub-agents | Medium |
| **7** | Consolidation (DCS 6→1, Extension 5→1, etc.) | 5–7 дней | Абсолютное кол-во tools −23 | Medium |

**Общая оценка:** 3–5 недель для полной реализации всех уровней.
**MVP (уровни 1–2):** 4–6 дней — даёт основной эффект (67→14–25 tools).
