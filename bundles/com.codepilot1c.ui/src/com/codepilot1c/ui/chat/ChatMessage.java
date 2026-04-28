/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.chat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.codepilot1c.core.model.LlmContentPart;
import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.model.ToolCall;

/**
 * Represents a chat message in the UI layer.
 *
 * <p>This class is decoupled from {@link LlmMessage} to support UI-specific
 * features like message editing, regeneration, timestamps, and status tracking.</p>
 *
 * <p>A ChatMessage contains:</p>
 * <ul>
 *   <li>Unique ID for identification</li>
 *   <li>Timestamp for display</li>
 *   <li>Status (pending, complete, failed, etc.)</li>
 *   <li>Structured content as a list of {@link MessagePart}s</li>
 * </ul>
 */
public class ChatMessage {

    private final String id;
    private final MessageKind kind;
    private final Instant timestamp;
    private MessageStatus status;
    private String rawContent;
    private String reasoningContent;
    private final List<LlmContentPart> contentParts;
    private final List<MessagePart> parts;
    private String errorMessage;

    // For tool call tracking
    private final List<ToolCall> toolCalls;

    // For regeneration tracking
    private String supersededById;
    private String regeneratedFromId;

    /**
     * Creates a new chat message.
     *
     * @param kind the message kind
     * @param rawContent the raw content (Markdown)
     */
    public ChatMessage(MessageKind kind, String rawContent) {
        this(UUID.randomUUID().toString(), kind, rawContent, Instant.now(), MessageStatus.COMPLETE);
    }

    /**
     * Creates a new chat message with specified parameters.
     *
     * @param id unique identifier
     * @param kind the message kind
     * @param rawContent the raw content
     * @param timestamp creation time
     * @param status initial status
     */
    public ChatMessage(String id, MessageKind kind, String rawContent, Instant timestamp, MessageStatus status) {
        this.id = Objects.requireNonNull(id, "id must not be null"); //$NON-NLS-1$
        this.kind = Objects.requireNonNull(kind, "kind must not be null"); //$NON-NLS-1$
        this.rawContent = rawContent != null ? rawContent : ""; //$NON-NLS-1$
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp must not be null"); //$NON-NLS-1$
        this.status = Objects.requireNonNull(status, "status must not be null"); //$NON-NLS-1$
        this.contentParts = new ArrayList<>();
        this.parts = new ArrayList<>();
        this.toolCalls = new ArrayList<>();
    }

    // === Factory Methods ===

    /**
     * Creates a user message.
     *
     * @param content the message content
     * @return a new user message
     */
    public static ChatMessage user(String content) {
        return new ChatMessage(MessageKind.USER, content);
    }

    public static ChatMessage user(String content, List<LlmContentPart> contentParts) {
        ChatMessage message = new ChatMessage(MessageKind.USER, content);
        if (contentParts != null) {
            message.contentParts.addAll(contentParts);
        }
        return message;
    }

    /**
     * Creates an assistant message.
     *
     * @param content the message content
     * @return a new assistant message
     */
    public static ChatMessage assistant(String content) {
        return new ChatMessage(MessageKind.ASSISTANT, content);
    }

    /**
     * Creates an assistant message with provider reasoning content.
     *
     * @param content the message content
     * @param reasoningContent the reasoning content to replay to thinking APIs
     * @return a new assistant message
     */
    public static ChatMessage assistant(String content, String reasoningContent) {
        ChatMessage message = assistant(content);
        message.reasoningContent = reasoningContent;
        return message;
    }

    /**
     * Creates a pending assistant message (for streaming).
     *
     * @return a new pending assistant message
     */
    public static ChatMessage pendingAssistant() {
        return new ChatMessage(
                UUID.randomUUID().toString(),
                MessageKind.ASSISTANT,
                "", //$NON-NLS-1$
                Instant.now(),
                MessageStatus.PENDING
        );
    }

    /**
     * Creates a system message.
     *
     * @param content the message content
     * @return a new system message
     */
    public static ChatMessage system(String content) {
        return new ChatMessage(MessageKind.SYSTEM, content);
    }

    /**
     * Creates a tool call message.
     *
     * @param toolCalls the tool calls
     * @return a new tool call message
     */
    public static ChatMessage toolCall(List<ToolCall> toolCalls) {
        ChatMessage message = new ChatMessage(MessageKind.TOOL_CALL, null);
        message.toolCalls.addAll(toolCalls);
        return message;
    }

    /**
     * Creates a tool call message with provider reasoning content.
     *
     * @param toolCalls the tool calls
     * @param reasoningContent the reasoning content to replay to thinking APIs
     * @return a new tool call message
     */
    public static ChatMessage toolCall(List<ToolCall> toolCalls, String reasoningContent) {
        ChatMessage message = toolCall(toolCalls);
        message.reasoningContent = reasoningContent;
        return message;
    }

    /**
     * Creates a tool result message.
     *
     * @param toolCallId the tool call ID
     * @param toolName the tool name
     * @param content the result content
     * @param success whether execution succeeded
     * @return a new tool result message
     */
    public static ChatMessage toolResult(String toolCallId, String toolName, String content, boolean success) {
        ChatMessage message = new ChatMessage(MessageKind.TOOL_RESULT, content);
        message.parts.add(new MessagePart.ToolResultPart(
                toolCallId,
                toolName,
                content,
                success,
                MessagePart.ToolResultPart.ResultType.TEXT
        ));
        return message;
    }

