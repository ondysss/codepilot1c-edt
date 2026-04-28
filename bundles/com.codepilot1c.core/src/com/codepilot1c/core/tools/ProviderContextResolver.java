/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import com.codepilot1c.core.provider.LlmProviderRegistry;
import com.codepilot1c.core.provider.ProviderSelectionGate;
import com.codepilot1c.core.provider.ProviderUtils;
import com.codepilot1c.core.provider.config.DynamicLlmProvider;
import com.codepilot1c.core.provider.config.LlmProviderConfig;
import com.codepilot1c.core.provider.config.LlmProviderConfigStore;
import com.codepilot1c.core.agent.profiles.AgentProfile;
import com.codepilot1c.core.tools.surface.ToolSurfaceContext;

/**
 * Resolves the active LLM provider configuration for tool surface context.
 *
 * <p>Extracted from {@code ToolRegistry} to isolate provider resolution
 * logic from tool registration and execution.</p>
 */
public class ProviderContextResolver {

    /**
     * Creates a runtime {@link ToolSurfaceContext} using the current active provider.
     *
     * @param profile the agent profile (may be null for default)
     * @return a fully resolved surface context
     */
    public ToolSurfaceContext createRuntimeSurfaceContext(AgentProfile profile) {
        ToolSurfaceContext.Builder builder = ToolSurfaceContext.builder()
                .profile(profile != null ? profile : ToolSurfaceContext.defaultProfile())
                .activeProviderId(resolveActiveProviderId())
                .backendSelectedInUi(ProviderSelectionGate.isCodePilotSelectedInUi());
        LlmProviderConfig providerConfig = resolveActiveProviderConfig();
        if (providerConfig != null) {
            builder.providerConfig(providerConfig);
            builder.qwenNative(ProviderUtils.capabilitiesFor(providerConfig).isQwenNative());
        }
        return builder.build();
    }

    /**
     * Resolves the active provider ID from the config store.
     *
     * @return the active provider ID, or null if none is configured
     */
    public String resolveActiveProviderId() {
        try {
            LlmProviderRegistry registry = LlmProviderRegistry.getInstance();
            LlmProviderConfigStore store = registry.getConfigStore();
            if (store == null) {
                store = LlmProviderConfigStore.getInstance();
            }
            String activeProviderId = store.getActiveProviderId();
            return activeProviderId != null && !activeProviderId.isBlank() ? activeProviderId : null;
        } catch (Exception e) {
            try {
                String activeProviderId = LlmProviderConfigStore.getInstance().getActiveProviderId();
                return activeProviderId != null && !activeProviderId.isBlank() ? activeProviderId : null;
            } catch (Exception nested) {
                return null;
            }
        }
    }

    /**
     * Resolves the active provider configuration.
     *
     * @return the provider config, or a default empty config if unavailable
     */
    public LlmProviderConfig resolveActiveProviderConfig() {
        try {
            LlmProviderRegistry registry = LlmProviderRegistry.getInstance();
            LlmProviderConfigStore store = registry.getConfigStore();
            if (store == null) {
                store = LlmProviderConfigStore.getInstance();
            }
            String activeProviderId = resolveActiveProviderId();
            if (activeProviderId != null) {
                if ("backend".equals(activeProviderId) && registry.getBackendProvider() instanceof DynamicLlmProvider backend) { //$NON-NLS-1$
                    return backend.getConfig().copy();
                }
                if (store != null) {
                    return store.getProvider(activeProviderId)
                            .map(LlmProviderConfig::copy)
                            .orElseGet(LlmProviderConfig::new);
                }
            }
        } catch (Exception e) {
            // Fall back to an empty snapshot when the registry is unavailable.
        }
        return new LlmProviderConfig();
    }
}
