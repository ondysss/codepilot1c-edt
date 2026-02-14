/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.internal;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;

import com.codepilot1c.core.settings.VibePreferenceConstants;

/**
 * Initializes default preference values for 1C Copilot plugin.
 */
public class VibePreferenceInitializer extends AbstractPreferenceInitializer {

    @Override
    public void initializeDefaultPreferences() {
        IEclipsePreferences defaults = DefaultScope.INSTANCE.getNode(VibeCorePlugin.PLUGIN_ID);

        // Default provider
        defaults.put(VibePreferenceConstants.PREF_PROVIDER_ID, "claude"); //$NON-NLS-1$

        // Claude defaults (no default model - user must configure)
        defaults.put(VibePreferenceConstants.PREF_CLAUDE_MODEL, ""); //$NON-NLS-1$
        defaults.put(VibePreferenceConstants.PREF_CLAUDE_API_URL, "https://api.anthropic.com/v1"); //$NON-NLS-1$
        defaults.putInt(VibePreferenceConstants.PREF_CLAUDE_MAX_TOKENS, 4096);
        defaults.put(VibePreferenceConstants.PREF_CLAUDE_CUSTOM_MODELS, ""); //$NON-NLS-1$

        // OpenAI defaults (no default model - user must configure)
        defaults.put(VibePreferenceConstants.PREF_OPENAI_MODEL, ""); //$NON-NLS-1$
        defaults.put(VibePreferenceConstants.PREF_OPENAI_API_URL, "https://api.openai.com/v1"); //$NON-NLS-1$
        defaults.putInt(VibePreferenceConstants.PREF_OPENAI_MAX_TOKENS, 4096);
        defaults.put(VibePreferenceConstants.PREF_OPENAI_CUSTOM_MODELS, ""); //$NON-NLS-1$

        // Ollama defaults (no default model - user must configure)
        defaults.put(VibePreferenceConstants.PREF_OLLAMA_MODEL, ""); //$NON-NLS-1$
        defaults.put(VibePreferenceConstants.PREF_OLLAMA_API_URL, "http://localhost:11434"); //$NON-NLS-1$
        defaults.put(VibePreferenceConstants.PREF_OLLAMA_CUSTOM_MODELS, ""); //$NON-NLS-1$

        // General settings
        defaults.putInt(VibePreferenceConstants.PREF_REQUEST_TIMEOUT, 60);
        defaults.putBoolean(VibePreferenceConstants.PREF_STREAMING_ENABLED, true);

        // Agent settings
        defaults.putInt(VibePreferenceConstants.PREF_MAX_TOOL_ITERATIONS,
                VibePreferenceConstants.DEFAULT_MAX_TOOL_ITERATIONS);
        defaults.putBoolean(VibePreferenceConstants.PREF_AGENT_SKIP_TOOL_CONFIRMATIONS, false);

        // Embedding defaults - use auto-detection for zero-config experience
        defaults.put(VibePreferenceConstants.PREF_EMBEDDING_PROVIDER_ID,
                VibePreferenceConstants.PREF_EMBEDDING_PROVIDER_AUTO); // Auto-detect
        defaults.putBoolean(VibePreferenceConstants.PREF_EMBEDDING_AUTO_DETECT, true);
        defaults.put(VibePreferenceConstants.PREF_EMBEDDING_API_URL, "https://api.openai.com/v1"); //$NON-NLS-1$
        defaults.put(VibePreferenceConstants.PREF_EMBEDDING_API_KEY, ""); //$NON-NLS-1$
        defaults.put(VibePreferenceConstants.PREF_OPENAI_EMBEDDING_MODEL, "text-embedding-3-small"); //$NON-NLS-1$
        defaults.putInt(VibePreferenceConstants.PREF_EMBEDDING_BATCH_SIZE, 100);
        defaults.putInt(VibePreferenceConstants.PREF_EMBEDDING_DIMENSIONS, 1536);

        // Ollama embedding defaults
        defaults.put(VibePreferenceConstants.PREF_OLLAMA_EMBEDDING_MODEL, "nomic-embed-text"); //$NON-NLS-1$
        defaults.put(VibePreferenceConstants.PREF_OLLAMA_EMBEDDING_API_URL, "http://localhost:11434"); //$NON-NLS-1$

        // Indexing defaults
        defaults.putBoolean(VibePreferenceConstants.PREF_INDEXING_ENABLED, true);
        defaults.putBoolean(VibePreferenceConstants.PREF_INDEXING_ON_STARTUP, false);
        defaults.putInt(VibePreferenceConstants.PREF_INDEXING_MAX_FILE_SIZE, 100000); // 100KB

        // HTTP defaults (based on Workmate patterns)
        defaults.putBoolean(VibePreferenceConstants.PREF_HTTP_HTTP2_ENABLED, true);
        defaults.putBoolean(VibePreferenceConstants.PREF_HTTP_USE_SYSTEM_PROXY, true);
        defaults.putBoolean(VibePreferenceConstants.PREF_HTTP_GZIP_ENABLED, true);
        defaults.putInt(VibePreferenceConstants.PREF_HTTP_GZIP_MIN_BYTES, 1024); // Compress if > 1KB

        // Completion/review are not part of OSS edition; their preferences are not initialized here.
    }
}
