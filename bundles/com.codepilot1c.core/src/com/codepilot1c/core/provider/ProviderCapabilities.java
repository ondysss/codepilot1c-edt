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
    public static final String FAMILY_KIMI = "kimi"; //$NON-NLS-1$
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
    private final boolean imageInput;
    private final boolean documentInput;
    private final boolean attachmentMetadata;
    private final long maxAttachmentBytes;
    private final int maxAttachmentsPerMessage;

    private ProviderCapabilities(Builder builder) {
        this.codePilotBackend = builder.codePilotBackend;
        this.backendOptimizations = builder.backendOptimizations;
        this.promptCacheHeaders = builder.promptCacheHeaders;
        this.resolvedModel = builder.resolvedModel;
        this.resolvedModelFamily = builder.resolvedModelFamily;
        this.defaultTemperature = builder.defaultTemperature;
        this.nativeDeferredToolLoading = builder.nativeDeferredToolLoading;
        this.imageInput = builder.imageInput;
        this.documentInput = builder.documentInput;
        this.attachmentMetadata = builder.attachmentMetadata;
        this.maxAttachmentBytes = builder.maxAttachmentBytes;
        this.maxAttachmentsPerMessage = builder.maxAttachmentsPerMessage;
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
     *
     * <p>Kimi/Moonshot models are NOT considered Qwen-native even when routed
     * through CodePilot backend. They use standard OpenAI function-calling format
     * and are confused by Qwen-specific XML tool call priming.</p>
     */
    public boolean isQwenNative() {
        return codePilotBackend && resolvedModelFamily != null
                && !FAMILY_UNKNOWN.equals(resolvedModelFamily)
                && !FAMILY_KIMI.equals(resolvedModelFamily);
    }

    /**
     * Returns {@code true} when the resolved model is from the Kimi/Moonshot family.
     * Kimi models require special handling: they use standard OpenAI tool-calling
     * format (not Qwen XML) but need {@code reasoning_content} preserved in conversation
     * history for stable multi-turn tool usage.
     */
    public boolean isKimiNative() {
        return codePilotBackend && FAMILY_KIMI.equals(resolvedModelFamily);
    }

    /**
     * Returns {@code true} when content-based tool call fallback should be enabled.
     *
     * <p>This is broader than {@link #isQwenNative()} — it covers ALL CodePilot backend
     * models that may emit tool calls as text content instead of structured API responses.
     * Currently this includes Qwen (XML format) and Kimi/Moonshot (special token format).</p>
     */
    public boolean needsContentToolCallFallback() {
        return codePilotBackend;
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

    public boolean supportsImageInput() {
        return imageInput;
    }

    public boolean supportsDocumentInput() {
        return documentInput;
    }

    public boolean supportsAttachmentMetadata() {
        return attachmentMetadata;
    }

    public long getMaxAttachmentBytes() {
        return maxAttachmentBytes;
    }

    public int getMaxAttachmentsPerMessage() {
        return maxAttachmentsPerMessage;
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
        // Match kimi/moonshot models — must be checked BEFORE qwen patterns
        // because CodePilot backend may route "auto" to kimi-k2.5
        if (lower.startsWith("kimi") || lower.startsWith("moonshot")) { //$NON-NLS-1$ //$NON-NLS-2$
            return FAMILY_KIMI;
        }
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

    /**
     * Best-effort heuristic for multimodal image input support when the provider
     * exposes an OpenAI-compatible API but does not publish modality metadata.
     *
     * <p>This is intentionally conservative enough to avoid enabling images for
     * clearly text-only models, while still recognizing the common multimodal
     * families used behind generic OpenAI-compatible gateways.</p>
     *
     * @param model the configured model name
     * @return {@code true} when the model name strongly suggests vision support
     */
    public static boolean inferImageInputFromModel(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }
        String lower = model.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("vision") || lower.contains("vl") || lower.contains("image")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return true;
        }
        if (lower.startsWith("gpt-4o") || lower.startsWith("gpt-4.1") || lower.startsWith("o4")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return true;
        }
        if (lower.startsWith("gemini")) { //$NON-NLS-1$
            return true;
        }
        if (lower.startsWith("pixtral") || lower.startsWith("llava")) { //$NON-NLS-1$ //$NON-NLS-2$
            return true;
        }
        if (lower.startsWith("qvq") || FAMILY_QWEN_VL.equals(resolveModelFamily(model))) { //$NON-NLS-1$
            return true;
        }
        return false;
    }

    public static final class Builder {
        private boolean codePilotBackend;
        private boolean backendOptimizations;
        private boolean promptCacheHeaders;
        private boolean resolvedModel;
        private String resolvedModelFamily = FAMILY_UNKNOWN;
        private float defaultTemperature = -1f; // -1 means "use request default"
        private boolean nativeDeferredToolLoading;
        private boolean imageInput;
        private boolean documentInput;
        private boolean attachmentMetadata;
        private long maxAttachmentBytes = 10L * 1024L * 1024L;
        private int maxAttachmentsPerMessage = 5;

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

        public Builder imageInput(boolean imageInput) {
            this.imageInput = imageInput;
            return this;
        }

        public Builder documentInput(boolean documentInput) {
            this.documentInput = documentInput;
            return this;
        }

        public Builder attachmentMetadata(boolean attachmentMetadata) {
            this.attachmentMetadata = attachmentMetadata;
            return this;
        }

        public Builder maxAttachmentBytes(long maxAttachmentBytes) {
            this.maxAttachmentBytes = maxAttachmentBytes;
            return this;
        }

        public Builder maxAttachmentsPerMessage(int maxAttachmentsPerMessage) {
            this.maxAttachmentsPerMessage = maxAttachmentsPerMessage;
            return this;
        }

        public ProviderCapabilities build() {
            return new ProviderCapabilities(this);
        }
    }
}
