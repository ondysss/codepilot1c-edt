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
 * Профиль агента для работы с расширениями и внешними обработками/отчётами.
 *
 * <p>Фокус на создании расширений, adopt объектов, внешних обработках.
 * ~16 tools.</p>
 */
public class ExtensionBuildProfile implements AgentProfile {

    public static final String ID = "extension";

    private static final Set<String> ALLOWED_TOOLS = new HashSet<>(Arrays.asList(
            // File operations
            "read_file",
            "edit_file",
            "write_file",
            "glob",
            "grep",
            "list_files",
            // Extension composite tool + smoke
            "extension_manage",
            "edt_extension_smoke",
            // External composite tool + smoke
            "external_manage",
            "edt_external_smoke",
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
        return "Расширения и обработки";
    }

    @Override
    public String getDescription() {
        return "Работа с расширениями конфигурации, внешними обработками и отчётами: " +
               "создание проектов, adopt объектов, управление свойствами.";
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
                PermissionRule.allow("extension_manage").forAllResources(),
                PermissionRule.allow("edt_extension_smoke").forAllResources(),
                PermissionRule.allow("external_manage").forAllResources(),
                PermissionRule.allow("edt_external_smoke").forAllResources(),
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
                PermissionRule.ask("extension_manage")
                        .withDescription("Управление расширениями конфигурации")
                        .forAllResources(),
                PermissionRule.ask("external_manage")
                        .withDescription("Управление внешними обработками и отчётами")
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
        return 35;
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
