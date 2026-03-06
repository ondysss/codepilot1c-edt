package com.codepilot1c.core.evaluation.trace;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.codepilot1c.core.agent.AgentConfig;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.provider.ILlmProvider;

/**
 * Runtime context for a single trace run.
 */
public class AgentTraceSession {

    public static final String PROP_TRACE_ENABLED = "codepilot1c.agent.trace.enabled"; //$NON-NLS-1$

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(AgentTraceSession.class);

    private final RunTraceMetadata metadata;
    private final TraceWriter writer;
    private final AtomicBoolean completed = new AtomicBoolean(false);

    private AgentTraceSession(RunTraceMetadata metadata, TraceWriter writer) {
        this.metadata = metadata;
        this.writer = writer;
    }

    public static boolean isTracingEnabled() {
        String raw = System.getProperty(PROP_TRACE_ENABLED);
        if (raw == null || raw.isBlank()) {
            return true;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    public static AgentTraceSession startAgentRun(AgentConfig config, ILlmProvider provider,
            String userPrompt, String appliedSystemPrompt) {
        if (!isTracingEnabled()) {
            return null;
        }
        return createSession("agent_run", LogSanitizer.newId("agent"), metadata -> { //$NON-NLS-1$ //$NON-NLS-2$
            if (config != null) {
                metadata.setProfileName(config.getProfileName());
                metadata.setStreamingEnabled(config.isStreamingEnabled());
                metadata.setMaxSteps(config.getMaxSteps());
            }
            if (provider != null) {
                metadata.setProviderId(provider.getId());
                metadata.setProviderDisplayName(provider.getDisplayName());
            }
            metadata.putAttribute("user_prompt", userPrompt); //$NON-NLS-1$
            metadata.putAttribute("user_prompt_length", Integer.valueOf(userPrompt != null ? userPrompt.length() : 0)); //$NON-NLS-1$
            metadata.putAttribute("system_prompt", appliedSystemPrompt); //$NON-NLS-1$
            metadata.putAttribute("system_prompt_length", Integer.valueOf(appliedSystemPrompt != null ? appliedSystemPrompt.length() : 0)); //$NON-NLS-1$
        });
    }

    public static AgentTraceSession startMcpSession(String sessionId, String transport,
            String remoteAddress, String requestPath) {
        if (!isTracingEnabled()) {
            return null;
        }
        return createSession("mcp_host", sessionId, metadata -> { //$NON-NLS-1$
            metadata.setTransport(transport);
            metadata.putAttribute("remote_address", remoteAddress); //$NON-NLS-1$
            metadata.putAttribute("request_path", requestPath); //$NON-NLS-1$
        });
    }

    private static AgentTraceSession createSession(String traceKind, String sessionId,
            java.util.function.Consumer<RunTraceMetadata> customizer) {
        String runId = LogSanitizer.newId("run"); //$NON-NLS-1$
        try {
            ArtifactLayout layout = ArtifactLayout.create(runId);
            RunTraceMetadata metadata = new RunTraceMetadata(runId, sessionId, traceKind, Instant.now());
            if (customizer != null) {
                customizer.accept(metadata);
            }
            TraceWriter writer = new TraceWriter(layout);
            writer.writeRunMetadata(metadata);
            return new AgentTraceSession(metadata, writer);
        } catch (IOException e) {
            LOG.error("Failed to initialize trace session", e); //$NON-NLS-1$
            return null;
        }
    }

    public String getRunId() {
        return metadata.getRunId();
    }

    public String getSessionId() {
        return metadata.getSessionId();
    }

    public TraceWriter getWriter() {
        return writer;
    }

    public ArtifactLayout getLayout() {
        return writer.getLayout();
    }

    public RunTraceMetadata getMetadata() {
        return metadata;
    }

    public String nextEventId(String prefix) {
        return LogSanitizer.newId(prefix);
    }

    public String writeAgentEvent(TraceEventType type, String parentEventId, Map<String, Object> data) {
        return write("events", type, parentEventId, data); //$NON-NLS-1$
    }

    public String writeLlmEvent(TraceEventType type, String parentEventId, Map<String, Object> data) {
        return write("llm", type, parentEventId, data); //$NON-NLS-1$
    }

    public String writeToolEvent(TraceEventType type, String parentEventId, Map<String, Object> data) {
        return write("tools", type, parentEventId, data); //$NON-NLS-1$
    }

    public String writeMcpEvent(TraceEventType type, String parentEventId, Map<String, Object> data) {
        return write("mcp", type, parentEventId, data); //$NON-NLS-1$
    }

    private String write(String channel, TraceEventType type, String parentEventId, Map<String, Object> data) {
        String eventId = nextEventId(channel);
        TraceEvent event = new TraceEvent(
                eventId,
                parentEventId,
                metadata.getRunId(),
                metadata.getSessionId(),
                channel,
                type,
                Instant.now(),
                data != null ? new LinkedHashMap<>(data) : Map.of());
        switch (channel) {
            case "llm" -> writer.appendLlm(event); //$NON-NLS-1$
            case "tools" -> writer.appendTools(event); //$NON-NLS-1$
            case "mcp" -> writer.appendMcp(event); //$NON-NLS-1$
            case "events" -> writer.appendEvents(event); //$NON-NLS-1$
            default -> writer.appendEvents(event);
        }
        return eventId;
    }

    public void markCompleted(String status, String errorMessage) {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        metadata.setStatus(status != null ? status : "COMPLETED"); //$NON-NLS-1$
        metadata.setCompletedAt(Instant.now());
        metadata.setErrorMessage(errorMessage);
        writer.writeRunMetadata(metadata);
    }
}
