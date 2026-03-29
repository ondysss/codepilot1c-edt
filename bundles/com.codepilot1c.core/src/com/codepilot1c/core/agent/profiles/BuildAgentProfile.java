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
            "workspace_import_project",
            "git_inspect",
            "git_mutate",
            "git_clone_and_import_project",
            "import_project_from_infobase",
            "get_diagnostics",
            "edt_content_assist",
            "edt_find_references",
            "edt_metadata_details",
            "scan_metadata_index",
            "dcs_manage",
            "extension_manage",
            "external_manage",
            "edt_external_smoke",
            "edt_extension_smoke",
            "edt_field_type_candidates",
            "inspect_platform_reference",
            "bsl_symbol_at_position",
            "bsl_type_at_position",
            "bsl_scope_members",
            "bsl_list_methods",
            "bsl_get_method_body",
            "bsl_analyze_method",
            "bsl_module_context",
            "bsl_module_exports",
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
            "author_yaxunit_tests",
            "edt_diagnostics",
            "qa_inspect",
            "qa_generate",
            "qa_run",
            "qa_prepare_form_context",
            "qa_plan_scenario",
            "qa_validate_feature",
            "skill",
            "task"
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
                PermissionRule.allow("git_inspect").forAllResources(),
                PermissionRule.allow("get_diagnostics").forAllResources(),
                PermissionRule.allow("edt_content_assist").forAllResources(),
                PermissionRule.allow("edt_find_references").forAllResources(),
                PermissionRule.allow("edt_metadata_details").forAllResources(),
                PermissionRule.allow("scan_metadata_index").forAllResources(),
                PermissionRule.allow("edt_extension_smoke").forAllResources(),
                PermissionRule.allow("edt_external_smoke").forAllResources(),
                PermissionRule.allow("edt_field_type_candidates").forAllResources(),
                PermissionRule.allow("inspect_platform_reference").forAllResources(),
                PermissionRule.allow("bsl_symbol_at_position").forAllResources(),
                PermissionRule.allow("bsl_type_at_position").forAllResources(),
                PermissionRule.allow("bsl_scope_members").forAllResources(),
                PermissionRule.allow("bsl_list_methods").forAllResources(),
                PermissionRule.allow("bsl_get_method_body").forAllResources(),
                PermissionRule.allow("bsl_analyze_method").forAllResources(),
                PermissionRule.allow("bsl_module_context").forAllResources(),
                PermissionRule.allow("bsl_module_exports").forAllResources(),
                PermissionRule.allow("edt_validate_request").forAllResources(),
                PermissionRule.allow("inspect_form_layout").forAllResources(),
                PermissionRule.allow("qa_inspect").forAllResources(),
                PermissionRule.ask("qa_generate")
                        .withDescription("QA генерация: init/migrate config, compile feature")
                        .forAllResources(),
                PermissionRule.allow("qa_run").forAllResources(),
                PermissionRule.allow("qa_plan_scenario").forAllResources(),
                PermissionRule.allow("qa_validate_feature").forAllResources(),
                PermissionRule.allow("skill").forAllResources(),
                PermissionRule.allow("task").forAllResources(),

                // Write tools - ask
                PermissionRule.ask("edit_file")
                        .withDescription("Редактирование файлов")
                        .forAllResources(),
	                PermissionRule.ask("write_file")
	                        .withDescription("Создание файлов")
	                        .forAllResources(),
                PermissionRule.ask("workspace_import_project")
                        .withDescription("Импорт проекта в workspace")
                        .forAllResources(),
                PermissionRule.ask("git_mutate")
                        .withDescription("Мутирующие git-операции")
                        .forAllResources(),
                PermissionRule.ask("git_clone_and_import_project")
                        .withDescription("Клонирование git-репозитория и импорт проекта")
                        .forAllResources(),
                PermissionRule.ask("import_project_from_infobase")
                        .withDescription("Импорт EDT проекта из связанной инфобазы через standalone server")
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
                PermissionRule.ask("extension_manage")
                        .withDescription("Управление расширениями конфигурации EDT")
                        .forAllResources(),
                PermissionRule.ask("external_manage")
                        .withDescription("Управление внешними обработками и отчётами EDT")
                        .forAllResources(),
                PermissionRule.ask("dcs_manage")
                        .withDescription("Управление схемами компоновки данных")
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
                PermissionRule.ask("author_yaxunit_tests")
                        .withDescription("Генерация/обновление автотестов YAxUnit")
                        .forAllResources(),
                PermissionRule.ask("qa_prepare_form_context")
                        .withDescription("Подготовка QA контекста формы с автосозданием default формы при отсутствии")
                        .forAllResources(),
                PermissionRule.ask("edt_diagnostics")
                        .withDescription("EDT диагностика: smoke, trace, анализ ошибок, обновление ИБ, запуск")
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
