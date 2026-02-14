/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.mcp.client.McpClient;
import com.codepilot1c.core.mcp.config.McpServerConfig;
import com.codepilot1c.core.mcp.config.McpServerConfigStore;
import com.codepilot1c.core.mcp.model.McpServerState;
import com.codepilot1c.core.mcp.model.McpTool;
import com.codepilot1c.core.mcp.transport.IMcpTransport;
import com.codepilot1c.core.mcp.transport.McpTransportFactory;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolRegistry;

/**
 * Manages MCP server lifecycle and tool registration.
 *
 * <p>This class handles starting/stopping MCP servers and registering
 * their tools with the ToolRegistry.</p>
 */
public class McpServerManager {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(McpServerManager.class);

    private static McpServerManager instance;

    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    private final Map<String, McpServerState> serverStates = new ConcurrentHashMap<>();
    private final Map<String, String> serverErrors = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<McpClient>> startingServers = new ConcurrentHashMap<>();
    private final List<IMcpServerListener> listeners = new CopyOnWriteArrayList<>();
    private final McpTransportFactory transportFactory = new McpTransportFactory();

    /**
     * Returns the singleton instance.
     *
     * @return the instance
     */
    public static synchronized McpServerManager getInstance() {
        if (instance == null) {
            instance = new McpServerManager();
        }
        return instance;
    }

    private McpServerManager() {
    }

    /**
     * Starts all enabled MCP servers.
     */
    public void startEnabledServers() {
        List<McpServerConfig> configs = McpServerConfigStore.getInstance().getEnabledServers();
        LOG.info("Starting %d enabled MCP servers", configs.size());
        for (McpServerConfig config : configs) {
            startServer(config);
        }
    }

    /**
     * Starts a specific MCP server.
     *
     * @param config the server configuration
     * @return a future containing the client
     */
    public CompletableFuture<McpClient> startServer(McpServerConfig config) {
        String serverId = config.getId();

        // Already running?
        if (clients.containsKey(serverId)) {
            LOG.debug("MCP server '%s' already running", config.getName());
            return CompletableFuture.completedFuture(clients.get(serverId));
        }

        // Prevent concurrent starts of the same server
        CompletableFuture<McpClient> existingStart = startingServers.get(serverId);
        if (existingStart != null) {
            LOG.debug("MCP server '%s' already starting, waiting...", config.getName());
            return existingStart;
        }

        LOG.info("Starting MCP server: %s (%s %s)",
            config.getName(), config.getCommand(), String.join(" ", config.getArgs()));

        serverStates.put(serverId, McpServerState.STARTING);
        serverErrors.remove(serverId);
        notifyStateChanged(config, McpServerState.STARTING);

        IMcpTransport transport = createTransport(config);
        McpClient client = new McpClient(
            config.getName(),
            transport,
            config.getPreferredProtocolVersion(),
            config.getSupportedProtocolVersions()
        );

        CompletableFuture<McpClient> startFuture = CompletableFuture
            .runAsync(() -> {
                try {
                    transport.connect();
                } catch (IOException e) {
                    throw new RuntimeException("Failed to connect: " + e.getMessage(), e);
                }
            })
            .thenCompose(v -> client.initialize())
            .thenApply(v -> {
                clients.put(serverId, client);
                registerToolsFromServer(serverId, client);
                serverStates.put(serverId, McpServerState.RUNNING);
                notifyStateChanged(config, McpServerState.RUNNING);
                LOG.info("MCP server '%s' started with %d tools",
                    config.getName(), client.getTools().size());
                return client;
            })
            .exceptionally(e -> {
                LOG.error("Failed to start MCP server '%s': %s", config.getName(), e.getMessage());
                serverStates.put(serverId, McpServerState.ERROR);
                serverErrors.put(serverId, e.getMessage());
                notifyStateChanged(config, McpServerState.ERROR);
                client.close();
                return null;
            })
            .whenComplete((result, error) -> {
                startingServers.remove(serverId);
            });

        startingServers.put(serverId, startFuture);
        return startFuture;
    }

