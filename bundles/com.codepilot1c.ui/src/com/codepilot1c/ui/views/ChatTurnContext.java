/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.views;

import java.util.List;
import java.util.Objects;

import com.codepilot1c.core.agent.profiles.AgentProfile;
import com.codepilot1c.core.agent.profiles.AgentProfileRegistry;
import com.codepilot1c.core.agent.prompts.SystemPromptAssembler;
import com.codepilot1c.core.session.Session;
import com.codepilot1c.core.tools.ToolExecutionContext;
import com.codepilot1c.core.ui.ChatToolGate;

/**
 * Immutable profile and session context captured for one ChatView turn.
 *
 * <p>The same {@link #profile()} instance is used to build the tool gate and
 * the prompt assembly input. Capturing the per-view session path and id here
 * also prevents prompt contributors from accidentally using another
 * ChatView's global current session.</p>
 */
public final class ChatTurnContext {

    private final AgentProfile profile;
    private final String promptAddition;
    private final String projectPath;
    private final String sessionId;

    private ChatTurnContext(AgentProfile profile, String promptAddition,
            String projectPath, String sessionId) {
        this.profile = Objects.requireNonNull(profile, "profile"); //$NON-NLS-1$
        this.promptAddition = promptAddition;
        this.projectPath = projectPath;
        this.sessionId = sessionId;
    }

    /**
     * Resolves the effective profile for a turn. A profile persisted on this
     * view's session wins over the legacy global ChatView preference.
     *
     * @param session this ChatView's session, may be {@code null}
     * @param configuredProfileId legacy global configured profile id
     * @return immutable context for the turn
     */
    public static ChatTurnContext resolve(Session session, String configuredProfileId) {
        String sessionProfileId = session != null ? session.getAgentProfile() : null;
        String requestedProfileId = sessionProfileId != null && !sessionProfileId.isBlank()
                ? sessionProfileId : configuredProfileId;
        AgentProfile profile = ChatToolGate.selectProfile(requestedProfileId);
        String addition = AgentProfileRegistry.getInstance()
                .createConfig(profile).getSystemPromptAddition();
        return new ChatTurnContext(
                profile,
                addition,
                session != null ? session.getProjectPath() : null,
                session != null ? session.getId() : null);
    }

    /**
     * Validates and persists a requested profile on one ChatView session.
     *
     * @param session target per-view session
     * @param profileId requested profile id
     * @return {@code true} when the requested profile exists and was selected
     */
    public static boolean selectForSession(Session session, String profileId) {
        if (session == null || profileId == null || profileId.isBlank()) {
            return false;
        }
        return AgentProfileRegistry.getInstance().getProfile(profileId)
                .map(profile -> {
                    session.setAgentProfile(profile.getId());
                    return true;
                })
                .orElse(false);
    }

    public AgentProfile profile() {
        return profile;
    }

    public String profileId() {
        return profile.getId();
    }

    /** Creates the tool identity from the same immutable turn capture as the prompt. */
    public ToolExecutionContext toolExecutionContext() {
        return ToolExecutionContext.of(profile, 0, projectPath, sessionId);
    }

    /**
     * Creates prompt input from the same effective profile captured for tools.
     */
    public SystemPromptAssembler.AssemblyInput promptInput(
            String basePrompt, List<String> requestedSkills) {
        return new SystemPromptAssembler.AssemblyInput(
                basePrompt,
                promptAddition,
                profile.getId(),
                requestedSkills,
                projectPath,
                sessionId);
    }
}
