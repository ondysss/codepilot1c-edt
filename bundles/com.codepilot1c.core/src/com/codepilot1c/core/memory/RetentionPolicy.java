/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.memory;

import java.time.Duration;
import java.time.Instant;

/**
 * Retention policy for memory entries with TTL support.
 */
public record RetentionPolicy(
        Duration ttl,
        Instant createdAt
) {

    /** Permanent retention (curated entries). */
    public static final RetentionPolicy PERMANENT = new RetentionPolicy(Duration.ZERO, Instant.EPOCH);

    /** Default TTL for auto-generated facts: 7 days. */
    public static final Duration DEFAULT_FACT_TTL = Duration.ofDays(7);

    /** Default TTL for PENDING tasks: 30 days. */
    public static final Duration DEFAULT_PENDING_TTL = Duration.ofDays(30);

    /**
     * Creates a retention policy with the given TTL starting now.
     */
    public static RetentionPolicy withTtl(Duration ttl) {
        return new RetentionPolicy(ttl, Instant.now());
    }

    /**
     * Returns true if this entry has expired according to its TTL.
     * Permanent entries (ttl == ZERO) never expire.
     */
    public boolean isExpired() {
        if (ttl.isZero()) {
            return false; // permanent
        }
        return Instant.now().isAfter(createdAt.plus(ttl));
    }
}
