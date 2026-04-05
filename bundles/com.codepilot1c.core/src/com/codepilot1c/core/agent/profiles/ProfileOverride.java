/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.profiles;

import java.util.Set;

/**
 * User-customizable override for an agent profile.
 *
 * <p>Null fields mean "use the default value from the underlying {@link AgentProfile}".</p>
 *
 * <p>The {@code disabledTools} set is a <em>blacklist</em>: it lists tools to
 * <b>remove</b> from the profile's default allowed-tools set. This ensures that
 * profile updates that add new tools are automatically inherited by users who
 * have not explicitly disabled them.</p>
 */
public record ProfileOverride(
        Integer maxSteps,
        Long timeoutMs,
        Set<String> disabledTools,
        String additionalPrompt) {

    /**
     * Returns {@code true} if this override changes nothing relative to defaults.
     */
    public boolean isEmpty() {
        return maxSteps == null && timeoutMs == null
                && (disabledTools == null || disabledTools.isEmpty())
                && (additionalPrompt == null || additionalPrompt.isBlank());
    }
}
