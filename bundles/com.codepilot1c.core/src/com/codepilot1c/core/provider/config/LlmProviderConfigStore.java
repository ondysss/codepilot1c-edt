/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;

import com.codepilot1c.core.internal.VibeCorePlugin;
import com.codepilot1c.core.settings.VibePreferenceConstants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

/**
 * Service for storing and loading LLM provider configurations.
 *
 * <p>Configurations are stored as JSON in Eclipse preferences.</p>
 */
public class LlmProviderConfigStore {

    private static final int CURRENT_CONFIG_VERSION = 1;
    static final String RESERVED_BACKEND_PROVIDER_ID = "backend"; //$NON-NLS-1$
    private static LlmProviderConfigStore instance;

    /**
     * Listener notified when provider configs are persisted.
     */
    public interface ProviderConfigChangeListener {
        void onProviderConfigsChanged();
    }

    private final Gson gson;
    private List<LlmProviderConfig> cachedConfigs;
    private String cachedActiveProviderId;
    private final List<ProviderConfigChangeListener> listeners = new CopyOnWriteArrayList<>();

    private LlmProviderConfigStore() {
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .create();
    }

    /**
     * Returns the singleton instance.
     */
    public static synchronized LlmProviderConfigStore getInstance() {
        if (instance == null) {
            instance = new LlmProviderConfigStore();
        }
        return instance;
    }

