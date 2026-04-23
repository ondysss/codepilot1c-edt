/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.session;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.codepilot1c.core.model.LlmContentPart;
import com.codepilot1c.core.model.ToolCall;

/**
 * Представляет одно сообщение в сессии.
 *
 * <p>Поддерживает различные типы сообщений:</p>
 * <ul>
 *   <li>USER - сообщение пользователя</li>
 *   <li>ASSISTANT - ответ ассистента</li>
 *   <li>SYSTEM - системное сообщение</li>
 *   <li>TOOL_CALL - вызов инструмента</li>
 *   <li>TOOL_RESULT - результат инструмента</li>
 * </ul>
 */
public class SessionMessage {

    /**
     * Тип сообщения.
     */
    public enum MessageType {
        /** Сообщение пользователя */
        USER,
        /** Ответ ассистента */
        ASSISTANT,
        /** Системное сообщение */
        SYSTEM,
        /** Вызов инструмента */
        TOOL_CALL,
        /** Результат инструмента */
        TOOL_RESULT
    }

    private final String id;
    private final MessageType type;
    private final String content;
    private final List<LlmContentPart> contentParts;
    private final Instant timestamp;
    private final List<ToolCall> toolCalls;
    private final String toolCallId;
    private final String toolName;
    private final boolean isError;

    private SessionMessage(Builder builder) {
        this.id = builder.id != null ? builder.id : UUID.randomUUID().toString();
        this.type = Objects.requireNonNull(builder.type, "type");
        this.content = builder.content;
        this.contentParts = builder.contentParts != null
                ? Collections.unmodifiableList(builder.contentParts)
                : Collections.emptyList();
        this.timestamp = builder.timestamp != null ? builder.timestamp : Instant.now();
        this.toolCalls = builder.toolCalls != null
                ? Collections.unmodifiableList(builder.toolCalls)
                : Collections.emptyList();
        this.toolCallId = builder.toolCallId;
        this.toolName = builder.toolName;
        this.isError = builder.isError;
    }

    /**
     * Уникальный идентификатор сообщения.
     */
    public String getId() {
        return id;
    }

    /**
     * Тип сообщения.
     */
    public MessageType getType() {
        return type;
    }

    /**
     * Содержимое сообщения.
     */
    public String getContent() {
        return content;
    }

    public List<LlmContentPart> getContentParts() {
        return contentParts;
    }

    /**
     * Время создания сообщения.
     */
    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * Вызовы инструментов (для ASSISTANT сообщений).
     */
    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    /**
     * Есть ли вызовы инструментов.
     */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    public boolean hasContentParts() {
        return !contentParts.isEmpty();
    }

    /**
     * ID вызова инструмента (для TOOL_RESULT сообщений).
     */
    public String getToolCallId() {
        return toolCallId;
    }

    /**
     * Имя инструмента (для TOOL_CALL и TOOL_RESULT).
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * Является ли результат ошибкой (для TOOL_RESULT).
     */
    public boolean isError() {
        return isError;
    }

    // --- Factory methods ---

    /**
     * Создает сообщение пользователя.
     */
    public static SessionMessage user(String content) {
        return builder()
                .type(MessageType.USER)
                .content(content)
                .build();
    }

    public static SessionMessage user(String content, List<LlmContentPart> contentParts) {
        return builder()
                .type(MessageType.USER)
                .content(content)
                .contentParts(contentParts)
                .build();
    }

    /**
     * Создает ответ ассистента.
     */
    public static SessionMessage assistant(String content) {
        return builder()
                .type(MessageType.ASSISTANT)
                .content(content)
                .build();
    }

    /**
     * Создает ответ ассистента с вызовами инструментов.
     */
    public static SessionMessage assistantWithToolCalls(String content, List<ToolCall> toolCalls) {
        return builder()
                .type(MessageType.ASSISTANT)
                .content(content)
                .toolCalls(toolCalls)
                .build();
    }

    /**
     * Создает системное сообщение.
     */
    public static SessionMessage system(String content) {
        return builder()
                .type(MessageType.SYSTEM)
                .content(content)
                .build();
    }

    /**
     * Создает сообщение о вызове инструмента.
     */
    public static SessionMessage toolCall(String toolName, String toolCallId, String arguments) {
        return builder()
                .type(MessageType.TOOL_CALL)
                .toolName(toolName)
                .toolCallId(toolCallId)
                .content(arguments)
                .build();
    }

    /**
     * Создает сообщение с результатом инструмента.
     */
    public static SessionMessage toolResult(String toolCallId, String content, boolean isError) {
        return builder()
                .type(MessageType.TOOL_RESULT)
                .toolCallId(toolCallId)
                .content(content)
                .isError(isError)
                .build();
    }

    /**
     * Создает builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "SessionMessage{" +
                "type=" + type +
                ", content='" + (content != null && content.length() > 50
                        ? content.substring(0, 50) + "..."
                        : content) + '\'' +
                ", toolCalls=" + toolCalls.size() +
                '}';
    }

    /**
     * Builder для SessionMessage.
     */
    public static class Builder {
        private String id;
        private MessageType type;
        private String content;
        private List<LlmContentPart> contentParts;
        private Instant timestamp;
        private List<ToolCall> toolCalls;
        private String toolCallId;
        private String toolName;
        private boolean isError;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder type(MessageType type) {
            this.type = type;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder contentParts(List<LlmContentPart> contentParts) {
            this.contentParts = contentParts;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder toolCalls(List<ToolCall> toolCalls) {
            this.toolCalls = toolCalls;
            return this;
        }

        public Builder toolCallId(String toolCallId) {
            this.toolCallId = toolCallId;
            return this;
        }

        public Builder toolName(String toolName) {
            this.toolName = toolName;
            return this;
        }

        public Builder isError(boolean isError) {
            this.isError = isError;
            return this;
        }

        public SessionMessage build() {
            return new SessionMessage(this);
        }
    }
}
