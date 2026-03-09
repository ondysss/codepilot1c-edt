package com.codepilot1c.core.qa;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.google.gson.Gson;

public final class QaStepRegistry {

    public static final String DEFAULT_RESOURCE_PATH = "com/codepilot1c/core/qa/step_registry.json"; //$NON-NLS-1$

    private final Map<String, QaStepSpec> stepsByIntent;
    private final Map<String, QaRecipe> recipesById;

    private QaStepRegistry(List<QaStepSpec> steps, List<QaRecipe> recipes) {
        Map<String, QaStepSpec> stepsMap = new LinkedHashMap<>();
        if (steps != null) {
            for (QaStepSpec step : steps) {
                if (step == null || step.intent() == null || step.intent().isBlank()) {
                    continue;
                }
                stepsMap.put(step.intent(), step);
            }
        }
        Map<String, QaRecipe> recipesMap = new LinkedHashMap<>();
        if (recipes != null) {
            for (QaRecipe recipe : recipes) {
                if (recipe == null || recipe.id() == null || recipe.id().isBlank()) {
                    continue;
                }
                recipesMap.put(recipe.id(), recipe);
            }
        }
        this.stepsByIntent = Map.copyOf(stepsMap);
        this.recipesById = Map.copyOf(recipesMap);
    }

    public static QaStepRegistry loadDefault() throws IOException {
        return loadFromResource(DEFAULT_RESOURCE_PATH, QaStepRegistry.class.getClassLoader());
    }

    public static QaStepRegistry loadFromResource(String resourcePath, ClassLoader classLoader) throws IOException {
        if (resourcePath == null || resourcePath.isBlank()) {
            throw new IOException("Step registry resource not specified"); //$NON-NLS-1$
        }
        ClassLoader loader = classLoader != null ? classLoader : QaStepRegistry.class.getClassLoader();
        try (InputStream stream = loader.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IOException("Step registry resource not found: " + resourcePath); //$NON-NLS-1$
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                RegistryData data = new Gson().fromJson(reader, RegistryData.class);
                if (data == null) {
                    return new QaStepRegistry(List.of(), List.of());
                }
                return new QaStepRegistry(data.steps == null ? List.of() : data.steps,
                        data.recipes == null ? List.of() : data.recipes);
            }
        }
    }

    public Optional<QaStepSpec> findStepByIntent(String intent) {
        if (intent == null || intent.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(stepsByIntent.get(intent));
    }

    public Optional<QaRecipe> findRecipe(String recipeId) {
        if (recipeId == null || recipeId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(recipesById.get(recipeId));
    }

    public List<QaStepSpec> getSteps() {
        return new ArrayList<>(stepsByIntent.values());
    }

    public List<QaRecipe> getRecipes() {
        return new ArrayList<>(recipesById.values());
    }

    public Optional<QaRecipe> inferRecipe(String goal, String objectType) {
        String haystack = ((goal == null ? "" : goal) + " " + (objectType == null ? "" : objectType)) //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                .toLowerCase(Locale.ROOT);
        QaRecipe best = null;
        int bestScore = 0;
        for (QaRecipe recipe : recipesById.values()) {
            int score = 0;
            if (recipe.keywords() != null) {
                for (String keyword : recipe.keywords()) {
                    if (keyword != null && !keyword.isBlank()
                            && haystack.contains(keyword.toLowerCase(Locale.ROOT))) {
                        score++;
                    }
                }
            }
            if (score > bestScore) {
                best = recipe;
                bestScore = score;
            }
        }
        return Optional.ofNullable(best);
    }

    private static final class RegistryData {
        List<QaStepSpec> steps;
        List<QaRecipe> recipes;
    }
}
