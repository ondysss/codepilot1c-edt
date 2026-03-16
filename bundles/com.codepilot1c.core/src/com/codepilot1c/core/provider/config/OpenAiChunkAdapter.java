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
        JsonArray choices = getArray(json, "choices"); //$NON-NLS-1$
        if (choices == null || choices.size() == 0) {
            return OpenAiStreamChunkData.of(null, null, null, 0, List.of(), true, false);
        }

        JsonObject choice = getObject(choices.get(0));
        if (choice == null) {
            return OpenAiStreamChunkData.of(null, null, null, 0, List.of(), true, false);
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
        if (finishReason != null && toolCallParser.hasPendingToolCalls()) {
            completedToolCalls = toolCallParser.drainCompletedToolCalls();
            if (!completedToolCalls.isEmpty() && !LlmResponse.FINISH_REASON_TOOL_USE.equals(finishReason)) {
                finishReason = LlmResponse.FINISH_REASON_TOOL_USE;
            }
        }

        boolean metadataOnly = isMetadataOnly(choice, content, reasoning, toolCallFragments, finishReason, source);
        boolean opaque = !metadataOnly
                && content == null
                && reasoning == null
                && toolCallFragments == 0
                && completedToolCalls.isEmpty()
                && finishReason == null
                && source == null;

        return OpenAiStreamChunkData.of(content, reasoning, finishReason, toolCallFragments,
                completedToolCalls, metadataOnly, opaque);
    }

    private boolean isMetadataOnly(JsonObject choice, String content, String reasoning,
            int toolCallFragments, String finishReason, JsonObject source) {
        if (content != null || reasoning != null || toolCallFragments > 0 || finishReason != null) {
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
