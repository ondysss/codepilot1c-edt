/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.langgraph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.checkpoint.MemorySaver;

import com.codepilot1c.core.agent.AgentConfig;
import com.codepilot1c.core.agent.AgentResult;
import com.codepilot1c.core.agent.AgentState;
import com.codepilot1c.core.agent.IAgentRunner;
import com.codepilot1c.core.agent.events.IAgentEventListener;
import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.provider.ILlmProvider;
import com.codepilot1c.core.tools.ToolRegistry;

/**
 * LangGraph-based runner that orchestrates multi-agent routing and delegates execution to AgentRunner.
 */
public class LangGraphAgentRunner implements IAgentRunner {

    private final ILlmProvider provider;
    private final ToolRegistry toolRegistry;
    private final String baseSystemPrompt;

    private final List<IAgentEventListener> listeners = new ArrayList<>();
    private final AtomicReference<com.codepilot1c.core.agent.AgentRunner> activeRunner = new AtomicReference<>();
    private final AtomicReference<AgentResult> lastResult = new AtomicReference<>();
    private final AtomicReference<AgentState> state = new AtomicReference<>(AgentState.IDLE);
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile List<LlmMessage> lastHistory = new ArrayList<>();
    private volatile int lastSteps = 0;

    public LangGraphAgentRunner(ILlmProvider provider, ToolRegistry toolRegistry) {
        this(provider, toolRegistry, ""); //$NON-NLS-1$
    }

    public LangGraphAgentRunner(ILlmProvider provider, ToolRegistry toolRegistry, String baseSystemPrompt) {
        this.provider = Objects.requireNonNull(provider, "provider"); //$NON-NLS-1$
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry"); //$NON-NLS-1$
        this.baseSystemPrompt = baseSystemPrompt != null ? baseSystemPrompt : ""; //$NON-NLS-1$
    }

    @Override
    public CompletableFuture<AgentResult> run(String prompt, AgentConfig config) {
        return run(prompt, List.of(), config);
    }

    @Override
    public CompletableFuture<AgentResult> run(String prompt, List<LlmMessage> history, AgentConfig config) {
        Objects.requireNonNull(config, "config"); //$NON-NLS-1$
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Агент уже выполняется"); //$NON-NLS-1$
        }

        state.set(AgentState.RUNNING);
        lastResult.set(null);

        List<LlmMessage> historyCopy = history != null ? new ArrayList<>(history) : List.of();

        return CompletableFuture.supplyAsync(() -> {
            try {
                LangGraphAgentRunContext context = new LangGraphAgentRunContext(
                        provider,
                        toolRegistry,
                        config,
                        baseSystemPrompt,
                        historyCopy,
                        listeners,
                        activeRunner,
                        lastResult,
                        false
                );

                org.bsc.langgraph4j.StateGraph<org.bsc.langgraph4j.state.AgentState> graph =
                        LangGraphAgentGraphFactory.buildGraph(context);

                CompileConfig compileConfig = CompileConfig.builder()
                        .checkpointSaver(new MemorySaver())
                        .build();

                CompiledGraph<org.bsc.langgraph4j.state.AgentState> compiledGraph = graph.compile(compileConfig);
                compiledGraph.setMaxIterations(config.getMaxSteps());

                Map<String, Object> input = new HashMap<>();
                input.put("prompt", prompt); //$NON-NLS-1$

                Optional<org.bsc.langgraph4j.state.AgentState> finalState = compiledGraph.invoke(input);
                AgentResult result = lastResult.get();

                if (result == null && finalState.isPresent()) {
                    result = toResultFromState(finalState.get());
                }
                if (result == null) {
                    result = AgentResult.error(new IllegalStateException("Нет результата выполнения"),
                            List.of(), 0, 0);
                }

                lastHistory = result.getConversationHistory();
                lastSteps = result.getStepsExecuted();
                state.set(result.getFinalState());
                return result;
            } catch (CancellationException cancel) {
                state.set(AgentState.CANCELLED);
                return AgentResult.cancelled(lastHistory, lastSteps, 0);
            } catch (Exception e) {
                state.set(AgentState.ERROR);
                return AgentResult.error(e, lastHistory, lastSteps, 0);
            } finally {
                running.set(false);
                activeRunner.set(null);
            }
        });
    }

    private AgentResult toResultFromState(org.bsc.langgraph4j.state.AgentState state) {
        String status = state.value("status", AgentState.ERROR.name()); //$NON-NLS-1$
        String response = state.value("finalResponse", ""); //$NON-NLS-1$ //$NON-NLS-2$
        String error = state.value("error", ""); //$NON-NLS-1$ //$NON-NLS-2$
        int steps = state.value("steps", 0); //$NON-NLS-1$
        int toolCalls = state.value("toolCalls", 0); //$NON-NLS-1$
        long duration = state.value("durationMs", 0L); //$NON-NLS-1$

        AgentState finalState;
        try {
            finalState = AgentState.valueOf(status);
        } catch (Exception e) {
            finalState = AgentState.ERROR;
        }

        return AgentResult.builder()
                .finalState(finalState)
                .finalResponse(response)
                .errorMessage(error)
                .stepsExecuted(steps)
                .toolCallsExecuted(toolCalls)
                .executionTimeMs(duration)
                .conversationHistory(lastHistory)
                .build();
    }

    @Override
    public void cancel() {
        com.codepilot1c.core.agent.AgentRunner runner = activeRunner.get();
        if (runner != null) {
            runner.cancel();
        }
        state.set(AgentState.CANCELLED);
    }

    @Override
    public AgentState getState() {
        return state.get();
    }

    @Override
    public int getCurrentStep() {
        com.codepilot1c.core.agent.AgentRunner runner = activeRunner.get();
        if (runner != null) {
            return runner.getCurrentStep();
        }
        return lastSteps;
    }

    @Override
    public List<LlmMessage> getConversationHistory() {
        com.codepilot1c.core.agent.AgentRunner runner = activeRunner.get();
        if (runner != null) {
            return runner.getConversationHistory();
        }
        return new ArrayList<>(lastHistory);
    }

    @Override
    public void addListener(IAgentEventListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeListener(IAgentEventListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void dispose() {
        cancel();
        listeners.clear();
    }
}
