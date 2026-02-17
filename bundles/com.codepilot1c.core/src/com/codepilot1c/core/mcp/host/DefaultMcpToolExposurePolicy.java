package com.codepilot1c.core.mcp.host;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolRegistry;

/**
 * Default tool exposure policy with wildcard and deny-by-name support.
 */
public class DefaultMcpToolExposurePolicy implements McpToolExposurePolicy {

    private final McpHostConfig config;
    private final Set<String> explicitAllow;
    private final Set<String> explicitDeny;

    public DefaultMcpToolExposurePolicy(McpHostConfig config) {
        this.config = config;
        this.explicitAllow = new HashSet<>();
        this.explicitDeny = new HashSet<>();
        parse(config.getExposedToolsFilter());
    }

    private void parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        Arrays.stream(raw.split(",")) //$NON-NLS-1$
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .forEach(token -> {
                if ("*".equals(token)) { //$NON-NLS-1$
                    explicitAllow.add(token);
                } else if (token.startsWith("-")) { //$NON-NLS-1$
                    explicitDeny.add(token.substring(1));
                } else {
                    explicitAllow.add(token);
                }
            });
    }

    @Override
    public boolean isExposed(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        if (explicitDeny.contains(toolName)) {
            return false;
        }
        if (explicitAllow.contains("*")) { //$NON-NLS-1$
            return true;
        }
        return explicitAllow.contains(toolName);
    }

    @Override
    public boolean requiresConfirmation(String toolName, Map<String, Object> args) {
        ITool tool = ToolRegistry.getInstance().getTool(toolName);
        if (tool == null) {
            return true;
        }
        if (tool.requiresConfirmation()) {
            return true;
        }
        return isDestructive(toolName);
    }

    @Override
    public boolean isDestructive(String toolName) {
        ITool tool = ToolRegistry.getInstance().getTool(toolName);
        if (tool == null) {
            return true;
        }
        if (tool.isDestructive()) {
            return true;
        }
        return switch (config.getMutationPolicy()) {
            case DENY, ASK -> true;
            case ALLOW -> false;
        };
    }

    public Set<String> getExplicitAllow() {
        return Collections.unmodifiableSet(explicitAllow);
    }

    public Set<String> getExplicitDeny() {
        return Collections.unmodifiableSet(explicitDeny);
    }
}
