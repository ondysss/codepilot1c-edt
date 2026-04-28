/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.eclipse.core.runtime.Platform;

import com.codepilot1c.core.settings.VibePreferenceConstants;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Detects tool-call repetition loops where the agent calls the same tool with
 * identical arguments multiple times within a rolling window (Plan 1.2).
 *
 * <p>The loop owner ({@code ChatView} / {@link AgentRunner}) invokes
 * {@link #observe(String, String)} before dispatching each tool call. When a
 * threshold of identical {@code (toolName, sha256(canonicalArgsJson))} keys is
 * hit inside the last {@code N} calls, the detector returns a {@link Trip} so
 * the caller can inject a synthetic USER message pushing the model to summarise
 * findings and change strategy. After tripping, the internal window is cleared
 * so the next call starts fresh — a trip requires another full threshold of
 * identical calls to fire again.</p>
 *
 * <p>The detector is instance-scoped (one per chat view / per agent run) and
 * thread-safe: {@link #observe} and {@link #resetForNewTurn} are synchronized
 * on the instance.</p>
 */
public final class ToolRepetitionDetector {

    private static final String PLUGIN_ID = "com.codepilot1c.core"; //$NON-NLS-1$

    /** Default rolling window size (number of last tool calls considered). */
    public static final int DEFAULT_WINDOW_SIZE = 10;

    /** Default threshold of identical calls inside the window that trips the detector. */
    public static final int DEFAULT_THRESHOLD = 5;

    /** Hard lower bound on both window and threshold to guarantee sensible behaviour. */
    private static final int MIN_VALUE = 2;

    /** Hard upper bound to prevent abusively large windows from piling memory. */
    private static final int MAX_VALUE = 1000;

    private final int windowSize;
    private final int threshold;

    /** FIFO of recent keys, oldest first. Access guarded by {@code this}. */
    private final Deque<String> recentKeys = new ArrayDeque<>();

    public ToolRepetitionDetector() {
        this(resolveWindowSize(), resolveThreshold());
    }

    /**
     * Package-visible constructor for tests to pin both values deterministically.
     */
    ToolRepetitionDetector(int windowSize, int threshold) {
        this.windowSize = clamp(windowSize);
        this.threshold = clamp(Math.min(threshold, this.windowSize));
    }

    /**
     * Observes a tool call.
     *
     * <p>Called by the loop owner just before dispatching a tool. Returns
     * {@link Optional#empty()} during normal operation; returns
     * {@link Optional#of(Object) Optional.of(Trip)} exactly once when the same
     * {@code (toolName, canonicalArgsJson)} key has been observed
     * {@link #threshold} times inside the last {@link #windowSize} calls.
     * Tripping resets the window — the next {@code observe} call starts fresh.</p>
     *
     * @param toolName           tool name as sent to the LLM, never {@code null}
     * @param canonicalArgsJson  canonicalised JSON arguments string (see
     *                           {@link #canonicalizeArgs(JsonObject)}); may be
     *                           any string — the detector hashes it verbatim
     * @return the {@link Trip} if threshold exceeded, otherwise empty
     */
    public synchronized Optional<Trip> observe(String toolName, String canonicalArgsJson) {
        if (toolName == null || toolName.isEmpty()) {
            return Optional.empty();
        }
        String key = buildKey(toolName, canonicalArgsJson != null ? canonicalArgsJson : ""); //$NON-NLS-1$
        recentKeys.addLast(key);
        while (recentKeys.size() > windowSize) {
            recentKeys.pollFirst();
        }

        int identical = 0;
        for (String stored : recentKeys) {
            if (stored.equals(key)) {
                identical++;
            }
        }

        if (identical >= threshold) {
            Trip trip = new Trip(toolName, identical);
            // Clear window so the next call is a clean slate.
            recentKeys.clear();
            return Optional.of(trip);
        }
        return Optional.empty();
    }

    /**
     * Resets the detector window. Call at each turn boundary (clear chat,
     * new user message, new agent run).
     */
    public synchronized void resetForNewTurn() {
        recentKeys.clear();
    }

    public int getWindowSize() {
        return windowSize;
    }

    public int getThreshold() {
        return threshold;
    }

    /**
     * Canonicalises a JSON object into a deterministic string by sorting keys
     * alphabetically at every level. Used as the input to SHA-256 hashing so
     * that {@code {"a":1,"b":2}} and {@code {"b":2,"a":1}} hash identically.
     *
     * <p>{@code null} input returns the literal string {@code "null"}. Gson
     * preserves number formatting, which is acceptable here because the LLM
     * typically emits numbers the same way when the arguments are semantically
     * identical.</p>
     *
     * @param args JSON object; may be {@code null}
     * @return canonical string form
     */
    public static String canonicalizeArgs(JsonObject args) {
        if (args == null) {
            return "null"; //$NON-NLS-1$
        }
        StringBuilder sb = new StringBuilder();
        appendCanonical(sb, args);
        return sb.toString();
    }

    private static void appendCanonical(StringBuilder sb, JsonElement element) {
        if (element == null || element.isJsonNull()) {
            sb.append("null"); //$NON-NLS-1$
            return;
        }
        if (element.isJsonPrimitive()) {
            // toString() already quotes strings and formats numbers deterministically.
            sb.append(element.toString());
            return;
        }
        if (element.isJsonArray()) {
            JsonArray arr = element.getAsJsonArray();
            sb.append('[');
            for (int i = 0; i < arr.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                appendCanonical(sb, arr.get(i));
            }
            sb.append(']');
            return;
        }
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            List<String> keys = new ArrayList<>(obj.keySet());
            Collections.sort(keys);
            sb.append('{');
            boolean first = true;
            for (String key : keys) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(escape(key)).append('"').append(':');
                appendCanonical(sb, obj.get(key));
            }
            sb.append('}');
        }
    }

    private static String escape(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 2);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break; //$NON-NLS-1$
                case '\\': sb.append("\\\\"); break; //$NON-NLS-1$
                case '\n': sb.append("\\n"); break; //$NON-NLS-1$
                case '\r': sb.append("\\r"); break; //$NON-NLS-1$
                case '\t': sb.append("\\t"); break; //$NON-NLS-1$
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c)); //$NON-NLS-1$
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private static String buildKey(String toolName, String canonicalArgsJson) {
        String hash = sha256Hex(canonicalArgsJson);
        return toolName + "\u0000" + hash; //$NON-NLS-1$
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); //$NON-NLS-1$
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format(Locale.ROOT, "%02x", Byte.valueOf(b))); //$NON-NLS-1$
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be present on every JRE. Fall back to the
            // raw string so the detector still works (less collision-resistant
            // but identical equal-to-equal matching still holds).
            return "fallback:" + value; //$NON-NLS-1$
        }
    }

    private static int resolveWindowSize() {
        return readIntPref(VibePreferenceConstants.TOOL_REPETITION_WINDOW_SIZE, DEFAULT_WINDOW_SIZE);
    }

    private static int resolveThreshold() {
        return readIntPref(VibePreferenceConstants.TOOL_REPETITION_THRESHOLD, DEFAULT_THRESHOLD);
    }

    private static int readIntPref(String key, int fallback) {
        try {
            return Platform.getPreferencesService().getInt(PLUGIN_ID, key, fallback, null);
        } catch (RuntimeException ignored) {
            // Plain JUnit (non-OSGi) runs come here. Fall back to the constant.
            return fallback;
        }
    }

    private static int clamp(int value) {
        if (value < MIN_VALUE) {
            return MIN_VALUE;
        }
        if (value > MAX_VALUE) {
            return MAX_VALUE;
        }
        return value;
    }

    /**
     * Payload returned by {@link #observe} when the repetition threshold is
     * exceeded. Carries the tripped tool name, the identical-call count that
     * triggered the trip, and a pre-formatted localized message suitable for
     * injecting directly into the conversation as a USER turn.
     */
    public static final class Trip {
        public final String toolName;
        public final int identicalCount;

        Trip(String toolName, int identicalCount) {
            this.toolName = toolName;
            this.identicalCount = identicalCount;
        }

        /**
         * Returns a user-visible message using the active locale. Format is
         * stable across runs to help operators grep logs.
         */
        public String localizedMessage() {
            Locale locale = Locale.getDefault();
            String language = locale != null && locale.getLanguage() != null
                    ? locale.getLanguage()
                    : ""; //$NON-NLS-1$
            if ("ru".equalsIgnoreCase(language)) { //$NON-NLS-1$
                return String.format(
                        "Вы вызвали `%s` с одинаковыми аргументами %d раз. Подведите итог находок и выберите другое действие.", //$NON-NLS-1$
                        toolName, Integer.valueOf(identicalCount));
            }
            return String.format(
                    "You have called `%s` with identical arguments %d times. Summarise findings and choose a different action.", //$NON-NLS-1$
                    toolName, Integer.valueOf(identicalCount));
        }

        @Override
        public String toString() {
            return "Trip[tool=" + toolName + ", count=" + identicalCount + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
    }

    // Exposed for tests that want to inspect the current window snapshot.
    synchronized Map<String, Integer> snapshotCounts() {
        Map<String, Integer> counts = new HashMap<>();
        for (String key : recentKeys) {
            counts.merge(key, Integer.valueOf(1), (a, b) -> Integer.valueOf(a.intValue() + b.intValue()));
        }
        return counts;
    }
}
