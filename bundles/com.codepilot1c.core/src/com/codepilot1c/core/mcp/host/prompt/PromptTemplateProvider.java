package com.codepilot1c.core.mcp.host.prompt;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.codepilot1c.core.agent.prompts.AgentPromptTemplates;
import com.codepilot1c.core.mcp.model.McpContent;
import com.codepilot1c.core.mcp.model.McpPrompt;
import com.codepilot1c.core.mcp.model.McpPromptResult;

public class PromptTemplateProvider implements IMcpPromptProvider {

    @Override
    public List<McpPrompt> listPrompts() {
        return List.of(
            new McpPrompt("build", "Build-mode agent system prompt"), //$NON-NLS-1$ //$NON-NLS-2$
            new McpPrompt("plan", "Plan-mode agent system prompt"), //$NON-NLS-1$ //$NON-NLS-2$
            new McpPrompt("explore", "Explore-mode agent system prompt"), //$NON-NLS-1$ //$NON-NLS-2$
            new McpPrompt("subagent", "Subagent system prompt") //$NON-NLS-1$ //$NON-NLS-2$
        );
    }

    @Override
    public Optional<McpPromptResult> getPrompt(String name, Map<String, Object> arguments) {
        String text;
        switch (name) {
            case "build": //$NON-NLS-1$
                text = AgentPromptTemplates.buildBuildPrompt();
                break;
            case "plan": //$NON-NLS-1$
                text = AgentPromptTemplates.buildPlanPrompt();
                break;
            case "explore": //$NON-NLS-1$
                text = AgentPromptTemplates.buildExplorePrompt();
                break;
            case "subagent": //$NON-NLS-1$
                String profile = stringArg(arguments, "profile", "mcp"); //$NON-NLS-1$ //$NON-NLS-2$
                String description = stringArg(arguments, "description", "MCP prompt request"); //$NON-NLS-1$ //$NON-NLS-2$
                boolean readOnly = Boolean.parseBoolean(stringArg(arguments, "readOnly", "true")); //$NON-NLS-1$ //$NON-NLS-2$
                text = AgentPromptTemplates.buildSubagentPrompt(profile, description, readOnly);
                break;
            default:
                return Optional.empty();
        }

        McpPromptResult result = new McpPromptResult();
        result.setDescription("CodePilot prompt template: " + name); //$NON-NLS-1$
        McpPromptResult.PromptMessage message = new McpPromptResult.PromptMessage();
        message.setRole("system"); //$NON-NLS-1$
        message.setContent(McpContent.text(text));
        result.setMessages(List.of(message));
        return Optional.of(result);
    }

    private String stringArg(Map<String, Object> args, String key, String defaultValue) {
        if (args == null) {
            return defaultValue;
        }
        Object value = args.get(key);
        return value != null ? String.valueOf(value) : defaultValue;
    }
}
