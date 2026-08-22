/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.prompts;

import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Regression coverage for the prompt QA checks run during profile-registry startup. */
public class GsdPromptStartupQaTest {

    private static final String STRICT_PROPERTY = "codepilot1c.prompt.qa.strict"; //$NON-NLS-1$

    private String previousStrictValue;

    @Before
    public void enableStrictPromptQa() {
        previousStrictValue = System.getProperty(STRICT_PROPERTY);
        System.setProperty(STRICT_PROPERTY, Boolean.TRUE.toString());
    }

    @After
    public void restoreStrictPromptQa() {
        if (previousStrictValue == null) {
            System.clearProperty(STRICT_PROPERTY);
        } else {
            System.setProperty(STRICT_PROPERTY, previousStrictValue);
        }
    }

    @Test
    public void startupChecksCompleteWithoutPromptQaWarnings() {
        AgentPromptTemplates.runStartupChecks();
    }

    @Test
    public void everyGsdTemplateContainsTheRequiredRoleHeading() {
        for (String profileId : List.of(
                "gsd-discuss", //$NON-NLS-1$
                "gsd-plan", //$NON-NLS-1$
                "gsd-execute", //$NON-NLS-1$
                "gsd-verify", //$NON-NLS-1$
                "gsd-ship")) { //$NON-NLS-1$
            String prompt = AgentPromptTemplates.buildGsdPhasePrompt(profileId);
            assertTrue(profileId + " must contain the required role heading", //$NON-NLS-1$
                    prompt.lines().anyMatch(line -> line.startsWith("## Роль"))); //$NON-NLS-1$
        }
    }
}
