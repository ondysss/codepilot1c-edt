# CodePilot1C (EDT plugin)

Плагин для 1C:EDT на базе Eclipse RCP/OSGi (open source).

## Актуальные артефакты

- Последний релиз: `v0.1.7.20260329-0855` — <https://github.com/ondysss/codepilot1c-edt/releases/tag/v0.1.7.20260329-0855>
- Update site (GitHub Pages): <https://ondysss.github.io/codepilot1c-edt/>
- GitHub Packages (Maven, ZIP): <https://github.com/ondysss/codepilot1c-edt/packages/2846572>
- Telegram-канал: <https://t.me/codepilot1c>
- Группа поддержки: <https://t.me/ai_1c_dev>

## Установка

### Вариант A (рекомендуется): Update Site (GitHub Pages)

URL update site: `https://ondysss.github.io/codepilot1c-edt/`

1. В 1C:EDT откройте `Справка -> Установить новое ПО...` (`Help -> Install New Software...`).
2. Нажмите `Добавить...` (`Add...`) и добавьте сайт:
   - `Name`: `codepilot`
   - `Location`: `https://ondysss.github.io/codepilot1c-edt/`
3. В `Work with:` выберите `codepilot - https://ondysss.github.io/codepilot1c-edt/`.
4. Отметьте `1C Copilot`, нажмите `Next`, примите лицензию и нажмите `Finish`.
5. Подтвердите окна доверия (`Trust Authorities` и `Trust Artifacts`) кнопкой `Trust Selected`.
6. Перезапустите EDT.

### Вариант B: ZIP (offline)

1. Откройте GitHub Releases и скачайте update-site ZIP (обычно `com.codepilot1c.update-*.zip`).
2. В 1C:EDT откройте `Help -> Install New Software...`.
3. Нажмите `Add...`.
4. Нажмите `Archive...` и выберите скачанный ZIP.
5. Выберите `1C Copilot`, нажмите `Next` и пройдите мастер установки.
6. При необходимости подтвердите окна доверия и перезапустите EDT.

## Сборка

Требования: JDK 17.

```bash
mvn -DskipTests package
```

## Локальный E2E workflow для EDT

Для полного локального цикла `build -> p2 update -> relaunch EDT -> MCP smoke -> qa_inspect(command=status) -> qa_run`
используйте:

```bash
EDT_HOME=/path/to/test-1cedt \
EDT_WORKSPACE=/path/to/test-workspace \
EDT_PROJECT_PATHS=/abs/path/to/project \
QA_PROJECT_NAME=MyProject \
tools/run-edt-e2e-local.sh
```

Скрипт:

- собирает полный reactor через `mvn -DskipTests package`;
- обновляет выделенную test-инсталляцию EDT через локальный p2 site
  `repositories/com.codepilot1c.update/target/repository`;
- патчит `bundles.info` для auto-start `com.codepilot1c.core` в headless режиме;
- поднимает EDT с MCP host на `http://127.0.0.1:8765/mcp`;
- выполняет `tools/list`, затем `qa_inspect(command=status)` и `qa_run` через MCP;
- складывает логи и trace-артефакты в `.runs/edt-e2e/<run-id>/`.

Ключевые переменные:

- `EDT_HOME` — обязательная test-инсталляция 1C:EDT.
- `EDT_WORKSPACE` — отдельный workspace для прогона.
- `EDT_PROJECT_PATHS` — список проектов через `:`, которые будут смонтированы в workspace симлинками.
- `QA_PROJECT_NAME` — EDT project name для `qa_inspect(command=status)`/`qa_run`.
- `MCP_BEARER_TOKEN` — опционально; если не задан, скрипт создаст временный токен на один прогон.
- `RUN_QA=false` — только build/update/launch/smoke без QA запуска.

## Локальный flow: Implementer -> Codex review loop

Если нужно прогонять простые coding-задачи через внешний implementer, а затем автоматически отправлять diff на review в `codex` и возвращать найденные дефекты обратно в тот же session, используйте один из wrappers:

```bash
bash /Users/alexorlik/repo/codepilot1c-oss/tools/run-qwen-codex-flow.sh /abs/path/to/task.md
bash /Users/alexorlik/repo/codepilot1c-oss/tools/run-claude-codex-flow.sh /abs/path/to/task.md
```

