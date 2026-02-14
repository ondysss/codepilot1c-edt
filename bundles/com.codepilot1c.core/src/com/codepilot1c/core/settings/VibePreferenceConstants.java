/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.settings;

/**
 * Constants for 1C Copilot plugin preferences.
 */
public final class VibePreferenceConstants {

    private VibePreferenceConstants() {
        // Utility class
    }

    // General preferences
    public static final String PREF_PROVIDER_ID = "providerId"; //$NON-NLS-1$
    public static final String PREF_REQUEST_TIMEOUT = "requestTimeout"; //$NON-NLS-1$
    public static final String PREF_STREAMING_ENABLED = "streamingEnabled"; //$NON-NLS-1$

    // Agent preferences
    /** Maximum number of tool call iterations before stopping agent loop. */
    public static final String PREF_MAX_TOOL_ITERATIONS = "agent.maxToolIterations"; //$NON-NLS-1$
    /** Allow agent to execute tools without user confirmation dialogs. */
    public static final String PREF_AGENT_SKIP_TOOL_CONFIRMATIONS = "agent.skipToolConfirmations"; //$NON-NLS-1$
    /** Default value for max tool iterations. */
    public static final int DEFAULT_MAX_TOOL_ITERATIONS = 100;

    // Claude preferences
    public static final String PREF_CLAUDE_API_KEY = "claude.apiKey"; //$NON-NLS-1$
    public static final String PREF_CLAUDE_API_URL = "claude.apiUrl"; //$NON-NLS-1$
    public static final String PREF_CLAUDE_MODEL = "claude.model"; //$NON-NLS-1$
    public static final String PREF_CLAUDE_MAX_TOKENS = "claude.maxTokens"; //$NON-NLS-1$
    public static final String PREF_CLAUDE_CUSTOM_MODELS = "claude.customModels"; //$NON-NLS-1$

    // OpenAI preferences
    public static final String PREF_OPENAI_API_KEY = "openai.apiKey"; //$NON-NLS-1$
    public static final String PREF_OPENAI_API_URL = "openai.apiUrl"; //$NON-NLS-1$
    public static final String PREF_OPENAI_MODEL = "openai.model"; //$NON-NLS-1$
    public static final String PREF_OPENAI_MAX_TOKENS = "openai.maxTokens"; //$NON-NLS-1$
    public static final String PREF_OPENAI_CUSTOM_MODELS = "openai.customModels"; //$NON-NLS-1$

    // Ollama preferences
    public static final String PREF_OLLAMA_API_URL = "ollama.apiUrl"; //$NON-NLS-1$
    public static final String PREF_OLLAMA_MODEL = "ollama.model"; //$NON-NLS-1$
    public static final String PREF_OLLAMA_CUSTOM_MODELS = "ollama.customModels"; //$NON-NLS-1$

    // Embedding preferences
    public static final String PREF_EMBEDDING_PROVIDER_ID = "embedding.providerId"; //$NON-NLS-1$
    public static final String PREF_EMBEDDING_API_URL = "embedding.apiUrl"; //$NON-NLS-1$
    public static final String PREF_EMBEDDING_API_KEY = "embedding.apiKey"; //$NON-NLS-1$
    public static final String PREF_OPENAI_EMBEDDING_MODEL = "openai.embeddingModel"; //$NON-NLS-1$
    public static final String PREF_EMBEDDING_BATCH_SIZE = "embedding.batchSize"; //$NON-NLS-1$
    public static final String PREF_EMBEDDING_DIMENSIONS = "embedding.dimensions"; //$NON-NLS-1$

    // Ollama embedding preferences
    /** Ollama embedding model name (e.g. nomic-embed-text). */
    public static final String PREF_OLLAMA_EMBEDDING_MODEL = "ollama.embeddingModel"; //$NON-NLS-1$
    /** Ollama embedding API URL (defaults to http://localhost:11434). */
    public static final String PREF_OLLAMA_EMBEDDING_API_URL = "ollama.embeddingApiUrl"; //$NON-NLS-1$

    // Auto-embedding preferences
    /** Enable auto-detection of embedding provider (local first, then cloud). */
    public static final String PREF_EMBEDDING_AUTO_DETECT = "embedding.autoDetect"; //$NON-NLS-1$
    /** Provider ID for auto mode: "auto", "ollama", "openai". */
    public static final String PREF_EMBEDDING_PROVIDER_AUTO = "auto"; //$NON-NLS-1$

    // Indexing preferences
    public static final String PREF_INDEXING_ENABLED = "indexing.enabled"; //$NON-NLS-1$
    public static final String PREF_INDEXING_ON_STARTUP = "indexing.onStartup"; //$NON-NLS-1$
    public static final String PREF_INDEXING_MAX_FILE_SIZE = "indexing.maxFileSize"; //$NON-NLS-1$

    // HTTP preferences
    /** Enable HTTP/2 protocol (with automatic fallback to HTTP/1.1). */
    public static final String PREF_HTTP_HTTP2_ENABLED = "http.http2Enabled"; //$NON-NLS-1$
    /** Enable system proxy support. */
    public static final String PREF_HTTP_USE_SYSTEM_PROXY = "http.useSystemProxy"; //$NON-NLS-1$
    /** Enable GZIP compression for request bodies. */
    public static final String PREF_HTTP_GZIP_ENABLED = "http.gzipEnabled"; //$NON-NLS-1$
    /** Minimum request body size (bytes) to trigger GZIP compression. */
    public static final String PREF_HTTP_GZIP_MIN_BYTES = "http.gzipMinBytes"; //$NON-NLS-1$

    // Universal LLM Provider preferences (new system)
    /** JSON array of provider configurations. */
    public static final String PREF_LLM_PROVIDERS = "llm.providers"; //$NON-NLS-1$
    /** Active provider UUID. */
    public static final String PREF_LLM_ACTIVE_PROVIDER_ID = "llm.activeProviderId"; //$NON-NLS-1$
    /** Config version for migration support. */
    public static final String PREF_LLM_CONFIG_VERSION = "llm.configVersion"; //$NON-NLS-1$
}
