package com.codepilot1c.core.edt.platformdoc;

/**
 * Exception for platform documentation operations.
 */
public class PlatformDocumentationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final PlatformDocumentationErrorCode code;
    private final boolean recoverable;

    public PlatformDocumentationException(PlatformDocumentationErrorCode code, String message, boolean recoverable) {
        super(message);
        this.code = code;
        this.recoverable = recoverable;
    }

    public PlatformDocumentationException(
            PlatformDocumentationErrorCode code,
            String message,
            boolean recoverable,
            Throwable cause) {
        super(message, cause);
        this.code = code;
        this.recoverable = recoverable;
    }

    public PlatformDocumentationErrorCode getCode() {
        return code;
    }

    public boolean isRecoverable() {
        return recoverable;
    }
}
