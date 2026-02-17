package com.codepilot1c.core.mcp.host;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.mcp.host.prompt.IMcpPromptProvider;
import com.codepilot1c.core.mcp.host.resource.IMcpResourceProvider;
import com.codepilot1c.core.mcp.host.session.McpHostSession;
import com.codepilot1c.core.mcp.model.McpContent;
import com.codepilot1c.core.mcp.model.McpError;
import com.codepilot1c.core.mcp.model.McpMessage;
import com.codepilot1c.core.mcp.model.McpPromptResult;
import com.codepilot1c.core.mcp.model.McpResource;
import com.codepilot1c.core.mcp.model.McpResourceContent;
import com.codepilot1c.core.permissions.PermissionDecision;
import com.codepilot1c.core.permissions.PermissionManager;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.ToolResult;

/**
 * JSON-RPC method router for inbound MCP host requests.
 */
public class McpHostRequestRouter {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(McpHostRequestRouter.class);
    private static final String SERVER_NAME = "CodePilot1C MCP Host"; //$NON-NLS-1$
    private static final String SERVER_VERSION = "1.3.0"; //$NON-NLS-1$
    private static final List<String> SUPPORTED_PROTOCOLS = List.of(
        "2025-11-25", //$NON-NLS-1$
        "2025-06-18", //$NON-NLS-1$
        "2024-11-05" //$NON-NLS-1$
    );

    private final Gson gson = new Gson();
    private final McpToolExposurePolicy exposurePolicy;
    private final List<IMcpResourceProvider> resourceProviders;
    private final IMcpPromptProvider promptProvider;
    private final McpHostConfig.MutationPolicy defaultMutationPolicy;

    public McpHostRequestRouter(
            McpToolExposurePolicy exposurePolicy,
            List<IMcpResourceProvider> resourceProviders,
            IMcpPromptProvider promptProvider,
            McpHostConfig.MutationPolicy defaultMutationPolicy) {
        this.exposurePolicy = exposurePolicy;
        this.resourceProviders = resourceProviders;
        this.promptProvider = promptProvider;
        this.defaultMutationPolicy = defaultMutationPolicy != null
            ? defaultMutationPolicy
            : McpHostConfig.MutationPolicy.ALLOW;
    }

    public McpMessage route(McpMessage request, McpHostSession session) {
        if (request == null || request.getMethod() == null) {
            return error(request, -32600, "Invalid request"); //$NON-NLS-1$
        }

        try {
            return switch (request.getMethod()) {
                case "initialize" -> handleInitialize(request, session); //$NON-NLS-1$
                case "notifications/initialized" -> handleInitialized(request, session); //$NON-NLS-1$
                case "tools/list" -> ok(request, Map.of("tools", listTools())); //$NON-NLS-1$ //$NON-NLS-2$
                case "tools/call" -> handleToolCall(request, session); //$NON-NLS-1$
                case "resources/list" -> ok(request, Map.of("resources", listResources(session))); //$NON-NLS-1$ //$NON-NLS-2$
                case "resources/read" -> handleResourceRead(request, session); //$NON-NLS-1$
                case "prompts/list" -> ok(request, Map.of("prompts", promptProvider.listPrompts())); //$NON-NLS-1$ //$NON-NLS-2$
                case "prompts/get" -> handlePromptGet(request); //$NON-NLS-1$
                case "ping" -> ok(request, Map.of("ok", true)); //$NON-NLS-1$ //$NON-NLS-2$
                case "shutdown" -> ok(request, Map.of()); //$NON-NLS-1$
                default -> error(request, -32601, "Method not found: " + request.getMethod()); //$NON-NLS-1$
            };
        } catch (Exception e) {
            LOG.error("MCP host request handling error", e); //$NON-NLS-1$
            return error(request, -32603, e.getMessage() != null ? e.getMessage() : "Internal error"); //$NON-NLS-1$
        }
    }

