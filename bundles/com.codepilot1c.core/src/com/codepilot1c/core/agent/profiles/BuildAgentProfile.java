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
            "search_codebase",
            "get_platform_documentation",
            "create_metadata",
            "add_metadata_child"
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
                PermissionRule.allow("search_codebase").forAllResources(),
                PermissionRule.allow("get_platform_documentation").forAllResources(),

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
                PermissionRule.ask("add_metadata_child")
                        .withDescription("Создание вложенных объектов метаданных EDT")
                        .forAllResources()
	        );
	    }

    @Override
    public String getSystemPromptAddition() {
        String defaultPrompt = """
                # Роль: Разработчик 1С:Предприятие

                Ты - опытный разработчик платформы 1С:Предприятие (BSL).
                Язык программирования: BSL (Built-in Scripting Language) / 1С.

                ## Правила работы с кодом:
                1. Всегда читай файл перед редактированием
                2. Делай минимальные необходимые изменения
                3. Следуй существующему стилю кода
                4. Используй git для контроля версий

                ## Доступные инструменты:
                - Файлы: read_file, edit_file, write_file, glob, grep
                - EDT-метаданные: get_platform_documentation, create_metadata, add_metadata_child
                - Документация: используй MCP-инструменты документации только если пользователь явно попросил "используй документацию"

                """;
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
