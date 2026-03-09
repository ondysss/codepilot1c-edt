package com.codepilot1c.core.qa;

import java.util.List;

public record QaRecipe(String id, String title, List<String> keywords, List<QaScenarioStep> steps) {
}
