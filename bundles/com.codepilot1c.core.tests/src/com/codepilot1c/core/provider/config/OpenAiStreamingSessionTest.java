package com.codepilot1c.core.provider.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.model.LlmStreamChunk;

public class OpenAiStreamingSessionTest {

    @Test
    public void glmFixtureProducesReasoningAndToolCall() throws Exception {
        assertFixtureProducesToolUse("glm5_reasoning_then_toolcall.sse", "call_glm", //$NON-NLS-1$ //$NON-NLS-2$
                "{\"operation\":\"status\"}", true); //$NON-NLS-1$
    }

    @Test
    public void minimaxFixtureProducesReasoningAndToolCall() throws Exception {
        assertFixtureProducesToolUse("minimax_reasoning_then_toolcall.sse", "call_minimax", //$NON-NLS-1$ //$NON-NLS-2$
                "{\"operation\":\"status\",\"repo_path\":\".\"}", true); //$NON-NLS-1$
    }

    @Test
    public void qwenFixtureProducesCleanToolCallWithoutFallbackSignals() throws Exception {
        OpenAiStreamingSession session = new OpenAiStreamingSession("fixture-qwen", true, new OpenAiStreamingToolCallParser()); //$NON-NLS-1$
        List<LlmStreamChunk> chunks = replayFixture("qwen_clean_toolcall.sse", session); //$NON-NLS-1$

        assertFalse(session.getSummary().shouldFallbackToNonStreaming());
        assertEquals(0, session.getSummary().getParseFailures().get());
        assertEquals(0, session.getSummary().getOpaqueChunks().get());
        assertNotNull(findToolChunk(chunks));
    }

    private void assertFixtureProducesToolUse(String fixtureName, String expectedId,
            String expectedArguments, boolean expectReasoning) throws Exception {
        OpenAiStreamingSession session = new OpenAiStreamingSession("fixture-" + fixtureName, true, //$NON-NLS-1$
                new OpenAiStreamingToolCallParser());
        List<LlmStreamChunk> chunks = replayFixture(fixtureName, session);

        LlmStreamChunk toolChunk = findToolChunk(chunks);
        assertNotNull(toolChunk);
        assertEquals(expectedId, toolChunk.getToolCalls().get(0).getId());
        assertEquals("git_inspect", toolChunk.getToolCalls().get(0).getName()); //$NON-NLS-1$
        assertEquals(expectedArguments, toolChunk.getToolCalls().get(0).getArguments());
        assertEquals(LlmResponse.FINISH_REASON_TOOL_USE, lastFinishReason(chunks));

        boolean hasReasoning = chunks.stream().anyMatch(chunk -> chunk.getReasoningContent() != null);
        assertEquals(expectReasoning, hasReasoning);
        assertFalse(session.getSummary().shouldFallbackToNonStreaming());
    }

    private List<LlmStreamChunk> replayFixture(String fixtureName, OpenAiStreamingSession session) throws IOException {
        List<LlmStreamChunk> chunks = new ArrayList<>();
        String finishReason = null;
        for (String line : Files.readAllLines(fixturePath(fixtureName))) {
            String currentFinishReason = session.processLine(line, chunks::add);
            if (currentFinishReason != null) {
                finishReason = currentFinishReason;
            }
        }
        if (finishReason != null) {
            chunks.add(LlmStreamChunk.complete(finishReason));
        }
        return chunks;
    }

    private Path fixturePath(String fixtureName) {
        return Path.of("/Users/alexorlik/repo/codepilot1c-oss/bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/provider/config/fixtures", //$NON-NLS-1$
                fixtureName);
    }

    private LlmStreamChunk findToolChunk(List<LlmStreamChunk> chunks) {
        return chunks.stream().filter(LlmStreamChunk::hasToolCalls).findFirst().orElse(null);
    }

    private String lastFinishReason(List<LlmStreamChunk> chunks) {
        for (int i = chunks.size() - 1; i >= 0; i--) {
            if (chunks.get(i).isComplete()) {
                return chunks.get(i).getFinishReason();
            }
        }
        return null;
    }
}
