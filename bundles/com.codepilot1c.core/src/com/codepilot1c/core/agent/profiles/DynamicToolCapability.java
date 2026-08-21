/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.profiles;

/**
 * Trust/capability classification for tools registered at runtime.
 *
 * <p>{@link #NONE} is used for unknown or unannotated tools and is never an
 * executable grant. Profiles declare the strongest non-{@code NONE}
 * capability they accept independently of the static tool-name allowlist.</p>
 */
public enum DynamicToolCapability {
    NONE,
    READ_ONLY,
    MUTATING;

    /** Returns whether this profile grant covers a registered capability. */
    public boolean grants(DynamicToolCapability registeredCapability) {
        return registeredCapability != null
                && registeredCapability != NONE
                && ordinal() >= registeredCapability.ordinal();
    }
}
