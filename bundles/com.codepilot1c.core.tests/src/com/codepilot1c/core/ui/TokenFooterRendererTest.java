/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link TokenFooterRenderer} (Plan 2.4).
 *
 * <p>These exercise the pure formatting/rendering logic so the full SWT
 * browser round-trip does not need to be reproduced in tests. The UI bundle
 * layers Browser.execute() on top of the HTML produced here; if this output
 * is correct and the injector call-site is plumbed, the footer is visually
 * correct.</p>
 */
public class TokenFooterRendererTest {

    @Test
    public void formatCompact_smallValuesAreLiteral() {
        assertEquals("0", TokenFooterRenderer.formatCompact(0L)); //$NON-NLS-1$
        assertEquals("1", TokenFooterRenderer.formatCompact(1L)); //$NON-NLS-1$
        assertEquals("42", TokenFooterRenderer.formatCompact(42L)); //$NON-NLS-1$
        assertEquals("999", TokenFooterRenderer.formatCompact(999L)); //$NON-NLS-1$
    }

    @Test
    public void formatCompact_thousandBoundary() {
        assertEquals("1k", TokenFooterRenderer.formatCompact(1_000L)); //$NON-NLS-1$
        assertEquals("1k", TokenFooterRenderer.formatCompact(1_001L)); //$NON-NLS-1$
        assertEquals("1k", TokenFooterRenderer.formatCompact(1_999L)); //$NON-NLS-1$
        assertEquals("2k", TokenFooterRenderer.formatCompact(2_000L)); //$NON-NLS-1$
        assertEquals("152k", TokenFooterRenderer.formatCompact(152_345L)); //$NON-NLS-1$
        assertEquals("999k", TokenFooterRenderer.formatCompact(999_999L)); //$NON-NLS-1$
    }

    @Test
    public void formatCompact_millionBoundary() {
        assertEquals("1M", TokenFooterRenderer.formatCompact(1_000_000L)); //$NON-NLS-1$
        assertEquals("1M", TokenFooterRenderer.formatCompact(1_999_999L)); //$NON-NLS-1$
        assertEquals("2M", TokenFooterRenderer.formatCompact(2_000_000L)); //$NON-NLS-1$
        assertEquals("12M", TokenFooterRenderer.formatCompact(12_345_678L)); //$NON-NLS-1$
    }

    @Test
    public void formatCompact_negativeClampsToZero() {
        assertEquals("0", TokenFooterRenderer.formatCompact(-1L)); //$NON-NLS-1$
        assertEquals("0", TokenFooterRenderer.formatCompact(Long.MIN_VALUE)); //$NON-NLS-1$
    }

    @Test
    public void renderFooterHtml_containsAllFiveMetrics() {
        String html = TokenFooterRenderer.renderFooterHtml(152_345L, 120_000L, 18_000L, 170_345L, 8);

        // Structural: has the persistent element id.
        assertTrue("missing stable id: " + html, //$NON-NLS-1$
                html.contains("id=\"token-footer\"")); //$NON-NLS-1$

        // Metric 1: input total (compact)
        assertTrue("missing input: " + html, html.contains("152k")); //$NON-NLS-1$ //$NON-NLS-2$
        // Metric 2: output total (compact)
        assertTrue("missing output: " + html, html.contains("18k")); //$NON-NLS-1$ //$NON-NLS-2$
        // Metric 3: cache total (compact)
        assertTrue("missing cache: " + html, html.contains("120k")); //$NON-NLS-1$ //$NON-NLS-2$
        // Metric 4: grand total in title attribute
        assertTrue("missing total in title: " + html, //$NON-NLS-1$
                html.contains("title=\"total=170345\"")); //$NON-NLS-1$
        // Metric 5: request count
        assertTrue("missing request count: " + html, html.contains(">8")); //$NON-NLS-1$ //$NON-NLS-2$

        // Localization: Russian label and unit.
        assertTrue("missing Russian session label: " + html, //$NON-NLS-1$
                html.contains("Сессия:")); //$NON-NLS-1$
        assertTrue("missing Russian requests unit: " + html, //$NON-NLS-1$
                html.contains("запр.")); //$NON-NLS-1$
        // Arrow markers.
        assertTrue("missing up-arrow: " + html, html.contains("\u2191")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("missing down-arrow: " + html, html.contains("\u2193")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void renderFooterHtml_edgeValuesRenderZero() {
        String html = TokenFooterRenderer.renderFooterHtml(0L, 0L, 0L, 0L, 0);
        assertTrue("zero state should contain id: " + html, //$NON-NLS-1$
                html.contains("id=\"token-footer\"")); //$NON-NLS-1$
        // All metrics appear as "0".
        assertTrue("should contain input 0: " + html, html.contains(">0")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("title total should be 0: " + html, //$NON-NLS-1$
                html.contains("title=\"total=0\"")); //$NON-NLS-1$
    }

    @Test
    public void renderFooterHtml_thresholdValuesRenderCorrectly() {
        String h999 = TokenFooterRenderer.renderFooterHtml(999L, 999L, 999L, 999L, 1);
        assertTrue("999 should stay literal: " + h999, h999.contains(">999")); //$NON-NLS-1$ //$NON-NLS-2$

        String h1000 = TokenFooterRenderer.renderFooterHtml(1_000L, 1_000L, 1_000L, 1_000L, 1);
        assertTrue("1000 should become 1k: " + h1000, h1000.contains("1k")); //$NON-NLS-1$ //$NON-NLS-2$

        String h999999 = TokenFooterRenderer.renderFooterHtml(999_999L, 999_999L, 999_999L, 999_999L, 1);
        assertTrue("999999 should become 999k: " + h999999, h999999.contains("999k")); //$NON-NLS-1$ //$NON-NLS-2$

        String h1M = TokenFooterRenderer.renderFooterHtml(1_000_000L, 1_000_000L, 1_000_000L, 1_000_000L, 1);
        assertTrue("1_000_000 should become 1M: " + h1M, h1M.contains("1M")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void renderFooterHtml_negativeTotalClampsInTitle() {
        String html = TokenFooterRenderer.renderFooterHtml(-10L, -10L, -10L, -10L, -5);
        assertTrue("negative total should clamp to 0 in title: " + html, //$NON-NLS-1$
                html.contains("title=\"total=0\"")); //$NON-NLS-1$
        assertTrue("negative request count should clamp to 0: " + html, //$NON-NLS-1$
                html.contains(">0")); //$NON-NLS-1$
    }

    @Test
    public void renderFooterHtml_updateInPlaceStableId() {
        // Two render calls with different totals must produce the same element id
        // so the browser can do element.outerHTML = fragment replacement.
        String a = TokenFooterRenderer.renderFooterHtml(100L, 10L, 50L, 150L, 1);
        String b = TokenFooterRenderer.renderFooterHtml(200L, 20L, 100L, 300L, 2);
        assertTrue(a.contains("id=\"token-footer\"")); //$NON-NLS-1$
        assertTrue(b.contains("id=\"token-footer\"")); //$NON-NLS-1$
        assertEquals(TokenFooterRenderer.FOOTER_ELEMENT_ID, "token-footer"); //$NON-NLS-1$
    }
}
