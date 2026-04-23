# План следующих шагов по CodePilot1C — Slice 2

**Дата:** 2026-03-24
**Статус:** Фазы 1–3 завершены, S2-14/S2-15 закрыты. Осталось: Фаза 4 (Web Remote UI) и Фаза 5 (Structured ToolResult)
**Основание:** предыдущий execution plan (`next-steps-execution-plan-2026-03-22`) закрыт полностью; все waves (A, B, C, D, Q, E) завершены и верифицированы.
**Источники:** `BACKLOG.md`, `REFACTORING-ROADMAP.md`, `tool-count-optimization-plan.md`, `tool-architecture-refactoring-plan.md`, `qwen35-optimization-plan-v2.md`, текущий working tree diff.

---

## Контекст

Предыдущий slice закрыл foundation:
- profile gate, domain profiles, context gating (Wave A)
- typed BSL hot path, module-level tools, structural analysis (Wave B)
- metadata-first tool surface, registry decomposition, package reorg (Wave C)
- orchestrator profile, profile router, delegation (Wave D)
- Qwen dual-mode transport, schema/example audit, large-result fallback (Wave Q)
- EDT API optimizations: read-only tasks, batch export, event-driven DD, form service, BmObjectHelper (Wave E)

Следующий slice фокусируется на трёх направлениях:
1. **Tool consolidation** — снижение абсолютного числа tools с 67 до ~44 через composite tools;
2. **Web Remote UI** — стабилизация удалённого веб-интерфейса (уже в работе);
3. **Core runtime hardening** — правки LlmResponse, sanitizer, prompt templates, MCP routing.

---

## Принципы выполнения

1. Незакоммиченные 22 файла в working tree — текущая работа; закоммитить или оформить как отдельную ветку до начала Фазы 2.
2. Tool consolidation не должен ломать existing profile whitelists — новые composite tools заменяют старые в allowedTools.
3. Qwen schema/example coverage поддерживается для каждого нового/изменённого tool.
4. Каждая фаза верифицируется targeted tests + `mvn -DskipTests package -q`.

---

## Фаза 1. Стабилизация текущей ветки (P0)

**Оценка:** 1 день
**Зависимости:** нет

### Objective

Закоммитить или разделить на осмысленные коммиты незакоммиченные 22 файла из working tree, чтобы дальнейшая работа шла от чистого состояния.

### Задачи

- `S2-01` `P0` Закоммитить Web Remote UI изменения (`web/remote/app.css`, `app.js`, `index.html`) отдельным коммитом.
- `S2-02` `P0` Закоммитить core runtime fixes (`LlmResponse`, `LlmConversationSanitizer`, `PromptTemplateService`, `McpHostRequestRouter`, `PromptTemplateProvider`, `TracingLlmProvider`, `VibeCorePlugin`) отдельным коммитом.
- `S2-03` `P0` Закоммитить EDT fixes (`EdtPlatformDocumentationService`, `ValidationOperation`, `EdtExtensionSmokeTool`) отдельным коммитом.
- `S2-04` `P0` Закоммитить UI/tooling (`ChatView`, `run-qwen-mcp-suite.py`) и planning cleanup отдельными коммитами.
- `S2-05` `P0` Проверить `mvn -DskipTests package -q` после всех коммитов.

### Done When

- Working tree чистый.
- Reactor build зелёный.
- Каждый коммит атомарный и осмысленный.

---

## Фаза 2. Tool Consolidation — Composite Tools (P1)

**Оценка:** 3-4 дня
**Зависимости:** Фаза 1

### Objective

Снизить абсолютное число built-in tools с ~67 до ~44 через объединение domain-группы в composite tools с параметром `command`. Это даёт дополнительное снижение tool surface поверх уже работающего profile filtering.

### Задачи

- `S2-06` `P1` Создать `DcsManageTool` (composite, `command: "get_summary|list_nodes|create_schema|upsert_dataset|upsert_param|upsert_field"`), заменяющий 6 отдельных DCS tools. (−5 tools)
  Files: новый `DcsManageTool.java`, удалить 6 старых DCS tools
  API: делегирует в существующий DCS service layer

- `S2-07` `P1` Создать `ExtensionManageTool` (composite, `command: "list_projects|list_objects|create|adopt|set_state"`), заменяющий 5 отдельных Extension tools. (−4 tools)
  Files: новый `ExtensionManageTool.java`, удалить 5 старых Extension tools

