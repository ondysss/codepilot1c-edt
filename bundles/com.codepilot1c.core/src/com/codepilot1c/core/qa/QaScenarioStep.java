package com.codepilot1c.core.qa;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record QaScenarioStep(String intent, Map<String, String> args) {

    public QaScenarioStep {
        args = args == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(args));
    }
}
