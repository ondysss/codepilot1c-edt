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
 * Профиль агента для QA-сценариев и автотестирования.
 *
 * <p>Фокус на планировании, компиляции и валидации QA feature-файлов,
 * запуске автотестов YAxUnit. ~18 tools.</p>
 */
public class QABuildProfile implements AgentProfile {

    public static final String ID = "qa";

    private static final Set<String> ALLOWED_TOOLS = new HashSet<>(Arrays.asList(
            // File operations
            "read_file",
            "edit_file",
            "write_file",
            "glob",
            "grep",
            "list_files",
            // QA composite tools
            "qa_inspect",
            "qa_generate",
            "qa_run",
            "qa_prepare_form_context",
            "qa_plan_scenario",
            "qa_validate_feature",
            // YAxUnit
            "author_yaxunit_tests",
            // Smoke & validation (edt_diagnostics dispatches: metadata_smoke, analyze_error)
            "edt_diagnostics",
            "get_diagnostics",
            "start_profiling",
            "get_profiling_results",
            "skill",
            "task"
    ));

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "QA и тестирование";
    }

    @Override
    public String getDescription() {
        return "Планирование, генерация и запуск QA-сценариев и автотестов YAxUnit. " +
               "Включает полный набор qa_* инструментов.";
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
                PermissionRule.allow("qa_inspect").forAllResources(),
                PermissionRule.allow("qa_validate_feature").forAllResources(),
                PermissionRule.allow("edt_diagnostics").forAllResources(),
                PermissionRule.allow("get_diagnostics").forAllResources(),
                PermissionRule.allow("get_profiling_results").forAllResources(),
                PermissionRule.allow("skill").forAllResources(),
                PermissionRule.allow("task").forAllResources(),
                // Write tools - ask
                PermissionRule.ask("edit_file")
                        .withDescription("Редактирование файлов")
                        .forAllResources(),
                PermissionRule.ask("write_file")
                        .withDescription("Создание файлов")
                        .forAllResources(),
                PermissionRule.ask("qa_generate")
                        .withDescription("QA генерация: init/migrate config, compile feature")
                        .forAllResources(),
                PermissionRule.ask("qa_run")
                        .withDescription("Запуск QA-сценариев")
                        .forAllResources(),
                PermissionRule.ask("qa_prepare_form_context")
                        .withDescription("Подготовка QA контекста формы")
                        .forAllResources(),
                PermissionRule.ask("qa_plan_scenario")
                        .withDescription("Планирование QA-сценария")
                        .forAllResources(),
                PermissionRule.ask("author_yaxunit_tests")
                        .withDescription("Генерация/обновление автотестов YAxUnit")
                        .forAllResources(),
                PermissionRule.ask("start_profiling")
                        .withDescription("Запуск профилирования")
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
