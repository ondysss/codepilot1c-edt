/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;

import com.codepilot1c.core.internal.VibeCorePlugin;
import com.codepilot1c.core.provider.config.DynamicLlmProvider;
import com.codepilot1c.core.provider.config.LlmProviderConfig;
import com.codepilot1c.core.provider.config.LlmProviderConfigStore;
import com.codepilot1c.core.settings.VibePreferenceConstants;
import com.codepilot1c.core.state.VibeState;
import com.codepilot1c.core.state.VibeStateService;

/**
 * Registry for LLM providers.
 *
 * <p>This class manages the lifecycle and access to registered LLM providers.
 * Supports both legacy extension-point providers and new dynamic providers
 * configured via the universal provider configuration system.</p>
 */
public final class LlmProviderRegistry {

    private static final String EXTENSION_POINT_ID = VibeCorePlugin.PLUGIN_ID + ".llmProvider"; //$NON-NLS-1$

    private static LlmProviderRegistry instance;

    /** Legacy providers from extension points */
    private final Map<String, ILlmProvider> legacyProviders = new LinkedHashMap<>();

    /** Dynamic providers from configuration store */
    private final Map<String, DynamicLlmProvider> dynamicProviders = new LinkedHashMap<>();

    /** Backend provider injected at runtime after plugin account login */
    private DynamicLlmProvider backendProvider;

    private boolean initialized = false;

    private LlmProviderConfigStore configStore;
    private final LlmProviderConfigStore.ProviderConfigChangeListener configListener = this::onProviderConfigsChanged;

    private LlmProviderRegistry() {
        // Singleton
    }

    /**
     * Returns the singleton instance.
     *
     * @return the registry instance
     */
    public static synchronized LlmProviderRegistry getInstance() {
        if (instance == null) {
            instance = new LlmProviderRegistry();
        }
        return instance;
    }

