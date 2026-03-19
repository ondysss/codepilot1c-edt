# Наши фиксы форка andromanpro/codepilot1c-edt

Ветка: `fix/fuzzy-matcher-findOriginalEnd`

---

## Fix 1: FuzzyMatcher trailing newline (issue #22)

**Коммит:** `082003c`

**Проблема:** `edit_file` с многострочным `old_text` при стратегии NORMALIZE_WHITESPACE захватывал лишнюю строку. Последняя строка `new_text` склеивалась со следующей строкой документа, строка за `old_text` терялась.

**Причина:** `split("\n", -1)` даёт trailing пустой элемент, `lineCount` завышается на 1.

**Фикс:** `FuzzyMatcher.findOriginalEnd()` — фильтрация trailing empty element перед подсчётом строк.

**Файлы:** `EdtFuzzyTextSearchUtils.java` (или `FuzzyMatcher.java`)

### Текст issue для ondysss
```
Title: fix: FuzzyMatcher.findOriginalEnd() — trailing newline захватывает лишнюю строку

Исправляет баг из issue #22.

`split("\n", -1)` на строке с trailing `\n` возвращает trailing пустой элемент,
что завышает lineCount на 1 и заставляет цикл while потребить лишнюю строку документа.

Фикс: вычитать 1 из lineCount если последний элемент пустой.
```

---

## Fix 2: EDT 2025.2.0 API compat — NumberValue NPE

**Коммит:** `9a2660b`

**Проблема:** После `add_metadata_child` с типом Number, `update_database` падал с NPE:
```
NullPointerException: Cannot invoke "BigDecimal.toPlainString()"
  because NumberValue.getValue() == null at ValueWriter.writeValue()
```

**Причина:** EDT EMF-адаптер создаёт `NumberValue` с `null` BigDecimal как FillValue при установке типа Number на атрибут. CodePilot1C не инициализировал его.

**Фикс:** `EdtMetadataService.fixNullNumberFillValue()` — детектирует NumberValue с null и заменяет на `BigDecimal.ZERO`.

**Файлы:** `EdtMetadataService.java`

### Текст issue для ondysss
```
Title: fix: NumberValue NPE при update_database после add_metadata_child с типом Number

После создания атрибута типа Number через add_metadata_child, update_database падает:
NPE в ValueWriter.writeValue() — NumberValue.getValue() == null.

EDT EMF-адаптер создаёт NumberValue с null внутри BigDecimal.
Фикс: после setAttributeType(), проверять FillValue на NumberValue(null)
и заменять на NumberValue(BigDecimal.ZERO).
```

---

## Fix 3: EDT 2025.2.0 API compat — resolveThickClientInfo

**Коммит:** `49e5654`

**Проблема:** `getThickClientInfo()` полностью удалён из `IRuntimeComponentManager` в EDT 2025.2.0.
Reflection-вызов падал с `NoSuchMethodException`. Сломаны 5 инструментов:
- `edt_launch_app`
- `import_project_from_infobase`
- `qa_run`
- `qa_status`
- `edt_update_infobase`

**Новый API EDT 2025.2.0:**
```
IRuntimeComponentManager:
  - getThickClientInfo(InfobaseReference) → УДАЛЁН
  - resolveExecutor(Class<C>, Class<E>, RuntimeInstallation, String) → ComponentExecutorInfo<C,E>

IResolvableRuntimeInstallationManager (новый OSGi сервис):
  - resolveByProjectAndInfobase(typeId, project, infobase, accessType) → IResolvableRuntimeInstallation
  - resolveByVersionOrMask(typeId, versionMask) → IResolvableRuntimeInstallation

IResolvableRuntimeInstallation:
  - resolve(typeIds, arch) → RuntimeInstallation
```

**Фикс:** Переписан `resolveThickClientInfo()` через новый API:
1. `IResolvableRuntimeInstallationManager` (новый ServiceTracker в VibeCorePlugin)
2. `resolveByProjectAndInfobase(THICK_CLIENT, project, infobase, CLIENT_LAUNCH)`
3. `resolvable.resolve([THICK_CLIENT], AUTO)` → `RuntimeInstallation`
4. `resolveExecutor(ILaunchableRuntimeComponent, IThickClientLauncher, installation, THICK_CLIENT)` → `ComponentExecutorInfo`
5. Конструируем `new ThickClientInfo(resolvable, executorInfo.getInstallation(), ...)`

**Константы:** `IRuntimeComponentTypes.THICK_CLIENT = "com._1c.g5.v8.dt.platform.services.core.componentTypes.ThickClient"`

**Файлы:** `EdtRuntimeService.java`, `EdtRuntimeGateway.java`, `VibeCorePlugin.java`, `MANIFEST.MF`

### Текст issue для ondysss
```
Title: fix: edt_launch_app / import_from_infobase не работают на EDT 2025.2.0

IRuntimeComponentManager.getThickClientInfo() полностью удалён в EDT 2025.2.0.
Reflection на него падает NoSuchMethodException — сломаны все runtime инструменты.

Новый API: IResolvableRuntimeInstallationManager (новый OSGi сервис) +
resolveExecutor(ILaunchableRuntimeComponent, IThickClientLauncher, installation, THICK_CLIENT).

PR с фиксом: andromanpro/codepilot1c-edt, branch fix/fuzzy-matcher-findOriginalEnd
```

---

## Fix 4: get_diagnostics scope=file — кросс-модульный шум (issue #24)

**Коммит:** `49e5654`

**Проблема:** `get_diagnostics(scope=file)` возвращал диагностики из чужих модулей из-за широкого token-matching в runtime markers.

**Фикс:** Для `scope=file` и `scope=active_editor`, `include_runtime_markers` по умолчанию = `false`. Пользователь может явно передать `include_runtime_markers: true`.

**Файлы:** `GetDiagnosticsTool.java`

### Текст issue для ondysss
```
Title: fix: get_diagnostics(scope=file) — runtime markers по умолчанию false (issue #24)

Для scope=file и scope=active_editor, include_runtime_markers теперь по умолчанию false,
чтобы избежать кросс-модульных ложных срабатываний (issue #24).

Для scope=project поведение не изменилось (default=true).
Пользователь может явно передать include_runtime_markers:true для file/active_editor.
```

---

## Таблица несовместимости EDT 2025.2.0

| Старый метод (2025.1.5) | Новый метод (2025.2.0) | Инструменты |
|---|---|---|
| `exportConfigurationToXml()` | `exportFullXmlFromInfobase()` | `import_project_from_infobase` |
| `getSettings(infobase, ...)` | `resolveSettings(infobase)` | все runtime |
| `getThickClientInfo()` | удалён → новый API через `resolveExecutor` | `edt_launch_app`, `qa_run`, etc. |
| `updateInfobase()` | `updateDatabaseConfiguration()` | `edt_update_infobase` |

---

## Статус PR в upstream (ondysss/codepilot1c-edt)

- Ждём реакции на Fix 1 (FuzzyMatcher). Если примет — отправим Fix 2-4 отдельными PR.
- Наша сборка: `repositories/com.codepilot1c.update/target/com.codepilot1c.update-0.1.7-SNAPSHOT.zip`
