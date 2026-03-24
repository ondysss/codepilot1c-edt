/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider;

/**
 * Declares provider-specific runtime capabilities.
 *
 * <p>Extended with Qwen model family detection for CodePilot backend
 * to enable model-specific transport optimizations.</p>
 */
public final class ProviderCapabilities {

    /** Model family constants. */
    public static final String FAMILY_QWEN_CODER = "qwen-coder"; //$NON-NLS-1$
    public static final String FAMILY_QWEN_VL = "qwen-vl"; //$NON-NLS-1$
    public static final String FAMILY_QWEN_GENERAL = "qwen-general"; //$NON-NLS-1$
    public static final String FAMILY_UNKNOWN = "unknown"; //$NON-NLS-1$

    /** Default temperature for Qwen models (per Qwen Code reference implementation). */
    public static final float QWEN_DEFAULT_TEMPERATURE = 0.3f;

    private static final ProviderCapabilities NONE = builder().build();

    private final boolean codePilotBackend;
    private final boolean backendOptimizations;
    private final boolean promptCacheHeaders;
    private final boolean resolvedModel;
    private final String resolvedModelFamily;
    private final float defaultTemperature;
    private final boolean nativeDeferredToolLoading;

    private ProviderCapabilities(Builder builder) {
        this.codePilotBackend = builder.codePilotBackend;
        this.backendOptimizations = builder.backendOptimizations;
        this.promptCacheHeaders = builder.promptCacheHeaders;
        this.resolvedModel = builder.resolvedModel;
        this.resolvedModelFamily = builder.resolvedModelFamily;
        this.defaultTemperature = builder.defaultTemperature;
        this.nativeDeferredToolLoading = builder.nativeDeferredToolLoading;
    }

    public static ProviderCapabilities none() {
        return NONE;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isCodePilotBackend() {
        return codePilotBackend;
    }

    /**
     * Returns {@code true} when the active provider is CodePilot backend
     * routing to a Qwen model family. This gates all Qwen-specific
     * transport optimizations (XML tool call priming, streaming repair, etc.).
     */
    public boolean isQwenNative() {
        return codePilotBackend && resolvedModelFamily != null
                && !FAMILY_UNKNOWN.equals(resolvedModelFamily);
    }

    public boolean supportsBackendOptimizations() {
        return backendOptimizations;
    }

    public boolean supportsPromptCacheHeaders() {
        return promptCacheHeaders;
    }

    public boolean supportsResolvedModel() {
        return resolvedModel;
    }

    /**
     * Returns the resolved model family string.
     *
     * @return one of {@link #FAMILY_QWEN_CODER}, {@link #FAMILY_QWEN_VL},
     *         {@link #FAMILY_QWEN_GENERAL}, or {@link #FAMILY_UNKNOWN}
     */
    public String getResolvedModelFamily() {
        return resolvedModelFamily != null ? resolvedModelFamily : FAMILY_UNKNOWN;
    }

    /**
     * Returns the recommended default temperature for this provider/model.
     * Qwen models default to {@value #QWEN_DEFAULT_TEMPERATURE}.
     */
    public float getDefaultTemperature() {
        return defaultTemperature;
    }

    /**
     * Returns {@code true} if the provider supports native deferred tool loading
     * (e.g., Anthropic's tool_choice with deferred loading). When {@code false},
     * the agent runner uses {@code discover_tools} meta-tool to reduce the
     * initial tool surface for OpenAI-compatible providers.
     */
    public boolean supportsNativeDeferredToolLoading() {
        return nativeDeferredToolLoading;
    }

    /**
     * Returns {@code true} if deferred tool loading via {@code discover_tools}
     * should be activated. This is the case when the provider does NOT support
     * native deferred loading AND is using a CodePilot backend (Qwen models).
     */
    public boolean shouldUseDeferredLoading() {
        return codePilotBackend && !nativeDeferredToolLoading;
    }

    /**
     * Resolves the model family from a model name string.
     *
     * @param model the model name (e.g. "qwen3-coder", "qwen2.5-vl")
     * @return the family constant
     */
    public static String resolveModelFamily(String model) {
        if (model == null || model.isBlank()) {
            return FAMILY_UNKNOWN;
        }
        String lower = model.toLowerCase(java.util.Locale.ROOT);
        // Match qwen*-coder or coder-model patterns
        if (lower.matches("qwen[^-]*-coder.*") || lower.contains("coder-model")) { //$NON-NLS-1$ //$NON-NLS-2$
            return FAMILY_QWEN_CODER;
        }
        // Match qwen*-vl patterns
        if (lower.matches("qwen[^-]*-vl.*")) { //$NON-NLS-1$
            return FAMILY_QWEN_VL;
        }
        // Match any qwen model
        if (lower.startsWith("qwen")) { //$NON-NLS-1$
            return FAMILY_QWEN_GENERAL;
        }
        return FAMILY_UNKNOWN;
    }

    public static final class Builder {
        private boolean codePilotBackend;
        private boolean backendOptimizations;
        private boolean promptCacheHeaders;
        private boolean resolvedModel;
        private String resolvedModelFamily = FAMILY_UNKNOWN;
        private float defaultTemperature = -1f; // -1 means "use request default"
        private boolean nativeDeferredToolLoading;

        public Builder codePilotBackend(boolean codePilotBackend) {
            this.codePilotBackend = codePilotBackend;
            return this;
        }

        public Builder backendOptimizations(boolean backendOptimizations) {
            this.backendOptimizations = backendOptimizations;
            return this;
        }

        public Builder promptCacheHeaders(boolean promptCacheHeaders) {
            this.promptCacheHeaders = promptCacheHeaders;
            return this;
        }

        public Builder resolvedModel(boolean resolvedModel) {
            this.resolvedModel = resolvedModel;
            return this;
        }

        public Builder resolvedModelFamily(String resolvedModelFamily) {
            this.resolvedModelFamily = resolvedModelFamily;
            return this;
        }

        public Builder defaultTemperature(float defaultTemperature) {
            this.defaultTemperature = defaultTemperature;
            return this;
        }

        public Builder nativeDeferredToolLoading(boolean nativeDeferredToolLoading) {
            this.nativeDeferredToolLoading = nativeDeferredToolLoading;
            return this;
        }

        public ProviderCapabilities build() {
            return new ProviderCapabilities(this);
        }
    }
}
