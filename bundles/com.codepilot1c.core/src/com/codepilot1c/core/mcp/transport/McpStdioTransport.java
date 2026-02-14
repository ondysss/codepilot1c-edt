/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp.transport;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.mcp.model.McpError;
import com.codepilot1c.core.mcp.model.McpException;
import com.codepilot1c.core.mcp.model.McpMessage;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * STDIO transport for MCP servers.
 *
 * <p>Communicates with MCP servers through stdin/stdout of a subprocess.
 * Messages are sent as JSON lines.</p>
 */
public class McpStdioTransport implements IMcpTransport {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(McpStdioTransport.class);

    private final String command;
    private final List<String> args;
    private final Map<String, String> env;
    private final File workingDirectory;
    private final int requestTimeoutMs;

    private Process process;
    private BufferedReader stdout;
    private BufferedWriter stdin;
    private BufferedReader stderr;
    private Thread readerThread;
    private Thread stderrThread;
    private volatile boolean running = false;

    private final Map<String, CompletableFuture<McpMessage>> pendingRequests = new ConcurrentHashMap<>();
    private final AtomicLong requestIdCounter = new AtomicLong(1);
    private Consumer<McpMessage> notificationHandler;
    private Function<McpMessage, CompletableFuture<McpMessage>> requestHandler;

    private final Gson gson = new GsonBuilder().create();

    /**
     * Creates a new STDIO transport.
     *
     * @param command the command to execute
     * @param args command arguments
     * @param env environment variables
     * @param workingDirectory working directory for the process
     * @param requestTimeoutMs timeout for requests in milliseconds
     */
    public McpStdioTransport(String command, List<String> args, Map<String, String> env,
                             File workingDirectory, int requestTimeoutMs) {
        this.command = command;
        this.args = args != null ? new ArrayList<>(args) : new ArrayList<>();
        this.env = env != null ? new HashMap<>(env) : new HashMap<>();
        this.workingDirectory = workingDirectory;
        this.requestTimeoutMs = requestTimeoutMs > 0 ? requestTimeoutMs : 60000;
    }

