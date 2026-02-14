package com.codepilot1c.core.edt.validation;

import java.util.Map;
import java.util.List;

/**
 * Result of metadata mutation pre-validation.
 */
public record ValidationResult(
        boolean valid,
        String project,
        String operation,
        List<String> checks,
        Map<String, Object> normalizedPayload,
        String validationToken,
        long expiresAtEpochMs
) {
}
