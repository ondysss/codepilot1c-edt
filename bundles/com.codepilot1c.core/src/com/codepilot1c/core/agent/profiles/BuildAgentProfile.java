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

import com.codepilot1c.core.agent.prompts.AgentPromptTemplates;
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
            "get_diagnostics",
            "edt_content_assist",
            "edt_find_references",
            "edt_metadata_details",
            "scan_metadata_index",
            "dcs_get_summary",
            "dcs_list_nodes",
            "dcs_create_main_schema",
            "dcs_upsert_query_dataset",
            "dcs_upsert_parameter",
            "dcs_upsert_calculated_field",
            "extension_list_projects",
            "extension_list_objects",
            "external_list_projects",
            "external_list_objects",
            "external_get_details",
            "external_create_report",
            "external_create_processing",
            "edt_external_smoke",
            "extension_create_project",
            "extension_adopt_object",
            "extension_set_property_state",
            "edt_extension_smoke",
            "edt_field_type_candidates",
            "inspect_platform_reference",
            "bsl_symbol_at_position",
            "bsl_type_at_position",
            "bsl_scope_members",
            "edt_validate_request",
            "create_metadata",
            "create_form",
            "apply_form_recipe",
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
                PermissionRule.allow("get_diagnostics").forAllResources(),
                PermissionRule.allow("edt_content_assist").forAllResources(),
                PermissionRule.allow("edt_find_references").forAllResources(),
                PermissionRule.allow("edt_metadata_details").forAllResources(),
                PermissionRule.allow("scan_metadata_index").forAllResources(),
                PermissionRule.allow("dcs_get_summary").forAllResources(),
                PermissionRule.allow("dcs_list_nodes").forAllResources(),
                PermissionRule.allow("extension_list_projects").forAllResources(),
                PermissionRule.allow("extension_list_objects").forAllResources(),
                PermissionRule.allow("external_list_projects").forAllResources(),
                PermissionRule.allow("external_list_objects").forAllResources(),
                PermissionRule.allow("external_get_details").forAllResources(),
                PermissionRule.allow("edt_extension_smoke").forAllResources(),
                PermissionRule.allow("edt_external_smoke").forAllResources(),
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
                PermissionRule.ask("apply_form_recipe")
                        .withDescription("Применение рецепта управляемой формы EDT")
                        .forAllResources(),
                PermissionRule.ask("extension_create_project")
                        .withDescription("Создание проекта расширения EDT")
                        .forAllResources(),
                PermissionRule.ask("external_create_report")
                        .withDescription("Создание проекта внешнего отчета EDT")
                        .forAllResources(),
                PermissionRule.ask("external_create_processing")
                        .withDescription("Создание проекта внешней обработки EDT")
                        .forAllResources(),
                PermissionRule.ask("extension_adopt_object")
                        .withDescription("Добавление объекта основной конфигурации в расширение EDT")
                        .forAllResources(),
                PermissionRule.ask("extension_set_property_state")
                        .withDescription("Установка состояния свойства объекта расширения EDT")
                        .forAllResources(),
                PermissionRule.ask("dcs_create_main_schema")
                        .withDescription("Создание основной схемы СКД")
                        .forAllResources(),
                PermissionRule.ask("dcs_upsert_query_dataset")
                        .withDescription("Создание/изменение набора данных запроса СКД")
                        .forAllResources(),
                PermissionRule.ask("dcs_upsert_parameter")
                        .withDescription("Создание/изменение параметра СКД")
                        .forAllResources(),
                PermissionRule.ask("dcs_upsert_calculated_field")
                        .withDescription("Создание/изменение вычисляемого поля СКД")
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
        String defaultPrompt = AgentPromptTemplates.buildBuildPrompt();
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
