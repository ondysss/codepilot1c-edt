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
    /** Enable automatic history compaction in chat view. */
    public static final String PREF_CHAT_AUTO_COMPACT_ENABLED = "chat.autoCompact.enabled"; //$NON-NLS-1$
    /** Threshold (percent) for automatic history compaction. */
    public static final String PREF_CHAT_AUTO_COMPACT_THRESHOLD_PERCENT = "chat.autoCompact.thresholdPercent"; //$NON-NLS-1$
    /** Show token usage stats in chat view. */
    public static final String PREF_CHAT_SHOW_TOKEN_USAGE = "chat.showTokenUsage"; //$NON-NLS-1$

    // QA preferences
    /** Path to Vanessa Automation .epf */
    public static final String PREF_QA_VA_EPF_PATH = "qa.vanessaEpfPath"; //$NON-NLS-1$

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

    // Prompt customization
    /** Optional custom text prepended to every system prompt. */
    public static final String PREF_PROMPT_SYSTEM_PREFIX = "prompt.systemPrefix"; //$NON-NLS-1$
    /** Optional custom text appended to every system prompt. */
    public static final String PREF_PROMPT_SYSTEM_SUFFIX = "prompt.systemSuffix"; //$NON-NLS-1$
    /** Custom template for explain-code command. */
    public static final String PREF_PROMPT_TEMPLATE_EXPLAIN_CODE = "prompt.template.explainCode"; //$NON-NLS-1$
    /** Custom template for generate-code command. */
    public static final String PREF_PROMPT_TEMPLATE_GENERATE_CODE = "prompt.template.generateCode"; //$NON-NLS-1$
    /** Custom template for fix-code command. */
    public static final String PREF_PROMPT_TEMPLATE_FIX_CODE = "prompt.template.fixCode"; //$NON-NLS-1$
    /** Custom template for critique-code command. */
    public static final String PREF_PROMPT_TEMPLATE_CRITICISE_CODE = "prompt.template.criticiseCode"; //$NON-NLS-1$
    /** Custom template for add-code command. */
    public static final String PREF_PROMPT_TEMPLATE_ADD_CODE = "prompt.template.addCode"; //$NON-NLS-1$
    /** Custom template for doc-comments command. */
    public static final String PREF_PROMPT_TEMPLATE_DOC_COMMENTS = "prompt.template.docComments"; //$NON-NLS-1$
    /** Custom template for query optimization command. */
    public static final String PREF_PROMPT_TEMPLATE_OPTIMIZE_QUERY = "prompt.template.optimizeQuery"; //$NON-NLS-1$
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

    // MCP host preferences
    public static final String PREF_MCP_HOST_ENABLED = "mcp.host.enabled"; //$NON-NLS-1$
    public static final String PREF_MCP_HOST_HTTP_ENABLED = "mcp.host.http.enabled"; //$NON-NLS-1$
    public static final String PREF_MCP_HOST_HTTP_BIND_ADDRESS = "mcp.host.http.bindAddress"; //$NON-NLS-1$
    public static final String PREF_MCP_HOST_HTTP_PORT = "mcp.host.http.port"; //$NON-NLS-1$
    public static final String PREF_MCP_HOST_AUTH_MODE = "mcp.host.auth.mode"; //$NON-NLS-1$
    public static final String PREF_MCP_HOST_POLICY_DEFAULT_MUTATION_DECISION =
        "mcp.host.policy.defaultMutationDecision"; //$NON-NLS-1$
    public static final String PREF_MCP_HOST_POLICY_EXPOSED_TOOLS = "mcp.host.policy.exposedTools"; //$NON-NLS-1$
}
