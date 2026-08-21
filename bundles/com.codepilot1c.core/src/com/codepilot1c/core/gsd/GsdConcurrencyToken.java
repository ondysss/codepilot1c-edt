/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

import java.util.Objects;

/**
 * Optimistic-concurrency identity for a GSD aggregate.
 *
 * <p>The cycle and generation components prevent a revision value from being reused
 * after a new cycle or backup recovery (the classic ABA problem).</p>
 *
 * @param cycleId   logical delivery-cycle identifier
 * @param generation recovery generation within the persisted aggregate
 * @param revision  mutation revision within the cycle/generation
 */
public record GsdConcurrencyToken(String cycleId, long generation, long revision) {

    public GsdConcurrencyToken {
        Objects.requireNonNull(cycleId, "cycleId"); //$NON-NLS-1$
    }
}
