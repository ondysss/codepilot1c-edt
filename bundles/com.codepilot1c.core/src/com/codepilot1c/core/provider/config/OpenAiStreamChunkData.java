package com.codepilot1c.core.provider.config;

import java.util.Collections;
import java.util.List;

import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.model.ToolCall;

/**
 * Normalized representation of one OpenAI-compatible stream chunk.
 */
final class OpenAiStreamChunkData {

    private final String content;
    private final String reasoning;
    private final String finishReason;
    private final String errorMessage;
    private final int toolCallFragments;
    private final int repairedToolCalls;
    private final int truncatedToolCalls;
    private final List<ToolCall> completedToolCalls;
    private final boolean metadataOnly;
    private final boolean opaque;
    private final LlmResponse.Usage usage;

    private OpenAiStreamChunkData(String content, String reasoning, String finishReason, String errorMessage,
            int toolCallFragments, int repairedToolCalls, int truncatedToolCalls, List<ToolCall> completedToolCalls,
            boolean metadataOnly, boolean opaque, LlmResponse.Usage usage) {
        this.content = content;
        this.reasoning = reasoning;
        this.finishReason = finishReason;
        this.errorMessage = errorMessage;
        this.toolCallFragments = toolCallFragments;
        this.repairedToolCalls = repairedToolCalls;
        this.truncatedToolCalls = truncatedToolCalls;
        this.completedToolCalls = completedToolCalls != null ? completedToolCalls : Collections.emptyList();
        this.metadataOnly = metadataOnly;
        this.opaque = opaque;
        this.usage = usage;
    }

    static OpenAiStreamChunkData of(String content, String reasoning, String finishReason, String errorMessage,
            int toolCallFragments, int repairedToolCalls, int truncatedToolCalls, List<ToolCall> completedToolCalls,
            boolean metadataOnly, boolean opaque) {
        return new OpenAiStreamChunkData(content, reasoning, finishReason, errorMessage, toolCallFragments,
                repairedToolCalls, truncatedToolCalls, completedToolCalls, metadataOnly, opaque, null);
    }

    static OpenAiStreamChunkData of(String content, String reasoning, String finishReason, String errorMessage,
            int toolCallFragments, int repairedToolCalls, int truncatedToolCalls, List<ToolCall> completedToolCalls,
            boolean metadataOnly, boolean opaque, LlmResponse.Usage usage) {
        return new OpenAiStreamChunkData(content, reasoning, finishReason, errorMessage, toolCallFragments,
                repairedToolCalls, truncatedToolCalls, completedToolCalls, metadataOnly, opaque, usage);
    }

    String getContent() {
        return content;
    }

    String getReasoning() {
        return reasoning;
    }

    String getFinishReason() {
        return finishReason;
    }

    String getErrorMessage() {
        return errorMessage;
    }

    int getToolCallFragments() {
        return toolCallFragments;
    }

    int getRepairedToolCalls() {
        return repairedToolCalls;
    }

    int getTruncatedToolCalls() {
        return truncatedToolCalls;
    }

    List<ToolCall> getCompletedToolCalls() {
        return completedToolCalls;
    }

    boolean hasCompletedToolCalls() {
        return !completedToolCalls.isEmpty();
    }

    boolean hasError() {
        return errorMessage != null && !errorMessage.isBlank();
    }

    boolean isMetadataOnly() {
        return metadataOnly;
    }

    boolean isOpaque() {
        return opaque;
    }

    LlmResponse.Usage getUsage() {
        return usage;
    }

    boolean hasUsage() {
        return usage != null;
    }
}
