/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.codepilot1c.core.provider.config.LlmProviderConfig;
import com.codepilot1c.core.provider.config.ProviderType;

/**
 * Tests for {@link ProviderCapabilities} focused on streaming-usage capability
 * gating added in Plan 2.3.
 */
public class ProviderCapabilitiesTest {

    @Test
    public void codePilotBackendSupportsStreamUsageByDefault() {
        ProviderCapabilities capabilities = ProviderUtils.capabilitiesFor(
                configured(ProviderType.CODEPILOT_BACKEND, "auto")); //$NON-NLS-1$

        assertTrue(capabilities.isCodePilotBackend());
        assertTrue(capabilities.supportsStreamUsage());
    }

    @Test
    public void codePilotBackendQwenCoderSupportsStreamUsage() {
        ProviderCapabilities capabilities = ProviderUtils.capabilitiesFor(
                configured(ProviderType.CODEPILOT_BACKEND, "qwen3-coder-plus")); //$NON-NLS-1$

        assertTrue(capabilities.isQwenNative());
        assertTrue(capabilities.supportsStreamUsage());
    }

    @Test
    public void genericOpenAiCompatibleDoesNotSupportStreamUsage() {
        ProviderCapabilities capabilities = ProviderUtils.capabilitiesFor(
                configured(ProviderType.OPENAI_COMPATIBLE, "gpt-4o")); //$NON-NLS-1$

        assertFalse(capabilities.isCodePilotBackend());
        assertFalse(capabilities.supportsStreamUsage());
    }

    @Test
    public void anthropicDoesNotSupportStreamUsage() {
        ProviderCapabilities capabilities = ProviderUtils.capabilitiesFor(
                configured(ProviderType.ANTHROPIC, "claude-sonnet-4-5")); //$NON-NLS-1$

        assertFalse(capabilities.supportsStreamUsage());
    }

    @Test
    public void ollamaDoesNotSupportStreamUsage() {
        ProviderCapabilities capabilities = ProviderUtils.capabilitiesFor(
                configured(ProviderType.OLLAMA, "llama3.2")); //$NON-NLS-1$

        assertFalse(capabilities.supportsStreamUsage());
    }

    @Test
    public void noneCapabilitiesDoNotSupportStreamUsage() {
        ProviderCapabilities none = ProviderCapabilities.none();

        assertFalse(none.supportsStreamUsage());
    }

    @Test
    public void builderStreamUsageFlagIsHonoured() {
        ProviderCapabilities explicit = ProviderCapabilities.builder()
                .streamUsage(true)
                .build();
        assertTrue(explicit.supportsStreamUsage());

        ProviderCapabilities unset = ProviderCapabilities.builder().build();
        assertFalse(unset.supportsStreamUsage());
    }

    private static LlmProviderConfig configured(ProviderType type, String model) {
        LlmProviderConfig config = new LlmProviderConfig();
        config.setId("test-" + type.name()); //$NON-NLS-1$
        config.setName("test-" + type.name()); //$NON-NLS-1$
        config.setType(type);
        config.setBaseUrl("https://example.com/v1"); //$NON-NLS-1$
        config.setApiKey("key"); //$NON-NLS-1$
        config.setModel(model);
        return config;
    }
}
