/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider.config;

import com.codepilot1c.core.model.LlmResponse;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Parses an OpenAI-compatible {@code usage} JSON object into
 * {@link LlmResponse.Usage}.
 *
 * <p>Handles the terminal usage chunk emitted when a streaming request is sent
 * with {@code stream_options: {include_usage: true}}. Shape:</p>
 * <pre>
 * {
 *   "prompt_tokens": 123,
 *   "completion_tokens": 45,
 *   "total_tokens": 168,
 *   "prompt_tokens_details": { "cached_tokens": 50 },
 *   "cache_read_input_tokens": 50,
 *   "cache_creation_input_tokens": 10
 * }
 * </pre>
 *
 * <p>Cache-token normalization (matches the backend contract):</p>
 * <ul>
 *   <li>{@code cachedPromptTokens} = {@code prompt_tokens_details.cached_tokens}
 *       (priority) &rarr; {@code cache_read_input_tokens} (fallback) &rarr; 0.</li>
 *   <li>{@code cacheReadInputTokens} = {@code cache_read_input_tokens} (priority)
 *       &rarr; same value as {@code cachedPromptTokens} (fallback).</li>
 *   <li>{@code cacheCreationInputTokens} = {@code cache_creation_input_tokens}
 *       when present, otherwise 0.</li>
 * </ul>
 *
 * <p>Missing fields are treated as {@code 0}. The parser never throws — invalid
 * inputs (null, empty, malformed JSON, wrong types) yield {@code null}.</p>
 */
public final class OpenAiUsageParser {

    private OpenAiUsageParser() {
    }

    /**
     * Parses a usage JSON object. Accepts either a pre-parsed {@link JsonObject}
     * (as attached to an OpenAI chunk) or a JSON string.
     *
     * @param usageObject the usage object; may be {@code null}
     * @return the parsed usage, or {@code null} when input is absent/invalid
     */
    public static LlmResponse.Usage parse(JsonObject usageObject) {
        if (usageObject == null) {
            return null;
        }
        try {
            int promptTokens = readInt(usageObject, "prompt_tokens"); //$NON-NLS-1$
            int completionTokens = readInt(usageObject, "completion_tokens"); //$NON-NLS-1$
            int totalTokens = readInt(usageObject, "total_tokens"); //$NON-NLS-1$

            int promptDetailsCached = 0;
            JsonElement detailsElement = usageObject.has("prompt_tokens_details") //$NON-NLS-1$
                    ? usageObject.get("prompt_tokens_details") //$NON-NLS-1$
                    : null;
            if (detailsElement != null && detailsElement.isJsonObject()) {
                promptDetailsCached = readInt(detailsElement.getAsJsonObject(), "cached_tokens"); //$NON-NLS-1$
            }

            int cacheReadRaw = readInt(usageObject, "cache_read_input_tokens"); //$NON-NLS-1$
            int cacheCreationRaw = readInt(usageObject, "cache_creation_input_tokens"); //$NON-NLS-1$

            int cachedPromptTokens = promptDetailsCached > 0 ? promptDetailsCached : cacheReadRaw;
            int cacheReadInputTokens = cacheReadRaw > 0 ? cacheReadRaw : cachedPromptTokens;
            int cacheCreationInputTokens = cacheCreationRaw;

            if (totalTokens <= 0 && (promptTokens > 0 || completionTokens > 0)) {
                totalTokens = promptTokens + completionTokens;
            }

            if (promptTokens == 0 && completionTokens == 0 && totalTokens == 0
                    && cachedPromptTokens == 0 && cacheReadInputTokens == 0 && cacheCreationInputTokens == 0) {
                return null;
            }

            return new LlmResponse.Usage(promptTokens, cachedPromptTokens, completionTokens, totalTokens,
                    cacheReadInputTokens, cacheCreationInputTokens);
        } catch (Exception e) {
            // Defensive: never throw on malformed inputs.
            return null;
        }
    }

    /**
     * Parses a usage JSON string. Returns {@code null} on null/empty/malformed
     * input.
     *
     * @param usageJson the raw JSON string of the usage object; may be {@code null}
     * @return the parsed usage, or {@code null}
     */
    public static LlmResponse.Usage parse(String usageJson) {
        if (usageJson == null || usageJson.isBlank()) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(usageJson);
            if (element == null || element.isJsonNull() || !element.isJsonObject()) {
                return null;
            }
            return parse(element.getAsJsonObject());
        } catch (Exception e) {
            return null;
        }
    }

    private static int readInt(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key)) {
            return 0;
        }
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return 0;
        }
        try {
            return element.getAsInt();
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return 0;
        }
    }
}
