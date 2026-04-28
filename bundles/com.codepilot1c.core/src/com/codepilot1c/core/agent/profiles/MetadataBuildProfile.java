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
import com.codepilot1c.core.permissions.PermissionRule;

/**
 * Профиль агента для работы с метаданными, формами и DCS.
 *
 * <p>Фокус на CRUD метаданных, управляемых форм, DCS-схем.
 * Включает минимальный набор файловых и BSL-инструментов для контекста.</p>
 *
 * <p>~25 tools — в пределах оптимального диапазона для LLM accuracy.</p>
 */
public class MetadataBuildProfile implements AgentProfile {

    public static final String ID = "metadata";

    private static final Set<String> ALLOWED_TOOLS = new HashSet<>(Arrays.asList(
            // File operations (read + write for module artifacts)
            "read_file",
            "edit_file",
            "write_file",
            "glob",
            "grep",
            "list_files",
            // Metadata CRUD
            "create_metadata",
            "update_metadata",
            "delete_metadata",
            "add_metadata_child",
            "edt_metadata_details",
            "scan_metadata_index",
            "edt_get_configuration_properties",
            "edt_get_problem_summary",
            "edt_get_tags",
            "edt_get_objects_by_tags",
            "edt_list_modules",
            "edt_get_module_structure",
            "edt_search_in_code",
            "edt_get_method_call_hierarchy",
            "edt_go_to_definition",
            "edt_get_symbol_info",
            "edt_diagnostics",
            // Forms
            "create_form",
            "apply_form_recipe",
            "inspect_form_layout",
            "mutate_form_model",
            "edt_field_type_candidates",
            // DCS
            "dcs_manage",
            // Module artifact (for creating modules on new objects)
            "ensure_module_artifact",
            // Validation
            "edt_validate_request",
            "get_diagnostics",
            "get_bookmarks",
            "get_tasks",
            // Meta
            "skill",
            "task"
    ));

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "Метаданные";
    }

    @Override
    public String getDescription() {
        return "Работа с метаданными, формами и DCS: создание, обновление, удаление " +
               "объектов конфигурации, управляемых форм, схем компоновки данных.";
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
                PermissionRule.allow("edt_metadata_details").forAllResources(),
                PermissionRule.allow("scan_metadata_index").forAllResources(),
                PermissionRule.allow("edt_get_configuration_properties").forAllResources(),
                PermissionRule.allow("edt_get_problem_summary").forAllResources(),
                PermissionRule.allow("edt_get_tags").forAllResources(),
                PermissionRule.allow("edt_get_objects_by_tags").forAllResources(),
                PermissionRule.allow("edt_list_modules").forAllResources(),
                PermissionRule.allow("edt_get_module_structure").forAllResources(),
                PermissionRule.allow("edt_search_in_code").forAllResources(),
                PermissionRule.allow("edt_get_method_call_hierarchy").forAllResources(),
                PermissionRule.allow("edt_go_to_definition").forAllResources(),
                PermissionRule.allow("edt_get_symbol_info").forAllResources(),
                PermissionRule.allow("inspect_form_layout").forAllResources(),
                PermissionRule.allow("edt_field_type_candidates").forAllResources(),
                PermissionRule.allow("edt_validate_request").forAllResources(),
                PermissionRule.allow("get_diagnostics").forAllResources(),
                PermissionRule.allow("get_bookmarks").forAllResources(),
                PermissionRule.allow("get_tasks").forAllResources(),
                PermissionRule.allow("skill").forAllResources(),
                PermissionRule.allow("task").forAllResources(),
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
                PermissionRule.ask("update_metadata")
                        .withDescription("Обновление свойств объектов метаданных EDT")
                        .forAllResources(),
                PermissionRule.ask("delete_metadata")
                        .withDescription("Удаление объектов метаданных EDT")
                        .forAllResources(),
                PermissionRule.ask("add_metadata_child")
                        .withDescription("Создание вложенных объектов метаданных EDT")
                        .forAllResources(),
                PermissionRule.ask("create_form")
                        .withDescription("Создание управляемых форм EDT")
                        .forAllResources(),
                PermissionRule.ask("apply_form_recipe")
                        .withDescription("Применение рецепта управляемой формы EDT")
                        .forAllResources(),
                PermissionRule.ask("mutate_form_model")
                        .withDescription("Обновление модели управляемых форм EDT")
                        .forAllResources(),
                PermissionRule.ask("dcs_manage")
                        .withDescription("Управление схемами компоновки данных")
                        .forAllResources(),
                PermissionRule.ask("edt_diagnostics")
                        .withDescription("EDT диагностика: smoke, trace, анализ ошибок, обновление ИБ, запуск")
                        .forAllResources(),
                PermissionRule.ask("ensure_module_artifact")
                        .withDescription("Создание/подготовка файлов модулей EDT")
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
        return 40;
    }

    @Override
    public long getTimeoutMs() {
        return 8 * 60 * 1000; // 8 minutes
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