или через stdin:

```bash
echo "Fix the failing test in MetadataSyncService and keep the scope minimal." \
  | bash /Users/alexorlik/repo/codepilot1c-oss/tools/run-qwen-codex-flow.sh -
echo "Fix the failing test in MetadataSyncService and keep the scope minimal." \
  | bash /Users/alexorlik/repo/codepilot1c-oss/tools/run-claude-codex-flow.sh -
```

Скрипт:

- создаёт отдельную `git worktree` от `BASE_BRANCH` для задачи;
- запускает выбранный implementer на реализацию в этой worktree;
- запускает `codex exec` с JSON schema на текущий diff;
- если Codex возвращает `NEEDS_FIXES`, отправляет review JSON обратно в тот же implementer session;
- повторяет цикл до `MAX_ROUNDS`, затем оставляет worktree на ручной разбор.

Основные переменные:

- `IMPLEMENTER=qwen|claude` — базовый переключатель в общем flow script.
- `BASE_BRANCH` — базовая ветка для новой worktree; по умолчанию берётся `origin/HEAD`, затем `main`/`master`.
- `MAX_ROUNDS=3` — максимум review/fix циклов.
- `KEEP_WORKTREE=true|false` — сохранять ли worktree после неуспеха.
- `CLEAN_WORKTREE_ON_SUCCESS=true|false` — удалять ли worktree после успешного review.
- `QWEN_MODEL`, `QWEN_AUTH_TYPE`, `QWEN_APPROVAL_MODE` — настройки `qwen`.
- `CLAUDE_MODEL=claude-sonnet-4-6` — модель Claude для `tools/run-claude-codex-flow.sh`.
- `CLAUDE_PERMISSION_MODE=acceptEdits` — permission mode для headless Claude Code.
- `CLAUDE_ALLOWED_TOOLS=Read,Edit,MultiEdit,Write,Glob,Grep,LS,Bash` — allowlist tool surface для Claude.
- `CLAUDE_LAUNCH_MODE=auto|direct|host` — как запускать Claude в flow. `auto` по умолчанию запускает Claude напрямую, если текущий процесс может писать в `~/.claude`, и переключается на host launcher через Terminal, если flow идёт из sandbox и прямой запуск Claude там ломается.
- `CODEX_MODEL` — опциональная модель для review.

Для Claude-wrapper в sandbox-контуре важен отдельный нюанс: если процесс не может писать в `~/.claude`, общий flow теперь автоматически запускает Claude через `tools/run-claude-host.sh`, то есть в отдельном macOS Terminal-процессе вне sandbox. Это нужно, чтобы не падать на `~/.claude/session-env` и обычную Claude auth/session persistence.

Артефакты пишутся в `.runs/qwen-codex-flow/<run-id>/` для Qwen-wrapper и в `.runs/claude-codex-flow/<run-id>/` для Claude-wrapper:

- `task/` — исходная задача;
- `prompts/` — prompt'ы, которыми гонялся flow;
- `logs/` — stdout/stderr implementer'а и `codex`;
- `reviews/` — JSON-результаты review;
- `snapshots/` — `git status`, `diff --stat` и patch после каждого этапа.

## Batch queue для простых задач

Если нужно гонять не одну, а пачку простых задач, используйте queue runner:

```bash
bash /Users/alexorlik/repo/codepilot1c-oss/tools/run-qwen-codex-queue.sh
bash /Users/alexorlik/repo/codepilot1c-oss/tools/run-claude-codex-queue.sh
```

По умолчанию queue root:

```text
/Users/alexorlik/repo/codepilot1c-oss/.runs/qwen-codex-queue/queue/
```

Для Claude-wrapper queue root по умолчанию:

```text
/Users/alexorlik/repo/codepilot1c-oss/.runs/claude-codex-queue/queue/
```

Структура очереди:

- `todo/` — входящие markdown-задачи;
- `in_progress/` — задачи, уже взятые в обработку;
- `approved/` — задачи, у которых итоговый diff одобрил Codex;
- `no_changes/` — implementer не сделал изменений;
- `needs_human/` — после лимита review/fix циклов нужны ручные действия;
- `failed/` — сбой orchestration/runtime.

