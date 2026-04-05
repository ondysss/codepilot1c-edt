/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.internal;

import java.util.Locale;
import java.util.Map;

/**
 * Maps skill names and agent profile names to display icons and labels.
 *
 * <p>Used by {@link com.codepilot1c.ui.views.ToolCallWidget} to show
 * skill/agent-specific visual indicators in the chat UI.</p>
 */
public final class SkillDisplayInfo {

    /** Skill name -> icon emoji. */
    private static final Map<String, String> SKILL_ICONS = Map.ofEntries(
            Map.entry("review", "\uD83D\uDD0D"),        // 🔍 — поиск дефектов
            Map.entry("refactor", "\u267B\uFE0F"),       // ♻️ — переработка кода
            Map.entry("explain", "\uD83D\uDCA1"),        // 💡 — объяснение/идея
            Map.entry("architect", "\uD83D\uDCCF"),      // 📏 — проектирование/чертёж
            Map.entry("validator", "\uD83D\uDEE1\uFE0F") // 🛡️ — защита/валидация
    );

    /** Agent profile -> icon emoji. */
    private static final Map<String, String> PROFILE_ICONS = Map.ofEntries(
            Map.entry("build", "\uD83D\uDD28"),         // 🔨
            Map.entry("code", "\uD83D\uDCBB"),          // 💻
            Map.entry("metadata", "\uD83D\uDDC2\uFE0F"), // 🗂️
            Map.entry("qa", "\uD83E\uDDEA"),            // 🧪
            Map.entry("dcs", "\uD83D\uDCCA"),           // 📊
            Map.entry("extension", "\uD83E\uDDE9"),     // 🧩
            Map.entry("recovery", "\uD83D\uDEE0\uFE0F"), // 🛠️
            Map.entry("plan", "\uD83D\uDCCB"),          // 📋
            Map.entry("explore", "\uD83D\uDD2D"),       // 🔭
            Map.entry("orchestrator", "\uD83C\uDFAF"),  // 🎯
            Map.entry("auto", "\u2699\uFE0F")            // ⚙️
    );

    /** Skill name -> NLS label field from Messages. */
    private static final Map<String, String> SKILL_LABELS = Map.ofEntries(
            Map.entry("review", Messages.SkillDisplayInfo_SkillLabel_review),
            Map.entry("refactor", Messages.SkillDisplayInfo_SkillLabel_refactor),
            Map.entry("explain", Messages.SkillDisplayInfo_SkillLabel_explain),
            Map.entry("architect", Messages.SkillDisplayInfo_SkillLabel_architect),
            Map.entry("validator", Messages.SkillDisplayInfo_SkillLabel_validator)
    );

    /** Agent profile -> NLS label field from Messages. */
    private static final Map<String, String> PROFILE_LABELS = Map.ofEntries(
            Map.entry("build", Messages.SkillDisplayInfo_ProfileLabel_build),
            Map.entry("code", Messages.SkillDisplayInfo_ProfileLabel_code),
            Map.entry("metadata", Messages.SkillDisplayInfo_ProfileLabel_metadata),
            Map.entry("qa", Messages.SkillDisplayInfo_ProfileLabel_qa),
            Map.entry("dcs", Messages.SkillDisplayInfo_ProfileLabel_dcs),
            Map.entry("extension", Messages.SkillDisplayInfo_ProfileLabel_extension),
            Map.entry("recovery", Messages.SkillDisplayInfo_ProfileLabel_recovery),
            Map.entry("plan", Messages.SkillDisplayInfo_ProfileLabel_plan),
            Map.entry("explore", Messages.SkillDisplayInfo_ProfileLabel_explore),
            Map.entry("orchestrator", Messages.SkillDisplayInfo_ProfileLabel_orchestrator),
            Map.entry("auto", Messages.SkillDisplayInfo_ProfileLabel_auto)
    );

    private static final String DEFAULT_SKILL_ICON = "\u2728";  // ✨
    private static final String DEFAULT_PROFILE_ICON = "\uD83E\uDD16"; // 🤖
    private static final String SKILL_TOOL_ICON = "\uD83D\uDD27"; // 🔧 fallback

    private SkillDisplayInfo() {
    }

    /**
     * Returns the icon for a skill name.
     *
     * @param skillName the skill name (e.g. "review", "architect")
     * @return emoji icon string
     */
    public static String getSkillIcon(String skillName) {
        if (skillName == null) {
            return DEFAULT_SKILL_ICON;
        }
        return SKILL_ICONS.getOrDefault(skillName.trim().toLowerCase(Locale.ROOT), DEFAULT_SKILL_ICON);
    }

    /**
     * Returns the display label for a skill name.
     *
     * @param skillName the skill name
     * @return human-readable label (NLS)
     */
    public static String getSkillLabel(String skillName) {
        if (skillName == null) {
            return ""; //$NON-NLS-1$
        }
        String key = skillName.trim().toLowerCase(Locale.ROOT);
        return SKILL_LABELS.getOrDefault(key, skillName);
    }

    /**
     * Returns the icon for an agent profile.
     *
     * @param profileName the profile name (e.g. "code", "metadata", "qa")
     * @return emoji icon string
     */
    public static String getProfileIcon(String profileName) {
        if (profileName == null) {
            return DEFAULT_PROFILE_ICON;
        }
        return PROFILE_ICONS.getOrDefault(profileName.trim().toLowerCase(Locale.ROOT), DEFAULT_PROFILE_ICON);
    }

    /**
     * Returns the display label for an agent profile.
     *
     * @param profileName the profile name
     * @return human-readable label (NLS)
     */
    public static String getProfileLabel(String profileName) {
        if (profileName == null) {
            return ""; //$NON-NLS-1$
        }
        String key = profileName.trim().toLowerCase(Locale.ROOT);
        return PROFILE_LABELS.getOrDefault(key, profileName);
    }

    /**
     * Checks if the tool name is a skill or task call that should show
     * a specialized display in the chat UI.
     *
     * @param toolName the tool name
     * @return true if the tool should display skill/agent info
     */
    public static boolean isSkillOrTaskTool(String toolName) {
        return "skill".equals(toolName) || "task".equals(toolName) //$NON-NLS-1$ //$NON-NLS-2$
                || "delegate_to_agent".equals(toolName); //$NON-NLS-1$
    }
}
