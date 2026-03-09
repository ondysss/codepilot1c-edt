package com.codepilot1c.core.evaluation.trace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.model.LlmRequest;
import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.model.LlmStreamChunk;
import com.codepilot1c.core.model.ToolCall;
import com.codepilot1c.core.model.ToolDefinition;
import com.codepilot1c.core.provider.ILlmProvider;

/**
 * Decorates an LLM provider with structured trace capture.
 */
public class TracingLlmProvider implements ILlmProvider {

    private final ILlmProvider delegate;
    private final AgentTraceSession traceSession;

    public TracingLlmProvider(ILlmProvider delegate, AgentTraceSession traceSession) {
        this.delegate = delegate;
        this.traceSession = traceSession;
    }

    @Override
    public String getId() {
        return delegate.getId();
    }

    @Override
    public String getDisplayName() {
        return delegate.getDisplayName();
    }

    @Override
    public boolean isConfigured() {
        return delegate.isConfigured();
    }

    @Override
    public boolean supportsStreaming() {
        return delegate.supportsStreaming();
    }

    @Override
    public CompletableFuture<LlmResponse> complete(LlmRequest request) {
        String requestEventId = traceSession != null
                ? traceSession.writeLlmEvent(TraceEventType.LLM_REQUEST, null, serializeRequest(request))
                : null;
        return delegate.complete(request).whenComplete((response, error) -> {
            if (traceSession == null) {
                return;
            }
            if (error != null) {
                traceSession.writeLlmEvent(TraceEventType.LLM_RESPONSE, requestEventId,
                        Map.of("status", "ERROR", "error_message", safeMessage(error))); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            traceSession.writeLlmEvent(TraceEventType.LLM_RESPONSE, requestEventId, serializeResponse(response));
        });
    }

    @Override
    public void streamComplete(LlmRequest request, Consumer<LlmStreamChunk> consumer) {
        String requestEventId = traceSession != null
                ? traceSession.writeLlmEvent(TraceEventType.LLM_REQUEST, null, serializeRequest(request))
                : null;

        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();

        Consumer<LlmStreamChunk> tracingConsumer = chunk -> {
            if (traceSession != null) {
                traceSession.writeLlmEvent(TraceEventType.LLM_STREAM_CHUNK, requestEventId, serializeChunk(chunk));
            }

            if (chunk != null) {
                if (chunk.getContent() != null && !chunk.getContent().isEmpty()) {
                    content.append(chunk.getContent());
                }
                if (chunk.hasReasoning()) {
                    reasoning.append(chunk.getReasoningContent());
                }
                if (chunk.hasToolCalls()) {
                    toolCalls.addAll(chunk.getToolCalls());
                }
                if (chunk.isComplete() && traceSession != null) {
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("status", "OK"); //$NON-NLS-1$ //$NON-NLS-2$
                    response.put("content", content.toString()); //$NON-NLS-1$
                    response.put("finish_reason", chunk.getFinishReason()); //$NON-NLS-1$
                    response.put("tool_calls", serializeToolCalls(toolCalls)); //$NON-NLS-1$
                    if (reasoning.length() > 0) {
                        response.put("reasoning_content", reasoning.toString()); //$NON-NLS-1$
                    }
                    traceSession.writeLlmEvent(TraceEventType.LLM_RESPONSE, requestEventId, response);
                } else if (chunk.getErrorMessage() != null && traceSession != null) {
                    traceSession.writeLlmEvent(TraceEventType.LLM_RESPONSE, requestEventId,
                            Map.of("status", "ERROR", "error_message", chunk.getErrorMessage())); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            consumer.accept(chunk);
        };

        try {
            delegate.streamComplete(request, tracingConsumer);
        } catch (RuntimeException e) {
            if (traceSession != null) {
                traceSession.writeLlmEvent(TraceEventType.LLM_RESPONSE, requestEventId,
                        Map.of("status", "ERROR", "error_message", safeMessage(e))); //$NON-NLS-1$ //$NON-NLS-2$
            }
            throw e;
        }
    }

    @Override
    public void cancel() {
        delegate.cancel();
    }

    @Override
    public void dispose() {
        delegate.dispose();
    }

    private Map<String, Object> serializeRequest(LlmRequest request) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (request == null) {
            return out;
        }
        out.put("provider_id", getId()); //$NON-NLS-1$
        out.put("provider_display_name", getDisplayName()); //$NON-NLS-1$
        out.put("model", request.getModel()); //$NON-NLS-1$
        out.put("max_tokens", Integer.valueOf(request.getMaxTokens())); //$NON-NLS-1$
        out.put("temperature", Double.valueOf(request.getTemperature())); //$NON-NLS-1$
        out.put("stream", Boolean.valueOf(request.isStream())); //$NON-NLS-1$
        out.put("tool_choice", request.getToolChoice() != null ? request.getToolChoice().name() : null); //$NON-NLS-1$
        out.put("messages", serializeMessages(request.getMessages())); //$NON-NLS-1$
        out.put("tool_count", Integer.valueOf(request.hasTools() ? request.getTools().size() : 0)); //$NON-NLS-1$
        out.put("tools", serializeToolDefinitions(request.getTools())); //$NON-NLS-1$
        return out;
    }

    private Map<String, Object> serializeResponse(LlmResponse response) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (response == null) {
            return out;
        }
        out.put("status", "OK"); //$NON-NLS-1$ //$NON-NLS-2$
        out.put("model", response.getModel()); //$NON-NLS-1$
        out.put("content", response.getContent()); //$NON-NLS-1$
        out.put("finish_reason", response.getFinishReason()); //$NON-NLS-1$
        out.put("tool_calls", serializeToolCalls(response.getToolCalls())); //$NON-NLS-1$
        if (response.getUsage() != null) {
            Map<String, Object> usage = new LinkedHashMap<>();
            usage.put("prompt_tokens", Integer.valueOf(response.getUsage().getPromptTokens())); //$NON-NLS-1$
            usage.put("cached_prompt_tokens", Integer.valueOf(response.getUsage().getCachedPromptTokens())); //$NON-NLS-1$
            usage.put("completion_tokens", Integer.valueOf(response.getUsage().getCompletionTokens())); //$NON-NLS-1$
            usage.put("total_tokens", Integer.valueOf(response.getUsage().getTotalTokens())); //$NON-NLS-1$
            out.put("usage", usage); //$NON-NLS-1$
        }
        if (response.hasReasoning()) {
            out.put("reasoning_content", response.getReasoningContent()); //$NON-NLS-1$
        }
        return out;
    }

    private Map<String, Object> serializeChunk(LlmStreamChunk chunk) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (chunk == null) {
            return out;
        }
        out.put("content", chunk.getContent()); //$NON-NLS-1$
        out.put("is_complete", Boolean.valueOf(chunk.isComplete())); //$NON-NLS-1$
        out.put("finish_reason", chunk.getFinishReason()); //$NON-NLS-1$
        out.put("error_message", chunk.getErrorMessage()); //$NON-NLS-1$
        out.put("tool_calls", serializeToolCalls(chunk.getToolCalls())); //$NON-NLS-1$
        if (chunk.hasReasoning()) {
            out.put("reasoning_content", chunk.getReasoningContent()); //$NON-NLS-1$
        }
        return out;
    }

