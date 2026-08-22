/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GsdFeatureGateTest {

    @Test
    public void defaultsToWorkspacePreferenceWhenJvmKillSwitchIsAbsent() {
        assertTrue(new GsdFeatureGate(() -> null, () -> true).isEnabled());
        assertFalse(new GsdFeatureGate(() -> null, () -> false).isEnabled());
    }

    @Test
    public void explicitFalseJvmValueAlwaysDisablesGsd() {
        assertFalse(new GsdFeatureGate(() -> "false", () -> true).isEnabled()); //$NON-NLS-1$
        assertFalse(new GsdFeatureGate(() -> " FALSE ", () -> true).isEnabled()); //$NON-NLS-1$
    }

    @Test
    public void nonKillSwitchJvmValuesDoNotOverrideWorkspacePreference() {
        assertFalse(new GsdFeatureGate(() -> "true", () -> false).isEnabled()); //$NON-NLS-1$
        assertTrue(new GsdFeatureGate(() -> "invalid", () -> true).isEnabled()); //$NON-NLS-1$
    }
}
