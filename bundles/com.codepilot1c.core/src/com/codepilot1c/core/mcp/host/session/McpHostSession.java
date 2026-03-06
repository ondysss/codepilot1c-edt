package com.codepilot1c.core.mcp.host.session;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;

import com.codepilot1c.core.evaluation.trace.AgentTraceSession;

public class McpHostSession {

    private final String sessionId;
    private final Instant createdAt = Instant.now();
    private final AtomicLong requestCount = new AtomicLong(0);
    private String protocolVersion;
    private String clientName;
    private String clientVersion;
    private String transport;
    private String remoteAddress;
    private String lastRequestPath;
    private boolean initialized;
    private AgentTraceSession traceSession;

    public McpHostSession() {
        this(UUID.randomUUID().toString());
    }

    public McpHostSession(String sessionId) {
        this.sessionId = sessionId != null && !sessionId.isBlank() ? sessionId : UUID.randomUUID().toString();
    }

    public String getSessionId() {
        return sessionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public String getClientName() {
        return clientName;
    }

    public void setClientName(String clientName) {
        this.clientName = clientName;
    }

    public String getClientVersion() {
        return clientVersion;
    }

    public void setClientVersion(String clientVersion) {
        this.clientVersion = clientVersion;
    }

    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }

    public String getRemoteAddress() {
        return remoteAddress;
    }

    public void setRemoteAddress(String remoteAddress) {
        this.remoteAddress = remoteAddress;
    }

    public String getLastRequestPath() {
        return lastRequestPath;
    }

    public void setLastRequestPath(String lastRequestPath) {
        this.lastRequestPath = lastRequestPath;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void setInitialized(boolean initialized) {
        this.initialized = initialized;
    }

    public AgentTraceSession getTraceSession() {
        return traceSession;
    }

    public void setTraceSession(AgentTraceSession traceSession) {
        this.traceSession = traceSession;
    }

    public long incrementRequestCount() {
        return requestCount.incrementAndGet();
    }

    public long getRequestCount() {
        return requestCount.get();
    }
}
