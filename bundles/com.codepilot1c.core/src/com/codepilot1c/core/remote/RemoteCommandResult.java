package com.codepilot1c.core.remote;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured result for remote commands and API calls.
 */
public class RemoteCommandResult {

    private final boolean ok;
    private final String code;
    private final String message;
    private final Map<String, Object> payload;

    private RemoteCommandResult(boolean ok, String code, String message, Map<String, Object> payload) {
        this.ok = ok;
        this.code = code;
        this.message = message;
        this.payload = payload != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(payload))
                : Collections.emptyMap();
    }

    public static RemoteCommandResult ok(String message) {
        return ok(message, Map.of());
    }

    public static RemoteCommandResult ok(String message, Map<String, Object> payload) {
        return new RemoteCommandResult(true, "ok", message, payload); //$NON-NLS-1$
    }

    public static RemoteCommandResult accepted(String code, String message, Map<String, Object> payload) {
        return new RemoteCommandResult(true, code != null ? code : "accepted", message, payload); //$NON-NLS-1$
    }

    public static RemoteCommandResult error(String code, String message) {
        return error(code, message, Map.of());
    }

    public static RemoteCommandResult error(String code, String message, Map<String, Object> payload) {
        return new RemoteCommandResult(false, code != null ? code : "error", message, payload); //$NON-NLS-1$
    }

    public boolean isOk() {
        return ok;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }
}
