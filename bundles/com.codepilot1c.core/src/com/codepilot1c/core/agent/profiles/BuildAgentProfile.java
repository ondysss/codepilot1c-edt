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
            "edt_content_assist",
            "edt_find_references",
            "edt_metadata_details",
            "edt_field_type_candidates",
            "get_platform_documentation",
            "bsl_symbol_at_position",
            "bsl_type_at_position",
            "bsl_scope_members",
            "edt_validate_request",
            "create_metadata",
            "create_form",
            "add_metadata_child",
            "ensure_module_artifact",
            "update_metadata",
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
                PermissionRule.allow("edt_content_assist").forAllResources(),
                PermissionRule.allow("edt_find_references").forAllResources(),
                PermissionRule.allow("edt_metadata_details").forAllResources(),
                PermissionRule.allow("edt_field_type_candidates").forAllResources(),
                PermissionRule.allow("get_platform_documentation").forAllResources(),
                PermissionRule.allow("bsl_symbol_at_position").forAllResources(),
                PermissionRule.allow("bsl_type_at_position").forAllResources(),
                PermissionRule.allow("bsl_scope_members").forAllResources(),
                PermissionRule.allow("edt_validate_request").forAllResources(),
                PermissionRule.allow("edt_trace_export").forAllResources(),
                PermissionRule.allow("edt_metadata_smoke").forAllResources(),

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
                - EDT AST API: edt_content_assist, edt_find_references, edt_metadata_details
                - EDT type provider: edt_field_type_candidates (допустимые типы для поля метаданных)
                - EDT-метаданные: get_platform_documentation, edt_validate_request, create_metadata, create_form, add_metadata_child, ensure_module_artifact, update_metadata, delete_metadata, edt_trace_export
                - EDT BSL-модель: bsl_symbol_at_position, bsl_type_at_position, bsl_scope_members
                - Диагностика метаданных: edt_metadata_smoke (регрессионный smoke-прогон)
                - Документация платформы: перед использованием незнакомых методов/свойств 1С
                  сначала вызывай get_platform_documentation и опирайся на его ответ.
                  Не придумывай API платформы без подтверждения из EDT runtime.
                  Важно: в get_platform_documentation передавай type_name=тип платформы
                  (например DocumentObject), а искомый метод/свойство передавай в contains.

                ## Политика изменения метаданных (обязательно):
                1. Перед create_metadata, create_form, add_metadata_child, update_metadata_properties и delete_metadata_object
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
                10. Для форм в текущем EDT-формате отдельный Form.form/Module.bsl не используется:
                    данные формы хранятся в owner .mdo. Не вызывай ensure_module_artifact для Form FQN.
                11. Если пользователь просит "проверь и исправь типы реквизитов", сначала прочитай текущие
                    метаданные через edt_metadata_details/get_diagnostics, затем отправь update_metadata c children_ops
                    только для реквизитов без типа или с неверным типом, и в конце повторно проверь диагностику.

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
