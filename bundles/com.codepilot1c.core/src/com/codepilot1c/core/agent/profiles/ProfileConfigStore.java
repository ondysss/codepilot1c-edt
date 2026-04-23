/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.profiles;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;

import com.codepilot1c.core.logging.VibeLogger;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Persists user-customizable profile overrides to Eclipse workspace preferences.
 *
 * <p>Thread-safe: all public methods are {@code synchronized} and read fresh
 * from the preference store on every call (no mutable cache).</p>
 *
 * <p>Load-time validation clamps numeric values to safe ranges:
 * {@code maxSteps} in [1..100], {@code timeoutMs} in [10_000..600_000].</p>
 */
public class ProfileConfigStore {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(ProfileConfigStore.class);
    private static final String CORE_PLUGIN_ID = "com.codepilot1c.core"; //$NON-NLS-1$
    private static final String PREF_PROFILE_OVERRIDES = "profiles.overrides"; //$NON-NLS-1$

    private static final int MIN_MAX_STEPS = 1;
    private static final int MAX_MAX_STEPS = 100;
    private static final long MIN_TIMEOUT_MS = 10_000L;
    private static final long MAX_TIMEOUT_MS = 600_000L;

    private static ProfileConfigStore instance;

    /**
     * Returns the singleton instance.
     */
    public static synchronized ProfileConfigStore getInstance() {
        if (instance == null) {
            instance = new ProfileConfigStore();
        }
        return instance;
    }

    private ProfileConfigStore() {
    }

    /**
     * Returns the override for a profile, or empty if not customized.
     *
     * @param profileId the profile ID
     * @return optional override (validated/clamped)
     */
    public synchronized Optional<ProfileOverride> getOverride(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return Optional.empty();
        }
        JsonObject all = readOverrides();
        if (!all.has(profileId)) {
            return Optional.empty();
        }
        try {
            JsonObject obj = all.getAsJsonObject(profileId);
            ProfileOverride override = parseOverride(obj);
            if (override.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(override);
        } catch (Exception e) {
            LOG.warn("Failed to parse override for profile " + profileId + ": " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return Optional.empty();
        }
    }

    /**
     * Saves an override for a profile.
     *
     * @param profileId the profile ID
     * @param override  the override to save
     */
    public synchronized void setOverride(String profileId, ProfileOverride override) {
        if (profileId == null || profileId.isBlank() || override == null) {
            return;
        }
        JsonObject all = readOverrides();
        if (override.isEmpty()) {
            all.remove(profileId);
        } else {
            all.add(profileId, serializeOverride(override));
        }
        writeOverrides(all);
    }

    /**
     * Removes override for a profile, reverting to defaults.
     *
     * @param profileId the profile ID
     */
    public synchronized void removeOverride(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return;
        }
        JsonObject all = readOverrides();
        if (all.remove(profileId) != null) {
            writeOverrides(all);
        }
    }

    /**
     * Returns all profile IDs that have overrides.
     *
     * @return set of profile IDs
     */
    public synchronized Set<String> getOverriddenProfileIds() {
        return readOverrides().keySet();
    }

    /**
     * Persists current state to Eclipse preferences.
     */
    public synchronized void save() {
        try {
            getPreferences().flush();
        } catch (BackingStoreException e) {
            LOG.error("Failed to flush profile config preferences", e); //$NON-NLS-1$
        }
    }

    // ---- serialization ----

    private ProfileOverride parseOverride(JsonObject obj) {
        Integer maxSteps = null;
        if (obj.has("maxSteps")) { //$NON-NLS-1$
            maxSteps = clampSteps(obj.get("maxSteps").getAsInt()); //$NON-NLS-1$
        }

        Long timeoutMs = null;
        if (obj.has("timeoutMs")) { //$NON-NLS-1$
            timeoutMs = clampTimeout(obj.get("timeoutMs").getAsLong()); //$NON-NLS-1$
        }

        Set<String> disabledTools = null;
        if (obj.has("disabledTools")) { //$NON-NLS-1$
            disabledTools = new HashSet<>();
            for (JsonElement el : obj.getAsJsonArray("disabledTools")) { //$NON-NLS-1$
                String val = el.getAsString();
                if (val != null && !val.isBlank()) {
                    disabledTools.add(val);
                }
            }
        }

        String additionalPrompt = null;
        if (obj.has("additionalPrompt")) { //$NON-NLS-1$
            additionalPrompt = obj.get("additionalPrompt").getAsString(); //$NON-NLS-1$
        }

        return new ProfileOverride(maxSteps, timeoutMs, disabledTools, additionalPrompt);
    }

    private JsonObject serializeOverride(ProfileOverride override) {
        JsonObject obj = new JsonObject();
        if (override.maxSteps() != null) {
            obj.addProperty("maxSteps", clampSteps(override.maxSteps())); //$NON-NLS-1$
        }
        if (override.timeoutMs() != null) {
            obj.addProperty("timeoutMs", clampTimeout(override.timeoutMs())); //$NON-NLS-1$
        }
        if (override.disabledTools() != null && !override.disabledTools().isEmpty()) {
            JsonArray arr = new JsonArray();
            for (String tool : override.disabledTools()) {
                arr.add(tool);
            }
            obj.add("disabledTools", arr); //$NON-NLS-1$
        }
        if (override.additionalPrompt() != null && !override.additionalPrompt().isBlank()) {
            obj.addProperty("additionalPrompt", override.additionalPrompt()); //$NON-NLS-1$
        }
        return obj;
    }

    // ---- persistence ----

    private JsonObject readOverrides() {
        String json = getPreferences().get(PREF_PROFILE_OVERRIDES, "{}"); //$NON-NLS-1$
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            LOG.warn("Failed to parse profile overrides JSON, resetting: " + e.getMessage()); //$NON-NLS-1$
            return new JsonObject();
        }
    }

    private void writeOverrides(JsonObject all) {
        getPreferences().put(PREF_PROFILE_OVERRIDES, all.toString());
        save();
    }

    private IEclipsePreferences getPreferences() {
        return InstanceScope.INSTANCE.getNode(CORE_PLUGIN_ID);
    }

    // ---- validation ----

    private static int clampSteps(int value) {
        return Math.max(MIN_MAX_STEPS, Math.min(MAX_MAX_STEPS, value));
    }

    private static long clampTimeout(long value) {
        return Math.max(MIN_TIMEOUT_MS, Math.min(MAX_TIMEOUT_MS, value));
    }
}
