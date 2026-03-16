package com.codepilot1c.core.provider.config;

import com.google.gson.JsonObject;

/**
 * Provider execution plan resolved before an LLM request is sent.
 */
final class ProviderExecutionPlan {

    private final boolean streaming;
    private final JsonObject requestOverrides;
    private final String reason;

    private ProviderExecutionPlan(boolean streaming, JsonObject requestOverrides, String reason) {
        this.streaming = streaming;
        this.requestOverrides = requestOverrides != null ? requestOverrides : new JsonObject();
        this.reason = reason;
    }

    static ProviderExecutionPlan streaming(boolean streaming) {
        return new ProviderExecutionPlan(streaming, new JsonObject(), null);
    }

    static ProviderExecutionPlan of(boolean streaming, JsonObject requestOverrides, String reason) {
        return new ProviderExecutionPlan(streaming, requestOverrides, reason);
    }

    boolean isStreaming() {
        return streaming;
    }

    JsonObject getRequestOverrides() {
        return requestOverrides;
    }

    String getReason() {
        return reason;
    }
}
