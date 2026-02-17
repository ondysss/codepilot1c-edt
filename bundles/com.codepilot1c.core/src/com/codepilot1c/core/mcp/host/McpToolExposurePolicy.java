package com.codepilot1c.core.mcp.host;

import java.util.Map;

public interface McpToolExposurePolicy {

    boolean isExposed(String toolName);

    boolean requiresConfirmation(String toolName, Map<String, Object> args);

    boolean isDestructive(String toolName);
}
