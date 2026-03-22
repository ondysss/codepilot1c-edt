/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.prompts;

import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.Platform;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.provider.ProviderSelectionGate;
import com.codepilot1c.core.settings.PromptTemplateService;

/**
 * Loads an optional {@link IPromptProvider} from an extension point and
 * provides fallback to OSS default prompts.
 */
public final class PromptProviderRegistry {

    private static final VibeLogger.CategoryLogger LOG =
            VibeLogger.forClass(PromptProviderRegistry.class);

    private static final String EXTENSION_POINT_ID =
            "com.codepilot1c.core.promptProvider"; //$NON-NLS-1$

    private static PromptProviderRegistry instance;

    private volatile IPromptProvider provider;

    private PromptProviderRegistry() {
        // lazy load
    }

    public static synchronized PromptProviderRegistry getInstance() {
        if (instance == null) {
            instance = new PromptProviderRegistry();
        }
        return instance;
    }

    public String getSystemPromptAddition(String profileId, String defaultText) {
        return getSystemPromptAddition(profileId, defaultText, ProviderSelectionGate.isCodePilotSelectedInUi());
    }

    public String getSystemPromptAddition(String profileId, String defaultText, boolean backendSelectedInUi) {
        IPromptProvider p = getProvider();
        String effective = defaultText;
        if (p != null) {
            try {
                String override = p.getSystemPromptAddition(profileId);
                if (override != null && !override.isEmpty()) {
                    effective = override;
                }
            } catch (Exception e) {
                LOG.warn("PromptProvider failed for profileId=%s: %s", profileId, e.getMessage()); //$NON-NLS-1$
            }
        }
        return PromptTemplateService.getInstance().applyFilesystemSystemPromptOverride(effective, backendSelectedInUi);
    }

    private IPromptProvider getProvider() {
        if (provider != null) {
            return provider;
        }

        synchronized (this) {
            if (provider != null) {
                return provider;
            }
            provider = loadFromExtensionPoint();
            return provider;
        }
    }

    private IPromptProvider loadFromExtensionPoint() {
        IExtensionRegistry registry = Platform.getExtensionRegistry();
        if (registry == null) {
            return null;
        }

        IConfigurationElement[] elements = registry.getConfigurationElementsFor(EXTENSION_POINT_ID);
        for (IConfigurationElement element : elements) {
            if (!"provider".equals(element.getName())) { //$NON-NLS-1$
                continue;
            }
            try {
                Object inst = element.createExecutableExtension("class"); //$NON-NLS-1$
                if (inst instanceof IPromptProvider p) {
                    LOG.info("Loaded prompt provider from extension point"); //$NON-NLS-1$
                    return p;
                }
            } catch (Exception e) {
                LOG.error("Failed to load prompt provider", e); //$NON-NLS-1$
            }
        }

        return null;
    }
}
