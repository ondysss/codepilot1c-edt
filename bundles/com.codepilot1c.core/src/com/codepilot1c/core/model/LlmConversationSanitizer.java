package com.codepilot1c.core.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Helpers for preserving OpenAI-compatible tool-call message invariants.
 */
public final class LlmConversationSanitizer {

    private LlmConversationSanitizer() {
    }

    /**
     * Removes malformed tool-call fragments from history.
     * <p>
     * OpenAI-compatible backends require every assistant message with
     * {@code tool_calls} to be followed immediately by matching {@code tool}
     * messages. If history was compacted mid-chain, the safest recovery is to
     * drop the incomplete fragment.
     *
     * @param messages original history
     * @return sanitized copy safe for OpenAI-compatible serialization
     */
    public static List<LlmMessage> sanitizeForOpenAiToolCalls(List<LlmMessage> messages) {
        List<LlmMessage> sanitized = new ArrayList<>();
        if (messages == null || messages.isEmpty()) {
            return sanitized;
        }

        int index = 0;
        while (index < messages.size()) {
            LlmMessage message = messages.get(index);
            if (message == null) {
                index++;
                continue;
            }

            if (message.hasToolCalls()) {
                int nextIndex = index + 1;
                List<LlmMessage> toolResults = new ArrayList<>();
                while (nextIndex < messages.size()) {
                    LlmMessage next = messages.get(nextIndex);
                    if (next == null || next.getRole() != LlmMessage.Role.TOOL) {
                        break;
                    }
                    toolResults.add(next);
                    nextIndex++;
                }

                if (hasMatchingToolResults(message, toolResults)) {
                    sanitized.add(message);
                    sanitized.addAll(toolResults);
                }
                index = nextIndex;
                continue;
            }

            if (message.getRole() != LlmMessage.Role.TOOL) {
                sanitized.add(message);
            }
            index++;
        }

        return sanitized;
    }

    /**
     * Chooses a compaction start index that does not split a tool-call block.
     *
     * @param messages original history
     * @param desiredStart desired tail start index
     * @return safe start index
     */
    public static int findSafeCompactionStart(List<LlmMessage> messages, int desiredStart) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int start = Math.max(0, Math.min(desiredStart, messages.size()));
        while (start > 0 && startsInsideToolExchange(messages, start)) {
            start--;
        }
        return start;
    }

    private static boolean startsInsideToolExchange(List<LlmMessage> messages, int start) {
        if (start <= 0 || start >= messages.size()) {
            return false;
        }
        LlmMessage current = messages.get(start);
        if (current != null && current.getRole() == LlmMessage.Role.TOOL) {
            return true;
        }
        LlmMessage previous = messages.get(start - 1);
        return previous != null && previous.hasToolCalls();
    }

    private static boolean hasMatchingToolResults(LlmMessage assistantMessage, List<LlmMessage> toolResults) {
        if (assistantMessage == null || !assistantMessage.hasToolCalls()) {
            return false;
        }

        Set<String> expectedIds = new HashSet<>();
        for (ToolCall toolCall : assistantMessage.getToolCalls()) {
            if (toolCall != null && toolCall.getId() != null && !toolCall.getId().isBlank()) {
                expectedIds.add(toolCall.getId());
            }
        }
        if (expectedIds.isEmpty()) {
            return false;
        }

        Set<String> actualIds = new HashSet<>();
        for (LlmMessage toolResult : toolResults) {
            if (toolResult == null || toolResult.getToolCallId() == null || toolResult.getToolCallId().isBlank()) {
                return false;
            }
            actualIds.add(toolResult.getToolCallId());
        }

        return actualIds.equals(expectedIds) && actualIds.size() == toolResults.size();
    }
}
