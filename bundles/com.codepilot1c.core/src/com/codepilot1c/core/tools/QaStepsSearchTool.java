package com.codepilot1c.core.tools;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

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

public class QaStepsSearchTool implements ITool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(QaStepsSearchTool.class);

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
                "query": {
                  "type": "string",
                  "description": "Текст для поиска шага (часть фразы или описание)"
                },
                "limit": {
                  "type": "integer",
                  "description": "Максимум результатов (по умолчанию 20)"
                },
                "only_placeholders": {
                  "type": "boolean",
                  "description": "Показывать только шаги с плейсхолдерами (%1, %2)"
                },
                "include_regex": {
                  "type": "boolean",
                  "description": "Добавлять regex-паттерн для шага"
                }
              },
              "required": ["query"]
            }
            """; //$NON-NLS-1$

    @Override
    public String getName() {
        return "qa_steps_search"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Ищет подходящие шаги Vanessa Automation по каталогу стандартных шагов."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("qa-steps-search"); //$NON-NLS-1$
            LOG.info("[%s] START qa_steps_search", opId); //$NON-NLS-1$

            try {
                if (parameters == null) {
                    return ToolResult.failure("QA_STEPS_SEARCH_ERROR: parameters are required"); //$NON-NLS-1$
                }
                String query = asString(parameters.get("query")); //$NON-NLS-1$
                if (query == null || query.isBlank()) {
                    return ToolResult.failure("QA_STEPS_SEARCH_ERROR: query is required"); //$NON-NLS-1$
                }
                String configPath = asString(parameters.get("config_path")); //$NON-NLS-1$
                int limit = asInt(parameters.get("limit"), 20); //$NON-NLS-1$
                boolean onlyPlaceholders = Boolean.TRUE.equals(parameters.get("only_placeholders")); //$NON-NLS-1$
                boolean includeRegex = Boolean.TRUE.equals(parameters.get("include_regex")); //$NON-NLS-1$

                File workspaceRoot = getWorkspaceRoot();
                File configFile = QaPaths.resolveConfigFile(configPath, workspaceRoot, DEFAULT_CONFIG_PATH);
                if (configFile != null && workspaceRoot != null
                        && !QaPaths.isWithinWorkspace(workspaceRoot, configFile)) {
                    return ToolResult.failure("QA_STEPS_SEARCH_ERROR: config_path must be within workspace"); //$NON-NLS-1$
                }
                QaConfig config = QaConfig.load(configFile);

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
                            QaStepsSearchTool.class.getClassLoader());
                }

                String normalizedQuery = normalize(query);
                List<ScoredStep> matches = new ArrayList<>();
                Set<String> steps = catalog.getSteps();
                for (String step : steps) {
                    if (step == null || step.isBlank()) {
                        continue;
                    }
                    if (onlyPlaceholders && !step.contains("%")) { //$NON-NLS-1$
                        continue;
                    }
                    String normalizedStep = normalize(step);
                    double score = score(normalizedStep, normalizedQuery);
                    if (score <= 0) {
                        continue;
                    }
                    matches.add(new ScoredStep(step, score));
                }

                matches.sort(Comparator
                        .comparingDouble(ScoredStep::score)
                        .reversed()
                        .thenComparing(ScoredStep::text));
                if (limit <= 0) {
                    limit = 20;
                }
                if (matches.size() > limit) {
                    matches = matches.subList(0, limit);
                }

                JsonObject result = new JsonObject();
                result.addProperty("op_id", opId); //$NON-NLS-1$
                result.addProperty("query", query); //$NON-NLS-1$
                result.addProperty("total", matches.size()); //$NON-NLS-1$
                JsonArray items = new JsonArray();
                for (ScoredStep step : matches) {
                    JsonObject item = new JsonObject();
                    item.addProperty("text", step.text()); //$NON-NLS-1$
                    item.addProperty("score", roundScore(step.score())); //$NON-NLS-1$
                    item.addProperty("placeholders", QaStepsMatcher.countPlaceholders(step.text())); //$NON-NLS-1$
                    if (includeRegex) {
                        item.addProperty("regex", QaStepsMatcher.toRegex(step.text())); //$NON-NLS-1$
                    }
                    items.add(item);
                }
                result.add("results", items); //$NON-NLS-1$
                String json = new GsonBuilder().setPrettyPrinting().create().toJson(result);
                return ToolResult.success(json, ToolResult.ToolResultType.CODE);
            } catch (Exception e) {
                LOG.error("[" + opId + "] qa_steps_search failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure("QA_STEPS_SEARCH_ERROR: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private static File getWorkspaceRoot() {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        return root.getLocation().toFile();
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return fallback;
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
}
