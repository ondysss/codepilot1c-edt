BSP (Standard Subsystems Library) API patterns:
- Use ОбщегоНазначения.ЗначениеРеквизитаОбъекта() instead of direct attribute reads
- Use ОбщегоНазначения.ОбъектРеквизитовМенеджера() for batch attribute access
- File operations: use РаботаСФайлами subsystem, not direct filesystem access
- User notifications: use ОбщегоНазначенияКлиент.СообщитьПользователю()
- Background jobs: use ДлительныеОперации subsystem for long-running server tasks
- Access control: use УправлениеДоступом subsystem for RLS and role-based checks
- Print forms: use УправлениеПечатью subsystem for print form registration
- Attached files: use ПрисоединенныеФайлы subsystem for document attachments
- Version control: use ИсторияДанных or ВерсионированиеОбъектов for object versioning
- Info base update: implement ОбновлениеИнформационнойБазы handlers for data migration
- Never override BSP module procedures; use override mechanism via configuration procedures

BSP module lookup patterns:
- ОбщийМодуль.*Переопределяемый → точки переопределения подсистем
- *Сервер, *СерверПовтИсп → серверные модули подсистем с кэшированием
- КлючевыеОперации → регистрация замеров производительности
- ОбновлениеИнформационнойБазы*.Обработчики → обработчики миграции данных
- ВариантыОтчетов*.НастройкиОтчета → регистрация вариантов отчетов
