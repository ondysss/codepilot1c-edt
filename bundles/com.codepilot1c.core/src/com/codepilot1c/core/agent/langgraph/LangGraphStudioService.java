/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.langgraph;

import java.net.URL;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.GraphRepresentation;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.studio.jetty.LangGraphStreamingServerJetty;

import com.codepilot1c.core.agent.AgentConfig;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.ToolRegistry;

/**
 * Starts LangGraph Studio server and provides Mermaid/PlantUML representations.
 */
public final class LangGraphStudioService {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(LangGraphStudioService.class);
    private static final int DEFAULT_PORT = 8770;
    private static final int MAX_PORT_ATTEMPTS = 10;
    private static final String PROP_PORT = "codepilot1c.langgraph.studio.port"; //$NON-NLS-1$

    private static final LangGraphStudioService INSTANCE = new LangGraphStudioService();

    private final AtomicReference<CompletableFuture<String>> startFuture = new AtomicReference<>();
    private final AtomicReference<Integer> activePort = new AtomicReference<>();
    private volatile org.bsc.langgraph4j.StateGraph<AgentState> studioGraph;

    public static LangGraphStudioService getInstance() {
        return INSTANCE;
    }

    public CompletableFuture<String> getStudioUrlAsync() {
        CompletableFuture<String> existing = startFuture.get();
        if (existing != null) {
            if (existing.isCompletedExceptionally() || existing.isCancelled()) {
                startFuture.compareAndSet(existing, null);
            } else {
                return existing;
            }
        }

        CompletableFuture<String> starter = new CompletableFuture<>();
        if (!startFuture.compareAndSet(null, starter)) {
            return startFuture.get();
        }

        CompletableFuture.runAsync(() -> {
            try {
                String url = tryStartServer();
                starter.complete(url);
            } catch (Exception e) {
                starter.completeExceptionally(e);
            }
        });

        return starter;
    }

    public String getMermaidGraph() {
        GraphRepresentation representation = getStudioGraph()
                .getGraph(GraphRepresentation.Type.MERMAID, "codepilot1c", true); //$NON-NLS-1$
        return representation != null ? representation.getContent() : ""; //$NON-NLS-1$
    }

    public String getPlantUmlGraph() {
        GraphRepresentation representation = getStudioGraph()
                .getGraph(GraphRepresentation.Type.PLANTUML, "codepilot1c", true); //$NON-NLS-1$
        return representation != null ? representation.getContent() : ""; //$NON-NLS-1$
    }

    private org.bsc.langgraph4j.StateGraph<AgentState> getStudioGraph() {
        if (studioGraph == null) {
            LangGraphAgentRunContext context = new LangGraphAgentRunContext(
                    null,
                    ToolRegistry.getInstance(),
                    AgentConfig.defaults(),
                    "", //$NON-NLS-1$
                    List.of(),
                    List.of(),
                    new AtomicReference<>(),
                    new AtomicReference<>(),
                    true
            );
            studioGraph = LangGraphAgentGraphFactory.buildGraph(context);
        }
        return studioGraph;
    }

    private int resolvePort() {
        String raw = System.getProperty(PROP_PORT);
        if (raw != null && !raw.isBlank()) {
            try {
                return Integer.parseInt(raw.trim());
            } catch (NumberFormatException ignored) {
                // fallback to default
            }
        }
        return DEFAULT_PORT;
    }

    private String tryStartServer() {
        int basePort = resolvePort();
        Exception lastError = null;

        for (int attempt = 0; attempt < MAX_PORT_ATTEMPTS; attempt++) {
            int port = basePort + attempt;
            try {
                startServerOnPort(port);
                activePort.set(port);
                return "http://127.0.0.1:" + port + "/"; //$NON-NLS-1$ //$NON-NLS-2$
            } catch (Exception e) {
                LOG.error(String.format("LangGraph Studio start failed on port %d", //$NON-NLS-1$
                        Integer.valueOf(port)), e);
                lastError = e;
                if (!isAddressInUse(e)) {
                    break;
                }
            }
        }

        if (lastError instanceof RuntimeException) {
            throw (RuntimeException) lastError;
        }
        throw new IllegalStateException("Не удалось запустить LangGraph Studio: " //$NON-NLS-1$
                + formatRootCause(lastError), lastError);
    }

    private void startServerOnPort(int port) throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(LangGraphStudioService.class.getClassLoader());
        try {
            ensureBundleResourceHandler();
            ensureWebappResources();
            LangGraphStreamingServerJetty server = LangGraphStreamingServerJetty.builder()
                    .port(port)
                    .title("CodePilot1C LangGraph") //$NON-NLS-1$
                    .addInputStringArg("prompt", true) //$NON-NLS-1$
                    .stateGraph(getStudioGraph())
                    .compileConfig(CompileConfig.builder()
                            .checkpointSaver(new MemorySaver())
                            .build())
                    .build();

            server.start().join();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private void ensureBundleResourceHandler() {
        Bundle bundle = Platform.getBundle("org.eclipse.osgi.services"); //$NON-NLS-1$
        if (bundle == null) {
            LOG.warn("OSGi bundle org.eclipse.osgi.services not found. " //$NON-NLS-1$
                    + "bundleresource URL handler may be unavailable."); //$NON-NLS-1$
            return;
        }
        if (bundle.getState() == Bundle.ACTIVE) {
            return;
        }
        try {
            bundle.start();
            LOG.info("OSGi bundle org.eclipse.osgi.services started"); //$NON-NLS-1$
        } catch (BundleException e) {
            LOG.error("Failed to start org.eclipse.osgi.services bundle", e); //$NON-NLS-1$
        }
    }

    private void ensureWebappResources() {
        ClassLoader loader = LangGraphStreamingServerJetty.class.getClassLoader();
        URL resource = loader != null ? loader.getResource("webapp/index.html") : null; //$NON-NLS-1$
        if (resource == null) {
            throw new IllegalStateException("LangGraph Studio webapp resources not found " //$NON-NLS-1$
                    + "(webapp/index.html отсутствует в classpath)"); //$NON-NLS-1$
        }
    }

    private String formatRootCause(Throwable error) {
        if (error == null) {
            return "unknown error"; //$NON-NLS-1$
        }
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        String type = root.getClass().getSimpleName();
        if (message == null || message.isBlank()) {
            return type;
        }
        return type + ": " + message; //$NON-NLS-1$
    }

    private boolean isAddressInUse(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null && msg.toLowerCase().contains("address already in use")) { //$NON-NLS-1$
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
