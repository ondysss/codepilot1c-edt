# CodePilot1C (EDT plugin)

Плагин для 1C:EDT на базе Eclipse RCP/OSGi (open source).

## Актуальные артефакты

- Текущая release-линия: `1.3.9`.
- Update site (GitHub Pages): <https://ondysss.github.io/codepilot1c-edt/> — публикует последнюю
  сборку release-линии `1.3.9`.
- Последний тегированный релиз: [GitHub Releases](https://github.com/ondysss/codepilot1c-edt/releases/latest).
- GitHub Packages (container image): <https://github.com/users/ondysss/packages/container/package/codepilot1c-edt>
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

## Обновление установленной версии

> **До начала обновления сохраните локальную копию прежнего update-site ZIP.** Публичный update site
> хранит только последнюю версию, поэтому без локальной копии или
> [ZIP-ассета релиза `v1.0.0.20260803-1552`](https://github.com/ondysss/codepilot1c-edt/releases/tag/v1.0.0.20260803-1552)
> надёжный откат на 1.0.x невозможен.

Начиная с версии конфигурации LLM-провайдеров 2, их API-ключи переносятся из настроек workspace
в Eclipse Secure Storage. Plaintext удаляется из preferences только после успешной записи всех
нужных ключей в Secure Storage и сохранения preferences. Если secure-хранилище недоступно,
плагин сохраняет прежний plaintext для повторной попытки и пишет предупреждение без значения
ключа. Secure Storage привязан к установке Eclipse/EDT и учётной записи ОС: перенос workspace
сам по себе не переносит ключи, а в некоторых headless/OS-конфигурациях хранилище может быть
недоступно. При откате на плагин без поддержки config v2 ключ потребуется ввести повторно:
старая версия не читает Secure Storage и автоматического обратного переноса в plaintext нет.
Secure-копия при этом не удаляется и снова доступна после возврата на v2-aware плагин.

Прямая доустановка через `Install New Software` может не разрешиться: `com.codepilot1c.core` и
`com.codepilot1c.ui` являются singleton-бандлами, а update site уже не содержит артефакты
установленной версии 1.0.x. Мастеру не хватает старых IU для согласованного обновления, тогда как
профилю нужна замена старого root IU на новый. Точный текст отказа смотрите в выводе p2 и логах
конкретной установки.

### Путь A: через интерфейс EDT

1. Закройте рабочие проекты и откройте `Help → About 1C:EDT → Installation Details`.
2. На вкладке `Installed Software` выберите `1C Copilot`, нажмите `Uninstall…`, затем `Finish` и перезапустите EDT.
3. Откройте `Help → Install New Software…`, добавьте сайт `https://ondysss.github.io/codepilot1c-edt/` и выберите `1C Copilot`.
4. Нажмите `Next`, затем `Finish`, подтвердите доверие кнопкой `Trust Selected` и снова перезапустите EDT.

### Путь B: замена через p2 director (рекомендуется)

Процедура версионно-нейтральна: конкретные версии определяются на месте, ничего не нужно
подставлять из этого файла. Пути ниже — **пример для проверенной установки на macOS**.

#### B0. Закрыть IDE и определить окружение

Полностью закройте 1C:EDT **и** `1cedtstart`: на установках, развёрнутых через `1cedtstart`, оба
процесса пишут в один разделяемый p2-реестр, и запущенный `1cedtstart` может перезаписать результат
director'а. Завершайте штатно; `kill -9` может оставить реестр в несогласованном состоянии.

```bash
pgrep -fl '1cedtstart|1cedt' || echo 'процессы EDT не найдены — можно продолжать'
```

Найдите установку и совместимую JVM (архитектура JVM должна совпадать с EDT):

```bash
find "$HOME/Library/Application Support/1C/1cedtstart/installations" \
  -maxdepth 2 -type d -name '1cedt.app' -print
find /Applications/1C/1CE/components \
  -type f -path '*/lib/server/libjvm.dylib' -print
```

Подставьте выбранные пути вместо примеров. Для проверенной установки 1C:EDT 2026.2.0 прямому
native launcher нужен явный `-vm`:

```bash
EDT_APP="$HOME/Library/Application Support/1C/1cedtstart/installations/1C_EDT (Lite) 2026.2.0/1cedt.app"
EDT_HOME="$EDT_APP/Contents/Eclipse"
EDT_EXE="$EDT_APP/Contents/MacOS/1cedt"
EDT_VM="/Applications/1C/1CE/components/axiom-jdk-full-25.0.2+12-x86_64/lib/server/libjvm.dylib"
BUNDLES_INFO="$EDT_HOME/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
IU="com.codepilot1c.feature.feature.group"
SITE="https://ondysss.github.io/codepilot1c-edt/"
# Для локальной сборки вместо SITE используйте абсолютный путь к собранному сайту:
# SITE="file:$(pwd)/repositories/com.codepilot1c.update/target/repository"
BACKUP_DIR="${BACKUP_DIR:-$HOME/codepilot1c-p2-backup-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$BACKUP_DIR"
```

#### B1. Определить, какой p2-агент использует установка

Установки из `1cedtstart` (Oomph, разделяемый bundle pool) держат профиль **не** в каталоге
установки, а в общем агенте из `config.ini`. Проверьте это до любых изменений:

```bash
CONFIG_INI="$EDT_HOME/configuration/config.ini"
grep -E '^eclipse\.p2\.(data\.area|profile)=' "$CONFIG_INI" \
  || echo 'ключей нет — установка самодостаточная, переходите к разделу B3'

P2_DATA_AREA="$(sed -n 's/^eclipse\.p2\.data\.area=//p' "$CONFIG_INI" | head -n1 | tr -d '\\')"
P2_DATA_AREA="${P2_DATA_AREA#file:}"
P2_DATA_AREA="${P2_DATA_AREA%/}"
P2_PROFILE="$(sed -n 's/^eclipse\.p2\.profile=//p' "$CONFIG_INI" | head -n1)"
printf 'data.area=[%s]\nprofile=[%s]\n' "$P2_DATA_AREA" "$P2_PROFILE"
```

- Обе переменные непусты и `data.area` указывает **вне** `$EDT_HOME` → раздел **B2**.
- Ключей нет (или `data.area` внутри `$EDT_HOME`) → раздел **B3**.
- Значение начинается с `@config.dir`, `@user.home` и т. п. → подстановка таких токенов здесь не
  выполняется; раскройте путь вручную и только потом продолжайте.

Идентификатор профиля содержит пробелы — всюду ниже он должен оставаться в кавычках.

#### B2. Разделяемый агент: обновление одной транзакцией

Пути к общей и возможной локальной копиям профиля нужны для бэкапа, диагностики и отката:

```bash
LOCAL_PROFILE="$EDT_HOME/p2/org.eclipse.equinox.p2.engine/profileRegistry/$P2_PROFILE.profile"
SHARED_PROFILE="$P2_DATA_AREA/org.eclipse.equinox.p2.engine/profileRegistry/$P2_PROFILE.profile"
```

**Шаг 1. Снять фактические версии (ничего не меняет).**

```bash
grep -E '^com\.codepilot1c\.(core|ui),' "$BUNDLES_INFO"

"$EDT_EXE" -vm "$EDT_VM" \
  -application org.eclipse.equinox.p2.director -noSplash -consoleLog \
  -shared "$P2_DATA_AREA" -destination "$EDT_HOME" -profile "$P2_PROFILE" \
  -listInstalledRoots | grep "$IU"

"$EDT_EXE" -vm "$EDT_VM" \
  -application org.eclipse.equinox.p2.director -noSplash -consoleLog \
  -repository "$SITE" -list | grep "$IU"
```

Первая команда обязана вывести **непустой** список root-IU с `$IU`. Если список пуст — остановитесь:
агент определён неверно. Версию установленного root-IU берите из этого вывода (перекрёстная проверка —
`bundles.info`), целевую — из вывода `-list`:

```bash
OLD_VERSION="<версия из -listInstalledRoots>"
NEW_VERSION="<версия из -list>"
OLD_IU="$IU/$OLD_VERSION"
NEW_IU="$IU/$NEW_VERSION"
```

**Шаг 2. Резервные копии (вне каталога установки).**

```bash
tar -czf "$BACKUP_DIR/configuration.tar.gz" -C "$EDT_HOME" configuration
[ -d "$EDT_HOME/p2" ] && tar -czf "$BACKUP_DIR/local-p2.tar.gz" -C "$EDT_HOME" p2
tar -czf "$BACKUP_DIR/shared-profile.tar.gz" \
  -C "$P2_DATA_AREA/org.eclipse.equinox.p2.engine/profileRegistry" "$P2_PROFILE.profile"
ls -l "$BACKUP_DIR"
```

**Шаг 3. Сухой прогон (`-verifyOnly` ничего не записывает).**

```bash
"$EDT_EXE" -vm "$EDT_VM" \
  -application org.eclipse.equinox.p2.director -noSplash -consoleLog \
  -shared "$P2_DATA_AREA" -destination "$EDT_HOME" -profile "$P2_PROFILE" \
  -repository "$SITE" \
  -uninstallIU "$OLD_IU" -installIU "$NEW_IU" -verifyOnly \
  2>&1 | tee "$BACKUP_DIR/p2-verify.log"
```

Ожидаемый успех — три строки: `Общий запрос на установку выполнен`, `Remove запрос для … $OLD_VERSION
выполнен`, `Add запрос для … $NEW_VERSION выполнен`. Если в плане появляются посторонние feature
(например `org.eclipse.cdt.*`, `org.eclipse.tm.terminal.*`) — не применяйте, используйте Путь A.

**Шаг 3a. Если сухой прогон сообщает `Копии профиля … не синхронизированы`.**

Рядом с рабочим профилем лежит устаревшая локальная копия — обычно её создаёт неудачный запуск
director'а с одним `-destination`. Сравните копии по числу установленных единиц:

```bash
for d in "$LOCAL_PROFILE" "$SHARED_PROFILE"; do
  f="$(ls -t "$d"/*.profile.gz 2>/dev/null | head -n1)"
  [ -n "$f" ] && printf '%s: %s IU (%s)\n' "$d" "$(gunzip -c "$f" | grep -c '<unit ')" "${f##*/}"
done
```

Если в локальной копии 0 (или на порядки меньше) единиц — **перенесите её в карантин, не удаляйте**,
и повторите шаг 3:

```bash
mkdir -p "$BACKUP_DIR/stale-local-p2-profile"
mv "$LOCAL_PROFILE" "$BACKUP_DIR/stale-local-p2-profile/"
```

Возврат карантина в любой момент:

```bash
mkdir -p "$(dirname "$LOCAL_PROFILE")"
mv "$BACKUP_DIR/stale-local-p2-profile/$(basename "$LOCAL_PROFILE")" "$(dirname "$LOCAL_PROFILE")/"
```

Если число единиц сопоставимо — карантин **не** применяйте: копии разошлись по другой причине,
безопаснее Путь A через интерфейс EDT.

**Шаг 4. Применение — одна p2-транзакция.**

Между шагами 3 и 4 не пересобирайте локальный сайт: qualifier генерируется заново при каждой сборке,
и снятая версия перестанет существовать.

```bash
"$EDT_EXE" -vm "$EDT_VM" \
  -application org.eclipse.equinox.p2.director -noSplash -consoleLog \
  -shared "$P2_DATA_AREA" -destination "$EDT_HOME" -profile "$P2_PROFILE" \
  -repository "$SITE" \
  -uninstallIU "$OLD_IU" -installIU "$NEW_IU" -tag "codepilot-$NEW_VERSION" \
  2>&1 | tee "$BACKUP_DIR/p2-install.log"
```

`-uninstallIU` и `-installIU` в одном вызове дают один provisioning-план: промежуточного состояния
«плагина нет» не возникает, а неразрешимый план отклоняется до записи. `-tag` создаёт именованную
точку в истории профиля; если ваша сборка director'а её не поддерживает, снимок всё равно создаётся
автоматически.

#### B3. Самодостаточная установка (профиль в каталоге установки)

Если `config.ini` не задаёт `eclipse.p2.data.area`, профиль живёт в `$EDT_HOME/p2` и агент задаётся
через `-destination`. Сначала независимо снимите обе версии и определите точные IU:

```bash
grep -E '^com\.codepilot1c\.(core|ui),' "$BUNDLES_INFO"

"$EDT_EXE" -vm "$EDT_VM" \
  -application org.eclipse.equinox.p2.director -noSplash -consoleLog \
  -destination "$EDT_HOME" -listInstalledRoots | grep "$IU"

"$EDT_EXE" -vm "$EDT_VM" \
  -application org.eclipse.equinox.p2.director -noSplash -consoleLog \
  -repository "$SITE" -list | grep "$IU"
```

Список root-IU обязан быть непустым. Установленную версию берите из `-listInstalledRoots`, целевую —
из `-repository "$SITE" -list`, затем задайте все четыре переменные до бэкапа и сухого прогона:

```bash
OLD_VERSION="<версия из -listInstalledRoots>"
NEW_VERSION="<версия из -list>"
OLD_IU="$IU/$OLD_VERSION"
NEW_IU="$IU/$NEW_VERSION"

tar -czf "$BACKUP_DIR/configuration.tar.gz" -C "$EDT_HOME" configuration
tar -czf "$BACKUP_DIR/local-p2.tar.gz" -C "$EDT_HOME" p2

"$EDT_EXE" -vm "$EDT_VM" \
  -application org.eclipse.equinox.p2.director -noSplash -consoleLog \
  -repository "$SITE" -destination "$EDT_HOME" \
  -uninstallIU "$OLD_IU" -installIU "$NEW_IU" -verifyOnly

"$EDT_EXE" -vm "$EDT_VM" \
  -application org.eclipse.equinox.p2.director -noSplash -consoleLog \
  -repository "$SITE" -destination "$EDT_HOME" \
  -uninstallIU "$OLD_IU" -installIU "$NEW_IU" \
  2>&1 | tee "$BACKUP_DIR/p2-install.log"
```

На headless Linux запускайте director под Xvfb, как в `tools/run-edt-e2e-local.sh`; на macOS Xvfb
не нужен. Сам `tools/run-edt-e2e-local.sh` рассчитан на выделенную самодостаточную test-инсталляцию
и агент из `config.ini` не определяет — для рабочей установки из `1cedtstart` он не подходит.

### Проверка обновления

Активный набор бандлов задаёт `bundles.info`. Общий bundle pool может хранить JAR предыдущих версий —
это кеш, удалять их вручную не нужно.

```bash
# 1) root IU в профиле (для B3 замените набор флагов на -destination "$EDT_HOME")
"$EDT_EXE" -vm "$EDT_VM" \
  -application org.eclipse.equinox.p2.director -noSplash \
  -shared "$P2_DATA_AREA" -destination "$EDT_HOME" -profile "$P2_PROFILE" \
  -listInstalledRoots | grep "$IU"

# 2) bundles.info переписан на новую версию
grep -E '^com\.codepilot1c\.(core|ui),' "$BUNDLES_INFO"

# 3) JAR действительно лежат по путям из bundles.info
awk -F, '/^com\.codepilot1c\./ {print $3}' "$BUNDLES_INFO" | while read -r rel; do
  ( cd "$EDT_HOME" && ls -l "$rel" )
done
```

Все три проверки должны показать `$NEW_VERSION` и ни одного `$OLD_VERSION`. При установке в
разделяемый агент новые JAR попадают в `<data.area>/plugins` (а не в `<data.area>/pool`) — это
нормально: что реально загружается, определяет `bundles.info`.

Запустите EDT и убедитесь, что загружены именно новые JAR:

```bash
pgrep -f '1cedt' | while read -r pid; do lsof -p "$pid" 2>/dev/null | grep codepilot1c; done
```

Затем в `Installation Details → Installed Software` проверьте версию `1C Copilot` — она должна
совпасть с `$NEW_VERSION`. Представление `1C Copilot` должно открываться, а раздел
`Preferences → 1C Copilot` — быть доступен.

Если обновление проверялось локальной сборкой и её нужно опубликовать, публикуйте **ровно этот**
артефакт — см. «Публикация update site».

### Диагностика и откат

При отказе сохраните `-consoleLog` (в примерах он уже пишется в `$BACKUP_DIR`) и проверьте
`$EDT_HOME/configuration/*.log`.

| Сообщение | Причина | Действие |
|---|---|---|
| `Устанавливаемый модуль …/<версия> не найден` | версии нет в профиле либо director смотрит не в тот агент | повторить B1 и `-listInstalledRoots`; версию брать только из вывода |
| `… требует 'osgi.bundle; org.eclipse.core.runtime …', но его не удалось найти` | director работает с пустым профилем | вернуться к B1; на shared-установке обязательны `-shared` и `-profile` |
| `Копии профиля … не синхронизированы` | осталась устаревшая локальная копия профиля | шаг 3a: карантин и повтор сухого прогона |

Откат, по возрастанию стоимости:

1. `-revert` на снимок профиля (имена снимков — метки времени в каталоге профиля; при использовании
   `-tag` можно указать созданный тег):

   ```bash
   # B2: разделяемый агент
   ls -t "$SHARED_PROFILE" | head
   "$EDT_EXE" -vm "$EDT_VM" \
     -application org.eclipse.equinox.p2.director -noSplash -consoleLog \
     -shared "$P2_DATA_AREA" -destination "$EDT_HOME" -profile "$P2_PROFILE" \
     -revert <снимок-или-тег>

   # B3: самодостаточная установка
   find "$EDT_HOME/p2/org.eclipse.equinox.p2.engine/profileRegistry" \
     -name '*.profile.gz' -print
   "$EDT_EXE" -vm "$EDT_VM" \
     -application org.eclipse.equinox.p2.director -noSplash -consoleLog \
     -destination "$EDT_HOME" -revert <снимок-или-тег>
   ```

   JAR прежней версии остаются в bundle pool, поэтому откат не требует сети.
2. `Installation Details → Installation History → Revert` — тот же механизм из интерфейса.
3. Возврат карантина (шаг 3a) и распаковка резервных копий из `$BACKUP_DIR` при закрытой EDT.
4. Локально сохранённый update-site ZIP: `Help → Install New Software… → Add… → Archive…`, после
   удаления текущей версии. Для 1.0.x это единственный надёжный путь: артефакты 1.0.x удалены с
   публичного сайта.

Чего делать не надо:

- редактировать вручную `bundles.info`, `config.ini`, `artifacts.xml`, `.profile.gz`;
- удалять устаревшую локальную копию профиля вместо карантина;
- очищать общий bundle pool — он используется всеми установками EDT на машине;
- разделять транзакцию на независимые вызовы `-uninstallIU` и `-installIU` или заменять версионные
  `OLD_IU`/`NEW_IU` значениями без версии;
- запускать director при работающих EDT или `1cedtstart`.

Сборка компилируется против target 1C:EDT `2025.2.3+30` (`targets/default/default.target`). Импорты
`com._1c.g5.*` не имеют версионных диапазонов: OSGi resolver формально принимает более новую EDT, но
это не гарантирует бинарную совместимость. Сборка проверена на `2025.2.3+30`; установка на `2026.2.0`
выполнялась вручную.

## Интерактивный CLI shell

Для shell нужен Java 17+ и интерактивный терминал. После распаковки CLI-дистрибутива запускайте:

```sh
# macOS / Linux
bin/codepilot shell
```

```powershell
# Windows PowerShell — канонический Windows launcher
pwsh -File .\bin\codepilot.ps1 shell
```

```bat
rem Windows cmd.exe — convenience wrapper с обычными ограничениями %*
bin\codepilot.cmd shell
```

Прямой запуск jar из дистрибутива — `java -jar lib/codepilot-cli.jar shell`, а из дерева сборки —
`java -jar cli/codepilot-cli/target/codepilot-cli-1.0.0-SNAPSHOT-all.jar shell`. В интерактивном
терминале `codepilot` без команды также открывает shell; с redirected stdin он печатает usage и
возвращает код `2`, поэтому для batch-сценариев используйте `agent run`.

`--mode connected` использует активный LLM-провайдер EDT через authenticated broker и не читает,
не экспортирует и не сохраняет его API-ключ. Broker должен быть включён instance preference
`mcp.host.llm.enabled=true` (эквивалентные startup overrides:
`-Dmcp.host.llm.enabled=true` или `-Dcodepilot.mcp.host.llm.enabled=true`), MCP host нужно
перезапустить, а в EDT должен быть выбран активный provider. `codepilot edt status --all`
показывает `llm.v1` только если capability опубликована в registry; старые записи без поля
остаются совместимыми. `codepilot doctor` отдельно проверяет доступность broker и active provider,
не выводя response body, bearer/API keys, custom headers или provider base URL.

`--mode standalone` запускает OpenAI-compatible provider внутри CLI и требует endpoint/model;
`--mode auto` сначала пробует connected, затем только полностью настроенный standalone. Для
standalone порядок endpoint и model: CLI flag → Java property → environment; для provider key:
`--provider-api-key-file` → `codepilot.provider.apiKey` → `CODEPILOT_PROVIDER_API_KEY`; для MCP
bearer: `--mcp-bearer-token-file` → `codepilot.mcp.bearerToken` →
`CODEPILOT_MCP_BEARER_TOKEN`. Secret-файл имеет приоритет и предпочтительнее property, видимой в
process list.

Команды shell: `/help`, `/exit`, `/new`, `/status`, `/tools`, `/model`, `/sessions`,
`/resume <session-id>`. Для рискованных, mutating или неаннотированных MCP tools shell спрашивает
`y` (один вызов), `n` (запрет) или `a` (разрешить это имя tool до `/new`/`/resume`). Первый Ctrl+C
во время turn отменяет turn, следующий завершает shell; в idle prompt Ctrl+C завершает shell.
Сессии лежат в `~/.codepilot1c/sessions/` как `<id>.meta.json` и `<id>.jsonl`; это локальные
redacted transcript-файлы, а не encrypted secret store. Полная грамматика options, правила
permissions/ACL и ограничения описаны в [`cli/README.md`](cli/README.md#interactive-shell), а
launcher/install layout — в [`packaging/README.md`](packaging/README.md#start-the-interactive-shell).

## Сборка

Требования: JDK 17 и локальная инсталляция 1C:EDT. Передайте каталог `Eclipse`
этой инсталляции как Maven system property — Tycho использует её для разрешения
`com._1c.g5.v8.*` бандлов:

```bash
mvn -Dedt.home=/path/to/1cedt/Eclipse -DskipTests package
```

Это одинаково работает на macOS, Linux и Windows; для путей с пробелами
заключите весь аргумент в кавычки, например:

```powershell
mvn "-Dedt.home=C:\Program Files\1C\EDT\Eclipse" -DskipTests package
```

`edt.home` не имеет machine-specific default и должен указывать именно на
каталог `Eclipse`, содержащий плагины EDT. Зафиксированная acceptance-версия —
EDT `2025.1.5+34`; локальную и Docker-проверку следует выполнять против одной
и той же инсталляции этой версии.

На Java 17 GSD-инспекция (`gsd_get_state` и status UI) остаётся доступной на macOS
и Linux, включая стандартный macOS provider без `SecureDirectoryStream`. Узкий
optional import использует JNA core 5.13.0 из pinned EDT: корень и каждый компонент
открываются через libc `open`/`openat` с `O_NOFOLLOW|O_DIRECTORY|O_CLOEXEC`, байты
читаются только из открытого fd с пределом 16 MiB, а `fstat` до и после чтения
проверяет `dev`/`ino`/тип/размер/`nlink`. Перед чтением у final regular file должен
быть ровно один hard link; после чтения fd допускает только `nlink` 0 или 1, но unlink
или atomic replacement current path всё равно завершается fail-closed при полной
повторной проверке identity. На macOS Java-атрибуты `/dev/fd` и `isSameFile` не
используются; Linux дополнительно сверяет file key и `isSameFile` через
`/proc/self/fd`. Pathname-only fallback и любые записи отсутствуют.

Native anchored read намеренно применяется и на Linux-провайдерах с
`SecureDirectoryStream`: Java API не раскрывает fd, необходимый для `fstat` и
проверки hard-link count. Поддерживаются macOS и Linux x86_64/AArch64; Windows,
другие ОС/архитектуры, отсутствие JNA/libc или stable file identity возвращают
`unsupported` без pathname fallback. Все GSD-мутации и GSD Ship publication на
провайдерах без реального `SecureDirectoryStream` также отключены fail-closed и
возвращают `error_code=unsupported`. Предварительное создание `.codepilot1c/gsd`
или каталога release artifact помогает только провайдеру с реальным
`SecureDirectoryStream`; на macOS Java 17 это не обход ограничения.

## Публикация update site

Публикует содержимое `repositories/com.codepilot1c.update/target/repository` в ветку `gh-pages`:

```bash
tools/publish-p2-local.sh
```

По умолчанию скрипт сначала собирает проект (`mvn clean verify`) и публикует свежий результат.

**Внимание:** `clean verify` удаляет существующий `target/`, включая уже записанные локальный
репозиторий и `repository.provenance`, до каких-либо проверок, после чего создаёт новый qualifier и
новый marker. Для публикации ровно тех bits, которые прошли live-приёмку, обязательны
`SKIP_BUILD=1`, точные `EXPECT_QUALIFIER`/`EXPECT_HEAD` и заранее записанный provenance.

**Важно про qualifier.** Tycho генерирует qualifier заново на каждой сборке, поэтому пересборка
всегда даёт версию, отличную от той, которую вы проверяли на живой EDT. Публиковать пересобранный,
непроверенный qualifier нельзя. Если приёмка проходила на конкретной локальной сборке — публикуйте
именно её, без пересборки:

```bash
# один раз зафиксировать происхождение уже собранного артефакта (сборка не запускается)
RECORD_PROVENANCE=1 tools/publish-p2-local.sh

# сухой прогон: только проверки, без worktree, коммита и push
DRY_RUN=1 SKIP_BUILD=1 \
  EXPECT_QUALIFIER=1.3.0.20260817-1635 \
  EXPECT_HEAD="$(git rev-parse HEAD)" \
  tools/publish-p2-local.sh

# публикация ровно этого артефакта
SKIP_BUILD=1 \
  EXPECT_QUALIFIER=1.3.0.20260817-1635 \
  EXPECT_HEAD="$(git rev-parse HEAD)" \
  tools/publish-p2-local.sh
```

`DRY_RUN=1` без `SKIP_BUILD=1` всё равно выполняет полный `mvn clean verify`; сухим является только
этап публикации. Для проверки уже собранного артефакта без сборки используйте сочетание из примера.

`SKIP_BUILD=1` требует обеих переменных `EXPECT_QUALIFIER` и `EXPECT_HEAD` и падает до любых
действий с git, если хотя бы одна проверка не прошла:

- каталог p2-репозитория существует, а `content.jar`/`artifacts.jar` — читаемые ZIP с
  `content.xml`/`artifacts.xml` соответственно;
- в репозитории присутствуют feature, `com.codepilot1c.core` и `com.codepilot1c.ui`;
- **ровно один** qualifier на все артефакты `com.codepilot1c.*`, и он равен `EXPECT_QUALIFIER`;
- в `plugins/` нет `*.tests` бандлов, а в `features/` — test-feature;
- рядом лежит файл происхождения `repositories/com.codepilot1c.update/target/repository.provenance`,
  его qualifier и HEAD совпадают с ожидаемыми, а контрольная сумма артефакта не изменилась с момента
  фиксации;
- provenance был записан при чистом рабочем дереве, дерево остаётся чистым, а `HEAD` совпадает с
  `EXPECT_HEAD`.

`EXPECT_HEAD` — hex-префикс commit длиной от 7 до 40 символов; более короткие и не-hex значения
отклоняются как ошибка использования.

`EXPECT_QUALIFIER` можно задавать и в режиме по умолчанию: тогда сборка выполняется, но публикация
будет отклонена, если собранный qualifier не совпал с ожидаемым.

Файл `repository.provenance` лежит **вне** публикуемого каталога и на update site не попадает.

Перед публикацией имеет смысл прогнать тесты самого скрипта:

```bash
bash tools/tests/p2-publish-validate-test.sh
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
bash tools/run-qwen-codex-flow.sh /abs/path/to/task.md
bash tools/run-claude-codex-flow.sh /abs/path/to/task.md
```

или через stdin:

```bash
echo "Fix the failing test in MetadataSyncService and keep the scope minimal." \
  | bash tools/run-qwen-codex-flow.sh -
echo "Fix the failing test in MetadataSyncService and keep the scope minimal." \
  | bash tools/run-claude-codex-flow.sh -
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
bash tools/run-qwen-codex-queue.sh
bash tools/run-claude-codex-queue.sh
```

По умолчанию queue root:

```text
.runs/qwen-codex-queue/queue/
```

Для Claude-wrapper queue root по умолчанию:

```text
.runs/claude-codex-queue/queue/
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
tasks/qwen-codex-queue/
```

Шаблоны:

- `bugfix-minimal`
- `test-gap`
- `narrow-cleanup`
- `review-followup`

Быстро создать task в queue `todo/` можно так:

```bash
bash tools/new-qwen-codex-task.sh --list
bash tools/new-qwen-codex-task.sh bugfix-minimal "fix metadata sync null guard"
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
tasks/qwen-codex-queue/automation/codex-app-queue-run.prompt.md
```

Он предназначен для периодического запуска очереди через Codex app внутри этого проекта.

## Plan-driven flow for local planning bundles

Если source of truth лежит в planning bundle, например:

```text
.planning/local/qwen-runtime-surface
```

используйте:

```bash
bash tools/run-qwen-codex-plan.sh \
  .planning/local/qwen-runtime-surface
bash tools/run-claude-codex-plan.sh \
  .planning/local/qwen-runtime-surface
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
tasks/qwen-codex-queue/automation/codex-app-plan-run.prompt.md
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
.runs/qwen-codex-plan/<plan-key>/
```

Для Claude-wrapper:

```text
.runs/claude-codex-plan/<plan-key>/
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
