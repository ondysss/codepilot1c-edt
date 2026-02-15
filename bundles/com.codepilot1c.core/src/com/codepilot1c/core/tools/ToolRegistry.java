/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.Platform;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.model.ToolCall;
import com.codepilot1c.core.model.ToolDefinition;

/**
 * Registry for AI tools.
 *
 * <p>Manages tool registration and execution.</p>
 */
public class ToolRegistry {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(ToolRegistry.class);

    private static final String TOOL_PROVIDER_EXTENSION_POINT =
            "com.codepilot1c.core.toolProvider"; //$NON-NLS-1$

    private static ToolRegistry instance;

    private final Map<String, ITool> tools = new HashMap<>();
    private final Map<String, ITool> dynamicTools = new ConcurrentHashMap<>();
    private final Gson gson = new Gson();

    private ToolRegistry() {
        // Register default tools
        registerDefaultTools();
        LOG.info("ToolRegistry initialized with %d tools", tools.size()); //$NON-NLS-1$
    }

    /**
     * Returns the singleton instance.
     *
     * @return the instance
     */
    public static synchronized ToolRegistry getInstance() {
        if (instance == null) {
            instance = new ToolRegistry();
        }
        return instance;
    }

    private void registerDefaultTools() {
        // OSS default tools (commodity)
        register(new SearchCodebaseTool());
        register(new ReadFileTool());
        register(new ListFilesTool());
        register(new EditFileTool());
        register(new WriteTool());
        register(new GrepTool());
        register(new GlobTool());
        register(new EdtContentAssistTool());
        register(new EdtFindReferencesTool());
        register(new EdtMetadataDetailsTool());
        register(new EdtFieldTypeCandidatesTool());
        register(new GetPlatformDocumentationTool());
        register(new BslSymbolAtPositionTool());
        register(new BslTypeAtPositionTool());
        register(new BslScopeMembersTool());
        register(new EdtValidateRequestTool());
        register(new CreateMetadataTool());
        register(new CreateFormTool());
        register(new AddMetadataChildTool());
        register(new EnsureModuleArtifactTool());
        register(new UpdateMetadataTool());
        register(new MutateFormModelTool());
        register(new DeleteMetadataTool());
        register(new EdtMetadataSmokeTool());
        register(new EdtTraceExportTool());

        // Extra tools may be contributed by an overlay (e.g. Pro) via extension point.
        loadToolsFromExtensionPoint();
    }

    private void loadToolsFromExtensionPoint() {
        IExtensionRegistry registry = Platform.getExtensionRegistry();
        if (registry == null) {
            return;
        }

        IConfigurationElement[] elements = registry.getConfigurationElementsFor(
                TOOL_PROVIDER_EXTENSION_POINT);
        for (IConfigurationElement element : elements) {
            if (!"tool".equals(element.getName())) { //$NON-NLS-1$
                continue;
            }
            try {
                Object instance = element.createExecutableExtension("class"); //$NON-NLS-1$
                if (instance instanceof ITool tool) {
                    register(tool);
                    LOG.info("Registered tool from extension: %s", tool.getName()); //$NON-NLS-1$
                } else {
                    LOG.warn("Ignoring non-ITool contribution: %s", instance); //$NON-NLS-1$
                }
            } catch (Exception e) {
                LOG.error("Failed to load tool contribution from extension point", e); //$NON-NLS-1$
            }
        }
    }

    /**
     * Registers a tool.
     *
     * @param tool the tool to register
     */
    public void register(ITool tool) {
        tools.put(tool.getName(), tool);
    }

    /**
     * Unregisters a tool.
     *
     * @param name the tool name
     */
    public void unregister(String name) {
        tools.remove(name);
    }

    /**
     * Registers a dynamic tool (e.g., from MCP server).
     *
     * <p>Dynamic tools are stored separately and can be unregistered at runtime.</p>
     *
     * @param tool the tool to register
     */
    public void registerDynamicTool(ITool tool) {
        dynamicTools.put(tool.getName(), tool);
        LOG.debug("Registered dynamic tool: %s", tool.getName()); //$NON-NLS-1$
    }

    /**
     * Unregisters a dynamic tool.
     *
     * @param name the tool name
     */
    public void unregisterDynamicTool(String name) {
        dynamicTools.remove(name);
        LOG.debug("Unregistered dynamic tool: %s", name); //$NON-NLS-1$
    }

    /**
     * Unregisters all dynamic tools with names starting with a prefix.
     *
     * @param prefix the prefix
     */
    public void unregisterToolsByPrefix(String prefix) {
        List<String> toRemove = dynamicTools.keySet().stream()
            .filter(name -> name.startsWith(prefix))
            .collect(Collectors.toList());
        toRemove.forEach(dynamicTools::remove);
        if (!toRemove.isEmpty()) {
            LOG.debug("Unregistered %d dynamic tools with prefix: %s", toRemove.size(), prefix); //$NON-NLS-1$
        }
    }

