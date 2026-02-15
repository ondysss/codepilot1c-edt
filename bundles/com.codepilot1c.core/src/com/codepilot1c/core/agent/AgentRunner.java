/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;

import com.codepilot1c.core.agent.events.AgentCompletedEvent;
import com.codepilot1c.core.agent.events.AgentEvent;
import com.codepilot1c.core.agent.events.AgentStartedEvent;
import com.codepilot1c.core.agent.events.AgentStepEvent;
import com.codepilot1c.core.agent.events.ConfirmationRequiredEvent;
import com.codepilot1c.core.agent.events.IAgentEventListener;
import com.codepilot1c.core.agent.events.StreamChunkEvent;
import com.codepilot1c.core.agent.events.ToolCallEvent;
import com.codepilot1c.core.agent.events.ToolResultEvent;
import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.model.LlmRequest;
import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.model.LlmStreamChunk;
import com.codepilot1c.core.model.ToolCall;
import com.codepilot1c.core.model.ToolDefinition;
import com.codepilot1c.core.provider.ILlmProvider;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolLogger;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.ToolResult;

/**
 * Реализация agentic loop для автоматического выполнения задач.
 *
 * <p>Цикл выполнения:</p>
 * <pre>
 * 1. Отправить prompt в LLM
 * 2. Получить ответ
 * 3. Если finish_reason == "tool_use":
 *    a. Выполнить tool calls
 *    b. Добавить tool results в историю
 *    c. Вернуться к шагу 1
 * 4. Если finish_reason == "stop":
 *    a. Вернуть финальный ответ
 * </pre>
 *
 * <p>Поддерживает:</p>
 * <ul>
 *   <li>Streaming ответов от LLM</li>
 *   <li>Отмену выполнения (cancellation)</li>
 *   <li>Ограничение количества шагов (max steps)</li>
 *   <li>Таймаут выполнения</li>
 *   <li>Подтверждение опасных операций</li>
 *   <li>События для UI</li>
 * </ul>
 */
public class AgentRunner implements IAgentRunner {

    private static final String PLUGIN_ID = "com.codepilot1c.core";
    private static final String PROP_PROMPT_TELEMETRY_ENABLED =
            "codepilot1c.prompt.telemetry.enabled"; //$NON-NLS-1$
    private static final ILog LOG = Platform.getLog(AgentRunner.class);

    private final ILlmProvider provider;
    private final ToolRegistry toolRegistry;
    private final String systemPrompt;

    private final List<IAgentEventListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicReference<AgentState> state = new AtomicReference<>(AgentState.IDLE);
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final AtomicInteger currentStep = new AtomicInteger(0);
    private final AtomicLong startTimeMs = new AtomicLong(0);
    private final AtomicInteger toolCallsCount = new AtomicInteger(0);

    // For proper cancellation handling
    private final AtomicReference<CompletableFuture<LlmResponse>> currentStreamingFuture =
            new AtomicReference<>();
    private final AtomicReference<ConfirmationRequiredEvent> pendingConfirmation =
            new AtomicReference<>();

    // Thread-safe conversation history with object lock
    private final Object historyLock = new Object();
    private List<LlmMessage> conversationHistory = new ArrayList<>();

