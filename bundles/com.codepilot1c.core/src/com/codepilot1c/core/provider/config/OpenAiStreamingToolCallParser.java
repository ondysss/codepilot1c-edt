package com.codepilot1c.core.provider.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.codepilot1c.core.model.ToolCall;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Stateful parser for fragmented streaming tool calls.
 */
class OpenAiStreamingToolCallParser {

    private final Map<Integer, Accumulator> accumulators = new HashMap<>();
    private final Deque<ToolCall> readyToolCalls = new LinkedList<>();

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

            String id = getString(toolCallObject, "id"); //$NON-NLS-1$
            JsonObject function = getObject(toolCallObject, "function"); //$NON-NLS-1$
            String name = function != null ? getString(function, "name") : null; //$NON-NLS-1$

            Accumulator accumulator = accumulators.computeIfAbsent(Integer.valueOf(index), key -> new Accumulator());
            if (isCollision(accumulator, id, name)) {
                finalizeAccumulator(index, accumulator, false, readyToolCalls, new RepairStats());
                accumulator = new Accumulator();
                accumulators.put(Integer.valueOf(index), accumulator);
            }

            accumulator.id = mergeStableField(accumulator.id, id);
            if (function != null) {
                accumulator.name = mergeStableField(accumulator.name, name);
                String arguments = getString(function, "arguments"); //$NON-NLS-1$
                if (arguments != null) {
                    accumulator.arguments.append(arguments);
                }
            }
            processed++;
        }
        return processed;
    }

    DrainResult drainCompletedToolCalls() {
        List<ToolCall> toolCalls = new ArrayList<>(readyToolCalls);
        readyToolCalls.clear();
        RepairStats stats = new RepairStats();
        accumulators.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry::getKey))
                .forEach(entry -> finalizeAccumulator(entry.getKey().intValue(), entry.getValue(), true, toolCalls, stats));
        accumulators.clear();
        return new DrainResult(toolCalls, stats.repaired, stats.truncated);
    }

    void clear() {
        accumulators.clear();
        readyToolCalls.clear();
    }

    boolean hasPendingToolCalls() {
        return !accumulators.isEmpty() || !readyToolCalls.isEmpty();
    }

    boolean hasIncompleteToolCalls() {
        return accumulators.values().stream().anyMatch(Accumulator::hasArguments);
    }

    private void finalizeAccumulator(int index, Accumulator accumulator, boolean allowRepair,
            Collection<ToolCall> toolCalls, RepairStats stats) {
        if (accumulator == null || accumulator.name == null || accumulator.name.isBlank()) {
            return;
        }
        String id = accumulator.id != null && !accumulator.id.isBlank()
                ? accumulator.id
                : "tool_call_" + index; //$NON-NLS-1$
        RepairOutcome repairOutcome = repairArguments(accumulator.arguments.length() > 0
                ? accumulator.arguments.toString()
                : "{}"); //$NON-NLS-1$
        if (!repairOutcome.valid()) {
            stats.truncated++;
            return;
        }
        if (repairOutcome.repaired() && allowRepair) {
            stats.repaired++;
        }
        toolCalls.add(new ToolCall(id, accumulator.name, repairOutcome.arguments()));
    }

    private boolean isCollision(Accumulator accumulator, String id, String name) {
        if (accumulator == null || !accumulator.hasMeaningfulState()) {
            return false;
        }
        boolean idCollision = id != null && !id.isBlank()
                && accumulator.id != null && !accumulator.id.isBlank()
                && !accumulator.id.equals(id);
        boolean nameCollision = name != null && !name.isBlank()
                && accumulator.name != null && !accumulator.name.isBlank()
                && !accumulator.name.equals(name);
        return idCollision || nameCollision;
    }

    private RepairOutcome repairArguments(String arguments) {
        String normalized = arguments != null && !arguments.isBlank() ? arguments.strip() : "{}"; //$NON-NLS-1$
        if (isValidJson(normalized)) {
            return new RepairOutcome(normalized, false, true);
        }

        String repaired = normalized;
        if (repaired.endsWith(",")) { //$NON-NLS-1$
            repaired = repaired.substring(0, repaired.length() - 1);
        }
        repaired = repaired.replaceAll(",\\s*([}\\]])", "$1"); //$NON-NLS-1$ //$NON-NLS-2$
        if (repaired.endsWith(":")) { //$NON-NLS-1$
            return new RepairOutcome(normalized, false, false);
        }
        if (hasOddQuoteCount(repaired)) {
            repaired = repaired + "\""; //$NON-NLS-1$
        }
        repaired = closeOpenContainers(repaired);
        if (isValidJson(repaired)) {
            return new RepairOutcome(repaired, true, true);
        }
        return new RepairOutcome(normalized, false, false);
    }

    private boolean hasOddQuoteCount(String value) {
        boolean escaped = false;
        int quoteCount = 0;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == '"') {
                quoteCount++;
            }
        }
        return (quoteCount % 2) != 0;
    }

    private String closeOpenContainers(String value) {
        StringBuilder suffix = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;
        List<Character> stack = new ArrayList<>();
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (current == '{' || current == '[') {
                stack.add(Character.valueOf(current));
            } else if (current == '}' || current == ']') {
                if (stack.isEmpty()) {
                    return value;
                }
                stack.remove(stack.size() - 1);
            }
        }
        for (int i = stack.size() - 1; i >= 0; i--) {
            suffix.append(stack.get(i).charValue() == '{' ? '}' : ']');
        }
        return value + suffix;
    }

    private boolean isValidJson(String value) {
        try {
            JsonParser.parseString(value);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
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

        private boolean hasMeaningfulState() {
            return (id != null && !id.isBlank())
                    || (name != null && !name.isBlank())
                    || arguments.length() > 0;
        }

        private boolean hasArguments() {
            return arguments.length() > 0;
        }
    }

    static final class DrainResult {
        private final List<ToolCall> toolCalls;
        private final int repairedCount;
        private final int truncatedCount;

        private DrainResult(List<ToolCall> toolCalls, int repairedCount, int truncatedCount) {
            this.toolCalls = toolCalls;
            this.repairedCount = repairedCount;
            this.truncatedCount = truncatedCount;
        }

        List<ToolCall> toolCalls() {
            return toolCalls;
        }

        int repairedCount() {
            return repairedCount;
        }

        int truncatedCount() {
            return truncatedCount;
        }
    }

    private record RepairOutcome(String arguments, boolean repaired, boolean valid) {
    }

    private static final class RepairStats {
        private int repaired;
        private int truncated;
    }
}
