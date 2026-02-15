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

import com.codepilot1c.core.agent.prompts.PromptProviderRegistry;
import com.codepilot1c.core.permissions.PermissionRule;

/**
 * Профиль агента для планирования и анализа (только чтение).
 *
 * <p>Возможности:</p>
 * <ul>
 *   <li>Чтение файлов</li>
 *   <li>Поиск по кодовой базе</li>
 *   <li>Анализ архитектуры</li>
 *   <li>Создание планов реализации</li>
 * </ul>
 *
 * <p>Ограничения:</p>
 * <ul>
 *   <li>Нет редактирования файлов</li>
 *   <li>Нет выполнения команд</li>
 *   <li>Максимум 25 шагов</li>
 * </ul>
 *
 * <p>Используется для:</p>
 * <ul>
 *   <li>Анализа кодовой базы</li>
 *   <li>Планирования задач</li>
 *   <li>Ревью архитектуры</li>
 *   <li>Оценки сложности</li>
 * </ul>
 */
public class PlanAgentProfile implements AgentProfile {

    public static final String ID = "plan";

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
        return "Планирование";
    }

    @Override
    public String getDescription() {
        return "Только чтение для анализа и планирования. Не может изменять файлы " +
               "или выполнять команды. Используйте для анализа кода и создания планов.";
    }

    @Override
    public Set<String> getAllowedTools() {
        return ALLOWED_TOOLS;
    }

    @Override
    public List<PermissionRule> getDefaultPermissions() {
        return Arrays.asList(
                // All read tools - allow
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
        String defaultPrompt = """
                Ты - архитектор и аналитик, помогающий с планированием задач для 1С:Предприятие.

                Твоя роль:
                1. Анализировать существующий код
                2. Понимать архитектуру проекта
                3. Создавать детальные планы реализации
                4. Выявлять потенциальные проблемы
                5. Оценивать сложность задач

                ВАЖНО: Ты можешь только ЧИТАТЬ код. Не предлагай изменения напрямую,
                вместо этого создавай план с конкретными шагами.

                Формат плана:
                ## Задача
                [Краткое описание]

                ## Анализ
                [Что нужно изменить и почему]

                ## План реализации
                1. [Конкретный шаг]
                2. [Конкретный шаг]
                ...

                ## Риски
                [Потенциальные проблемы]

                Для задач по управляемым формам:
                1. Сначала анализируй структуру формы через inspect_form_layout.
                2. Учитывай разделение логики: UI в модуле формы, предметная логика в модуле объекта.
                3. Проверяй клиент-серверный контекст вызовов формы и объекта.
                4. Включай в план повторную проверку get_diagnostics (файл + проект).

                Доступные инструменты: read_file, glob, grep, list_files, search_codebase,
                get_diagnostics, edt_content_assist, edt_find_references, edt_metadata_details, scan_metadata_index, inspect_form_layout,
                bsl_symbol_at_position, bsl_type_at_position, bsl_scope_members, inspect_platform_reference.
                """;
        return PromptProviderRegistry.getInstance().getSystemPromptAddition(getId(), defaultPrompt);
    }

    @Override
    public int getMaxSteps() {
        return 25;
    }

    @Override
    public long getTimeoutMs() {
        return 5 * 60 * 1000; // 5 minutes
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
