package com.codepilot1c.core.provider.config;

import java.util.function.Consumer;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.model.LlmStreamChunk;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Stateful OpenAI-compatible SSE session that normalizes chunks and updates diagnostics.
 */
final class OpenAiStreamingSession {

    private final OpenAiChunkAdapter chunkAdapter;
    private final OpenAiStreamingToolCallParser toolCallParser;
    private final ProviderStreamProcessingSummary summary;
    private final boolean qwenContentFallbackEnabled;
    private final boolean delayContentStreaming;
    private final StringBuilder bufferedContent = new StringBuilder();
    /**
     * Real usage captured from the terminal usage chunk emitted by providers that
     * honour {@code stream_options: {include_usage: true}}. Emitted downstream
     * exactly once as {@link LlmStreamChunk#usage(LlmResponse.Usage)}; subsequent
     * usage objects in the same stream are ignored to prevent double-emission.
     */
    private volatile LlmResponse.Usage lastUsage;
    private boolean usageChunkEmitted;

    OpenAiStreamingSession(String correlationId, boolean requestHasTools,
            OpenAiStreamingToolCallParser toolCallParser) {
        this(correlationId, requestHasTools, toolCallParser, false);
    }

    OpenAiStreamingSession(String correlationId, boolean requestHasTools,
            OpenAiStreamingToolCallParser toolCallParser,
            boolean qwenContentFallbackEnabled) {
        this.toolCallParser = toolCallParser;
        this.summary = new ProviderStreamProcessingSummary(correlationId, requestHasTools);
        this.chunkAdapter = new OpenAiChunkAdapter(toolCallParser);
        this.qwenContentFallbackEnabled = qwenContentFallbackEnabled;
        this.delayContentStreaming = qwenContentFallbackEnabled && requestHasTools;
    }

    ProviderStreamProcessingSummary getSummary() {
        return summary;
    }

    /**
     * Returns the real token usage captured from the terminal stream usage chunk,
     * or {@code null} when the provider did not emit one (capability off or stream
     * ended without a usage payload).
     */
    LlmResponse.Usage getLastUsage() {
        return lastUsage;
    }

    String processLine(String line, Consumer<LlmStreamChunk> consumer) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        if (!line.startsWith("data:")) { //$NON-NLS-1$
            return null;
        }

        String data = line.substring(5).trim();
        if ("[DONE]".equals(data)) { //$NON-NLS-1$
            return null;
        }

