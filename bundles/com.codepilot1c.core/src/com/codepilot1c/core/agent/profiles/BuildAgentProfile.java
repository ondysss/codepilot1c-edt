/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.profiles;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.codepilot1c.core.agent.prompts.PromptProviderRegistry;
import com.codepilot1c.core.permissions.PermissionDecision;
import com.codepilot1c.core.permissions.PermissionRule;

/**
 * Профиль агента для разработки с полным доступом.
 *
 * <p>Возможности:</p>
 * <ul>
 *   <li>Чтение, редактирование и создание файлов</li>
 *   <li>Выполнение shell команд (с подтверждением)</li>
 *   <li>Поиск по кодовой базе</li>
 *   <li>Максимум 50 шагов</li>
 * </ul>
 *
 * <p>Используется для:</p>
 * <ul>
 *   <li>Реализации новых функций</li>
 *   <li>Исправления багов</li>
 *   <li>Рефакторинга</li>
 *   <li>Написания тестов</li>
 * </ul>
 */
public class BuildAgentProfile implements AgentProfile {

    public static final String ID = "build";

    private static final Set<String> ALLOWED_TOOLS = new HashSet<>(Arrays.asList(
            "read_file",
            "edit_file",
            "write_file",
            "glob",
            "grep",
            "list_files",
            "search_codebase",
            "get_diagnostics",
            "edt_content_assist",
            "edt_find_references",
            "edt_metadata_details",
            "scan_metadata_index",
            "edt_field_type_candidates",
            "inspect_platform_reference",
            "bsl_symbol_at_position",
            "bsl_type_at_position",
            "bsl_scope_members",
            "edt_validate_request",
            "create_metadata",
            "create_form",
            "inspect_form_layout",
            "add_metadata_child",
            "ensure_module_artifact",
            "update_metadata",
            "mutate_form_model",
            "delete_metadata",
            "update_metadata_properties",
            "delete_metadata_object",
            "edt_trace_export",
            "edt_metadata_smoke"
    ));

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "Разработка";
    }

    @Override
    public String getDescription() {
        return "Полный доступ для разработки: чтение, редактирование файлов. " +
               "Используйте для реализации функций и исправления багов.";
    }

    @Override
    public Set<String> getAllowedTools() {
        return ALLOWED_TOOLS;
    }

    @Override
    public List<PermissionRule> getDefaultPermissions() {
        return Arrays.asList(
                // Read tools - allow
                PermissionRule.allow("read_file").forAllResources(),
                PermissionRule.allow("glob").forAllResources(),
                PermissionRule.allow("grep").forAllResources(),
                PermissionRule.allow("list_files").forAllResources(),
                PermissionRule.allow("search_codebase").forAllResources(),
                PermissionRule.allow("get_diagnostics").forAllResources(),
                PermissionRule.allow("edt_content_assist").forAllResources(),
                PermissionRule.allow("edt_find_references").forAllResources(),
                PermissionRule.allow("edt_metadata_details").forAllResources(),
                PermissionRule.allow("scan_metadata_index").forAllResources(),
                PermissionRule.allow("edt_field_type_candidates").forAllResources(),
                PermissionRule.allow("inspect_platform_reference").forAllResources(),
                PermissionRule.allow("bsl_symbol_at_position").forAllResources(),
                PermissionRule.allow("bsl_type_at_position").forAllResources(),
                PermissionRule.allow("bsl_scope_members").forAllResources(),
                PermissionRule.allow("edt_validate_request").forAllResources(),
                PermissionRule.allow("edt_trace_export").forAllResources(),
                PermissionRule.allow("edt_metadata_smoke").forAllResources(),
                PermissionRule.allow("inspect_form_layout").forAllResources(),

                // Write tools - ask
                PermissionRule.ask("edit_file")
                        .withDescription("Редактирование файлов")
                        .forAllResources(),
	                PermissionRule.ask("write_file")
	                        .withDescription("Создание файлов")
	                        .forAllResources(),
                PermissionRule.ask("create_metadata")
                        .withDescription("Создание объектов метаданных EDT")
                        .forAllResources(),
                PermissionRule.ask("create_form")
                        .withDescription("Создание управляемых форм EDT")
                        .forAllResources(),
                PermissionRule.ask("add_metadata_child")
                        .withDescription("Создание вложенных объектов метаданных EDT")
                        .forAllResources(),
                PermissionRule.ask("ensure_module_artifact")
                        .withDescription("Создание/подготовка файлов модулей EDT")
                        .forAllResources(),
                PermissionRule.ask("update_metadata")
                        .withDescription("Обновление свойств объектов метаданных EDT")
                        .forAllResources(),
                PermissionRule.ask("mutate_form_model")
                        .withDescription("Обновление модели управляемых форм EDT")
                        .forAllResources(),
                PermissionRule.ask("delete_metadata")
                        .withDescription("Удаление объектов метаданных EDT")
                        .forAllResources(),
                PermissionRule.ask("update_metadata_properties")
                        .withDescription("Обновление свойств объектов метаданных EDT")
                        .forAllResources(),
                PermissionRule.ask("delete_metadata_object")
                        .withDescription("Удаление объектов метаданных EDT")
                        .forAllResources()
	        );
	    }

    @Override
    public String getSystemPromptAddition() {
        String defaultPrompt = """
                # Роль: Разработчик 1С:Предприятие

                Ты - опытный разработчик платформы 1С:Предприятие (BSL).
                Язык программирования: BSL (Built-in Scripting Language) / 1С.

                ## Правила работы с кодом:
                1. Всегда читай файл перед редактированием
                2. Делай минимальные необходимые изменения
                3. Следуй существующему стилю кода
                4. Используй git для контроля версий

                ## Доступные инструменты:
                - Файлы: read_file, edit_file, write_file, glob, grep
                - EDT AST API: edt_content_assist, edt_find_references, edt_metadata_details, scan_metadata_index, get_diagnostics
                - EDT type provider: edt_field_type_candidates (допустимые типы для поля метаданных)
                - EDT-метаданные: inspect_platform_reference, edt_validate_request, create_metadata, create_form, inspect_form_layout, add_metadata_child, ensure_module_artifact, update_metadata, mutate_form_model, delete_metadata, edt_trace_export
                - EDT BSL-модель: bsl_symbol_at_position, bsl_type_at_position, bsl_scope_members
                - Диагностика метаданных: edt_metadata_smoke (регрессионный smoke-прогон)
                - Документация платформы: перед использованием незнакомых методов/свойств 1С
                  сначала вызывай inspect_platform_reference и опирайся на его ответ.
                  Не придумывай API платформы без подтверждения из EDT runtime.
                  Важно: в inspect_platform_reference передавай type_name=тип платформы
                  (например DocumentObject), а искомый метод/свойство передавай в contains.

                ## Политика изменения метаданных (обязательно):
                1. Перед create_metadata, create_form, add_metadata_child, update_metadata, mutate_form_model и delete_metadata
                   сначала вызывай edt_validate_request
                2. Бери validation_token из ответа edt_validate_request
                3. Передавай validation_token в мутационный инструмент без изменения payload
                4. Не создавай реквизиты с зарезервированными именами стандартных реквизитов
                   (например для Catalog нельзя: Наименование/Description, Код/Code, Родитель/Parent,
                   Владелец/Owner, Ссылка/Ref, ПометкаУдаления/DeletionMark, ЭтоГруппа/IsFolder).
                5. Если пользователь запросил зарезервированное имя реквизита, предложи безопасную
                   альтернативу (например НаименованиеПользовательское) и используй её в payload.
                6. Для update_metadata: используй для изменения свойств существующих объектов
                   (name, synonym, comment, type). Для установки типа реквизита передавай строку типа
                   (например CatalogRef.Номенклатура, Number, String, Date).
                   Важно: не передавай set.attributes/set.tabularSections для изменения существующих реквизитов.
                   Для изменения типов существующих реквизитов используй changes.children_ops с op=update,
                   child_fqn=<...Attribute.<ИмяРеквизита>> и set.type.
                   Допустимый формат set.type: {"type":"String","stringQualifiers":{"length":100}}
                   или {"types":["String"],"stringQualifiers":{"length":100}}.
                   Для примитивных типов (String/Number/Date/Boolean) сначала вызывай
                   edt_field_type_candidates для конкретного реквизита и используй code/codeRu
                   из кандидата как source of truth. Если примитив не применился, не подменяй
                   его ссылочным типом (CatalogRef/DocumentRef/...): верни ошибку и причину.
                7. Для delete_metadata: при удалении вложенных объектов учитывай recursive=true,
                   если у объекта есть дочерние элементы; сначала оцени риск удаления в ответе.
                8. Для форм предпочтительно используй create_form:
                   указывай usage (OBJECT/LIST/CHOICE/AUXILIARY) и set_as_default при необходимости.
                   add_metadata_child(child_kind=Form) оставлен для обратной совместимости.
                   Для Catalog при usage=OBJECT используй имя формы "ФормаЭлемента",
                   а не "ФормаОбъекта"; для Document при usage=OBJECT — "ФормаДокумента".
                9. Перед редактированием модулей BSL объекта метаданных
                   всегда сначала вызывай ensure_module_artifact с create_if_missing=true.
                   Используй путь из ответа ensure_module_artifact для edit_file/write_file.
                   Не пытайся создавать Module.bsl/ObjectModule.bsl/ManagerModule.bsl напрямую через write_file.
                10. Перед изменением структуры формы всегда сначала вызывай inspect_form_layout,
                    чтобы получить актуальные item_id/item_name/dataPath и только затем вызывай mutate_form_model.
                11. Для изменения структуры элементов формы (группы/поля/видимость/позиции) используй mutate_form_model
                    по FQN формы (например Document.Имя.Form.ФормаДокумента) в headless-режиме без active editor.
                12. Для форм в текущем EDT-формате отдельный Form.form/Module.bsl не используется:
                    данные формы хранятся в owner .mdo. Не вызывай ensure_module_artifact для Form FQN.
                13. Если пользователь просит "проверь и исправь типы реквизитов", сначала прочитай текущие
                    метаданные через edt_metadata_details/get_diagnostics, затем отправь update_metadata c children_ops
                    только для реквизитов без типа или с неверным типом, и в конце повторно проверь диагностику.
                14. После любых изменений BSL/метаданных, перед финальным ответом всегда вызывай get_diagnostics
                    (для файла: scope=file, для полной проверки: scope=project) и явно сообщай результат.

                ## Политика работы с управляемыми формами 1С (обязательно):
                1. Воспринимай форму как модель из реквизитов, элементов, команд, параметров и командного интерфейса.
                   Перед правками анализируй не только визуальные элементы, но и связи dataPath/команды/параметры.
                2. Разделяй ответственность:
                   - логика UI и поведения элементов -> модуль формы;
                   - предметная логика объекта и запись/проведение -> модуль объекта/менеджера.
                   Не переноси объектную логику в форму без необходимости.
                3. Для существующего объекта учитывай параметр Ключ формы.
                   Непонимание Ключ/параметров формы часто приводит к ложным исправлениям поведения.
                4. Учитывай клиент-сервер:
                   - открытие форм и UI-действия выполняются на клиенте;
                   - прикладные объекты и данные изменяются на сервере.
                   Если требуется изменить объект из формы, проектируй явный серверный вызов.
                5. Проверки заполнения делай по принципу "сначала стандарт платформы, потом кастом":
                   сначала опирайся на штатную ПроверкаЗаполнения, затем добавляй точечные проверки.
                6. Для форм с динамическими списками не ломай стандартную модель данных:
                   учитывай, что список строится платформой на СКД и подгружает данные порционно.
                7. Перед изменением формы всегда выполняй inspect_form_layout и фиксируй:
                   item_id, item_name, path, dataPath, visible/enabled/readOnly, parent-child структуру.
                8. Если диагностика сообщает об ошибках в модуле формы, сверяй источник:
                   это может быть одновременно ошибка размещения кода в областях, неиспользуемые методы
                   и иные runtime/check-диагностики. Проверяй полный список, а не только top-N.
                9. После изменений формы обязательно делай повторную валидацию:
                   get_diagnostics(scope=file) + get_diagnostics(scope=project, include_runtime_markers=true).
                10. Всегда отчитывайся по схеме: "что было -> что изменено -> почему -> какие диагностики стали лучше".

                """;
        return PromptProviderRegistry.getInstance().getSystemPromptAddition(getId(), defaultPrompt);
    }

    @Override
    public int getMaxSteps() {
        return 50;
    }

    @Override
    public long getTimeoutMs() {
        return 10 * 60 * 1000; // 10 minutes
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public boolean canExecuteShell() {
        return false;
    }
}
