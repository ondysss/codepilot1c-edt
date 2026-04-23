/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider.config;

/**
 * Supported LLM provider API types.
 */
public enum ProviderType {

    /**
     * OpenAI-compatible chat completions API.
     * Used by: OpenAI, Groq, Together.ai, Mistral, Z.AI, etc.
     * Endpoint: POST /chat/completions
     * Models endpoint: GET /models
     */
    OPENAI_COMPATIBLE("openai_compatible", "OpenAI Compatible"), //$NON-NLS-1$ //$NON-NLS-2$

    /**
     * Anthropic Claude API (or Anthropic-compatible like Z.AI).
     * Endpoint: POST /messages
     * Models endpoint: GET /v1/models (supported by some providers like Z.AI)
     */
    ANTHROPIC("anthropic", "Anthropic (Claude)"), //$NON-NLS-1$ //$NON-NLS-2$

    /**
     * Ollama native API.
     * Endpoint: POST /api/chat
     * Models endpoint: GET /api/tags
     */
    OLLAMA("ollama", "Ollama (Local)"), //$NON-NLS-1$ //$NON-NLS-2$

    /**
     * 1C CodePilot hosted backend.
     *
     * <p>This remains wire-compatible with the OpenAI chat completions API, but
     * it needs a distinct type so backend-only routing and prompt/tool-surface
     * policies can be keyed on an explicit provider type instead of URL
     * heuristics.</p>
     */
    CODEPILOT_BACKEND("codepilot_backend", "1C CodePilot Backend"); //$NON-NLS-1$ //$NON-NLS-2$

    private final String id;
    private final String displayName;

    ProviderType(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Returns whether this provider type supports fetching model list from API.
     */
    public boolean supportsModelListing() {
        // All provider types support model listing now
        return true;
    }

    /**
     * Returns the models endpoint path for this provider type.
     * @return endpoint path or null if not supported
     */
    public String getModelsEndpoint() {
        switch (this) {
            case OPENAI_COMPATIBLE:
                return "/models"; //$NON-NLS-1$
            case ANTHROPIC:
                return "/v1/models"; //$NON-NLS-1$
            case OLLAMA:
                return "/api/tags"; //$NON-NLS-1$
            default:
                return "/models"; //$NON-NLS-1$
        }
    }

    /**
     * Returns the chat completions endpoint path for this provider type.
     */
    public String getChatEndpoint() {
        switch (this) {
            case OPENAI_COMPATIBLE:
                return "/chat/completions"; //$NON-NLS-1$
            case ANTHROPIC:
                return "/messages"; //$NON-NLS-1$
            case OLLAMA:
                return "/api/chat"; //$NON-NLS-1$
            default:
                return "/chat/completions"; //$NON-NLS-1$
        }
    }

    /**
     * Finds ProviderType by its id.
     */
    public static ProviderType fromId(String id) {
        for (ProviderType type : values()) {
            if (type.id.equals(id)) {
                return type;
            }
        }
        return OPENAI_COMPATIBLE; // default
    }
}
