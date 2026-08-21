/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.util;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/** Bounded, identity-cycle-safe inspection of untrusted throwable cause chains. */
public final class ThrowableCauseTraversal {

    static final int MAX_CAUSES = 64;

    private ThrowableCauseTraversal() {
    }

    public static boolean contains(Throwable failure, Class<? extends Throwable> type) {
        Objects.requireNonNull(type, "type"); //$NON-NLS-1$
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        for (int visited = 0; current != null && visited < MAX_CAUSES; visited++) {
            if (!seen.add(current)) {
                return false;
            }
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
