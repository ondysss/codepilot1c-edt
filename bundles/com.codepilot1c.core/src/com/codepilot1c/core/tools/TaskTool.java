/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;

import com.codepilot1c.core.agent.AgentConfig;
import com.codepilot1c.core.agent.AgentResult;
import com.codepilot1c.core.agent.AgentState;
import com.codepilot1c.core.agent.langgraph.LangGraphAgentRunner;
import com.codepilot1c.core.agent.profiles.AgentCapability;
import com.codepilot1c.core.agent.profiles.AgentProfile;
import com.codepilot1c.core.agent.profiles.AgentProfileRegistry;
import com.codepilot1c.core.agent.profiles.DelegationClamp;
import com.codepilot1c.core.agent.profiles.DelegationClamp.Decision;
import com.codepilot1c.core.agent.profiles.DelegationClamp.Outcome;
import com.codepilot1c.core.agent.profiles.ExploreAgentProfile;
import com.codepilot1c.core.agent.profiles.ProfileCapabilities;
import com.codepilot1c.core.agent.profiles.ProfileRouter;
import com.codepilot1c.core.agent.prompts.AgentPromptTemplates;
import com.codepilot1c.core.provider.ILlmProvider;
import com.codepilot1c.core.provider.LlmProviderRegistry;

import com.google.gson.JsonObject;

/**
 * Инструмент для запуска подагентов.
 *
 * <p>Позволяет основному агенту делегировать сложные задачи
 * специализированным подагентам с разными профилями.</p>
 *
 * <p>Особенности:</p>
 * <ul>
 *   <li>Ограничение глубины вложенности (макс. 3)</li>
 *   <li>Выбор профиля подагента (auto, init, explore, plan, code, metadata, qa, dcs, extension, recovery)</li>
 *   <li>Автоматическое суммирование результата</li>
 *   <li>Таймаут выполнения</li>
 * </ul>
 *
 * <p>Пример использования агентом:</p>
 * <pre>
 * // Делегировать исследование кодовой базы
 * task(prompt="Найди все обработчики событий формы", profile="explore")
 *
 * // Делегировать задачу по метаданным
 * task(prompt="Создай справочник Товары и форму списка", profile="metadata")
 * </pre>
 */
@ToolMeta(name = "task", category = "general", tags = {"workspace"})
public class TaskTool extends AbstractTool {

    private static final String PLUGIN_ID = "com.codepilot1c.core";

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
                        "enum": ["auto", "explore", "plan", "init", "build", "code", "metadata", "qa", "dcs", "extension", "recovery", "orchestrator"],
                        "description": "Sub-agent profile or auto routing based on prompt keywords."
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

    private final ToolRegistry toolRegistry;
    private final ProfileRouter profileRouter;
    private final SubagentExecutor subagentExecutor;

    /**
     * Создает TaskTool с указанным реестром инструментов.
     *
     * @param toolRegistry реестр инструментов для подагентов
     */
    public TaskTool(ToolRegistry toolRegistry) {
        this(toolRegistry, new ProfileRouter(), DefaultSubagentExecutor.INSTANCE);
    }

    TaskTool(ToolRegistry toolRegistry, ProfileRouter profileRouter) {
        this(toolRegistry, profileRouter, DefaultSubagentExecutor.INSTANCE);
    }

    TaskTool(ToolRegistry toolRegistry, ProfileRouter profileRouter, SubagentExecutor subagentExecutor) {
        this.toolRegistry = toolRegistry;
        this.profileRouter = profileRouter;
        this.subagentExecutor = subagentExecutor;
    }