        try {
            JsonElement parsed = JsonParser.parseString(data);
            if (parsed == null || parsed.isJsonNull() || !parsed.isJsonObject()) {
                summary.getNullPayloads().incrementAndGet();
                return null;
            }

            JsonObject json = parsed.getAsJsonObject();
            OpenAiStreamChunkData chunkData = chunkAdapter.adapt(json);

            if (chunkData.getContent() != null && !chunkData.getContent().isEmpty()) {
                summary.getContentChunks().incrementAndGet();
                if (delayContentStreaming) {
                    bufferedContent.append(chunkData.getContent());
                } else {
                    consumer.accept(LlmStreamChunk.content(chunkData.getContent()));
                }
            }

            if (chunkData.getReasoning() != null && !chunkData.getReasoning().isEmpty()) {
                summary.getReasoningChunks().incrementAndGet();
                consumer.accept(LlmStreamChunk.reasoning(chunkData.getReasoning()));
            }

            if (chunkData.getToolCallFragments() > 0) {
                summary.getToolCallFragments().addAndGet(chunkData.getToolCallFragments());
            }

            if (chunkData.getRepairedToolCalls() > 0) {
                summary.getRepairedToolCalls().addAndGet(chunkData.getRepairedToolCalls());
            }

            if (chunkData.getTruncatedToolCalls() > 0) {
                summary.getTruncatedToolCalls().addAndGet(chunkData.getTruncatedToolCalls());
            }

            if (chunkData.hasCompletedToolCalls()) {
                summary.getCompletedToolCalls().addAndGet(chunkData.getCompletedToolCalls().size());
                consumer.accept(LlmStreamChunk.toolCalls(chunkData.getCompletedToolCalls()));
            }

            if (chunkData.hasError()) {
                summary.markTerminalError(chunkData.getErrorMessage());
                consumer.accept(LlmStreamChunk.error(chunkData.getErrorMessage()));
            }

            if (chunkData.isMetadataOnly()) {
                summary.getMetadataChunks().incrementAndGet();
            } else if (chunkData.isOpaque()) {
                summary.getOpaqueChunks().incrementAndGet();
            }

            if (chunkData.hasUsage()) {
                lastUsage = chunkData.getUsage();
                summary.setUsage(chunkData.getUsage());
                if (!usageChunkEmitted) {
                    LlmStreamChunk usageChunk = LlmStreamChunk.usage(chunkData.getUsage());
                    if (usageChunk != null) {
                        consumer.accept(usageChunk);
                        usageChunkEmitted = true;
                    }
                }
            }

            return chunkData.getFinishReason();
        } catch (Exception e) {
            summary.getParseFailures().incrementAndGet();
            return null;
        }
    }

    String completePendingToolCalls(Consumer<LlmStreamChunk> consumer) {
        boolean emittedToolUse = false;
        if (!toolCallParser.hasPendingToolCalls()) {
        } else {
            OpenAiStreamingToolCallParser.DrainResult drainResult = toolCallParser.drainCompletedToolCalls();
            if (drainResult.repairedCount() > 0) {
                summary.getRepairedToolCalls().addAndGet(drainResult.repairedCount());
            }
            if (drainResult.truncatedCount() > 0) {
                summary.getTruncatedToolCalls().addAndGet(drainResult.truncatedCount());
            }
            if (!drainResult.toolCalls().isEmpty()) {
                summary.getCompletedToolCalls().addAndGet(drainResult.toolCalls().size());
                consumer.accept(LlmStreamChunk.toolCalls(drainResult.toolCalls()));
                emittedToolUse = true;
            }
        }

        String delayedContent = flushBufferedContent(consumer, emittedToolUse);
        if (!emittedToolUse && qwenContentFallbackEnabled
                && QwenContentToolCallParser.hasToolCallMarkers(delayedContent)) {
            java.util.List<com.codepilot1c.core.model.ToolCall> fallbackCalls =
                    QwenContentToolCallParser.extractFromContent(delayedContent);
            if (!fallbackCalls.isEmpty()) {
                summary.getCompletedToolCalls().addAndGet(fallbackCalls.size());
                String stripped = QwenContentToolCallParser.stripToolCallBlocks(delayedContent);
                if (stripped != null && !stripped.isBlank()) {
                    consumer.accept(LlmStreamChunk.content(stripped));
                }
                consumer.accept(LlmStreamChunk.toolCalls(fallbackCalls));
                return com.codepilot1c.core.model.LlmResponse.FINISH_REASON_TOOL_USE;
            }
        }

        if (delayedContent != null && !delayedContent.isBlank()) {
            String flushedContent = delayedContent;
            if (qwenContentFallbackEnabled && QwenContentToolCallParser.hasToolCallMarkers(flushedContent)) {
                flushedContent = QwenContentToolCallParser.stripToolCallBlocks(flushedContent);
            }
            if (flushedContent != null && !flushedContent.isBlank()) {
                consumer.accept(LlmStreamChunk.content(flushedContent));
            }
        }
        return emittedToolUse ? com.codepilot1c.core.model.LlmResponse.FINISH_REASON_TOOL_USE : null;
    }

    private String flushBufferedContent(Consumer<LlmStreamChunk> consumer, boolean emittedToolUse) {
        if (!delayContentStreaming || bufferedContent.length() == 0) {
            return null;
        }
        String delayedContent = bufferedContent.toString();
        bufferedContent.setLength(0);
        if (emittedToolUse && delayedContent != null && !delayedContent.isBlank()) {
            String stripped = delayedContent;
            if (qwenContentFallbackEnabled && QwenContentToolCallParser.hasToolCallMarkers(stripped)) {
                stripped = QwenContentToolCallParser.stripToolCallBlocks(stripped);
            }
            if (stripped != null && !stripped.isBlank()) {
                consumer.accept(LlmStreamChunk.content(stripped));
            }
            return null;
        }
        return delayedContent;
    }

    void logSummary(VibeLogger.CategoryLogger log) {
        if (summary.getNullPayloads().get() == 0
                && summary.getMetadataChunks().get() == 0
                && summary.getOpaqueChunks().get() == 0
                && summary.getParseFailures().get() == 0) {
            return;
        }
        log.debug("[%s] Stream summary: nullPayloads=%d, metadataChunks=%d, opaqueChunks=%d, parseFailures=%d, contentChunks=%d, reasoningChunks=%d, toolCallChunks=%d, completedToolCalls=%d repairedToolCalls=%d, truncatedToolCalls=%d, errorChunks=%d", //$NON-NLS-1$
                summary.getCorrelationId(),
                summary.getNullPayloads().get(),
                summary.getMetadataChunks().get(),
                summary.getOpaqueChunks().get(),
                summary.getParseFailures().get(),
                summary.getContentChunks().get(),
                summary.getReasoningChunks().get(),
                summary.getToolCallFragments().get(),
                summary.getCompletedToolCalls().get(),
                summary.getRepairedToolCalls().get(),
                summary.getTruncatedToolCalls().get(),
                summary.getErrorChunks().get());
    }
}