    /**
     * Returns a tool by name.
     *
     * @param name the tool name
     * @return the tool, or null if not found
     */
    public ITool getTool(String name) {
        // Built-in tools take precedence over dynamic tools
        ITool tool = tools.get(name);
        if (tool == null) {
            tool = dynamicTools.get(name);
        }
        return tool;
    }

    /**
     * Returns all registered tools (built-in and dynamic).
     *
     * <p>Built-in tools take precedence over dynamic tools with the same name.</p>
     *
     * @return unmodifiable list of tools
     */
    public List<ITool> getAllTools() {
        Map<String, ITool> allTools = new LinkedHashMap<>();
        // Add dynamic tools first
        allTools.putAll(dynamicTools);
        // Built-in tools override dynamic tools with same name
        allTools.putAll(tools);
        return Collections.unmodifiableList(new ArrayList<>(allTools.values()));
    }

    /**
     * Returns tool definitions for all registered tools (built-in and dynamic).
     *
     * @return list of tool definitions
     */
    public List<ToolDefinition> getToolDefinitions() {
        return getAllTools().stream()
                .map(tool -> new ToolDefinition(
                        tool.getName(),
                        tool.getDescription(),
                        tool.getParameterSchema()))
                .collect(Collectors.toList());
    }

    /**
     * Executes a tool call.
     *
     * @param toolCall the tool call to execute
     * @return future with the result
     */
    public CompletableFuture<ToolResult> execute(ToolCall toolCall) {
        LOG.debug("Executing tool: %s with args: %s", toolCall.getName(), toolCall.getArguments()); //$NON-NLS-1$

        ToolLogger toolLogger = ToolLogger.getInstance();

        // Use getTool() to search both built-in and dynamic tools (MCP)
        ITool tool = getTool(toolCall.getName());
        if (tool == null) {
            LOG.error("Unknown tool: %s (checked %d built-in and %d dynamic tools)", //$NON-NLS-1$
                    toolCall.getName(), tools.size(), dynamicTools.size());
            ToolResult failResult = ToolResult.failure("Unknown tool: " + toolCall.getName()); //$NON-NLS-1$
            toolLogger.logToolCallResult(-1, toolCall.getName(), failResult, 0);
            return CompletableFuture.completedFuture(failResult);
        }

        try {
            Map<String, Object> parameters = parseArguments(toolCall.getArguments());
            LOG.debug("Parsed parameters: %s", parameters); //$NON-NLS-1$

            // Log tool call start
            int callId = toolLogger.logToolCallStart(toolCall.getName(), parameters);
            long startTime = System.currentTimeMillis();

            return tool.execute(parameters)
                    .thenApply(result -> {
                        long duration = System.currentTimeMillis() - startTime;
                        // Log tool call result
                        toolLogger.logToolCallResult(callId, toolCall.getName(), result, duration);

                        if (result.isSuccess()) {
                            LOG.debug("Tool %s completed in %d ms, result length: %d", //$NON-NLS-1$
                                    toolCall.getName(), duration,
                                    result.getContent() != null ? result.getContent().length() : 0);
                        } else {
                            LOG.warn("Tool %s failed in %d ms: %s", //$NON-NLS-1$
                                    toolCall.getName(), duration, result.getErrorMessage());
                        }
                        return result;
                    })
                    .exceptionally(error -> {
                        long duration = System.currentTimeMillis() - startTime;
                        // Log tool call error
                        toolLogger.logToolCallError(callId, toolCall.getName(), error, duration);
                        LOG.error("Tool %s threw exception in %d ms: %s", //$NON-NLS-1$
                                toolCall.getName(), duration, error.getMessage());
                        return ToolResult.failure("Exception: " + error.getMessage()); //$NON-NLS-1$
                    });
        } catch (Exception e) {
            LOG.error("Error executing tool %s: %s", toolCall.getName(), e.getMessage()); //$NON-NLS-1$
            ToolResult failResult = ToolResult.failure("Error executing tool: " + e.getMessage()); //$NON-NLS-1$
            toolLogger.logToolCallResult(-1, toolCall.getName(), failResult, 0);
            return CompletableFuture.completedFuture(failResult);
        }
    }

    /**
     * Parses JSON arguments to a map using Gson.
     *
     * <p>This properly handles multiline strings, escape sequences, and nested objects
     * which is critical for SEARCH/REPLACE edit blocks.</p>
     */
    private Map<String, Object> parseArguments(String json) {
        if (json == null || json.isEmpty() || "{}".equals(json)) { //$NON-NLS-1$
            return Collections.emptyMap();
        }

        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonObject()) {
                LOG.warn("Tool arguments is not a JSON object: %s", json); //$NON-NLS-1$
                return Collections.emptyMap();
            }

            JsonObject obj = element.getAsJsonObject();
            Map<String, Object> result = new HashMap<>();

