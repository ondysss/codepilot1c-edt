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
 * Профиль агента для работы со схемами компоновки данных (СКД/DCS).
 *
 * <p>Фокус на создании, изменении и анализе DCS-схем.
 * Включает файловые операции и минимальный набор metadata tools для контекста. ~14 tools.</p>
 */
public class DCSBuildProfile implements AgentProfile {

    public static final String ID = "dcs";

    private static final Set<String> ALLOWED_TOOLS = new HashSet<>(Arrays.asList(
            // File operations
            "read_file",
            "edit_file",
            "write_file",
            "glob",
            "grep",
            "list_files",
            // DCS tools
            "dcs_get_summary",
            "dcs_list_nodes",
            "dcs_create_main_schema",
            "dcs_upsert_query_dataset",
            "dcs_upsert_parameter",
            "dcs_upsert_calculated_field",
            // Context
            "scan_metadata_index",
            "edt_metadata_details",
            "get_diagnostics",
            // Meta
            "analyze_tool_error",
            "skill",
            "task"
    ));

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "Схемы компоновки (DCS)";
    }

    @Override
    public String getDescription() {
        return "Работа со схемами компоновки данных: создание, наборы данных, " +
               "параметры, вычисляемые поля.";
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
                PermissionRule.allow("dcs_get_summary").forAllResources(),
                PermissionRule.allow("dcs_list_nodes").forAllResources(),
                PermissionRule.allow("scan_metadata_index").forAllResources(),
                PermissionRule.allow("edt_metadata_details").forAllResources(),
                PermissionRule.allow("get_diagnostics").forAllResources(),
                PermissionRule.allow("analyze_tool_error").forAllResources(),
                PermissionRule.allow("skill").forAllResources(),
                PermissionRule.allow("task").forAllResources(),
                // Write tools - ask
                PermissionRule.ask("edit_file")
                        .withDescription("Редактирование файлов")
                        .forAllResources(),
                PermissionRule.ask("write_file")
                        .withDescription("Создание файлов")
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
        return 30;
    }

    @Override
    public long getTimeoutMs() {
        return 6 * 60 * 1000; // 6 minutes
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
