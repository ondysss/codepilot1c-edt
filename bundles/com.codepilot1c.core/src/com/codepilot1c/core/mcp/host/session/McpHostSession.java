package com.codepilot1c.core.mcp.host.session;

import java.time.Instant;
import java.util.UUID;

public class McpHostSession {

    private final String sessionId = UUID.randomUUID().toString();
    private final Instant createdAt = Instant.now();
    private String protocolVersion;
    private String clientName;
    private String clientVersion;
    private boolean initialized;

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

    public boolean isInitialized() {
        return initialized;
    }

    public void setInitialized(boolean initialized) {
        this.initialized = initialized;
    }
}
