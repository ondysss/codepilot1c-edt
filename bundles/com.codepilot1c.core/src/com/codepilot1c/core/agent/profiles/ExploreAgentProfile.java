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
 * Профиль агента для быстрого исследования кодовой базы.
 *
 * <p>Возможности:</p>
 * <ul>
 *   <li>Быстрый поиск файлов (glob)</li>
 *   <li>Поиск по содержимому (grep)</li>
 *   <li>Чтение файлов</li>
 *   <li>Семантический поиск (search_codebase)</li>
 * </ul>
 *
 * <p>Особенности:</p>
 * <ul>
 *   <li>Оптимизирован для скорости</li>
 *   <li>Минимум шагов (15)</li>
 *   <li>Короткий таймаут (2 мин)</li>
 *   <li>Фокус на навигации</li>
 * </ul>
 *
 * <p>Используется для:</p>
 * <ul>
 *   <li>Поиска определений</li>
 *   <li>Навигации по коду</li>
 *   <li>Ответов на вопросы о структуре</li>
 *   <li>Быстрого анализа</li>
 * </ul>
 */
public class ExploreAgentProfile implements AgentProfile {

    public static final String ID = "explore";

    private static final Set<String> ALLOWED_TOOLS = new HashSet<>(Arrays.asList(
            "read_file",
            "glob",
            "grep",
            "list_files",
            "search_codebase",
            "get_diagnostics",
            "edt_content_assist",
            "edt_find_references",
            "edt_metadata_details",
            "scan_metadata_index",
            "inspect_form_layout",
            "bsl_symbol_at_position",
            "bsl_type_at_position",
            "bsl_scope_members",
            "inspect_platform_reference"
    ));

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "Исследование";
    }

    @Override
    public String getDescription() {
        return "Быстрый поиск и навигация по кодовой базе. Оптимизирован для " +
               "скорости ответов на вопросы о структуре и содержимом кода.";
    }

    @Override
    public Set<String> getAllowedTools() {
        return ALLOWED_TOOLS;
    }

    @Override
    public List<PermissionRule> getDefaultPermissions() {
        return Arrays.asList(
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
                PermissionRule.allow("inspect_form_layout").forAllResources(),
                PermissionRule.allow("bsl_symbol_at_position").forAllResources(),
                PermissionRule.allow("bsl_type_at_position").forAllResources(),
                PermissionRule.allow("bsl_scope_members").forAllResources(),
                PermissionRule.allow("inspect_platform_reference").forAllResources()
        );
    }

    @Override
    public String getSystemPromptAddition() {
        String defaultPrompt = AgentPromptTemplates.buildExplorePrompt();
        return PromptProviderRegistry.getInstance().getSystemPromptAddition(getId(), defaultPrompt);
    }

    @Override
    public int getMaxSteps() {
        return 15;
    }

    @Override
    public long getTimeoutMs() {
        return 2 * 60 * 1000; // 2 minutes
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public boolean canExecuteShell() {
        return false;
    }
}
