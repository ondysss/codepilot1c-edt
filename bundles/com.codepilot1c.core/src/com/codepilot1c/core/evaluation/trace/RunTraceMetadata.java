package com.codepilot1c.core.evaluation.trace;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Metadata for a single trace run persisted in run.json.
 */
public class RunTraceMetadata {

    private final String runId;
    private final String sessionId;
    private final String traceKind;
    private final Instant startedAt;

    private String status;
    private Instant completedAt;
    private String profileName;
    private String providerId;
    private String providerDisplayName;
    private String transport;
    private String clientName;
    private String clientVersion;
    private boolean streamingEnabled;
    private int maxSteps;
    private String errorMessage;
    private final Map<String, Object> attributes = new LinkedHashMap<>();

    public RunTraceMetadata(String runId, String sessionId, String traceKind, Instant startedAt) {
        this.runId = runId;
        this.sessionId = sessionId;
        this.traceKind = traceKind;
        this.startedAt = startedAt != null ? startedAt : Instant.now();
        this.status = "RUNNING"; //$NON-NLS-1$
    }

    public String getRunId() {
        return runId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getTraceKind() {
        return traceKind;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getProfileName() {
        return profileName;
    }

    public void setProfileName(String profileName) {
        this.profileName = profileName;
    }

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public String getProviderDisplayName() {
        return providerDisplayName;
    }

    public void setProviderDisplayName(String providerDisplayName) {
        this.providerDisplayName = providerDisplayName;
    }

    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = transport;
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

    public boolean isStreamingEnabled() {
        return streamingEnabled;
    }

    public void setStreamingEnabled(boolean streamingEnabled) {
        this.streamingEnabled = streamingEnabled;
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public void setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Map<String, Object> getAttributes() {
        return Collections.unmodifiableMap(attributes);
    }

    public void putAttribute(String key, Object value) {
        if (key == null || key.isBlank()) {
            return;
        }
        attributes.put(key, value);
    }
}
