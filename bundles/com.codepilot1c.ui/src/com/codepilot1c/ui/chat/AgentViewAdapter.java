/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.chat;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.swt.widgets.Display;

import com.codepilot1c.core.agent.AgentConfig;
import com.codepilot1c.core.agent.AgentResult;
import com.codepilot1c.core.agent.AgentRunner;
import com.codepilot1c.core.agent.AgentState;
import com.codepilot1c.core.agent.events.AgentCompletedEvent;
import com.codepilot1c.core.agent.events.AgentEvent;
import com.codepilot1c.core.agent.events.AgentStartedEvent;
import com.codepilot1c.core.agent.events.AgentStepEvent;
import com.codepilot1c.core.agent.events.ConfirmationRequiredEvent;
import com.codepilot1c.core.agent.events.IAgentEventListener;
import com.codepilot1c.core.agent.events.StreamChunkEvent;
import com.codepilot1c.core.agent.events.ToolCallEvent;
import com.codepilot1c.core.agent.events.ToolResultEvent;
import com.codepilot1c.core.agent.profiles.AgentProfile;
import com.codepilot1c.core.agent.profiles.AgentProfileRegistry;
import com.codepilot1c.core.provider.ILlmProvider;
import com.codepilot1c.core.provider.LlmProviderRegistry;
import com.codepilot1c.core.settings.VibePreferenceConstants;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.ui.dialogs.ToolConfirmationDialog;

/**
 * Адаптер для интеграции AgentRunner с ChatView.
 *
 * <p>Преобразует события агента в обновления UI через callback'и.
 * Обеспечивает потокобезопасность через Display.asyncExec().</p>
 *
 * <p>Использование:</p>
 * <pre>
 * AgentViewAdapter adapter = new AgentViewAdapter(display);
 * adapter.setMessageAppender(this::appendMessage);
 * adapter.setProgressUpdater(this::updateProgress);
 * adapter.run("Найди все обработчики событий", profile);
 * </pre>
 */
public class AgentViewAdapter implements IAgentEventListener {

    private static final String CORE_PLUGIN_ID = "com.codepilot1c.core"; //$NON-NLS-1$

    private final Display display;
    private AgentRunner currentRunner;
    private StringBuilder streamingContent;
    private String currentStreamingMessageId;

    // Callbacks for UI updates
    private MessageAppender messageAppender;
    private ProgressUpdater progressUpdater;
    private StateChangeListener stateChangeListener;
    private ConfirmationHandler confirmationHandler;

    /**
     * Создаёт адаптер с указанным Display.
     *
     * @param display SWT Display для потокобезопасных обновлений
     */
    public AgentViewAdapter(Display display) {
        this.display = Objects.requireNonNull(display, "display");
    }

    /**
     * Устанавливает callback для добавления сообщений.
     */
    public void setMessageAppender(MessageAppender appender) {
        this.messageAppender = appender;
    }

    /**
     * Устанавливает callback для обновления прогресса.
     */
    public void setProgressUpdater(ProgressUpdater updater) {
        this.progressUpdater = updater;
    }

    /**
     * Устанавливает callback для изменения состояния.
     */
    public void setStateChangeListener(StateChangeListener listener) {
        this.stateChangeListener = listener;
    }

    /**
     * Устанавливает handler для подтверждений.
     */
    public void setConfirmationHandler(ConfirmationHandler handler) {
        this.confirmationHandler = handler;
    }

    /**
     * Запускает агента с заданным промптом и профилем.
     *
     * @param prompt пользовательский запрос
     * @param profileId ID профиля (build, plan, explore)
     * @return Future с результатом
     */
    public CompletableFuture<AgentResult> run(String prompt, String profileId) {
        ILlmProvider provider = LlmProviderRegistry.getInstance().getActiveProvider();
        if (provider == null || !provider.isConfigured()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("LLM провайдер не настроен"));
        }

        // Get profile
        AgentProfile profile = AgentProfileRegistry.getInstance()
                .getProfile(profileId)
                .orElse(AgentProfileRegistry.getInstance().getDefaultProfile());

        // Create config from profile
        AgentConfig config = AgentProfileRegistry.getInstance().createConfig(profile);

        // Create runner with system prompt
        currentRunner = new AgentRunner(provider, ToolRegistry.getInstance(),
                profile.getSystemPromptAddition());
        currentRunner.addListener(this);

        // Initialize streaming state
        streamingContent = new StringBuilder();
        currentStreamingMessageId = null;

