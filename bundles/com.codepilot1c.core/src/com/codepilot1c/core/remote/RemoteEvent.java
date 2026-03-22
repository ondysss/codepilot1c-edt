package com.codepilot1c.core.remote;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured remote event delivered to web companion clients.
 */
public class RemoteEvent {

    private final String type;
    private final String sessionId;
    private final long sequence;
    private final Instant timestamp;
    private final Map<String, Object> payload;

    public RemoteEvent(String type, String sessionId, long sequence, Instant timestamp, Map<String, Object> payload) {
        this.type = type;
        this.sessionId = sessionId;
        this.sequence = sequence;
        this.timestamp = timestamp != null ? timestamp : Instant.now();
        this.payload = payload != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(payload))
                : Collections.emptyMap();
    }

    public String getType() {
        return type;
    }

    public String getSessionId() {
        return sessionId;
    }

    public long getSequence() {
        return sequence;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }
}
