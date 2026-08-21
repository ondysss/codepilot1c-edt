/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.views;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;

import org.junit.Test;

/** Tests fail-closed Code.md project ownership independently of Eclipse resources. */
public class CodeMdProjectResolverTest {

    @Test
    public void unresolvedExplicitIdentityDoesNotFallBackToOpenProject() {
        assertNull(CodeMdProjectResolver.resolve(
                "/missing/project-a", ignored -> null, List.of("project-b"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void explicitIdentityWinsWithMultipleOpenProjects() {
        assertEquals("project-a", CodeMdProjectResolver.resolve( //$NON-NLS-1$
                "/workspace/project-a", path -> "project-a", //$NON-NLS-1$ //$NON-NLS-2$
                List.of("project-a", "project-b"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void onlyUnboundViewMayUseSingleProjectFallback() {
        assertEquals("project-a", CodeMdProjectResolver.resolve( //$NON-NLS-1$
                null, ignored -> null, List.of("project-a"))); //$NON-NLS-1$
        assertNull(CodeMdProjectResolver.resolve(
                "", ignored -> null, List.of("project-a", "project-b"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
