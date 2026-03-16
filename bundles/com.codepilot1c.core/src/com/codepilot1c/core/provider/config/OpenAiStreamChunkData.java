package com.codepilot1c.core.provider.config;

import java.util.Collections;
import java.util.List;

import com.codepilot1c.core.model.ToolCall;

/**
 * Normalized representation of one OpenAI-compatible stream chunk.
 */
final class OpenAiStreamChunkData {

    private final String content;
    private final String reasoning;
    private final String finishReason;
    private final int toolCallFragments;
    private final List<ToolCall> completedToolCalls;
    private final boolean metadataOnly;
    private final boolean opaque;

    private OpenAiStreamChunkData(String content, String reasoning, String finishReason,
            int toolCallFragments, List<ToolCall> completedToolCalls, boolean metadataOnly, boolean opaque) {
        this.content = content;
        this.reasoning = reasoning;
        this.finishReason = finishReason;
        this.toolCallFragments = toolCallFragments;
        this.completedToolCalls = completedToolCalls != null ? completedToolCalls : Collections.emptyList();
        this.metadataOnly = metadataOnly;
        this.opaque = opaque;
    }

    static OpenAiStreamChunkData of(String content, String reasoning, String finishReason,
            int toolCallFragments, List<ToolCall> completedToolCalls, boolean metadataOnly, boolean opaque) {
        return new OpenAiStreamChunkData(content, reasoning, finishReason, toolCallFragments,
                completedToolCalls, metadataOnly, opaque);
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

    int getToolCallFragments() {
        return toolCallFragments;
    }

    List<ToolCall> getCompletedToolCalls() {
        return completedToolCalls;
    }

    boolean hasCompletedToolCalls() {
        return !completedToolCalls.isEmpty();
    }

    boolean isMetadataOnly() {
        return metadataOnly;
    }

    boolean isOpaque() {
        return opaque;
    }
}
