/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.profiles;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.codepilot1c.core.agent.AgentConfig;
import com.codepilot1c.core.agent.prompts.AgentPromptTemplates;
import com.codepilot1c.core.gsd.GsdFeatureGate;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolRegistry;

/**
 * Реестр профилей агентов.
 *
 * <p>Предоставляет доступ к зарегистрированным профилям и позволяет
 * создавать конфигурации на их основе.</p>
 */
public class AgentProfileRegistry {

    private static AgentProfileRegistry instance;

    private final Map<String, AgentProfile> profiles = new LinkedHashMap<>();
    private String defaultProfileId = BuildAgentProfile.ID;

    /**
     * Возвращает единственный экземпляр реестра.
     */
    public static synchronized AgentProfileRegistry getInstance() {
        if (instance == null) {
            instance = new AgentProfileRegistry();
            instance.registerDefaultProfiles();
        }
        return instance;
    }

    /**
     * Регистрирует профили по умолчанию.
     */
    private void registerDefaultProfiles() {
        AgentPromptTemplates.runStartupChecks();
        register(new BuildAgentProfile());
        register(new OrchestratorProfile());
        register(new InitAgentProfile());
        register(new CodeBuildProfile());
        register(new MetadataBuildProfile());
        register(new QABuildProfile());
        register(new DCSBuildProfile());
        register(new ExtensionBuildProfile());
        register(new RecoveryProfile());
        register(new PlanAgentProfile());
        register(new ExploreAgentProfile());
        register(new GsdDiscussProfile());
        register(new GsdPlanProfile());
        register(new GsdExecuteProfile());
        register(new GsdVerifyProfile());
        register(new GsdShipProfile());
    }

    /**
     * Регистрирует профиль.
     *
     * @param profile профиль
     */
    public void register(AgentProfile profile) {
        if (profile != null) {
            profiles.put(profile.getId(), profile);
        }
    }

    /**
     * Удаляет профиль из реестра.
     *
     * @param profileId ID профиля
     * @return true если был удален
     */
    public boolean unregister(String profileId) {
        return profiles.remove(profileId) != null;
    }

    /**
     * Возвращает профиль по ID.
     *
     * @param profileId ID профиля
     * @return профиль или empty
     */
    public Optional<AgentProfile> getProfile(String profileId) {
        return Optional.ofNullable(profiles.get(profileId));
    }

    /** Returns a registered profile only when its feature is currently available. */
    public Optional<AgentProfile> getAvailableProfile(String profileId) {
        AgentProfile profile = profiles.get(profileId);
        return profile != null && GsdFeatureGate.getInstance()
                .isProfileAvailable(profile.getId())
                ? Optional.of(profile)
                : Optional.empty();
    }

    /**
     * Возвращает все доступные профили.
     *
     * @return коллекция профилей
     */
    public Collection<AgentProfile> getAllProfiles() {
        return getAvailableProfiles();
    }

    /**
     * Returns profiles that may currently be selected by UI, remote, MCP, and
     * other runtime consumers.
     */
    public Collection<AgentProfile> getAvailableProfiles() {
        GsdFeatureGate gate = GsdFeatureGate.getInstance();
        return profiles.values().stream()
                .filter(profile -> gate.isProfileAvailable(profile.getId()))
                .toList();
    }

    /** Returns every registered profile, including feature-disabled profiles. */
    public Collection<AgentProfile> getRegisteredProfiles() {
        return Collections.unmodifiableCollection(profiles.values());
    }

    /**
     * Возвращает профиль по умолчанию.
     *
     * @return профиль по умолчанию
     */
    public AgentProfile getDefaultProfile() {
        return getAvailableProfile(defaultProfileId)
                .orElseGet(() -> profiles.getOrDefault(
                        BuildAgentProfile.ID, new BuildAgentProfile()));
    }

