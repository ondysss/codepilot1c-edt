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
 * Профиль агента для работы с BSL-кодом.
 *
 * <p>Фокус на чтении, редактировании и анализе модулей BSL.
 * Не включает инструменты метаданных, DCS, расширений и QA.</p>
 *
 * <p>~20 tools — в пределах оптимального диапазона для LLM accuracy.</p>
 */
public class CodeBuildProfile implements AgentProfile {

    public static final String ID = "code";

    private static final Set<String> ALLOWED_TOOLS = new HashSet<>(Arrays.asList(
            // File operations
            "read_file",
            "edit_file",
            "write_file",
            "glob",
            "grep",
            "list_files",
            // Git
            "git_inspect",
            "git_mutate",
            // BSL semantic
            "bsl_symbol_at_position",
            "bsl_type_at_position",
            "bsl_scope_members",
            "bsl_list_methods",
            "bsl_get_method_body",
            "bsl_analyze_method",
            "bsl_module_context",
            "bsl_module_exports",
            // EDT code tools
            "edt_content_assist",
            "edt_find_references",
            "edt_list_modules",
            "edt_get_module_structure",
            "edt_search_in_code",
            "edt_get_method_call_hierarchy",
            "edt_go_to_definition",
            "edt_get_symbol_info",
            "get_diagnostics",
            "get_bookmarks",
            "get_tasks",
            "inspect_platform_reference",
            "ensure_module_artifact",
            // Meta
            "edt_diagnostics",
            "update_infobase_status",
            "skill",
            "task"
    ));

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "Код BSL";
    }

    @Override
    public String getDescription() {
        return "Работа с BSL-кодом: чтение, редактирование модулей, навигация по символам. " +
               "Не включает метаданные, формы, DCS и QA.";
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
                PermissionRule.allow("git_inspect").forAllResources(),
                PermissionRule.allow("bsl_symbol_at_position").forAllResources(),
                PermissionRule.allow("bsl_type_at_position").forAllResources(),
                PermissionRule.allow("bsl_scope_members").forAllResources(),
                PermissionRule.allow("bsl_list_methods").forAllResources(),
                PermissionRule.allow("bsl_get_method_body").forAllResources(),
                PermissionRule.allow("bsl_analyze_method").forAllResources(),
                PermissionRule.allow("bsl_module_context").forAllResources(),
                PermissionRule.allow("bsl_module_exports").forAllResources(),
                PermissionRule.allow("edt_content_assist").forAllResources(),
                PermissionRule.allow("edt_find_references").forAllResources(),
                PermissionRule.allow("edt_list_modules").forAllResources(),
                PermissionRule.allow("edt_get_module_structure").forAllResources(),
                PermissionRule.allow("edt_search_in_code").forAllResources(),
                PermissionRule.allow("edt_get_method_call_hierarchy").forAllResources(),
                PermissionRule.allow("edt_go_to_definition").forAllResources(),
                PermissionRule.allow("edt_get_symbol_info").forAllResources(),
                PermissionRule.allow("get_diagnostics").forAllResources(),
                PermissionRule.allow("get_bookmarks").forAllResources(),
                PermissionRule.allow("get_tasks").forAllResources(),
                PermissionRule.allow("inspect_platform_reference").forAllResources(),
                PermissionRule.allow("edt_diagnostics").forAllResources(),
                PermissionRule.allow("update_infobase_status").forAllResources(),
                PermissionRule.allow("skill").forAllResources(),
                PermissionRule.allow("task").forAllResources(),
                // Write tools - ask
                PermissionRule.ask("edit_file")
                        .withDescription("Редактирование файлов")
                        .forAllResources(),
                PermissionRule.ask("write_file")
                        .withDescription("Создание файлов")
                        .forAllResources(),
                PermissionRule.ask("git_mutate")
                        .withDescription("Мутирующие git-операции")
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
