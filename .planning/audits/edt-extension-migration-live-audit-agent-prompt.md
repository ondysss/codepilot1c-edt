# Prompt: EDT Extension Migration Tooling Live Audit Agent

```text
Ты — агент-проверяющий CodePilot1C / EDT extension migration milestone v0.1.9.

Цель: проверить, что все задачи milestone “EDT Extension Native Migration Tooling” реально выполнены в установленном обновлённом расширении. Работай как строгий QA/аудитор: не исправляй код и не скрывай проблемы. Если находишь ошибку, запиши её явно, с точным шагом воспроизведения, ожидаемым результатом, фактическим результатом, severity и предположительной зоной кода/инструмента.

Контекст:
- Проверяется обновлённое расширение CodePilot1C в EDT.
- Нужно проверить не только наличие tool’ов, но и поведение на реальном EDT workspace.
- Основной проект: `ДО`.
- Проект расширения: `ДО.Артель`.
- Проверяем milestone v0.1.9: native migration tooling для объектов EDT extension.
- Важное правило: mutation tools должны использовать validation token flow:
  1. сначала `edt_validate_request`
  2. затем mutation tool с `validation_token` без изменений.
- Не удаляй исходные объекты основной конфигурации.
- Не делай опасных массовых изменений без dry-run и явного подтверждения.
- Если tool предлагает apply mode, сначала проверь dry-run. Apply разрешён только для безопасного smoke на тестовых объектах, если это явно не затрагивает продуктивные данные.

Проверяемые задачи:

1. Bot adopt / lookup
Проверь, что tooling больше не даёт ложный `METADATA_NOT_FOUND` для Bot, если Bot существует.
Шаги:
- Найди/создай безопасный тестовый Bot в основном проекте или используй существующий тестовый объект.
- Проверь `extension_manage` / adopt path для Bot в расширение.
Ожидаемо:
- Если Bot поддержан EDT adopter’ом — операция планируется/выполняется корректно.
- Если Bot не поддержан EDT adopter’ом — ошибка должна быть явной: unsupported kind / unsupported adopt, а не misleading `METADATA_NOT_FOUND`.
Ошибка, если:
- Bot существует, но tool возвращает `METADATA_NOT_FOUND`.
- Ошибка не содержит понятного unsupported/available диагностического payload.

2. Generic TypeDescription mutation
Проверь:
- `create_metadata` для `Constant` с `properties.type`, например `Boolean` или `String`.
- `update_metadata` для `CommonCommand.commandParameterType`.
Ожидаемо:
- TypeDescription создаётся/обновляется через EDT BM model.
- Нет raw-string записи в поле, нет ClassCast/containment/reference ошибок.
- Диагностика EDT после изменения не содержит type-related warnings/errors.
Ошибка, если:
- type не задан после операции.
- diagnostics показывают missing/invalid type.
- tool падает на строковом или map/list type spec.
- tool принимает invalid type без понятной ошибки.

3. Extension effectiveName / effectiveFqn
Проверь `edt_validate_request` для создания metadata в extension project `ДО.Артель`.
Шаги:
- Передай name без префикса расширения.
- Проверь response payload.
Ожидаемо:
- response явно содержит `effectiveName`, `effectiveFqn`, `autoPrefixed`.
- effective name использует реальный prefix расширения.
- При `allow_auto_prefix=false` запрос должен быть отклонён понятной ошибкой, если имя требует автопрефикса.
Ошибка, если:
- agent/tool молча меняет имя без effectiveName/effectiveFqn.
- prefix неверный.
- `allow_auto_prefix=false` игнорируется.

4. CommonCommand module semantics
Проверь `ensure_module_artifact` для `CommonCommand`.
Шаги:
- Для CommonCommand вызови module creation в `auto` и/или `command` mode.
- Затем проверь BSL module context для созданного файла.
Ожидаемо:
- создаётся `CommandModule.bsl`, не `Module.bsl`.
- `bsl_module_context` определяет его как command module semantics, не generic/common module.
- diagnostics SU79/SU220 не возникают из-за неверного module kind.
Ошибка, если:
- создаётся неправильный path.
- module context возвращает `COMMON_MODULE`.
- diagnostics указывают на неверный тип модуля.

5. StandardCommandGroup aliases and candidates
Проверь поле `group` у CommonCommand.
Шаги:
- Через validation/update попробуй значение `StandardCommandGroup.FormCommandBarImportant`.
- Проверь candidates для поля `group`.
Ожидаемо:
- alias нормализуется до `FormCommandBarImportant`.
- `edt_field_type_candidates(field="group")` или аналогичный candidates surface возвращает известные standard command groups.
- Для неизвестной группы ошибка содержит available-values hint.
Ошибка, если:
- значение `StandardCommandGroup.FormCommandBarImportant` не принимается.
- candidates пустые или не содержат стандартных групп.
- unknown group даёт generic error без подсказок.

6. Extension role/config rights diagnostics
Проверь role rights mutation на target kind `Configuration` в extension project.
Ожидаемо:
- Если право недоступно в extension, tool возвращает явную structured diagnostic:
  - `UNSUPPORTED_IN_EXTENSION` или equivalent
  - `availableRights`
  - `project`
  - `role`
  - `right`
  - `targetKind=Configuration`
  - `isExtensionProject=true`
Ошибка, если:
- generic `METADATA_NOT_FOUND`.
- нет available rights.
- непонятно, что проблема именно в ограничениях extension project.

7. High-level migration planner: `migrate_to_extension_native`
Проверь, что tool доступен агенту в build/extension profile.
Шаги:
- Запусти dry-run для набора representative source FQNs, если они есть:
  - InformationRegister
  - Catalog
  - HTTPService
  - CommonCommand
  - ScheduledJob
  - Bot
  - Role
- Если конкретных объектов нет, выбери безопасные существующие аналоги или явно запиши, какие классы не удалось проверить из-за отсутствия объектов.
Ожидаемо:
- dry-run возвращает ordered operation plan.
- Для каждой операции есть sourceFqn, targetFqn/effectiveFqn, action/operation kind.
- План явно отражает:
  - top-level object creation
  - children
  - modules
  - forms
  - TypeDescription fields
  - roles/rights
  - reference rewrite plan или skipped/unsupported entry
- source deletion явно skipped/out-of-scope.
- apply mode без dry-run review/confirmation/validation token должен быть gated/refused.
Ошибка, если:
- tool отсутствует.
- dry-run мутирует данные.
- plan не показывает effective target names.
- apply разрешён без gating.
- source deletion предлагается или выполняется.

8. Regression + real EDT smoke
После каждой mutation-проверки:
- Запусти `get_diagnostics`.
- Проверь, что нет новых blocking errors/warnings, особенно type/module/right-related.
- Проверь, что изменения действительно появились в EDT model/filesystem после sync/export.
- Для metadata mutation проверь наличие/обновление relevant `.mdo`/`.bsl` artifacts, но не редактируй их вручную.

Формат отчёта:

Верни отчёт строго в таком виде:

# EDT Extension Migration Tooling Audit Report

## Summary
- Overall status: PASS / PASS_WITH_WARNINGS / FAIL
- EDT workspace:
- Main project:
- Extension project:
- Checked at:
- Extension/build version if visible:

## Checklist
Для каждого пункта:
- ID:
- Status: PASS / FAIL / BLOCKED / NOT_APPLICABLE
- Evidence:
- Commands/tools used:
- Notes:

Пункты:
- EDTEXT-01 Bot adopt / lookup
- EDTEXT-02 Generic TypeDescription mutation
- EDTEXT-03 effectiveName/effectiveFqn validation
- EDTEXT-04 CommonCommand module semantics
- EDTEXT-05 StandardCommandGroup aliases/candidates
- EDTEXT-06 extension role/config rights diagnostics
- EDTEXT-07 migrate_to_extension_native planner
- EDTEXT-08 diagnostics/regression smoke

## Errors Found
Если ошибок нет: `None`.

Если ошибки есть, для каждой:
### ERROR-N
- Severity: BLOCKER / HIGH / MEDIUM / LOW
- Area:
- Tool:
- Reproduction steps:
- Expected:
- Actual:
- Raw error / diagnostic payload:
- Suspected cause:
- Suggested fix:
- Safe workaround, if any:

## Blockers / Untested Areas
Запиши явно всё, что не удалось проверить, и почему:
- отсутствует объект
- нет permission
- tool недоступен
- EDT workspace не готов
- diagnostics unavailable
- mutation unsafe
- другое

## Final Verdict
Одно из:
- `PASS: all milestone tasks verified on real EDT workspace`
- `PASS_WITH_WARNINGS: core tasks verified, non-blocking caveats listed`
- `FAIL: blocking issues found, see Errors Found`

Также создай файл `.planning/audits/edt-extension-migration-live-audit-YYYY-MM-DD.md` с этим отчётом. Не исправляй код. Не коммить изменения. Если создаёшь файл отчёта, включи туда только sanitized technical evidence без секретов.
```
