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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.codepilot1c.core.agent.AgentConfig;
import com.codepilot1c.core.agent.prompts.AgentPromptTemplates;

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
        register(new PlanAgentProfile());
        register(new ExploreAgentProfile());
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

    /**
     * Возвращает все зарегистрированные профили.
     *
     * @return коллекция профилей
     */
    public Collection<AgentProfile> getAllProfiles() {
        return Collections.unmodifiableCollection(profiles.values());
    }

    /**
     * Возвращает профиль по умолчанию.
     *
     * @return профиль по умолчанию
     */
    public AgentProfile getDefaultProfile() {
        return profiles.getOrDefault(defaultProfileId, new BuildAgentProfile());
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
        AgentConfig.Builder builder = AgentConfig.builder()
                .maxSteps(profile.getMaxSteps())
                .timeoutMs(profile.getTimeoutMs())
                .systemPromptAddition(profile.getSystemPromptAddition())
                .profileName(profile.getId());

        // Enable only allowed tools
        for (String tool : profile.getAllowedTools()) {
            builder.enableTool(tool);
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
        AgentProfile profile = getProfile(profileId).orElse(getDefaultProfile());
        return createConfig(profile);
    }

    /**
     * Возвращает профиль "build" (разработка).
     */
    public AgentProfile getBuildProfile() {
        return profiles.get(BuildAgentProfile.ID);
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
