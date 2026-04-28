/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider.claude;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.codepilot1c.core.model.LlmResponse;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Exercises {@link ClaudeProvider#parseClaudeUsage(JsonObject)} for the
 * expanded cache-token schema Claude exposes on its Messages API.
 */
public class ClaudeProviderUsageTest {

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    public void parsesCacheReadAndCacheCreation() {
        JsonObject usageJson = obj("{" //$NON-NLS-1$
                + "\"input_tokens\":800,\"output_tokens\":120," //$NON-NLS-1$
                + "\"cache_read_input_tokens\":500," //$NON-NLS-1$
                + "\"cache_creation_input_tokens\":100}"); //$NON-NLS-1$

        LlmResponse.Usage usage = ClaudeProvider.parseClaudeUsage(usageJson);

        assertEquals(800, usage.getPromptTokens());
        assertEquals(120, usage.getCompletionTokens());
        assertEquals(920, usage.getTotalTokens());
        assertEquals(500, usage.getCachedPromptTokens());
        assertEquals(500, usage.getCacheReadInputTokens());
        assertEquals(100, usage.getCacheCreationInputTokens());
    }

    @Test
    public void parsesCacheReadOnly() {
        JsonObject usageJson = obj("{" //$NON-NLS-1$
                + "\"input_tokens\":600,\"output_tokens\":90," //$NON-NLS-1$
                + "\"cache_read_input_tokens\":200}"); //$NON-NLS-1$

        LlmResponse.Usage usage = ClaudeProvider.parseClaudeUsage(usageJson);

        assertEquals(600, usage.getPromptTokens());
        assertEquals(90, usage.getCompletionTokens());
        assertEquals(200, usage.getCachedPromptTokens());
        assertEquals(200, usage.getCacheReadInputTokens());
        assertEquals(0, usage.getCacheCreationInputTokens());
    }

    @Test
    public void parsesNoCacheMarkers() {
        JsonObject usageJson = obj("{" //$NON-NLS-1$
                + "\"input_tokens\":300,\"output_tokens\":40}"); //$NON-NLS-1$

        LlmResponse.Usage usage = ClaudeProvider.parseClaudeUsage(usageJson);

        assertEquals(300, usage.getPromptTokens());
        assertEquals(40, usage.getCompletionTokens());
        assertEquals(340, usage.getTotalTokens());
        assertEquals(0, usage.getCachedPromptTokens());
        assertEquals(0, usage.getCacheReadInputTokens());
        assertEquals(0, usage.getCacheCreationInputTokens());
    }

    @Test
    public void parsesCacheCreationOnly() {
        JsonObject usageJson = obj("{" //$NON-NLS-1$
                + "\"input_tokens\":1000,\"output_tokens\":150," //$NON-NLS-1$
                + "\"cache_creation_input_tokens\":400}"); //$NON-NLS-1$

        LlmResponse.Usage usage = ClaudeProvider.parseClaudeUsage(usageJson);

        assertEquals(1000, usage.getPromptTokens());
        assertEquals(150, usage.getCompletionTokens());
        assertEquals(0, usage.getCacheReadInputTokens());
        assertEquals(400, usage.getCacheCreationInputTokens());
    }

    @Test
    public void handlesNullCacheFieldGracefully() {
        JsonObject usageJson = obj("{" //$NON-NLS-1$
                + "\"input_tokens\":50,\"output_tokens\":10," //$NON-NLS-1$
                + "\"cache_read_input_tokens\":null," //$NON-NLS-1$
                + "\"cache_creation_input_tokens\":null}"); //$NON-NLS-1$

        LlmResponse.Usage usage = ClaudeProvider.parseClaudeUsage(usageJson);

        assertEquals(50, usage.getPromptTokens());
        assertEquals(10, usage.getCompletionTokens());
        assertEquals(0, usage.getCacheReadInputTokens());
        assertEquals(0, usage.getCacheCreationInputTokens());
    }
}
