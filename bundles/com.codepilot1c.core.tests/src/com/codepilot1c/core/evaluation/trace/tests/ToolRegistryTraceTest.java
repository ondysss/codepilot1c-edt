package com.codepilot1c.core.evaluation.trace.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.Test;

import com.codepilot1c.core.agent.AgentConfig;
import com.codepilot1c.core.evaluation.trace.AgentTraceSession;
import com.codepilot1c.core.model.ToolCall;
import com.codepilot1c.core.provider.ILlmProvider;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonObject;

public class ToolRegistryTraceTest extends AbstractTraceTest {

    @Test
    public void executeWritesSuccessTrace() throws Exception {
        ToolRegistry registry = ToolRegistryTestSupport.createIsolatedRegistry();
        String toolName = "trace_test_tool_success";
        registry.registerDynamicTool(new FixedResultTool(toolName, ToolResult.success("ok"), false));

        try {
            AgentTraceSession session = AgentTraceSession.startAgentRun(
                    AgentConfig.builder().profileName("explore").build(),
                    new NoopProvider(), "run tool", "system");
            ToolResult result = registry.execute(new ToolCall("call-1", toolName, "{\"value\":42}"),
                    session, "step-1").join();

            assertTrue(result.isSuccess());

            List<JsonObject> toolEvents = readJsonLines(session.getLayout().getToolsFile());
            assertEquals(2, toolEvents.size());
            assertEquals("TOOL_CALL", toolEvents.get(0).get("type").getAsString());
            assertEquals("step-1", toolEvents.get(0).get("parentEventId").getAsString());
            assertEquals("TOOL_RESULT", toolEvents.get(1).get("type").getAsString());
            assertEquals(toolEvents.get(0).get("eventId").getAsString(),
                    toolEvents.get(1).get("parentEventId").getAsString());
            assertEquals("42.0", toolEvents.get(0).getAsJsonObject("data")
                    .getAsJsonObject("parsed_arguments").get("value").getAsString());
            assertTrue(toolEvents.get(1).getAsJsonObject("data").get("success").getAsBoolean());
        } finally {
            registry.unregisterDynamicTool(toolName);
        }
    }

    @Test
    public void executeWritesFailureTraceOnException() throws Exception {
        ToolRegistry registry = ToolRegistryTestSupport.createIsolatedRegistry();
        String toolName = "trace_test_tool_failure";
        registry.registerDynamicTool(new FixedResultTool(toolName, null, true));

        try {
            AgentTraceSession session = AgentTraceSession.startAgentRun(
                    AgentConfig.builder().profileName("build").build(),
                    new NoopProvider(), "run tool", "system");
            ToolResult result = registry.execute(new ToolCall("call-2", toolName, "{}"),
                    session, "step-2").join();

            assertFalse(result.isSuccess());
            assertTrue(result.getErrorMessage().contains("Exception:"));

            List<JsonObject> toolEvents = readJsonLines(session.getLayout().getToolsFile());
            assertEquals(2, toolEvents.size());
            JsonObject resultEvent = toolEvents.get(1).getAsJsonObject("data");
            assertEquals("CompletionException", resultEvent.get("exception_type").getAsString());
            assertTrue(resultEvent.get("exception_message").getAsString().contains("boom"));
        } finally {
            registry.unregisterDynamicTool(toolName);
        }
    }

    private static final class FixedResultTool implements ITool {

        private final String name;
        private final ToolResult result;
        private final boolean failWithException;

        private FixedResultTool(String name, ToolResult result, boolean failWithException) {
            this.name = name;
            this.result = result;
            this.failWithException = failWithException;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "Trace test tool";
        }

        @Override
        public String getParameterSchema() {
            return "{\"type\":\"object\"}";
        }

        @Override
        public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            if (failWithException) {
                CompletableFuture<ToolResult> future = new CompletableFuture<>();
                future.completeExceptionally(new RuntimeException("boom"));
                return future;
            }
            return CompletableFuture.completedFuture(result);
        }
    }

    private static final class NoopProvider implements ILlmProvider {

        @Override
        public String getId() {
            return "noop";
        }

        @Override
        public String getDisplayName() {
            return "Noop";
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            return false;
        }

        @Override
        public CompletableFuture<com.codepilot1c.core.model.LlmResponse> complete(
                com.codepilot1c.core.model.LlmRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void streamComplete(com.codepilot1c.core.model.LlmRequest request,
                java.util.function.Consumer<com.codepilot1c.core.model.LlmStreamChunk> consumer) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancel() {
        }

        @Override
        public void dispose() {
        }
    }
}
