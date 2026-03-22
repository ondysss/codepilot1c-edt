package com.codepilot1c.core.remote;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured remote command request DTO.
 */
public class RemoteCommandRequest {

    private String clientRequestId;
    private String kind;
    private Map<String, Object> payload = Collections.emptyMap();

    public String getClientRequestId() {
        return clientRequestId;
    }

    public void setClientRequestId(String clientRequestId) {
        this.clientRequestId = clientRequestId;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload != null
                ? new LinkedHashMap<>(payload)
                : Collections.emptyMap();
    }
}
