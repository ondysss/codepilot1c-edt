package com.codepilot1c.core.edt.debug;

public class EdtDebugException extends RuntimeException {
    
    private final EdtDebugErrorCode code;
    private final boolean recoverable;
    
    public EdtDebugException(EdtDebugErrorCode code, String message, boolean recoverable) {
        super(message);
        this.code = code;
        this.recoverable = recoverable;
    }
    
    public EdtDebugErrorCode getCode() {
        return code;
    }
    
    public boolean isRecoverable() {
        return recoverable;
    }
}
