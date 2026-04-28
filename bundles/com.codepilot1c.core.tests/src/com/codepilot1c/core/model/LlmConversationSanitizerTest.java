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

    /**
     * Lock-in: when tool_call_id sets do not match, the sanitizer drops the WHOLE
     * assistant+tool block, not just the assistant envelope. Any softening of this
     * contract (e.g. synthesizing missing tool results) must be a deliberate design
     * change — not an accidental refactor.
     */
    @Test
    public void sanitizeDropsWholeBlockWhenToolCallIdsMismatch() {
        List<LlmMessage> messages = List.of(
                LlmMessage.user("user"),
                LlmMessage.assistantWithToolCalls("", List.of(
                        new ToolCall("expected_id", "read_file", "{}"))),
                LlmMessage.toolResult("unexpected_id", "oops"),
                LlmMessage.assistant("final"));

        List<LlmMessage> sanitized = LlmConversationSanitizer.sanitizeForOpenAiToolCalls(messages);

        assertEquals(2, sanitized.size());
        assertEquals(LlmMessage.Role.USER, sanitized.get(0).getRole());
        assertEquals(LlmMessage.Role.ASSISTANT, sanitized.get(1).getRole());
        assertEquals("final", sanitized.get(1).getContent());
    }

    /**
     * Lock-in: partial tool results (N tool_calls but fewer tool messages) also drop
     * the entire block. The strict 1:1 contract is required by OpenAI-compatible APIs.
     */
    @Test
    public void sanitizeDropsWholeBlockWhenToolResultCountDiffers() {
        List<LlmMessage> messages = List.of(
                LlmMessage.user("user"),
                LlmMessage.assistantWithToolCalls("", List.of(
                        new ToolCall("call_1", "read_file", "{}"),
                        new ToolCall("call_2", "read_file", "{}"))),
                LlmMessage.toolResult("call_1", "ok"),
                LlmMessage.assistant("final"));

        List<LlmMessage> sanitized = LlmConversationSanitizer.sanitizeForOpenAiToolCalls(messages);

        assertEquals(2, sanitized.size());
        assertEquals(LlmMessage.Role.USER, sanitized.get(0).getRole());
        assertEquals(LlmMessage.Role.ASSISTANT, sanitized.get(1).getRole());
        assertEquals("final", sanitized.get(1).getContent());
    }

    @Test
    public void sanitizeKeepsAssistantAndToolWhenIdsMatch() {
        List<LlmMessage> messages = List.of(
                LlmMessage.user("user"),
                LlmMessage.assistantWithToolCalls("", List.of(
                        new ToolCall("call_1", "read_file", "{}"))),
                LlmMessage.toolResult("call_1", "ok"));

        List<LlmMessage> sanitized = LlmConversationSanitizer.sanitizeForOpenAiToolCalls(messages);

        assertEquals(3, sanitized.size());
        assertEquals(LlmMessage.Role.ASSISTANT, sanitized.get(1).getRole());
        assertEquals(LlmMessage.Role.TOOL, sanitized.get(2).getRole());
        assertEquals("call_1", sanitized.get(2).getToolCallId());
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
