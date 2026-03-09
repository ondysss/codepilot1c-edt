package com.codepilot1c.core.qa;

import java.util.List;

public record QaFeatureValidationResult(boolean ready, List<QaValidationIssue> issues) {
}
