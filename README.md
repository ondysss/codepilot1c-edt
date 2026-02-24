# CodePilot1C (EDT plugin)

Плагин для 1C:EDT на базе Eclipse RCP/OSGi (open source).

## Актуальные артефакты

- Последний релиз: `v0.1.7.20260221-1351` — <https://github.com/ondysss/codepilot1c-edt/releases/tag/v0.1.7.20260221-1351>
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
mvn -B -V --no-transfer-progress clean verify
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