Runner обрабатывает `todo/*.md` в лексикографическом порядке и на каждую задачу пишет:

- flow-артефакты в `.runs/qwen-codex-flow/<task-run-id>/` или `.runs/claude-codex-flow/<task-run-id>/`;
- queue summary в `.runs/qwen-codex-queue/runs/<run-id>/SUMMARY.md` или `.runs/claude-codex-queue/runs/<run-id>/SUMMARY.md`;
- per-task result JSON рядом с задачей после перемещения по финальному статусу.
- если задача закончилась в `needs_human/`, может автоматически сгенерировать новые `review-followup` задачи обратно в `todo/` по findings из последнего Codex review.
  Эти follow-up задачи подхватываются уже следующим запуском очереди, а не в том же проходе.

Полезные env:

- `QUEUE_DIR` — альтернативный queue root;
- `MAX_TASKS=10` — ограничить число задач за один прогон;
- `FLOW_MAX_ROUNDS=3` — лимит review/fix раундов на одну задачу;
- `BASE_BRANCH` — базовая ветка для worktree каждой задачи.
- `AUTO_GENERATE_REVIEW_FOLLOWUPS=true|false` — порождать follow-up задачи из `needs_human` findings;
- `FOLLOWUP_MAX_FINDINGS=0` — лимит числа follow-up задач на один `needs_human` task; `0` означает все findings.

## Versioned task templates

Versioned каталог шаблонов лежит в:

```text
/Users/alexorlik/repo/codepilot1c-oss/tasks/qwen-codex-queue/
```

Шаблоны:

- `bugfix-minimal`
- `test-gap`
- `narrow-cleanup`
- `review-followup`

Быстро создать task в queue `todo/` можно так:

```bash
bash /Users/alexorlik/repo/codepilot1c-oss/tools/new-qwen-codex-task.sh --list
bash /Users/alexorlik/repo/codepilot1c-oss/tools/new-qwen-codex-task.sh bugfix-minimal "fix metadata sync null guard"
```

Скрипт положит новый markdown-файл в `.runs/qwen-codex-queue/queue/todo/` с очередным числовым префиксом.

## Repo-local skills для этого flow

В репозиторий добавлены skills в `.agents/skills`, которые Codex может подхватывать прямо из repo:

- `.agents/skills/qwen-codex-simple-task` — один простой task через implementer -> `codex review` flow; для Claude используйте wrapper `tools/run-claude-codex-flow.sh`;
- `.agents/skills/qwen-codex-queue` — batch/queue обработка пачки простых задач; для Claude используйте wrapper `tools/run-claude-codex-queue.sh`;
- `.agents/skills/qwen-codex-review-gate` — строгий Codex-only review gate для уже готового diff.
- `.agents/skills/qwen-codex-plan-bundle` — запуск queue flow прямо из planning bundle с синхронизацией `BACKLOG.md` и phase statuses; для Claude используйте wrapper `tools/run-claude-codex-plan.sh`.

Это repo-scoped skills по официальной схеме Codex: агент сканирует `.agents/skills` от текущей директории вверх до корня репозитория. Для пользователей repo ничего дополнительно устанавливать не нужно, если запуск идёт из этого checkout.

## Codex app automation

Versioned prompt для automation лежит в:

```text
/Users/alexorlik/repo/codepilot1c-oss/tasks/qwen-codex-queue/automation/codex-app-queue-run.prompt.md
```

Он предназначен для периодического запуска очереди через Codex app внутри этого проекта.

## Plan-driven flow for local planning bundles

Если source of truth лежит в planning bundle, например:

```text
/Users/alexorlik/repo/codepilot1c-oss/.planning/local/qwen-runtime-surface
```

используйте:

```bash
bash /Users/alexorlik/repo/codepilot1c-oss/tools/run-qwen-codex-plan.sh \
  /Users/alexorlik/repo/codepilot1c-oss/.planning/local/qwen-runtime-surface
bash /Users/alexorlik/repo/codepilot1c-oss/tools/run-claude-codex-plan.sh \
  /Users/alexorlik/repo/codepilot1c-oss/.planning/local/qwen-runtime-surface
```

Этот runner:

