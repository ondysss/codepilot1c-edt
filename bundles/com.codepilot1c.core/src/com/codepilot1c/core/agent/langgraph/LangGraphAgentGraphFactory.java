/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.langgraph;

import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.AgentState;

final class LangGraphAgentGraphFactory {

    static final String NODE_RUN_AGENT = "run_agent"; //$NON-NLS-1$

    private LangGraphAgentGraphFactory() {
    }

    static StateGraph<AgentState> buildGraph(LangGraphAgentRunContext context) {
        StateGraph<AgentState> graph = new StateGraph<>(AgentState::new);

        try {
            graph.addNode(NODE_RUN_AGENT, AsyncNodeAction.node_async(state -> {
                String prompt = state.value("prompt", ""); //$NON-NLS-1$ //$NON-NLS-2$
                return context.run(prompt);
            }));

            graph.addEdge(StateGraph.START, NODE_RUN_AGENT);
            graph.addEdge(NODE_RUN_AGENT, StateGraph.END);
        } catch (GraphStateException e) {
            throw new IllegalStateException("Failed to build LangGraph agent graph", e); //$NON-NLS-1$
        }

        return graph;
    }
}
