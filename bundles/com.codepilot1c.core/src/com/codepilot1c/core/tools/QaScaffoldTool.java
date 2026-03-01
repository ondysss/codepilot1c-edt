package com.codepilot1c.core.tools;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;

import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.qa.QaConfig;
import com.codepilot1c.core.qa.QaPaths;
import com.codepilot1c.core.qa.QaStepsCatalog;
import com.codepilot1c.core.qa.QaStepsMatcher;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class QaScaffoldTool implements ITool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(QaScaffoldTool.class);

    private static final String DEFAULT_CONFIG_PATH = "tests/qa/qa-config.json"; //$NON-NLS-1$
    private static final String DEFAULT_STEPS_CATALOG = "tests/va/steps_catalog.json"; //$NON-NLS-1$
    private static final String BUNDLED_STEPS_CATALOG = "com/codepilot1c/core/qa/steps_catalog.json"; //$NON-NLS-1$

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "config_path": {
                  "type": "string",
                  "description": "Path to qa-config.json (workspace-relative or absolute)"
                },
                "feature_title": {
                  "type": "string",
                  "description": "Название фичи"
                },
                "feature_file": {
                  "type": "string",
                  "description": "Имя файла .feature (без пути или с относительным путем)"
                },
                "scenario_title": {
                  "type": "string",
                  "description": "Название сценария"
                },
                "tags": {
                  "type": "array",
                  "items": {"type": "string"}
                },
                "steps": {
                  "type": "array",
                  "items": {"type": "string"}
                },
                "language": {
                  "type": "string",
                  "description": "Язык Gherkin (по умолчанию ru)"
                },
                "allow_unknown_steps": {
                  "type": "boolean",
                  "description": "Разрешить неизвестные шаги"
                },
                "overwrite": {
                  "type": "boolean",
                  "description": "Перезаписать существующий файл"
                }
              },
              "required": ["scenario_title", "steps"]
            }
            """; //$NON-NLS-1$

    @Override
    public String getName() {
        return "qa_scaffold"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Создает Gherkin feature файл для Vanessa Automation с проверкой шагов."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("qa-scaffold"); //$NON-NLS-1$
            LOG.info("[%s] START qa_scaffold", opId); //$NON-NLS-1$

            try {
                if (parameters == null) {
                    return ToolResult.failure("QA_SCAFFOLD_ERROR: parameters are required"); //$NON-NLS-1$
                }
                String scenarioTitle = asString(parameters.get("scenario_title")); //$NON-NLS-1$
                if (scenarioTitle == null || scenarioTitle.isBlank()) {
                    return ToolResult.failure("QA_SCAFFOLD_ERROR: scenario_title is required"); //$NON-NLS-1$
                }
                List<String> steps = asStringList(parameters.get("steps")); //$NON-NLS-1$
                if (steps.isEmpty()) {
                    return ToolResult.failure("QA_SCAFFOLD_ERROR: steps are required"); //$NON-NLS-1$
                }

                String configPath = asString(parameters.get("config_path")); //$NON-NLS-1$
                String featureTitle = asString(parameters.get("feature_title")); //$NON-NLS-1$
                String featureFile = asString(parameters.get("feature_file")); //$NON-NLS-1$
                String language = asString(parameters.get("language")); //$NON-NLS-1$
                List<String> tags = asStringList(parameters.get("tags")); //$NON-NLS-1$
                boolean allowUnknown = Boolean.TRUE.equals(parameters.get("allow_unknown_steps")); //$NON-NLS-1$
                boolean overwrite = Boolean.TRUE.equals(parameters.get("overwrite")); //$NON-NLS-1$

                File workspaceRoot = getWorkspaceRoot();
                File configFile = QaPaths.resolveConfigFile(configPath, workspaceRoot, DEFAULT_CONFIG_PATH);
                if (configFile != null && workspaceRoot != null
                        && !QaPaths.isWithinWorkspace(workspaceRoot, configFile)) {
                    return ToolResult.failure("QA_SCAFFOLD_ERROR: config_path must be within workspace"); //$NON-NLS-1$
                }
                QaConfig config = QaConfig.load(configFile);

                File featuresDir = QaPaths.resolve(config.paths == null ? null : config.paths.features_dir, workspaceRoot);
                if (featuresDir == null) {
                    return ToolResult.failure("QA_SCAFFOLD_ERROR: features_dir is not configured"); //$NON-NLS-1$
                }
                if (!featuresDir.exists()) {
                    featuresDir.mkdirs();
                }

                File stepsCatalogFile = QaPaths.resolve(config.vanessa == null ? null : config.vanessa.steps_catalog,
                        workspaceRoot);
                if (stepsCatalogFile == null) {
                    stepsCatalogFile = QaPaths.resolve(DEFAULT_STEPS_CATALOG, workspaceRoot);
                }
                QaStepsCatalog catalog;
                if (stepsCatalogFile != null && stepsCatalogFile.exists()) {
                    catalog = QaStepsCatalog.load(stepsCatalogFile);
                } else {
                    catalog = QaStepsCatalog.loadFromResource(BUNDLED_STEPS_CATALOG,
                            QaScaffoldTool.class.getClassLoader());
                }

                List<String> unknown = findUnknownSteps(steps, catalog);
                if (!unknown.isEmpty() && !allowUnknown) {
                    String json = buildUnknownStepsError(opId, unknown, catalog);
                    return ToolResult.failure("QA_SCAFFOLD_ERROR: unknown_steps\n" + json); //$NON-NLS-1$
                }

                String resolvedFeatureTitle = (featureTitle == null || featureTitle.isBlank())
                        ? scenarioTitle
                        : featureTitle;
                String resolvedFileName = resolveFeatureFileName(featureFile, resolvedFeatureTitle);
                File targetFile = new File(featuresDir, resolvedFileName);

                if (!isWithin(featuresDir, targetFile)) {
                    return ToolResult.failure("QA_SCAFFOLD_ERROR: feature_file must stay within features_dir"); //$NON-NLS-1$
                }
                if (targetFile.exists() && !overwrite) {
                    return ToolResult.failure("QA_SCAFFOLD_ERROR: feature file already exists: " +
                            targetFile.getAbsolutePath()); //$NON-NLS-1$
                }

                String gherkinLanguage = (language == null || language.isBlank()) ? "ru" : language.trim(); //$NON-NLS-1$
                String content = buildFeatureContent(gherkinLanguage, resolvedFeatureTitle, scenarioTitle, tags, steps);
                Files.writeString(targetFile.toPath(), content, StandardCharsets.UTF_8);

                JsonObject result = new JsonObject();
                result.addProperty("op_id", opId); //$NON-NLS-1$
                result.addProperty("status", "created"); //$NON-NLS-1$ //$NON-NLS-2$
                result.addProperty("feature_file", targetFile.getAbsolutePath()); //$NON-NLS-1$
                result.addProperty("scenario_title", scenarioTitle); //$NON-NLS-1$
                result.addProperty("steps_count", steps.size()); //$NON-NLS-1$
                if (!unknown.isEmpty()) {
                    JsonArray unknownArray = new JsonArray();
                    for (String step : unknown) {
                        unknownArray.add(step);
                    }
                    result.add("unknown_steps", unknownArray); //$NON-NLS-1$
                }

                String json = new GsonBuilder().setPrettyPrinting().create().toJson(result);
                return ToolResult.success(json, ToolResult.ToolResultType.CODE);
            } catch (Exception e) {
                LOG.error("[" + opId + "] qa_scaffold failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure("QA_SCAFFOLD_ERROR: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    @Override
    public boolean isDestructive() {
        return true;
    }

    private static File getWorkspaceRoot() {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        return root.getLocation().toFile();
    }

    private static List<String> asStringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                String text = item.toString().trim();
                if (!text.isBlank()) {
                    result.add(text);
                }
            }
        }
        return result;
    }

    private static String buildUnknownStepsError(String opId, List<String> unknown, QaStepsCatalog catalog) {
        JsonObject error = new JsonObject();
        error.addProperty("op_id", opId); //$NON-NLS-1$
        error.addProperty("status", "unknown_steps"); //$NON-NLS-1$ //$NON-NLS-2$
        JsonArray unknownArray = new JsonArray();
        Set<String> steps = catalog.getSteps();
        for (String step : unknown) {
            JsonObject item = new JsonObject();
            item.addProperty("step", step); //$NON-NLS-1$
            JsonArray suggestions = new JsonArray();
            for (ScoredStep suggestion : suggestSteps(step, steps, 5)) {
                JsonObject suggestionJson = new JsonObject();
                suggestionJson.addProperty("text", suggestion.text()); //$NON-NLS-1$
                suggestionJson.addProperty("score", roundScore(suggestion.score())); //$NON-NLS-1$
                suggestionJson.addProperty("placeholders", QaStepsMatcher.countPlaceholders(suggestion.text())); //$NON-NLS-1$
                suggestions.add(suggestionJson);
            }
            item.add("suggestions", suggestions); //$NON-NLS-1$
            unknownArray.add(item);
        }
        error.add("unknown_steps", unknownArray); //$NON-NLS-1$
        return new GsonBuilder().setPrettyPrinting().create().toJson(error);
    }

    private static List<ScoredStep> suggestSteps(String query, Set<String> candidates, int limit) {
        String normalizedQuery = normalize(query);
        List<ScoredStep> matches = new ArrayList<>();
        for (String step : candidates) {
            if (step == null || step.isBlank()) {
                continue;
            }
            String normalizedStep = normalize(step);
            double score = score(normalizedStep, normalizedQuery);
            if (score <= 0) {
                continue;
            }
            matches.add(new ScoredStep(step, score));
        }
        matches.sort((a, b) -> {
            int cmp = Double.compare(b.score(), a.score());
            if (cmp != 0) {
                return cmp;
            }
            return a.text().compareToIgnoreCase(b.text());
        });
        if (limit > 0 && matches.size() > limit) {
            return matches.subList(0, limit);
        }
        return matches;
    }

    private static String normalize(String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\"'`]", " ") //$NON-NLS-1$ //$NON-NLS-2$
                .replaceAll("\\s+", " ") //$NON-NLS-1$ //$NON-NLS-2$
                .trim();
    }

    private static double score(String step, String query) {
        if (step == null || query == null) {
            return 0;
        }
        if (step.contains(query)) {
            int diff = Math.abs(step.length() - query.length());
            return 1.0 - Math.min(0.2, diff / (double) Math.max(step.length(), 1));
        }
        String[] stepTokens = step.split(" "); //$NON-NLS-1$
        String[] queryTokens = query.split(" "); //$NON-NLS-1$
        if (queryTokens.length == 0) {
            return 0;
        }
        int overlap = 0;
        for (String token : queryTokens) {
            if (token.isBlank()) {
                continue;
            }
            for (String stepToken : stepTokens) {
                if (stepToken.equals(token)) {
                    overlap++;
                    break;
                }
            }
        }
        if (overlap == 0) {
            return 0;
        }
        double recall = overlap / (double) queryTokens.length;
        int union = stepTokens.length + queryTokens.length - overlap;
        double jaccard = union <= 0 ? 0 : overlap / (double) union;
        double base = Math.max(recall, jaccard * 0.9);
        int diff = Math.abs(step.length() - query.length());
        double penalty = Math.min(0.15, diff / (double) Math.max(step.length(), 1));
        return Math.max(0, base - penalty);
    }

    private static double roundScore(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private record ScoredStep(String text, double score) { }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static List<String> findUnknownSteps(List<String> steps, QaStepsCatalog catalog) {
        List<String> unknown = new ArrayList<>();
        List<Pattern> patterns = new ArrayList<>();
        for (String step : catalog.getSteps()) {
            if (step.contains("%")) { //$NON-NLS-1$
                patterns.add(QaStepsMatcher.compilePattern(step));
            }
        }
        for (String raw : steps) {
            String normalized = normalizeStep(raw);
            if (catalog.contains(normalized)) {
                continue;
            }
            boolean matched = false;
            for (Pattern pattern : patterns) {
                Matcher matcher = pattern.matcher(normalized);
                if (matcher.matches()) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                unknown.add(raw);
            }
        }
        return unknown;
    }

    private static String normalizeStep(String step) {
        if (step == null) {
            return ""; //$NON-NLS-1$
        }
        return step.replaceAll("\\s+", " ").trim(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String resolveFeatureFileName(String featureFile, String featureTitle) {
        String base = featureFile;
        if (base == null || base.isBlank()) {
            base = featureTitle;
        }
        String normalized = base.replace('\\', '/'); //$NON-NLS-1$
        String[] parts = normalized.split("/"); //$NON-NLS-1$
        List<String> sanitizedParts = new ArrayList<>();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            sanitizedParts.add(sanitizeSegment(part));
        }
        if (sanitizedParts.isEmpty()) {
            sanitizedParts.add("feature"); //$NON-NLS-1$
        }
        int lastIndex = sanitizedParts.size() - 1;
        String last = sanitizedParts.get(lastIndex);
        if (!last.toLowerCase(Locale.ROOT).endsWith(".feature")) { //$NON-NLS-1$
            last = last + ".feature"; //$NON-NLS-1$
        }
        sanitizedParts.set(lastIndex, last);
        return String.join(File.separator, sanitizedParts);
    }

    private static String sanitizeSegment(String value) {
        if (value == null) {
            return "feature"; //$NON-NLS-1$
        }
        String sanitized = value.replaceAll("[\\\\/:*?\"<>|]", "_") //$NON-NLS-1$ //$NON-NLS-2$
                .replaceAll("\\s+", "_") //$NON-NLS-1$ //$NON-NLS-2$
                .replaceAll("_+", "_") //$NON-NLS-1$ //$NON-NLS-2$
                .trim();
        if (sanitized.isBlank()) {
            return "feature"; //$NON-NLS-1$
        }
        return sanitized;
    }

    private static boolean isWithin(File baseDir, File target) {
        try {
            String basePath = baseDir.getCanonicalPath();
            String targetPath = target.getCanonicalPath();
            return targetPath.startsWith(basePath + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    private static String buildFeatureContent(String language, String featureTitle, String scenarioTitle,
                                              List<String> tags, List<String> steps) {
        StringBuilder sb = new StringBuilder();
        sb.append("#language: ").append(language).append('\n').append('\n'); //$NON-NLS-1$
        if (!tags.isEmpty()) {
            for (String tag : tags) {
                if (tag == null || tag.isBlank()) {
                    continue;
                }
                String normalized = tag.startsWith("@") ? tag : "@" + tag; //$NON-NLS-1$ //$NON-NLS-2$
                sb.append(normalized).append(' ');
            }
            sb.setLength(sb.length() - 1);
            sb.append('\n').append('\n');
        }
        sb.append("Функциональность: ").append(featureTitle).append('\n').append('\n'); //$NON-NLS-1$
        sb.append("Сценарий: ").append(scenarioTitle).append('\n'); //$NON-NLS-1$
        for (String step : steps) {
            sb.append("  ").append(step).append('\n'); //$NON-NLS-1$
        }
        return sb.toString();
    }
}
