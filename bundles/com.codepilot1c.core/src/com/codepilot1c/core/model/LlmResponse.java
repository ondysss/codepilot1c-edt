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

/**
 * Represents a response from an LLM provider.
 */
public class LlmResponse {

    /** Finish reason when the model wants to use tools */
    public static final String FINISH_REASON_TOOL_USE = "tool_use"; //$NON-NLS-1$
    /** Finish reason for normal completion */
    public static final String FINISH_REASON_STOP = "stop"; //$NON-NLS-1$

    private final String content;
    private final String model;
    private final String resolvedModel;
    private final Usage usage;
    private final String finishReason;
    private final List<ToolCall> toolCalls;
    private final String reasoningContent;

    /**
     * Creates a new response.
     *
     * @param content      the generated content
     * @param model        the model used
     * @param usage        token usage information
     * @param finishReason the reason for completion
     */
    public LlmResponse(String content, String model, Usage usage, String finishReason) {
        this(content, model, model, usage, finishReason, null);
    }

    /**
     * Creates a new response with tool calls.
     *
     * @param content      the generated content (may be null if only tool calls)
     * @param model        the model used
     * @param usage        token usage information
     * @param finishReason the reason for completion
     * @param toolCalls    list of tool calls requested by the model
     */
    public LlmResponse(String content, String model, Usage usage, String finishReason, List<ToolCall> toolCalls) {
        this(content, model, model, usage, finishReason, toolCalls, null);
    }

    public LlmResponse(String content, String model, String resolvedModel, Usage usage, String finishReason,
            List<ToolCall> toolCalls) {
        this(content, model, resolvedModel, usage, finishReason, toolCalls, null);
    }

    /**
     * Creates a new response with tool calls and reasoning content.
     *
     * @param content          the generated content (may be null if only tool calls)
     * @param model            the model used
     * @param usage            token usage information
     * @param finishReason     the reason for completion
     * @param toolCalls        list of tool calls requested by the model
     * @param reasoningContent the model's reasoning/thinking content
     */
    public LlmResponse(String content, String model, Usage usage, String finishReason,
            List<ToolCall> toolCalls, String reasoningContent) {
        this(content, model, model, usage, finishReason, toolCalls, reasoningContent);
    }

    public LlmResponse(String content, String model, String resolvedModel, Usage usage, String finishReason,
            List<ToolCall> toolCalls, String reasoningContent) {
        this.content = content != null ? content : ""; //$NON-NLS-1$
        this.model = model;
        this.resolvedModel = resolvedModel != null && !resolvedModel.isBlank() ? resolvedModel : model;
        this.usage = usage;
        this.finishReason = finishReason;
        this.toolCalls = toolCalls != null ? Collections.unmodifiableList(toolCalls) : Collections.emptyList();
        this.reasoningContent = reasoningContent;
    }

    /**
     * Creates a simple response with just content.
     *
     * @param content the generated content
     * @return a new response
     */
    public static LlmResponse of(String content) {
        return new LlmResponse(content, null, null, null);
    }

    /**
     * Creates a response with tool calls.
     *
     * @param toolCalls the tool calls
     * @return a new response
     */
    public static LlmResponse withToolCalls(List<ToolCall> toolCalls) {
        return new LlmResponse(null, null, null, FINISH_REASON_TOOL_USE, toolCalls);
    }

    public String getContent() {
        return content;
    }

    public String getModel() {
        return model;
    }

    public String getResolvedModel() {
        return resolvedModel;
    }

    public Usage getUsage() {
        return usage;
    }

    public String getFinishReason() {
        return finishReason;
    }

    /**
     * Returns the tool calls requested by the model.
     *
     * @return list of tool calls, empty if none
     */
    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    /**
     * Returns whether this response contains tool calls.
     *
     * @return true if there are tool calls
     */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /**
     * Returns whether the model wants to use tools.
     *
     * @return true if finish reason is tool_use
     */
    public boolean isToolUse() {
        return FINISH_REASON_TOOL_USE.equals(finishReason) || !toolCalls.isEmpty();
    }

    /**
     * Returns the reasoning/thinking content from the model.
     *
     * @return the reasoning content, or null if not present
     */
    public String getReasoningContent() {
        return reasoningContent;
    }

    /**
     * Returns whether this response contains reasoning content.
     *
     * @return true if there is reasoning content
     */
    public boolean hasReasoning() {
        return reasoningContent != null && !reasoningContent.isEmpty();
    }

    /**
     * Creates a new builder.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for LlmResponse.
     */
    public static class Builder {
        private String content;
        private String model;
        private String resolvedModel;
        private Usage usage;
        private String finishReason;
        private List<ToolCall> toolCalls;
        private String reasoningContent;

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder resolvedModel(String resolvedModel) {
            this.resolvedModel = resolvedModel;
            return this;
        }

        public Builder usage(Usage usage) {
            this.usage = usage;
            return this;
        }

        public Builder finishReason(String finishReason) {
            this.finishReason = finishReason;
            return this;
        }

        public Builder toolCalls(List<ToolCall> toolCalls) {
            this.toolCalls = toolCalls;
            return this;
        }

        public Builder reasoningContent(String reasoningContent) {
            this.reasoningContent = reasoningContent;
            return this;
        }

        public LlmResponse build() {
            return new LlmResponse(content, model, resolvedModel, usage, finishReason, toolCalls, reasoningContent);
        }
    }

    /**
     * Token usage information.
     */
    public static class Usage {
        private final int promptTokens;
        private final int cachedPromptTokens;
        private final int completionTokens;
        private final int totalTokens;

        public Usage(int promptTokens, int completionTokens, int totalTokens) {
            this(promptTokens, 0, completionTokens, totalTokens);
        }

        public Usage(int promptTokens, int cachedPromptTokens, int completionTokens, int totalTokens) {
            this.promptTokens = promptTokens;
            this.cachedPromptTokens = cachedPromptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
        }

        public int getPromptTokens() {
            return promptTokens;
        }

        public int getCachedPromptTokens() {
            return cachedPromptTokens;
        }

        public int getCompletionTokens() {
            return completionTokens;
        }

        public int getTotalTokens() {
            return totalTokens;
        }
    }
}
