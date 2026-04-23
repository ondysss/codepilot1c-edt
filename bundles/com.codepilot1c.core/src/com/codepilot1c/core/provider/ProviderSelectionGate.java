/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider;

import com.codepilot1c.core.provider.config.DynamicLlmProvider;
import com.codepilot1c.core.provider.config.LlmProviderConfig;
import com.codepilot1c.core.provider.config.LlmProviderConfigStore;

/**
 * Gate for backend-only runtime behavior.
 *
 * <p>Backend-only prompt and tool-surface logic must activate only when the UI
 * explicitly persisted a CodePilot backend selection. Callers must not infer
 * backend mode from provider fallbacks.</p>
 */
public final class ProviderSelectionGate {

    private ProviderSelectionGate() {
    }

    /**
     * Returns {@code true} only when the UI explicitly persisted a CodePilot
     * backend selection.
     */
    public static boolean isCodePilotSelectedInUi() {
        LlmProviderConfigStore store = LlmProviderConfigStore.getInstance();
        String activeId = store.getActiveProviderId();
        if (activeId == null || activeId.isBlank()) {
            return false;
        }
        if ("backend".equals(activeId)) { //$NON-NLS-1$
            return true;
        }
        return store.getProvider(activeId)
                .map(ProviderSelectionGate::isCodePilotBackend)
                .orElse(false);
    }

    /**
     * Returns whether the given provider instance is backed by a CodePilot
     * backend configuration.
     */
    public static boolean isCodePilotBackend(ILlmProvider provider) {
        if (ProviderUtils.isCodePilotBackend(provider)) {
            return true;
        }
        if (provider instanceof DynamicLlmProvider dynamicProvider) {
            return isCodePilotBackend(dynamicProvider.getConfig());
        }
        return false;
    }

    /**
     * Returns whether the given config represents the CodePilot backend.
     */
    public static boolean isCodePilotBackend(LlmProviderConfig config) {
        return ProviderUtils.isCodePilotBackend(config);
    }
}
