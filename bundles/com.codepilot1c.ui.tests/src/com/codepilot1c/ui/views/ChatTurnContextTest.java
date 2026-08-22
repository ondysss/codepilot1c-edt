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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.agent.profiles.AgentProfileRegistry;
import com.codepilot1c.core.agent.profiles.DynamicToolCapability;
import com.codepilot1c.core.agent.prompts.SystemPromptAssembler;
import com.codepilot1c.core.session.Session;
import com.codepilot1c.core.model.ToolCall;
import com.codepilot1c.core.tools.ToolRegistry.ToolResolution;
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
                context.profile(), List::of, ignored -> Map.of(),
                () -> true, () -> false, context.toolExecutionContext());

        SystemPromptAssembler.AssemblyInput input = context.promptInput(
                "base", List.of("review")); //$NON-NLS-1$ //$NON-NLS-2$

        assertSame(context.profile(), gate.profile());
        assertEquals(gate.profile().getId(), context.profileId());
        assertEquals(gate.profile().getId(), input.profileName());
        assertEquals(AgentProfileRegistry.getInstance().createConfig(context.profile())
                .getSystemPromptAddition(), input.promptAddition());
        assertEquals(session.getProjectPath(), input.projectPath());
        assertEquals(session.getId(), input.sessionId());
        assertEquals(session.getProjectPath(),
                gate.decide(new ToolCall("c1", "dynamic", "{}"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        unknownResolution("dynamic")) //$NON-NLS-1$
                        .context().projectPath());
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

    @Test
    public void twoChatViewsBindIndependentProjectAndSessionExecutionIdentity() {
        Session first = new Session("chat-a"); //$NON-NLS-1$
        first.setProjectPath("/workspace/project-a"); //$NON-NLS-1$
        Session second = new Session("chat-b"); //$NON-NLS-1$
        second.setProjectPath("/workspace/project-b"); //$NON-NLS-1$

        ChatTurnContext firstTurn = ChatTurnContext.resolve(first, "build"); //$NON-NLS-1$
        ChatTurnContext secondTurn = ChatTurnContext.resolve(second, "build"); //$NON-NLS-1$
        ChatToolGate firstGate = gate(firstTurn);
        ChatToolGate secondGate = gate(secondTurn);

        assertEquals("/workspace/project-a", decision(firstGate).context().projectPath()); //$NON-NLS-1$
        assertEquals("chat-a", decision(firstGate).context().sessionId()); //$NON-NLS-1$
        assertEquals("/workspace/project-b", decision(secondGate).context().projectPath()); //$NON-NLS-1$
        assertEquals("chat-b", decision(secondGate).context().sessionId()); //$NON-NLS-1$
    }

    @Test
    public void relativeSessionProjectIsNormalizedForPromptAndToolIdentity() {
        Session session = new Session("chat-relative"); //$NON-NLS-1$
        session.setProjectPath("workspace/../workspace/project"); //$NON-NLS-1$

        ChatTurnContext context = ChatTurnContext.resolve(session, "build"); //$NON-NLS-1$
        String expected = Path.of(session.getProjectPath())
                .toAbsolutePath().normalize().toString();

        assertEquals(expected, context.toolExecutionContext().projectPath());
        assertEquals(expected, context.promptInput("base", List.of()).projectPath()); //$NON-NLS-1$
        assertTrue(Path.of(context.toolExecutionContext().projectPath()).isAbsolute());
    }

    private ChatToolGate gate(ChatTurnContext context) {
        return new ChatToolGate(context.profile(), List::of, ignored -> Map.of(),
                () -> true, () -> false, context.toolExecutionContext());
    }

    private ChatToolGate.Decision decision(ChatToolGate gate) {
        return gate.decide(new ToolCall("call", "dynamic", "{}"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                unknownResolution("dynamic")); //$NON-NLS-1$
    }

    private ToolResolution unknownResolution(String name) {
        return new ToolResolution(
                name, null, DynamicToolCapability.NONE, false, null);
    }
}
