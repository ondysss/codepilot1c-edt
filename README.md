# CodePilot1C (EDT plugin)

Плагин для 1C:EDT на базе Eclipse RCP/OSGi (open source).

## Установка

### Вариант A: ZIP (offline)

1. Откройте GitHub Releases и скачайте update-site ZIP (обычно `com.codepilot1c.update-*.zip`).
2. В 1C:EDT откройте `Help -> Install New Software...`
3. Нажмите `Add...`
4. Нажмите `Archive...` и выберите скачанный ZIP.
5. Выберите `1C Copilot` (или весь сайт), нажмите `Next` и пройдите мастер установки.
6. Перезапустите EDT.

### Вариант B: Update Site (GitHub Pages)

1. В 1C:EDT откройте `Help -> Install New Software...`
2. В поле `Work with:` укажите URL update-site (GitHub Pages для репозитория).
3. Выберите `1C Copilot`, нажмите `Next` и пройдите мастер установки.
4. Перезапустите EDT.

## Сборка

Требования: JDK 17.

```bash
mvn -B -V --no-transfer-progress clean verify
```

## Структура

- `bundles/` — OSGi плагины
- `features/` — Eclipse features
- `repositories/` — p2 update site
- `targets/` — target platform
