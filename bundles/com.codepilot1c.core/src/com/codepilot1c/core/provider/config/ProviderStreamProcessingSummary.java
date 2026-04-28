package com.codepilot1c.core.provider.config;

import java.util.concurrent.atomic.AtomicInteger;

import com.codepilot1c.core.model.LlmResponse;

/**
 * Aggregated stream processing counters used for diagnostics and fallback decisions.
 */
final class ProviderStreamProcessingSummary {

    private static final int FALLBACK_PARSE_FAILURE_THRESHOLD = 3;
    private static final int FALLBACK_OPAQUE_CHUNK_THRESHOLD = 5;

    private final String correlationId;
    private final boolean requestHasTools;
    private final AtomicInteger nullPayloads = new AtomicInteger();
    private final AtomicInteger metadataChunks = new AtomicInteger();
    private final AtomicInteger opaqueChunks = new AtomicInteger();
    private final AtomicInteger parseFailures = new AtomicInteger();
    private final AtomicInteger contentChunks = new AtomicInteger();
    private final AtomicInteger reasoningChunks = new AtomicInteger();
    private final AtomicInteger toolCallFragments = new AtomicInteger();
    private final AtomicInteger completedToolCalls = new AtomicInteger();
    private final AtomicInteger repairedToolCalls = new AtomicInteger();
    private final AtomicInteger truncatedToolCalls = new AtomicInteger();
    private final AtomicInteger errorChunks = new AtomicInteger();
    private volatile String terminalErrorMessage;
    private volatile LlmResponse.Usage usage;

    ProviderStreamProcessingSummary(String correlationId, boolean requestHasTools) {
        this.correlationId = correlationId;
        this.requestHasTools = requestHasTools;
    }

    String getCorrelationId() {
        return correlationId;
    }

    AtomicInteger getNullPayloads() {
        return nullPayloads;
    }

    AtomicInteger getMetadataChunks() {
        return metadataChunks;
    }

    AtomicInteger getOpaqueChunks() {
        return opaqueChunks;
    }

    AtomicInteger getParseFailures() {
        return parseFailures;
    }

    AtomicInteger getContentChunks() {
        return contentChunks;
    }

    AtomicInteger getReasoningChunks() {
        return reasoningChunks;
    }

    AtomicInteger getToolCallFragments() {
        return toolCallFragments;
    }

    AtomicInteger getCompletedToolCalls() {
        return completedToolCalls;
    }

    AtomicInteger getRepairedToolCalls() {
        return repairedToolCalls;
    }

    AtomicInteger getTruncatedToolCalls() {
        return truncatedToolCalls;
    }

    AtomicInteger getErrorChunks() {
        return errorChunks;
    }

    void markTerminalError(String errorMessage) {
        terminalErrorMessage = errorMessage;
        errorChunks.incrementAndGet();
    }

    boolean hasTerminalError() {
        return terminalErrorMessage != null && !terminalErrorMessage.isBlank();
    }

    String getTerminalErrorMessage() {
        return terminalErrorMessage;
    }

    void setUsage(LlmResponse.Usage usage) {
        if (usage == null || this.usage != null) {
            return;
        }
        this.usage = usage;
    }

    LlmResponse.Usage getUsage() {
        return usage;
    }

    boolean hasUsage() {
        return usage != null;
    }

    boolean hasMeaningfulOutput() {
        // Reasoning-only responses (contentChunks=0, completedToolCalls=0) are NOT considered
        // meaningful — they indicate the model consumed its token budget on thinking without
        // producing actionable output. This enables fallback/retry for such cases.
        return contentChunks.get() > 0 || completedToolCalls.get() > 0;
    }

    /**
     * Returns true when the stream produced only reasoning tokens but no content or tool calls.
     * This is a known issue with Moonshot/Kimi models when thinking is enabled but
     * reasoning_content is not preserved in conversation history.
     */
    boolean isReasoningOnlyResponse() {
        return reasoningChunks.get() > 0
                && contentChunks.get() == 0
                && completedToolCalls.get() == 0;
    }

    boolean shouldFallbackToNonStreaming() {
        if (!requestHasTools || hasMeaningfulOutput()) {
            return false;
        }
        // Reasoning-only responses should also trigger fallback to non-streaming
        if (isReasoningOnlyResponse()) {
            return true;
        }
        return parseFailures.get() >= FALLBACK_PARSE_FAILURE_THRESHOLD
                || opaqueChunks.get() >= FALLBACK_OPAQUE_CHUNK_THRESHOLD
                || truncatedToolCalls.get() > 0;
    }
}