- `S2-08` `P1` Создать `ExternalManageTool` (composite, `command: "list_projects|list_objects|details|create_report|create_processing"`), заменяющий 5 отдельных External tools. (−4 tools)
  Files: новый `ExternalManageTool.java`, удалить 5 старых External tools

- `S2-09` `P1` Создать `EdtSmokeTool` (composite, `command: "metadata|trace|extension|external|analyze_error|update_infobase|launch"`), заменяющий 7 отдельных smoke/recovery tools. (−6 tools)
  Files: новый `EdtSmokeTool.java`, удалить 7 старых smoke tools

- `S2-10` `P2` Создать `QaInspectTool` (composite, `command: "explain|status|search_steps"`), объединяющий 3 read-only QA tools. (−2 tools)
  Files: новый `QaInspectTool.java`, удалить 3 старых QA tools

- `S2-11` `P2` Создать `QaGenerateTool` (composite, `command: "init|migrate|compile"`), объединяющий 3 write QA tools. (−2 tools)
  Files: новый `QaGenerateTool.java`, удалить 3 старых QA tools

- `S2-12` `P1` Обновить все profile whitelists: заменить старые имена на composite имена в `CodeBuildProfile`, `MetadataBuildProfile`, `QABuildProfile`, `DCSBuildProfile`, `ExtensionBuildProfile`, `RecoveryProfile`, `OrchestratorProfile`.
  Files: все profile-классы

- `S2-13` `P1` Обновить `ToolContextGate`: заменить pattern-matching по старым `dcs_*`, `extension_*`, `external_*`, `qa_*` на новые composite имена.
  Files: `ToolContextGate.java`

- `S2-14` `P1` Обновить `ToolGraphDefinitions` для новых composite tools.
  Files: `ToolGraphDefinitions.java`

- `S2-15` `P1` Обновить `QwenToolCallExamples` — добавить example params для каждого composite tool с command dispatch.
  Files: `QwenToolCallExamples.java`

- `S2-16` `P1` Тесты: composite dispatch для каждого нового tool, profile whitelist regression, Qwen example coverage.
  Files: новые и обновлённые тесты

### Done When

- Built-in tool count: 67 → ~44.
- Все profiles корректно ссылаются на composite tools.
- Context gate и graph definitions синхронизированы.
- Qwen example coverage не деградировала.
- `mvn -pl bundles/com.codepilot1c.core.tests -am test -q` и `mvn -DskipTests package -q` зелёные.

### Риски

- Composite tools с `command` параметром могут быть менее точны для Qwen-моделей, чем отдельные tools — требуется A/B тестирование.
- Refactoring затрагивает много файлов одновременно — нужны поэтапные коммиты по domain-группам.

---

## Фаза 3. Deferred Tool Loading для OpenAI-compatible API (P2)

**Оценка:** 2-3 дня
**Зависимости:** Фаза 2

### Objective

Реализовать `discover_tools` meta-tool для OpenAI-compatible API (Qwen, Ollama), который позволяет LLM загружать domain-specific tools по запросу, а не видеть все 44 в каждом запросе.

### Задачи

- `S2-17` `P2` Определить core tool set (всегда загружены): `read_file`, `list_files`, `edit_file`, `write_file`, `grep`, `glob`, `git_inspect`, `git_mutate`, `task`, `skill`, `discover_tools`. (~11 tools, ~2.5k tokens)
  Files: `ToolRegistry.java`, новая конфигурация

- `S2-18` `P2` Реализовать `DiscoverToolsTool`: параметр `category: "dcs|qa|extension|bsl|forms|metadata|diagnostics|workspace"`, возвращает tool definitions для запрошенной категории.
  Files: новый `DiscoverToolsTool.java`

- `S2-19` `P2` Интегрировать deferred loading в `AgentRunner.buildRequest()`: core tools + ранее discovered tools.
  Files: `AgentRunner.java`

- `S2-20` `P2` Добавить capability gate: deferred loading активируется только для non-Anthropic providers (`!capabilities.supportsNativeDeferredLoading()`).
  Files: `ProviderCapabilities.java`, `AgentRunner.java`

- `S2-21` `P2` Тесты: discover→inject→call round-trip, category validation, Qwen example coverage.
  Files: тесты

### Done When

- OpenAI-compatible провайдеры видят ~11 core tools вместо ~44.
- Категории загружаются по запросу через `discover_tools`.
- Anthropic providers не затронуты (используют native deferred loading).
- `mvn -DskipTests package -q` зелёный.

