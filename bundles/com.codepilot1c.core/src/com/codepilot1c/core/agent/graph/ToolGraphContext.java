package com.codepilot1c.core.agent.graph;

import com.codepilot1c.core.tools.ToolResult;

/**
 * Context for evaluating graph transitions.
 */
public class ToolGraphContext {

    private final String userPrompt;
    private final String toolName;
    private final ToolResult toolResult;
    private final boolean success;
    private final String errorCode;

    public ToolGraphContext(String userPrompt, String toolName, ToolResult toolResult,
            boolean success, String errorCode) {
        this.userPrompt = userPrompt;
        this.toolName = toolName;
        this.toolResult = toolResult;
        this.success = success;
        this.errorCode = errorCode;
    }

    public String getUserPrompt() {
        return userPrompt;
    }

    public String getToolName() {
        return toolName;
    }

    public ToolResult getToolResult() {
        return toolResult;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
