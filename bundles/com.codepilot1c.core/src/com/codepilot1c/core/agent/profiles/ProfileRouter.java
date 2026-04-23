/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.profiles;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Routes generic prompts to the most relevant agent profile.
 */
public final class ProfileRouter {

    private static final Set<String> AUTO_PROFILE_IDS = Set.of(
            "", //$NON-NLS-1$
            "auto", //$NON-NLS-1$
            BuildAgentProfile.ID);

    private static final Map<String, Set<String>> DOMAIN_KEYWORDS = new LinkedHashMap<>();

    private static final Set<String> PLAN_KEYWORDS = Set.of(
            "план", //$NON-NLS-1$
            "roadmap", //$NON-NLS-1$
            "архитект", //$NON-NLS-1$
            "оцен", //$NON-NLS-1$
            "design", //$NON-NLS-1$
            "refactor plan"); //$NON-NLS-1$

    private static final Set<String> EXPLORE_KEYWORDS = Set.of(
            "найд", //$NON-NLS-1$
            "где ", //$NON-NLS-1$
            "покаж", //$NON-NLS-1$
            "исслед", //$NON-NLS-1$
            "проанализир", //$NON-NLS-1$
            "locate", //$NON-NLS-1$
            "search", //$NON-NLS-1$
            "grep"); //$NON-NLS-1$

    static {
        DOMAIN_KEYWORDS.put(CodeBuildProfile.ID, Set.of(
                "bsl", //$NON-NLS-1$
                "процед", //$NON-NLS-1$
                "функци", //$NON-NLS-1$
                "модул", //$NON-NLS-1$
                "commonmodule", //$NON-NLS-1$
                "обычн", //$NON-NLS-1$
                "код")); //$NON-NLS-1$
        DOMAIN_KEYWORDS.put(MetadataBuildProfile.ID, Set.of(
                "справочник", //$NON-NLS-1$
                "документ", //$NON-NLS-1$
                "регистр", //$NON-NLS-1$
                "форма", //$NON-NLS-1$
                "реквизит", //$NON-NLS-1$
                "команда", //$NON-NLS-1$
                "metadata", //$NON-NLS-1$
                "конфигурац")); //$NON-NLS-1$
        DOMAIN_KEYWORDS.put(QABuildProfile.ID, Set.of(
                "тест", //$NON-NLS-1$
                "qa", //$NON-NLS-1$
                "vanessa", //$NON-NLS-1$
                "yaxunit", //$NON-NLS-1$
                "сценар", //$NON-NLS-1$
                "feature", //$NON-NLS-1$
                "gherkin")); //$NON-NLS-1$
        DOMAIN_KEYWORDS.put(DCSBuildProfile.ID, Set.of(
                "скд", //$NON-NLS-1$
                "компоновк", //$NON-NLS-1$
                "dataset", //$NON-NLS-1$
                "schema", //$NON-NLS-1$
                "набор данных")); //$NON-NLS-1$
        DOMAIN_KEYWORDS.put(ExtensionBuildProfile.ID, Set.of(
                "расширени", //$NON-NLS-1$
                "extension", //$NON-NLS-1$
                "внешн", //$NON-NLS-1$
                "обработк", //$NON-NLS-1$
                "отчет", //$NON-NLS-1$
                "отчёт")); //$NON-NLS-1$
        DOMAIN_KEYWORDS.put(RecoveryProfile.ID, Set.of(
                "диагност", //$NON-NLS-1$
                "smoke", //$NON-NLS-1$
                "trace", //$NON-NLS-1$
                "не запуска", //$NON-NLS-1$
                "ошибк", //$NON-NLS-1$
                "восстанов", //$NON-NLS-1$
                "recovery", //$NON-NLS-1$
                "runtime")); //$NON-NLS-1$
    }

    /**
     * Routes a prompt to a direct domain profile or to the orchestrator.
     */
    public String route(String prompt) {
        String normalized = normalize(prompt);
        if (normalized.isEmpty()) {
            return CodeBuildProfile.ID;
        }

        Set<String> matchedProfiles = new LinkedHashSet<>();
        for (Map.Entry<String, Set<String>> entry : DOMAIN_KEYWORDS.entrySet()) {
            if (containsAny(normalized, entry.getValue())) {
                matchedProfiles.add(entry.getKey());
            }
        }

        if (containsAny(normalized, PLAN_KEYWORDS)) {
            return PlanAgentProfile.ID;
        }
        if (matchedProfiles.size() > 1) {
            return OrchestratorProfile.ID;
        }
        if (containsAny(normalized, EXPLORE_KEYWORDS)) {
            return ExploreAgentProfile.ID;
        }
        if (matchedProfiles.size() == 1) {
            return matchedProfiles.iterator().next();
        }
        return CodeBuildProfile.ID;
    }

    /**
     * Resolves the final profile considering explicit profile choice and auto-routing.
     */
    public String resolveRequestedProfile(String prompt, String requestedProfileId) {
        String normalizedProfile = normalizeProfileId(requestedProfileId);
        if (!AUTO_PROFILE_IDS.contains(normalizedProfile)) {
            return normalizedProfile;
        }
        return route(prompt);
    }

    /**
     * Maps delegate tool agent types to actual profile ids.
     */
    public String normalizeProfileId(String profileId) {
        String normalized = normalize(profileId);
        if (normalized.isEmpty()) {
            return ""; //$NON-NLS-1$
        }
        return switch (normalized) {
            case "auto" -> "auto"; //$NON-NLS-1$ //$NON-NLS-2$
            case "build", "разработка" -> BuildAgentProfile.ID; //$NON-NLS-1$ //$NON-NLS-2$
            case "code", "bsl", "код" -> CodeBuildProfile.ID; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "metadata", "meta", "метаданные" -> MetadataBuildProfile.ID; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "qa", "test", "tests" -> QABuildProfile.ID; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "dcs", "скд" -> DCSBuildProfile.ID; //$NON-NLS-1$ //$NON-NLS-2$
            case "extension", "external", "расширение", "расширения" -> ExtensionBuildProfile.ID; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "recovery", "diag", "диагностика" -> RecoveryProfile.ID; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "plan", "planning" -> PlanAgentProfile.ID; //$NON-NLS-1$ //$NON-NLS-2$
            case "explore", "search", "исследование" -> ExploreAgentProfile.ID; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "orchestrator", "оркестратор" -> OrchestratorProfile.ID; //$NON-NLS-1$ //$NON-NLS-2$
            default -> normalized;
        };
    }

    public Set<String> supportedDelegateTargets() {
        return Set.of(
                "auto", //$NON-NLS-1$
                CodeBuildProfile.ID,
                MetadataBuildProfile.ID,
                QABuildProfile.ID,
                DCSBuildProfile.ID,
                ExtensionBuildProfile.ID,
                RecoveryProfile.ID,
                PlanAgentProfile.ID,
                ExploreAgentProfile.ID,
                OrchestratorProfile.ID);
    }

    private boolean containsAny(String prompt, Set<String> keywords) {
        for (String keyword : keywords) {
            if (prompt.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
