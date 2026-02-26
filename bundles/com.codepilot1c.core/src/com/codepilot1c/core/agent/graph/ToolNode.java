package com.codepilot1c.core.agent.graph;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Node definition for tool graph.
 */
public class ToolNode {

    private final String id;
    private final boolean restrictive;
    private final Set<String> allowedTools;
    private final Set<String> requiredTools;
    private final int maxVisits;
    private final CycleLimitAction onCycleLimit;
    private final Set<String> tags;
    private final String promptHint;

    public ToolNode(
            String id,
            boolean restrictive,
            Set<String> allowedTools,
            Set<String> requiredTools,
            int maxVisits,
            CycleLimitAction onCycleLimit,
            Set<String> tags,
            String promptHint) {
        this.id = Objects.requireNonNull(id, "id"); //$NON-NLS-1$
        this.restrictive = restrictive;
        this.allowedTools = allowedTools != null ? new HashSet<>(allowedTools) : new HashSet<>();
        this.requiredTools = requiredTools != null ? new HashSet<>(requiredTools) : new HashSet<>();
        this.maxVisits = Math.max(1, maxVisits);
        this.onCycleLimit = onCycleLimit != null ? onCycleLimit : CycleLimitAction.ESCALATE_TO_FALLBACK;
        this.tags = tags != null ? new HashSet<>(tags) : new HashSet<>();
        this.promptHint = promptHint;
    }

    public String getId() {
        return id;
    }

    public boolean isRestrictive() {
        return restrictive;
    }

    public Set<String> getAllowedTools() {
        return Collections.unmodifiableSet(allowedTools);
    }

    public Set<String> getRequiredTools() {
        return Collections.unmodifiableSet(requiredTools);
    }

    public int getMaxVisits() {
        return maxVisits;
    }

    public CycleLimitAction getOnCycleLimit() {
        return onCycleLimit;
    }

    public Set<String> getTags() {
        return Collections.unmodifiableSet(tags);
    }

    public String getPromptHint() {
        return promptHint;
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static final class Builder {
        private final String id;
        private boolean restrictive = true;
        private final Set<String> allowedTools = new HashSet<>();
        private final Set<String> requiredTools = new HashSet<>();
        private int maxVisits = 5;
        private CycleLimitAction onCycleLimit = CycleLimitAction.ESCALATE_TO_FALLBACK;
        private final Set<String> tags = new HashSet<>();
        private String promptHint;

        private Builder(String id) {
            this.id = id;
        }

        public Builder restrictive(boolean restrictive) {
            this.restrictive = restrictive;
            return this;
        }

        public Builder allowTool(String toolName) {
            if (toolName != null && !toolName.isBlank()) {
                this.allowedTools.add(toolName);
            }
            return this;
        }

        public Builder allowTools(Set<String> toolNames) {
            if (toolNames != null) {
                for (String tool : toolNames) {
                    allowTool(tool);
                }
            }
            return this;
        }

        public Builder requireTool(String toolName) {
            if (toolName != null && !toolName.isBlank()) {
                this.requiredTools.add(toolName);
            }
            return this;
        }

        public Builder maxVisits(int maxVisits) {
            this.maxVisits = maxVisits;
            return this;
        }

        public Builder onCycleLimit(CycleLimitAction action) {
            if (action != null) {
                this.onCycleLimit = action;
            }
            return this;
        }

        public Builder tag(String tag) {
            if (tag != null && !tag.isBlank()) {
                this.tags.add(tag);
            }
            return this;
        }

        public Builder promptHint(String promptHint) {
            this.promptHint = promptHint;
            return this;
        }

        public ToolNode build() {
            return new ToolNode(id, restrictive, allowedTools, requiredTools, maxVisits, onCycleLimit, tags, promptHint);
        }
    }
}