    /**
     * Устанавливает ID профиля по умолчанию.
     *
     * @param profileId ID профиля
     */
    public void setDefaultProfileId(String profileId) {
        if (profiles.containsKey(profileId)) {
            this.defaultProfileId = profileId;
        }
    }

    /**
     * Создает конфигурацию агента на основе профиля.
     *
     * @param profile профиль
     * @return конфигурация
     */
    public AgentConfig createConfig(AgentProfile profile) {
        if (profile == null || !GsdFeatureGate.getInstance()
                .isProfileAvailable(profile.getId())) {
            throw new IllegalArgumentException("Agent profile is not available: " //$NON-NLS-1$
                    + (profile != null ? profile.getId() : "")); //$NON-NLS-1$
        }
        ProfileOverride override = ProfileConfigStore.getInstance()
                .getOverride(profile.getId()).orElse(null);

        int maxSteps = (override != null && override.maxSteps() != null)
                ? override.maxSteps() : profile.getMaxSteps();
        long timeoutMs = (override != null && override.timeoutMs() != null)
                ? override.timeoutMs() : profile.getTimeoutMs();

        String promptAddition = profile.getSystemPromptAddition();
        if (override != null && override.additionalPrompt() != null
                && !override.additionalPrompt().isBlank()) {
            promptAddition = (promptAddition != null ? promptAddition + "\n" : "") //$NON-NLS-1$ //$NON-NLS-2$
                    + override.additionalPrompt();
        }
        if (profile.getId().startsWith("gsd-")) { //$NON-NLS-1$
            ToolRegistry registry = ToolRegistry.getInstance();
            var effectiveTools = new HashSet<>(
                    ProfileToolAccess.effectiveToolNames(profile, registry));
            if (override != null && override.disabledTools() != null) {
                effectiveTools.removeAll(override.disabledTools());
            }
            promptAddition = AgentPromptTemplates.enforceGsdToolParity(
                    promptAddition,
                    Set.copyOf(effectiveTools),
                    registry.getAllTools().stream().map(ITool::getName).toList());
        }

        AgentConfig.Builder builder = AgentConfig.builder()
                .maxSteps(maxSteps)
                .timeoutMs(timeoutMs)
                .systemPromptAddition(promptAddition)
                .profileName(profile.getId());

        // Enable all profile tools
        for (String tool : profile.getAllowedTools()) {
            builder.enableTool(tool);
        }

        // Apply user's disabled tools blacklist via AgentConfig's native mechanism
        if (override != null && override.disabledTools() != null
                && !override.disabledTools().isEmpty()) {
            builder.disabledTools(override.disabledTools());
        }

        return builder.build();
    }

    /**
     * Создает конфигурацию агента на основе профиля по ID.
     *
     * @param profileId ID профиля
     * @return конфигурация или конфигурация по умолчанию
     */
    public AgentConfig createConfig(String profileId) {
        Optional<AgentProfile> registered = getProfile(profileId);
        if (registered.isPresent() && getAvailableProfile(profileId).isEmpty()) {
            throw new IllegalArgumentException(
                    "Agent profile is disabled: " + profileId); //$NON-NLS-1$
        }
        AgentProfile profile = getAvailableProfile(profileId).orElse(getDefaultProfile());
        return createConfig(profile);
    }

    /**
     * Возвращает профиль "build" (разработка).
     */
    public AgentProfile getBuildProfile() {
        return profiles.get(BuildAgentProfile.ID);
    }

    /**
     * Возвращает профиль "orchestrator" (координация подагентов).
     */
    public AgentProfile getOrchestratorProfile() {
        return profiles.get(OrchestratorProfile.ID);
    }

    /**
     * Возвращает профиль "plan" (планирование).
     */
    public AgentProfile getPlanProfile() {
        return profiles.get(PlanAgentProfile.ID);
    }

    /**
     * Возвращает профиль "explore" (исследование).
     */
    public AgentProfile getExploreProfile() {
        return profiles.get(ExploreAgentProfile.ID);
    }
}
