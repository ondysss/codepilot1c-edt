# CodePilot1C EDT — релиз 0.1.7.20260217-1730

Дата: 17.02.2026

## Что исправлено

Исправлены все 7 проблем, выявленные в повторном тестировании MCP-инструментов:

1. `update_metadata`: устранено некорректное приведение `int -> float` для числовых полей.
2. `scan_metadata_index`: восстановлен корректный возврат объектов метаданных и работа фильтра `scope`.
3. `bsl_type_at_position`: добавлен fallback, убрана ошибка `EDT_SERVICE_UNAVAILABLE` при недоступном BM resource set.
4. `bsl_scope_members`: добавлен fallback через content assist с корректной выдачей предложений.
5. `mutate_form_model add_field`: поля создаются полностью (включая `visible`, `enabled`, `dataPath`, `type`).
6. `ensure_module_artifact` для форм: поддержано создание `Forms/<Имя>/Module.bsl`.
7. `delete_metadata` для вложенных форм: удаляются файловые артефакты формы на диске.

## Результат верификации

- Статус: `7/7` исправлено.
- Регрессий по итогам верификации не обнаружено.

## Сборка

- Полная сборка реактора: `mvn -DskipTests package`.
- P2 update site: `repositories/com.codepilot1c.update/target/repository`.
- Архив update site: `repositories/com.codepilot1c.update/target/com.codepilot1c.update-0.1.7-SNAPSHOT.zip`.
