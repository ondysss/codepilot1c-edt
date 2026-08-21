/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

/**
 * Lifecycle phases of a GSD project. Transitions are monotonic in declaration order
 * (forward progress); the {@link GsdGuard} gates entry into {@link #CLOSED}.
 */
public enum GsdPhase {

    /** Gathering context, constraints, and knowns before planning. */
    DISCOVERY,

    /** Decomposing the goal into decisions, tasks, and waves. */
    PLANNING,

    /** Executing waves of tasks. */
    EXECUTING,

    /** Verifying completed work against the goal and evidence. */
    VERIFYING,

    /** Delivering the verified work and recording the shipment result. */
    SHIPPING,

    /** Terminal state; requires passed criteria, verified evidence, and delivery. */
    CLOSED;

    /**
     * Parses a phase by name, case-insensitive; returns {@code null} if unknown so the
     * caller can treat it as corruption rather than silently coercing.
     *
     * @param name the phase name
     * @return the phase, or {@code null} if not recognized
     */
    public static GsdPhase fromName(String name) {
        if (name == null) {
            return null;
        }
        for (GsdPhase phase : values()) {
            if (phase.name().equalsIgnoreCase(name)) {
                return phase;
            }
        }
        return null;
    }
}
