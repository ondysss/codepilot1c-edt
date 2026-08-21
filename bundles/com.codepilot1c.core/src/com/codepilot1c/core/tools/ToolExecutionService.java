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
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.evaluation.trace.AgentTraceSession;
import com.codepilot1c.core.evaluation.trace.TraceEventType;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.model.ToolCall;
import com.codepilot1c.core.tools.ToolRegistry.ToolResolution;

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
            toolLogger.logToolCallResult(-1, toolCall.getName(), failResult, 0, false);
            String traceToolCallEventId = writeToolCallTrace(traceSession, parentEventId, toolCall, parameters, null);
            writeToolResultTrace(traceSession, traceToolCallEventId, toolCall, failResult, 0, null, false);
            return CompletableFuture.completedFuture(failResult);
        }

        boolean sensitive = isSensitive(tool);

        Optional<ToolResult> repairedRejection = rejectRepairedMutatingCall(tool, toolCall);
        if (repairedRejection.isPresent()) {
            ToolResult failResult = repairedRejection.get();
            LOG.warn("Blocking mutating tool %s: arguments were repaired from a truncated payload", //$NON-NLS-1$
                    toolCall.getName());
            toolLogger.logToolCallResult(-1, toolCall.getName(), failResult, 0, sensitive);
            String traceToolCallEventId = writeToolCallTrace(traceSession, parentEventId, toolCall, parameters, tool);
            writeToolResultTrace(traceSession, traceToolCallEventId, toolCall, failResult, 0, null, sensitive);
            return CompletableFuture.completedFuture(failResult);
        }

        try {
            parameters = argumentParser.parseArguments(toolCall.getArguments());
            return executeParsed(toolCall, parameters, tool, toolLogger, traceSession, parentEventId,
                    ToolExecutionContext.unscoped(), sensitive);
        } catch (Exception e) {
            if (sensitive) {
                LOG.error("Error executing tool %s: [omitted: sensitive tool]", toolCall.getName()); //$NON-NLS-1$
            } else {
                LOG.error("Error executing tool %s: %s", toolCall.getName(), e.getMessage()); //$NON-NLS-1$
            }
            ToolResult failResult = ToolResult.failure("Error executing tool: " + e.getMessage()); //$NON-NLS-1$
            toolLogger.logToolCallResult(-1, toolCall.getName(), failResult, 0, sensitive);
            String traceToolCallEventId = writeToolCallTrace(traceSession, parentEventId, toolCall, parameters, tool);
            writeToolResultTrace(traceSession, traceToolCallEventId, toolCall, failResult, 0, e, sensitive);
            return CompletableFuture.completedFuture(failResult);
        }
    }

    /**
     * Executes a tool call with an exact parameter map already approved by a
     * caller-side permission gate. The raw JSON is retained only for logging
     * and tracing and is not parsed again.
     *
     * @param toolCall the original tool call
     * @param parameters the exact parsed parameters to execute
     * @param traceSession optional trace session
     * @param parentEventId optional parent trace event
     * @return future with the result
     */
    public CompletableFuture<ToolResult> execute(ToolCall toolCall, Map<String, Object> parameters,
            AgentTraceSession traceSession, String parentEventId) {
        return execute(toolCall, parameters, traceSession, parentEventId,
                ToolExecutionContext.unscoped());
    }

    /**
     * Executes an already parsed tool call with immutable caller context.
     *
     * @param toolCall original tool call
     * @param parameters exact parsed parameters approved by the permission gate
     * @param traceSession optional trace session
     * @param parentEventId optional parent trace event
     * @param context immutable caller context
     * @return future with the result
     */
    public CompletableFuture<ToolResult> execute(ToolCall toolCall, Map<String, Object> parameters,
            AgentTraceSession traceSession, String parentEventId, ToolExecutionContext context) {
        LOG.debug("Executing tool with pre-parsed args: %s", toolCall.getName()); //$NON-NLS-1$

        Map<String, Object> approvedParameters = parameters != null
                ? parameters
                : Collections.emptyMap();
        ToolLogger toolLogger = ToolLogger.getInstance();
        ITool tool = registry.getTool(toolCall.getName());
        if (tool == null) {
            LOG.error("Unknown tool: %s (checked %d tools)", //$NON-NLS-1$
                    toolCall.getName(), registry.getAllTools().size());
            ToolResult failResult = ToolResult.failure("Unknown tool: " + toolCall.getName()); //$NON-NLS-1$
            toolLogger.logToolCallResult(-1, toolCall.getName(), failResult, 0, false);
            String traceToolCallEventId = writeToolCallTrace(
                    traceSession, parentEventId, toolCall, approvedParameters, null);
            writeToolResultTrace(traceSession, traceToolCallEventId, toolCall, failResult, 0, null, false);
            return CompletableFuture.completedFuture(failResult);
        }

        return executeResolved(toolCall, approvedParameters, traceSession, parentEventId,
                context, tool);
    }

    /**
     * Executes the exact registry implementation authorized by the caller if
     * its effective implementation, capability, and generation are unchanged.
     * No lookup by name occurs after validation.
     *
     * @return empty when the authorized registry resolution is stale
     */
    public Optional<CompletableFuture<ToolResult>> executeIfCurrent(
            ToolCall toolCall, Map<String, Object> parameters,
            AgentTraceSession traceSession, String parentEventId,
            ToolExecutionContext context, ToolResolution resolution) {
        if (toolCall == null || resolution == null
                || !Objects.equals(toolCall.getName(), resolution.name())) {
            return Optional.empty();
        }
        Map<String, Object> approvedParameters = parameters != null
                ? parameters
                : Collections.emptyMap();
        return registry.dispatchIfCurrent(resolution, () -> {
            LOG.debug("Executing exact tool with pre-parsed args: %s", toolCall.getName()); //$NON-NLS-1$
            return executeResolved(toolCall, approvedParameters, traceSession, parentEventId,
                    context, resolution.tool());
        });
    }

    private CompletableFuture<ToolResult> executeResolved(
            ToolCall toolCall, Map<String, Object> approvedParameters,
            AgentTraceSession traceSession, String parentEventId,
            ToolExecutionContext context, ITool tool) {
        ToolLogger toolLogger = ToolLogger.getInstance();

        boolean sensitive = isSensitive(tool);

        Optional<ToolResult> repairedRejection = rejectRepairedMutatingCall(tool, toolCall);
        if (repairedRejection.isPresent()) {
            ToolResult failResult = repairedRejection.get();
            LOG.warn("Blocking mutating tool %s: arguments were repaired from a truncated payload", //$NON-NLS-1$
                    toolCall.getName());
            toolLogger.logToolCallResult(-1, toolCall.getName(), failResult, 0, sensitive);
            String traceToolCallEventId = writeToolCallTrace(
                    traceSession, parentEventId, toolCall, approvedParameters, tool);
            writeToolResultTrace(traceSession, traceToolCallEventId, toolCall, failResult, 0, null, sensitive);
            return CompletableFuture.completedFuture(failResult);
        }

        try {
            return executeParsed(toolCall, approvedParameters, tool, toolLogger,
                    traceSession, parentEventId, context != null ? context : ToolExecutionContext.unscoped(),
                    sensitive);
        } catch (Exception e) {
            if (sensitive) {
                LOG.error("Error executing tool %s: [omitted: sensitive tool]", toolCall.getName()); //$NON-NLS-1$
            } else {
                LOG.error("Error executing tool %s: %s", toolCall.getName(), e.getMessage()); //$NON-NLS-1$
            }
            ToolResult failResult = ToolResult.failure("Error executing tool: " + e.getMessage()); //$NON-NLS-1$
            toolLogger.logToolCallResult(-1, toolCall.getName(), failResult, 0, sensitive);
            String traceToolCallEventId = writeToolCallTrace(
                    traceSession, parentEventId, toolCall, approvedParameters, tool);
            writeToolResultTrace(traceSession, traceToolCallEventId, toolCall, failResult, 0, e, sensitive);
            return CompletableFuture.completedFuture(failResult);
        }
    }

    private CompletableFuture<ToolResult> executeParsed(
            ToolCall toolCall, Map<String, Object> parameters, ITool tool,
            ToolLogger toolLogger, AgentTraceSession traceSession, String parentEventId,
            ToolExecutionContext context, boolean sensitive) {
        LOG.debug("Parsed parameters: %s", parameters); //$NON-NLS-1$
        final String traceToolCallEventId =
                writeToolCallTrace(traceSession, parentEventId, toolCall, parameters, tool);

        int callId = toolLogger.logToolCallStart(toolCall.getName(), parameters);
        long startTime = System.currentTimeMillis();

        return tool.execute(parameters, context)
                .thenApply(result -> {
                    long duration = System.currentTimeMillis() - startTime;
                    toolLogger.logToolCallResult(callId, toolCall.getName(), result, duration, sensitive);
                    writeToolResultTrace(traceSession, traceToolCallEventId, toolCall, result, duration, null,
                            sensitive);

                    if (result.isSuccess()) {
                        LOG.debug("Tool %s completed in %d ms, result length: %d", //$NON-NLS-1$
                                toolCall.getName(), duration,
                                result.getContent() != null ? result.getContent().length() : 0);
                    } else if (sensitive) {
                        LOG.warn("Tool %s failed in %d ms: [omitted: sensitive tool]", //$NON-NLS-1$
                                toolCall.getName(), duration);
                    } else {
                        LOG.warn("Tool %s failed in %d ms: %s", //$NON-NLS-1$
                                toolCall.getName(), duration, result.getErrorMessage());
                    }
                    return result;
                })
                .exceptionally(error -> {
                    long duration = System.currentTimeMillis() - startTime;
                    toolLogger.logToolCallError(callId, toolCall.getName(), error, duration, sensitive);
                    writeToolResultTrace(traceSession, traceToolCallEventId, toolCall, null, duration, error,
                            sensitive);
                    if (sensitive) {
                        LOG.error("Tool %s threw exception in %d ms: [omitted: sensitive tool]", //$NON-NLS-1$
                                toolCall.getName(), duration);
                    } else {
                        LOG.error("Tool %s threw exception in %d ms: %s", //$NON-NLS-1$
                                toolCall.getName(), duration, error.getMessage());
                    }
                    return ToolResult.failure("Exception: " + error.getMessage()); //$NON-NLS-1$
                });
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

    /**
     * Blocks mutating tools when the tool-call arguments were repaired from a
     * truncated or malformed payload: executing such a call risks writing
     * incomplete content (e.g. wiping a file with a truncated 'content').
     *
     * @param tool the resolved tool (may be null)
     * @param toolCall the incoming tool call
     * @return failure result if the call must be rejected, empty otherwise
     */
    static Optional<ToolResult> rejectRepairedMutatingCall(ITool tool, ToolCall toolCall) {
        if (tool == null || !tool.isMutating() || !toolCall.isArgumentsRepaired()) {
            return Optional.empty();
        }
        return Optional.of(ToolResult.failure(
                "Tool call arguments for '" + toolCall.getName() //$NON-NLS-1$
                + "' were repaired after a truncated or malformed stream and may be incomplete. " //$NON-NLS-1$
                + "Mutating execution was blocked to prevent data loss. " //$NON-NLS-1$
                + "Re-issue the tool call; prefer smaller targeted edits " //$NON-NLS-1$
                + "(old_text/new_text or SEARCH/REPLACE blocks) over full-content payloads.")); //$NON-NLS-1$
    }

    static boolean isSensitive(ITool tool) {
        return tool != null && tool.getTags() != null && tool.getTags().contains("sensitive"); //$NON-NLS-1$
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
            ToolResult result, long durationMs, Throwable error, boolean sensitive) {
        if (traceSession == null) {
            return;
        }
        traceSession.writeToolEvent(TraceEventType.TOOL_RESULT, parentEventId,
                buildToolResultTracePayload(toolCall, result, durationMs, error, sensitive));
    }

    static Map<String, Object> buildToolResultTracePayload(ToolCall toolCall, ToolResult result,
            long durationMs, Throwable error, boolean sensitive) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool_name", toolCall.getName()); //$NON-NLS-1$
        payload.put("call_id", toolCall.getId()); //$NON-NLS-1$
        payload.put("duration_ms", Long.valueOf(durationMs)); //$NON-NLS-1$
        if (result != null) {
            payload.put("success", Boolean.valueOf(result.isSuccess())); //$NON-NLS-1$
            payload.put("result_type", result.getType().name()); //$NON-NLS-1$
            if (sensitive) {
                String resultText = result.isSuccess() ? result.getContent() : result.getErrorMessage();
                payload.put("content_omitted", Boolean.TRUE); //$NON-NLS-1$
                payload.put("content_length", Integer.valueOf(resultText == null ? 0 : resultText.length())); //$NON-NLS-1$
            } else {
                payload.put("content", result.getContent()); //$NON-NLS-1$
                payload.put("error_message", result.getErrorMessage()); //$NON-NLS-1$
            }
        }
        if (error != null) {
            if (sensitive) {
                payload.put("content_omitted", Boolean.TRUE); //$NON-NLS-1$
                payload.putIfAbsent("content_length", //$NON-NLS-1$
                        Integer.valueOf(error.getMessage() == null ? 0 : error.getMessage().length()));
            } else {
                payload.put("exception_type", error.getClass().getSimpleName()); //$NON-NLS-1$
                payload.put("exception_message", error.getMessage()); //$NON-NLS-1$
            }
        }
        return payload;
    }
}
