/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.model;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.stream.Collectors;

/**
 * Represents a message in a conversation with an LLM.
 */
public class LlmMessage {

    /**
     * Role of the message sender.
     */
    public enum Role {
        SYSTEM("system"), //$NON-NLS-1$
        USER("user"), //$NON-NLS-1$
        ASSISTANT("assistant"), //$NON-NLS-1$
        TOOL("tool"); //$NON-NLS-1$

        private final String value;

        Role(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    private final Role role;
    private final String content;
    private final String reasoningContent;
    private final List<LlmContentPart> contentParts;
    private final List<ToolCall> toolCalls;
    private final String toolCallId;

    /**
     * Creates a new message.
     *
     * @param role    the role of the sender
     * @param content the message content
     */
    public LlmMessage(Role role, String content) {
        this(role, content, null, null, null, null);
    }

    /**
     * Creates a new message with tool calls.
     *
     * @param role      the role of the sender
     * @param content   the message content
     * @param toolCalls the tool calls (for assistant messages)
     * @param toolCallId the tool call ID (for tool result messages)
     */
    public LlmMessage(Role role, String content, List<ToolCall> toolCalls, String toolCallId) {
        this(role, content, null, null, toolCalls, toolCallId);
    }

    /**
     * Creates a new message with structured content parts.
     *
     * @param role the role of the sender
     * @param contentParts the content parts
     */
    public LlmMessage(Role role, List<LlmContentPart> contentParts) {
        this(role, flattenContentParts(contentParts), null, contentParts, null, null);
    }

    /**
     * Creates a new message with fully specified fields (backward-compatible 5-arg).
     */
    public LlmMessage(Role role, String content, List<LlmContentPart> contentParts, List<ToolCall> toolCalls,
            String toolCallId) {
        this(role, content, null, contentParts, toolCalls, toolCallId);
    }

    /**
     * Creates a new message with fully specified fields including reasoning content.
     * Moonshot/Kimi API requires reasoning_content to be preserved in conversation history
     * when thinking mode is enabled — omitting it causes reasoning-only empty-content responses.
     */
    public LlmMessage(Role role, String content, String reasoningContent, List<LlmContentPart> contentParts,
            List<ToolCall> toolCalls, String toolCallId) {
        this.role = Objects.requireNonNull(role, "role must not be null"); //$NON-NLS-1$
        this.content = content != null ? content : ""; //$NON-NLS-1$
        this.reasoningContent = reasoningContent;
        this.contentParts = contentParts != null
                ? Collections.unmodifiableList(contentParts)
                : Collections.emptyList();
        this.toolCalls = toolCalls != null ? Collections.unmodifiableList(toolCalls) : Collections.emptyList();
        this.toolCallId = toolCallId;
    }

    /**
     * Creates a system message.
     *
     * @param content the message content
     * @return a new system message
     */
    public static LlmMessage system(String content) {
        return new LlmMessage(Role.SYSTEM, content);
    }

    public static LlmMessage system(List<LlmContentPart> contentParts) {
        return new LlmMessage(Role.SYSTEM, contentParts);
    }

    /**
     * Creates a user message.
     *
     * @param content the message content
     * @return a new user message
     */
    public static LlmMessage user(String content) {
        return new LlmMessage(Role.USER, content);
    }

    public static LlmMessage user(List<LlmContentPart> contentParts) {
        return new LlmMessage(Role.USER, contentParts);
    }

    /**
     * Creates an assistant message.
     *
     * @param content the message content
     * @return a new assistant message
     */
    public static LlmMessage assistant(String content) {
        return new LlmMessage(Role.ASSISTANT, content);
    }

    /**
     * Creates an assistant message with tool calls.
     *
     * @param content   the message content (may be null)
     * @param toolCalls the tool calls requested by the assistant
     * @return a new assistant message
     */
    public static LlmMessage assistantWithToolCalls(String content, List<ToolCall> toolCalls) {
        return new LlmMessage(Role.ASSISTANT, content, null, null, toolCalls, null);
    }

    /**
     * Creates an assistant message with tool calls and reasoning content.
     * Required by Moonshot/Kimi API: reasoning_content must be preserved in conversation
     * history when thinking mode is enabled, otherwise follow-up responses degrade to
     * reasoning-only with empty content.
     *
     * @param content          the message content (may be null)
     * @param reasoningContent the model's reasoning/thinking content (may be null)
     * @param toolCalls        the tool calls requested by the assistant
     * @return a new assistant message
     */
    public static LlmMessage assistantWithToolCalls(String content, String reasoningContent,
            List<ToolCall> toolCalls) {
        return new LlmMessage(Role.ASSISTANT, content, reasoningContent, null, toolCalls, null);
    }

    /**
     * Creates a tool result message.
     *
     * @param toolCallId the ID of the tool call this is a response to
     * @param content    the result content
     * @return a new tool message
     */
    public static LlmMessage toolResult(String toolCallId, String content) {
        return new LlmMessage(Role.TOOL, content, null, toolCallId);
    }

    public Role getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    /**
     * Returns the reasoning/thinking content from the model.
     * Must be preserved in conversation history for Moonshot/Kimi API compatibility.
     *
     * @return the reasoning content, or null if not present
     */
    public String getReasoningContent() {
        return reasoningContent;
    }

    /**
     * Returns whether this message has reasoning content.
     */
    public boolean hasReasoningContent() {
        return reasoningContent != null && !reasoningContent.isEmpty();
    }

    public List<LlmContentPart> getContentParts() {
        return contentParts;
    }

    public boolean hasContentParts() {
        return !contentParts.isEmpty();
    }

    public List<LlmAttachment> getAttachments() {
        return contentParts.stream()
                .map(LlmContentPart::getAttachment)
                .filter(Objects::nonNull)
                .toList();
    }

    public boolean hasImageAttachments() {
        return getAttachments().stream().anyMatch(LlmAttachment::isImage);
    }

    public boolean hasFileAttachments() {
        return getAttachments().stream().anyMatch(LlmAttachment::isFile);
    }

    public String getTextualContentFallback() {
        if (!hasContentParts()) {
            return content;
        }
        return flattenContentParts(contentParts);
    }

    /**
     * Returns the tool calls for this message.
     *
     * @return the tool calls, empty if none
     */
    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    /**
     * Returns whether this message contains tool calls.
     *
     * @return true if there are tool calls
     */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /**
     * Returns the tool call ID (for tool result messages).
     *
     * @return the tool call ID, or null
     */
    public String getToolCallId() {
        return toolCallId;
    }

    /**
     * Returns whether this is a tool result message.
     *
     * @return true if this is a tool result
     */
    public boolean isToolResult() {
        return role == Role.TOOL && toolCallId != null;
    }

    public Set<String> getAttachmentMimeTypes() {
        Set<String> result = new LinkedHashSet<>();
        for (LlmAttachment attachment : getAttachments()) {
            if (attachment.getMimeType() != null && !attachment.getMimeType().isBlank()) {
                result.add(attachment.getMimeType());
            }
        }
        return result;
    }

    private static String flattenContentParts(List<LlmContentPart> contentParts) {
        if (contentParts == null || contentParts.isEmpty()) {
            return ""; //$NON-NLS-1$
        }
        return contentParts.stream()
                .map(LlmContentPart::toTextFallback)
                .filter(part -> part != null && !part.isBlank())
                .collect(Collectors.joining("\n\n")); //$NON-NLS-1$
    }

    @Override
    public String toString() {
        return String.format("LlmMessage[role=%s, content=%s]", role, //$NON-NLS-1$
                content.length() > 50 ? content.substring(0, 50) + "..." : content); //$NON-NLS-1$
    }
}
