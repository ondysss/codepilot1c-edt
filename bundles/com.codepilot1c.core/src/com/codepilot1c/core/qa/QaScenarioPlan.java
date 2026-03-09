package com.codepilot1c.core.qa;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record QaScenarioPlan(
        String scenarioTitle,
        String recipeId,
        Map<String, String> context,
        List<QaScenarioStep> steps,
        List<String> unresolvedBindings,
        List<String> tags) {

    public QaScenarioPlan {
        context = context == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(context));
        steps = steps == null ? List.of() : List.copyOf(steps);
        unresolvedBindings = unresolvedBindings == null ? List.of() : List.copyOf(unresolvedBindings);
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public boolean readyForCompile() {
        return !steps.isEmpty() && unresolvedBindings.isEmpty();
    }
}
