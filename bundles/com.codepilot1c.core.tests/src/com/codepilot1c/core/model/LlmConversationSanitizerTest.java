package com.codepilot1c.core.model;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

public class LlmConversationSanitizerTest {

    @Test
    public void sanitizeDropsAssistantToolCallWithoutMatchingToolResult() {
        List<LlmMessage> messages = List.of(
                LlmMessage.user("user"),
                LlmMessage.assistantWithToolCalls("", List.of(
                        new ToolCall("call_1", "read_file", "{}"))),
                LlmMessage.assistant("final"));

        List<LlmMessage> sanitized = LlmConversationSanitizer.sanitizeForOpenAiToolCalls(messages);

        assertEquals(2, sanitized.size());
        assertEquals(LlmMessage.Role.USER, sanitized.get(0).getRole());
        assertEquals(LlmMessage.Role.ASSISTANT, sanitized.get(1).getRole());
        assertEquals("final", sanitized.get(1).getContent());
    }

    @Test
    public void findSafeCompactionStartMovesBoundaryBeforeToolResults() {
        List<LlmMessage> messages = List.of(
                LlmMessage.user("u1"),
                LlmMessage.assistantWithToolCalls("", List.of(
                        new ToolCall("call_1", "read_file", "{}"))),
                LlmMessage.toolResult("call_1", "ok"),
                LlmMessage.assistant("done"));

        int safeStart = LlmConversationSanitizer.findSafeCompactionStart(messages, 2);

        assertEquals(1, safeStart);
    }
}