- читает `BACKLOG.md`;
- берёт задачи в порядке `EXECUTION-SLICE.md`, затем оставшиеся `todo`;
- создаёт plan-scoped queue tasks;
- гоняет их через implementer -> `codex review` -> implementer fix;
- пишет результат обратно в `BACKLOG.md`;
- синхронизирует `status:` в `phases/*/PLAN.md`;
- сохраняет `backlog-id` metadata в follow-up задачах, чтобы повторные проходы тоже закрывали исходный backlog item.

Готовый prompt для Codex app automation под background plan-run лежит в:

```text
/Users/alexorlik/repo/codepilot1c-oss/tasks/qwen-codex-queue/automation/codex-app-plan-run.prompt.md
```

Полезные env:

- `ORDERING=slice|backlog`
- `MAX_TASKS=5`
- `APPROVED_PLAN_STATUS=done`
- `NO_CHANGES_PLAN_STATUS=blocked`
- `NEEDS_HUMAN_PLAN_STATUS=blocked`
- `FAILED_PLAN_STATUS=blocked`

Артефакты этого режима пишутся в:

```text
/Users/alexorlik/repo/codepilot1c-oss/.runs/qwen-codex-plan/<plan-key>/
```

Для Claude-wrapper:

```text
/Users/alexorlik/repo/codepilot1c-oss/.runs/claude-codex-plan/<plan-key>/
```

## Публикация p2 из локальной сборки

Автоматическая публикация p2 из GitHub Actions отключена. Публикация выполняется локально:



## Структура

- `bundles/` — OSGi плагины
- `features/` — Eclipse features
- `repositories/` — p2 update site
- `targets/` — target platform

## Inbound MCP Host (Claude Code / Cursor / Codex)

Начиная с этой версии плагин поддерживает входящий MCP Host:

- Настройки: `Preferences -> 1C Copilot -> MCP Host`
- HTTP endpoint: по умолчанию `http://127.0.0.1:8765/mcp`
- Авторизация: `OAuth 2.1` (MCP Auth / RFC 9728)
- Резервный режим: статический `Bearer` token (опционально, для клиентов без OAuth)

Базовый сценарий: подключайте клиентов напрямую по HTTP.

### Автозапуск MCP Host через `1cedt.ini`

Откройте `1cedt.ini` и добавьте параметры ниже.

```ini
-Dcodepilot.mcp.enabled=true
-Dcodepilot.mcp.host.http.enabled=true
-Dcodepilot.mcp.host.http.bindAddress=127.0.0.1
-Dcodepilot.mcp.host.http.port=8765
-Dcodepilot.mcp.host.policy.defaultMutationDecision=ALLOW
-Dcodepilot.mcp.host.policy.exposedTools=*
# опционально:
# -Dcodepilot.mcp.host.http.bearerToken=ваш_токен
```

Claude Code (глобально, профиль пользователя):
```bash
claude mcp add --transport http -s user codepilot1c http://127.0.0.1:8765/mcp
```

Пример для `Cursor` / `Codex`:
```json
{
  "mcpServers": {
    "codepilot1c": {
      "url": "http://127.0.0.1:8765/mcp"
    }
  }
}
```

Пример для `Claude Code`:
```json
{
  "mcpServers": {
    "codepilot1c": {
      "type": "http",
      "url": "http://127.0.0.1:8765/mcp"
    }
  }
}
```

Если клиент не поддерживает OAuth и нужен статический токен:
```json
{
  "mcpServers": {
    "codepilot1c": {
      "url": "http://127.0.0.1:8765/mcp",
      "headers": {
        "Authorization": "Bearer <TOKEN>"
      }
    }
  }
}
```


## Публикация на Infostart

[![Infostart](https://infostart.ru/bitrix/templates/sandbox_empty/assets/tpl/abo/img/logo.svg)](https://infostart.ru/1c/articles/2613515/)

- Статья: [Выбор модели для разработки в 1С: сравниваем топов и open source](https://infostart.ru/1c/articles/2613515/)
- Статья: [CodePilot1C для EDT: встроенный MCP Host для Claude Code, Cursor и Codex](https://infostart.ru/public/2618356/)
- Статья: [Qwen Code CLI для 1С-разработчика: BSL Language Server + CodePilot1C MCP — бесплатно и без VPN](https://infostart.ru/1c/articles/2624226/)
