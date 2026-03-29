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
 * Read-mostly profile for cross-domain orchestration through sub-agents.
 */
public final class OrchestratorProfile implements AgentProfile {

    public static final String ID = "orchestrator"; //$NON-NLS-1$

    private static final Set<String> ALLOWED_TOOLS = new HashSet<>(Arrays.asList(
            "read_file", //$NON-NLS-1$
            "list_files", //$NON-NLS-1$
            "glob", //$NON-NLS-1$
            "grep", //$NON-NLS-1$
            "delegate_to_agent", //$NON-NLS-1$
            "task", //$NON-NLS-1$
            "skill")); //$NON-NLS-1$

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getName() {
        return "Оркестрация"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Координирует multi-domain задачи: читает контекст, выбирает профиль и делегирует работу специализированным подагентам."; //$NON-NLS-1$
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
                PermissionRule.allow("delegate_to_agent").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("task").forAllResources(), //$NON-NLS-1$
                PermissionRule.allow("skill").forAllResources()); //$NON-NLS-1$
    }

    @Override
    public String getSystemPromptAddition() {
        String defaultPrompt = AgentPromptTemplates.buildOrchestratorPrompt();
        return PromptProviderRegistry.getInstance().getSystemPromptAddition(getId(), defaultPrompt);
    }

    @Override
    public int getMaxSteps() {
        return 30;
    }

    @Override
    public long getTimeoutMs() {
        return 10 * 60 * 1000L;
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
