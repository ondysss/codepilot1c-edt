/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.backend;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Exercises {@link BackendService#parseUsageInfo(JsonObject)} against both
 * legacy and expanded usage payloads to verify the fallback strategy.
 */
public class BackendServiceUsageParserTest {

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    public void parsesFullExpandedSchema() {
        JsonObject json = obj("{" //$NON-NLS-1$
                + "\"spend\":12.5,\"max_budget\":100.0,\"total_tokens\":1500," //$NON-NLS-1$
                + "\"input_tokens_total\":1000,\"input_tokens_uncached\":700," //$NON-NLS-1$
                + "\"input_tokens_cached\":300,\"output_tokens\":500," //$NON-NLS-1$
                + "\"cache_read_input_tokens\":300,\"cache_creation_input_tokens\":50," //$NON-NLS-1$
                + "\"prompt_tokens\":999,\"completion_tokens\":888," //$NON-NLS-1$
                + "\"budget_duration\":\"monthly\",\"budget_reset_at\":\"2026-05-01\"}"); //$NON-NLS-1$

        UsageInfo usage = BackendService.parseUsageInfo(json);

        assertEquals(12.5, usage.getSpend(), 0.001);
        assertEquals(100.0, usage.getMaxBudget(), 0.001);
        assertEquals(1500L, usage.getTotalTokens());
        assertEquals(1000L, usage.getInputTokensTotal());
        assertEquals(700L, usage.getInputTokensUncached());
        assertEquals(300L, usage.getInputTokensCached());
        assertEquals(500L, usage.getOutputTokens());
        assertEquals(300L, usage.getCacheReadInputTokens());
        assertEquals(50L, usage.getCacheCreationInputTokens());

        // Aliases: new fields win over legacy.
        assertEquals(1000L, usage.getPromptTokens());
        assertEquals(500L, usage.getCompletionTokens());
        assertEquals("monthly", usage.getBudgetDuration()); //$NON-NLS-1$
        assertEquals("2026-05-01", usage.getResetDate()); //$NON-NLS-1$
    }

    @Test
    public void parsesLegacySchemaWithFallback() {
        JsonObject json = obj("{" //$NON-NLS-1$
                + "\"spend\":5.0,\"max_budget\":50.0,\"total_tokens\":300," //$NON-NLS-1$
                + "\"prompt_tokens\":200,\"completion_tokens\":100," //$NON-NLS-1$
                + "\"budget_duration\":\"monthly\"}"); //$NON-NLS-1$

        UsageInfo usage = BackendService.parseUsageInfo(json);

        // Legacy values populate both legacy and new-style accessors.
        assertEquals(200L, usage.getPromptTokens());
        assertEquals(100L, usage.getCompletionTokens());

        // inputTokensTotal is filled from prompt_tokens as the fallback.
        assertEquals(200L, usage.getInputTokensTotal());
        // Uncached falls back to the total when no cache info is present.
        assertEquals(200L, usage.getInputTokensUncached());
        assertEquals(0L, usage.getInputTokensCached());
        assertEquals(100L, usage.getOutputTokens());
        assertEquals(0L, usage.getCacheReadInputTokens());
        assertEquals(0L, usage.getCacheCreationInputTokens());
    }

    @Test
    public void parsesMixedSchemaPartialNewFields() {
        // Backend already upgraded input_* but older cache fields absent
        JsonObject json = obj("{" //$NON-NLS-1$
                + "\"input_tokens_total\":800,\"input_tokens_cached\":200," //$NON-NLS-1$
                + "\"output_tokens\":400," //$NON-NLS-1$
                + "\"prompt_tokens\":800,\"completion_tokens\":400}"); //$NON-NLS-1$

        UsageInfo usage = BackendService.parseUsageInfo(json);

        assertEquals(800L, usage.getInputTokensTotal());
        // Uncached not present → falls back to total.
        assertEquals(800L, usage.getInputTokensUncached());
        assertEquals(200L, usage.getInputTokensCached());
        assertEquals(400L, usage.getOutputTokens());
        // cache_read_input_tokens missing → falls back to input_tokens_cached.
        assertEquals(200L, usage.getCacheReadInputTokens());
        assertEquals(0L, usage.getCacheCreationInputTokens());

        // Legacy aliases reflect new values.
        assertEquals(800L, usage.getPromptTokens());
        assertEquals(400L, usage.getCompletionTokens());
    }

    @Test
    public void newSchemaTakesPrecedenceOverLegacyWhenBothPresent() {
        JsonObject json = obj("{" //$NON-NLS-1$
                + "\"input_tokens_total\":1234,\"output_tokens\":567," //$NON-NLS-1$
                + "\"prompt_tokens\":1,\"completion_tokens\":2}"); //$NON-NLS-1$

        UsageInfo usage = BackendService.parseUsageInfo(json);

        assertEquals(1234L, usage.getPromptTokens());
        assertEquals(567L, usage.getCompletionTokens());
    }

    @Test
    public void emptyPayloadYieldsZeroUsage() {
        UsageInfo usage = BackendService.parseUsageInfo(obj("{}")); //$NON-NLS-1$

        assertEquals(0L, usage.getInputTokensTotal());
        assertEquals(0L, usage.getOutputTokens());
        assertEquals(0L, usage.getPromptTokens());
        assertEquals(0L, usage.getCompletionTokens());
        assertEquals(0L, usage.getCacheReadInputTokens());
        assertEquals(0L, usage.getCacheCreationInputTokens());
    }
}
