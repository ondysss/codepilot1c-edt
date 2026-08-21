/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

import java.time.Instant;

/**
 * Immutable audit record of a lifecycle transition.
 *
 * @param cycleId   cycle in which the target phase became active
 * @param generation recovery generation at transition time
 * @param revision  committed revision containing the transition
 * @param fromPhase previous phase
 * @param toPhase   resulting phase
 * @param reason    supplied reason; mandatory for verification rollback
 * @param occurredAt transition time
 */
public record GsdTransition(
        String cycleId,
        long generation,
        long revision,
        GsdPhase fromPhase,
        GsdPhase toPhase,
        String reason,
        Instant occurredAt) {

    public GsdTransition {
        cycleId = cycleId == null ? "" : cycleId; //$NON-NLS-1$
        reason = reason == null ? "" : reason; //$NON-NLS-1$
    }

    /** Compatibility-style alias for concise audit consumers. */
    public GsdPhase from() {
        return fromPhase;
    }

    /** Compatibility-style alias for concise audit consumers. */
    public GsdPhase to() {
        return toPhase;
    }
}
