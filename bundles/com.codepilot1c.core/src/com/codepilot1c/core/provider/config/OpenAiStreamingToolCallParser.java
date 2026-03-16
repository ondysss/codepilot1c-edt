package com.codepilot1c.core.provider.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.codepilot1c.core.model.ToolCall;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Stateful parser for fragmented streaming tool calls.
 */
final class OpenAiStreamingToolCallParser {

    private final Map<Integer, Accumulator> accumulators = new HashMap<>();

    int append(JsonArray toolCallsArray) {
        if (toolCallsArray == null || toolCallsArray.size() == 0) {
            return 0;
        }

        int processed = 0;
        for (JsonElement element : toolCallsArray) {
            JsonObject toolCallObject = getObject(element);
            if (toolCallObject == null) {
                continue;
            }

            int index = 0;
            if (toolCallObject.has("index") && !toolCallObject.get("index").isJsonNull()) { //$NON-NLS-1$ //$NON-NLS-2$
                index = toolCallObject.get("index").getAsInt(); //$NON-NLS-1$
            }

            Accumulator accumulator = accumulators.computeIfAbsent(Integer.valueOf(index), key -> new Accumulator());
            String id = getString(toolCallObject, "id"); //$NON-NLS-1$
            accumulator.id = mergeStableField(accumulator.id, id);

            JsonObject function = getObject(toolCallObject, "function"); //$NON-NLS-1$
            if (function != null) {
                accumulator.name = mergeStableField(accumulator.name, getString(function, "name")); //$NON-NLS-1$
                String arguments = getString(function, "arguments"); //$NON-NLS-1$
                if (arguments != null) {
                    accumulator.arguments.append(arguments);
                }
            }
            processed++;
        }
        return processed;
    }

    List<ToolCall> drainCompletedToolCalls() {
        List<ToolCall> toolCalls = new ArrayList<>();
        accumulators.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry::getKey))
                .forEach(entry -> {
                    Accumulator accumulator = entry.getValue();
                    if (accumulator.name == null || accumulator.name.isBlank()) {
                        return;
                    }
                    String id = accumulator.id != null && !accumulator.id.isBlank()
                            ? accumulator.id
                            : "tool_call_" + entry.getKey(); //$NON-NLS-1$
                    String arguments = accumulator.arguments.length() > 0
                            ? accumulator.arguments.toString()
                            : "{}"; //$NON-NLS-1$
                    toolCalls.add(new ToolCall(id, accumulator.name, arguments));
                });
        accumulators.clear();
        return toolCalls;
    }

    void clear() {
        accumulators.clear();
    }

    boolean hasPendingToolCalls() {
        return !accumulators.isEmpty();
    }

    private String mergeStableField(String current, String incoming) {
        if (incoming == null || incoming.isBlank()) {
            return current;
        }
        if (current == null || current.isBlank()) {
            return incoming;
        }
        if (current.equals(incoming) || current.endsWith(incoming)) {
            return current;
        }
        if (incoming.endsWith(current)) {
            return incoming;
        }
        return incoming;
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

    private static final class Accumulator {
        private String id = ""; //$NON-NLS-1$
        private String name = ""; //$NON-NLS-1$
        private final StringBuilder arguments = new StringBuilder();
    }
}