    @Override
    public void connect() throws IOException {
        Map<String, String> resolvedEnv = resolveEnvironment(env);

        // Try to resolve the command to its full path
        String resolvedCommand = resolveCommandPath(command, resolvedEnv);

        List<String> fullCommand = new ArrayList<>();
        fullCommand.add(resolvedCommand);
        fullCommand.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(fullCommand);
        pb.environment().putAll(resolvedEnv);
        if (workingDirectory != null) {
            pb.directory(workingDirectory);
        }
        pb.redirectErrorStream(false);

        LOG.info("Starting MCP process: %s", String.join(" ", fullCommand));
        process = pb.start();
        running = true;

        stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        stderr = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));

        // Start reader thread
        readerThread = new Thread(this::readLoop, "MCP-STDIO-Reader-" + command);
        readerThread.setDaemon(true);
        readerThread.start();

        // Start stderr logger thread
        stderrThread = new Thread(this::logStderr, "MCP-STDIO-Stderr-" + command);
        stderrThread.setDaemon(true);
        stderrThread.start();

        LOG.info("MCP STDIO transport connected: %s", command);
    }

    private void readLoop() {
        try {
            String line;
            while (running && (line = stdout.readLine()) != null) {
                try {
                    McpMessage message = gson.fromJson(line, McpMessage.class);
                    handleMessage(message);
                } catch (Exception e) {
                    LOG.warn("Failed to parse MCP message: %s - line: %s", e.getMessage(), line);
                }
            }
        } catch (IOException e) {
            if (running) {
                LOG.error("MCP STDIO read error", e);
            }
        } finally {
            running = false;
        }
    }

    private void logStderr() {
        try {
            String line;
            while (running && (line = stderr.readLine()) != null) {
                LOG.debug("[MCP stderr] %s", line);
            }
        } catch (IOException e) {
            // Ignore - process likely terminated
        }
    }

    private void handleMessage(McpMessage message) {
        if (message.isResponse()) {
            // Response to our request
            String id = message.getId();
            CompletableFuture<McpMessage> future = pendingRequests.remove(id);
            if (future != null) {
                if (message.getError() != null) {
                    future.completeExceptionally(new McpException(message.getError()));
                } else {
                    future.complete(message);
                }
            } else {
                LOG.warn("Received response for unknown request ID: %s", id);
            }
        } else if (message.isNotification()) {
            // Server notification (no id, has method)
            if (notificationHandler != null) {
                notificationHandler.accept(message);
            }
        } else if (message.isRequest()) {
            handleServerRequest(message);
        }
    }

    private void handleServerRequest(McpMessage message) {
        Function<McpMessage, CompletableFuture<McpMessage>> handler = requestHandler;
        if (handler == null) {
            LOG.warn("Received unsupported server request: %s", message.getMethod());
            sendErrorResponse(message.getId(), -32601, "Method not found: " + message.getMethod());
            return;
        }
        handler.apply(message)
            .thenAccept(response -> {
                if (response == null) {
                    sendErrorResponse(message.getId(), -32603, "Request handler returned null response");
                    return;
                }
                Object rawId = message.getRawId();
                if (rawId != null) {
                    response.setRawId(rawId);
                } else {
                    response.setId(message.getId());
                }
                sendResponse(response);
            })
            .exceptionally(e -> {
                sendErrorResponse(message.getId(), -32603, "Request handler failed: " + e.getMessage());
                return null;
            });
    }

    private void sendResponse(McpMessage response) {
        try {
            String json = gson.toJson(response);
            synchronized (stdin) {
                stdin.write(json);
                stdin.newLine();
                stdin.flush();
            }
        } catch (IOException e) {
            LOG.warn("Failed to send response", e);
        }
    }

    private void sendErrorResponse(String id, int code, String errorMessage) {
        try {
            McpMessage response = new McpMessage();
            response.setId(id);
            response.setError(new McpError(code, errorMessage, null));
            String json = gson.toJson(response);
            synchronized (stdin) {
                stdin.write(json);
                stdin.newLine();
                stdin.flush();
            }
        } catch (IOException e) {
            LOG.warn("Failed to send error response", e);
        }
    }

    @Override
    public CompletableFuture<McpMessage> send(McpMessage message) {
        if (!isConnected()) {
            return CompletableFuture.failedFuture(new IOException("Transport not connected"));
        }

        // For requests - assign ID and wait for response
        String id = String.valueOf(requestIdCounter.getAndIncrement());
        message.setId(id);

        CompletableFuture<McpMessage> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        try {
            String json = gson.toJson(message);
            synchronized (stdin) {
                stdin.write(json);
                stdin.newLine();
                stdin.flush();
            }
            LOG.debug("Sent MCP request: %s", message);
        } catch (IOException e) {
            pendingRequests.remove(id);
            future.completeExceptionally(e);
            return future;
        }

        // Add timeout with cleanup
        return future
            .orTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
            .whenComplete((result, error) -> {
                // Always cleanup pending request on completion (success, error, or timeout)
                pendingRequests.remove(id);
            });
    }

    @Override
    public CompletableFuture<Void> sendNotification(McpMessage message) {
        if (!isConnected()) {
            return CompletableFuture.failedFuture(new IOException("Transport not connected"));
        }

        try {
            String json = gson.toJson(message);
            synchronized (stdin) {
                stdin.write(json);
                stdin.newLine();
                stdin.flush();
            }
            LOG.debug("Sent MCP notification: %s", message);
            return CompletableFuture.completedFuture(null);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public void disconnect() {
        running = false;

        // Complete all pending requests with error
        pendingRequests.forEach((id, future) ->
            future.completeExceptionally(new IOException("Transport disconnected")));
        pendingRequests.clear();

        // Close streams properly
        closeQuietly(stdin);
        closeQuietly(stdout);
        closeQuietly(stderr);

        // Terminate process
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }

        // Interrupt and wait for threads
        if (readerThread != null) {
            readerThread.interrupt();
            try {
                readerThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (stderrThread != null) {
            stderrThread.interrupt();
            try {
                stderrThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        LOG.info("MCP STDIO transport disconnected: %s", command);
    }

    private void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }

    @Override
    public void close() {
        disconnect();
    }

    @Override
    public boolean isConnected() {
        return running && process != null && process.isAlive();
    }

    @Override
    public void setNotificationHandler(Consumer<McpMessage> handler) {
        this.notificationHandler = handler;
    }

    @Override
    public void setRequestHandler(Function<McpMessage, CompletableFuture<McpMessage>> handler) {
        this.requestHandler = handler;
    }

    /**
     * Resolves a command to its full path by searching in PATH directories.
     *
     * @param cmd the command name
     * @param resolvedEnv the resolved environment containing PATH
     * @return the full path to the command, or the original command if not found
     */
    private String resolveCommandPath(String cmd, Map<String, String> resolvedEnv) {
        // If already an absolute path, return as is
        File cmdFile = new File(cmd);
        if (cmdFile.isAbsolute() && cmdFile.canExecute()) {
            return cmd;
        }

        // Get PATH from resolved environment
        String pathKey = isWindows() ? "Path" : "PATH";
        String pathValue = resolvedEnv.get(pathKey);
        if (pathValue == null) {
            pathValue = resolvedEnv.get("PATH");
        }
        if (pathValue == null) {
            LOG.warn("No PATH found in environment, using command as-is: %s", cmd);
            return cmd;
        }

        // Search for command in PATH directories
        String[] pathDirs = pathValue.split(File.pathSeparator);
        for (String dir : pathDirs) {
            if (dir.isEmpty()) {
                continue;
            }

            File candidate;
            if (isWindows()) {
                // On Windows, try with common extensions
                String[] extensions = { ".exe", ".cmd", ".bat", ".com", "" };
                for (String ext : extensions) {
                    candidate = new File(dir, cmd + ext);
                    if (candidate.isFile() && candidate.canExecute()) {
                        LOG.debug("Resolved command '%s' to '%s'", cmd, candidate.getAbsolutePath());
                        return candidate.getAbsolutePath();
                    }
                }
            } else {
                // On Unix-like systems
                candidate = new File(dir, cmd);
                if (candidate.isFile() && candidate.canExecute()) {
                    LOG.debug("Resolved command '%s' to '%s'", cmd, candidate.getAbsolutePath());
                    return candidate.getAbsolutePath();
                }
            }
        }

        LOG.warn("Could not resolve command '%s' in PATH, using as-is", cmd);
        return cmd;
    }

    private Map<String, String> resolveEnvironment(Map<String, String> env) {
        Map<String, String> resolved = new HashMap<>();

        // Resolve user-provided environment variables
        for (Map.Entry<String, String> entry : env.entrySet()) {
            String value = entry.getValue();
            // Resolve ${VAR} references
            if (value != null && value.startsWith("${") && value.endsWith("}")) {
                String envVar = value.substring(2, value.length() - 1);
                value = System.getenv(envVar);
                if (value == null) {
                    LOG.warn("Environment variable not found: %s", envVar);
                    value = "";
                }
            }
            resolved.put(entry.getKey(), value);
        }

        // Ensure PATH includes common binary locations
        // Eclipse doesn't inherit shell PATH, so we need to add these manually
        String pathKey = isWindows() ? "Path" : "PATH";
        if (!resolved.containsKey(pathKey) && !resolved.containsKey("PATH")) {
            String currentPath = System.getenv(pathKey);
            if (currentPath == null) {
                currentPath = System.getenv("PATH");
            }
            if (currentPath == null) {
                currentPath = "";
            }

            List<String> additionalPaths = getAdditionalPaths();
            StringBuilder pathBuilder = new StringBuilder();

            for (String path : additionalPaths) {
                File dir = new File(path);
                if (dir.isDirectory()) {
                    if (pathBuilder.length() > 0) {
                        pathBuilder.append(File.pathSeparator);
                    }
                    pathBuilder.append(path);
                }
            }

            // Add current PATH at the end
            if (!currentPath.isEmpty()) {
                if (pathBuilder.length() > 0) {
                    pathBuilder.append(File.pathSeparator);
                }
                pathBuilder.append(currentPath);
            }

            resolved.put(pathKey, pathBuilder.toString());
            LOG.debug("Resolved %s for MCP process: %s", pathKey, pathBuilder.toString());
        }

        return resolved;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    private List<String> getAdditionalPaths() {
        List<String> paths = new ArrayList<>();
        String userHome = System.getProperty("user.home");

        if (isWindows()) {
            // Windows paths
            String appData = System.getenv("APPDATA");
            String localAppData = System.getenv("LOCALAPPDATA");
            String programFiles = System.getenv("ProgramFiles");
            String programFilesX86 = System.getenv("ProgramFiles(x86)");

            // Node.js / npm / npx
            if (appData != null) {
                paths.add(appData + "\\npm");
                paths.add(appData + "\\nvm");
            }
            if (programFiles != null) {
                paths.add(programFiles + "\\nodejs");
            }
            if (programFilesX86 != null) {
                paths.add(programFilesX86 + "\\nodejs");
            }

            // nvm-windows
            if (appData != null) {
                File nvmDir = new File(appData + "\\nvm");
                if (nvmDir.isDirectory()) {
                    // Find installed Node versions
                    File[] nodeDirs = nvmDir.listFiles(f -> f.isDirectory() && f.getName().startsWith("v"));
                    if (nodeDirs != null) {
                        for (File nodeDir : nodeDirs) {
                            paths.add(nodeDir.getAbsolutePath());
                        }
                    }
                }
            }

            // Python
            if (localAppData != null) {
                paths.add(localAppData + "\\Programs\\Python\\Python311");
                paths.add(localAppData + "\\Programs\\Python\\Python310");
                paths.add(localAppData + "\\Programs\\Python\\Python39");
            }

            // User local bin
            if (userHome != null) {
                paths.add(userHome + "\\.local\\bin");
            }

        } else {
            // Unix-like paths (macOS and Linux)

            if (isMac()) {
                // macOS Homebrew
                paths.add("/opt/homebrew/bin");      // Apple Silicon
                paths.add("/opt/homebrew/sbin");
                paths.add("/usr/local/bin");         // Intel Mac
                paths.add("/usr/local/sbin");
            }

            // Linux common paths
            paths.add("/usr/local/bin");
            paths.add("/usr/bin");
            paths.add("/bin");
            paths.add("/snap/bin");                  // Snap packages

            // User directories
            if (userHome != null) {
                paths.add(userHome + "/.local/bin");  // pip user installs
                paths.add(userHome + "/bin");

                // nvm (Unix)
                File nvmDir = new File(userHome + "/.nvm/versions/node");
                if (nvmDir.isDirectory()) {
                    File[] nodeDirs = nvmDir.listFiles(f -> f.isDirectory() && f.getName().startsWith("v"));
                    if (nodeDirs != null) {
                        for (File nodeDir : nodeDirs) {
                            paths.add(nodeDir.getAbsolutePath() + "/bin");
                        }
                    }
                }

                // fnm (Fast Node Manager)
                File fnmDir = new File(userHome + "/.fnm/node-versions");
                if (fnmDir.isDirectory()) {
                    File[] nodeDirs = fnmDir.listFiles(File::isDirectory);
                    if (nodeDirs != null) {
                        for (File nodeDir : nodeDirs) {
                            paths.add(nodeDir.getAbsolutePath() + "/installation/bin");
                        }
                    }
                }

                // Volta
                paths.add(userHome + "/.volta/bin");

                // Cargo (Rust)
                paths.add(userHome + "/.cargo/bin");

                // Go
                paths.add(userHome + "/go/bin");
            }
        }

        return paths;
    }
}