    private List<Map<String, Object>> serializeMessages(List<LlmMessage> messages) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (messages == null) {
            return out;
        }
        for (LlmMessage message : messages) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", message.getRole().getValue()); //$NON-NLS-1$
            item.put("content", message.getContent()); //$NON-NLS-1$
            item.put("tool_call_id", message.getToolCallId()); //$NON-NLS-1$
            item.put("tool_calls", serializeToolCalls(message.getToolCalls())); //$NON-NLS-1$
            out.add(item);
        }
        return out;
    }

    private List<Map<String, Object>> serializeToolDefinitions(List<ToolDefinition> tools) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (tools == null) {
            return out;
        }
        for (ToolDefinition tool : tools) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", tool.getName()); //$NON-NLS-1$
            item.put("description", tool.getDescription()); //$NON-NLS-1$
            item.put("parameter_schema", tool.getParametersSchema()); //$NON-NLS-1$
            out.add(item);
        }
        return out;
    }

    private List<Map<String, Object>> serializeToolCalls(List<ToolCall> toolCalls) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (toolCalls == null) {
            return out;
        }
        for (ToolCall toolCall : toolCalls) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", toolCall.getId()); //$NON-NLS-1$
            item.put("name", toolCall.getName()); //$NON-NLS-1$
            item.put("arguments", toolCall.getArguments()); //$NON-NLS-1$
            out.add(item);
        }
        return out;
    }

    private String safeMessage(Throwable error) {
        return error != null && error.getMessage() != null ? error.getMessage() : String.valueOf(error);
    }
}
