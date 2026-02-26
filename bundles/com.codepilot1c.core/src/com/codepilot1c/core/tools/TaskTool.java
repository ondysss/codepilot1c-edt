/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;

import com.codepilot1c.core.agent.AgentConfig;
import com.codepilot1c.core.agent.AgentResult;
import com.codepilot1c.core.agent.langgraph.LangGraphAgentRunner;
import com.codepilot1c.core.agent.AgentState;
import com.codepilot1c.core.agent.profiles.AgentProfile;
import com.codepilot1c.core.agent.profiles.AgentProfileRegistry;
import com.codepilot1c.core.agent.prompts.AgentPromptTemplates;
import com.codepilot1c.core.provider.ILlmProvider;
import com.codepilot1c.core.provider.LlmProviderRegistry;

/**
 * Инструмент для запуска подагентов.
 *
 * <p>Позволяет основному агенту делегировать сложные задачи
 * специализированным подагентам с разными профилями.</p>
 *
 * <p>Особенности:</p>
 * <ul>
 *   <li>Ограничение глубины вложенности (макс. 3)</li>
 *   <li>Выбор профиля подагента (explore, plan, build)</li>
 *   <li>Автоматическое суммирование результата</li>
 *   <li>Таймаут выполнения</li>
 * </ul>
 *
 * <p>Пример использования агентом:</p>
 * <pre>
 * // Делегировать исследование кодовой базы
 * task(prompt="Найди все обработчики событий формы", profile="explore")
 *
 * // Делегировать создание плана
 * task(prompt="Создай план рефакторинга модуля", profile="plan")
 * </pre>
 */
public class TaskTool implements ITool {

    private static final String PLUGIN_ID = "com.codepilot1c.core";
    private static final ILog LOG = Platform.getLog(TaskTool.class);

    private static final String SCHEMA = """
            {
                "type": "object",
                "properties": {
                    "prompt": {
                        "type": "string",
                        "description": "Task description for the subagent"
                    },
                    "profile": {
                        "type": "string",
                        "enum": ["explore", "plan", "build"],
                        "description": "Agent profile: explore (fast search), plan (analysis), build (full access)"
                    },
                    "description": {
                        "type": "string",
                        "description": "Short description of what the subagent will do (3-5 words)"
                    }
                },
                "required": ["prompt"]
            }
            """;

    private static final int MAX_DEPTH = 3;
    private static final int DEFAULT_TIMEOUT_SECONDS = 180; // 3 minutes

    // Thread-local depth counter to prevent infinite recursion
    private static final ThreadLocal<AtomicInteger> currentDepth =
            ThreadLocal.withInitial(() -> new AtomicInteger(0));

    private final ToolRegistry toolRegistry;

