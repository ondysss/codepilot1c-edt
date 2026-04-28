/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import com.codepilot1c.core.model.LlmResponse;

public class OpenAiUsageParserTest {

    @Test
    public void parsesFullUsageWithCachedTokens() {
        String json = "{\"prompt_tokens\":100,\"completion_tokens\":50,\"total_tokens\":150," //$NON-NLS-1$
                + "\"prompt_tokens_details\":{\"cached_tokens\":40}}"; //$NON-NLS-1$

        LlmResponse.Usage usage = OpenAiUsageParser.parse(json);

        assertEquals(100, usage.getPromptTokens());
        assertEquals(40, usage.getCachedPromptTokens());
        assertEquals(50, usage.getCompletionTokens());
        assertEquals(150, usage.getTotalTokens());
    }

    @Test
    public void parsesMinimalUsageWithoutCachedTokens() {
        String json = "{\"prompt_tokens\":80,\"completion_tokens\":20,\"total_tokens\":100}"; //$NON-NLS-1$

        LlmResponse.Usage usage = OpenAiUsageParser.parse(json);

        assertEquals(80, usage.getPromptTokens());
        assertEquals(0, usage.getCachedPromptTokens());
        assertEquals(20, usage.getCompletionTokens());
        assertEquals(100, usage.getTotalTokens());
    }

    @Test
    public void derivesTotalFromPromptPlusCompletionWhenMissing() {
        String json = "{\"prompt_tokens\":10,\"completion_tokens\":5}"; //$NON-NLS-1$

        LlmResponse.Usage usage = OpenAiUsageParser.parse(json);

        assertEquals(10, usage.getPromptTokens());
        assertEquals(5, usage.getCompletionTokens());
        assertEquals(15, usage.getTotalTokens());
    }

    @Test
    public void returnsNullForNullInput() {
        assertNull(OpenAiUsageParser.parse((String) null));
    }

    @Test
    public void returnsNullForEmptyInput() {
        assertNull(OpenAiUsageParser.parse("")); //$NON-NLS-1$
        assertNull(OpenAiUsageParser.parse("   ")); //$NON-NLS-1$
    }

    @Test
    public void returnsNullForMalformedJson() {
        assertNull(OpenAiUsageParser.parse("not json")); //$NON-NLS-1$
        assertNull(OpenAiUsageParser.parse("{\"prompt_tokens\":")); //$NON-NLS-1$
    }

    @Test
    public void returnsNullForJsonArrayInput() {
        assertNull(OpenAiUsageParser.parse("[1,2,3]")); //$NON-NLS-1$
    }

    @Test
    public void returnsNullForEmptyObject() {
        assertNull(OpenAiUsageParser.parse("{}")); //$NON-NLS-1$
    }

    @Test
    public void ignoresNonNumericFieldsGracefully() {
        String json = "{\"prompt_tokens\":\"not-a-number\",\"completion_tokens\":5,\"total_tokens\":5}"; //$NON-NLS-1$

        LlmResponse.Usage usage = OpenAiUsageParser.parse(json);

        // Malformed prompt_tokens is tolerated (treated as 0), completion/total are preserved.
        assertEquals(0, usage.getPromptTokens());
        assertEquals(5, usage.getCompletionTokens());
        assertEquals(5, usage.getTotalTokens());
    }

    @Test
    public void ignoresMalformedCachedTokensDetails() {
        String json = "{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15," //$NON-NLS-1$
                + "\"prompt_tokens_details\":\"bogus\"}"; //$NON-NLS-1$

        LlmResponse.Usage usage = OpenAiUsageParser.parse(json);

        assertEquals(10, usage.getPromptTokens());
        assertEquals(0, usage.getCachedPromptTokens());
        assertEquals(5, usage.getCompletionTokens());
        assertEquals(15, usage.getTotalTokens());
    }

    // --- Cache-token normalization (backend expanded schema) ---