    /**
     * Initializes the registry by loading providers from extension points
     * and dynamic configurations.
     */
    public synchronized void initialize() {
        if (initialized) {
            return;
        }

        // Initialize config store
        configStore = LlmProviderConfigStore.getInstance();
        configStore.addListener(configListener);

        // Load legacy providers from extension points (for backwards compatibility)
        loadLegacyProviders();

        // Load dynamic providers from configuration store
        loadDynamicProviders();

        initialized = true;
        int total = legacyProviders.size() + dynamicProviders.size();
        VibeCorePlugin.logInfo("Loaded " + total + " LLM providers (" + //$NON-NLS-1$ //$NON-NLS-2$
                dynamicProviders.size() + " dynamic, " + legacyProviders.size() + " legacy)"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void onProviderConfigsChanged() {
        // Keep derived caches and UI state in sync when preferences change at runtime.
        try {
            refreshDynamicProviders();
        } catch (Exception e) {
            VibeCorePlugin.logWarn("Failed to refresh dynamic providers: " + e.getMessage(), e); //$NON-NLS-1$
        }
        updateConfigurationState();
    }

    private void updateConfigurationState() {
        VibeStateService state = VibeStateService.getInstance();
        VibeState current = state.getState();
        if (current != VibeState.IDLE && current != VibeState.NOT_CONFIGURED) {
            return; // don't override active/error states
        }

        ILlmProvider active = null;
        try {
            active = getActiveProvider();
        } catch (Exception e) {
            // ignore and treat as not configured
        }

        if (active != null && active.isConfigured()) {
            if (current == VibeState.NOT_CONFIGURED) {
                state.setIdle();
            }
        } else {
            if (current == VibeState.IDLE) {
                state.setNotConfigured("No LLM providers configured. Configure one in Preferences."); //$NON-NLS-1$
            }
        }
    }

    /**
     * Loads providers from extension points (legacy system).
     */
    private void loadLegacyProviders() {
        IExtensionRegistry registry = Platform.getExtensionRegistry();
        if (registry == null) {
            return;
        }

        IConfigurationElement[] elements = registry.getConfigurationElementsFor(EXTENSION_POINT_ID);
        for (IConfigurationElement element : elements) {
            if ("provider".equals(element.getName())) { //$NON-NLS-1$
                try {
                    loadLegacyProvider(element);
                } catch (Exception e) {
                    VibeCorePlugin.logError("Failed to load LLM provider: " + element.getAttribute("id"), e); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
    }

    private void loadLegacyProvider(IConfigurationElement element) throws CoreException {
        String id = element.getAttribute("id"); //$NON-NLS-1$
        if (id == null || id.isEmpty()) {
            return;
        }

        Object instance = element.createExecutableExtension("class"); //$NON-NLS-1$
        if (instance instanceof ILlmProvider provider) {
            legacyProviders.put(id, provider);
        }
    }

    /**
     * Loads dynamic providers from the configuration store.
     */
    private void loadDynamicProviders() {
        dynamicProviders.clear();

        for (LlmProviderConfig config : configStore.getProviders()) {
            DynamicLlmProvider provider = new DynamicLlmProvider(config);
            dynamicProviders.put(config.getId(), provider);
        }
    }

    /**
     * Refreshes dynamic providers from configuration store.
     * Call this after modifying provider configurations.
     */
    public synchronized void refreshDynamicProviders() {
        // Ensure registry is initialized first
        if (!initialized) {
            initialize();
        }

        // Dispose old dynamic providers
        for (DynamicLlmProvider provider : dynamicProviders.values()) {
            try {
                provider.dispose();
            } catch (Exception e) {
                // ignore
            }
        }

        // Reload from store
        configStore.refresh();
        loadDynamicProviders();
    }

    /**
     * Returns all registered providers (both dynamic and legacy).
     *
     * @return collection of providers
     */
    public Collection<ILlmProvider> getProviders() {
        initialize();
        List<ILlmProvider> all = new ArrayList<>();
        if (backendProvider != null) {
            all.add(backendProvider);
        }
        all.addAll(dynamicProviders.values());
        all.addAll(legacyProviders.values());
        return Collections.unmodifiableCollection(all);
    }

    /**
     * Returns only dynamic providers (user-configured).
     *
     * @return collection of dynamic providers
     */
    public Collection<DynamicLlmProvider> getDynamicProviders() {
        initialize();
        return Collections.unmodifiableCollection(dynamicProviders.values());
    }

    /**
     * Returns only legacy providers (from extension points).
     *
     * @return collection of legacy providers
     */
    public Collection<ILlmProvider> getLegacyProviders() {
        initialize();
        return Collections.unmodifiableCollection(legacyProviders.values());
    }

    /**
     * Returns a provider by its ID.
     * Searches dynamic providers first, then legacy providers.
     *
     * @param id the provider ID
     * @return the provider, or null if not found
     */
    public ILlmProvider getProvider(String id) {
        initialize();

        if (backendProvider != null && backendProvider.getId().equals(id)) {
            return backendProvider;
        }

        // Check dynamic providers first (new system)
        DynamicLlmProvider dynamic = dynamicProviders.get(id);
        if (dynamic != null) {
            return dynamic;
        }

        // Fall back to legacy providers
        return legacyProviders.get(id);
    }

    /**
     * Returns the currently active provider based on preferences.
     * Otherwise checks new config system first, then falls back to legacy.
     *
     * @return the active provider, or null if none configured
     */
    public ILlmProvider getActiveProvider() {
        initialize();

        String explicitProviderId = configStore != null ? configStore.getActiveProviderId() : null;
        if (explicitProviderId != null && !explicitProviderId.isBlank()) {
            ILlmProvider explicitProvider = getProvider(explicitProviderId);
            if (explicitProvider != null && explicitProvider.isConfigured()) {
                return explicitProvider;
            }
        }

        // First check new config system
        if (configStore != null && configStore.hasConfiguredProviders()) {
            Optional<LlmProviderConfig> activeConfig = configStore.getActiveProvider();
            if (activeConfig.isPresent()) {
                DynamicLlmProvider provider = dynamicProviders.get(activeConfig.get().getId());
                if (provider != null && provider.isConfigured()) {
                    return provider;
                }
            }

            // If no active set, return first configured dynamic provider
            for (DynamicLlmProvider provider : dynamicProviders.values()) {
                if (provider.isConfigured()) {
                    return provider;
                }
            }
        }

        // Fall back to legacy provider selection
        IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(VibeCorePlugin.PLUGIN_ID);
        String providerId = prefs.get(VibePreferenceConstants.PREF_PROVIDER_ID, "claude"); //$NON-NLS-1$
        ILlmProvider legacy = legacyProviders.get(providerId);
        if (legacy != null && legacy.isConfigured()) {
            return legacy;
        }

        // Try any configured legacy provider
        for (ILlmProvider provider : legacyProviders.values()) {
            if (provider.isConfigured()) {
                return provider;
            }
        }

        if (backendProvider != null && backendProvider.isConfigured()) {
            return backendProvider;
        }

        return null;
    }

    /**
     * Sets a transient backend provider managed by CodePilot account authentication.
     */
    public synchronized void setBackendProvider(DynamicLlmProvider provider) {
        initialize();
        if (backendProvider != null && backendProvider != provider) {
            try {
                backendProvider.dispose();
            } catch (Exception e) {
                VibeCorePlugin.logWarn("Error disposing backend provider", e); //$NON-NLS-1$
            }
        }
        backendProvider = provider;
        updateConfigurationState();
    }

    /**
     * Clears the transient backend provider.
     */
    public synchronized void clearBackendProvider() {
        if (backendProvider != null) {
            try {
                backendProvider.dispose();
            } catch (Exception e) {
                VibeCorePlugin.logWarn("Error disposing backend provider", e); //$NON-NLS-1$
            }
            backendProvider = null;
        }
        if (configStore != null && "backend".equals(configStore.getActiveProviderId())) { //$NON-NLS-1$
            String fallbackProviderId = findFallbackProviderId();
            configStore.setActiveProviderId(fallbackProviderId != null ? fallbackProviderId : ""); //$NON-NLS-1$
        }
        updateConfigurationState();
    }

    /**
     * Sets the active provider.
     *
     * @param id the provider ID
     */
    public void setActiveProvider(String id) {
        initialize();

        if (backendProvider != null && backendProvider.isConfigured() && backendProvider.getId().equals(id)) {
            configStore.setActiveProviderId(id);
            updateConfigurationState();
            return;
        }

        // Check if it's a dynamic provider
        if (dynamicProviders.containsKey(id)) {
            configStore.setActiveProviderId(id);
            updateConfigurationState();
            return;
        }

        // Check legacy providers
        if (!legacyProviders.containsKey(id)) {
            throw new IllegalArgumentException("Unknown provider: " + id); //$NON-NLS-1$
        }

        IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(VibeCorePlugin.PLUGIN_ID);
        prefs.put(VibePreferenceConstants.PREF_PROVIDER_ID, id);
        try {
            prefs.flush();
        } catch (BackingStoreException e) {
            VibeCorePlugin.logWarn("Failed to persist provider preference", e); //$NON-NLS-1$
        }
        configStore.setActiveProviderId(id);
        updateConfigurationState();
    }

    /**
     * Returns the configured backend provider, if available.
     *
     * @return backend provider or {@code null}
     */
    public ILlmProvider getBackendProvider() {
        initialize();
        return backendProvider;
    }

    /**
     * Returns the effective active provider ID.
     *
     * @return active provider ID or {@code null}
     */
    public String getEffectiveActiveProviderId() {
        ILlmProvider provider = getActiveProvider();
        return provider != null ? provider.getId() : null;
    }

    private String findFallbackProviderId() {
        for (DynamicLlmProvider provider : dynamicProviders.values()) {
            if (provider.isConfigured()) {
                return provider.getId();
            }
        }

        IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(VibeCorePlugin.PLUGIN_ID);
        String legacyPreferredId = prefs.get(VibePreferenceConstants.PREF_PROVIDER_ID, ""); //$NON-NLS-1$
        ILlmProvider preferredLegacy = legacyProviders.get(legacyPreferredId);
        if (preferredLegacy != null && preferredLegacy.isConfigured()) {
            return preferredLegacy.getId();
        }

        for (ILlmProvider provider : legacyProviders.values()) {
            if (provider.isConfigured()) {
                return provider.getId();
            }
        }

        return null;
    }

    /**
     * Returns the configuration store for managing provider configs.
     */
    public LlmProviderConfigStore getConfigStore() {
        initialize();
        return configStore;
    }

    /**
     * Disposes all providers and clears the registry.
     */
    public synchronized void dispose() {
        if (configStore != null) {
            try {
                configStore.removeListener(configListener);
            } catch (Exception e) {
                // ignore
            }
        }

        if (backendProvider != null) {
            try {
                backendProvider.dispose();
            } catch (Exception e) {
                VibeCorePlugin.logWarn("Error disposing backend provider", e); //$NON-NLS-1$
            }
            backendProvider = null;
        }
        for (DynamicLlmProvider provider : dynamicProviders.values()) {
            try {
                provider.dispose();
            } catch (Exception e) {
                VibeCorePlugin.logWarn("Error disposing dynamic provider: " + provider.getId(), e); //$NON-NLS-1$
            }
        }
        dynamicProviders.clear();

        for (ILlmProvider provider : legacyProviders.values()) {
            try {
                provider.dispose();
            } catch (Exception e) {
                VibeCorePlugin.logWarn("Error disposing provider: " + provider.getId(), e); //$NON-NLS-1$
            }
        }
        legacyProviders.clear();

        initialized = false;
    }
}
