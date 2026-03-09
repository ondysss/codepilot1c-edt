package com.codepilot1c.core.qa;

public record QaValidationIssue(
        String code,
        String message,
        String step,
        int line) {
}
