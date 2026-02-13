package com.codepilot1c.core.edt.metadata;

/**
 * Runtime exception for metadata operations in EDT BM.
 */
public class MetadataOperationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final MetadataOperationCode code;
    private final boolean recoverable;

    public MetadataOperationException(MetadataOperationCode code, String message, boolean recoverable) {
        super(message);
        this.code = code;
        this.recoverable = recoverable;
    }

    public MetadataOperationException(
            MetadataOperationCode code,
            String message,
            boolean recoverable,
            Throwable cause
    ) {
        super(message, cause);
        this.code = code;
        this.recoverable = recoverable;
    }

    public MetadataOperationCode getCode() {
        return code;
    }

    public boolean isRecoverable() {
        return recoverable;
    }
}
