package com.codepilot1c.core.provider.config;

import java.util.concurrent.atomic.AtomicInteger;

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

    boolean hasMeaningfulOutput() {
        return contentChunks.get() > 0 || reasoningChunks.get() > 0 || completedToolCalls.get() > 0;
    }

    boolean shouldFallbackToNonStreaming() {
        if (!requestHasTools || hasMeaningfulOutput()) {
            return false;
        }
        return parseFailures.get() >= FALLBACK_PARSE_FAILURE_THRESHOLD
                || opaqueChunks.get() >= FALLBACK_OPAQUE_CHUNK_THRESHOLD;
    }
}