    @Test
    public void cacheReadFallsBackToPromptDetailsCached() {
        // Only prompt_tokens_details.cached_tokens present.
        String json = "{\"prompt_tokens\":120,\"completion_tokens\":30,\"total_tokens\":150," //$NON-NLS-1$
                + "\"prompt_tokens_details\":{\"cached_tokens\":60}}"; //$NON-NLS-1$

        LlmResponse.Usage usage = OpenAiUsageParser.parse(json);

        assertEquals(60, usage.getCachedPromptTokens());
        // cache_read_input_tokens absent → falls back to cached_tokens (60)
        assertEquals(60, usage.getCacheReadInputTokens());
        assertEquals(0, usage.getCacheCreationInputTokens());
    }

    @Test
    public void cacheReadOnlyPopulatesBothAliases() {
        // Only cache_read_input_tokens at the top level (no prompt_tokens_details).
        String json = "{\"prompt_tokens\":200,\"completion_tokens\":40,\"total_tokens\":240," //$NON-NLS-1$
                + "\"cache_read_input_tokens\":75}"; //$NON-NLS-1$

        LlmResponse.Usage usage = OpenAiUsageParser.parse(json);

        // cached_tokens absent → cachedPromptTokens falls back to cache_read_input_tokens.
        assertEquals(75, usage.getCachedPromptTokens());
        assertEquals(75, usage.getCacheReadInputTokens());
        assertEquals(0, usage.getCacheCreationInputTokens());
    }

    @Test
    public void bothCacheFieldsAndCreation() {
        // All three cache markers present.
        String json = "{\"prompt_tokens\":500,\"completion_tokens\":80,\"total_tokens\":580," //$NON-NLS-1$
                + "\"prompt_tokens_details\":{\"cached_tokens\":200}," //$NON-NLS-1$
                + "\"cache_read_input_tokens\":200,\"cache_creation_input_tokens\":50}"; //$NON-NLS-1$

        LlmResponse.Usage usage = OpenAiUsageParser.parse(json);

        assertEquals(500, usage.getPromptTokens());
        assertEquals(80, usage.getCompletionTokens());
        assertEquals(200, usage.getCachedPromptTokens());
        assertEquals(200, usage.getCacheReadInputTokens());
        assertEquals(50, usage.getCacheCreationInputTokens());
    }

    @Test
    public void legacyResponseHasNoCacheMarkers() {
        String json = "{\"prompt_tokens\":100,\"completion_tokens\":25,\"total_tokens\":125}"; //$NON-NLS-1$

        LlmResponse.Usage usage = OpenAiUsageParser.parse(json);

        assertEquals(100, usage.getPromptTokens());
        assertEquals(25, usage.getCompletionTokens());
        assertEquals(0, usage.getCachedPromptTokens());
        assertEquals(0, usage.getCacheReadInputTokens());
        assertEquals(0, usage.getCacheCreationInputTokens());
    }

    @Test
    public void promptDetailsCachedTakesPriorityOverTopLevelCacheRead() {
        // prompt_tokens_details.cached_tokens is the priority source per spec.
        String json = "{\"prompt_tokens\":400,\"completion_tokens\":60,\"total_tokens\":460," //$NON-NLS-1$
                + "\"prompt_tokens_details\":{\"cached_tokens\":150}," //$NON-NLS-1$
                + "\"cache_read_input_tokens\":999}"; //$NON-NLS-1$

        LlmResponse.Usage usage = OpenAiUsageParser.parse(json);

        // Legacy alias uses the priority source (prompt_tokens_details.cached_tokens).
        assertEquals(150, usage.getCachedPromptTokens());
        // New explicit field preserves the top-level cache_read_input_tokens as-is.
        assertEquals(999, usage.getCacheReadInputTokens());
    }

    @Test
    public void cacheCreationOnlyIsPreserved() {
        String json = "{\"prompt_tokens\":50,\"completion_tokens\":10,\"total_tokens\":60," //$NON-NLS-1$
                + "\"cache_creation_input_tokens\":25}"; //$NON-NLS-1$

        LlmResponse.Usage usage = OpenAiUsageParser.parse(json);

        assertEquals(0, usage.getCachedPromptTokens());
        assertEquals(0, usage.getCacheReadInputTokens());
        assertEquals(25, usage.getCacheCreationInputTokens());
    }
}
