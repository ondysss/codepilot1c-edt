package com.codepilot1c.core.mcp.host.prompt;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.codepilot1c.core.mcp.model.McpPrompt;
import com.codepilot1c.core.mcp.model.McpPromptResult;

public interface IMcpPromptProvider {

    List<McpPrompt> listPrompts();

    Optional<McpPromptResult> getPrompt(String name, Map<String, Object> arguments);
}
