/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.langgraph;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.codepilot1c.core.agent.AgentConfig;
import com.codepilot1c.core.agent.graph.ToolGraph;
import com.codepilot1c.core.agent.graph.ToolGraphRegistry;
import com.codepilot1c.core.agent.graph.ToolNode;

final class LangGraphAgentConfigFactory {

    private LangGraphAgentConfigFactory() {
    }

    static AgentConfig buildDomainConfig(AgentConfig baseConfig, LangGraphAgentDomain domain) {
        AgentConfig.Builder builder = AgentConfig.builder().from(baseConfig);

        String mergedPrompt = mergePrompt(baseConfig.getSystemPromptAddition(), domain.getPromptAddition());
        builder.systemPromptAddition(mergedPrompt);

        Set<String> domainTools = collectAllowedTools(domain);
        if (!domainTools.isEmpty()) {
            Set<String> enabled = new HashSet<>(domainTools);
            if (!baseConfig.getEnabledTools().isEmpty()) {
                enabled.retainAll(baseConfig.getEnabledTools());
            }
            builder.enabledTools(enabled);
        }

        return builder.build();
    }

    private static String mergePrompt(String basePrompt, String addition) {
        if (addition == null || addition.isBlank()) {
            return basePrompt;
        }
        if (basePrompt == null || basePrompt.isBlank()) {
            return addition;
        }
        return basePrompt + "\n\n" + addition; //$NON-NLS-1$
    }

    private static Set<String> collectAllowedTools(LangGraphAgentDomain domain) {
        ToolGraph graph = ToolGraphRegistry.getInstance().get(domain.getGraphId());
        if (graph == null) {
            return Set.of();
        }
        Set<String> tools = new HashSet<>();
        for (Map.Entry<String, ToolNode> entry : graph.getNodes().entrySet()) {
            ToolNode node = entry.getValue();
            if (node != null) {
                tools.addAll(node.getAllowedTools());
                tools.addAll(node.getRequiredTools());
            }
        }
        return tools;
    }
}
