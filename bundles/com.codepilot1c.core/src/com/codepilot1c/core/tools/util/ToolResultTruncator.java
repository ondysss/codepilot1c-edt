/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Shared helpers that cap oversized tool output so tool results do not
 * inflate the conversation token budget.
 *
 * <p>Two strategies:</p>
 * <ul>
 *   <li>{@link #truncateText(String, int)} &mdash; trims a raw text/markdown
 *       payload to at most {@code maxChars}, keeping a head (5/8 of the cap)
 *       and a tail (2/8 of the cap) with an explicit truncation marker.</li>
 *   <li>{@link #truncateJsonField(JsonObject, String, int)} &mdash; trims one
 *       string-valued field inside a structured JSON payload and adds sibling
 *       meta fields that describe the truncation so the consumer can still
 *       detect and handle the drop.</li>
 * </ul>
 *
 * <p>The head/tail split follows a parametric 5/8 + 2/8 ratio of {@code maxChars}
 * (the remaining 1/8 is reserved for the inserted marker). For the canonical
 * 40000-char cap this yields 25000 leading chars and 10000 trailing chars.</p>
 */
public final class ToolResultTruncator {

    /** Head ratio expressed as numerator over {@link #RATIO_DENOMINATOR}. */
    private static final int HEAD_RATIO_NUM = 5;

    /** Tail ratio expressed as numerator over {@link #RATIO_DENOMINATOR}. */
    private static final int TAIL_RATIO_NUM = 2;

    /** Common ratio denominator; 5/8 head + 2/8 tail + 1/8 marker budget. */
    private static final int RATIO_DENOMINATOR = 8;

    private ToolResultTruncator() {
        // utility class
    }

    /**
     * Returns {@code text} as is when it is {@code null} or already within the cap,
     * otherwise returns a concatenation of the first {@code maxChars * 5/8} chars,
     * a human-readable truncation marker and the last {@code maxChars * 2/8} chars.
     *
     * @param text     the text to truncate (may be {@code null})
     * @param maxChars the cap in characters; must be positive to take effect
     * @return the original or truncated text
     */
    public static String truncateText(String text, int maxChars) {
        if (text == null || maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        int head = (maxChars * HEAD_RATIO_NUM) / RATIO_DENOMINATOR;
        int tail = (maxChars * TAIL_RATIO_NUM) / RATIO_DENOMINATOR;
        if (head < 0) {
            head = 0;
        }
        if (tail < 0) {
            tail = 0;
        }
        // Defensive clamp so head+tail never exceed the original length.
        if (head + tail >= text.length()) {
            return text;
        }
        int dropped = text.length() - head - tail;
        StringBuilder sb = new StringBuilder(head + tail + 64);
        sb.append(text, 0, head);
        sb.append("\n[... truncated ").append(dropped).append(" chars ...]\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(text, text.length() - tail, text.length());
        return sb.toString();
    }

    /**
     * Truncates a single string-valued field inside {@code obj} in place.
     *
     * <p>If the field is missing, not a string, or its value is shorter than
     * {@code maxChars} the object is returned unchanged. Otherwise the field
     * value is replaced with a head+marker+tail excerpt, and the following
     * sibling fields are added to the same object so consumers can detect
     * the truncation:</p>
     *
     * <ul>
     *   <li>{@code truncated: true}</li>
     *   <li>{@code original_length: N}</li>
     *   <li>{@code body_excerpt_head: H}</li>
     *   <li>{@code body_excerpt_tail: T}</li>
     * </ul>
     *
     * @param obj       the structured payload (mutated in place; must not be {@code null})
     * @param fieldName the name of the string field to cap
     * @param maxChars  the cap in characters
     * @return the same {@code obj} reference for chaining
     */
    public static JsonObject truncateJsonField(JsonObject obj, String fieldName, int maxChars) {
        if (obj == null || fieldName == null || maxChars <= 0) {
            return obj;
        }
        JsonElement element = obj.get(fieldName);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            return obj;
        }
        String value = element.getAsString();
        if (value == null || value.length() <= maxChars) {
            return obj;
        }
        int head = (maxChars * HEAD_RATIO_NUM) / RATIO_DENOMINATOR;
        int tail = (maxChars * TAIL_RATIO_NUM) / RATIO_DENOMINATOR;
        if (head < 0) {
            head = 0;
        }
        if (tail < 0) {
            tail = 0;
        }
        if (head + tail >= value.length()) {
            return obj;
        }
        int originalLength = value.length();
        int dropped = originalLength - head - tail;
        StringBuilder sb = new StringBuilder(head + tail + 64);
        sb.append(value, 0, head);
        sb.append("\n[... truncated ").append(dropped).append(" chars ...]\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(value, originalLength - tail, originalLength);
        obj.addProperty(fieldName, sb.toString());
        obj.addProperty("truncated", true); //$NON-NLS-1$
        obj.addProperty("original_length", originalLength); //$NON-NLS-1$
        obj.addProperty("body_excerpt_head", head); //$NON-NLS-1$
        obj.addProperty("body_excerpt_tail", tail); //$NON-NLS-1$
        return obj;
    }
}
