# CodePilot1C MCP Host — Docker

Запуск MCP Host сервера в Docker без GUI. 1C:EDT работает в CLI режиме
(`eclipse.ignoreApp=true`), `com.codepilot1c.core` бандл активируется
автоматически и поднимает HTTP сервер на порту 8765.

---

## Что нужно для сборки

| Файл | Откуда взять |
|---|---|
| `edt.tar` | Офлайн-дистрибутив 1C:EDT для Linux x86_64 с [releases.1c.ru](https://releases.1c.ru) |
| `plugin.zip` | Артефакт сборки: `mvn package` → `releng/com.codepilot1c.update/target/com.codepilot1c.update-*.zip` |
| `Dockerfile` | `docker/Dockerfile` в этом репозитории |
| `docker-entrypoint.sh` | `docker/docker-entrypoint.sh` в этом репозитории |

Дистрибутив EDT: файл вида `1c_edt_distr_offline_<version>_linux_x86_64.tar`.
Проверенная версия: `2025.2.3+30`.

---

## Сборка плагина

```bash
# В корне репозитория
mvn package -Dmaven.test.skip=true

# Артефакт будет здесь:
ls releng/com.codepilot1c.update/target/com.codepilot1c.update-*.zip
```

---

## Сборка Docker образа

### Структура build-контекста

```
docker/
├── Dockerfile
├── docker-entrypoint.sh
├── edt.tar          # ~4 GB — дистрибутив 1C:EDT для Linux
└── plugin.zip       # ~18 MB — артефакт плагина
```

```bash
# Копируем дистрибутив и плагин
cp /path/to/1c_edt_distr_offline_2025.2.3_30_linux_x86_64.tar docker/edt.tar
cp releng/com.codepilot1c.update/target/com.codepilot1c.update-*.zip docker/plugin.zip

# Собираем образ (~10–15 минут первый раз, ~1 минута при повторной сборке с кешем)
docker build -t codepilot1c-edt:latest docker/
```

### Что происходит при сборке

1. **Установка системных зависимостей** — GTK, Xvfb (нужен только для шага 3)
2. **Установка 1C:EDT** — через `1ce-installer-cli` в `/opt/1c/`, симлинк `/opt/1cedt`
3. **Установка плагина** — через `p2 director` с виртуальным дисплеем Xvfb; после установки Xvfb убивается и lock-файл удаляется
4. **Патч `bundles.info`** — `com.codepilot1c.core` переводится в `autoStart=true`, чтобы `VibeCorePlugin.start()` вызвался без workbench
5. **Конфигурация MCP Host** — JVM-свойства дописываются в `1cedt.ini` (в том числе `eclipse.ignoreApp=true` и `osgi.noShutdown=true`)

---

## Запуск контейнера

### Минимальный запуск

```bash
docker run -d \
  --name codepilot1c-mcp \
  -p 8765:8765 \
  -e MCP_BEARER_TOKEN=your-secret-token \
  codepilot1c-edt:latest
```

### С одним EDT-проектом

```bash
docker run -d \
  --name codepilot1c-mcp \
  -p 8765:8765 \
  -e MCP_BEARER_TOKEN=your-secret-token \
  -v /path/to/MyProject:/workspace/MyProject:ro \
  codepilot1c-edt:latest
```

EDT при старте автоматически подхватит `/workspace/MyProject` как проект
(стандартное поведение Eclipse — сканирует workspace root на наличие `.project`).

### С несколькими проектами через `PROJECT_PATH`

```bash
docker run -d \
  --name codepilot1c-mcp \
  -p 8765:8765 \
  -e MCP_BEARER_TOKEN=your-secret-token \
  -e PROJECT_PATH=/projects/ProjectA:/projects/ProjectB \
  -v /host/ProjectA:/projects/ProjectA:ro \
  -v /host/ProjectB:/projects/ProjectB:ro \
  codepilot1c-edt:latest
```

Entrypoint создаст симлинки: `/workspace/ProjectA → /projects/ProjectA` и т.д.

### С явным именем проекта в workspace

```bash
docker run -d \
  --name codepilot1c-mcp \
  -p 8765:8765 \
  -e MCP_BEARER_TOKEN=your-secret-token \
  -v /host/my-1c-project:/workspace/MyProject:ro \
  codepilot1c-edt:latest
```

Имя директории в `/workspace/` = имя проекта в MCP-инструментах (`project_name`).

---

## Переменные окружения

| Переменная | Обязательная | Описание |
|---|---|---|
| `MCP_BEARER_TOKEN` | Да | Bearer-токен для аутентификации MCP клиентов |
| `PROJECT_PATH` | Нет | Пути к проектам через `:`, создаются симлинки в `/workspace/` |
| `MCP_PORT` | Нет | Порт MCP сервера (по умолчанию `8765`, только для логов в entrypoint; сам порт задаётся в `1cedt.ini`) |

### Как сгенерировать MCP_BEARER_TOKEN

`MCP_BEARER_TOKEN` — произвольная секретная строка, которую вы придумываете сами.
Она передаётся клиентом в заголовке `Authorization: Bearer <token>` при каждом запросе.

```bash
# Вариант 1: openssl (рекомендуется)
openssl rand -hex 32

# Вариант 2: uuidgen
uuidgen

# Вариант 3: python
python3 -c "import secrets; print(secrets.token_hex(32))"
```

Пример вывода: `a3f8c2d1e5b94076f2a1c3d8e9b05712f4a6c8d2e0b193745f6a8c2d1e4b7f9`

Сохраните токен и используйте его при запуске контейнера и в конфигурации MCP-клиента.

---

## Проверка работоспособности

```bash
TOKEN="your-secret-token"

# 1. Initialize сессию
SESSION=$(curl -si -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  http://localhost:8765/mcp \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}' \
  | grep -i "mcp-session-id" | awk '{print $2}' | tr -d '\r')

echo "Session: $SESSION"

# 2. Запросить список инструментов
curl -s -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Mcp-Session-Id: $SESSION" \
  http://localhost:8765/mcp \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
  | python3 -c "import sys,json; [print(t['name']) for t in json.load(sys.stdin)['result']['tools']]"
```

Ожидаемый результат — список ~50 инструментов: `scan_metadata_index`, `get_diagnostics`, `read_file`, etc.

---

## Подключение в Claude Code / MCP-клиент

```json
{
  "mcpServers": {
    "codepilot1c": {
      "url": "http://localhost:8765/mcp",
      "headers": {
        "Authorization": "Bearer your-secret-token"
      }
    }
  }
}
```

---

## Как это работает (архитектура)

```
docker run
  └─ docker-entrypoint.sh
       ├─ записывает bearerToken в 1cedt.ini
       ├─ создаёт симлинки проектов в /workspace/ (если PROJECT_PATH задан)
       └─ exec /opt/1cedt/1cedt -nosplash -data /workspace
            └─ OSGi framework
                 ├─ com.codepilot1c.core (autoStart=true в bundles.info)
                 │    └─ VibeCorePlugin.start()
                 │         └─ McpHostManager.startIfEnabled()
                 │              └─ HTTP сервер на :8765 (Jetty)
                 └─ (workbench НЕ запускается — eclipse.ignoreApp=true)
```

**Почему не нужен Xvfb в runtime:**
`-Declipse.ignoreApp=true` запрещает запуск приложения `org.eclipse.ui.ide.workbench`.
OSGi-бандлы стартуют, но SWT/GTK не инициализируются. MCP Host — чистый HTTP-сервер на Jetty, GUI не требует.

**Почему Xvfb нужен при сборке:**
`p2 director` (установщик плагинов Eclipse) использует GTK при инициализации, даже в headless-режиме. Без виртуального дисплея завершается с ошибкой.

**Почему патчится `bundles.info`:**
После установки через `p2 director` плагин прописывается с `autoStart=false` (lazy OSGi).
В CLI режиме без workbench ничто не загружает класс из `com.codepilot1c.core`, поэтому
`VibeCorePlugin.start()` никогда не вызывается. Смена на `autoStart=true` заставляет
OSGi-фреймворк запустить бандл на старте.

---

## Требования к EDT-проекту

Проект должен быть в формате 1C:EDT (не конфигуратора). Структура:

```
MyProject/
├── .project              ← обязательно: Eclipse project descriptor
├── src/
│   └── Configuration/
│       ├── Configuration.mdo
│       └── ...
└── .edt.yml / .settings/ (опционально)
```

Конвертация из конфигуратора в EDT-формат — через EDT: `File → Import → 1C:Enterprise → Import from 1C:Enterprise Infobase`.

---

## Логи и отладка

```bash
# Логи контейнера
docker logs codepilot1c-mcp

# Логи 1C Copilot (пишутся в workspace)
docker exec codepilot1c-mcp find /workspace -name "codepilot*.log" | xargs tail -50

# Статус процессов
docker exec codepilot1c-mcp ps aux | grep -E "1cedt|java"

# Проверить что порт открыт внутри контейнера
docker exec codepilot1c-mcp ss -tlnp | grep 8765
```
