/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.memory.compaction;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Lock-in test for Plan 1.3: {@link LlmCompactionService#isEnabled()} must
 * default to {@code true} when no explicit preference override exists.
 *
 * <p>This test executes in a plain JUnit (non-OSGi) environment, where
 * {@code Platform.getPreferencesService()} is not wired. Under that condition
 * {@code isEnabled()} falls through to its default-value branch, which is the
 * exact contract this test pins: the feature is default-on.</p>
 *
 * <p>If a future change reintroduces a {@code false} default (either from the
 * preference lookup or the exception fallback), this test will fail, flagging
 * the regression before it reaches users.</p>
 */
public class LlmCompactionServiceDefaultEnabledTest {

    @Test
    public void isEnabledDefaultsToTrueWhenNoPreferenceOverrideSet() {
        LlmCompactionService service = LlmCompactionService.getInstance();

        boolean enabled = service.isEnabled();

        assertTrue(
                "Plan 1.3: LLM compaction must be enabled by default when no explicit preference is set", //$NON-NLS-1$
                enabled);
    }
}
