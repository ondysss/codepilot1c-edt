/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.evaluation.trace.AgentTraceSession;
import com.codepilot1c.core.evaluation.trace.TraceEventType;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.model.ToolCall;

/**
 * Executes tool calls with argument parsing, logging, and tracing.
 *
 * <p>Extracted from {@code ToolRegistry} to separate execution concerns
 * from registration and lookup.</p>
 *
 * <p>Usage:</p>
 * <pre>
 * ToolExecutionService executor = new ToolExecutionService(registry);
 * CompletableFuture&lt;ToolResult&gt; result = executor.execute(toolCall);
 * </pre>
 */
public class ToolExecutionService {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(ToolExecutionService.class);

    private final ToolRegistry registry;
    private final ToolArgumentParser argumentParser;

    /**
     * Creates an execution service backed by the given registry.
     *
     * @param registry the tool registry for tool lookup
     */
    public ToolExecutionService(ToolRegistry registry) {
        this.registry = registry;
        this.argumentParser = new ToolArgumentParser();
    }

    /**
     * Executes a tool call without tracing.
     *
     * @param toolCall the tool call to execute
     * @return future with the result
     */
    public CompletableFuture<ToolResult> execute(ToolCall toolCall) {
        return execute(toolCall, null, null);
    }

    /**
     * Executes a tool call with optional tracing.
     *
     * @param toolCall       the tool call to execute
     * @param traceSession   optional trace session (may be null)
     * @param parentEventId  optional parent event ID for trace hierarchy (may be null)
     * @return future with the result
     */
    public CompletableFuture<ToolResult> execute(ToolCall toolCall, AgentTraceSession traceSession,
            String parentEventId) {
        LOG.debug("Executing tool: %s with args: %s", toolCall.getName(), toolCall.getArguments()); //$NON-NLS-1$

        ToolLogger toolLogger = ToolLogger.getInstance();
        Map<String, Object> parameters = Collections.emptyMap();
        try {
            parameters = argumentParser.parseArguments(toolCall.getArguments());
        } catch (Exception e) {
            LOG.warn("Failed to pre-parse tool arguments for trace: %s", e.getMessage()); //$NON-NLS-1$
        }
        // Use getTool() to search both built-in and dynamic tools (MCP)
        ITool tool = registry.getTool(toolCall.getName());
        if (tool == null) {
            LOG.error("Unknown tool: %s (checked %d tools)", //$NON-NLS-1$
                    toolCall.getName(), registry.getAllTools().size());
            ToolResult failResult = ToolResult.failure("Unknown tool: " + toolCall.getName()); //$NON-NLS-1$
            toolLogger.logToolCallResult(-1, toolCall.getName(), failResult, 0);
            String traceToolCallEventId = writeToolCallTrace(traceSession, parentEventId, toolCall, parameters, null);
            writeToolResultTrace(traceSession, traceToolCallEventId, toolCall, failResult, 0, null);
            return CompletableFuture.completedFuture(failResult);
        }

        try {
            parameters = argumentParser.parseArguments(toolCall.getArguments());
            LOG.debug("Parsed parameters: %s", parameters); //$NON-NLS-1$
            final String traceToolCallEventId =
                    writeToolCallTrace(traceSession, parentEventId, toolCall, parameters, tool);

            // Log tool call start
            int callId = toolLogger.logToolCallStart(toolCall.getName(), parameters);
            long startTime = System.currentTimeMillis();

            return tool.execute(parameters)
                    .thenApply(result -> {
                        long duration = System.currentTimeMillis() - startTime;
                        // Log tool call result
                        toolLogger.logToolCallResult(callId, toolCall.getName(), result, duration);
                        writeToolResultTrace(traceSession, traceToolCallEventId, toolCall, result, duration, null);

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
                        writeToolResultTrace(traceSession, traceToolCallEventId, toolCall, null, duration, error);
                        LOG.error("Tool %s threw exception in %d ms: %s", //$NON-NLS-1$
                                toolCall.getName(), duration, error.getMessage());
                        return ToolResult.failure("Exception: " + error.getMessage()); //$NON-NLS-1$
                    });
        } catch (Exception e) {
            LOG.error("Error executing tool %s: %s", toolCall.getName(), e.getMessage()); //$NON-NLS-1$
            ToolResult failResult = ToolResult.failure("Error executing tool: " + e.getMessage()); //$NON-NLS-1$
            toolLogger.logToolCallResult(-1, toolCall.getName(), failResult, 0);
            String traceToolCallEventId = writeToolCallTrace(traceSession, parentEventId, toolCall, parameters, tool);
            writeToolResultTrace(traceSession, traceToolCallEventId, toolCall, failResult, 0, e);
            return CompletableFuture.completedFuture(failResult);
        }
    }

    /**
     * Parses JSON arguments to a parameter map.
     *
     * @param json the JSON arguments string
     * @return parsed parameters (never null)
     */
    public Map<String, Object> parseArguments(String json) {
        return argumentParser.parseArguments(json);
    }

    private String writeToolCallTrace(AgentTraceSession traceSession, String parentEventId, ToolCall toolCall,
            Map<String, Object> parameters, ITool tool) {
        if (traceSession == null) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool_name", toolCall.getName()); //$NON-NLS-1$
        payload.put("call_id", toolCall.getId()); //$NON-NLS-1$
        payload.put("arguments_json", toolCall.getArguments()); //$NON-NLS-1$
        payload.put("parsed_arguments", parameters); //$NON-NLS-1$
        if (tool != null) {
            payload.put("tool_description", tool.getDescription()); //$NON-NLS-1$
            payload.put("requires_confirmation", Boolean.valueOf(tool.requiresConfirmation())); //$NON-NLS-1$
            payload.put("is_destructive", Boolean.valueOf(tool.isDestructive())); //$NON-NLS-1$
        }
        return traceSession.writeToolEvent(TraceEventType.TOOL_CALL, parentEventId, payload);
    }

    private void writeToolResultTrace(AgentTraceSession traceSession, String parentEventId, ToolCall toolCall,
            ToolResult result, long durationMs, Throwable error) {
        if (traceSession == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool_name", toolCall.getName()); //$NON-NLS-1$
        payload.put("call_id", toolCall.getId()); //$NON-NLS-1$
        payload.put("duration_ms", Long.valueOf(durationMs)); //$NON-NLS-1$
        if (result != null) {
            payload.put("success", Boolean.valueOf(result.isSuccess())); //$NON-NLS-1$
            payload.put("result_type", result.getType().name()); //$NON-NLS-1$
            payload.put("content", result.getContent()); //$NON-NLS-1$
            payload.put("error_message", result.getErrorMessage()); //$NON-NLS-1$
        }
        if (error != null) {
            payload.put("exception_type", error.getClass().getSimpleName()); //$NON-NLS-1$
            payload.put("exception_message", error.getMessage()); //$NON-NLS-1$
        }
        traceSession.writeToolEvent(TraceEventType.TOOL_RESULT, parentEventId, payload);
    }
}
