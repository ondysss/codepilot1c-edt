/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Shared service/store rules for CLOSED-to-DISCOVERY cycle replacement. */
final class GsdCycleRules {

    private GsdCycleRules() {
    }

    static void validateRequestedIdentity(GsdState current, String newCycleId,
            long newGeneration) {
        Objects.requireNonNull(current, "current"); //$NON-NLS-1$
        if (newCycleId == null || newCycleId.isBlank()) {
            throw new IllegalArgumentException("new cycleId must not be blank"); //$NON-NLS-1$
        }
        if (newGeneration < current.generation()) {
            throw new IllegalArgumentException("new cycle generation must not regress: current " //$NON-NLS-1$
                    + current.generation() + ", requested " + newGeneration); //$NON-NLS-1$
        }
        if (newGeneration != current.generation()) {
            throw new IllegalArgumentException("new cycle must preserve generation " //$NON-NLS-1$
                    + current.generation() + ", requested " + newGeneration); //$NON-NLS-1$
        }
        Set<String> usedCycleIds = new HashSet<>();
        usedCycleIds.add(current.cycleId());
        for (GsdTransition transition : current.transitionHistory()) {
            usedCycleIds.add(transition.cycleId());
        }
        if (usedCycleIds.contains(newCycleId)) {
            throw new GsdCycleIdReuseException(newCycleId);
        }
    }

    static void validateReplacement(GsdState current, GsdState next) {
        Objects.requireNonNull(next, "next"); //$NON-NLS-1$
        if (current.phase() != GsdPhase.CLOSED) {
            throw new IllegalStateException("new cycle requires current phase CLOSED"); //$NON-NLS-1$
        }
        if (next.phase() != GsdPhase.DISCOVERY
                || next.revision() != GsdState.INITIAL_REVISION) {
            throw new IllegalArgumentException("new cycle must start in DISCOVERY at revision 0"); //$NON-NLS-1$
        }
        validateRequestedIdentity(current, next.cycleId(), next.generation());
        List<GsdTransition> oldHistory = current.transitionHistory();
        List<GsdTransition> newHistory = next.transitionHistory();
        if (newHistory.size() != oldHistory.size() + 1
                || !newHistory.subList(0, oldHistory.size()).equals(oldHistory)) {
            throw new IllegalArgumentException(
                    "new cycle must preserve transition history and append one audit entry"); //$NON-NLS-1$
        }
        GsdTransition audit = newHistory.get(newHistory.size() - 1);
        if (!audit.cycleId().equals(next.cycleId())
                || audit.generation() != next.generation()
                || audit.revision() != GsdState.INITIAL_REVISION
                || audit.fromPhase() != GsdPhase.CLOSED
                || audit.toPhase() != GsdPhase.DISCOVERY
                || audit.reason().isBlank()) {
            throw new IllegalArgumentException("new cycle audit entry is incomplete or inconsistent"); //$NON-NLS-1$
        }
    }
}
