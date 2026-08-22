/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.eclipse.core.runtime.Platform;

import com.codepilot1c.core.internal.VibeCorePlugin;
import com.codepilot1c.core.settings.VibePreferenceConstants;

/**
 * Core-owned availability policy for the GSD feature.
 *
 * <p>The workspace preference is enabled by default. The JVM property is an
 * emergency kill switch: only an explicit {@code false} disables GSD before
 * the workspace preference is consulted.</p>
 */
public final class GsdFeatureGate {

    /** Emergency JVM kill-switch property. */
    public static final String JVM_PROPERTY = "codepilot1c.gsd.enabled"; //$NON-NLS-1$

    private static final GsdFeatureGate INSTANCE = new GsdFeatureGate(
            () -> System.getProperty(JVM_PROPERTY),
            GsdFeatureGate::workspacePreferenceEnabled);

    private final Supplier<String> jvmProperty;
    private final BooleanSupplier workspacePreference;

    GsdFeatureGate(Supplier<String> jvmProperty, BooleanSupplier workspacePreference) {
        this.jvmProperty = Objects.requireNonNull(jvmProperty, "jvmProperty"); //$NON-NLS-1$
        this.workspacePreference = Objects.requireNonNull(
                workspacePreference, "workspacePreference"); //$NON-NLS-1$
    }

    /** Returns the process-wide gate. The value is deliberately read on every call. */
    public static GsdFeatureGate getInstance() {
        return INSTANCE;
    }

    /** Returns whether GSD is currently available. */
    public boolean isEnabled() {
        String override = jvmProperty.get();
        if (override != null && "false".equalsIgnoreCase(override.trim())) { //$NON-NLS-1$
            return false;
        }
        return workspacePreference.getAsBoolean();
    }

    /** Returns whether a built-in GSD tool may appear on a model-facing surface. */
    public boolean isToolVisible(String toolName) {
        return !isGsdTool(toolName) || isEnabled();
    }

    /** Returns whether a profile may be offered or resolved for execution. */
    public boolean isProfileAvailable(String profileId) {
        return !isGsdProfile(profileId) || isEnabled();
    }

    /** Identifies the stable built-in GSD tool namespace. */
    public static boolean isGsdTool(String toolName) {
        return toolName != null && toolName.startsWith("gsd_"); //$NON-NLS-1$
    }

    /** Identifies the stable GSD profile namespace. */
    public static boolean isGsdProfile(String profileId) {
        return profileId != null && profileId.startsWith("gsd-"); //$NON-NLS-1$
    }

    private static boolean workspacePreferenceEnabled() {
        try {
            if (Platform.getPreferencesService() == null) {
                return true;
            }
            return Platform.getPreferencesService().getBoolean(
                    VibeCorePlugin.PLUGIN_ID,
                    VibePreferenceConstants.PREF_GSD_ENABLED,
                    true,
                    null);
        } catch (RuntimeException e) {
            // Headless/bootstrap callers retain the documented enabled default.
            return true;
        }
    }
}
