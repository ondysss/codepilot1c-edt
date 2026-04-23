# Qwen 1C Developer Eval Suite

Набор сценариев для `LLM-as-user` тестирования через `qwen` + локальный MCP host EDT.

Цель:

- эмулировать реальную работу 1С-разработчика в EDT через Qwen CLI;
- проверять не только финальный ответ модели, но и корректный tool path;
- собирать артефакты для анализа промптов, инструментов и MCP host.

## Как использовать

Каждый сценарий описан JSON-файлом в [`evals/qwen/scenarios`](/Users/alexorlik/repo/codepilot1c-oss/evals/qwen/scenarios).

Базовый runner:

```bash
QWEN_MCP_SERVER=codepilot1clocal \
bash /Users/alexorlik/repo/codepilot1c-oss/tools/run-qwen-mcp-suite.sh \
  --suite /Users/alexorlik/repo/codepilot1c-oss/evals/qwen/suite-greenfield-warehouse.json
```

Точечный запуск одного сценария:

```bash
QWEN_MCP_SERVER=codepilot1clocal \
bash /Users/alexorlik/repo/codepilot1c-oss/tools/run-qwen-mcp-suite.sh \
  --suite /Users/alexorlik/repo/codepilot1c-oss/evals/qwen/suite-typical-accounting.json \
  --scenario-id BUH-002
```

Runner:

- берёт prompt из scenario-spec;
- запускает `qwen` в one-shot режиме с `stream-json` output;
- использует chat recording и `session_id` для поиска Qwen chat log;
- извлекает MCP tool calls и результаты;
- валидирует tool path, mutation flow, `qa_inspect(command=status) -> qa_run`, наличие QA артефактов;
- пишет отчёт в `.runs/qwen-suite/<run-id>/`.

## Локальный workflow: EDT MCP Host + Qwen из папки проекта

Если нужен реальный локальный сценарий, а не внешний/k8s host, используйте:

```bash
PROJECT_DIR=/abs/path/to/edt-project \
bash /Users/alexorlik/repo/codepilot1c-oss/tools/run-qwen-local-edt-suite.sh
```

Этот wrapper:

- создаёт user-writable test copy локальной EDT;
- ставит в неё текущую локальную сборку плагина через `p2 director`;
- поднимает локальный MCP host на `127.0.0.1:8765`;
- создаёт временный `HOME/.qwen` только для этого прогона;
- прописывает туда `codepilot1clocal -> http://127.0.0.1:8765/mcp`;
- запускает `qwen` с `cwd=PROJECT_DIR`.

Ожидаемый режим исполнения:

1. EDT уже запущен локально на macOS.
2. MCP host доступен по `http://127.0.0.1:8765/mcp`.
3. `qwen` подключён к локальному MCP server `codepilot1clocal`.
4. Harness передаёт в Qwen поле `prompt`.
5. Затем harness валидирует:
   - финальный ответ;
   - последовательность вызовов инструментов;
   - наличие trace/QA артефактов;
   - статус `qa_inspect(command=status)`/`qa_run` или post-mutation diagnostics.

## Формат scenario-spec

- `id`: стабильный идентификатор сценария.
- `category`: тип пользовательской задачи.
- `channel`: канал теста. Для этого набора всегда `qwen_mcp`.
- `risk_level`: `low|medium|high`.
- `workspace_requirements`: что должно быть подготовлено в test workspace.
- `actor`: настройки поведения LLM-актёра.
- `prompt`: миссия, которую получает Qwen как будто от реального 1С-разработчика.
- `expected_tool_path`: минимально ожидаемые инструменты и ограничения.
- `assertions`: машинные пост-проверки.
- `artifacts`: какие артефакты должны быть собраны.

## Базовые принципы

- Mutation-сценарии всегда требуют:
  - `edt_validate_request`;
  - передачу `validation_token` без изменений;
  - post-check через `get_diagnostics`.
- Read-only сценарии не должны использовать mutating tools.
- QA-сценарии должны уважать порядок `qa_inspect(command=status) -> qa_run`.
- Успех сценария определяется не только текстом ответа, но и корректным процессом.

## Основные suites

- `qwen-1c-smoke` — безопасная проверка живости runner + Qwen + MCP без мутаций.
- `qwen-1c-greenfield-warehouse` — создание конфигурации складского учета с нуля.
- `qwen-1c-typical-accounting-customization` — доработка типовой `1С:Бухгалтерия`.
- `qwen-1c-developer-core` — компактный базовый набор mixed-сценариев.

Главный фокус набора теперь на mutation-heavy сценариях:

- создание объектов конфигурации;
- доработка существующих объектов типовой конфигурации;
- post-mutation diagnostics;
- smoke/regression QA;
- разбор падений после изменений.

`read-only` сценарии остаются только как отрицательный контроль и не являются основой evaluation suite.

## Сценарии Greenfield Warehouse

- `WH-001` — каркас конфигурации складского учета.
- `WH-002` — справочники `Склады` и `Номенклатура` с корректными типами.
- `WH-003` — документ поступления товаров и табличная часть.
- `WH-004` — регистр накопления остатков и движения документа.
- `WH-005` — формы и post-mutation diagnostics.
- `WH-006` — smoke QA для сценария поступления на склад.

## Сценарии Typical Accounting Customization

- `BUH-001` — найти точку расширения в типовой конфигурации.
- `BUH-002` — добавить реквизит в существующий объект с validation flow.
- `BUH-003` — доработать форму типового объекта.
- `BUH-004` — изменить поведение проведения/обработки без ложных широких правок.
- `BUH-005` — исправить diagnostics/regression после доработки.
- `BUH-006` — targeted QA/regression после изменения типовой конфигурации.

## Что должен собирать runner

- stdout/stderr Qwen.
- `stream-json` вывод Qwen.
- chat/session logs Qwen.
- tool call telemetry Qwen.
- EDT trace артефакты из `agent-runs`.
- MCP trace `mcp.jsonl`.
- tool trace `tools.jsonl`.
- QA run directory с `junit`, `screenshots`, `va.log`.
