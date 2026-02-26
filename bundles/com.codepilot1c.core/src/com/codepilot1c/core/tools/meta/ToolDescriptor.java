package com.codepilot1c.core.tools.meta;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Metadata for tools used by routing logic.
 */
public class ToolDescriptor {

    private final String name;
    private final ToolCategory category;
    private final boolean mutating;
    private final boolean requiresValidationToken;
    private final Set<String> tags;

    public ToolDescriptor(
            String name,
            ToolCategory category,
            boolean mutating,
            boolean requiresValidationToken,
            Set<String> tags) {
        this.name = Objects.requireNonNull(name, "name"); //$NON-NLS-1$
        this.category = category != null ? category : ToolCategory.OTHER;
        this.mutating = mutating;
        this.requiresValidationToken = requiresValidationToken;
        this.tags = tags != null ? new HashSet<>(tags) : new HashSet<>();
    }

    public String getName() {
        return name;
    }

    public ToolCategory getCategory() {
        return category;
    }

    public boolean isMutating() {
        return mutating;
    }

    public boolean requiresValidationToken() {
        return requiresValidationToken;
    }

    public Set<String> getTags() {
        return Collections.unmodifiableSet(tags);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private ToolCategory category = ToolCategory.OTHER;
        private boolean mutating;
        private boolean requiresValidationToken;
        private final Set<String> tags = new HashSet<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder category(ToolCategory category) {
            if (category != null) {
                this.category = category;
            }
            return this;
        }

        public Builder mutating(boolean mutating) {
            this.mutating = mutating;
            return this;
        }

        public Builder requiresValidationToken(boolean requiresValidationToken) {
            this.requiresValidationToken = requiresValidationToken;
            return this;
        }

        public Builder tag(String tag) {
            if (tag != null && !tag.isBlank()) {
                this.tags.add(tag);
            }
            return this;
        }

        public ToolDescriptor build() {
            return new ToolDescriptor(name, category, mutating, requiresValidationToken, tags);
        }
    }
}
