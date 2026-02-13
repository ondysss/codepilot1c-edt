package com.codepilot1c.core.edt.validation;

import java.util.List;

/**
 * Result of metadata mutation pre-validation.
 */
public record ValidationResult(
        boolean valid,
        String project,
        String operation,
        List<String> checks,
        String validationToken,
        long expiresAtEpochMs
) {
}
