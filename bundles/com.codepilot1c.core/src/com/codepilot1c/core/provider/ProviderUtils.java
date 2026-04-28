/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider;

import com.codepilot1c.core.provider.config.LlmProviderConfig;
import com.codepilot1c.core.provider.config.ProviderType;

/**
 * Capability-driven helper methods for provider-specific runtime behavior.
 */
public final class ProviderUtils {

    private static final long DEFAULT_MAX_ATTACHMENT_BYTES = 10L * 1024L * 1024L;
    private static final int DEFAULT_MAX_ATTACHMENTS_PER_MESSAGE = 5;

    private ProviderUtils() {
    }

    public static ProviderCapabilities capabilitiesOf(ILlmProvider provider) {
        if (provider == null) {
            return ProviderCapabilities.none();
        }
        ProviderCapabilities capabilities = provider.getCapabilities();
        return capabilities != null ? capabilities : ProviderCapabilities.none();
    }

    public static ProviderCapabilities capabilitiesFor(LlmProviderConfig config) {
        if (config == null || config.getType() == null) {
            return ProviderCapabilities.none();
        }
        ProviderCapabilities.Builder base = ProviderCapabilities.builder()
                .imageInput(true)
                .documentInput(true)
                .attachmentMetadata(true)
                .maxAttachmentBytes(DEFAULT_MAX_ATTACHMENT_BYTES)
                .maxAttachmentsPerMessage(DEFAULT_MAX_ATTACHMENTS_PER_MESSAGE);
        if (config.getType() == ProviderType.ANTHROPIC) {
            return base.build();
        }
        if (config.getType() == ProviderType.OPENAI_COMPATIBLE) {
            return base.build();
        }
        if (config.getType() == ProviderType.CODEPILOT_BACKEND) {
            String modelFamily = ProviderCapabilities.resolveModelFamily(config.getModel());
            float defaultTemp = ProviderCapabilities.FAMILY_UNKNOWN.equals(modelFamily)
                    ? -1f
                    : ProviderCapabilities.QWEN_DEFAULT_TEMPERATURE;
            return base
                    .codePilotBackend(true)
                    .backendOptimizations(true)
                    .promptCacheHeaders(true)
                    .resolvedModel(true)
                    .resolvedModelFamily(modelFamily)
                    .defaultTemperature(defaultTemp)
                    .streamUsage(true)
                    .build();
        }
        if (config.getType() == ProviderType.OLLAMA) {
            return base.build();
        }
        return ProviderCapabilities.none();
    }

    public static boolean isCodePilotBackend(ILlmProvider provider) {
        return capabilitiesOf(provider).isCodePilotBackend();
    }

    public static boolean isCodePilotBackend(LlmProviderConfig config) {
        return capabilitiesFor(config).isCodePilotBackend();
    }

    public static boolean supportsBackendOptimizations(ILlmProvider provider) {
        return capabilitiesOf(provider).supportsBackendOptimizations();
    }

    public static boolean supportsBackendOptimizations(LlmProviderConfig config) {
        return capabilitiesFor(config).supportsBackendOptimizations();
    }

    public static boolean supportsPromptCacheHeaders(ILlmProvider provider) {
        return capabilitiesOf(provider).supportsPromptCacheHeaders();
    }

    public static boolean supportsPromptCacheHeaders(LlmProviderConfig config) {
        return capabilitiesFor(config).supportsPromptCacheHeaders();
    }

    public static boolean supportsResolvedModel(ILlmProvider provider) {
        return capabilitiesOf(provider).supportsResolvedModel();
    }

    public static boolean supportsResolvedModel(LlmProviderConfig config) {
        return capabilitiesFor(config).supportsResolvedModel();
    }
}
