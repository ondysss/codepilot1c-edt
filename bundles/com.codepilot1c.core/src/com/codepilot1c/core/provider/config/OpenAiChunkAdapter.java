package com.codepilot1c.core.provider.config;

import java.util.List;

import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.model.ToolCall;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Converts raw OpenAI-compatible stream chunks into normalized events.
 */
final class OpenAiChunkAdapter {

    private final OpenAiStreamingToolCallParser toolCallParser;

    OpenAiChunkAdapter(OpenAiStreamingToolCallParser toolCallParser) {
        this.toolCallParser = toolCallParser;
    }

    OpenAiStreamChunkData adapt(JsonObject json) {
        String errorMessage = extractErrorMessage(json);
        LlmResponse.Usage usage = extractUsage(json);
        JsonArray choices = getArray(json, "choices"); //$NON-NLS-1$
        if (choices == null || choices.size() == 0) {
            // Terminal usage chunks carry empty/absent choices + a top-level usage object.
            // Keep them as metadata chunks so downstream counters aren't inflated, but
            // propagate the usage so the session can emit an LlmStreamChunk.usage(...).
            return OpenAiStreamChunkData.of(null, null, null, errorMessage, 0, 0, 0, List.of(), true, false, usage);
        }

        JsonObject choice = getObject(choices.get(0));
        if (choice == null) {
            return OpenAiStreamChunkData.of(null, null, null, errorMessage, 0, 0, 0, List.of(), true, false, usage);
        }

        JsonObject delta = getObject(choice, "delta"); //$NON-NLS-1$
        JsonObject message = getObject(choice, "message"); //$NON-NLS-1$
        JsonObject source = delta != null ? delta : message;

        String content = getString(source, "content"); //$NON-NLS-1$
        String reasoning = getString(source, "reasoning_content"); //$NON-NLS-1$
        if (reasoning == null) {
            reasoning = getString(source, "reasoning"); //$NON-NLS-1$
        }

        JsonArray toolCallsArray = getArray(source, "tool_calls"); //$NON-NLS-1$
        int toolCallFragments = toolCallParser.append(toolCallsArray);

        String finishReason = null;
        if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) { //$NON-NLS-1$ //$NON-NLS-2$
            finishReason = normalizeFinishReason(choice.get("finish_reason").getAsString()); //$NON-NLS-1$
        }

        List<ToolCall> completedToolCalls = List.of();
        int repairedToolCalls = 0;
        int truncatedToolCalls = 0;
        if (finishReason != null && toolCallParser.hasPendingToolCalls()) {
            OpenAiStreamingToolCallParser.DrainResult drainResult = toolCallParser.drainCompletedToolCalls();
            completedToolCalls = drainResult.toolCalls();
            repairedToolCalls = drainResult.repairedCount();
            truncatedToolCalls = drainResult.truncatedCount();
            if (!completedToolCalls.isEmpty() && !LlmResponse.FINISH_REASON_TOOL_USE.equals(finishReason)) {
                finishReason = LlmResponse.FINISH_REASON_TOOL_USE;
            }
        }

        if ("error".equals(finishReason) && (errorMessage == null || errorMessage.isBlank())) { //$NON-NLS-1$
            errorMessage = "OpenAI-compatible stream returned finish_reason=error"; //$NON-NLS-1$
        }

        boolean metadataOnly = isMetadataOnly(choice, content, reasoning, toolCallFragments, finishReason, source, errorMessage);
        boolean opaque = !metadataOnly
                && content == null
                && reasoning == null
                && toolCallFragments == 0
                && completedToolCalls.isEmpty()
                && errorMessage == null
                && finishReason == null
                && source == null;

        return OpenAiStreamChunkData.of(content, reasoning, finishReason, errorMessage, toolCallFragments,
                repairedToolCalls, truncatedToolCalls, completedToolCalls, metadataOnly, opaque, usage);
    }

    /**
     * Extracts a top-level {@code usage} object when present. OpenAI emits this as
     * the terminal chunk before {@code [DONE]} when {@code stream_options.include_usage}
     * is on; some gateways attach it to the same choices chunk that carries the
     * {@code finish_reason}, so it is extracted unconditionally.
     */
    private LlmResponse.Usage extractUsage(JsonObject json) {
        JsonObject usageObject = getObject(json, "usage"); //$NON-NLS-1$
        if (usageObject == null) {
            return null;
        }
        return OpenAiUsageParser.parse(usageObject);
    }

    private boolean isMetadataOnly(JsonObject choice, String content, String reasoning,
            int toolCallFragments, String finishReason, JsonObject source, String errorMessage) {
        if (content != null || reasoning != null || toolCallFragments > 0 || finishReason != null || errorMessage != null) {
            return false;
        }
        if (source == null) {
            return true;
        }
        if (source.entrySet().isEmpty()) {
            return true;
        }
        return source.has("role") || source.has("annotations"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String extractErrorMessage(JsonObject json) {
        JsonObject error = getObject(json, "error"); //$NON-NLS-1$
        if (error == null) {
            return null;
        }
        String message = getString(error, "message"); //$NON-NLS-1$
        if (message != null && !message.isBlank()) {
            return message;
        }
        String type = getString(error, "type"); //$NON-NLS-1$
        return type != null && !type.isBlank() ? type : "OpenAI-compatible stream error"; //$NON-NLS-1$
    }

    private String normalizeFinishReason(String finishReason) {
        if (finishReason == null || finishReason.isBlank()) {
            return LlmResponse.FINISH_REASON_STOP;
        }
        if ("tool_calls".equals(finishReason)) { //$NON-NLS-1$
            return LlmResponse.FINISH_REASON_TOOL_USE;
        }
        return finishReason;
    }

    private JsonArray getArray(JsonObject object, String propertyName) {
        if (object == null || propertyName == null || !object.has(propertyName) || object.get(propertyName).isJsonNull()) {
            return null;
        }
        JsonElement element = object.get(propertyName);
        return element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    private JsonObject getObject(JsonObject object, String propertyName) {
        if (object == null || propertyName == null || !object.has(propertyName) || object.get(propertyName).isJsonNull()) {
            return null;
        }
        return getObject(object.get(propertyName));
    }

    private JsonObject getObject(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonObject()) {
            return null;
        }
        return element.getAsJsonObject();
    }

    private String getString(JsonObject object, String propertyName) {
        if (object == null || propertyName == null || !object.has(propertyName) || object.get(propertyName).isJsonNull()) {
            return null;
        }
        JsonElement element = object.get(propertyName);
        return element.isJsonPrimitive() ? element.getAsString() : null;
    }
}
