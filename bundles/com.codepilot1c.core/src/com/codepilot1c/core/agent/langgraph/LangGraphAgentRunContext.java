/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.langgraph;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import com.codepilot1c.core.agent.AgentConfig;
import com.codepilot1c.core.agent.AgentResult;
import com.codepilot1c.core.agent.AgentRunner;
import com.codepilot1c.core.agent.AgentState;
import com.codepilot1c.core.agent.events.IAgentEventListener;
import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.provider.ILlmProvider;
import com.codepilot1c.core.tools.ToolRegistry;

final class LangGraphAgentRunContext {

    private static final long EXTRA_WAIT_MS = 10_000L;
    private static final int MAX_RESPONSE_PREVIEW = 4000;

    private final ILlmProvider provider;
    private final ToolRegistry toolRegistry;
    private final AgentConfig baseConfig;
    private final String baseSystemPrompt;
    private final List<LlmMessage> history;
    private final List<IAgentEventListener> listeners;
    private final AtomicReference<AgentRunner> activeRunner;
    private final AtomicReference<AgentResult> lastResult;
    private final boolean studioMode;

    LangGraphAgentRunContext(
            ILlmProvider provider,
            ToolRegistry toolRegistry,
            AgentConfig baseConfig,
            String baseSystemPrompt,
            List<LlmMessage> history,
            List<IAgentEventListener> listeners,
            AtomicReference<AgentRunner> activeRunner,
            AtomicReference<AgentResult> lastResult,
            boolean studioMode) {
        this.provider = provider;
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry"); //$NON-NLS-1$
        this.baseConfig = Objects.requireNonNull(baseConfig, "baseConfig"); //$NON-NLS-1$
        this.baseSystemPrompt = baseSystemPrompt != null ? baseSystemPrompt : ""; //$NON-NLS-1$
        this.history = history;
        this.listeners = listeners;
        this.activeRunner = activeRunner;
        this.lastResult = lastResult;
        this.studioMode = studioMode;
    }

    LangGraphAgentDomain resolveDomain(String prompt) {
        return LangGraphAgentDomain.fromPrompt(prompt);
    }

    Map<String, Object> runDomain(LangGraphAgentDomain domain, String prompt) {
        Map<String, Object> output = new HashMap<>();
        output.put("agentId", domain.getId()); //$NON-NLS-1$
        output.put("agentName", domain.getDisplayName()); //$NON-NLS-1$

        if (studioMode) {
            output.put("status", "STUDIO_PREVIEW"); //$NON-NLS-1$ //$NON-NLS-2$
            output.put("finalResponse", "Graph preview only. Execution is disabled in Studio mode."); //$NON-NLS-1$ //$NON-NLS-2$
            return output;
        }

        if (provider == null || !provider.isConfigured()) {
            output.put("status", AgentState.ERROR.name()); //$NON-NLS-1$
            output.put("error", "LLM провайдер не настроен"); //$NON-NLS-1$ //$NON-NLS-2$
            lastResult.set(AgentResult.error(new IllegalStateException("LLM провайдер не настроен"),
                    List.of(), 0, 0));
            return output;
        }

        AgentConfig domainConfig = LangGraphAgentConfigFactory.buildDomainConfig(baseConfig, domain);
        AgentRunner runner = new AgentRunner(provider, toolRegistry, baseSystemPrompt);
        if (listeners != null) {
            for (IAgentEventListener listener : listeners) {
                if (listener != null) {
                    runner.addListener(listener);
                }
            }
        }

        activeRunner.set(runner);
        long start = System.currentTimeMillis();
        try {
            CompletableFuture<AgentResult> future = history == null || history.isEmpty()
                    ? runner.run(prompt, domainConfig)
                    : runner.run(prompt, history, domainConfig);

            AgentResult result = future.get(domainConfig.getTimeoutMs() + EXTRA_WAIT_MS, TimeUnit.MILLISECONDS);
            lastResult.set(result);

            output.put("status", result.getFinalState().name()); //$NON-NLS-1$
            output.put("steps", result.getStepsExecuted()); //$NON-NLS-1$
            output.put("toolCalls", result.getToolCallsExecuted()); //$NON-NLS-1$
            output.put("durationMs", result.getExecutionTimeMs()); //$NON-NLS-1$
            output.put("finalResponse", truncate(result.getFinalResponse())); //$NON-NLS-1$

            return output;
        } catch (TimeoutException timeout) {
            runner.cancel();
            AgentResult result = AgentResult.builder()
                    .finalState(AgentState.ERROR)
                    .error(timeout)
                    .errorMessage("Превышен таймаут выполнения") //$NON-NLS-1$
                    .conversationHistory(runner.getConversationHistory())
                    .stepsExecuted(runner.getCurrentStep())
                    .executionTimeMs(System.currentTimeMillis() - start)
                    .build();
            lastResult.set(result);
            output.put("status", AgentState.ERROR.name()); //$NON-NLS-1$
            output.put("error", "Превышен таймаут выполнения"); //$NON-NLS-1$ //$NON-NLS-2$
            return output;
        } catch (Exception e) {
            runner.cancel();
            AgentResult result = AgentResult.error(e, runner.getConversationHistory(), runner.getCurrentStep(),
                    System.currentTimeMillis() - start);
            lastResult.set(result);
            output.put("status", AgentState.ERROR.name()); //$NON-NLS-1$
            output.put("error", e.getMessage()); //$NON-NLS-1$
            return output;
        } finally {
            runner.dispose();
            activeRunner.compareAndSet(runner, null);
        }
    }

    private String truncate(String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        if (value.length() <= MAX_RESPONSE_PREVIEW) {
            return value;
        }
        return value.substring(0, MAX_RESPONSE_PREVIEW) + "..."; //$NON-NLS-1$
    }
}
