/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp.client;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.mcp.model.McpMessage;
import com.codepilot1c.core.mcp.model.McpPrompt;
import com.codepilot1c.core.mcp.model.McpPromptResult;
import com.codepilot1c.core.mcp.model.McpResource;
import com.codepilot1c.core.mcp.model.McpResourceContent;
import com.codepilot1c.core.mcp.model.McpServerCapabilities;
import com.codepilot1c.core.mcp.model.McpTool;
import com.codepilot1c.core.mcp.model.McpToolResult;
import com.codepilot1c.core.mcp.transport.IMcpTransport;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

/**
 * High-level MCP client for communicating with MCP servers.
 *
 * <p>This client handles the MCP protocol handshake and provides
 * methods for discovering and invoking tools, resources, and prompts.</p>
 */
public class McpClient implements AutoCloseable {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(McpClient.class);
    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final String CLIENT_NAME = "1C Copilot";
    private static final String CLIENT_VERSION = "1.3.0";

    private final IMcpTransport transport;
    private final String serverName;
    private McpServerCapabilities serverCapabilities;
    private List<McpTool> tools = new ArrayList<>();
    private List<McpResource> resources = new ArrayList<>();
    private List<McpPrompt> prompts = new ArrayList<>();

    private final Gson gson = new Gson();

    // Listener for tools list changes (e.g., for re-registering in ToolRegistry)
    private java.util.function.Consumer<List<McpTool>> toolsChangedListener;

    /**
     * Creates a new MCP client.
     *
     * @param serverName the server display name
     * @param transport the transport to use
     */
    public McpClient(String serverName, IMcpTransport transport) {
        this.serverName = serverName;
        this.transport = transport;
        transport.setNotificationHandler(this::handleNotification);
    }

    /**
     * Initializes the connection and discovers server capabilities.
     *
     * @return a future that completes when initialization is done
     */
    public CompletableFuture<Void> initialize() {
        // Don't advertise capabilities we don't support (roots, sampling)
        // This avoids server->client requests that we can't handle
        return transport.send(createRequest("initialize", Map.of(
            "protocolVersion", PROTOCOL_VERSION,
            "capabilities", Map.of(),
            "clientInfo", Map.of(
                "name", CLIENT_NAME,
                "version", CLIENT_VERSION
            )
        ))).thenCompose(response -> {
            this.serverCapabilities = McpServerCapabilities.fromInitializeResult(response.getResult());
            LOG.info("MCP server '%s' initialized. Server capabilities: tools=%b, resources=%b, prompts=%b",
                serverName,
                serverCapabilities.supportsTools(),
                serverCapabilities.supportsResources(),
                serverCapabilities.supportsPrompts());

            // Send initialized notification (required by protocol)
            sendNotification("notifications/initialized", null);

            // Discover tools, resources, prompts based on server capabilities
            return discoverCapabilities();
        });
    }

    private void handleNotification(McpMessage notification) {
        String method = notification.getMethod();
        LOG.debug("Received notification: %s", method);

        // Handle list change notifications
        if ("notifications/tools/list_changed".equals(method)) {
            LOG.info("Tools list changed notification from %s, refreshing...", serverName);
            listTools().thenAccept(newTools -> {
                this.tools = newTools;
                LOG.info("Refreshed tools for %s: %d tools", serverName, newTools.size());
                if (toolsChangedListener != null) {
                    toolsChangedListener.accept(newTools);
                }
            }).exceptionally(e -> {
                LOG.warn("Failed to refresh tools for %s: %s", serverName, e.getMessage());
                return null;
            });
        } else if ("notifications/resources/list_changed".equals(method)) {
            LOG.info("Resources list changed notification from %s, refreshing...", serverName);
            listResources().thenAccept(newResources -> {
                this.resources = newResources;
                LOG.info("Refreshed resources for %s: %d resources", serverName, newResources.size());
            }).exceptionally(e -> {
                LOG.warn("Failed to refresh resources for %s: %s", serverName, e.getMessage());
                return null;
            });
        } else if ("notifications/prompts/list_changed".equals(method)) {
            LOG.info("Prompts list changed notification from %s, refreshing...", serverName);
            listPrompts().thenAccept(newPrompts -> {
                this.prompts = newPrompts;
                LOG.info("Refreshed prompts for %s: %d prompts", serverName, newPrompts.size());
            }).exceptionally(e -> {
                LOG.warn("Failed to refresh prompts for %s: %s", serverName, e.getMessage());
                return null;
            });
        }
    }

