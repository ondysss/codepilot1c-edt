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
 * Профиль агента для диагностики и восстановления.
 *
 * <p>Минимальный набор инструментов для анализа ошибок, проверки состояния
 * и быстрого восстановления. ~12 tools.</p>
 */
public class RecoveryProfile implements AgentProfile {

    public static final String ID = "recovery";

    private static final Set<String> ALLOWED_TOOLS = new HashSet<>(Arrays.asList(
            // File operations (read-only + targeted write)
            "read_file",
            "edit_file",
            "glob",
            "grep",
            "list_files",
            // Diagnostics (edt_diagnostics dispatches: metadata_smoke, trace_export, analyze_error)
            "get_diagnostics",
            "get_bookmarks",
            "get_tasks",
            "edt_diagnostics",
            "update_infobase_status",
            "edt_validate_request",
            "inspect_platform_reference",
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
        return "Диагностика";
    }

    @Override
    public String getDescription() {
        return "Диагностика и восстановление: анализ ошибок, проверка smoke, " +
               "экспорт трассировки. Минимальный набор инструментов.";
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
                PermissionRule.allow("get_bookmarks").forAllResources(),
                PermissionRule.allow("get_tasks").forAllResources(),
                PermissionRule.allow("edt_diagnostics").forAllResources(),
                PermissionRule.allow("update_infobase_status").forAllResources(),
                PermissionRule.allow("edt_validate_request").forAllResources(),
                PermissionRule.allow("inspect_platform_reference").forAllResources(),
                PermissionRule.allow("skill").forAllResources(),
                PermissionRule.allow("task").forAllResources(),
                // Targeted write - ask
                PermissionRule.ask("edit_file")
                        .withDescription("Редактирование файлов для восстановления")
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
        return 20;
    }

    @Override
    public long getTimeoutMs() {
        return 5 * 60 * 1000; // 5 minutes
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
