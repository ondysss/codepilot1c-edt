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
 * Agent profile for initializing and refreshing the project Code.md context file.
 */
public class InitAgentProfile implements AgentProfile {

    public static final String ID = "init"; //$NON-NLS-1$

    private static final Set<String> ALLOWED_TOOLS = new HashSet<>(Arrays.asList(
            "read_file", //$NON-NLS-1$
            "list_files", //$NON-NLS-1$
            "glob", //$NON-NLS-1$
            "grep", //$NON-NLS-1$
            "write_file", //$NON-NLS-1$
            "scan_metadata_index", //$NON-NLS-1$
            "discover_tools" //$NON-NLS-1$
    ));

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "Инициализация Code.md"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Анализирует проект 1С и создаёт или обновляет Code.md с контекстом для AI-агента."; //$NON-NLS-1$
    }

    @Override
    public Set<String> getAllowedTools() {
        return ALLOWED_TOOLS;
    }

    @Override
    public List<PermissionRule> getDefaultPermissions() {
        return Arrays.asList(
                PermissionRule.allow("read_file").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("list_files").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("glob").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("grep").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("scan_metadata_index").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("discover_tools").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("write_file") //$NON-NLS-1$
                        .withDescription("Создание или обновление Code.md") //$NON-NLS-1$
                        .forAllResources()
        );
    }

    @Override
    public String getSystemPromptAddition() {
        String defaultPrompt = AgentPromptTemplates.buildInitPrompt();
        return PromptProviderRegistry.getInstance().getSystemPromptAddition(getId(), defaultPrompt);
    }

    @Override
    public int getMaxSteps() {
        return 40;
    }

    @Override
    public long getTimeoutMs() {
        return 8 * 60 * 1000L;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public boolean canExecuteShell() {
        return false;
    }

    @Override
    public DynamicToolCapability getDynamicToolGrant() {
        return DynamicToolCapability.READ_ONLY;
    }
}