    /**
     * Создает TaskTool с указанным реестром инструментов.
     *
     * @param toolRegistry реестр инструментов для подагентов
     */
    public TaskTool(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Override
    public String getName() {
        return "task";
    }

    @Override
    public String getDescription() {
        return "Запускает подагента для выполнения сложной задачи. " +
               "Используйте для делегирования исследования кода (explore), " +
               "создания планов (plan) или выполнения задач (build).";
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            String prompt = (String) parameters.get("prompt");
            if (prompt == null || prompt.isEmpty()) {
                return ToolResult.failure("Параметр prompt обязателен");
            }

            String profileId = (String) parameters.get("profile");
            if (profileId == null || profileId.isEmpty()) {
                profileId = "explore"; // Default to fast explore
            }

            String description = (String) parameters.get("description");
            if (description == null || description.isEmpty()) {
                description = "Подзадача";
            }

            // Check depth limit
            AtomicInteger depth = currentDepth.get();
            if (depth.get() >= MAX_DEPTH) {
                return ToolResult.failure(
                        "Достигнут лимит вложенности подагентов (" + MAX_DEPTH + ")");
            }

            try {
                depth.incrementAndGet();
                return executeSubagent(prompt, profileId, description);
            } finally {
                depth.decrementAndGet();
            }
        });
    }

    /**
     * Выполняет подагента.
     */
    private ToolResult executeSubagent(String prompt, String profileId, String description) {
        logInfo("Запуск подагента [" + profileId + "]: " + description);

        // Get provider
        ILlmProvider provider = LlmProviderRegistry.getInstance().getActiveProvider();
        if (provider == null || !provider.isConfigured()) {
            return ToolResult.failure("LLM провайдер не настроен");
        }

        // Get profile
        AgentProfile profile = AgentProfileRegistry.getInstance()
                .getProfile(profileId)
                .orElse(AgentProfileRegistry.getInstance().getExploreProfile());

        // Create config from profile with reduced limits for subagent
        AgentConfig.Builder configBuilder = AgentConfig.builder()
                .maxSteps(Math.min(profile.getMaxSteps(), 15)) // Limit subagent steps
                .timeoutMs(DEFAULT_TIMEOUT_SECONDS * 1000L)
                .systemPromptAddition(buildSubagentSystemPrompt(profile, description))
                .profileName(profileId);

        // Enable tools based on profile
        for (String tool : profile.getAllowedTools()) {
            // Don't allow nested task calls beyond depth 2
            if (!"task".equals(tool) || currentDepth.get().get() < MAX_DEPTH - 1) {
                configBuilder.enableTool(tool);
            }
        }

        AgentConfig config = configBuilder.build();

        // Create and run subagent
        LangGraphAgentRunner subagent = new LangGraphAgentRunner(provider, toolRegistry,
                profile.getSystemPromptAddition());

        try {
            CompletableFuture<AgentResult> future = subagent.run(prompt, config);

            // Wait with timeout
            AgentResult result = future.get(DEFAULT_TIMEOUT_SECONDS + 10, TimeUnit.SECONDS);

            return formatResult(result, description, profileId);

        } catch (Exception e) {
            logError("Ошибка выполнения подагента", e);
            subagent.cancel();
            return ToolResult.failure("Ошибка подагента: " + e.getMessage());
        } finally {
            subagent.dispose();
        }
    }

    /**
     * Строит системный промпт для подагента.
     */
    private String buildSubagentSystemPrompt(AgentProfile profile, String description) {
        return AgentPromptTemplates.buildSubagentPrompt(
                profile.getName(),
                description,
                profile.isReadOnly());
    }

    /**
     * Форматирует результат подагента.
     */
    private ToolResult formatResult(AgentResult result, String description, String profileId) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Результат подагента\n\n");
        sb.append("**Задача:** ").append(description).append("\n");
        sb.append("**Профиль:** ").append(profileId).append("\n");
        sb.append("**Статус:** ").append(formatStatus(result.getFinalState())).append("\n");
        sb.append("**Шагов:** ").append(result.getStepsExecuted()).append("\n");
        sb.append("**Вызовов инструментов:** ").append(result.getToolCallsExecuted()).append("\n");
        sb.append("**Время:** ").append(result.getExecutionTimeMs()).append(" мс\n\n");

        if (result.isSuccess() && result.getFinalResponse() != null) {
            sb.append("### Ответ\n\n");
            sb.append(truncateResponse(result.getFinalResponse()));
        } else if (result.isError()) {
            sb.append("### Ошибка\n\n");
            sb.append(result.getErrorMessage());
        } else if (result.isCancelled()) {
            sb.append("*Задача была отменена*");
        }

        logInfo("Подагент завершен: " + result.getFinalState() +
                ", шагов: " + result.getStepsExecuted());

        if (result.isSuccess()) {
            return ToolResult.success(sb.toString(), ToolResult.ToolResultType.TEXT);
        } else {
            return ToolResult.failure(sb.toString());
        }
    }

    /**
     * Форматирует статус.
     */
    private String formatStatus(AgentState state) {
        switch (state) {
            case COMPLETED:
                return "✓ Завершено";
            case CANCELLED:
                return "⊘ Отменено";
            case ERROR:
                return "✗ Ошибка";
            default:
                return state.toString();
        }
    }

    /**
     * Обрезает длинный ответ.
     */
    private String truncateResponse(String response) {
        final int maxLength = 10000;
        if (response.length() > maxLength) {
            return response.substring(0, maxLength) +
                   "\n\n*... (ответ обрезан, " + response.length() + " символов)*";
        }
        return response;
    }

    private void logInfo(String message) {
        LOG.log(new Status(IStatus.INFO, PLUGIN_ID, message));
    }

    private void logError(String message, Throwable error) {
        LOG.log(new Status(IStatus.ERROR, PLUGIN_ID, message, error));
    }
}