    /**
     * Creates a ChatMessage from an LlmMessage.
     *
     * @param llmMessage the LLM message
     * @return a new ChatMessage
     */
    public static ChatMessage fromLlmMessage(LlmMessage llmMessage) {
        MessageKind kind = switch (llmMessage.getRole()) {
            case USER -> MessageKind.USER;
            case ASSISTANT -> llmMessage.hasToolCalls() ? MessageKind.TOOL_CALL : MessageKind.ASSISTANT;
            case SYSTEM -> MessageKind.SYSTEM;
            case TOOL -> MessageKind.TOOL_RESULT;
        };

        ChatMessage message = new ChatMessage(kind, llmMessage.getContent());
        message.reasoningContent = llmMessage.getReasoningContent();
        if (llmMessage.hasContentParts()) {
            message.contentParts.addAll(llmMessage.getContentParts());
        }

        if (llmMessage.hasToolCalls()) {
            message.toolCalls.addAll(llmMessage.getToolCalls());
        }

        return message;
    }

    // === Getters ===

    public String getId() {
        return id;
    }

    public MessageKind getKind() {
        return kind;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public MessageStatus getStatus() {
        return status;
    }

    public String getRawContent() {
        return rawContent;
    }

    public String getReasoningContent() {
        return reasoningContent;
    }

    public List<MessagePart> getParts() {
        return Collections.unmodifiableList(parts);
    }

    public List<LlmContentPart> getContentParts() {
        return Collections.unmodifiableList(contentParts);
    }

    public List<ToolCall> getToolCalls() {
        return Collections.unmodifiableList(toolCalls);
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getSupersededById() {
        return supersededById;
    }

    public String getRegeneratedFromId() {
        return regeneratedFromId;
    }

    // === Status Methods ===

    /**
     * Checks if this message is currently being streamed.
     *
     * @return true if status is PENDING
     */
    public boolean isPending() {
        return status == MessageStatus.PENDING;
    }

    /**
     * Checks if this message is complete.
     *
     * @return true if status is COMPLETE
     */
    public boolean isComplete() {
        return status == MessageStatus.COMPLETE;
    }

    /**
     * Checks if this message has failed.
     *
     * @return true if status is FAILED
     */
    public boolean isFailed() {
        return status == MessageStatus.FAILED;
    }

    /**
     * Checks if this message was superseded by a regeneration.
     *
     * @return true if superseded
     */
    public boolean isSuperseded() {
        return status == MessageStatus.SUPERSEDED;
    }

    /**
     * Checks if this message contains tool calls.
     *
     * @return true if there are tool calls
     */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /**
     * Checks if this message contains provider reasoning content.
     *
     * @return true if reasoning content is present
     */
    public boolean hasReasoningContent() {
        return reasoningContent != null && !reasoningContent.isEmpty();
    }

    // === Modification Methods ===

    /**
     * Sets the status of this message.
     *
     * @param status the new status
     */
    public void setStatus(MessageStatus status) {
        this.status = Objects.requireNonNull(status);
    }

    /**
     * Appends content to this message (for streaming).
     *
     * @param chunk the content chunk to append
     */
    public void appendContent(String chunk) {
        if (chunk != null && !chunk.isEmpty()) {
            this.rawContent = this.rawContent + chunk;
        }
    }

    /**
     * Sets provider reasoning content for replay to thinking APIs.
     *
     * @param reasoningContent the reasoning content
     */
    public void setReasoningContent(String reasoningContent) {
        this.reasoningContent = reasoningContent;
    }

    /**
     * Sets the error message (when status is FAILED).
     *
     * @param errorMessage the error message
     */
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    /**
     * Marks this message as superseded by another.
     *
     * @param newMessageId the ID of the superseding message
     */
    public void markSupersededBy(String newMessageId) {
        this.supersededById = newMessageId;
        this.status = MessageStatus.SUPERSEDED;
    }

    /**
     * Marks this message as regenerated from another.
     *
     * @param originalMessageId the ID of the original message
     */
    public void markRegeneratedFrom(String originalMessageId) {
        this.regeneratedFromId = originalMessageId;
    }

    /**
     * Sets the parsed parts for this message.
     *
     * @param parts the message parts
     */
    public void setParts(List<MessagePart> parts) {
        this.parts.clear();
        if (parts != null) {
            this.parts.addAll(parts);
        }
    }

    /**
     * Adds a message part.
     *
     * @param part the part to add
     */
    public void addPart(MessagePart part) {
        if (part != null) {
            this.parts.add(part);
        }
    }

    public void setContentParts(List<LlmContentPart> contentParts) {
        this.contentParts.clear();
        if (contentParts != null) {
            this.contentParts.addAll(contentParts);
        }
    }

    // === Conversion ===

    /**
     * Converts this ChatMessage to an LlmMessage for API calls.
     *
     * @return the corresponding LlmMessage
     */
    public LlmMessage toLlmMessage() {
        return switch (kind) {
            case USER -> !contentParts.isEmpty() ? LlmMessage.user(contentParts) : LlmMessage.user(rawContent);
            case ASSISTANT -> hasToolCalls()
                    ? LlmMessage.assistantWithToolCalls(rawContent, reasoningContent, toolCalls)
                    : LlmMessage.assistant(rawContent, reasoningContent);
            case SYSTEM -> LlmMessage.system(rawContent);
            case TOOL_CALL -> LlmMessage.assistantWithToolCalls(rawContent, reasoningContent, toolCalls);
            case TOOL_RESULT -> {
                // Find the tool call ID from parts
                String toolCallId = parts.stream()
                        .filter(p -> p instanceof MessagePart.ToolResultPart)
                        .map(p -> ((MessagePart.ToolResultPart) p).toolCallId())
                        .findFirst()
                        .orElse("unknown"); //$NON-NLS-1$
                yield LlmMessage.toolResult(toolCallId, rawContent);
            }
        };
    }

    @Override
    public String toString() {
        return String.format("ChatMessage[id=%s, kind=%s, status=%s]", id, kind, status); //$NON-NLS-1$
    }
}
