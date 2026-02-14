/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.mcp.model.McpContent;
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

/**
 * High-level MCP client for communicating with MCP servers.
 */
public class McpClient implements AutoCloseable {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(McpClient.class);
    private static final String CLIENT_NAME = "1C Copilot"; //$NON-NLS-1$
    private static final String CLIENT_VERSION = "1.3.0"; //$NON-NLS-1$
    private static final List<String> DEFAULT_PROTOCOL_VERSIONS = List.of(
        "2025-11-25", //$NON-NLS-1$
        "2025-06-18", //$NON-NLS-1$
        "2024-11-05" //$NON-NLS-1$
    );

    private final IMcpTransport transport;
    private final String serverName;
    private final List<String> supportedProtocolVersions;
    private String preferredProtocolVersion;
    private String negotiatedProtocolVersion;
    private McpServerCapabilities serverCapabilities;
    private List<McpTool> tools = new ArrayList<>();
    private List<McpResource> resources = new ArrayList<>();
    private List<McpPrompt> prompts = new ArrayList<>();

    private final Gson gson = new Gson();

    // Listener for tools list changes (e.g., for re-registering in ToolRegistry)
    private java.util.function.Consumer<List<McpTool>> toolsChangedListener;
    private Function<McpMessage, CompletableFuture<McpMessage>> serverRequestHandler;

    /**
     * Creates a new MCP client with default protocol negotiation matrix.
     *
     * @param serverName the server display name
     * @param transport the transport to use
     */
    public McpClient(String serverName, IMcpTransport transport) {
        this(serverName, transport, DEFAULT_PROTOCOL_VERSIONS.get(0), DEFAULT_PROTOCOL_VERSIONS);
    }

    /**
     * Creates a new MCP client with explicit protocol preferences.
     *
     * @param serverName the server display name
     * @param transport the transport to use
     * @param preferredProtocolVersion preferred protocol version
     * @param supportedProtocolVersions supported protocol versions
     */
    public McpClient(String serverName, IMcpTransport transport,
                     String preferredProtocolVersion, List<String> supportedProtocolVersions) {
        this.serverName = serverName;
        this.transport = transport;
        this.preferredProtocolVersion = preferredProtocolVersion != null && !preferredProtocolVersion.isBlank()
            ? preferredProtocolVersion
            : DEFAULT_PROTOCOL_VERSIONS.get(0);
        this.supportedProtocolVersions = supportedProtocolVersions != null && !supportedProtocolVersions.isEmpty()
            ? new ArrayList<>(supportedProtocolVersions)
            : new ArrayList<>(DEFAULT_PROTOCOL_VERSIONS);
        transport.setNotificationHandler(this::handleNotification);
        transport.setRequestHandler(this::handleServerRequest);
    }

    /**
     * Initializes the connection and discovers server capabilities.
     *
     * @return a future that completes when initialization is done
     */
    public CompletableFuture<Void> initialize() {
        return transport.initializeSession()
            .thenCompose(v -> initializeWithProtocolFallback(0))
            .thenCompose(v -> discoverCapabilities());
    }

