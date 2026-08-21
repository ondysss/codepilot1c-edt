/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.views;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.codepilot1c.core.agent.profiles.AgentProfileRegistry;
import com.codepilot1c.core.agent.prompts.SystemPromptAssembler;
import com.codepilot1c.core.session.Session;
import com.codepilot1c.core.ui.ChatToolGate;

/** Focused tests for per-view profile selection and turn capture. */
public class ChatTurnContextTest {

    @Test
    public void suggestedProfileSelectionIsStoredOnRequestedSession() {
        Session session = new Session("chat-a"); //$NON-NLS-1$

        assertTrue(ChatTurnContext.selectForSession(session, "gsd-plan")); //$NON-NLS-1$

        assertEquals("gsd-plan", session.getAgentProfile()); //$NON-NLS-1$
        assertEquals("gsd-plan", ChatTurnContext.resolve(session, "build").profileId()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void unknownSuggestedProfileDoesNotReplaceCurrentSelection() {
        Session session = new Session("chat-a"); //$NON-NLS-1$
        session.setAgentProfile("gsd-plan"); //$NON-NLS-1$

        assertFalse(ChatTurnContext.selectForSession(session, "missing-profile")); //$NON-NLS-1$

        assertEquals("gsd-plan", session.getAgentProfile()); //$NON-NLS-1$
    }

    @Test
    public void promptAndToolSurfaceUseSameEffectiveProfileInstanceAndId() {
        Session session = new Session("chat-a"); //$NON-NLS-1$
        session.setAgentProfile("gsd-execute"); //$NON-NLS-1$
        session.setProjectPath("/workspace/project-a"); //$NON-NLS-1$
        ChatTurnContext context = ChatTurnContext.resolve(session, "build"); //$NON-NLS-1$
        ChatToolGate gate = new ChatToolGate(
                context.profile(), List::of, ignored -> Map.of(), Set::of,
                () -> true, () -> false);

        SystemPromptAssembler.AssemblyInput input = context.promptInput(
                "base", List.of("review")); //$NON-NLS-1$ //$NON-NLS-2$

        assertSame(context.profile(), gate.profile());
        assertEquals(gate.profile().getId(), context.profileId());
        assertEquals(gate.profile().getId(), input.profileName());
        assertEquals(AgentProfileRegistry.getInstance().createConfig(context.profile())
                .getSystemPromptAddition(), input.promptAddition());
        assertEquals(session.getProjectPath(), input.projectPath());
        assertEquals(session.getId(), input.sessionId());
    }

    @Test
    public void twoChatViewsKeepIndependentSessionProfilesAndTurnInstances() {
        Session first = new Session("chat-a"); //$NON-NLS-1$
        Session second = new Session("chat-b"); //$NON-NLS-1$
        ChatTurnContext.selectForSession(first, "gsd-discuss"); //$NON-NLS-1$
        ChatTurnContext.selectForSession(second, "gsd-verify"); //$NON-NLS-1$

        ChatTurnContext firstTurn = ChatTurnContext.resolve(first, "build"); //$NON-NLS-1$
        ChatTurnContext secondTurn = ChatTurnContext.resolve(second, "build"); //$NON-NLS-1$

        assertEquals("gsd-discuss", firstTurn.profileId()); //$NON-NLS-1$
        assertEquals("gsd-verify", secondTurn.profileId()); //$NON-NLS-1$
        assertNotSame(firstTurn, secondTurn);
        assertEquals("gsd-discuss", first.getAgentProfile()); //$NON-NLS-1$
        assertEquals("gsd-verify", second.getAgentProfile()); //$NON-NLS-1$
    }
}