    /**
     * Создает AgentRunner.
     *
     * @param provider LLM провайдер
     * @param toolRegistry реестр инструментов
     * @param systemPrompt системный промпт
     */
    public AgentRunner(ILlmProvider provider, ToolRegistry toolRegistry, String systemPrompt) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.systemPrompt = systemPrompt != null ? systemPrompt : "";
    }

    /**
     * Создает AgentRunner с дефолтным системным промптом.
     *
     * @param provider LLM провайдер
     * @param toolRegistry реестр инструментов
     */
    public AgentRunner(ILlmProvider provider, ToolRegistry toolRegistry) {
        this(provider, toolRegistry, null);
    }

    @Override
    public CompletableFuture<AgentResult> run(String prompt, AgentConfig config) {
        return run(prompt, new ArrayList<>(), config);
    }

    @Override
    public CompletableFuture<AgentResult> run(String prompt, List<LlmMessage> history, AgentConfig config) {
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(config, "config");

        if (!state.compareAndSet(AgentState.IDLE, AgentState.RUNNING)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Агент уже выполняется"));
        }

        // Reset state for reuse
        resetState();
        AtomicReference<String> appliedSystemPrompt = new AtomicReference<>(""); //$NON-NLS-1$

        // Initialize conversation history
        synchronized (historyLock) {
            conversationHistory = new ArrayList<>();
            if (history != null && !history.isEmpty()) {
                conversationHistory.addAll(history);
            }

            // Add system prompt if not already present
            if (conversationHistory.isEmpty() || !isSystemMessage(conversationHistory.get(0))) {
                String fullSystemPrompt = buildSystemPrompt(config);
                appliedSystemPrompt.set(fullSystemPrompt);
                if (!fullSystemPrompt.isEmpty()) {
                    conversationHistory.add(0, LlmMessage.system(fullSystemPrompt));
                }
            } else {
                appliedSystemPrompt.set(conversationHistory.get(0).getContent());
            }

            // Add user message
            conversationHistory.add(LlmMessage.user(prompt));
        }

        // Emit started event
        emit(new AgentStartedEvent(prompt, config));

        // Start the loop with timeout
        CompletableFuture<AgentResult> result = executeLoop(config);

        // Apply timeout if configured
        if (config.getTimeoutMs() > 0) {
            result = result.orTimeout(config.getTimeoutMs(), TimeUnit.MILLISECONDS)
                    .exceptionally(error -> {
                        if (error instanceof TimeoutException ||
                                (error.getCause() instanceof TimeoutException)) {
                            return createTimeoutResult(config);
                        }
                        throw (error instanceof RuntimeException)
                                ? (RuntimeException) error
                                : new RuntimeException(error);
                    });
        }

        // Always reset to IDLE when done (for reuse)
        return result.whenComplete((res, err) -> {
            // Clear pending futures
            currentStreamingFuture.set(null);
            pendingConfirmation.set(null);

            // Emit completion event if we have a result
            if (res != null) {
                emit(new AgentCompletedEvent(res));
            }

            logPromptTelemetry(config, prompt, appliedSystemPrompt.get(), res, err);

            // Reset to IDLE for reuse
            state.set(AgentState.IDLE);
        });
    }

    /**
     * Сбрасывает состояние для повторного использования.
     */
    private void resetState() {
        cancelRequested.set(false);
        currentStep.set(0);
        toolCallsCount.set(0);
        startTimeMs.set(System.currentTimeMillis());
        currentStreamingFuture.set(null);
        pendingConfirmation.set(null);
    }

    /**
     * Создает результат при таймауте.
     */
    private AgentResult createTimeoutResult(AgentConfig config) {
        long executionTime = System.currentTimeMillis() - startTimeMs.get();
        List<LlmMessage> historyCopy;
        synchronized (historyLock) {
            historyCopy = new ArrayList<>(conversationHistory);
        }
        return AgentResult.builder()
                .finalState(AgentState.ERROR)
                .errorMessage("Превышен таймаут выполнения: " + config.getTimeoutMs() + " мс")
                .conversationHistory(historyCopy)
                .stepsExecuted(currentStep.get())
                .toolCallsExecuted(toolCallsCount.get())
                .executionTimeMs(executionTime)
                .build();
    }

    /**
     * Выполняет основной цикл агента.
     */
    private CompletableFuture<AgentResult> executeLoop(AgentConfig config) {
        if (cancelRequested.get()) {
            return completeCancelled();
        }

        int step = currentStep.incrementAndGet();
        if (step > config.getMaxSteps()) {
            return completeMaxStepsReached(config);
        }

        emit(new AgentStepEvent(step, config.getMaxSteps(), "Отправка запроса к LLM"));
        state.set(AgentState.RUNNING);

        // Build request with tools
        LlmRequest request = buildRequest(config);

        // Execute based on streaming preference
        CompletableFuture<LlmResponse> responseFuture;
        try {
            if (config.isStreamingEnabled() && provider.supportsStreaming()) {
                responseFuture = executeStreaming(request, step);
            } else {
                responseFuture = provider.complete(request);
            }
        } catch (Exception e) {
            // Handle synchronous provider exceptions
            logError("Ошибка при вызове провайдера", e);
            return CompletableFuture.completedFuture(createErrorResult(e));
        }

        return responseFuture
                .thenCompose(response -> handleResponse(response, config))
                .exceptionally(error -> {
                    Throwable cause = unwrapException(error);
                    if (cancelRequested.get() || cause instanceof CancellationException) {
                        return createCancelledResult();
                    }
                    logError("Ошибка в цикле агента", cause);
                    return createErrorResult(cause);
                });
    }

    /**
     * Выполняет streaming запрос.
     */
    private CompletableFuture<LlmResponse> executeStreaming(LlmRequest request, int step) {
        CompletableFuture<LlmResponse> future = new CompletableFuture<>();
        currentStreamingFuture.set(future);

        StringBuilder contentBuilder = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();

        Consumer<LlmStreamChunk> chunkHandler = chunk -> {
            if (cancelRequested.get()) {
                // Complete with cancellation if cancelled
                if (!future.isDone()) {
                    future.completeExceptionally(new CancellationException("Операция отменена"));
                }
                return;
            }

            if (chunk.getContent() != null && !chunk.getContent().isEmpty()) {
                contentBuilder.append(chunk.getContent());
                emit(StreamChunkEvent.partial(step, chunk.getContent()));
            }

            if (chunk.getToolCalls() != null) {
                toolCalls.addAll(chunk.getToolCalls());
            }

            if (chunk.isComplete()) {
                emit(StreamChunkEvent.complete(step, chunk.getFinishReason()));

                LlmResponse response = LlmResponse.builder()
                        .content(contentBuilder.toString())
                        .toolCalls(toolCalls)
                        .finishReason(chunk.getFinishReason())
                        .build();
                future.complete(response);
            }

            if (chunk.getErrorMessage() != null) {
                future.completeExceptionally(
                        new RuntimeException(chunk.getErrorMessage()));
            }
        };

        try {
            provider.streamComplete(request, chunkHandler);
        } catch (Exception e) {
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * Обрабатывает ответ от LLM.
     */
    private CompletableFuture<AgentResult> handleResponse(LlmResponse response, AgentConfig config) {
        if (cancelRequested.get()) {
            return completeCancelled();
        }

        // Add assistant message to history
        synchronized (historyLock) {
            if (response.hasToolCalls()) {
                conversationHistory.add(LlmMessage.assistantWithToolCalls(
                        response.getContent(), response.getToolCalls()));
            } else {
                conversationHistory.add(LlmMessage.assistant(response.getContent()));
            }
        }

        // Check if we need to execute tools
        if (response.isToolUse() && response.hasToolCalls()) {
            return executeToolCalls(response.getToolCalls(), config);
        }

        // Final response - done!
        return completeSuccess(response.getContent());
    }

    /**
     * Выполняет вызовы инструментов.
     */
    private CompletableFuture<AgentResult> executeToolCalls(
            List<ToolCall> toolCalls, AgentConfig config) {

        state.set(AgentState.WAITING_TOOL);

        // Set agent context for tool logging
        String sessionId = String.valueOf(System.identityHashCode(this));
        ToolLogger.getInstance().setAgentContext(sessionId, currentStep.get());

        // Process tool calls sequentially
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

        for (ToolCall call : toolCalls) {
            chain = chain.thenCompose(v -> {
                if (cancelRequested.get()) {
                    return CompletableFuture.completedFuture(null);
                }
                return executeSingleToolCall(call, config);
            });
        }

        return chain.thenCompose(v -> {
            if (cancelRequested.get()) {
                return completeCancelled();
            }
            // Continue the loop
            return executeLoop(config);
        });
    }

    /**
     * Выполняет один вызов инструмента.
     */
    private CompletableFuture<Void> executeSingleToolCall(ToolCall call, AgentConfig config) {
        String toolName = call.getName();
        ITool tool = toolRegistry.getTool(toolName);
        int step = currentStep.get();

        if (tool == null) {
            // Unknown tool - add error result and emit event
            ToolResult errorResult = ToolResult.failure("Неизвестный инструмент: " + toolName);
            addToolResult(call.getId(), errorResult);
            emit(new ToolResultEvent(step, toolName, call.getId(), errorResult, 0));
            return CompletableFuture.completedFuture(null);
        }

        // Check if tool is allowed
        if (!config.isToolAllowed(toolName)) {
            ToolResult disabledResult = ToolResult.failure("Инструмент отключен: " + toolName);
            addToolResult(call.getId(), disabledResult);
            emit(new ToolResultEvent(step, toolName, call.getId(), disabledResult, 0));
            return CompletableFuture.completedFuture(null);
        }

        // Parse arguments
        Map<String, Object> args = parseArguments(call.getArguments());

        // Emit tool call event
        emit(new ToolCallEvent(step, call, args, tool.requiresConfirmation()));

        // Check if confirmation is required
        if (tool.requiresConfirmation()) {
            return requestConfirmation(call, tool, args, config);
        }

        // Execute directly
        return executeToolAndAddResult(call, args);
    }

    /**
     * Запрашивает подтверждение у пользователя.
     */
    private CompletableFuture<Void> requestConfirmation(
            ToolCall call, ITool tool, Map<String, Object> args, AgentConfig config) {

        state.set(AgentState.WAITING_CONFIRMATION);
        int step = currentStep.get();

        ConfirmationRequiredEvent event = new ConfirmationRequiredEvent(
                step,
                call,
                tool.getDescription(),
                args,
                tool.isDestructive()
        );
        pendingConfirmation.set(event);
        emit(event);

        return event.getResultFuture().thenCompose(result -> {
            pendingConfirmation.set(null);
            state.set(AgentState.RUNNING);

            ToolResult toolResult;
            switch (result) {
                case CONFIRMED:
                    return executeToolAndAddResult(call, args);
                case SKIPPED:
                    toolResult = ToolResult.success("Операция пропущена пользователем",
                            ToolResult.ToolResultType.CONFIRMATION);
                    addToolResult(call.getId(), toolResult);
                    emit(new ToolResultEvent(step, call.getName(), call.getId(), toolResult, 0));
                    return CompletableFuture.completedFuture(null);
                case DENIED:
                default:
                    toolResult = ToolResult.failure("Операция отклонена пользователем");
                    addToolResult(call.getId(), toolResult);
                    emit(new ToolResultEvent(step, call.getName(), call.getId(), toolResult, 0));
                    return CompletableFuture.completedFuture(null);
            }
        }).exceptionally(error -> {
            // Handle cancellation during confirmation
            pendingConfirmation.set(null);
            if (error instanceof CancellationException ||
                    (error.getCause() instanceof CancellationException)) {
                ToolResult cancelResult = ToolResult.failure("Операция отменена");
                addToolResult(call.getId(), cancelResult);
                emit(new ToolResultEvent(step, call.getName(), call.getId(), cancelResult, 0));
            }
            return null;
        });
    }

    /**
     * Выполняет инструмент и добавляет результат в историю.
     */
    private CompletableFuture<Void> executeToolAndAddResult(ToolCall call, Map<String, Object> args) {
        long toolStartTime = System.currentTimeMillis();
        int step = currentStep.get();

        return toolRegistry.execute(call)
                .handle((result, error) -> {
                    long executionTime = System.currentTimeMillis() - toolStartTime;
                    toolCallsCount.incrementAndGet();

                    ToolResult toolResult;
                    if (error != null) {
                        toolResult = ToolResult.failure("Ошибка: " + error.getMessage());
                    } else {
                        toolResult = result;
                    }

                    addToolResult(call.getId(), toolResult);
                    emit(new ToolResultEvent(step, call.getName(), call.getId(),
                            toolResult, executionTime));

                    return null;
                });
    }

    /**
     * Добавляет результат инструмента в историю.
     */
    private void addToolResult(String callId, ToolResult result) {
        String content = result.isSuccess()
                ? result.getContent()
                : "Ошибка: " + result.getErrorMessage();
        synchronized (historyLock) {
            conversationHistory.add(LlmMessage.toolResult(callId, content));
        }
    }

    /**
     * Создает LlmRequest с инструментами.
     */
    private LlmRequest buildRequest(AgentConfig config) {
        List<ToolDefinition> tools = new ArrayList<>();

        for (ITool tool : toolRegistry.getAllTools()) {
            if (config.isToolAllowed(tool.getName())) {
                tools.add(ToolDefinition.builder()
                        .name(tool.getName())
                        .description(tool.getDescription())
                        .parametersSchema(tool.getParameterSchema())
                        .build());
            }
        }

        List<LlmMessage> messagesCopy;
        synchronized (historyLock) {
            messagesCopy = new ArrayList<>(conversationHistory);
        }

        return LlmRequest.builder()
                .messages(messagesCopy)
                .tools(tools)
                .toolChoice(LlmRequest.ToolChoice.AUTO)
                .stream(config.isStreamingEnabled())
                .build();
    }

    /**
     * Строит системный промпт.
     */
    private String buildSystemPrompt(AgentConfig config) {
        StringBuilder sb = new StringBuilder();
        if (!systemPrompt.isEmpty()) {
            sb.append(systemPrompt);
        }
        if (config.getSystemPromptAddition() != null) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(config.getSystemPromptAddition());
        }
        return sb.toString();
    }

    /**
     * Парсит JSON аргументы в Map.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String json) {
        if (json == null || json.isEmpty() || "{}".equals(json)) {
            return new HashMap<>();
        }
        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            java.lang.reflect.Type mapType =
                    new com.google.gson.reflect.TypeToken<Map<String, Object>>(){}.getType();
            Map<String, Object> result = gson.fromJson(json, mapType);
            return result != null ? result : new HashMap<>();
        } catch (Exception e) {
            logWarning("Не удалось распарсить аргументы инструмента: " + json, e);
            return new HashMap<>();
        }
    }

    /**
     * Проверяет, является ли сообщение системным.
     */
    private boolean isSystemMessage(LlmMessage message) {
        return message != null && message.getRole() == LlmMessage.Role.SYSTEM;
    }

    /**
     * Разворачивает вложенные исключения.
     */
    private Throwable unwrapException(Throwable error) {
        if (error instanceof java.util.concurrent.CompletionException && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }

    // --- Completion methods ---

    private CompletableFuture<AgentResult> completeSuccess(String response) {
        return CompletableFuture.completedFuture(createSuccessResult(response));
    }

    private AgentResult createSuccessResult(String response) {
        long executionTime = System.currentTimeMillis() - startTimeMs.get();
        List<LlmMessage> historyCopy;
        synchronized (historyLock) {
            historyCopy = new ArrayList<>(conversationHistory);
        }
        AgentResult result = AgentResult.success(
                response,
                historyCopy,
                currentStep.get(),
                toolCallsCount.get(),
                executionTime
        );
        state.set(AgentState.COMPLETED);
        return result;
    }

    private CompletableFuture<AgentResult> completeCancelled() {
        return CompletableFuture.completedFuture(createCancelledResult());
    }

    private AgentResult createCancelledResult() {
        long executionTime = System.currentTimeMillis() - startTimeMs.get();
        List<LlmMessage> historyCopy;
        synchronized (historyLock) {
            historyCopy = new ArrayList<>(conversationHistory);
        }
        AgentResult result = AgentResult.cancelled(
                historyCopy,
                currentStep.get(),
                executionTime
        );
        state.set(AgentState.CANCELLED);
        return result;
    }

    private AgentResult createErrorResult(Throwable error) {
        long executionTime = System.currentTimeMillis() - startTimeMs.get();
        List<LlmMessage> historyCopy;
        synchronized (historyLock) {
            historyCopy = new ArrayList<>(conversationHistory);
        }
        return AgentResult.error(error, historyCopy, currentStep.get(), executionTime);
    }

    private CompletableFuture<AgentResult> completeMaxStepsReached(AgentConfig config) {
        long executionTime = System.currentTimeMillis() - startTimeMs.get();
        List<LlmMessage> historyCopy;
        synchronized (historyLock) {
            historyCopy = new ArrayList<>(conversationHistory);
        }
        AgentResult result = AgentResult.builder()
                .finalState(AgentState.COMPLETED)
                .errorMessage("Достигнут лимит шагов: " + config.getMaxSteps())
                .conversationHistory(historyCopy)
                .stepsExecuted(currentStep.get())
                .toolCallsExecuted(toolCallsCount.get())
                .executionTimeMs(executionTime)
                .build();
        state.set(AgentState.COMPLETED);
        return CompletableFuture.completedFuture(result);
    }

    // --- Logging ---

    private void logError(String message, Throwable error) {
        LOG.log(new Status(IStatus.ERROR, PLUGIN_ID, message, error));
    }

    private void logWarning(String message, Throwable error) {
        LOG.log(new Status(IStatus.WARNING, PLUGIN_ID, message, error));
    }

    private void logPromptTelemetry(
            AgentConfig config,
            String userPrompt,
            String appliedSystemPrompt,
            AgentResult result,
            Throwable error) {
        if (!isPromptTelemetryEnabled()) {
            return;
        }
        String profile = config.getProfileName() == null || config.getProfileName().isBlank()
                ? "default" //$NON-NLS-1$
                : config.getProfileName();
        int userChars = userPrompt == null ? 0 : userPrompt.length();
        int systemChars = appliedSystemPrompt == null ? 0 : appliedSystemPrompt.length();

        String stateLabel;
        int steps;
        int toolCalls;
        long execMs;
        if (result != null) {
            stateLabel = result.getFinalState().name();
            steps = result.getStepsExecuted();
            toolCalls = result.getToolCallsExecuted();
            execMs = result.getExecutionTimeMs();
        } else {
            stateLabel = AgentState.ERROR.name();
            steps = currentStep.get();
            toolCalls = toolCallsCount.get();
            execMs = Math.max(0L, System.currentTimeMillis() - startTimeMs.get());
        }

        String errorType = "-"; //$NON-NLS-1$
        if (error != null) {
            Throwable root = unwrapException(error);
            if (root != null) {
                errorType = root.getClass().getSimpleName();
            }
        }

        String message = String.format(
                "prompt_telemetry profile=%s state=%s steps=%d tool_calls=%d time_ms=%d system_chars=%d user_chars=%d error_type=%s", //$NON-NLS-1$
                profile,
                stateLabel,
                Integer.valueOf(steps),
                Integer.valueOf(toolCalls),
                Long.valueOf(execMs),
                Integer.valueOf(systemChars),
                Integer.valueOf(userChars),
                errorType);
        LOG.log(new Status(IStatus.INFO, PLUGIN_ID, message));
    }

    private boolean isPromptTelemetryEnabled() {
        String raw = System.getProperty(PROP_PROMPT_TELEMETRY_ENABLED);
        if (raw == null || raw.isBlank()) {
            return true;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    // --- IAgentRunner implementation ---

    @Override
    public void cancel() {
        cancelRequested.set(true);
        state.set(AgentState.CANCELLED);

        // Cancel provider
        try {
            provider.cancel();
        } catch (Exception e) {
            logWarning("Ошибка при отмене провайдера", e);
        }

        // Complete pending streaming future
        CompletableFuture<LlmResponse> streamFuture = currentStreamingFuture.getAndSet(null);
        if (streamFuture != null && !streamFuture.isDone()) {
            streamFuture.completeExceptionally(new CancellationException("Операция отменена"));
        }

        // Complete pending confirmation
        ConfirmationRequiredEvent confirmation = pendingConfirmation.getAndSet(null);
        if (confirmation != null) {
            confirmation.getResultFuture().completeExceptionally(
                    new CancellationException("Операция отменена"));
        }
    }

    @Override
    public AgentState getState() {
        return state.get();
    }

    @Override
    public int getCurrentStep() {
        return currentStep.get();
    }

    @Override
    public List<LlmMessage> getConversationHistory() {
        synchronized (historyLock) {
            return new ArrayList<>(conversationHistory);
        }
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
        synchronized (historyLock) {
            conversationHistory.clear();
        }
    }

    /**
     * Отправляет событие всем слушателям.
     */
    private void emit(AgentEvent event) {
        for (IAgentEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                logWarning("Ошибка в обработчике события: " + event.getType(), e);
            }
        }
    }
}
