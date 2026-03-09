package com.codepilot1c.core.evaluation.trace.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.junit.Test;

import com.codepilot1c.core.agent.AgentConfig;
import com.codepilot1c.core.evaluation.trace.AgentTraceSession;
import com.codepilot1c.core.evaluation.trace.TracingLlmProvider;
import com.codepilot1c.core.model.LlmRequest;
import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.model.LlmStreamChunk;
import com.codepilot1c.core.model.ToolCall;
import com.codepilot1c.core.provider.ILlmProvider;
import com.google.gson.JsonObject;

public class TracingLlmProviderTest extends AbstractTraceTest {

    @Test
    public void completeWritesRequestAndResponseWithReasoning() throws Exception {
        LlmResponse expected = LlmResponse.builder()
                .content("done")
                .model("gpt-test")
                .usage(new LlmResponse.Usage(11, 7, 18))
                .finishReason(LlmResponse.FINISH_REASON_STOP)
                .reasoningContent("reasoning-summary")
                .build();
        FakeLlmProvider delegate = new FakeLlmProvider(expected, List.of(), false);
        AgentTraceSession session = AgentTraceSession.startAgentRun(
                AgentConfig.builder().profileName("explore").build(), delegate,
                "hello", "system");

        LlmRequest request = LlmRequest.builder()
                .systemMessage("system")
                .userMessage("hello")
                .model("gpt-test")
                .build();

        TracingLlmProvider traced = new TracingLlmProvider(delegate, session);
        LlmResponse actual = traced.complete(request).join();

        assertSame(expected, actual);

        List<JsonObject> llmEvents = readJsonLines(session.getLayout().getLlmFile());
        assertEquals(2, llmEvents.size());
        assertEquals("LLM_REQUEST", llmEvents.get(0).get("type").getAsString());
        assertEquals("LLM_RESPONSE", llmEvents.get(1).get("type").getAsString());
        assertEquals("reasoning-summary",
                llmEvents.get(1).getAsJsonObject("data").get("reasoning_content").getAsString());
        assertEquals(llmEvents.get(0).get("eventId").getAsString(),
                llmEvents.get(1).get("parentEventId").getAsString());
    }

    @Test
    public void streamCompleteAggregatesReasoningAndToolCallsInTrace() throws Exception {
        ToolCall toolCall = new ToolCall("call-1", "demo_tool", "{\"path\":\"/tmp/file\"}");
        List<LlmStreamChunk> chunks = List.of(
                LlmStreamChunk.reasoning("thinking"),
                LlmStreamChunk.content("partial"),
                new LlmStreamChunk("", false, null, null, List.of(toolCall)),
                new LlmStreamChunk("", true, LlmResponse.FINISH_REASON_TOOL_USE, null, List.of(toolCall), " more"));
        FakeLlmProvider delegate = new FakeLlmProvider(null, chunks, true);
        AgentTraceSession session = AgentTraceSession.startAgentRun(
                AgentConfig.builder().profileName("build").build(), delegate,
                "use tool", "system");

        LlmRequest request = LlmRequest.builder()
                .systemMessage("system")
                .userMessage("use tool")
                .model("gpt-stream")
                .stream(true)
                .build();
        TracingLlmProvider traced = new TracingLlmProvider(delegate, session);
        List<LlmStreamChunk> consumed = new ArrayList<>();

        traced.streamComplete(request, consumed::add);

        assertEquals(chunks.size(), consumed.size());
        assertSame(chunks.get(0), consumed.get(0));

        List<JsonObject> llmEvents = readJsonLines(session.getLayout().getLlmFile());
        assertTrue(llmEvents.size() >= 5);
        JsonObject responseEvent = llmEvents.get(llmEvents.size() - 1);
        assertEquals("LLM_RESPONSE", responseEvent.get("type").getAsString());
        JsonObject data = responseEvent.getAsJsonObject("data");
        assertEquals("partial", data.get("content").getAsString());
        assertEquals("thinking more", data.get("reasoning_content").getAsString());
        assertEquals(2, data.getAsJsonArray("tool_calls").size());
        assertFalse(data.get("tool_calls").isJsonNull());
    }

    private static final class FakeLlmProvider implements ILlmProvider {

        private final LlmResponse completeResponse;
        private final List<LlmStreamChunk> streamChunks;
        private final boolean streamingSupported;

        private FakeLlmProvider(LlmResponse completeResponse, List<LlmStreamChunk> streamChunks,
                boolean streamingSupported) {
            this.completeResponse = completeResponse;
            this.streamChunks = streamChunks;
            this.streamingSupported = streamingSupported;
        }

        @Override
        public String getId() {
            return "fake-provider";
        }

        @Override
        public String getDisplayName() {
            return "Fake Provider";
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            return streamingSupported;
        }

        @Override
        public CompletableFuture<LlmResponse> complete(LlmRequest request) {
            return CompletableFuture.completedFuture(completeResponse);
        }

        @Override
        public void streamComplete(LlmRequest request, Consumer<LlmStreamChunk> consumer) {
            for (LlmStreamChunk chunk : streamChunks) {
                consumer.accept(chunk);
            }
        }

        @Override
        public void cancel() {
        }

        @Override
        public void dispose() {
        }
    }
}
