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
            "git_inspect",
            "get_diagnostics",
            "get_bookmarks",
            "get_tasks",
            "edt_content_assist",
            "edt_find_references",
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
            "inspect_form_layout",
            "bsl_symbol_at_position",
            "bsl_type_at_position",
            "bsl_scope_members",
            "bsl_list_methods",
            "bsl_get_method_body",
            "bsl_analyze_method",
            "bsl_module_context",
            "bsl_module_exports",
            "inspect_platform_reference",
            "skill",
            "task"
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
                PermissionRule.allow("git_inspect").forAllResources(),
                PermissionRule.allow("get_diagnostics").forAllResources(),
                PermissionRule.allow("get_bookmarks").forAllResources(),
                PermissionRule.allow("get_tasks").forAllResources(),
                PermissionRule.allow("edt_content_assist").forAllResources(),
                PermissionRule.allow("edt_find_references").forAllResources(),
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
                PermissionRule.allow("bsl_symbol_at_position").forAllResources(),
                PermissionRule.allow("bsl_type_at_position").forAllResources(),
                PermissionRule.allow("bsl_scope_members").forAllResources(),
                PermissionRule.allow("bsl_list_methods").forAllResources(),
                PermissionRule.allow("bsl_get_method_body").forAllResources(),
                PermissionRule.allow("bsl_analyze_method").forAllResources(),
                PermissionRule.allow("bsl_module_context").forAllResources(),
                PermissionRule.allow("bsl_module_exports").forAllResources(),
                PermissionRule.allow("inspect_platform_reference").forAllResources(),
                PermissionRule.allow("skill").forAllResources(),
                PermissionRule.allow("task").forAllResources()
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
