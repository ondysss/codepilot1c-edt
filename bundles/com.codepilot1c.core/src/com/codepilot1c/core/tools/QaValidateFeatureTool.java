package com.codepilot1c.core.tools;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;

import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.qa.QaConfig;
import com.codepilot1c.core.qa.QaFeatureCompiler;
import com.codepilot1c.core.qa.QaFeatureValidationResult;
import com.codepilot1c.core.qa.QaPaths;
import com.codepilot1c.core.qa.QaStepRegistry;
import com.codepilot1c.core.qa.QaStepsCatalog;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

public class QaValidateFeatureTool implements ITool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(QaValidateFeatureTool.class);

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
                "feature_file": {
                  "type": "string",
                  "description": "Имя файла .feature или абсолютный путь"
                }
              },
              "required": ["feature_file"]
            }
            """; //$NON-NLS-1$

    @Override
    public String getName() {
        return "qa_validate_feature"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Проверяет feature по structured QA registry и текущему Vanessa steps catalog до qa_run."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("qa-validate"); //$NON-NLS-1$
            LOG.info("[%s] START qa_validate_feature", opId); //$NON-NLS-1$
            try {
                if (parameters == null) {
                    return ToolResult.failure("QA_VALIDATE_FEATURE_ERROR: parameters are required"); //$NON-NLS-1$
                }
                String featureFile = parameters.get("feature_file") == null ? null : parameters.get("feature_file").toString(); //$NON-NLS-1$ //$NON-NLS-2$
                if (featureFile == null || featureFile.isBlank()) {
                    return ToolResult.failure("QA_VALIDATE_FEATURE_ERROR: feature_file is required"); //$NON-NLS-1$
                }
                File workspaceRoot = getWorkspaceRoot();
                File configFile = QaPaths.resolveConfigFile((String) parameters.get("config_path"), workspaceRoot,
                        DEFAULT_CONFIG_PATH); //$NON-NLS-1$
                QaConfig config = QaConfig.load(configFile);
                File featuresDir = QaPaths.resolve(config.paths == null ? null : config.paths.features_dir, workspaceRoot);
                File targetFile = resolveFeatureFile(featureFile, featuresDir);
                if (targetFile == null || !targetFile.exists()) {
                    return ToolResult.failure("QA_VALIDATE_FEATURE_ERROR: feature file not found: " + featureFile); //$NON-NLS-1$
                }
                List<String> lines = Files.readAllLines(targetFile.toPath(), StandardCharsets.UTF_8);
                QaFeatureValidationResult validation = QaFeatureCompiler.validateFeatureLines(lines,
                        QaStepRegistry.loadDefault(), loadCatalog(config, workspaceRoot));
                JsonObject result = new JsonObject();
                result.addProperty("op_id", opId); //$NON-NLS-1$
                result.addProperty("feature_file", targetFile.getAbsolutePath()); //$NON-NLS-1$
                result.add("validation", new GsonBuilder().setPrettyPrinting().create().toJsonTree(validation)); //$NON-NLS-1$
                if (!validation.ready()) {
                    return ToolResult.failure("QA_VALIDATE_FEATURE_ERROR: validation_failed\n"
                            + new GsonBuilder().setPrettyPrinting().create().toJson(result)); //$NON-NLS-1$
                }
                return ToolResult.success(new GsonBuilder().setPrettyPrinting().create().toJson(result),
                        ToolResult.ToolResultType.CODE);
            } catch (Exception e) {
                LOG.error("[" + opId + "] qa_validate_feature failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure("QA_VALIDATE_FEATURE_ERROR: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private static File getWorkspaceRoot() {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        return root.getLocation().toFile();
    }

    private static File resolveFeatureFile(String featureFile, File featuresDir) {
        File candidate = new File(featureFile);
        if (candidate.isAbsolute()) {
            if (candidate.exists()) {
                return candidate;
            }
            if (featuresDir == null) {
                return candidate;
            }
            String normalizedAbsolute = normalizeFeaturePath(featuresDir, featureFile);
            return new File(featuresDir, ensureFeatureExtension(normalizedAbsolute));
        }
        if (featuresDir == null) {
            return candidate;
        }
        return new File(featuresDir, ensureFeatureExtension(normalizeFeaturePath(featuresDir, featureFile)));
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

    private static String ensureFeatureExtension(String normalized) {
        if (!normalized.toLowerCase(Locale.ROOT).endsWith(".feature")) { //$NON-NLS-1$
            return normalized + ".feature"; //$NON-NLS-1$
        }
        return normalized;
    }

    private static QaStepsCatalog loadCatalog(QaConfig config, File workspaceRoot) throws Exception {
        File stepsCatalogFile = QaPaths.resolve(config.vanessa == null ? null : config.vanessa.steps_catalog,
                workspaceRoot);
        if (stepsCatalogFile == null) {
            stepsCatalogFile = QaPaths.resolve(DEFAULT_STEPS_CATALOG, workspaceRoot);
        }
        if (stepsCatalogFile != null && stepsCatalogFile.exists()) {
            return QaStepsCatalog.load(stepsCatalogFile);
        }
        return QaStepsCatalog.loadFromResource(BUNDLED_STEPS_CATALOG, QaValidateFeatureTool.class.getClassLoader());
    }
}