        // Run the agent
        return currentRunner.run(prompt, config)
                .whenComplete((result, error) -> {
                    currentRunner.removeListener(this);
                    if (error != null && stateChangeListener != null) {
                        asyncExec(() -> stateChangeListener.onStateChange(
                                AgentState.ERROR, error.getMessage()));
                    }
                });
    }

    /**
     * Отменяет текущее выполнение.
     */
    public void cancel() {
        if (currentRunner != null) {
            currentRunner.cancel();
        }
    }

    /**
     * Возвращает текущее состояние агента.
     */
    public AgentState getState() {
        return currentRunner != null ? currentRunner.getState() : AgentState.IDLE;
    }

    /**
     * Проверяет, выполняется ли агент.
     */
    public boolean isRunning() {
        AgentState state = getState();
        return state == AgentState.RUNNING ||
               state == AgentState.WAITING_TOOL ||
               state == AgentState.WAITING_CONFIRMATION;
    }

    @Override
    public void onEvent(AgentEvent event) {
        switch (event.getType()) {
            case STARTED:
                handleStarted((AgentStartedEvent) event);
                break;
            case STEP:
                handleStep((AgentStepEvent) event);
                break;
            case TOOL_CALL:
                handleToolCall((ToolCallEvent) event);
                break;
            case TOOL_RESULT:
                handleToolResult((ToolResultEvent) event);
                break;
            case STREAM_CHUNK:
                handleStreamChunk((StreamChunkEvent) event);
                break;
            case CONFIRMATION_REQUIRED:
                handleConfirmation((ConfirmationRequiredEvent) event);
                break;
            case COMPLETED:
                handleCompleted((AgentCompletedEvent) event);
                break;
            default:
                break;
        }
    }

    private void handleStarted(AgentStartedEvent event) {
        asyncExec(() -> {
            if (stateChangeListener != null) {
                stateChangeListener.onStateChange(AgentState.RUNNING, null);
            }
            if (messageAppender != null) {
                String info = String.format("🚀 Агент запущен (профиль: %s, макс. шагов: %d)",
                        event.getConfig().getProfileName() != null
                                ? event.getConfig().getProfileName() : "default",
                        event.getConfig().getMaxSteps());
                messageAppender.appendSystemMessage(info);
            }
        });
    }

    private void handleStep(AgentStepEvent event) {
        asyncExec(() -> {
            if (progressUpdater != null) {
                String status = String.format("Шаг %d/%d: %s",
                        event.getStep(),
                        event.getMaxSteps(),
                        event.getDescription());
                progressUpdater.updateProgress(
                        event.getStep(),
                        event.getMaxSteps(),
                        status);
            }
        });
    }

    private void handleToolCall(ToolCallEvent event) {
        asyncExec(() -> {
            if (messageAppender != null) {
                String icon = event.isRequiresConfirmation() ? "⚠️" : "🔧";
                String info = String.format("%s Вызов инструмента: %s",
                        icon, event.getToolName());
                messageAppender.appendSystemMessage(info);
            }
        });
    }

    private void handleToolResult(ToolResultEvent event) {
        asyncExec(() -> {
            if (messageAppender != null) {
                String icon = event.getResult().isSuccess() ? "✓" : "✗";
                String content = event.getResult().isSuccess()
                        ? event.getResult().getContent()
                        : event.getResult().getErrorMessage();

                // Truncate long results
                if (content != null && content.length() > 500) {
                    content = content.substring(0, 500) + "\n... (обрезано)";
                }

                String info = String.format("%s **%s** (%d мс)\n%s",
                        icon,
                        getToolDisplayName(event.getToolName()),
                        event.getExecutionTimeMs(),
                        content != null ? content : "");
                messageAppender.appendToolMessage(info);
            }
        });
    }

    private void handleStreamChunk(StreamChunkEvent event) {
        if (!event.isComplete() && event.getContent() != null) {
            streamingContent.append(event.getContent());
            asyncExec(() -> {
                if (messageAppender != null) {
                    messageAppender.updateStreamingMessage(
                            currentStreamingMessageId,
                            streamingContent.toString());
                }
            });
        } else if (event.isComplete()) {
            String finalContent = streamingContent.toString();
            streamingContent = new StringBuilder();
            currentStreamingMessageId = null;

            asyncExec(() -> {
                if (messageAppender != null && !finalContent.isEmpty()) {
                    messageAppender.appendAssistantMessage(finalContent);
                }
            });
        }
    }

    private void handleConfirmation(ConfirmationRequiredEvent event) {
        if (shouldSkipToolConfirmations()) {
            event.confirm();
            return;
        }

        asyncExec(() -> {
            if (confirmationHandler != null) {
                confirmationHandler.requestConfirmation(event);
            } else {
                // Default behavior - use dialog
                showConfirmationDialog(event);
            }
        });
    }

    private void showConfirmationDialog(ConfirmationRequiredEvent event) {
        if (display.isDisposed()) {
            event.deny();
            return;
        }

        ToolConfirmationDialog dialog = new ToolConfirmationDialog(
                display.getActiveShell(),
                event.getToolCall(),
                event.getToolDescription(),
                event.isDestructive()
        );

        if (dialog.openAndConfirm()) {
            event.confirm();
        } else if (dialog.wasSkipped()) {
            event.skip();
        } else {
            event.deny();
        }
    }

    private boolean shouldSkipToolConfirmations() {
        IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(CORE_PLUGIN_ID);
        return prefs.getBoolean(VibePreferenceConstants.PREF_AGENT_SKIP_TOOL_CONFIRMATIONS, false);
    }

    private void handleCompleted(AgentCompletedEvent event) {
        AgentResult result = event.getResult();

        asyncExec(() -> {
            if (stateChangeListener != null) {
                stateChangeListener.onStateChange(
                        result.getFinalState(),
                        result.getErrorMessage());
            }

            if (messageAppender != null) {
                String status;
                switch (result.getFinalState()) {
                    case COMPLETED:
                        status = String.format("✅ Агент завершён (шагов: %d, инструментов: %d, время: %d мс)",
                                result.getStepsExecuted(),
                                result.getToolCallsExecuted(),
                                result.getExecutionTimeMs());
                        break;
                    case CANCELLED:
                        status = "⊘ Агент отменён";
                        break;
                    case ERROR:
                        status = "❌ Ошибка агента: " + result.getErrorMessage();
                        break;
                    default:
                        status = "Агент завершён: " + result.getFinalState();
                }
                messageAppender.appendSystemMessage(status);

                // Append final response if available
                if (result.isSuccess() && result.getFinalResponse() != null
                        && !result.getFinalResponse().isEmpty()) {
                    messageAppender.appendAssistantMessage(result.getFinalResponse());
                }
            }
        });
    }

    /**
     * Возвращает локализованное имя инструмента.
     */
    private String getToolDisplayName(String name) {
        return switch (name) {
            case "read_file" -> "Чтение файла";
            case "edit_file" -> "Редактирование файла";
            case "write_file" -> "Создание файла";
            case "list_files" -> "Список файлов";
            case "glob" -> "Поиск файлов";
            case "grep" -> "Поиск текста";
            case "shell" -> "Команда оболочки";
            case "search_codebase" -> "Семантический поиск";
            case "edt_content_assist" -> "EDT автодополнение";
            case "edt_find_references" -> "EDT поиск ссылок";
            case "edt_metadata_details" -> "EDT детали метаданных";
            case "get_platform_documentation" -> "Справка платформы";
            case "bsl_symbol_at_position" -> "BSL символ по позиции";
            case "bsl_type_at_position" -> "BSL тип по позиции";
            case "bsl_scope_members" -> "BSL элементы области";
            case "edt_validate_request" -> "Валидация запроса EDT";
            case "create_metadata" -> "Создание метаданных EDT";
            case "add_metadata_child" -> "Создание вложенных метаданных EDT";
            case "edt_trace_export" -> "Трейс экспорта EDT";
            case "edt_metadata_smoke" -> "Smoke метаданных EDT";
            case "task" -> "Подзадача";
            default -> name;
        };
    }

    private void asyncExec(Runnable runnable) {
        if (!display.isDisposed()) {
            display.asyncExec(() -> {
                if (!display.isDisposed()) {
                    runnable.run();
                }
            });
        }
    }

    /**
     * Освобождает ресурсы.
     */
    public void dispose() {
        cancel();
        if (currentRunner != null) {
            currentRunner.dispose();
            currentRunner = null;
        }
    }

    // --- Callback interfaces ---

    /**
     * Callback для добавления сообщений в чат.
     */
    public interface MessageAppender {
        void appendSystemMessage(String message);
        void appendAssistantMessage(String message);
        void appendToolMessage(String message);
        void updateStreamingMessage(String messageId, String content);
    }

    /**
     * Callback для обновления прогресса.
     */
    public interface ProgressUpdater {
        void updateProgress(int current, int max, String status);
    }

    /**
     * Callback для изменения состояния.
     */
    public interface StateChangeListener {
        void onStateChange(AgentState newState, String errorMessage);
    }

    /**
     * Callback для запроса подтверждения.
     */
    public interface ConfirmationHandler {
        void requestConfirmation(ConfirmationRequiredEvent event);
    }
}
