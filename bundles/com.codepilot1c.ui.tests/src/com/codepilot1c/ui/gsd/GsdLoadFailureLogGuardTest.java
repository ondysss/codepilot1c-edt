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
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

public class GsdLoadFailureLogGuardTest {

    private static final long INTERVAL = 100L;

    @Test
    public void suppressesDuplicateFailureUntilIntervalExpires() {
        AtomicLong clock = new AtomicLong(1_000L);
        GsdLoadFailureLogGuard guard = new GsdLoadFailureLogGuard(INTERVAL, clock::get);
        Path root = Path.of("/project/a"); //$NON-NLS-1$

        GsdLoadFailureLogGuard.Decision first =
                guard.register(root, new IOException("broken state")); //$NON-NLS-1$
        GsdLoadFailureLogGuard.Decision second =
                guard.register(root, new IOException("broken state")); //$NON-NLS-1$
        clock.addAndGet(INTERVAL);
        GsdLoadFailureLogGuard.Decision afterInterval =
                guard.register(root, new IOException("broken state")); //$NON-NLS-1$

        assertTrue(first.shouldLog());
        assertEquals(0, first.suppressedDuplicates());
        assertFalse(second.shouldLog());
        assertEquals(1, second.suppressedDuplicates());
        assertTrue(afterInterval.shouldLog());
        assertEquals(1, afterInterval.suppressedDuplicates());
    }

    @Test
    public void distinctProjectsAndFailuresAreLoggedIndependently() {
        AtomicLong clock = new AtomicLong(1_000L);
        GsdLoadFailureLogGuard guard = new GsdLoadFailureLogGuard(INTERVAL, clock::get);

        assertTrue(guard.register(Path.of("/project/a"), //$NON-NLS-1$
                new IOException("broken state")).shouldLog()); //$NON-NLS-1$
        assertTrue(guard.register(Path.of("/project/b"), //$NON-NLS-1$
                new IOException("broken state")).shouldLog()); //$NON-NLS-1$
        assertTrue(guard.register(Path.of("/project/a"), //$NON-NLS-1$
                new IOException("different failure")).shouldLog()); //$NON-NLS-1$
    }
}
