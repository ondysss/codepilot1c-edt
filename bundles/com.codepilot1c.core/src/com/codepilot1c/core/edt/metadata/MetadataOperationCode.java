package com.codepilot1c.core.edt.metadata;

/**
 * Error codes for metadata creation/edit operations.
 */
public enum MetadataOperationCode {
    PROJECT_NOT_FOUND,
    PROJECT_NOT_READY,
    INVALID_METADATA_KIND,
    INVALID_METADATA_NAME,
    METADATA_PARENT_NOT_FOUND,
    METADATA_ALREADY_EXISTS,
    EDT_SERVICE_UNAVAILABLE,
    EDT_TRANSACTION_FAILED
}
