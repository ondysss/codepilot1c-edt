/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.gsd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.codepilot1c.core.agent.profiles.AgentProfileRegistry;
import com.codepilot1c.core.gsd.GsdFeatureGate;

public class GsdUiProfilePolicyTest {

    private String previousOverride;

    @Before
    public void disableGsdThroughCoreGate() {
        previousOverride = System.getProperty(GsdFeatureGate.JVM_PROPERTY);
        System.setProperty(GsdFeatureGate.JVM_PROPERTY, Boolean.FALSE.toString());
    }

    @After
    public void restoreOverride() {
        if (previousOverride == null) {
            System.clearProperty(GsdFeatureGate.JVM_PROPERTY);
        } else {
            System.setProperty(GsdFeatureGate.JVM_PROPERTY, previousOverride);
        }
    }

    @Test
    public void unavailableGsdProfilesAreHiddenFromUiLists() {
        List<String> ids = AgentProfileRegistry.getInstance().getAvailableProfiles().stream()
                .map(profile -> profile.getId())
                .toList();

        assertFalse(ids.stream().anyMatch(GsdFeatureGate::isGsdProfile));
    }

    @Test
    public void persistedGsdProfileFallsBackToExplore() {
        assertEquals("explore", GsdUiProfilePolicy.safeProfileId("gsd-execute")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("build", GsdUiProfilePolicy.safeProfileId("build")); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
