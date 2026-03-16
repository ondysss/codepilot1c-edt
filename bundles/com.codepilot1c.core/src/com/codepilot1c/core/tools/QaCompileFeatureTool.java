package com.codepilot1c.core.tools;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;

import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.qa.QaCompiledFeature;
import com.codepilot1c.core.qa.QaConfig;
import com.codepilot1c.core.qa.QaConfigMigration;
import com.codepilot1c.core.qa.QaFeatureCompiler;
import com.codepilot1c.core.qa.QaFeatureValidationResult;
import com.codepilot1c.core.qa.QaPaths;
import com.codepilot1c.core.qa.QaScenarioPlan;
import com.codepilot1c.core.qa.QaScenarioStep;
import com.codepilot1c.core.qa.QaStepRegistry;
import com.codepilot1c.core.qa.QaStepsCatalog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

public class QaCompileFeatureTool implements ITool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(QaCompileFeatureTool.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String DEFAULT_CONFIG_PATH = "tests/qa/qa-config.json"; //$NON-NLS-1$
    private static final String DEFAULT_STEPS_CATALOG = "tests/va/steps_catalog.json"; //$NON-NLS-1$
    private static final String BUNDLED_STEPS_CATALOG = "com/codepilot1c/core/qa/steps_catalog.json"; //$NON-NLS-1$

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "config_path": {
                  "type": "string"
                },
                "plan": {
                  "type": "object",
                  "description": "Scenario plan from qa_plan_scenario"
                },
                "feature_title": {
                  "type": "string"
                },
                "feature_file": {
                  "type": "string"
                },
                "language": {
                  "type": "string"
                },
                "overwrite": {
                  "type": "boolean"
                },
                "project_name": {
                  "type": "string"
                },
                "auto_create_config": {
                  "type": "boolean"
                }
              },
              "required": ["plan"]
            }
            """; //$NON-NLS-1$

    @Override
    public String getName() {
        return "qa_compile_feature"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Компилирует структурированный QA plan в канонический Vanessa feature и сохраняет его на диск."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("qa-compile"); //$NON-NLS-1$
            LOG.info("[%s] START qa_compile_feature", opId); //$NON-NLS-1$
            try {
                if (parameters == null) {
                    return ToolResult.failure("QA_COMPILE_FEATURE_ERROR: parameters are required"); //$NON-NLS-1$
                }
                QaScenarioPlan plan = parsePlan(parameters.get("plan")); //$NON-NLS-1$
                if (plan == null) {
                    return ToolResult.failure("QA_COMPILE_FEATURE_ERROR: plan is required"); //$NON-NLS-1$
                }
                File workspaceRoot = getWorkspaceRoot();
                String configPath = asString(parameters.get("config_path")); //$NON-NLS-1$
                String projectNameParam = asString(parameters.get("project_name")); //$NON-NLS-1$
                boolean autoCreateConfig = !Boolean.FALSE.equals(parameters.get("auto_create_config")); //$NON-NLS-1$
                boolean overwrite = Boolean.TRUE.equals(parameters.get("overwrite")); //$NON-NLS-1$

                File configFile = QaPaths.resolveConfigFile(configPath, workspaceRoot, DEFAULT_CONFIG_PATH);
                if (configFile != null && workspaceRoot != null
                        && !QaPaths.isWithinWorkspace(workspaceRoot, configFile)) {
                    return ToolResult.failure("QA_COMPILE_FEATURE_ERROR: config_path must be within workspace"); //$NON-NLS-1$
                }
                if (configFile == null || !configFile.exists()) {
                    if (!autoCreateConfig) {
                        return ToolResult.failure("QA_COMPILE_FEATURE_ERROR: QA config not found"); //$NON-NLS-1$
                    }
                    String defaultProjectName = resolveProjectName(projectNameParam);
                    QaConfig.defaultConfig(defaultProjectName).save(configFile);
                }
                QaConfig config = QaConfig.load(configFile);
                QaConfigMigration.MigrationReport migration = QaConfigMigration.analyze(config,
                        resolveProjectName(projectNameParam),
                        config.edt != null && Boolean.TRUE.equals(config.edt.use_runtime),
                        config.test_runner == null || !Boolean.FALSE.equals(config.test_runner.use_test_manager));
                if (autoCreateConfig && (migration.changed() || migration.legacyDetected() || migration.incomplete())) {
                    LOG.info("[%s] qa_compile_feature detected legacy/incomplete QA config; preserving existing file",
                            opId); //$NON-NLS-1$
                }
                File featuresDir = QaPaths.resolve(config.paths == null ? null : config.paths.features_dir, workspaceRoot);
                if (featuresDir == null) {
                    return ToolResult.failure("QA_COMPILE_FEATURE_ERROR: features_dir is not configured"); //$NON-NLS-1$
                }
                if (!featuresDir.exists()) {
                    featuresDir.mkdirs();
                }

                QaStepRegistry registry = QaStepRegistry.loadDefault();
                QaStepsCatalog catalog = loadCatalog(config, workspaceRoot);
                QaFeatureCompiler compiler = new QaFeatureCompiler();
                QaCompiledFeature compiled = compiler.compile(plan, registry, catalog);
                if (!compiled.ready()) {
                    JsonObject error = new JsonObject();
                    error.addProperty("op_id", opId); //$NON-NLS-1$
                    error.addProperty("status", "compile_failed"); //$NON-NLS-1$ //$NON-NLS-2$
                    error.add("compiled", GSON.toJsonTree(compiled)); //$NON-NLS-1$
                    return ToolResult.failure("QA_COMPILE_FEATURE_ERROR: compile_failed\n" + GSON.toJson(error)); //$NON-NLS-1$
                }

                String featureTitle = asString(parameters.get("feature_title")); //$NON-NLS-1$
                if (featureTitle == null || featureTitle.isBlank()) {
                    featureTitle = plan.scenarioTitle();
                }
                String featureFile = asString(parameters.get("feature_file")); //$NON-NLS-1$
                File targetFile = resolveFeatureTargetFile(featuresDir, featureFile, featureTitle);
                if (!isWithin(featuresDir, targetFile)) {
                    return ToolResult.failure("QA_COMPILE_FEATURE_ERROR: feature_file must stay within features_dir"); //$NON-NLS-1$
                }
                if (targetFile.exists() && !overwrite) {
                    return ToolResult.failure("QA_COMPILE_FEATURE_ERROR: feature file already exists: "
                            + targetFile.getAbsolutePath()); //$NON-NLS-1$
                }
                String language = asString(parameters.get("language")); //$NON-NLS-1$
                if (language == null || language.isBlank()) {
                    language = "ru"; //$NON-NLS-1$
                }
                String content = buildFeatureContent(language, featureTitle, plan.scenarioTitle(), plan.tags(),
                        compiled.steps());
                Files.writeString(targetFile.toPath(), content, StandardCharsets.UTF_8);

                QaFeatureValidationResult validation = QaFeatureCompiler.validateFeatureLines(
                        Files.readAllLines(targetFile.toPath(), StandardCharsets.UTF_8), registry, catalog);
                JsonObject result = new JsonObject();
                result.addProperty("op_id", opId); //$NON-NLS-1$
                result.addProperty("status", validation.ready() ? "compiled" : "compiled_with_issues"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                result.addProperty("feature_file", targetFile.getAbsolutePath()); //$NON-NLS-1$
                result.add("compiled", GSON.toJsonTree(compiled)); //$NON-NLS-1$
                result.add("validation", GSON.toJsonTree(validation)); //$NON-NLS-1$
                return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.CODE);
            } catch (Exception e) {
                LOG.error("[" + opId + "] qa_compile_feature failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure("QA_COMPILE_FEATURE_ERROR: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    @Override
    public boolean isDestructive() {
        return true;
    }

    private static QaScenarioPlan parsePlan(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            String scenarioTitle = asString(map.get("scenarioTitle")); //$NON-NLS-1$
            if (scenarioTitle == null) {
                scenarioTitle = asString(map.get("scenario_title")); //$NON-NLS-1$
            }
            String recipeId = asString(map.get("recipeId")); //$NON-NLS-1$
            if (recipeId == null) {
                recipeId = asString(map.get("recipe_id")); //$NON-NLS-1$
            }
            Map<String, String> context = asStringMap(map.get("context")); //$NON-NLS-1$
            List<QaScenarioStep> steps = new ArrayList<>();
            Object stepsValue = map.get("steps"); //$NON-NLS-1$
            if (stepsValue instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> stepMap) {
                        String intent = asString(stepMap.get("intent")); //$NON-NLS-1$
                        Map<String, String> args = asStringMap(stepMap.get("args")); //$NON-NLS-1$
                        steps.add(new QaScenarioStep(intent, args));
                    }
                }
            }
            List<String> unresolvedBindings = asStringList(map.get("unresolvedBindings")); //$NON-NLS-1$
            if (unresolvedBindings.isEmpty()) {
                unresolvedBindings = asStringList(map.get("unresolved_bindings")); //$NON-NLS-1$
            }
            List<String> tags = asStringList(map.get("tags")); //$NON-NLS-1$
            return new QaScenarioPlan(scenarioTitle, recipeId, context, steps, unresolvedBindings, tags);
        }
        return null;
    }

    private static File getWorkspaceRoot() {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        return root.getLocation().toFile();
    }

    private static String resolveProjectName(String projectNameParam) {
        if (projectNameParam != null && !projectNameParam.isBlank()) {
            return projectNameParam;
        }
        var projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
        if (projects != null) {
            for (var project : projects) {
                if (project != null && project.isOpen()) {
                    return project.getName();
                }
            }
        }
        return null;
    }

    private static QaStepsCatalog loadCatalog(QaConfig config, File workspaceRoot) throws IOException {
        File stepsCatalogFile = QaPaths.resolve(config.vanessa == null ? null : config.vanessa.steps_catalog,
                workspaceRoot);
        if (stepsCatalogFile == null) {
            stepsCatalogFile = QaPaths.resolve(DEFAULT_STEPS_CATALOG, workspaceRoot);
        }
        if (stepsCatalogFile != null && stepsCatalogFile.exists()) {
            return QaStepsCatalog.load(stepsCatalogFile);
        }
        return QaStepsCatalog.loadFromResource(BUNDLED_STEPS_CATALOG, QaCompileFeatureTool.class.getClassLoader());
    }

    private static File resolveFeatureTargetFile(File featuresDir, String featureFile, String featureTitle) {
        if (featureFile != null && !featureFile.isBlank()) {
            File candidate = new File(featureFile);
            if (candidate.isAbsolute()) {
                String relativePath = normalizeFeaturePath(featuresDir, featureFile);
                if (relativePath == null || relativePath.isBlank()) {
                    return candidate;
                }
                return new File(featuresDir, relativePath);
            }
        }
        String resolvedFileName = resolveFeatureFileName(featuresDir, featureFile, featureTitle);
        return new File(featuresDir, resolvedFileName);
    }

    private static String resolveFeatureFileName(File featuresDir, String featureFile, String featureTitle) {
        String base = featureFile;
        if (base == null || base.isBlank()) {
            base = featureTitle;
        }
        String normalized = normalizeFeaturePath(featuresDir, base);
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

    private static String normalizeFeaturePath(File featuresDir, String featurePath) {
        String normalized = featurePath.replace('\\', '/'); //$NON-NLS-1$
        int testsFeaturesIndex = normalized.indexOf("/tests/features/"); //$NON-NLS-1$
        if (testsFeaturesIndex >= 0) {
            normalized = normalized.substring(testsFeaturesIndex + "/tests/features/".length()); //$NON-NLS-1$
        } else if (normalized.startsWith("tests/features/")) { //$NON-NLS-1$
            normalized = normalized.substring("tests/features/".length()); //$NON-NLS-1$
        } else if (featuresDir != null) {
            String featuresPath = featuresDir.getPath().replace('\\', '/'); //$NON-NLS-1$
            int markerIndex = featuresPath.indexOf("/tests/features"); //$NON-NLS-1$
            if (markerIndex >= 0) {
                String relativePrefix = featuresPath.substring(markerIndex + 1);
                if (normalized.startsWith(relativePrefix + "/")) { //$NON-NLS-1$
                    normalized = normalized.substring(relativePrefix.length() + 1);
                }
            }
        }
        return normalized;
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
        if (tags != null && !tags.isEmpty()) {
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

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static List<String> asStringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !item.toString().isBlank()) {
                    result.add(item.toString().trim());
                }
            }
        }
        return result;
    }

    private static Map<String, String> asStringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                result.put(entry.getKey().toString(), entry.getValue().toString());
            }
        }
        return result;
    }
}
