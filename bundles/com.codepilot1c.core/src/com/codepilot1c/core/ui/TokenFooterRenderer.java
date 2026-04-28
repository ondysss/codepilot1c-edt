/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.ui;

/**
 * Pure render helper for the compact token-usage footer shown in the chat
 * panel (Plan 2.4 of the token-reduction program).
 *
 * <p>Extracted from the UI bundle so it can be unit-tested without PDE/SWT
 * scaffolding. The UI bundle calls {@link #renderFooterHtml} and injects the
 * resulting fragment via its browser JS channel.</p>
 *
 * <p>Format (Russian locale, matches current chat UI aesthetic):</p>
 * <pre>Сессия: 152k ↑ · 18k ↓ · 120k cache · 8 запр.</pre>
 *
 * <p>Numeric compaction rules (see {@link #formatCompact}):</p>
 * <ul>
 *   <li>{@code n < 1_000} rendered as-is</li>
 *   <li>{@code 1_000 <= n < 1_000_000} rendered as {@code ${n/1_000}k}</li>
 *   <li>{@code n >= 1_000_000} rendered as {@code ${n/1_000_000}M}</li>
 * </ul>
 *
 * <p>Negative values are clamped to 0 for rendering purposes.</p>
 */
public final class TokenFooterRenderer {

    /** Stable element id so repeated calls update in place. */
    public static final String FOOTER_ELEMENT_ID = "token-footer"; //$NON-NLS-1$

    private TokenFooterRenderer() {
    }

    /**
     * Builds the HTML fragment for the token-usage footer.
     *
     * <p>Callers are expected to inject or replace the element with id
     * {@link #FOOTER_ELEMENT_ID} inside the chat scroll container.</p>
     *
     * @param inputTotal    accumulated prompt tokens for the session
     * @param cachedTotal   accumulated cached prompt tokens for the session
     * @param outputTotal   accumulated completion tokens for the session
     * @param totalAll      accumulated grand total reported by the provider
     *                      (may diverge from {@code inputTotal + outputTotal}
     *                      when the provider reports a composite total);
     *                      exposed in the element's {@code title} attribute
     *                      for diagnostic hover
     * @param requestCount  number of accepted top-level round-trips in this
     *                      chat session
     * @return HTML snippet ready to assign to {@code element.outerHTML}
     */
    public static String renderFooterHtml(long inputTotal,
                                          long cachedTotal,
                                          long outputTotal,
                                          long totalAll,
                                          int requestCount) {
        String input = formatCompact(inputTotal);
        String output = formatCompact(outputTotal);
        String cached = formatCompact(cachedTotal);
        String reqs = Integer.toString(Math.max(0, requestCount));

        StringBuilder sb = new StringBuilder(200);
        sb.append("<div id=\"") //$NON-NLS-1$
          .append(FOOTER_ELEMENT_ID)
          .append("\" class=\"token-footer\" title=\"total=") //$NON-NLS-1$
          .append(Math.max(0L, totalAll))
          .append("\">") //$NON-NLS-1$
          .append("<span class=\"tf-label\">Сессия:</span> ") //$NON-NLS-1$
          .append("<span class=\"tf-in\">").append(input).append("\u00A0\u2191</span>") //$NON-NLS-1$ //$NON-NLS-2$
          .append(" \u00B7 ") //$NON-NLS-1$
          .append("<span class=\"tf-out\">").append(output).append("\u00A0\u2193</span>") //$NON-NLS-1$ //$NON-NLS-2$
          .append(" \u00B7 ") //$NON-NLS-1$
          .append("<span class=\"tf-cache\">").append(cached).append("\u00A0cache</span>") //$NON-NLS-1$ //$NON-NLS-2$
          .append(" \u00B7 ") //$NON-NLS-1$
          .append("<span class=\"tf-reqs\">").append(reqs).append("\u00A0\u0437\u0430\u043F\u0440.</span>") //$NON-NLS-1$ //$NON-NLS-2$
          .append("</div>"); //$NON-NLS-1$
        return sb.toString();
    }

    /**
     * Compact count formatter.
     *
     * <ul>
     *   <li>{@code n < 1_000} → decimal digits of {@code n}</li>
     *   <li>{@code 1_000 <= n < 1_000_000} → {@code ${n/1_000}k}</li>
     *   <li>{@code n >= 1_000_000} → {@code ${n/1_000_000}M}</li>
     * </ul>
     *
     * <p>Rounding is floor toward zero (integer division).</p>
     *
     * @param n the value; negatives are clamped to 0
     * @return the compact string representation
     */
    public static String formatCompact(long n) {
        long v = Math.max(0L, n);
        if (v < 1_000L) {
            return Long.toString(v);
        }
        if (v < 1_000_000L) {
            return (v / 1_000L) + "k"; //$NON-NLS-1$
        }
        return (v / 1_000_000L) + "M"; //$NON-NLS-1$
    }
}