    private CompletableFuture<Void> initializeWithProtocolFallback(int idx) {
        if (idx >= supportedProtocolVersions.size()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("MCP initialization failed for all protocol versions")); //$NON-NLS-1$
        }
        String protocolVersion = idx == 0 ? preferredProtocolVersion : supportedProtocolVersions.get(idx);
        return sendInitialize(protocolVersion)
            .thenAccept(response -> {
                this.negotiatedProtocolVersion = protocolVersion;
                this.serverCapabilities = McpServerCapabilities.fromInitializeResult(response.getResult());
                LOG.info("MCP server '%s' initialized with protocol=%s. capabilities: tools=%b, resources=%b, prompts=%b", //$NON-NLS-1$
                    serverName,
                    protocolVersion,
                    serverCapabilities.supportsTools(),
                    serverCapabilities.supportsResources(),
                    serverCapabilities.supportsPrompts());
                // Required by MCP lifecycle
                sendNotification("notifications/initialized", null); //$NON-NLS-1$
            })
            .handle((ok, ex) -> {
                if (ex == null) {
                    return CompletableFuture.<Void>completedFuture(null);
                }
                LOG.warn("Initialize failed for protocol %s on server %s: %s", //$NON-NLS-1$
                    protocolVersion, serverName, ex.getMessage());
                return initializeWithProtocolFallback(idx + 1);
            })
            .thenCompose(f -> f);
    }

    private CompletableFuture<McpMessage> sendInitialize(String protocolVersion) {
        return transport.send(createRequest("initialize", Map.of( //$NON-NLS-1$
            "protocolVersion", protocolVersion, //$NON-NLS-1$
            "capabilities", Map.of(), //$NON-NLS-1$
            "clientInfo", Map.of( //$NON-NLS-1$
                "name", CLIENT_NAME, //$NON-NLS-1$
                "version", CLIENT_VERSION //$NON-NLS-1$
            )
        )));
    }

    private CompletableFuture<McpMessage> handleServerRequest(McpMessage request) {
        Function<McpMessage, CompletableFuture<McpMessage>> handler = serverRequestHandler;
        if (handler != null) {
            return handler.apply(request);
        }
        McpMessage response = new McpMessage();
        response.setError(new com.codepilot1c.core.mcp.model.McpError(
            -32601,
            "Method not found: " + request.getMethod(), //$NON-NLS-1$
            null
        ));
        return CompletableFuture.completedFuture(response);
    }

    private void handleNotification(McpMessage notification) {
        String method = notification.getMethod();
        LOG.debug("Received notification: %s", method); //$NON-NLS-1$

        if ("notifications/tools/list_changed".equals(method)) { //$NON-NLS-1$
            LOG.info("Tools list changed notification from %s, refreshing...", serverName); //$NON-NLS-1$
            listTools().thenAccept(newTools -> {
                this.tools = newTools;
                LOG.info("Refreshed tools for %s: %d tools", serverName, newTools.size()); //$NON-NLS-1$
                if (toolsChangedListener != null) {
                    toolsChangedListener.accept(newTools);
                }
            }).exceptionally(e -> {
                LOG.warn("Failed to refresh tools for %s: %s", serverName, e.getMessage()); //$NON-NLS-1$
                return null;
            });
        } else if ("notifications/resources/list_changed".equals(method)) { //$NON-NLS-1$
            LOG.info("Resources list changed notification from %s, refreshing...", serverName); //$NON-NLS-1$
            listResources().thenAccept(newResources -> {
                this.resources = newResources;
                LOG.info("Refreshed resources for %s: %d resources", serverName, newResources.size()); //$NON-NLS-1$
            }).exceptionally(e -> {
                LOG.warn("Failed to refresh resources for %s: %s", serverName, e.getMessage()); //$NON-NLS-1$
                return null;
            });
        } else if ("notifications/prompts/list_changed".equals(method)) { //$NON-NLS-1$
            LOG.info("Prompts list changed notification from %s, refreshing...", serverName); //$NON-NLS-1$
            listPrompts().thenAccept(newPrompts -> {
                this.prompts = newPrompts;
                LOG.info("Refreshed prompts for %s: %d prompts", serverName, newPrompts.size()); //$NON-NLS-1$
            }).exceptionally(e -> {
                LOG.warn("Failed to refresh prompts for %s: %s", serverName, e.getMessage()); //$NON-NLS-1$
                return null;
            });
        }
    }

    /**
     * Sets a listener for tools list changes.
     *
     * @param listener the listener to set
     */
    public void setToolsChangedListener(java.util.function.Consumer<List<McpTool>> listener) {
        this.toolsChangedListener = listener;
    }

    /**
     * Sets custom server request handler.
     *
     * @param handler request handler
     */
    public void setServerRequestHandler(Function<McpMessage, CompletableFuture<McpMessage>> handler) {
        this.serverRequestHandler = handler;
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
        return sendWithRetry("tools/list", null, true).thenApply(response -> parseToolList(response.getResult())); //$NON-NLS-1$
    }

    /**
     * Executes a tool on the server.
     *
     * @param toolName the tool name
     * @param arguments the tool arguments
     * @return a future containing the result
     */
    public CompletableFuture<McpToolResult> callTool(String toolName, Map<String, Object> arguments) {
        return sendWithRetry("tools/call", Map.of( //$NON-NLS-1$
            "name", toolName, //$NON-NLS-1$
            "arguments", arguments != null ? arguments : Map.of() //$NON-NLS-1$
        ), false).thenApply(response -> parseToolResult(response.getResult()));
    }

    /**
     * Lists available resources from the server.
     *
     * @return a future containing the resource list
     */
    public CompletableFuture<List<McpResource>> listResources() {
        return sendWithRetry("resources/list", null, true).thenApply(response -> parseResourceList(response.getResult())); //$NON-NLS-1$
    }

    /**
     * Reads a resource by URI.
     *
     * @param uri the resource URI
     * @return a future containing the resource content
     */
    public CompletableFuture<McpResourceContent> readResource(String uri) {
        return sendWithRetry("resources/read", Map.of("uri", uri), true) //$NON-NLS-1$ //$NON-NLS-2$
            .thenApply(response -> parseResourceContent(response.getResult()));
    }

    /**
     * Lists available prompts from the server.
     *
     * @return a future containing the prompt list
     */
    public CompletableFuture<List<McpPrompt>> listPrompts() {
        return sendWithRetry("prompts/list", null, true).thenApply(response -> parsePromptList(response.getResult())); //$NON-NLS-1$
    }

    /**
     * Gets a prompt with arguments.
     *
     * @param name the prompt name
     * @param arguments the prompt arguments
     * @return a future containing the prompt result
     */
    public CompletableFuture<McpPromptResult> getPrompt(String name, Map<String, String> arguments) {
        return sendWithRetry("prompts/get", Map.of( //$NON-NLS-1$
            "name", name, //$NON-NLS-1$
            "arguments", arguments != null ? arguments : Map.of() //$NON-NLS-1$
        ), true).thenApply(response -> parsePromptResult(response.getResult()));
    }

    private CompletableFuture<McpMessage> sendWithRetry(String method, Object params, boolean idempotent) {
        return transport.send(createRequest(method, params)).handle((response, error) -> {
            if (error == null) {
                return CompletableFuture.completedFuture(response);
            }
            if (!idempotent || !isSessionError(error)) {
                return CompletableFuture.<McpMessage>failedFuture(asThrowable(error));
            }
            LOG.warn("Retrying idempotent MCP request after transport/session error: %s", method); //$NON-NLS-1$
            return transport.initializeSession()
                .thenCompose(v -> transport.send(createRequest(method, params)));
        }).thenCompose(f -> f);
    }

    private boolean isSessionError(Throwable error) {
        String text = error != null && error.getMessage() != null ? error.getMessage().toLowerCase() : ""; //$NON-NLS-1$
        return text.contains("session") || text.contains("404"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private Throwable asThrowable(Throwable error) {
        if (error instanceof java.util.concurrent.CompletionException && error.getCause() != null) {
            return error.getCause();
        }
        return error;
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
     * Returns negotiated protocol version.
     *
     * @return negotiated version or null
     */
    public String getNegotiatedProtocolVersion() {
        return negotiatedProtocolVersion;
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
            transport.shutdown().exceptionally(e -> null).join();
            transport.close();
        } catch (Exception e) {
            LOG.warn("Error closing MCP transport", e); //$NON-NLS-1$
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
        transport.sendNotification(msg).exceptionally(e -> {
            LOG.warn("Failed to send notification %s: %s", method, e.getMessage()); //$NON-NLS-1$
            return null;
        });
    }

    private JsonObject toJsonObject(Object result) {
        if (result == null) {
            return null;
        }
        if (result instanceof JsonObject) {
            return (JsonObject) result;
        }
        JsonElement element = gson.toJsonTree(result);
        if (element.isJsonObject()) {
            return element.getAsJsonObject();
        }
        return null;
    }

    private List<McpTool> parseToolList(Object result) {
        List<McpTool> parsedTools = new ArrayList<>();
        JsonObject obj = toJsonObject(result);
        if (obj != null && obj.has("tools")) { //$NON-NLS-1$
            JsonArray arr = obj.getAsJsonArray("tools"); //$NON-NLS-1$
            for (JsonElement elem : arr) {
                parsedTools.add(gson.fromJson(elem, McpTool.class));
            }
            LOG.debug("Parsed %d tools from server %s", parsedTools.size(), serverName); //$NON-NLS-1$
        }
        return parsedTools;
    }

    private McpToolResult parseToolResult(Object result) {
        JsonObject obj = toJsonObject(result);
        if (obj != null) {
            McpToolResult parsed = gson.fromJson(obj, McpToolResult.class);
            if (parsed.getContent().isEmpty() && obj.has("structuredContent")) { //$NON-NLS-1$
                parsed.setContent(List.of(McpContent.text(gson.toJson(obj.get("structuredContent"))))); //$NON-NLS-1$
            }
            return parsed;
        }
        return new McpToolResult();
    }

    private List<McpResource> parseResourceList(Object result) {
        List<McpResource> parsedResources = new ArrayList<>();
        JsonObject obj = toJsonObject(result);
        if (obj != null && obj.has("resources")) { //$NON-NLS-1$
            JsonArray arr = obj.getAsJsonArray("resources"); //$NON-NLS-1$
            for (JsonElement elem : arr) {
                parsedResources.add(gson.fromJson(elem, McpResource.class));
            }
            LOG.debug("Parsed %d resources from server %s", parsedResources.size(), serverName); //$NON-NLS-1$
        }
        return parsedResources;
    }

    private McpResourceContent parseResourceContent(Object result) {
        JsonObject obj = toJsonObject(result);
        if (obj != null) {
            return gson.fromJson(obj, McpResourceContent.class);
        }
        return new McpResourceContent();
    }

    private List<McpPrompt> parsePromptList(Object result) {
        List<McpPrompt> parsedPrompts = new ArrayList<>();
        JsonObject obj = toJsonObject(result);
        if (obj != null && obj.has("prompts")) { //$NON-NLS-1$
            JsonArray arr = obj.getAsJsonArray("prompts"); //$NON-NLS-1$
            for (JsonElement elem : arr) {
                parsedPrompts.add(gson.fromJson(elem, McpPrompt.class));
            }
            LOG.debug("Parsed %d prompts from server %s", parsedPrompts.size(), serverName); //$NON-NLS-1$
        }
        return parsedPrompts;
    }

    private McpPromptResult parsePromptResult(Object result) {
        JsonObject obj = toJsonObject(result);
        if (obj != null) {
            return gson.fromJson(obj, McpPromptResult.class);
        }
        return new McpPromptResult();
    }
}