### Риски

- Deferred loading добавляет 1 extra turn для discovery — может замедлить простые задачи.
- LLM может не всегда правильно решать, какую категорию загрузить — требуется prompt tuning.

---

## Фаза 4. Web Remote UI Hardening (P2)

**Оценка:** 2 дня
**Зависимости:** Фаза 1

### Objective

Стабилизировать веб-интерфейс удалённого управления: обработка ошибок, reconnect, авторизация, responsive layout.

### Задачи

- `S2-22` `P2` Реализовать WebSocket reconnect с exponential backoff при потере соединения.
  Files: `web/remote/app.js`

- `S2-23` `P2` Добавить серверную авторизацию по token/session для remote endpoint.
  Files: `McpHostRequestRouter.java`, `web/remote/app.js`

- `S2-24` `P2` Улучшить error handling: connection lost indicator, message delivery confirmation.
  Files: `web/remote/app.js`, `web/remote/app.css`

- `S2-25` `P3` Responsive layout: mobile/tablet breakpoints.
  Files: `web/remote/app.css`, `web/remote/index.html`

- `S2-26` `P2` Добавить smoke-тест для remote endpoint lifecycle.
  Files: тесты

### Done When

- Remote UI стабильно работает при потере/восстановлении соединения.
- Unauthorized доступ блокируется.
- UI адаптируется к разным экранам.

---

## Фаза 5. Structured ToolResult для Tool Chaining (P3)

**Оценка:** 2 дня
**Зависимости:** Фаза 2

### Objective

Добавить `JsonObject structuredData` в `ToolResult`, чтобы composite и chained tools могли передавать machine-readable данные без повторного парсинга текста.

### Задачи

- `S2-27` `P3` Расширить `ToolResult`: добавить `JsonObject structuredData` field, `success(content, structured)` factory, `getStructured(key, type)` accessor.
  Files: `ToolResult.java`

- `S2-28` `P3` Мигрировать 5 пилотных tools на structured result: `bsl_list_methods`, `bsl_module_context`, `bsl_module_exports`, `scan_metadata_index`, `edt_metadata_details`.
  Files: 5 tool-классов

- `S2-29` `P3` Добавить structured data passthrough в `DelegateToAgentTool` для sub-agent orchestration.
  Files: `DelegateToAgentTool.java`

- `S2-30` `P3` Тесты: structured data round-trip, backward compatibility (old tools без structured data).
  Files: тесты

### Done When

- `ToolResult` поддерживает structured data без breaking change для текущих consumers.
- Pilot tools возвращают machine-readable JSON наряду с text content.
- Delegation round-trip сохраняет structured data.

---

## Резюме по фазам

| Фаза | Описание | Приоритет | Оценка | Зависимости | Ключевой результат |
|-------|----------|-----------|--------|-------------|-------------------|
| 1 | Стабилизация ветки | P0 | 1 день | нет | Чистый working tree, reactor green |
| 2 | Tool consolidation | P1 | 3-4 дня | Фаза 1 | 67→44 tools, composite dispatch |
| 3 | Deferred tool loading | P2 | 2-3 дня | Фаза 2 | 44→11 видимых tools для Qwen/OpenAI |
| 4 | Web Remote UI | P2 | 2 дня | Фаза 1 | Reconnect, auth, responsive |
| 5 | Structured ToolResult | P3 | 2 дня | Фаза 2 | Machine-readable tool chaining |

**Параллелизм:** Фазы 2 и 4 могут выполняться параллельно после завершения Фазы 1. Фаза 3 начинается только после Фазы 2. Фаза 5 — optional, начинается по ситуации.

---

## Граф зависимостей

```
Фаза 1 (стабилизация)
   ├──→ Фаза 2 (tool consolidation) ──→ Фаза 3 (deferred loading)
   │                                  └──→ Фаза 5 (structured result)
   └──→ Фаза 4 (web remote UI)
```

---

## Порядок обновления backlog после выполнения

1. Обновить `BACKLOG.md`: добавить items `S2-01..S2-30` со статусами.
2. После каждой фазы: зафиксировать зелёный targeted/full test path и reactor build.
3. После Фазы 2: обновить `tool-count-optimization-plan.md` — перевести Level 2 в `done`.
4. После Фазы 3: обновить `tool-count-optimization-plan.md` — перевести Level 4 в `done`.