    /**
     * Adds a listener notified when configs are persisted.
     */
    public void addListener(ProviderConfigChangeListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Removes a previously added listener.
     */
    public void removeListener(ProviderConfigChangeListener listener) {
        listeners.remove(listener);
    }

    /**
     * Returns all configured providers.
     */
    public List<LlmProviderConfig> getProviders() {
        if (cachedConfigs == null) {
            loadFromPreferences();
        }
        return Collections.unmodifiableList(cachedConfigs);
    }

    /**
     * Returns a provider by its ID.
     */
    public Optional<LlmProviderConfig> getProvider(String id) {
        return getProviders().stream()
                .filter(p -> p.getId().equals(id))
                .findFirst();
    }

    /**
     * Returns the active provider configuration.
     */
    public Optional<LlmProviderConfig> getActiveProvider() {
        String activeId = getActiveProviderId();
        if (activeId == null || activeId.isEmpty()) {
            // Return first configured provider if no active set
            return getProviders().stream()
                    .filter(LlmProviderConfig::isConfigured)
                    .findFirst();
        }
        return getProvider(activeId);
    }

    /**
     * Returns the active provider ID.
     */
    public String getActiveProviderId() {
        if (cachedActiveProviderId == null) {
            loadFromPreferences();
        }
        return cachedActiveProviderId;
    }

    /**
     * Sets the active provider by ID.
     */
    public void setActiveProviderId(String id) {
        this.cachedActiveProviderId = id;
        saveToPreferences();
    }

    /**
     * Adds a new provider configuration.
     */
    public void addProvider(LlmProviderConfig config) {
        if (config == null || isReservedId(config.getId())) {
            VibeCorePlugin.logWarn("Ignoring provider config with reserved ID: " //$NON-NLS-1$
                    + (config != null ? config.getId() : "null")); //$NON-NLS-1$
            return;
        }
        if (cachedConfigs == null) {
            loadFromPreferences();
        }
        cachedConfigs.add(config);
        saveToPreferences();
    }

    /**
     * Updates an existing provider configuration.
     */
    public void updateProvider(LlmProviderConfig config) {
        if (config == null || isReservedId(config.getId())) {
            VibeCorePlugin.logWarn("Ignoring update for provider config with reserved ID: " //$NON-NLS-1$
                    + (config != null ? config.getId() : "null")); //$NON-NLS-1$
            return;
        }
        if (cachedConfigs == null) {
            loadFromPreferences();
        }
        for (int i = 0; i < cachedConfigs.size(); i++) {
            if (cachedConfigs.get(i).getId().equals(config.getId())) {
                cachedConfigs.set(i, config);
                break;
            }
        }
        saveToPreferences();
    }

    /**
     * Removes a provider by ID.
     */
    public void removeProvider(String id) {
        if (cachedConfigs == null) {
            loadFromPreferences();
        }
        cachedConfigs.removeIf(p -> p.getId().equals(id));
        // Clear active if it was the removed provider
        if (id.equals(cachedActiveProviderId)) {
            cachedActiveProviderId = null;
        }
        saveToPreferences();
    }

    /**
     * Saves all providers at once (for batch updates).
     */
    public void saveProviders(List<LlmProviderConfig> providers) {
        LoadedState sanitized = sanitizeLoadedState(
                providers != null ? providers : List.of(),
                cachedActiveProviderId);
        this.cachedConfigs = sanitized.configs();
        this.cachedActiveProviderId = sanitized.activeProviderId();
        saveToPreferences();
    }

    /**
     * Clears the cache and reloads from preferences.
     */
    public void refresh() {
        cachedConfigs = null;
        cachedActiveProviderId = null;
        loadFromPreferences();
    }

    /**
     * Loads configurations from Eclipse preferences.
     */
    private void loadFromPreferences() {
        IEclipsePreferences prefs = getPreferences();

        // Load providers JSON
        String json = prefs.get(VibePreferenceConstants.PREF_LLM_PROVIDERS, "[]"); //$NON-NLS-1$
        try {
            List<LlmProviderConfig> loadedConfigs = new ArrayList<>();
            JsonArray array = JsonParser.parseString(json).getAsJsonArray();
            for (JsonElement element : array) {
                LlmProviderConfig config = gson.fromJson(element, LlmProviderConfig.class);
                if (config != null) {
                    loadedConfigs.add(config);
                }
            }
            LoadedState sanitized = sanitizeLoadedState(
                    loadedConfigs,
                    prefs.get(VibePreferenceConstants.PREF_LLM_ACTIVE_PROVIDER_ID, "")); //$NON-NLS-1$
            cachedConfigs = sanitized.configs();
            cachedActiveProviderId = sanitized.activeProviderId();
        } catch (Exception e) {
            VibeCorePlugin.logWarn("Failed to parse provider configs: " + e.getMessage()); //$NON-NLS-1$
            cachedConfigs = new ArrayList<>();
            cachedActiveProviderId = prefs.get(VibePreferenceConstants.PREF_LLM_ACTIVE_PROVIDER_ID, ""); //$NON-NLS-1$
        }
    }

    /**
     * Saves configurations to Eclipse preferences.
     */
    private void saveToPreferences() {
        IEclipsePreferences prefs = getPreferences();

        try {
            // Save providers JSON
            String json = gson.toJson(cachedConfigs);
            prefs.put(VibePreferenceConstants.PREF_LLM_PROVIDERS, json);

            // Save active provider ID
            prefs.put(VibePreferenceConstants.PREF_LLM_ACTIVE_PROVIDER_ID,
                    cachedActiveProviderId != null ? cachedActiveProviderId : ""); //$NON-NLS-1$

            // Save config version
            prefs.putInt(VibePreferenceConstants.PREF_LLM_CONFIG_VERSION, CURRENT_CONFIG_VERSION);

            prefs.flush();

            // Notify after successful persist so consumers can refresh derived caches/state.
            for (ProviderConfigChangeListener listener : listeners) {
                try {
                    listener.onProviderConfigsChanged();
                } catch (Exception e) {
                    VibeCorePlugin.logWarn("Provider config listener failed: " + e.getMessage()); //$NON-NLS-1$
                }
            }
        } catch (BackingStoreException e) {
            VibeCorePlugin.logError("Failed to save provider configs", e); //$NON-NLS-1$
        }
    }

    /**
     * Returns Eclipse preferences node.
     */
    private IEclipsePreferences getPreferences() {
        return InstanceScope.INSTANCE.getNode(VibeCorePlugin.PLUGIN_ID);
    }

    static LoadedState sanitizeLoadedState(List<LlmProviderConfig> configs, String activeProviderId) {
        List<LlmProviderConfig> sanitizedConfigs = new ArrayList<>();
        boolean strippedReservedConfig = false;
        if (configs != null) {
            for (LlmProviderConfig config : configs) {
                if (config == null) {
                    continue;
                }
                if (isReservedId(config.getId())) {
                    strippedReservedConfig = true;
                    VibeCorePlugin.logWarn("Skipping persisted config with reserved system ID: " + config.getId()); //$NON-NLS-1$
                    continue;
                }
                sanitizedConfigs.add(config);
            }
        }

        String sanitizedActiveProviderId = activeProviderId != null ? activeProviderId : ""; //$NON-NLS-1$
        if (strippedReservedConfig && isReservedId(sanitizedActiveProviderId)) {
            sanitizedActiveProviderId = ""; //$NON-NLS-1$
        }

        return new LoadedState(sanitizedConfigs, sanitizedActiveProviderId);
    }

    static boolean isReservedId(String id) {
        return RESERVED_BACKEND_PROVIDER_ID.equals(id);
    }

    static final class LoadedState {
        private final List<LlmProviderConfig> configs;
        private final String activeProviderId;

        LoadedState(List<LlmProviderConfig> configs, String activeProviderId) {
            this.configs = new ArrayList<>(configs);
            this.activeProviderId = activeProviderId != null ? activeProviderId : ""; //$NON-NLS-1$
        }

        List<LlmProviderConfig> configs() {
            return new ArrayList<>(configs);
        }

        String activeProviderId() {
            return activeProviderId;
        }
    }

    /**
     * Checks if there are any configured providers.
     */
    public boolean hasConfiguredProviders() {
        return getProviders().stream().anyMatch(LlmProviderConfig::isConfigured);
    }

    /**
     * Returns the current config version stored in preferences.
     */
    public int getStoredConfigVersion() {
        return getPreferences().getInt(VibePreferenceConstants.PREF_LLM_CONFIG_VERSION, 0);
    }
}