            for (String key : obj.keySet()) {
                JsonElement value = obj.get(key);
                result.put(key, convertJsonElement(value));
            }

            return result;

        } catch (JsonSyntaxException e) {
            // Truncate large JSON for logging
            String truncatedJson = json.length() > 200 ? json.substring(0, 200) + "..." : json; //$NON-NLS-1$
            LOG.warn("Failed to parse JSON arguments: %s, error: %s", truncatedJson, e.getMessage()); //$NON-NLS-1$
            // Fall back to simple parsing for backwards compatibility
            return parseSimpleJson(json);
        }
    }

    /**
     * Converts a JsonElement to a Java object.
     */
    private Object convertJsonElement(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            var primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            } else if (primitive.isNumber()) {
                // Preserve integer vs double distinction, handling large values
                double d = primitive.getAsDouble();
                if (d == Math.floor(d) && !Double.isInfinite(d)) {
                    // Check if fits in int range
                    if (d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) {
                        return (int) d;
                    }
                    // Use long for larger integers
                    return primitive.getAsLong();
                }
                return d;
            } else {
                return primitive.getAsString();
            }
        }
        if (element.isJsonArray()) {
            var array = element.getAsJsonArray();
            List<Object> list = new ArrayList<>();
            for (JsonElement item : array) {
                list.add(convertJsonElement(item));
            }
            return list;
        }
        if (element.isJsonObject()) {
            var obj = element.getAsJsonObject();
            Map<String, Object> map = new HashMap<>();
            for (String key : obj.keySet()) {
                map.put(key, convertJsonElement(obj.get(key)));
            }
            return map;
        }
        return element.toString();
    }

    /**
     * Simple JSON parser for tool arguments (fallback).
     * Handles basic key-value pairs when Gson parsing fails.
     */
    private Map<String, Object> parseSimpleJson(String json) {
        Map<String, Object> result = new HashMap<>();
        json = json.trim();
        if (json.startsWith("{") && json.endsWith("}")) { //$NON-NLS-1$ //$NON-NLS-2$
            json = json.substring(1, json.length() - 1).trim();
        }

        if (json.isEmpty()) {
            return result;
        }

        // Split by commas not inside quotes
        int depth = 0;
        boolean inString = false;
        StringBuilder current = new StringBuilder();
        List<String> pairs = new ArrayList<>();

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (!inString) {
                if (c == '{' || c == '[') depth++;
                if (c == '}' || c == ']') depth--;
                if (c == ',' && depth == 0) {
                    pairs.add(current.toString().trim());
                    current = new StringBuilder();
                    continue;
                }
            }
            current.append(c);
        }
        if (current.length() > 0) {
            pairs.add(current.toString().trim());
        }

        for (String pair : pairs) {
            int colonIndex = findFirstColonOutsideQuotes(pair);
            if (colonIndex > 0) {
                String key = pair.substring(0, colonIndex).trim();
                String value = pair.substring(colonIndex + 1).trim();

                // Remove quotes from key
                if (key.startsWith("\"") && key.endsWith("\"")) { //$NON-NLS-1$ //$NON-NLS-2$
                    key = key.substring(1, key.length() - 1);
                }

                // Parse value
                if (value.startsWith("\"") && value.endsWith("\"")) { //$NON-NLS-1$ //$NON-NLS-2$
                    // Unescape the string value
                    String unescaped = value.substring(1, value.length() - 1);
                    unescaped = unescaped.replace("\\n", "\n") //$NON-NLS-1$ //$NON-NLS-2$
                            .replace("\\r", "\r") //$NON-NLS-1$ //$NON-NLS-2$
                            .replace("\\t", "\t") //$NON-NLS-1$ //$NON-NLS-2$
                            .replace("\\\"", "\"") //$NON-NLS-1$ //$NON-NLS-2$
                            .replace("\\\\", "\\"); //$NON-NLS-1$ //$NON-NLS-2$
                    result.put(key, unescaped);
                } else if ("true".equals(value)) { //$NON-NLS-1$
                    result.put(key, Boolean.TRUE);
                } else if ("false".equals(value)) { //$NON-NLS-1$
                    result.put(key, Boolean.FALSE);
                } else if ("null".equals(value)) { //$NON-NLS-1$
                    result.put(key, null);
                } else {
                    try {
                        if (value.contains(".")) { //$NON-NLS-1$
                            result.put(key, Double.parseDouble(value));
                        } else {
                            result.put(key, Integer.parseInt(value));
                        }
                    } catch (NumberFormatException e) {
                        result.put(key, value);
                    }
                }
            }
        }

        return result;
    }

    /**
     * Finds the first colon that is not inside a quoted string.
     */
    private int findFirstColonOutsideQuotes(String str) {
        boolean inString = false;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '"' && (i == 0 || str.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (c == ':' && !inString) {
                return i;
            }
        }
        return -1;
    }
}
