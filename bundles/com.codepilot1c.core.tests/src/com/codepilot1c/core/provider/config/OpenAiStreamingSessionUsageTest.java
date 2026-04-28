package com.codepilot1c.core.provider.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.model.LlmStreamChunk;

public class OpenAiStreamingSessionUsageTest {

    @Test
    public void terminalUsageOnlyChunkEmitsUsageChunk() {
        OpenAiStreamingSession session = new OpenAiStreamingSession(
                "fixture-usage", false, new OpenAiStreamingToolCallParser()); //$NON-NLS-1$
        List<LlmStreamChunk> chunks = new ArrayList<>();

        session.processLine(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}", //$NON-NLS-1$
                chunks::add);
        session.processLine(
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}", //$NON-NLS-1$
                chunks::add);
        session.processLine(
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":30," //$NON-NLS-1$
                        + "\"total_tokens\":150,\"prompt_tokens_details\":{\"cached_tokens\":60}}}", //$NON-NLS-1$
                chunks::add);

        LlmResponse.Usage sessionUsage = session.getLastUsage();
        assertNotNull(sessionUsage);
        assertEquals(120, sessionUsage.getPromptTokens());
        assertEquals(60, sessionUsage.getCachedPromptTokens());
        assertEquals(30, sessionUsage.getCompletionTokens());
        assertEquals(150, sessionUsage.getTotalTokens());

        assertNotNull(session.getSummary().getUsage());
        assertEquals(150, session.getSummary().getUsage().getTotalTokens());
        assertEquals(1L, chunks.stream().filter(LlmStreamChunk::hasUsage).count());

        LlmStreamChunk usageChunk = chunks.stream()
                .filter(LlmStreamChunk::hasUsage)
                .findFirst()
                .orElseThrow();
        assertEquals(120, usageChunk.getUsage().getPromptTokens());
        assertEquals(30, usageChunk.getUsage().getCompletionTokens());
    }

    @Test
    public void usageAlongsideChoiceEmitsContentAndUsage() {
        OpenAiStreamingSession session = new OpenAiStreamingSession(
                "fixture-combined-usage", false, new OpenAiStreamingToolCallParser()); //$NON-NLS-1$
        List<LlmStreamChunk> chunks = new ArrayList<>();

        session.processLine(
                "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":null}]," //$NON-NLS-1$
                        + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3,\"total_tokens\":10}}", //$NON-NLS-1$
                chunks::add);

        assertEquals("ok", chunks.get(0).getContent()); //$NON-NLS-1$
        assertEquals(1L, chunks.stream().filter(LlmStreamChunk::hasUsage).count());
        assertEquals(10, session.getLastUsage().getTotalTokens());
    }

    @Test
    public void streamWithoutUsageChunkLeavesUsageNull() {
        OpenAiStreamingSession session = new OpenAiStreamingSession(
                "fixture-no-usage", false, new OpenAiStreamingToolCallParser()); //$NON-NLS-1$
        List<LlmStreamChunk> chunks = new ArrayList<>();

        session.processLine(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"},\"finish_reason\":null}]}", //$NON-NLS-1$
                chunks::add);
        session.processLine(
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}", //$NON-NLS-1$
                chunks::add);

        assertNull(session.getLastUsage());
        assertNull(session.getSummary().getUsage());
        assertEquals(0L, chunks.stream().filter(LlmStreamChunk::hasUsage).count());
    }

    @Test
    public void repeatedUsageChunksEmitOnlyOnceAndKeepLatestSessionUsage() {
        OpenAiStreamingSession session = new OpenAiStreamingSession(
                "fixture-usage-repeat", false, new OpenAiStreamingToolCallParser()); //$NON-NLS-1$
        List<LlmStreamChunk> chunks = new ArrayList<>();

        session.processLine(
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}", //$NON-NLS-1$
                chunks::add);
        session.processLine(
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":99,\"completion_tokens\":99,\"total_tokens\":198}}", //$NON-NLS-1$
                chunks::add);

        assertEquals(1L, chunks.stream().filter(LlmStreamChunk::hasUsage).count());
        LlmStreamChunk emitted = chunks.stream()
                .filter(LlmStreamChunk::hasUsage)
                .findFirst()
                .orElseThrow();
        assertEquals(10, emitted.getUsage().getPromptTokens());
        assertEquals(99, session.getLastUsage().getPromptTokens());
        assertEquals(15, session.getSummary().getUsage().getTotalTokens());
    }
}
