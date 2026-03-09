package com.codepilot1c.core.qa;

import java.util.List;

public record QaCompileIssue(
        String code,
        String message,
        Integer stepIndex,
        String intent,
        List<String> details) {
}
