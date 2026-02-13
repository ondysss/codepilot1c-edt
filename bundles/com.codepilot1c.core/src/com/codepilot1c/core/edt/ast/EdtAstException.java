package com.codepilot1c.core.edt.ast;

/**
 * Exception for EDT AST service failures.
 */
public class EdtAstException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final EdtAstErrorCode code;
    private final boolean recoverable;

    public EdtAstException(EdtAstErrorCode code, String message, boolean recoverable) {
        super(message);
        this.code = code;
        this.recoverable = recoverable;
    }

    public EdtAstException(EdtAstErrorCode code, String message, boolean recoverable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.recoverable = recoverable;
    }

    public EdtAstErrorCode getCode() {
        return code;
    }

    public boolean isRecoverable() {
        return recoverable;
    }
}
