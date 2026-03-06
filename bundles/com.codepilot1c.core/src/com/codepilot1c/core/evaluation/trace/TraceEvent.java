package com.codepilot1c.core.evaluation.trace;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * Structured trace event written to JSONL files.
 */
public record TraceEvent(
        String eventId,
        String parentEventId,
        String runId,
        String sessionId,
        String channel,
        TraceEventType type,
        Instant timestamp,
        Map<String, Object> data) {

    public TraceEvent {
        data = data == null ? Collections.emptyMap() : Collections.unmodifiableMap(data);
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }
}
