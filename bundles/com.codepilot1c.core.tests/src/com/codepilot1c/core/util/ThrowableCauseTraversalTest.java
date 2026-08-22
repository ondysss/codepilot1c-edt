/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.Test;

import com.codepilot1c.core.filesystem.SecureDirectoryCapabilityException;

public class ThrowableCauseTraversalTest {

    @Test
    public void identityCycleTerminatesAndStillFindsCapabilityBeforeCycle() {
        IOException first = new IOException("first"); //$NON-NLS-1$
        IOException second = new IOException("second"); //$NON-NLS-1$
        first.initCause(second);
        second.initCause(first);

        assertFalse(ThrowableCauseTraversal.contains(
                first, SecureDirectoryCapabilityException.class));

        SecureDirectoryCapabilityException capability =
                new SecureDirectoryCapabilityException(Path.of("state"), "unsupported"); //$NON-NLS-1$ //$NON-NLS-2$
        IOException wrapper = new IOException("wrapper", capability); //$NON-NLS-1$
        capability.initCause(wrapper);
        assertTrue(ThrowableCauseTraversal.contains(
                wrapper, SecureDirectoryCapabilityException.class));
    }

    @Test
    public void traversalStopsAtExplicitBound() {
        IOException root = new IOException("0"); //$NON-NLS-1$
        IOException current = root;
        for (int i = 1; i < 80; i++) {
            IOException next = new IOException(Integer.toString(i));
            current.initCause(next);
            current = next;
        }
        current.initCause(new SecureDirectoryCapabilityException(
                Path.of("state"), "too deep")); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(ThrowableCauseTraversal.contains(
                root, SecureDirectoryCapabilityException.class));
    }
}
