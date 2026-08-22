/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.gsd;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Per-panel rate limiter for repeated asynchronous GSD load failures.
 * Distinct project/error fingerprints are logged independently.
 */
public final class GsdLoadFailureLogGuard {

    /** Result of registering one load failure. */
    public record Decision(boolean shouldLog, int suppressedDuplicates) {
    }

    private static final int MAX_TRACKED_FAILURES = 16;

    private final long repeatIntervalNanos;
    private final LongSupplier nanoTime;
    private final Map<FailureKey, FailureState> failures = new LinkedHashMap<>();

    /** Creates a guard using {@link System#nanoTime()}. */
    public GsdLoadFailureLogGuard(long repeatIntervalNanos) {
        this(repeatIntervalNanos, System::nanoTime);
    }

    GsdLoadFailureLogGuard(long repeatIntervalNanos, LongSupplier nanoTime) {
        if (repeatIntervalNanos < 0) {
            throw new IllegalArgumentException("repeatIntervalNanos must not be negative"); //$NON-NLS-1$
        }
        this.repeatIntervalNanos = repeatIntervalNanos;
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime"); //$NON-NLS-1$
    }

    /**
     * Registers a failure and decides whether it should be emitted now.
     * The first occurrence is always logged. Repeats inside the interval are
     * counted and reported with the next emitted occurrence.
     */
    public synchronized Decision register(Path projectRoot, Throwable error) {
        Objects.requireNonNull(error, "error"); //$NON-NLS-1$
        FailureKey key = new FailureKey(
                projectRoot != null ? projectRoot.toAbsolutePath().normalize().toString() : "<none>", //$NON-NLS-1$
                error.getClass().getName(),
                error.getMessage() != null ? error.getMessage() : ""); //$NON-NLS-1$
        long now = nanoTime.getAsLong();
        FailureState state = failures.get(key);
        if (state == null) {
            evictOldestIfFull();
            failures.put(key, new FailureState(now));
            return new Decision(true, 0);
        }

        long elapsed = now - state.lastLoggedAtNanos;
        if (elapsed >= 0 && elapsed < repeatIntervalNanos) {
            state.suppressedDuplicates++;
            return new Decision(false, state.suppressedDuplicates);
        }

        int suppressed = state.suppressedDuplicates;
        state.lastLoggedAtNanos = now;
        state.suppressedDuplicates = 0;
        return new Decision(true, suppressed);
    }

    private void evictOldestIfFull() {
        if (failures.size() < MAX_TRACKED_FAILURES) {
            return;
        }
        FailureKey oldest = failures.keySet().iterator().next();
        failures.remove(oldest);
    }

    private record FailureKey(String projectRoot, String errorType, String message) {
    }

    private static final class FailureState {
        private long lastLoggedAtNanos;
        private int suppressedDuplicates;

        private FailureState(long lastLoggedAtNanos) {
            this.lastLoggedAtNanos = lastLoggedAtNanos;
        }
    }
}
