package com.codepilot1c.core.provider.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.Test;

import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.model.LlmRequest;
import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.model.LlmStreamChunk;
import com.codepilot1c.core.model.ToolCall;
import com.codepilot1c.core.model.ToolDefinition;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class DynamicLlmProviderStreamingTest {

    @Test
    public void streamIgnoresNullContentAndAccumulatesToolCalls() throws Exception {
        String streamBody = ""
                + "data: {\"choices\":[{\"delta\":{\"content\":null},\"finish_reason\":null}]}\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"git_inspect\",\"arguments\":\"\"}}]},\"finish_reason\":null}]}\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"operation\\\":\\\"status\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n"
                + "data: [DONE]\n";

        HttpServer server = startServer(new DualModeHandler(streamBody, nonStreamingTextResponse("unused"))); //$NON-NLS-1$
        try {
            DynamicLlmProvider provider = createProvider(server);
            List<LlmStreamChunk> chunks = new ArrayList<>();

            provider.streamComplete(createToolRequest(), chunks::add);

            assertFalse(chunks.isEmpty());
            LlmStreamChunk toolChunk = findToolChunk(chunks);
            assertNotNull(toolChunk);
            assertEquals(1, toolChunk.getToolCalls().size());
            ToolCall toolCall = toolChunk.getToolCalls().get(0);
            assertEquals("git_inspect", toolCall.getName()); //$NON-NLS-1$
            assertEquals("{\"operation\":\"status\"}", toolCall.getArguments()); //$NON-NLS-1$

            LlmStreamChunk completeChunk = chunks.get(chunks.size() - 1);
            assertTrue(completeChunk.isComplete());
            assertEquals(LlmResponse.FINISH_REASON_TOOL_USE, completeChunk.getFinishReason());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void streamIgnoresNullAndMetadataChunksBeforeContent() throws Exception {
        String streamBody = ""
                + "data: null\n"
                + "data: {\"id\":\"evt_1\",\"choices\":[]}\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"Привет\"},\"finish_reason\":null}]}\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n"
                + "data: [DONE]\n";

        HttpServer server = startServer(new DualModeHandler(streamBody, nonStreamingTextResponse("unused"))); //$NON-NLS-1$
        try {
            DynamicLlmProvider provider = createProvider(server);
            List<LlmStreamChunk> chunks = new ArrayList<>();

            provider.streamComplete(createPlainRequest(), chunks::add);

            StringBuilder content = new StringBuilder();
            for (LlmStreamChunk chunk : chunks) {
                if (chunk.getContent() != null) {
                    content.append(chunk.getContent());
                }
            }
            assertEquals("Привет", content.toString()); //$NON-NLS-1$
            LlmStreamChunk completeChunk = chunks.get(chunks.size() - 1);
            assertTrue(completeChunk.isComplete());
            assertEquals(LlmResponse.FINISH_REASON_STOP, completeChunk.getFinishReason());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void streamFallsBackToNonStreamingWhenMalformedChunksPreventToolUse() throws Exception {
        String streamBody = ""
                + "data: {\"choices\":[\n"
                + "data: {broken json\n"
                + "data: {\"choices\":[\n"
                + "data: [DONE]\n";

        String nonStreamBody = "{"
                + "\"choices\":[{"
                + "\"message\":{"
                + "\"role\":\"assistant\","
                + "\"content\":null,"
                + "\"tool_calls\":[{"
                + "\"id\":\"call_fallback\","
                + "\"type\":\"function\","
                + "\"function\":{"
                + "\"name\":\"git_inspect\","
                + "\"arguments\":\"{\\\"operation\\\":\\\"status\\\"}\""
                + "}"
                + "}]"
                + "},"
                + "\"finish_reason\":\"tool_calls\""
                + "}]"
                + "}";

        HttpServer server = startServer(new DualModeHandler(streamBody, nonStreamBody));
        try {
            DynamicLlmProvider provider = createProvider(server);
            List<LlmStreamChunk> chunks = new ArrayList<>();

            provider.streamComplete(createToolRequest(), chunks::add);

            LlmStreamChunk toolChunk = findToolChunk(chunks);
            assertNotNull(toolChunk);
            assertEquals(1, toolChunk.getToolCalls().size());
            assertEquals("git_inspect", toolChunk.getToolCalls().get(0).getName()); //$NON-NLS-1$
            assertEquals("{\"operation\":\"status\"}", toolChunk.getToolCalls().get(0).getArguments()); //$NON-NLS-1$

            LlmStreamChunk completeChunk = chunks.get(chunks.size() - 1);
            assertTrue(completeChunk.isComplete());
            assertEquals(LlmResponse.FINISH_REASON_TOOL_USE, completeChunk.getFinishReason());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void glm5ToolRequestsUseNonStreamingExecutionPlan() throws Exception {
        String nonStreamBody = "{"
                + "\"choices\":[{"
                + "\"message\":{"
                + "\"role\":\"assistant\","
                + "\"content\":null,"
                + "\"tool_calls\":[{"
                + "\"id\":\"call_glm\","
                + "\"type\":\"function\","
                + "\"function\":{"
                + "\"name\":\"git_inspect\","
                + "\"arguments\":\"{\\\"operation\\\":\\\"status\\\"}\""
                + "}"
                + "}]"
                + "},"
                + "\"finish_reason\":\"tool_calls\""
                + "}]"
                + "}";

        RecordingDualModeHandler handler = new RecordingDualModeHandler("data: [DONE]\n", nonStreamBody); //$NON-NLS-1$
        HttpServer server = startServer(handler);
        try {
            DynamicLlmProvider provider = createProvider(server, "glm-5"); //$NON-NLS-1$
            List<LlmStreamChunk> chunks = new ArrayList<>();

            provider.streamComplete(createToolRequest(), chunks::add);

            assertEquals(1, handler.getRequestBodies().size());
            String requestBody = handler.getRequestBodies().get(0);
            assertTrue(requestBody.contains("\"stream\":false")); //$NON-NLS-1$

            LlmStreamChunk toolChunk = findToolChunk(chunks);
            assertNotNull(toolChunk);
            assertEquals("git_inspect", toolChunk.getToolCalls().get(0).getName()); //$NON-NLS-1$
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void kimiToolRequestsSetEnableThinkingFalse() throws Exception {
        RecordingDualModeHandler handler = new RecordingDualModeHandler("data: [DONE]\n", nonStreamingTextResponse("ok")); //$NON-NLS-1$ //$NON-NLS-2$
        HttpServer server = startServer(handler);
        try {
            DynamicLlmProvider provider = createProvider(server, "kimi-k2.5"); //$NON-NLS-1$

            provider.complete(createToolRequest()).join();

            assertEquals(1, handler.getRequestBodies().size());
            String requestBody = handler.getRequestBodies().get(0);
            assertTrue(requestBody.contains("\"enable_thinking\":false")); //$NON-NLS-1$
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void roleMetadataChunksDoNotTriggerNonStreamingFallback() throws Exception {
        String streamBody = ""
                + "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"},\"finish_reason\":null}]}\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"git_inspect\",\"arguments\":\"\"}}]},\"finish_reason\":null}]}\n"
                + "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"operation\\\":\\\"status\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n"
                + "data: [DONE]\n";

        RecordingDualModeHandler handler = new RecordingDualModeHandler(streamBody, nonStreamingTextResponse("unused")); //$NON-NLS-1$
        HttpServer server = startServer(handler);
        try {
            DynamicLlmProvider provider = createProvider(server, "qwen3-coder-next"); //$NON-NLS-1$
            List<LlmStreamChunk> chunks = new ArrayList<>();

            provider.streamComplete(createToolRequest(), chunks::add);

            assertEquals(1, handler.getRequestBodies().size());
            assertTrue(handler.getRequestBodies().get(0).contains("\"stream\":true")); //$NON-NLS-1$
            assertNotNull(findToolChunk(chunks));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void kimiLargeToolResultFollowUpUsesNonStreamingPlan() throws Exception {
        RecordingDualModeHandler handler = new RecordingDualModeHandler("data: [DONE]\n", nonStreamingTextResponse("ok")); //$NON-NLS-1$ //$NON-NLS-2$
        HttpServer server = startServer(handler);
        try {
            DynamicLlmProvider provider = createProvider(server, "kimi-k2.5"); //$NON-NLS-1$
            List<LlmStreamChunk> chunks = new ArrayList<>();
            String largeToolResult = "X".repeat(80_000); //$NON-NLS-1$

            LlmRequest request = LlmRequest.builder()
                    .addMessage(LlmMessage.user("Проверь результат")) //$NON-NLS-1$
                    .addMessage(LlmMessage.toolResult("call_big", largeToolResult)) //$NON-NLS-1$
                    .addTool(ToolDefinition.builder()
                            .name("git_inspect") //$NON-NLS-1$
                            .description("Inspect git") //$NON-NLS-1$
                            .parametersSchema("{\"type\":\"object\"}") //$NON-NLS-1$
                            .build())
                    .stream(true)
                    .build();

            provider.streamComplete(request, chunks::add);

            assertEquals(1, handler.getRequestBodies().size());
            String requestBody = handler.getRequestBodies().get(0);
            assertTrue(requestBody.contains("\"stream\":false")); //$NON-NLS-1$
            assertTrue(requestBody.contains("\"enable_thinking\":false")); //$NON-NLS-1$
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void oversizedToolResultIsTruncatedBeforeSendingToProvider() throws Exception {
        RecordingDualModeHandler handler = new RecordingDualModeHandler("data: [DONE]\n", nonStreamingTextResponse("ok")); //$NON-NLS-1$ //$NON-NLS-2$
        HttpServer server = startServer(handler);
        try {
            DynamicLlmProvider provider = createProvider(server, "qwen3-coder-next"); //$NON-NLS-1$
            String largeToolResult = "Y".repeat(80_000); //$NON-NLS-1$

            provider.complete(LlmRequest.builder()
                    .addMessage(LlmMessage.user("Суммаризируй")) //$NON-NLS-1$
                    .addMessage(LlmMessage.toolResult("call_diag", largeToolResult)) //$NON-NLS-1$
                    .build()).join();

            assertEquals(1, handler.getRequestBodies().size());
            String requestBody = handler.getRequestBodies().get(0);
            assertTrue(requestBody.contains("[tool result truncated by CodePilot1C]")); //$NON-NLS-1$
            assertTrue(requestBody.contains("\"tool_call_id\":\"call_diag\"")); //$NON-NLS-1$
            assertFalse(requestBody.contains(largeToolResult));
        } finally {
            server.stop(0);
        }
    }

    private static DynamicLlmProvider createProvider(HttpServer server) {
        return createProvider(server, "test-model"); //$NON-NLS-1$
    }

    private static DynamicLlmProvider createProvider(HttpServer server, String model) {
        LlmProviderConfig config = new LlmProviderConfig();
        config.setName("test"); //$NON-NLS-1$
        config.setType(ProviderType.OPENAI_COMPATIBLE);
        config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"); //$NON-NLS-1$ //$NON-NLS-2$
        config.setApiKey("test-key"); //$NON-NLS-1$
        config.setModel(model);
        config.setStreamingEnabled(true);
        return new DynamicLlmProvider(config);
    }

    private static LlmRequest createToolRequest() {
        return LlmRequest.builder()
                .addMessage(LlmMessage.user("Проверь git")) //$NON-NLS-1$
                .addTool(ToolDefinition.builder()
                        .name("git_inspect") //$NON-NLS-1$
                        .description("Inspect git") //$NON-NLS-1$
                        .parametersSchema("{\"type\":\"object\"}") //$NON-NLS-1$
                        .build())
                .stream(true)
                .build();
    }

    private static LlmRequest createPlainRequest() {
        return LlmRequest.builder()
                .addMessage(LlmMessage.user("Привет")) //$NON-NLS-1$
                .stream(true)
                .build();
    }

    private static LlmStreamChunk findToolChunk(List<LlmStreamChunk> chunks) {
        for (LlmStreamChunk chunk : chunks) {
            if (chunk.hasToolCalls()) {
                return chunk;
            }
        }
        return null;
    }

    private static HttpServer startServer(HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", handler); //$NON-NLS-1$
        server.start();
        return server;
    }

    private static String nonStreamingTextResponse(String content) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\"},\"finish_reason\":\"stop\"}]}"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static final class DualModeHandler implements HttpHandler {
        private final byte[] streamingBody;
        private final byte[] nonStreamingBody;

        private DualModeHandler(String streamingBody, String nonStreamingBody) {
            this.streamingBody = streamingBody.getBytes(StandardCharsets.UTF_8);
            this.nonStreamingBody = nonStreamingBody.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] requestBytes = exchange.getRequestBody().readAllBytes();
            String requestBody = new String(requestBytes, StandardCharsets.UTF_8);
            boolean streaming = requestBody.contains("\"stream\":true"); //$NON-NLS-1$
            byte[] response = streaming ? streamingBody : nonStreamingBody;
            exchange.getResponseHeaders().add("Content-Type", streaming ? "text/event-stream" : "application/json"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(response);
            }
        }
    }

    private static final class RecordingDualModeHandler implements HttpHandler {
        private final byte[] streamingBody;
        private final byte[] nonStreamingBody;
        private final List<String> requestBodies = new CopyOnWriteArrayList<>();

        private RecordingDualModeHandler(String streamingBody, String nonStreamingBody) {
            this.streamingBody = streamingBody.getBytes(StandardCharsets.UTF_8);
            this.nonStreamingBody = nonStreamingBody.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] requestBytes = exchange.getRequestBody().readAllBytes();
            String requestBody = new String(requestBytes, StandardCharsets.UTF_8);
            requestBodies.add(requestBody);
            boolean streaming = requestBody.contains("\"stream\":true"); //$NON-NLS-1$
            byte[] response = streaming ? streamingBody : nonStreamingBody;
            exchange.getResponseHeaders().add("Content-Type", streaming ? "text/event-stream" : "application/json"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(response);
            }
        }

        private List<String> getRequestBodies() {
            return requestBodies;
        }
    }
}
