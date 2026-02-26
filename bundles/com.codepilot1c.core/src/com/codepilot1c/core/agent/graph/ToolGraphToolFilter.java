package com.codepilot1c.core.agent.graph;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import com.codepilot1c.core.model.LlmRequest;

/**
 * Result of tool graph filtering.
 */
public class ToolGraphToolFilter {

    private final boolean allowAll;
    private final Set<String> allowedTools;
    private final LlmRequest.ToolChoice toolChoice;

    private ToolGraphToolFilter(boolean allowAll, Set<String> allowedTools, LlmRequest.ToolChoice toolChoice) {
        this.allowAll = allowAll;
        this.allowedTools = allowedTools != null ? new HashSet<>(allowedTools) : new HashSet<>();
        this.toolChoice = toolChoice;
    }

    public static ToolGraphToolFilter allowAll() {
        return new ToolGraphToolFilter(true, Set.of(), LlmRequest.ToolChoice.AUTO);
    }

    public static ToolGraphToolFilter restrictTo(Set<String> allowedTools, LlmRequest.ToolChoice toolChoice) {
        Objects.requireNonNull(allowedTools, "allowedTools"); //$NON-NLS-1$
        return new ToolGraphToolFilter(false, allowedTools, toolChoice != null ? toolChoice : LlmRequest.ToolChoice.AUTO);
    }

    public boolean isAllowAll() {
        return allowAll;
    }

    public boolean allows(String toolName) {
        if (allowAll) {
            return true;
        }
        return allowedTools.contains(toolName);
    }

    public Set<String> getAllowedTools() {
        return Collections.unmodifiableSet(allowedTools);
    }

    public LlmRequest.ToolChoice getToolChoice() {
        return toolChoice;
    }
}