    private McpMessage handleInitialize(McpMessage request, McpHostSession session) {
        Map<String, Object> params = asMap(request.getParams());
        String requestedProtocol = string(params.get("protocolVersion")); //$NON-NLS-1$
        String negotiated = negotiateProtocol(requestedProtocol);

        Map<String, Object> clientInfo = asMap(params.get("clientInfo")); //$NON-NLS-1$
        session.setClientName(string(clientInfo.get("name"))); //$NON-NLS-1$
        session.setClientVersion(string(clientInfo.get("version"))); //$NON-NLS-1$
        session.setProtocolVersion(negotiated);

        Map<String, Object> result = new HashMap<>();
        result.put("protocolVersion", negotiated); //$NON-NLS-1$
        result.put("capabilities", Map.of( //$NON-NLS-1$
            "tools", Map.of("listChanged", true), //$NON-NLS-1$ //$NON-NLS-2$
            "resources", Map.of("listChanged", true), //$NON-NLS-1$ //$NON-NLS-2$
            "prompts", Map.of("listChanged", true), //$NON-NLS-1$ //$NON-NLS-2$
            "logging", Map.of() //$NON-NLS-1$
        ));
        result.put("serverInfo", Map.of("name", SERVER_NAME, "version", SERVER_VERSION)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return ok(request, result);
    }

    private McpMessage handleInitialized(McpMessage request, McpHostSession session) {
        session.setInitialized(true);
        return notificationAck(request);
    }

    private McpMessage handleToolCall(McpMessage request, McpHostSession session) {
        Map<String, Object> params = asMap(request.getParams());
        String toolName = string(params.get("name")); //$NON-NLS-1$
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = params.get("arguments") instanceof Map<?, ?> m //$NON-NLS-1$
            ? (Map<String, Object>) m
            : Map.of();

        if (toolName == null || toolName.isBlank()) {
            return error(request, -32602, "Missing required parameter: name"); //$NON-NLS-1$
        }

        if (!exposurePolicy.isExposed(toolName)) {
            return ok(request, toolError("Tool is not exposed: " + toolName)); //$NON-NLS-1$
        }

        ITool tool = ToolRegistry.getInstance().getTool(toolName);
        if (tool == null) {
            return ok(request, toolError("Unknown tool: " + toolName)); //$NON-NLS-1$
        }

        Instant startedAt = Instant.now();
        PermissionDecision decision = resolvePermissionDecision(toolName, arguments);

        if (decision == PermissionDecision.DENY || decision == PermissionDecision.ASK) {
            return ok(request, toolError("Tool execution denied by permission policy: " + decision)); //$NON-NLS-1$
        }

        ToolResult toolResult;
        try {
            toolResult = tool.execute(arguments)
                .orTimeout(120, TimeUnit.SECONDS)
                .join();
        } catch (Exception e) {
            return ok(request, toolError("Tool execution failed: " + e.getMessage())); //$NON-NLS-1$
        }

        Duration duration = Duration.between(startedAt, Instant.now());
        LOG.info("MCP host tool call client=%s tool=%s decision=%s success=%s durationMs=%d", //$NON-NLS-1$
            session.getClientName(), toolName, decision, Boolean.valueOf(toolResult.isSuccess()), Long.valueOf(duration.toMillis()));

        return ok(request, toMcpToolResult(toolResult));
    }

    private PermissionDecision resolvePermissionDecision(String toolName, Map<String, Object> arguments) {
        return switch (defaultMutationPolicy) {
            case ALLOW -> PermissionDecision.ALLOW;
            case DENY -> PermissionDecision.DENY;
            case ASK -> PermissionManager.getInstance()
                .check(toolName, "mcp_host_call", arguments) //$NON-NLS-1$
                .orTimeout(5, TimeUnit.SECONDS)
                .exceptionally(e -> PermissionDecision.DENY)
                .join();
        };
    }

    private McpMessage handleResourceRead(McpMessage request, McpHostSession session) {
        Map<String, Object> params = asMap(request.getParams());
        String uri = string(params.get("uri")); //$NON-NLS-1$
        if (uri == null || uri.isBlank()) {
            return error(request, -32602, "Missing required parameter: uri"); //$NON-NLS-1$
        }
        for (IMcpResourceProvider provider : resourceProviders) {
            Optional<McpResourceContent> content = provider.readResource(uri, session);
            if (content.isPresent()) {
                return ok(request, content.get());
            }
        }
        return error(request, -32602, "Unknown resource URI: " + uri); //$NON-NLS-1$
    }

    private McpMessage handlePromptGet(McpMessage request) {
        Map<String, Object> params = asMap(request.getParams());
        String name = string(params.get("name")); //$NON-NLS-1$
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = params.get("arguments") instanceof Map<?, ?> m //$NON-NLS-1$
            ? (Map<String, Object>) m
            : Map.of();
        if (name == null || name.isBlank()) {
            return error(request, -32602, "Missing required parameter: name"); //$NON-NLS-1$
        }
        Optional<McpPromptResult> result = promptProvider.getPrompt(name, arguments);
        return result.map(r -> ok(request, r))
            .orElseGet(() -> error(request, -32602, "Unknown prompt: " + name)); //$NON-NLS-1$
    }

    public Map<String, Object> capabilitiesSnapshot() {
        return Map.of(
            "tools", Map.of("listChanged", true), //$NON-NLS-1$ //$NON-NLS-2$
            "resources", Map.of("listChanged", true), //$NON-NLS-1$ //$NON-NLS-2$
            "prompts", Map.of("listChanged", true) //$NON-NLS-1$ //$NON-NLS-2$
        );
    }

    private List<Map<String, Object>> listTools() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ITool tool : ToolRegistry.getInstance().getAllTools()) {
            if (!exposurePolicy.isExposed(tool.getName())) {
                continue;
            }
            Map<String, Object> item = new HashMap<>();
            item.put("name", tool.getName()); //$NON-NLS-1$
            item.put("description", tool.getDescription()); //$NON-NLS-1$
            item.put("inputSchema", parseSchema(tool.getParameterSchema())); //$NON-NLS-1$
            out.add(item);
        }
        return out;
    }

    private List<McpResource> listResources(McpHostSession session) {
        List<McpResource> out = new ArrayList<>();
        for (IMcpResourceProvider provider : resourceProviders) {
            out.addAll(provider.listResources(session));
        }
        return out;
    }

    private Map<String, Object> toMcpToolResult(ToolResult result) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("isError", Boolean.valueOf(!result.isSuccess())); //$NON-NLS-1$
        payload.put("content", List.of(McpContent.text(result.getContentForLlm()))); //$NON-NLS-1$
        return payload;
    }

    private Map<String, Object> toolError(String message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("isError", Boolean.TRUE); //$NON-NLS-1$
        payload.put("content", List.of(McpContent.text(message))); //$NON-NLS-1$
        return payload;
    }

    private String negotiateProtocol(String requested) {
        if (requested != null && SUPPORTED_PROTOCOLS.contains(requested)) {
            return requested;
        }
        return SUPPORTED_PROTOCOLS.get(0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private String string(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private Object parseSchema(String schema) {
        if (schema == null || schema.isBlank()) {
            return Map.of("type", "object", "properties", Map.of()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }
        try {
            JsonElement parsed = JsonParser.parseString(schema);
            return gson.fromJson(parsed, Object.class);
        } catch (Exception e) {
            JsonObject fallback = new JsonObject();
            fallback.addProperty("type", "object"); //$NON-NLS-1$ //$NON-NLS-2$
            fallback.addProperty("description", "Schema parse error: " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            return gson.fromJson(fallback, Object.class);
        }
    }

    private McpMessage ok(McpMessage request, Object result) {
        McpMessage response = new McpMessage();
        response.setRawId(request.getRawId());
        response.setResult(result);
        return response;
    }

    private McpMessage notificationAck(McpMessage request) {
        if (request.getRawId() == null) {
            return new McpMessage();
        }
        return ok(request, Map.of());
    }

    private McpMessage error(McpMessage request, int code, String message) {
        McpMessage response = new McpMessage();
        if (request != null) {
            response.setRawId(request.getRawId());
        }
        response.setError(new McpError(code, message, null));
        return response;
    }
}
