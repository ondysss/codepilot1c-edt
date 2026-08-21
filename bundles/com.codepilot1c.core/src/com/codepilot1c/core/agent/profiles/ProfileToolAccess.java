/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.profiles;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolRegistry;

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
        if (registry.isEffectiveDynamicTool(toolName)) {
            return profile.getDynamicToolGrant().grants(
                    registry.getDynamicToolCapability(toolName));
        }
        Set<String> allowed = profile.getAllowedTools();
        return allowed != null && allowed.contains(toolName);
    }

    /** Returns the currently effective model-facing tool names for a profile. */
    public static Set<String> effectiveToolNames(AgentProfile profile, ToolRegistry registry) {
        Set<String> result = new LinkedHashSet<>();
        for (ITool tool : registry.getAllTools()) {
            if (allows(profile, tool.getName(), registry)) {
                result.add(tool.getName());
            }
        }
        return Set.copyOf(result);
    }
}
