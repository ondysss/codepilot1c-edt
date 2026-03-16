package com.codepilot1c.core.provider.config;

import java.util.function.Consumer;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.model.LlmStreamChunk;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Stateful OpenAI-compatible SSE session that normalizes chunks and updates diagnostics.
 */
final class OpenAiStreamingSession {

    private final OpenAiChunkAdapter chunkAdapter;
    private final ProviderStreamProcessingSummary summary;

    OpenAiStreamingSession(String correlationId, boolean requestHasTools,
            OpenAiStreamingToolCallParser toolCallParser) {
        this.summary = new ProviderStreamProcessingSummary(correlationId, requestHasTools);
        this.chunkAdapter = new OpenAiChunkAdapter(toolCallParser);
    }

    ProviderStreamProcessingSummary getSummary() {
        return summary;
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
                consumer.accept(LlmStreamChunk.content(chunkData.getContent()));
            }

            if (chunkData.getReasoning() != null && !chunkData.getReasoning().isEmpty()) {
                summary.getReasoningChunks().incrementAndGet();
                consumer.accept(LlmStreamChunk.reasoning(chunkData.getReasoning()));
            }

            if (chunkData.getToolCallFragments() > 0) {
                summary.getToolCallFragments().addAndGet(chunkData.getToolCallFragments());
            }

            if (chunkData.hasCompletedToolCalls()) {
                summary.getCompletedToolCalls().addAndGet(chunkData.getCompletedToolCalls().size());
                consumer.accept(LlmStreamChunk.toolCalls(chunkData.getCompletedToolCalls()));
            }

            if (chunkData.isMetadataOnly()) {
                summary.getMetadataChunks().incrementAndGet();
            } else if (chunkData.isOpaque()) {
                summary.getOpaqueChunks().incrementAndGet();
            }

            return chunkData.getFinishReason();
        } catch (Exception e) {
            summary.getParseFailures().incrementAndGet();
            return null;
        }
    }

    void logSummary(VibeLogger.CategoryLogger log) {
        if (summary.getNullPayloads().get() == 0
                && summary.getMetadataChunks().get() == 0
                && summary.getOpaqueChunks().get() == 0
                && summary.getParseFailures().get() == 0) {
            return;
        }
        log.debug("[%s] Stream summary: nullPayloads=%d, metadataChunks=%d, opaqueChunks=%d, parseFailures=%d, contentChunks=%d, reasoningChunks=%d, toolCallChunks=%d, completedToolCalls=%d", //$NON-NLS-1$
                summary.getCorrelationId(),
                summary.getNullPayloads().get(),
                summary.getMetadataChunks().get(),
                summary.getOpaqueChunks().get(),
                summary.getParseFailures().get(),
                summary.getContentChunks().get(),
                summary.getReasoningChunks().get(),
                summary.getToolCallFragments().get(),
                summary.getCompletedToolCalls().get());
    }
}
