# Исследование и план восстановления GSD Native

Дата: 2026-07-28

## Вывод

Функциональность GSD была удалена коммитом
`6be3816e4f748ddfb89ee241cd318e679f76a1ad`
(`refactor: remove deprecated phased planning integration`) и попала в основную
историю через merge-коммит `2903a741`. Исходная реализация была добавлена
коммитом `8e828369`.

Прямое восстановление удалённых файлов нецелесообразно: прежняя реализация
представляла собой один расширенный build-профиль, один универсальный
`gsd_plan` tool и JSON-файл на сессию во внутреннем state каталоге Eclipse.
Она не обеспечивала строгую изоляцию фаз, проектный source of truth,
межпроцессную конкурентность, независимую проверку и доказательную
приёмку.

В качестве референса исследован `open-gsd/gsd-core`, локальная копия:
`/Users/alexorlik/repo/gsd-core`, ветка `next`, ревизия
`1f6822ccba8e0348c7909ae73a10d3927e77268c`.

Выбран вариант **Native Java GSD**: GSD становится частью существующих
OSGi/core/UI контрактов CodePilot1C, а не встраиваемым Node.js runtime.

## Что было в удалённой реализации CodePilot1C

- Профиль `GsdAgentProfile` наследовал полный `BuildAgentProfile`.
- Один инструмент `gsd_plan` управлял всеми операциями.
- `PlanArtifactStore` хранил один JSON на session id во внутреннем plugin state.
- Фазы `DISCUSS → PLAN → EXECUTE → VERIFY` в основном направлялись prompt-ом.
- Возобновление выполнялось по session id и текстовому resume summary.
- UI содержал переключатель режима и отображение прогресса.
- Не было атомарного проектного состояния, CAS revision, файловой блокировки,
  строгих отдельных схем операций, security scan и доказательного запрета на
  ложное завершение.

## Отличия open-gsd

| Область | Удалённый CodePilot1C GSD | open-gsd |
|---|---|---|
| Состояние | Внутренний JSON на сессию | Проектные `.planning`-артефакты и `STATE.md` |
| Фазы | Один профиль с полным доступом | Раздельные команды, роли и фазовые workflow |
| План | Один изменяемый объект | Планы, контекст, summaries, verification и UAT |
| Исполнение | Последовательный agent loop | Dependency waves и параллельные fresh-context исполнители |
| Проверка | Самопроверка того же агента | Plan checker, verifier, integration/UI checkers |
| Конкурентность | Нет CAS/межпроцессного lock | State/capability lifecycle, locks и guards |
| Безопасность | Нет content security слоя | Trust boundaries, prohibition enforcement, redaction |
| Наблюдаемость | Общие tool events | Специализированные state/phase/verification события |
| Интеграция | Привязка к одной Eclipse-сессии | Несколько host/runtime adapters |

## Специфика CodePilot1C, которой нет в generic open-gsd

Копирование open-gsd один в один также неверно. Для 1C:EDT обязательны:

- BM-модель как source of truth для metadata mutation;
- `edt_validate_request → validation_token` перед каждой EDT-мутацией;
- запрет прямого изменения `.mdo` как основного пути;
- сериализация конфликтующих EDT/BM-мутаций;
- отдельная проверка BM commit, filesystem export и diagnostics;
- top-object normalization перед `bmGetFqn()`;
- динамическая регистрация UI-only tools без зависимости `core → UI`;
- provider-neutral tool surface.

## Рассмотренные варианты восстановления

1. **Точный revert удаления.** Самый быстрый путь, но возвращает монолитный
   профиль, session-local state и известные архитектурные ограничения.
2. **Vendor open-gsd runtime.** Даёт максимум upstream-возможностей, но
   добавляет Node.js runtime, дублирует agent/tool orchestration и плохо
   согласуется с OSGi/EDT lifecycle.
