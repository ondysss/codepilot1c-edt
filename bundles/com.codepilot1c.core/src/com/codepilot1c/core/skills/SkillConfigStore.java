/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.skills;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;

import com.codepilot1c.core.logging.VibeLogger;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * Persists skill enabled/disabled flags to Eclipse workspace preferences.
 *
 * <p>Thread-safe: all public methods are {@code synchronized} and read fresh
 * from the preference store on every call (no mutable cache).</p>
 */
public class SkillConfigStore {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(SkillConfigStore.class);
    private static final String CORE_PLUGIN_ID = "com.codepilot1c.core"; //$NON-NLS-1$
    private static final String PREF_DISABLED_SKILLS = "skills.disabled"; //$NON-NLS-1$

    private static SkillConfigStore instance;

    /**
     * Returns the singleton instance.
     */
    public static synchronized SkillConfigStore getInstance() {
        if (instance == null) {
            instance = new SkillConfigStore();
        }
        return instance;
    }

    private SkillConfigStore() {
    }

    /**
     * Returns the set of explicitly disabled skill names (lowercase, normalized).
     *
     * @return immutable copy of disabled skill names
     */
    public synchronized Set<String> getDisabledSkills() {
        return Collections.unmodifiableSet(readDisabledSet());
    }

    /**
     * Checks if a skill is enabled (not in the disabled set).
     *
     * @param name skill name
     * @return true if enabled
     */
    public synchronized boolean isEnabled(String name) {
        if (name == null || name.isBlank()) {
            return true;
        }
        return !readDisabledSet().contains(normalize(name));
    }

    /**
     * Disables a skill by name.
     *
     * @param name skill name
     */
    public synchronized void disableSkill(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        Set<String> disabled = readDisabledSet();
        disabled.add(normalize(name));
        writeDisabledSet(disabled);
    }

    /**
     * Enables a skill by name (removes from disabled set).
     *
     * @param name skill name
     */
    public synchronized void enableSkill(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        Set<String> disabled = readDisabledSet();
        if (disabled.remove(normalize(name))) {
            writeDisabledSet(disabled);
        }
    }

    /**
     * Replaces the entire disabled set (used by preference page "apply").
     *
     * @param disabledNames set of disabled skill names
     */
    public synchronized void setDisabledSkills(Set<String> disabledNames) {
        Set<String> normalized = new HashSet<>();
        if (disabledNames != null) {
            for (String n : disabledNames) {
                if (n != null && !n.isBlank()) {
                    normalized.add(normalize(n));
                }
            }
        }
        writeDisabledSet(normalized);
    }

    /**
     * Persists current state to Eclipse preferences.
     */
    public synchronized void save() {
        try {
            getPreferences().flush();
        } catch (BackingStoreException e) {
            LOG.error("Failed to flush skill config preferences", e); //$NON-NLS-1$
        }
    }

    // ---- internal ----

    private Set<String> readDisabledSet() {
        String json = getPreferences().get(PREF_DISABLED_SKILLS, "[]"); //$NON-NLS-1$
        Set<String> result = new HashSet<>();
        try {
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            for (JsonElement el : arr) {
                String val = el.getAsString();
                if (val != null && !val.isBlank()) {
                    result.add(normalize(val));
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to parse disabled skills JSON, resetting: " + e.getMessage()); //$NON-NLS-1$
        }
        return result;
    }

    private void writeDisabledSet(Set<String> disabled) {
        JsonArray arr = new JsonArray();
        for (String name : disabled) {
            arr.add(name);
        }
        getPreferences().put(PREF_DISABLED_SKILLS, arr.toString());
        save();
    }

    private IEclipsePreferences getPreferences() {
        return InstanceScope.INSTANCE.getNode(CORE_PLUGIN_ID);
    }

    private static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
