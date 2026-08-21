/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

import java.util.List;
import java.util.Objects;

/**
 * Outcome returned after an authoritative state commit.
 *
 * <p>Projection failures are warnings because {@code state.json} has already been
 * atomically committed. Pre-commit failures still throw and therefore never produce
 * this outcome.</p>
 *
 * @param state     committed state
 * @param committed always {@code true} for a returned outcome
 * @param warnings  non-authoritative projection warnings
 */
public record GsdCommitOutcome(GsdState state, boolean committed, List<String> warnings) {

    public GsdCommitOutcome {
        Objects.requireNonNull(state, "state"); //$NON-NLS-1$
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** @return whether non-authoritative follow-up work produced warnings */
    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }

    /** @return the authoritative state; alias for {@link #state()} */
    public GsdState committedState() {
        return state;
    }

    /** @return non-authoritative projection warnings; alias for {@link #warnings()} */
    public List<String> projectionWarnings() {
        return warnings;
    }
}