    @Override
    public String getDescription() {
        return "Запускает подагента для многошаговой задачи через профиль или auto routing. Для явного выбора домена предпочитай delegate_to_agent."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return doExecute(params, ToolExecutionContext.unscoped());
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(
            ToolParameters params, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            String prompt = params.requireString("prompt"); //$NON-NLS-1$
            String requestedProfileId = profileRouter.normalizeProfileId(params.optString("profile", "auto")); //$NON-NLS-1$ //$NON-NLS-2$
            String resolvedProfileId = profileRouter.resolveRequestedProfile(prompt, requestedProfileId);
            String description = params.optString("description", "Подзадача"); //$NON-NLS-1$ //$NON-NLS-2$
            AgentProfileRegistry profileRegistry = AgentProfileRegistry.getInstance();
            AgentProfile resolvedProfile = profileRegistry.getProfile(resolvedProfileId).orElse(null);

            // ChatView (W6) и MCP host (W7) передают scoped-контекст и сюда не попадают.
            // Ветка остаётся для оставшихся unscoped вызывающих (benchmark runner,
            // прямые ITool.execute, тесты) и сохраняет для них прежний fail-open fallback.
            if (!context.isScoped()) {
                AgentProfile legacyProfile = resolvedProfile != null
                        ? resolvedProfile
                        : profileRegistry.getExploreProfile();
                return executeSubagent(
                        prompt, resolvedProfileId, legacyProfile, description,
                        context.delegationDepth() + 1, null, resolvedProfileId, context);
            }

            AgentCapability required = resolvedProfile != null
                    ? ProfileCapabilities.requiredForChild(resolvedProfile)
                    : AgentCapability.MUTATING;
            if (context.delegationDepth() >= MAX_DEPTH) {
                return delegationDenied(
                        getName(), DelegationClamp.REASON_DEPTH_EXCEEDED,
                        context, requestedProfileId, resolvedProfileId, required);
            }

            boolean autoRequested = profileRouter.isAutoProfileId(requestedProfileId);
            Decision decision = DelegationClamp.decide(
                    context.delegationCeiling(),
                    autoRequested,
                    requestedProfileId,
                    resolvedProfile,
                    candidate -> profileRegistry.getProfile(candidate)
                            .filter(profile -> context.delegationCeiling()
                                    .covers(ProfileCapabilities.requiredForChild(profile)))
                            .map(AgentProfile::getId)
                            .orElse(null));
            if (decision.outcome() == Outcome.DENIED) {
                return delegationDenied(
                        getName(), decision.reasonCode(), context,
                        requestedProfileId, resolvedProfileId,
                        decision.requiredCapability());
            }

            AgentProfile effectiveProfile = profileRegistry
                    .getProfile(decision.effectiveProfileId())
                    .orElse(null);
            if (effectiveProfile == null) {
                return delegationDenied(
                        getName(), DelegationClamp.REASON_TARGET_UNRESOLVED,
                        context, requestedProfileId, decision.effectiveProfileId(),
                        decision.requiredCapability());
            }
            return executeSubagent(
                    prompt, decision.effectiveProfileId(), effectiveProfile, description,
                    context.delegationDepth() + 1, decision, resolvedProfileId, context);
        });
    }

    /**
     * Выполняет подагента.
     */
    private ToolResult executeSubagent(
            String prompt,
            String profileId,
            AgentProfile profile,
            String description,
            int childDepth,
            Decision decision,
            String routedProfileId,
            ToolExecutionContext parentContext) {
        logInfo("Запуск подагента [" + profileId + "]: " + description);

        // Get provider
        ILlmProvider provider = LlmProviderRegistry.getInstance().getActiveProvider();
        if (provider == null || !provider.isConfigured()) {
            return ToolResult.failure("LLM провайдер не настроен");
        }
        // Sub-agents run through the active provider, so task/delegate_to_agent work on any
        // configured provider (not just the CodePilot Account backend).

        // Create config from profile (applies user overrides via ProfileConfigStore)
        AgentConfig baseConfig = AgentProfileRegistry.getInstance().createConfig(profile);
        AgentConfig.Builder configBuilder = AgentConfig.builder().from(baseConfig)
                .systemPromptAddition(buildSubagentSystemPrompt(profile, description))
                .profileName(profileId)
                .delegationDepth(childDepth)
                .executionIdentity(parentContext.projectPath(), parentContext.sessionId());

        if (childDepth >= MAX_DEPTH) {
            configBuilder.disableTool("task"); //$NON-NLS-1$
            configBuilder.disableTool("delegate_to_agent"); //$NON-NLS-1$
        }

        AgentConfig config = configBuilder.build();

        try {
            AgentResult result = subagentExecutor.run(provider, toolRegistry, profile, prompt, config);
            return formatResult(result, description, profileId, decision, routedProfileId);
        } catch (Exception e) {
            Throwable root = unwrap(e);
            logError("Ошибка выполнения подагента", root);
            JsonObject structured = new JsonObject();
            structured.addProperty("profile", profileId); //$NON-NLS-1$
            structured.addProperty("error_type", root.getClass().getSimpleName()); //$NON-NLS-1$
            structured.addProperty("error_message", describe(root)); //$NON-NLS-1$
            addClampMetadata(structured, decision, routedProfileId, profileId);
            return ToolResult.failure("Ошибка подагента: " + describe(root), structured); //$NON-NLS-1$
        }
    }

    private Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private String describe(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return message;
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
    private ToolResult formatResult(
            AgentResult result,
            String description,
            String profileId,
            Decision decision,
            String routedProfileId) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Результат подагента\n\n");
        sb.append("**Задача:** ").append(description).append("\n");
        sb.append("**Профиль:** ").append(profileId).append("\n");
        if (decision != null && decision.outcome() == Outcome.CLAMPED) {
            sb.append("**Профиль ограничен политикой родителя:** ")
                    .append(routedProfileId)
                    .append(" → ").append(profileId).append("\n"); //$NON-NLS-1$
        }
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

        JsonObject structured = new JsonObject();
        structured.addProperty("profile", profileId); //$NON-NLS-1$
        structured.addProperty("status", result.getFinalState().name()); //$NON-NLS-1$
        structured.addProperty("steps", result.getStepsExecuted()); //$NON-NLS-1$
        structured.addProperty("tool_calls", result.getToolCallsExecuted()); //$NON-NLS-1$
        structured.addProperty("execution_time_ms", result.getExecutionTimeMs()); //$NON-NLS-1$
        addClampMetadata(structured, decision, routedProfileId, profileId);

        if (result.isSuccess()) {
            return ToolResult.success(sb.toString(), ToolResult.ToolResultType.TEXT, structured);
        } else {
            return ToolResult.failure(sb.toString(), structured);
        }
    }

    private void addClampMetadata(
            JsonObject structured,
            Decision decision,
            String routedProfileId,
            String effectiveProfileId) {
        if (decision != null && decision.outcome() == Outcome.CLAMPED) {
            structured.addProperty("clamped_from", routedProfileId); //$NON-NLS-1$
            structured.addProperty("clamped_to", effectiveProfileId); //$NON-NLS-1$
            structured.addProperty("reason_code", decision.reasonCode()); //$NON-NLS-1$
        }
    }

    private ToolResult delegationDenied(
            String toolName,
            String reasonCode,
            ToolExecutionContext context,
            String requestedProfileId,
            String resolvedProfileId,
            AgentCapability requiredCapability) {
        JsonObject structured = new JsonObject();
        structured.addProperty("error", "delegation_denied"); //$NON-NLS-1$ //$NON-NLS-2$
        structured.addProperty("tool", toolName); //$NON-NLS-1$
        structured.addProperty("reason", reasonCode); //$NON-NLS-1$
        structured.addProperty("reason_code", reasonCode); //$NON-NLS-1$
        structured.addProperty("parent_profile", context.parentProfileId()); //$NON-NLS-1$
        structured.addProperty("requested_profile", safeId(requestedProfileId)); //$NON-NLS-1$
        structured.addProperty("resolved_profile", safeId(resolvedProfileId)); //$NON-NLS-1$
        structured.addProperty("parent_ceiling", context.delegationCeiling().name()); //$NON-NLS-1$
        structured.addProperty("required_capability", requiredCapability.name()); //$NON-NLS-1$

        String message = String.format(
                "Делегирование запрещено: tool=%s, parent=%s, requested=%s, resolved=%s, reason_code=%s", //$NON-NLS-1$
                toolName,
                context.parentProfileId(),
                safeId(requestedProfileId),
                safeId(resolvedProfileId),
                reasonCode);
        logWarning(String.format(
                "delegation_denied tool=%s parent=%s requested=%s resolved=%s ceiling=%s", //$NON-NLS-1$
                toolName,
                context.parentProfileId(),
                safeId(requestedProfileId),
                safeId(resolvedProfileId),
                context.delegationCeiling().name()));
        return ToolResult.failure(message, structured);
    }

    private String safeId(String profileId) {
        return profileId != null ? profileId : ""; //$NON-NLS-1$
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
        ILog log = safeLog();
        if (log != null) {
            log.log(new Status(IStatus.INFO, PLUGIN_ID, message));
        }
    }

    private void logError(String message, Throwable error) {
        ILog log = safeLog();
        if (log != null) {
            log.log(new Status(IStatus.ERROR, PLUGIN_ID, message, error));
        }
    }

    private void logWarning(String message) {
        ILog log = safeLog();
        if (log != null) {
            log.log(new Status(IStatus.WARNING, PLUGIN_ID, message));
        }
    }

    private ILog safeLog() {
        try {
            return Platform.getLog(TaskTool.class);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @FunctionalInterface
    interface SubagentExecutor {
        AgentResult run(
                ILlmProvider provider,
                ToolRegistry toolRegistry,
                AgentProfile profile,
                String prompt,
                AgentConfig config) throws Exception;
    }

    private static final class DefaultSubagentExecutor implements SubagentExecutor {

        private static final DefaultSubagentExecutor INSTANCE = new DefaultSubagentExecutor();

        @Override
        public AgentResult run(
                ILlmProvider provider,
                ToolRegistry toolRegistry,
                AgentProfile profile,
                String prompt,
                AgentConfig config) throws Exception {
            LangGraphAgentRunner subagent = new LangGraphAgentRunner(provider, toolRegistry,
                    profile.getSystemPromptAddition());
            try {
                CompletableFuture<AgentResult> future = subagent.run(prompt, config);
                long timeoutSeconds = Math.max(1L, TimeUnit.MILLISECONDS.toSeconds(config.getTimeoutMs()) + 10L);
                return future.get(timeoutSeconds, TimeUnit.SECONDS);
            } finally {
                subagent.dispose();
            }
        }
    }
}