3. **MCP bridge к open-gsd.** Сохраняет upstream отдельно, но создаёт две
   конкурирующие системы профилей, разрешений, состояния и диагностики.
4. **Native Java GSD.** Реализует проверенные идеи open-gsd на существующих
   `AgentProfile`, `ToolRegistry`, permission и SWT-контрактах. Выбран.
5. **Hybrid Native + `.planning` compatibility.** Native state остаётся source
   of truth, а `.planning` импортируется/экспортируется для совместимости.
6. **Полный multi-agent orchestrator поверх Native GSD.** Добавляет wave
   scheduling, fresh contexts и независимых checker/verifier агентов после
   стабилизации базового state/tool контракта.

## Улучшения относительно обеих исходных реализаций

1. Пять отдельных профилей: Discuss, Plan, Execute, Verify, Ship.
2. Typed project state с monotonic revision вместо session-local mutable JSON.
3. Атомарная запись, backup/recovery, JVM + OS locks и fail-closed corruption.
4. Восемь узких provider-neutral tools вместо универсального `gsd_plan`, включая
   отдельные операции для verification outcome и shipment.
5. Строгая state machine с entry guards и единственным обоснованным rollback.
6. Evidence provenance: задача и workflow не закрываются на одном `INFERRED`.
7. Content caps, Unicode sanitation, secret redaction и injection detection.
8. EDT-aware execution: metadata mutation только через validation-token flow.
9. Read-only SWT status panel без скрытой смены профиля и без записи состояния.
10. Детерминированные `STATE.md`/`PLAN.md` projections из JSON source of truth.
11. Явное optimistic concurrency поведение для параллельных agent/tool вызовов.
12. Независимый verifier/checker как отдельная роль, а не самооценка исполнителя.

## План реализации

1. Ввести строгие фазовые профили и синхронизированные prompts/permissions.
2. Добавить typed state, guards, atomic store и Markdown projections.
3. Добавить узкие GSD tools со строгими JSON schemas и structured errors.
4. Встроить security обработку во все model-facing и persistent text fields.
5. Добавить evidence-backed переходы и EDT-aware правила исполнения.
6. Добавить read-only UI состояния и явную рекомендацию следующего профиля.
7. Добавить совместимый импорт legacy session artifacts и `.planning` как
   отдельный, явно запускаемый migration path.
8. Провести независимое ревью, regression tests и полный Tycho reactor build.

## Критерии готовности

- Все GSD tools зарегистрированы только в `ToolRegistry.registerDefaultTools()`.
- Schemas валидны, запрещают неизвестные поля и совпадают с runtime parsing.
- Read-only профили не имеют project/EDT/Git mutation и profile-escape tools.
- `DONE` и `CLOSED` невозможны без проверяемого evidence.
- Corrupt/stale state приводит к machine-readable fail-closed результату.
- UI чтение не создаёт и не восстанавливает файлы состояния.
- EDT metadata mutation сохраняет обязательный validation-token flow.
- Целевые тесты и `mvn -DskipTests package` из корня завершаются успешно.

## Интегрированный контракт SHIPPING (2026-08-21)

Реализованный workflow использует
`DISCOVERY → PLANNING → EXECUTING → VERIFYING → SHIPPING → CLOSED`.
`gsd_record_verification_outcome` фиксирует `PASSED`/`FAILED` для persisted acceptance
criterion только в `VERIFYING`; обязательные criteria должны быть `PASSED` до перехода
в `SHIPPING`. `gsd_record_shipment` фиксирует delivery result только в `SHIPPING`, не
принимает `LEGACY_MIGRATED`, требует полный cycle/generation/revision token и не допускает
замену уже записанного shipment. Exact duplicate с актуальным token — no-op; stale token
не становится успешным retry.

Все восемь built-in GSD tools регистрируются в `ToolRegistry.registerDefaultTools()`.
UI-only tools по-прежнему регистрируются динамически из `com.codepilot1c.ui`; зависимости
Core на UI API для GSD нет.
