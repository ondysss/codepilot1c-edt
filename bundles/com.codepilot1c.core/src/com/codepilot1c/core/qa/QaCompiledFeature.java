package com.codepilot1c.core.qa;

import java.util.List;

public record QaCompiledFeature(
        String scenarioTitle,
        String recipeId,
        List<String> steps,
        List<QaCompileIssue> issues) {

    public boolean ready() {
        return issues == null || issues.isEmpty();
    }
}
