/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.agent.profiles.ProfileRouter;

/**
 * Thin orchestration wrapper over {@link TaskTool}.
 */
@ToolMeta(name = "delegate_to_agent", category = "general", tags = {"workspace"})
public final class DelegateToAgentTool extends AbstractTool {

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "agentType": {
                  "type": "string",
                  "enum": ["auto", "code", "metadata", "qa", "dcs", "extension", "recovery", "plan", "explore", "orchestrator"],
                  "description": "Target agent type or auto routing."
                },
                "task": {
                  "type": "string",
                  "description": "Concrete task for the delegated sub-agent."
                },
                "context": {
                  "type": "string",
                  "description": "Optional extra context, constraints, file paths, or project details."
                },
                "description": {
                  "type": "string",
                  "description": "Short label for the delegated sub-task."
                }
              },
              "required": ["task"]
            }
            """; //$NON-NLS-1$

    private final TaskTool taskTool;
    private final ProfileRouter profileRouter;

    public DelegateToAgentTool(ToolRegistry toolRegistry) {
        this(toolRegistry, new ProfileRouter());
    }

    DelegateToAgentTool(ToolRegistry toolRegistry, ProfileRouter profileRouter) {
        this(new TaskTool(toolRegistry, profileRouter), profileRouter);
    }

    DelegateToAgentTool(TaskTool taskTool, ProfileRouter profileRouter) {
        this.taskTool = taskTool;
        this.profileRouter = profileRouter;
    }

    @Override
    public String getDescription() {
        return "Делегирует подзадачу профильному подагенту по явному домену или через auto routing. Используй для крупных или изолированных задач."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        String agentType = profileRouter.normalizeProfileId(params.optString("agentType", "auto")); //$NON-NLS-1$ //$NON-NLS-2$
        if (!"auto".equals(agentType) && !profileRouter.supportedDelegateTargets().contains(agentType)) { //$NON-NLS-1$
            return CompletableFuture.completedFuture(ToolResult.failure(
                    "Unsupported agentType: " + agentType + ". Supported: " //$NON-NLS-1$ //$NON-NLS-2$
                            + String.join(", ", profileRouter.supportedDelegateTargets()))); //$NON-NLS-1$
        }

        String prompt = params.requireString("task"); //$NON-NLS-1$
        String context = params.optString("context", "").trim(); //$NON-NLS-1$ //$NON-NLS-2$
        String description = params.optString("description", prompt); //$NON-NLS-1$ //$NON-NLS-2$

        String delegatedPrompt = context.isEmpty()
                ? prompt
                : prompt + "\n\nДополнительный контекст:\n" + context; //$NON-NLS-1$

        Map<String, Object> delegatedParams = new LinkedHashMap<>();
        delegatedParams.put("prompt", delegatedPrompt); //$NON-NLS-1$
        delegatedParams.put("profile", agentType); //$NON-NLS-1$
        delegatedParams.put("description", description); //$NON-NLS-1$
        return taskTool.execute(delegatedParams);
    }
}
