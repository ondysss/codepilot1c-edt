package com.codepilot1c.core.edt.runtime;

/**
 * Structured runtime tool error with a stable code.
 */
public class EdtToolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final EdtToolErrorCode code;

    public EdtToolException(EdtToolErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public EdtToolException(EdtToolErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public EdtToolErrorCode getCode() {
        return code;
    }
}
