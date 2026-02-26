/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.langgraph;

import java.util.HashMap;
import java.util.Map;

import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.AgentState;

final class LangGraphAgentGraphFactory {

    static final String NODE_SELECT = "select_agent"; //$NON-NLS-1$
    static final String NODE_AGENT_GENERAL = "agent_general"; //$NON-NLS-1$
    static final String NODE_AGENT_BSL = "agent_bsl"; //$NON-NLS-1$
    static final String NODE_AGENT_METADATA = "agent_metadata"; //$NON-NLS-1$
    static final String NODE_AGENT_FORMS = "agent_forms"; //$NON-NLS-1$

    private LangGraphAgentGraphFactory() {
    }

    static StateGraph<AgentState> buildGraph(LangGraphAgentRunContext context) {
        StateGraph<AgentState> graph = new StateGraph<>(AgentState::new);

        try {
            graph.addNode(NODE_SELECT, AsyncNodeAction.node_async(state -> {
                String prompt = state.value("prompt", ""); //$NON-NLS-1$ //$NON-NLS-2$
                LangGraphAgentDomain domain = context.resolveDomain(prompt);
                Map<String, Object> update = new HashMap<>();
                update.put("agentId", domain.getId()); //$NON-NLS-1$
                update.put("agentName", domain.getDisplayName()); //$NON-NLS-1$
                return update;
            }));

            graph.addNode(NODE_AGENT_GENERAL, AsyncNodeAction.node_async(state -> {
                String prompt = state.value("prompt", ""); //$NON-NLS-1$ //$NON-NLS-2$
                return context.runDomain(LangGraphAgentDomain.GENERAL, prompt);
            }));

            graph.addNode(NODE_AGENT_BSL, AsyncNodeAction.node_async(state -> {
                String prompt = state.value("prompt", ""); //$NON-NLS-1$ //$NON-NLS-2$
                return context.runDomain(LangGraphAgentDomain.BSL, prompt);
            }));

            graph.addNode(NODE_AGENT_METADATA, AsyncNodeAction.node_async(state -> {
                String prompt = state.value("prompt", ""); //$NON-NLS-1$ //$NON-NLS-2$
                return context.runDomain(LangGraphAgentDomain.METADATA, prompt);
            }));

            graph.addNode(NODE_AGENT_FORMS, AsyncNodeAction.node_async(state -> {
                String prompt = state.value("prompt", ""); //$NON-NLS-1$ //$NON-NLS-2$
                return context.runDomain(LangGraphAgentDomain.FORMS, prompt);
            }));

            graph.addEdge(StateGraph.START, NODE_SELECT);

            Map<String, String> routes = Map.of(
                    LangGraphAgentDomain.GENERAL.getId(), NODE_AGENT_GENERAL,
                    LangGraphAgentDomain.BSL.getId(), NODE_AGENT_BSL,
                    LangGraphAgentDomain.METADATA.getId(), NODE_AGENT_METADATA,
                    LangGraphAgentDomain.FORMS.getId(), NODE_AGENT_FORMS
            );

            graph.addConditionalEdges(NODE_SELECT,
                    AsyncEdgeAction.edge_async(state -> state.value(
                            "agentId", LangGraphAgentDomain.GENERAL.getId())), //$NON-NLS-1$
                    routes);

            graph.addEdge(NODE_AGENT_GENERAL, StateGraph.END);
            graph.addEdge(NODE_AGENT_BSL, StateGraph.END);
            graph.addEdge(NODE_AGENT_METADATA, StateGraph.END);
            graph.addEdge(NODE_AGENT_FORMS, StateGraph.END);
        } catch (GraphStateException e) {
            throw new IllegalStateException("Failed to build LangGraph agent graph", e); //$NON-NLS-1$
        }

        return graph;
    }
}
