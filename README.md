# CodePilot1C (EDT plugin)

Плагин для 1C:EDT на базе Eclipse RCP/OSGi (open source).

## Актуальные артефакты

- Последний релиз: `v0.1.7` — <https://github.com/ondysss/codepilot1c-edt/releases/tag/v0.1.7>
- Update site (GitHub Pages): <https://ondysss.github.io/codepilot1c-edt/>
- GitHub Packages (Maven, ZIP): <https://github.com/ondysss/codepilot1c-edt/packages/2846572>

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



## Публикация ZIP в GitHub Packages

После сборки update-site ZIP публикуется в GitHub Packages (Maven registry):

```bash
mvn -B -V --no-transfer-progress deploy:deploy-file \
  -Durl="https://maven.pkg.github.com/ondysss/codepilot1c-edt" \
  -DrepositoryId=github \
  -DgroupId="com.codepilot1c" \
  -DartifactId="codepilot1c-edt-update-site" \
  -Dversion="0.1.7" \
  -Dpackaging=zip \
  -Dfile="repositories/com.codepilot1c.update/target/com.codepilot1c.update-1.3.0-SNAPSHOT.zip" \
  -DgeneratePom=true
```

Требуется токен GitHub с правом `write:packages`.

## Структура

- `bundles/` — OSGi плагины
- `features/` — Eclipse features
- `repositories/` — p2 update site
- `targets/` — target platform


## Публикация на Infostart

[![Infostart](https://infostart.ru/bitrix/templates/sandbox_empty/assets/tpl/abo/img/logo.svg)](https://infostart.ru/1c/articles/2613515/)

Статья: [Выбор модели для разработки в 1С: сравниваем топов и open source](https://infostart.ru/1c/articles/2613515/)