    /**
     * Stops a specific MCP server.
     *
     * @param serverId the server ID
     */
    public void stopServer(String serverId) {
        McpClient client = clients.remove(serverId);
        if (client != null) {
            unregisterToolsFromServer(serverId, client);
            client.close();
            serverStates.put(serverId, McpServerState.STOPPED);
            serverErrors.remove(serverId);
            LOG.info("MCP server stopped: %s", client.getServerName());
            notifyServerStopped(serverId);
        }
    }

    /**
     * Stops all MCP servers.
     */
    public void stopAllServers() {
        for (String serverId : new ArrayList<>(clients.keySet())) {
            stopServer(serverId);
        }
    }

    /**
     * Gets the state of a server.
     *
     * @param serverId the server ID
     * @return the state
     */
    public McpServerState getServerState(String serverId) {
        return serverStates.getOrDefault(serverId, McpServerState.STOPPED);
    }

    /**
     * Gets the error message for a server in ERROR state.
     *
     * @param serverId the server ID
     * @return the error message, or null
     */
    public String getServerError(String serverId) {
        return serverErrors.get(serverId);
    }

    /**
     * Gets all running clients.
     *
     * @return unmodifiable collection of clients
     */
    public Collection<McpClient> getRunningClients() {
        return Collections.unmodifiableCollection(clients.values());
    }

    /**
     * Gets a client by server ID.
     *
     * @param serverId the server ID
     * @return the client, or null if not running
     */
    public McpClient getClient(String serverId) {
        return clients.get(serverId);
    }

    /**
     * Checks if a server is running.
     *
     * @param serverId the server ID
     * @return true if running
     */
    public boolean isServerRunning(String serverId) {
        return serverStates.get(serverId) == McpServerState.RUNNING;
    }

    /**
     * Adds a listener.
     *
     * @param listener the listener
     */
    public void addListener(IMcpServerListener listener) {
        listeners.add(listener);
    }

    /**
     * Removes a listener.
     *
     * @param listener the listener
     */
    public void removeListener(IMcpServerListener listener) {
        listeners.remove(listener);
    }

    private IMcpTransport createTransport(McpServerConfig config) {
        return transportFactory.create(config);
    }

    private void registerToolsFromServer(String serverId, McpClient client) {
        ToolRegistry registry = ToolRegistry.getInstance();
        String prefix = "mcp_" + client.getServerName().replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase() + "_";

        // Register initial tools
        for (McpTool mcpTool : client.getTools()) {
            ITool adapter = new McpToolAdapter(client, mcpTool);
            registry.registerDynamicTool(adapter);
            LOG.debug("Registered MCP tool: %s", adapter.getName());
        }

        // Set listener for dynamic tools updates
        client.setToolsChangedListener(newTools -> {
            LOG.info("Re-registering tools for %s due to list_changed notification", client.getServerName());
            // Unregister old tools
            registry.unregisterToolsByPrefix(prefix);
            // Register new tools
            for (McpTool mcpTool : newTools) {
                ITool adapter = new McpToolAdapter(client, mcpTool);
                registry.registerDynamicTool(adapter);
                LOG.debug("Re-registered MCP tool: %s", adapter.getName());
            }
        });
    }

    private void unregisterToolsFromServer(String serverId, McpClient client) {
        ToolRegistry registry = ToolRegistry.getInstance();
        String prefix = "mcp_" + client.getServerName().replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase() + "_";
        registry.unregisterToolsByPrefix(prefix);
    }

    private void notifyStateChanged(McpServerConfig config, McpServerState state) {
        for (IMcpServerListener listener : listeners) {
            try {
                listener.onServerStateChanged(config, state);
            } catch (Exception e) {
                LOG.warn("Listener error", e);
            }
        }
    }

    private void notifyServerStopped(String serverId) {
        for (IMcpServerListener listener : listeners) {
            try {
                listener.onServerStopped(serverId);
            } catch (Exception e) {
                LOG.warn("Listener error", e);
            }
        }
    }
}
