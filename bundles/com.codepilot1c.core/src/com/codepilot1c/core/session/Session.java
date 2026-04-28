/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.codepilot1c.core.model.LlmMessage;

/**
 * Представляет сессию диалога с агентом.
 *
 * <p>Сессия содержит:</p>
 * <ul>
 *   <li>Уникальный идентификатор</li>
 *   <li>Заголовок (автогенерируемый или пользовательский)</li>
 *   <li>Историю сообщений</li>
 *   <li>Привязку к проекту (опционально)</li>
 *   <li>Метаданные (время создания, обновления и т.д.)</li>
 * </ul>
 *
 * <p>Сессии можно сохранять и восстанавливать для продолжения диалога.</p>
 */
public class Session {

    /**
     * Статус сессии.
     */
    public enum SessionStatus {
        /** Активная сессия */
        ACTIVE,
        /** Приостановленная сессия */
        PAUSED,
        /** Завершенная сессия */
        COMPLETED,
        /** Архивированная сессия */
        ARCHIVED
    }

    private final String id;
    private String title;
    private String projectPath;
    private String projectName;
    private SessionStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private final List<SessionMessage> messages;
    private String systemPrompt;
    private String agentProfile;
    private int totalTokens;

    /**
     * Создает новую сессию.
     */
    public Session() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.status = SessionStatus.ACTIVE;
        this.messages = new ArrayList<>();
        this.totalTokens = 0;
    }

    /**
     * Создает сессию с указанным ID (для восстановления).
     */
    public Session(String id) {
        this.id = Objects.requireNonNull(id, "id");
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.status = SessionStatus.ACTIVE;
        this.messages = new ArrayList<>();
        this.totalTokens = 0;
    }

    /**
     * Уникальный идентификатор сессии.
     */
    public String getId() {
        return id;
    }

    /**
     * Заголовок сессии.
     */
    public String getTitle() {
        return title;
    }

    /**
     * Устанавливает заголовок сессии.
     */
    public void setTitle(String title) {
        this.title = title;
        touch();
    }

    /**
     * Путь к связанному проекту (абсолютный путь).
     */
    public String getProjectPath() {
        return projectPath;
    }

    /**
     * Устанавливает путь к проекту.
     */
    public void setProjectPath(String projectPath) {
        this.projectPath = projectPath;
        touch();
    }

    /**
     * Имя связанного проекта.
     */
    public String getProjectName() {
        return projectName;
    }

    /**
     * Устанавливает имя проекта.
     */
    public void setProjectName(String projectName) {
        this.projectName = projectName;
        touch();
    }

    /**
     * Статус сессии.
     */
    public SessionStatus getStatus() {
        return status;
    }

    /**
     * Устанавливает статус сессии.
     */
    public void setStatus(SessionStatus status) {
        this.status = status;
        touch();
    }

    /**
     * Время создания сессии.
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Время последнего обновления сессии.
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Список сообщений (неизменяемый).
     */
    public List<SessionMessage> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    /**
     * Добавляет сообщение в сессию.
     */
    public void addMessage(SessionMessage message) {
        Objects.requireNonNull(message, "message");
        messages.add(message);
        touch();

        // Auto-generate title from first user message
        if (title == null && message.getType() == SessionMessage.MessageType.USER) {
            title = generateTitle(message.getContent());
        }
    }

    /**
     * Добавляет все сообщения в сессию.
     */
    public void addMessages(List<SessionMessage> newMessages) {
        for (SessionMessage msg : newMessages) {
            addMessage(msg);
        }
    }

    /**
     * Количество сообщений в сессии.
     */
    public int getMessageCount() {
        return messages.size();
    }

    /**
     * Последнее сообщение (или null).
     */
    public SessionMessage getLastMessage() {
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }

    /**
     * Системный промпт сессии.
     */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    /**
     * Устанавливает системный промпт.
     */
    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
        touch();
    }

    /**
     * Профиль агента для этой сессии.
     */
    public String getAgentProfile() {
        return agentProfile;
    }

    /**
     * Устанавливает профиль агента.
     */
    public void setAgentProfile(String agentProfile) {
        this.agentProfile = agentProfile;
        touch();
    }

    /**
     * Общее количество токенов (примерная оценка).
     */
    public int getTotalTokens() {
        return totalTokens;
    }

    /**
     * Устанавливает общее количество токенов.
     */
    public void setTotalTokens(int totalTokens) {
        this.totalTokens = totalTokens;
    }

    /**
     * Добавляет токены к счетчику.
     */
    public void addTokens(int tokens) {
        this.totalTokens += tokens;
    }

    /**
     * Конвертирует сообщения сессии в формат LlmMessage для отправки провайдеру.
     */
    public List<LlmMessage> toLlmMessages() {
        List<LlmMessage> result = new ArrayList<>();

        // Add system prompt if present
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            result.add(LlmMessage.system(systemPrompt));
        }

        // Convert session messages to LlmMessages
        for (SessionMessage msg : messages) {
            switch (msg.getType()) {
                case USER:
                    result.add(msg.hasContentParts()
                            ? LlmMessage.user(msg.getContentParts())
                            : LlmMessage.user(msg.getContent()));
                    break;
                case ASSISTANT:
                    if (msg.hasToolCalls()) {
                        result.add(LlmMessage.assistantWithToolCalls(
                                msg.getContent(), msg.getReasoningContent(), msg.getToolCalls()));
                    } else {
                        result.add(LlmMessage.assistant(msg.getContent(), msg.getReasoningContent()));
                    }
                    break;
                case SYSTEM:
                    result.add(LlmMessage.system(msg.getContent()));
                    break;
                case TOOL_RESULT:
                    result.add(LlmMessage.toolResult(
                            msg.getToolCallId(), msg.getContent()));
                    break;
                case TOOL_CALL:
                    // Tool calls are typically part of assistant messages
                    break;
            }
        }

        return result;
    }

    /**
     * Очищает историю сообщений.
     */
    public void clearMessages() {
        messages.clear();
        touch();
    }

    /**
     * Привязана ли сессия к проекту.
     */
    public boolean hasProject() {
        return projectPath != null && !projectPath.isEmpty();
    }

    /**
     * Пуста ли сессия (нет сообщений).
     */
    public boolean isEmpty() {
        return messages.isEmpty();
    }

    /**
     * Обновляет время последнего изменения.
     */
    private void touch() {
        this.updatedAt = Instant.now();
    }

    /**
     * Генерирует заголовок из первого сообщения.
     */
    private String generateTitle(String content) {
        if (content == null || content.isEmpty()) {
            return "Новая сессия";
        }
        // Take first 50 chars or first line
        String title = content.split("\n")[0];
        if (title.length() > 50) {
            title = title.substring(0, 47) + "...";
        }
        return title;
    }

    /**
     * Устанавливает время обновления (для восстановления из хранилища).
     */
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return "Session{" +
                "id='" + id + '\'' +
                ", title='" + title + '\'' +
                ", project='" + projectName + '\'' +
                ", messages=" + messages.size() +
                ", status=" + status +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Session session = (Session) o;
        return Objects.equals(id, session.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