    /**
     * Sets a listener for tools list changes.
     * The listener is called when the server sends a tools/list_changed notification.
     *
     * @param listener the listener to set
     */
    public void setToolsChangedListener(java.util.function.Consumer<List<McpTool>> listener) {
        this.toolsChangedListener = listener;
    }

    private CompletableFuture<Void> discoverCapabilities() {
        List<CompletableFuture<?>> futures = new ArrayList<>();

        if (serverCapabilities.supportsTools()) {
            futures.add(listTools().thenAccept(t -> this.tools = t));
        }
        if (serverCapabilities.supportsResources()) {
            futures.add(listResources().thenAccept(r -> this.resources = r));
        }
        if (serverCapabilities.supportsPrompts()) {
            futures.add(listPrompts().thenAccept(p -> this.prompts = p));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    /**
     * Lists available tools from the server.
     *
     * @return a future containing the tool list
     */
    public CompletableFuture<List<McpTool>> listTools() {
        return transport.send(createRequest("tools/list", null))
            .thenApply(response -> parseToolList(response.getResult()));
    }

    /**
     * Executes a tool on the server.
     *
     * @param toolName the tool name
     * @param arguments the tool arguments
     * @return a future containing the result
     */
    public CompletableFuture<McpToolResult> callTool(String toolName, Map<String, Object> arguments) {
        return transport.send(createRequest("tools/call", Map.of(
            "name", toolName,
            "arguments", arguments != null ? arguments : Map.of()
        ))).thenApply(response -> parseToolResult(response.getResult()));
    }

    /**
     * Lists available resources from the server.
     *
     * @return a future containing the resource list
     */
    public CompletableFuture<List<McpResource>> listResources() {
        return transport.send(createRequest("resources/list", null))
            .thenApply(response -> parseResourceList(response.getResult()));
    }

    /**
     * Reads a resource by URI.
     *
     * @param uri the resource URI
     * @return a future containing the resource content
     */
    public CompletableFuture<McpResourceContent> readResource(String uri) {
        return transport.send(createRequest("resources/read", Map.of("uri", uri)))
            .thenApply(response -> parseResourceContent(response.getResult()));
    }

    /**
     * Lists available prompts from the server.
     *
     * @return a future containing the prompt list
     */
    public CompletableFuture<List<McpPrompt>> listPrompts() {
        return transport.send(createRequest("prompts/list", null))
            .thenApply(response -> parsePromptList(response.getResult()));
    }

    /**
     * Gets a prompt with arguments.
     *
     * @param name the prompt name
     * @param arguments the prompt arguments
     * @return a future containing the prompt result
     */
    public CompletableFuture<McpPromptResult> getPrompt(String name, Map<String, String> arguments) {
        return transport.send(createRequest("prompts/get", Map.of(
            "name", name,
            "arguments", arguments != null ? arguments : Map.of()
        ))).thenApply(response -> parsePromptResult(response.getResult()));
    }

    /**
     * Returns the server name.
     *
     * @return the server name
     */
    public String getServerName() {
        return serverName;
    }

    /**
     * Returns the discovered tools.
     *
     * @return unmodifiable list of tools
     */
    public List<McpTool> getTools() {
        return Collections.unmodifiableList(tools);
    }

    /**
     * Returns the discovered resources.
     *
     * @return unmodifiable list of resources
     */
    public List<McpResource> getResources() {
        return Collections.unmodifiableList(resources);
    }

    /**
     * Returns the discovered prompts.
     *
     * @return unmodifiable list of prompts
     */
    public List<McpPrompt> getPrompts() {
        return Collections.unmodifiableList(prompts);
    }

    /**
     * Returns whether the client is connected.
     *
     * @return true if connected
     */
    public boolean isConnected() {
        return transport.isConnected();
    }

    /**
     * Returns the server capabilities.
     *
     * @return the capabilities
     */
    public McpServerCapabilities getCapabilities() {
        return serverCapabilities;
    }

    @Override
    public void close() {
        try {
            transport.close();
        } catch (Exception e) {
            LOG.warn("Error closing MCP transport", e);
        }
    }

    private McpMessage createRequest(String method, Object params) {
        McpMessage msg = new McpMessage();
        msg.setMethod(method);
        msg.setParams(params);
        return msg;
    }

    private void sendNotification(String method, Object params) {
        McpMessage msg = new McpMessage();
        msg.setMethod(method);
        msg.setParams(params);
        // No id for notifications - use sendNotification method
        transport.sendNotification(msg).exceptionally(e -> {
            LOG.warn("Failed to send notification %s: %s", method, e.getMessage());
            return null;
        });
    }

    // Parsing helpers

    /**
     * Converts a result object to JsonObject.
     * Gson deserializes objects as LinkedTreeMap, not JsonObject.
     * This method handles both cases.
     */
    private JsonObject toJsonObject(Object result) {
        if (result == null) {
            return null;
        }
        if (result instanceof JsonObject) {
            return (JsonObject) result;
        }
        // Gson deserializes to LinkedTreeMap, convert via toJsonTree
        JsonElement element = gson.toJsonTree(result);
        if (element.isJsonObject()) {
            return element.getAsJsonObject();
        }
        return null;
    }

    private List<McpTool> parseToolList(Object result) {
        List<McpTool> tools = new ArrayList<>();
        JsonObject obj = toJsonObject(result);
        if (obj != null && obj.has("tools")) {
            JsonArray arr = obj.getAsJsonArray("tools");
            for (JsonElement elem : arr) {
                tools.add(gson.fromJson(elem, McpTool.class));
            }
            LOG.debug("Parsed %d tools from server %s", tools.size(), serverName);
        }
        return tools;
    }

    private McpToolResult parseToolResult(Object result) {
        JsonObject obj = toJsonObject(result);
        if (obj != null) {
            McpToolResult parsed = gson.fromJson(obj, McpToolResult.class);
            // Compatibility: if server returns only structuredContent and no content array.
            if (parsed.getContent().isEmpty() && obj.has("structuredContent")) { //$NON-NLS-1$
                parsed.setContent(List.of(com.codepilot1c.core.mcp.model.McpContent
                        .text(gson.toJson(obj.get("structuredContent"))))); //$NON-NLS-1$
            }
            return parsed;
        }
        return new McpToolResult();
    }

    private List<McpResource> parseResourceList(Object result) {
        List<McpResource> resources = new ArrayList<>();
        JsonObject obj = toJsonObject(result);
        if (obj != null && obj.has("resources")) {
            JsonArray arr = obj.getAsJsonArray("resources");
            for (JsonElement elem : arr) {
                resources.add(gson.fromJson(elem, McpResource.class));
            }
            LOG.debug("Parsed %d resources from server %s", resources.size(), serverName);
        }
        return resources;
    }

    private McpResourceContent parseResourceContent(Object result) {
        JsonObject obj = toJsonObject(result);
        if (obj != null) {
            return gson.fromJson(obj, McpResourceContent.class);
        }
        return new McpResourceContent();
    }

    private List<McpPrompt> parsePromptList(Object result) {
        List<McpPrompt> prompts = new ArrayList<>();
        JsonObject obj = toJsonObject(result);
        if (obj != null && obj.has("prompts")) {
            JsonArray arr = obj.getAsJsonArray("prompts");
            for (JsonElement elem : arr) {
                prompts.add(gson.fromJson(elem, McpPrompt.class));
            }
            LOG.debug("Parsed %d prompts from server %s", prompts.size(), serverName);
        }
        return prompts;
    }

    private McpPromptResult parsePromptResult(Object result) {
        JsonObject obj = toJsonObject(result);
        if (obj != null) {
            return gson.fromJson(obj, McpPromptResult.class);
        }
        return new McpPromptResult();
    }
}
