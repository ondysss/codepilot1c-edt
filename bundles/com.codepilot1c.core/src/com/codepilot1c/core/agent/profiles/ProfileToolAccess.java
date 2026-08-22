/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.profiles;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.ToolRegistry.ToolResolution;

/** Shared static/dynamic profile capability decision for every runtime gate. */
public final class ProfileToolAccess {

    private ProfileToolAccess() {
    }

    /**
     * Static names require an exact allowlist match. A dynamic name is allowed
     * only when it resolves to the dynamic implementation (built-ins win name
     * collisions), has an explicit non-NONE capability, and the profile grant
     * covers it.
     */
    public static boolean allows(AgentProfile profile, String toolName, ToolRegistry registry) {
        Objects.requireNonNull(profile, "profile"); //$NON-NLS-1$
        Objects.requireNonNull(registry, "registry"); //$NON-NLS-1$
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        return allows(profile, registry.resolveTool(toolName));
    }

    /** Applies the profile gate to the exact registry resolution being authorized. */
    public static boolean allows(AgentProfile profile, ToolResolution resolution) {
        Objects.requireNonNull(profile, "profile"); //$NON-NLS-1$
        if (resolution == null || resolution.name() == null
                || resolution.name().isBlank()) {
            return false;
        }
        if (resolution.dynamic()) {
            return profile.getDynamicToolGrant().grants(
                    resolution.dynamicCapability());
        }
        Set<String> allowed = profile.getAllowedTools();
        return allowed != null && allowed.contains(resolution.name());
    }

    /** Returns the currently effective model-facing tool names for a profile. */
    public static Set<String> effectiveToolNames(AgentProfile profile, ToolRegistry registry) {
        Set<String> result = new LinkedHashSet<>();
        for (ToolResolution resolution : registry.getModelFacingToolResolutions()) {
            if (allows(profile, resolution)) {
                result.add(resolution.name());
            }
        }
        return Set.copyOf(result);
    }
}
