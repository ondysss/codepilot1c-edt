package com.codepilot1c.core.tools.qa;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;

import com.codepilot1c.core.internal.VibeCorePlugin;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.qa.QaConfig;
import com.codepilot1c.core.qa.QaPaths;
import com.codepilot1c.core.qa.QaRuntimeSettings;
import com.codepilot1c.core.qa.QaStepsCatalog;
import com.codepilot1c.core.settings.VibePreferenceConstants;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

@ToolMeta(
        name = "qa_explain_config",
        category = "diagnostics",
        surfaceCategory = "qa",
        tags = {"read-only", "workspace"})
public class QaExplainConfigTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(QaExplainConfigTool.class);
    private static final String DEFAULT_CONFIG_PATH = "tests/qa/qa-config.json"; //$NON-NLS-1$
    private static final String BUNDLED_STEPS_CATALOG = "com/codepilot1c/core/qa/steps_catalog.json"; //$NON-NLS-1$

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "config_path": {
                  "type": "string",
                  "description": "Path to qa-config.json (workspace-relative or absolute)"
                },
                "include_contract": {
                  "type": "boolean",
                  "description": "Include top-level contract description (default: true)"
                }
              }
            }
            """; //$NON-NLS-1$

    @Override
    public String getDescription() {
        return "Объясняет контракт qa-config.json, effective значения и источники путей Vanessa Automation без запуска тестов."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> parameters = params.getRaw();
            String opId = LogSanitizer.newId("qa-explain"); //$NON-NLS-1$
            LOG.info("[%s] START qa_explain_config", opId); //$NON-NLS-1$
            try {
                File workspaceRoot = getWorkspaceRoot();
                String configPath = parameters == null ? null : (String) parameters.get("config_path"); //$NON-NLS-1$
                boolean includeContract = parameters == null
                        || !Boolean.FALSE.equals(parameters.get("include_contract")); //$NON-NLS-1$
                File configFile = QaPaths.resolveConfigFile(configPath, workspaceRoot, DEFAULT_CONFIG_PATH);
                if (configFile != null && workspaceRoot != null
                        && !QaPaths.isWithinWorkspace(workspaceRoot, configFile)) {
                    return ToolResult.failure("QA_EXPLAIN_CONFIG_ERROR: config_path must be within workspace"); //$NON-NLS-1$
                }

                QaConfig config = configFile.exists()
                        ? QaConfig.load(configFile)
                        : QaConfig.defaultConfig(resolveProjectName());
                String preferenceEpfPath = getPreferenceEpfPath();
                boolean bundledStepsCatalogExists = QaStepsCatalog.resourceExists(BUNDLED_STEPS_CATALOG,
                        QaExplainConfigTool.class.getClassLoader());

                JsonObject result = new JsonObject();
                result.addProperty("op_id", opId); //$NON-NLS-1$
                result.addProperty("config_path", configFile.getAbsolutePath()); //$NON-NLS-1$
                result.addProperty("config_exists", configFile.exists()); //$NON-NLS-1$
                if (includeContract) {
                    result.add("contract", buildContract()); //$NON-NLS-1$
                }
                result.add("config", new GsonBuilder().create().toJsonTree(config)); //$NON-NLS-1$
                result.add("effective_config",
                        buildEffectiveConfig(config, workspaceRoot, preferenceEpfPath, bundledStepsCatalogExists)); //$NON-NLS-1$
                result.add("missing_or_recommended", toArray(buildRecommendations(config, preferenceEpfPath))); //$NON-NLS-1$
                return ToolResult.success(new GsonBuilder().setPrettyPrinting().create().toJson(result),
                        ToolResult.ToolResultType.CODE);
            } catch (Exception e) {
                LOG.error("[" + opId + "] qa_explain_config failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure("QA_EXPLAIN_CONFIG_ERROR: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private static JsonObject buildContract() {
        JsonObject contract = new JsonObject();
        JsonArray sections = new JsonArray();
        sections.add(section("vanessa", "Настройки Vanessa Automation: epf_path, params_template, steps_catalog, run flags")); //$NON-NLS-1$ //$NON-NLS-2$
        sections.add(section("paths", "Каталоги features, step libraries и результатов")); //$NON-NLS-1$ //$NON-NLS-2$
        sections.add(section("edt", "Привязка к EDT runtime и project_name")); //$NON-NLS-1$ //$NON-NLS-2$
        sections.add(section("test_runner", "Режим TestManager, timeout и unknown_steps_mode")); //$NON-NLS-1$ //$NON-NLS-2$
        sections.add(section("test_clients", "Определения test client для TestManager")); //$NON-NLS-1$ //$NON-NLS-2$
        contract.add("sections", sections); //$NON-NLS-1$
        JsonArray important = new JsonArray();
        important.add("vanessa.epf_path = путь к VanessaAutomation.epf"); //$NON-NLS-1$
        important.add("vanessa.params_template = путь к базовому JSON-конфигу Vanessa Automation"); //$NON-NLS-1$
        important.add("vanessa.steps_catalog = JSON-каталог шагов только для предварительной unknown_steps проверки"); //$NON-NLS-1$
        important.add("paths.steps_dir = каталог библиотек шагов, который передаётся в Vanessa runtime"); //$NON-NLS-1$
        important.add("test_runner.unknown_steps_mode = off | warn | strict"); //$NON-NLS-1$
        contract.add("important_fields", important); //$NON-NLS-1$
        return contract;
    }

    private static JsonObject buildEffectiveConfig(QaConfig config, File workspaceRoot, String preferenceEpfPath,
            boolean bundledStepsCatalogExists) {
        JsonObject effective = new JsonObject();
        File epfPath = QaRuntimeSettings.resolveEpfPath(config, workspaceRoot, preferenceEpfPath);
        if (epfPath != null) {
            effective.addProperty("effective_epf_path", epfPath.getAbsolutePath()); //$NON-NLS-1$
        }
        effective.addProperty("epf_source", QaRuntimeSettings.describeEpfSource(config, preferenceEpfPath)); //$NON-NLS-1$
        File paramsTemplate = QaRuntimeSettings.resolveParamsTemplate(config, workspaceRoot);
        if (paramsTemplate != null) {
            effective.addProperty("effective_params_template", paramsTemplate.getAbsolutePath()); //$NON-NLS-1$
        }
        effective.addProperty("params_template_source",
                QaRuntimeSettings.describeParamsTemplateSource(config, workspaceRoot)); //$NON-NLS-1$
        File stepsCatalog = QaRuntimeSettings.resolveStepsCatalog(config, workspaceRoot);
        if (stepsCatalog != null && stepsCatalog.exists()) {
            effective.addProperty("effective_steps_catalog", stepsCatalog.getAbsolutePath()); //$NON-NLS-1$
        }
        effective.addProperty("steps_catalog_source",
                QaRuntimeSettings.describeStepsCatalogSource(config, workspaceRoot, bundledStepsCatalogExists)); //$NON-NLS-1$
        addResolvedPath(effective, "effective_features_dir", config.paths == null ? null : config.paths.features_dir, workspaceRoot); //$NON-NLS-1$ //$NON-NLS-2$
        addResolvedPath(effective, "effective_steps_dir", config.paths == null ? null : config.paths.steps_dir, workspaceRoot); //$NON-NLS-1$ //$NON-NLS-2$
        addResolvedPath(effective, "effective_results_dir", config.paths == null ? null : config.paths.results_dir, workspaceRoot); //$NON-NLS-1$ //$NON-NLS-2$
        effective.addProperty("unknown_steps_mode", QaRuntimeSettings.resolveUnknownStepsMode(config)); //$NON-NLS-1$
        return effective;
    }

    private static List<String> buildRecommendations(QaConfig config, String preferenceEpfPath) {
        List<String> recommendations = new ArrayList<>();
        if (!QaRuntimeSettings.hasConfiguredEpfPath(config, preferenceEpfPath)) {
            recommendations.add("Укажите vanessa.epf_path в qa-config.json или глобальных настройках EDT"); //$NON-NLS-1$
        }
        if (config.vanessa == null || config.vanessa.params_template == null || config.vanessa.params_template.isBlank()) {
            recommendations.add("При необходимости задайте vanessa.params_template для базового конфига Vanessa Automation"); //$NON-NLS-1$
        }
        if (config.vanessa == null || config.vanessa.steps_catalog == null || config.vanessa.steps_catalog.isBlank()) {
            recommendations.add("Если precheck шагов даёт ложные unknown_steps, задайте vanessa.steps_catalog или переключите test_runner.unknown_steps_mode=warn/off"); //$NON-NLS-1$
        }
        return recommendations;
    }

    private static JsonObject section(String name, String description) {
        JsonObject section = new JsonObject();
        section.addProperty("name", name); //$NON-NLS-1$
        section.addProperty("description", description); //$NON-NLS-1$
        return section;
    }

    private static JsonArray toArray(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static void addResolvedPath(JsonObject target, String property, String rawPath, File workspaceRoot) {
        File resolved = QaPaths.resolve(rawPath, workspaceRoot);
        if (resolved != null) {
            target.addProperty(property, resolved.getAbsolutePath());
        }
    }

    private static File getWorkspaceRoot() {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        return root.getLocation().toFile();
    }

    private static String resolveProjectName() {
        var projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
        if (projects != null) {
            for (var project : projects) {
                if (project != null && project.isOpen()) {
                    return project.getName();
                }
            }
        }
        return "DemoProject"; //$NON-NLS-1$
    }

    private static String getPreferenceEpfPath() {
        IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(VibeCorePlugin.PLUGIN_ID);
        return prefs.get(VibePreferenceConstants.PREF_QA_VA_EPF_PATH, ""); //$NON-NLS-1$
    }
}
