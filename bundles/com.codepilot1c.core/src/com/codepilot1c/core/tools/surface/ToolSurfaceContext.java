/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.surface;

import java.util.Objects;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.codepilot1c.core.agent.profiles.AgentProfile;
import com.codepilot1c.core.agent.profiles.AgentProfileRegistry;
import com.codepilot1c.core.permissions.PermissionRule;
import com.codepilot1c.core.provider.config.LlmProviderConfig;

/**
 * Immutable per-tool surface context passed to contributors.
 */
public final class ToolSurfaceContext {

    private static final AgentProfile FALLBACK_PROFILE = new AgentProfile() {
        @Override public String getId() { return "fallback"; } //$NON-NLS-1$
        @Override public String getName() { return "Fallback"; } //$NON-NLS-1$
        @Override public String getDescription() { return "Fallback profile for headless tool-surface resolution."; } //$NON-NLS-1$
        @Override public Set<String> getAllowedTools() { return Collections.emptySet(); }
        @Override public List<PermissionRule> getDefaultPermissions() { return Collections.emptyList(); }
        @Override public String getSystemPromptAddition() { return null; }
        @Override public int getMaxSteps() { return 25; }
        @Override public long getTimeoutMs() { return 300_000L; }
        @Override public boolean isReadOnly() { return false; }
        @Override public boolean canExecuteShell() { return true; }
    };

    private final LlmProviderConfig providerSnapshot;
    private final String activeProviderId;
    private final AgentProfile profile;
    private final ToolCategory category;
    private final boolean builtIn;
    private final boolean backendSelectedInUi;

    private ToolSurfaceContext(Builder builder) {
        this.providerSnapshot = (builder.providerConfig != null ? builder.providerConfig : new LlmProviderConfig()).copy();
        this.activeProviderId = builder.activeProviderId;
        this.profile = builder.profile != null ? builder.profile : defaultProfile();
        this.category = builder.category != null ? builder.category : ToolCategory.DYNAMIC;
        this.builtIn = builder.builtIn;
        this.backendSelectedInUi = builder.backendSelectedInUi;
    }

    public static ToolSurfaceContext passthrough() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static AgentProfile defaultProfile() {
        try {
            return AgentProfileRegistry.getInstance().getDefaultProfile();
        } catch (Throwable e) {
            return FALLBACK_PROFILE;
        }
    }

    public Builder toBuilder() {
        return builder()
                .providerConfig(providerSnapshot)
                .activeProviderId(activeProviderId)
                .profile(profile)
                .category(category)
                .builtIn(builtIn)
                .backendSelectedInUi(backendSelectedInUi);
    }

    public LlmProviderConfig getProviderConfig() {
        return providerSnapshot.copy();
    }

    public String getActiveProviderId() {
        return activeProviderId;
    }

    public AgentProfile getProfile() {
        return profile;
    }

    public ToolCategory getCategory() {
        return category;
    }

    public boolean isBuiltIn() {
        return builtIn;
    }

    public boolean isBackendSelectedInUi() {
        return backendSelectedInUi;
    }

    public static final class Builder {
        private LlmProviderConfig providerConfig;
        private String activeProviderId;
        private AgentProfile profile;
        private ToolCategory category;
        private boolean builtIn;
        private boolean backendSelectedInUi;

        public Builder providerConfig(LlmProviderConfig providerConfig) {
            this.providerConfig = providerConfig;
            return this;
        }

        public Builder activeProviderId(String activeProviderId) {
            this.activeProviderId = activeProviderId;
            return this;
        }

        public Builder profile(AgentProfile profile) {
            this.profile = profile;
            return this;
        }

        public Builder category(ToolCategory category) {
            this.category = category;
            return this;
        }

        public Builder builtIn(boolean builtIn) {
            this.builtIn = builtIn;
            return this;
        }

        public Builder backendSelectedInUi(boolean backendSelectedInUi) {
            this.backendSelectedInUi = backendSelectedInUi;
            return this;
        }

        public ToolSurfaceContext build() {
            return new ToolSurfaceContext(this);
        }
    }
}
