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

/**
 * Represents a chunk of streamed response from an LLM.
 */
public class LlmStreamChunk {

    private final String content;
    private final boolean isComplete;
    private final String finishReason;
    private final String errorMessage;
    private final List<ToolCall> toolCalls;
    private final String reasoningContent;
    private final LlmResponse.Usage usage;

    /**
     * Creates a new stream chunk.
     *
     * @param content      the content delta
     * @param isComplete   true if this is the final chunk
     * @param finishReason the reason for completion (if complete)
     * @param errorMessage the error message (if error)
     * @param toolCalls    the tool calls (if any)
     */
    public LlmStreamChunk(String content, boolean isComplete, String finishReason,
                          String errorMessage, List<ToolCall> toolCalls) {
        this(content, isComplete, finishReason, errorMessage, toolCalls, null, null);
    }

    /**
     * Creates a new stream chunk with reasoning content.
     *
     * @param content          the content delta
     * @param isComplete       true if this is the final chunk
     * @param finishReason     the reason for completion (if complete)
     * @param errorMessage     the error message (if error)
     * @param toolCalls        the tool calls (if any)
     * @param reasoningContent the reasoning/thinking content delta
     */
    public LlmStreamChunk(String content, boolean isComplete, String finishReason,
                          String errorMessage, List<ToolCall> toolCalls, String reasoningContent) {
        this(content, isComplete, finishReason, errorMessage, toolCalls, reasoningContent, null);
    }

    /**
     * Creates a new stream chunk with reasoning content and usage.
     *
     * @param content          the content delta
     * @param isComplete       true if this is the final chunk
     * @param finishReason     the reason for completion (if complete)
     * @param errorMessage     the error message (if error)
     * @param toolCalls        the tool calls (if any)
     * @param reasoningContent the reasoning/thinking content delta
     * @param usage            the token usage (only set on the terminal usage chunk)
     */
    public LlmStreamChunk(String content, boolean isComplete, String finishReason,
                          String errorMessage, List<ToolCall> toolCalls, String reasoningContent,
                          LlmResponse.Usage usage) {
        this.content = content;
        this.isComplete = isComplete;
        this.finishReason = finishReason;
        this.errorMessage = errorMessage;
        this.toolCalls = toolCalls != null ? Collections.unmodifiableList(toolCalls) : Collections.emptyList();
        this.reasoningContent = reasoningContent;
        this.usage = usage;
    }

    /**
     * Creates a new stream chunk without tool calls.
     *
     * @param content      the content delta
     * @param isComplete   true if this is the final chunk
     * @param finishReason the reason for completion (if complete)
     * @param errorMessage the error message (if error)
     */
    public LlmStreamChunk(String content, boolean isComplete, String finishReason, String errorMessage) {
        this(content, isComplete, finishReason, errorMessage, null);
    }

    /**
     * Creates a new stream chunk (backward compatibility).
     *
     * @param content      the content delta
     * @param isComplete   true if this is the final chunk
     * @param finishReason the reason for completion (if complete)
     */
    public LlmStreamChunk(String content, boolean isComplete, String finishReason) {
        this(content, isComplete, finishReason, null, null);
    }

    /**
     * Creates a content chunk.
     *
     * @param content the content delta
     * @return a new chunk
     */
    public static LlmStreamChunk content(String content) {
        return new LlmStreamChunk(content, false, null);
    }

    /**
     * Creates a completion chunk.
     *
     * @param finishReason the reason for completion
     * @return a new chunk
     */
    public static LlmStreamChunk complete(String finishReason) {
        return new LlmStreamChunk("", true, finishReason); //$NON-NLS-1$
    }

    /**
     * Creates an error chunk.
     *
     * @param errorMessage the error message
     * @return a new error chunk
     */
    public static LlmStreamChunk error(String errorMessage) {
        return new LlmStreamChunk("", true, "error", errorMessage, null); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Creates a tool calls chunk.
     *
     * @param toolCalls the tool calls
     * @return a new chunk with tool calls
     */
    public static LlmStreamChunk toolCalls(List<ToolCall> toolCalls) {
        return new LlmStreamChunk("", false, null, null, toolCalls); //$NON-NLS-1$
    }

    /**
     * Creates a reasoning content chunk.
     *
     * @param reasoning the reasoning/thinking content delta
     * @return a new chunk with reasoning content
     */
    public static LlmStreamChunk reasoning(String reasoning) {
        return new LlmStreamChunk("", false, null, null, null, reasoning); //$NON-NLS-1$
    }

    /**
     * Creates a terminal usage chunk carrying real token counts from the
     * provider. Emitted once per stream when the provider supports
     * {@code stream_options: {include_usage: true}}.
     *
     * <p>The chunk is intentionally non-complete so it does not trigger the
     * stream-termination path in downstream consumers; the normal completion
     * chunk still follows (or precedes) it.</p>
     *
     * @param usage the parsed usage; {@code null} yields {@code null}
     * @return a new chunk carrying the usage, or {@code null} when usage is
     *         {@code null}
     */
    public static LlmStreamChunk usage(LlmResponse.Usage usage) {
        if (usage == null) {
            return null;
        }
        return new LlmStreamChunk("", false, null, null, null, null, usage); //$NON-NLS-1$
    }

    public String getContent() {
        return content;
    }

    public boolean isComplete() {
        return isComplete;
    }

    public String getFinishReason() {
        return finishReason;
    }

    /**
     * Returns whether this chunk represents an error.
     *
     * @return true if this is an error chunk
     */
    public boolean isError() {
        return errorMessage != null;
    }

    /**
     * Returns the error message, if any.
     *
     * @return the error message or null
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Returns the tool calls in this chunk.
     *
     * @return the tool calls, empty if none
     */
    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    /**
     * Returns whether this chunk contains tool calls.
     *
     * @return true if there are tool calls
     */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /**
     * Returns whether this chunk indicates the model wants to use tools.
     *
     * @return true if this is a tool use chunk
     */
    public boolean isToolUse() {
        return !toolCalls.isEmpty() || LlmResponse.FINISH_REASON_TOOL_USE.equals(finishReason);
    }

    /**
     * Returns the reasoning/thinking content delta.
     *
     * @return the reasoning content or null
     */
    public String getReasoningContent() {
        return reasoningContent;
    }

    /**
     * Returns whether this chunk contains reasoning content.
     *
     * @return true if there is reasoning content
     */
    public boolean hasReasoning() {
        return reasoningContent != null && !reasoningContent.isEmpty();
    }

    /**
     * Returns whether this chunk carries a reasoning content field.
     * Empty reasoning chunks are meaningful for providers that require exact replay.
     */
    public boolean hasReasoningField() {
        return reasoningContent != null;
    }

    /**
     * Returns the real token usage carried by this chunk, if any.
     *
     * @return the usage, or {@code null} when this chunk is not a usage chunk
     */
    public LlmResponse.Usage getUsage() {
        return usage;
    }

    /**
     * Returns whether this chunk carries real token usage data.
     *
     * @return {@code true} if a non-null usage is attached
     */
    public boolean hasUsage() {
        return usage != null;
    }
}
