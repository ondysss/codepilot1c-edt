package com.codepilot1c.core.tools;

import java.io.File;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;

import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.qa.QaConfig;
import com.codepilot1c.core.qa.QaPaths;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class QaInitConfigTool implements ITool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(QaInitConfigTool.class);
    private static final String DEFAULT_CONFIG_PATH = "tests/qa/qa-config.json"; //$NON-NLS-1$

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "config_path": {
                  "type": "string",
                  "description": "Path to qa-config.json (workspace-relative or absolute)"
                },
                "project_name": {
                  "type": "string",
                  "description": "EDT project name for default config"
                },
                "epf_path": {
                  "type": "string",
                  "description": "Optional Vanessa Automation EPF path to write into config"
                },
                "params_template": {
                  "type": "string",
                  "description": "Optional base Vanessa Automation params JSON path"
                },
                "force": {
                  "type": "boolean",
                  "description": "Overwrite existing qa-config.json with defaults"
                }
              }
            }
            """; //$NON-NLS-1$

    @Override
    public String getName() {
        return "qa_init_config"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Создаёт начальный qa-config.json для проекта и может сразу заполнить путь к VanessaAutomation.epf и базовому VA JSON."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public boolean isDestructive() {
        return true;
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("qa-init"); //$NON-NLS-1$
            LOG.info("[%s] START qa_init_config", opId); //$NON-NLS-1$
            try {
                File workspaceRoot = getWorkspaceRoot();
                String configPath = parameters == null ? null : (String) parameters.get("config_path"); //$NON-NLS-1$
                String projectName = parameters == null ? null : (String) parameters.get("project_name"); //$NON-NLS-1$
                String epfPath = parameters == null ? null : (String) parameters.get("epf_path"); //$NON-NLS-1$
                String paramsTemplate = parameters == null ? null : (String) parameters.get("params_template"); //$NON-NLS-1$
                boolean force = parameters != null && Boolean.TRUE.equals(parameters.get("force")); //$NON-NLS-1$

                File configFile = QaPaths.resolveConfigFile(configPath, workspaceRoot, DEFAULT_CONFIG_PATH);
                if (configFile != null && workspaceRoot != null
                        && !QaPaths.isWithinWorkspace(workspaceRoot, configFile)) {
                    return ToolResult.failure("QA_INIT_CONFIG_ERROR: config_path must be within workspace"); //$NON-NLS-1$
                }

                String effectiveProjectName = resolveProjectName(projectName);
                QaConfig config;
                String status;
                if (configFile.exists() && !force) {
                    config = QaConfig.load(configFile);
                    status = "exists"; //$NON-NLS-1$
                } else {
                    config = QaConfig.defaultConfig(effectiveProjectName);
                    status = configFile.exists() ? "overwritten" : "created"; //$NON-NLS-1$ //$NON-NLS-2$
                }

                if (config.vanessa == null) {
                    config.vanessa = new QaConfig.Vanessa();
                }
                if (epfPath != null && !epfPath.isBlank()) {
                    config.vanessa.epf_path = epfPath.trim();
                }
                if (paramsTemplate != null && !paramsTemplate.isBlank()) {
                    config.vanessa.params_template = paramsTemplate.trim();
                }
                config.save(configFile);

                JsonObject result = new JsonObject();
                result.addProperty("op_id", opId); //$NON-NLS-1$
                result.addProperty("status", status); //$NON-NLS-1$
                result.addProperty("config_path", configFile.getAbsolutePath()); //$NON-NLS-1$
                result.add("config", new GsonBuilder().create().toJsonTree(config)); //$NON-NLS-1$
                JsonArray next = new JsonArray();
                if (config.vanessa == null || config.vanessa.epf_path == null || config.vanessa.epf_path.isBlank()) {
                    next.add("Заполните vanessa.epf_path"); //$NON-NLS-1$
                }
                if (config.vanessa == null || config.vanessa.params_template == null
                        || config.vanessa.params_template.isBlank()) {
                    next.add("При необходимости задайте vanessa.params_template"); //$NON-NLS-1$
                }
                next.add("Проверьте effective значения через qa_explain_config или qa_status"); //$NON-NLS-1$
                result.add("next_steps", next); //$NON-NLS-1$
                return ToolResult.success(new GsonBuilder().setPrettyPrinting().create().toJson(result),
                        ToolResult.ToolResultType.CODE);
            } catch (Exception e) {
                LOG.error("[" + opId + "] qa_init_config failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure("QA_INIT_CONFIG_ERROR: " + e.getMessage()); //$NON-NLS-1$
            }
        });
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
        return "DemoProject"; //$NON-NLS-1$
    }
}
