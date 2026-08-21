/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.profiles;

/**
 * Trust/capability classification for tools registered at runtime.
 *
 * <p>{@link #NONE} is used for unclassified local runtime registrations and
 * is never an executable grant. Untrusted MCP tools are classified
 * {@link #MUTATING}; only trusted local per-server/tool provenance may
 * classify them {@link #READ_ONLY}. Profiles declare the strongest accepted
 * capability independently of the static tool-name allowlist.</p>
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
