package com.codepilot1c.core.evaluation.trace.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.junit.Test;

import com.codepilot1c.core.evaluation.trace.AgentTraceSession;
import com.codepilot1c.core.mcp.host.McpHostConfig;
import com.codepilot1c.core.mcp.host.McpHostRequestRouter;
import com.codepilot1c.core.mcp.host.McpToolExposurePolicy;
import com.codepilot1c.core.mcp.host.prompt.IMcpPromptProvider;
import com.codepilot1c.core.mcp.host.resource.IMcpResourceProvider;
import com.codepilot1c.core.mcp.host.session.McpHostSession;
import com.codepilot1c.core.mcp.model.McpContent;
import com.codepilot1c.core.mcp.model.McpMessage;
import com.codepilot1c.core.mcp.model.McpPrompt;
import com.codepilot1c.core.mcp.model.McpPromptArgument;
import com.codepilot1c.core.mcp.model.McpPromptResult;
import com.codepilot1c.core.mcp.model.McpResource;
import com.codepilot1c.core.mcp.model.McpResourceContent;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonObject;

public class McpHostRequestRouterTraceTest extends AbstractTraceTest {

    @Test
    public void routeWritesRequestResponseAndToolTrace() throws Exception {
        ToolRegistry registry = ToolRegistryTestSupport.createIsolatedRegistry();
        ToolRegistry previousRegistry = ToolRegistryTestSupport.installSingleton(registry);
        String toolName = "mcp_trace_test_tool";
        registry.registerDynamicTool(new StaticTool(toolName, ToolResult.success("tool-ok")));

        try {
            McpHostRequestRouter router = new McpHostRequestRouter(
                    new AllowAllExposurePolicy(),
                    List.of(new StaticResourceProvider()),
                    new StaticPromptProvider(),
                    McpHostConfig.MutationPolicy.ALLOW);
            McpHostSession session = new McpHostSession("session-1");
            session.setTransport("http");
            session.setRemoteAddress("127.0.0.1");
            session.setLastRequestPath("/mcp");
            session.setTraceSession(AgentTraceSession.startMcpSession("session-1", "http",
                    "127.0.0.1", "/mcp"));

            McpMessage initialize = request("initialize", Map.of(
                    "protocolVersion", "2025-06-18",
                    "clientInfo", Map.of("name", "trace-client", "version", "1.0")));
            assertNotNull(router.route(initialize, session).getResult());

            session.incrementRequestCount();
            McpMessage toolsList = request("tools/list", Map.of());
            assertNotNull(router.route(toolsList, session).getResult());

            session.incrementRequestCount();
            McpMessage toolCall = request("tools/call", Map.of(
                    "name", toolName,
                    "arguments", Map.of("value", "x")));
            McpMessage toolResponse = router.route(toolCall, session);
            assertNotNull(toolResponse.getResult());
            assertFalse(toolResponse.isErrorResponse());

            session.incrementRequestCount();
            McpMessage resourceRead = request("resources/read", Map.of("uri", "resource://trace"));
            assertNotNull(router.route(resourceRead, session).getResult());

            session.incrementRequestCount();
            McpMessage promptGet = request("prompts/get", Map.of(
                    "name", "trace-prompt",
                    "arguments", Map.of("topic", "trace")));
            assertNotNull(router.route(promptGet, session).getResult());

            List<JsonObject> mcpEvents = readJsonLines(session.getTraceSession().getLayout().getMcpFile());
            assertTrue(mcpEvents.size() >= 9);
            assertEquals("MCP_REQUEST", mcpEvents.get(0).get("type").getAsString());
            assertEquals("initialize", mcpEvents.get(0).getAsJsonObject("data").get("method").getAsString());

            boolean foundToolTrace = mcpEvents.stream()
                    .map(event -> event.getAsJsonObject("data"))
                    .anyMatch(data -> data.has("tool_name")
                            && toolName.equals(data.get("tool_name").getAsString())
                            && "ALLOW".equals(data.get("permission_decision").getAsString()));
            assertTrue(foundToolTrace);

            boolean foundPromptTrace = mcpEvents.stream()
                    .map(event -> event.getAsJsonObject("data"))
                    .anyMatch(data -> data.has("method")
                            && "prompts/get".equals(data.get("method").getAsString()));
            assertTrue(foundPromptTrace);
        } finally {
            registry.unregisterDynamicTool(toolName);
            ToolRegistryTestSupport.installSingleton(previousRegistry);
        }
    }

    private McpMessage request(String method, Object params) {
        McpMessage message = new McpMessage();
        message.setMethod(method);
        message.setRawId(method + "-id");
        message.setParams(params);
        return message;
    }

    private static final class StaticTool implements ITool {

        private final String name;
        private final ToolResult result;

        private StaticTool(String name, ToolResult result) {
            this.name = name;
            this.result = result;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "MCP trace tool";
        }

        @Override
        public String getParameterSchema() {
            return "{\"type\":\"object\"}";
        }

        @Override
        public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            return CompletableFuture.completedFuture(result);
        }
    }

    private static final class AllowAllExposurePolicy implements McpToolExposurePolicy {

        @Override
        public boolean isExposed(String toolName) {
            return true;
        }

        @Override
        public boolean requiresConfirmation(String toolName, Map<String, Object> args) {
            return false;
        }

        @Override
        public boolean isDestructive(String toolName) {
            return false;
        }
    }

    private static final class StaticResourceProvider implements IMcpResourceProvider {

        @Override
        public List<McpResource> listResources(McpHostSession session) {
            McpResource resource = new McpResource("resource://trace", "Trace Resource");
            resource.setMimeType("text/plain");
            resource.setDescription("Trace test resource");
            return List.of(resource);
        }

        @Override
        public Optional<McpResourceContent> readResource(String uri, McpHostSession session) {
            if (!"resource://trace".equals(uri)) {
                return Optional.empty();
            }
            McpResourceContent.ResourceContentItem item = new McpResourceContent.ResourceContentItem();
            item.setUri("resource://trace");
            item.setMimeType("text/plain");
            item.setText("resource-ok");
            McpResourceContent content = new McpResourceContent();
            content.setContents(List.of(item));
            return Optional.of(content);
        }
    }

    private static final class StaticPromptProvider implements IMcpPromptProvider {

        @Override
        public List<McpPrompt> listPrompts() {
            McpPrompt prompt = new McpPrompt("trace-prompt", "Trace prompt");
            prompt.setArguments(List.of(new McpPromptArgument("topic", "Topic", false)));
            return List.of(prompt);
        }

        @Override
        public Optional<McpPromptResult> getPrompt(String name, Map<String, Object> arguments) {
            if (!"trace-prompt".equals(name)) {
                return Optional.empty();
            }
            McpPromptResult.PromptMessage message = new McpPromptResult.PromptMessage();
            message.setRole("user");
            message.setContent(McpContent.text("topic=" + arguments.get("topic")));
            McpPromptResult result = new McpPromptResult();
            result.setDescription("Trace prompt result");
            result.setMessages(List.of(message));
            return Optional.of(result);
        }
    }
}
