package com.codepilot1c.core.tools;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;

import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.internal.VibeCorePlugin;
import com.codepilot1c.core.qa.QaConfig;
import com.codepilot1c.core.qa.QaPaths;
import com.codepilot1c.core.qa.QaStepsCatalog;
import com.codepilot1c.core.qa.QaStatusState;
import com.codepilot1c.core.settings.VibePreferenceConstants;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class QaStatusTool implements ITool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(QaStatusTool.class);

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
                "validate_ports": {
                  "type": "boolean",
                  "description": "Check local ports for test clients"
                },
                "use_edt_runtime": {
                  "type": "boolean",
                  "description": "Use EDT runtime to resolve infobase and launch"
                },
                "use_test_manager": {
                  "type": "boolean",
                  "description": "Use TestManager mode (default: true)"
                },
                "project_name": {
                  "type": "string",
                  "description": "EDT project name for infobase association"
                }
              }
            }
            """; //$NON-NLS-1$

    @Override
    public String getName() {
        return "qa_status"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Проверяет наличие конфигурации и окружения для запуска тестов Vanessa Automation."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("qa-status"); //$NON-NLS-1$
            LOG.info("[%s] START qa_status", opId); //$NON-NLS-1$

            try {
                File workspaceRoot = getWorkspaceRoot();
                String configPath = parameters == null ? null : (String) parameters.get("config_path"); //$NON-NLS-1$
                boolean validatePorts = parameters != null && Boolean.TRUE.equals(parameters.get("validate_ports")); //$NON-NLS-1$
                boolean useEdtRuntimeParam = parameters != null && Boolean.TRUE.equals(parameters.get("use_edt_runtime")); //$NON-NLS-1$
                boolean useTestManager = parameters == null || !Boolean.FALSE.equals(parameters.get("use_test_manager")); //$NON-NLS-1$
                String projectNameParam = parameters == null ? null : (String) parameters.get("project_name"); //$NON-NLS-1$

                File configFile = QaPaths.resolveConfigFile(configPath, workspaceRoot, DEFAULT_CONFIG_PATH);

                List<Check> checks = new ArrayList<>();
                if (configFile != null && workspaceRoot != null
                        && !QaPaths.isWithinWorkspace(workspaceRoot, configFile)) {
                    checks.add(Check.error("config_path", "QA config must be within workspace", configFile)); //$NON-NLS-1$ //$NON-NLS-2$
                    return buildResult(opId, workspaceRoot, configFile, checks);
                }
                if (configFile == null || !configFile.exists()) {
                    checks.add(Check.error("config", "QA config not found", configFile)); //$NON-NLS-1$ //$NON-NLS-2$
                    return buildResult(opId, workspaceRoot, configFile, checks);
                }

                QaConfig config = QaConfig.load(configFile);
                List<String> configErrors = new ArrayList<>(config.validate());
                if (useEdtRuntimeParam) {
                    configErrors.removeIf(error -> error.startsWith("platform.bin_path") //$NON-NLS-1$
                            || error.startsWith("test_manager.ib_connection")); //$NON-NLS-1$
                }
                if (!useTestManager) {
                    configErrors.removeIf(error -> error.startsWith("test_manager.ib_connection")); //$NON-NLS-1$
                }
                if (projectNameParam != null && !projectNameParam.isBlank()) {
                    configErrors.removeIf(error -> error.startsWith("edt.project_name")); //$NON-NLS-1$
                }
                if (getPreferenceEpfPath() != null && !getPreferenceEpfPath().isBlank()) {
                    configErrors.removeIf(error -> error.startsWith("vanessa.epf_path")); //$NON-NLS-1$
                }
                if (!configErrors.isEmpty()) {
                    for (String error : configErrors) {
                        checks.add(Check.error("config", error, null)); //$NON-NLS-1$
                    }
                } else {
                    checks.add(Check.ok("config", "QA config loaded", configFile)); //$NON-NLS-1$ //$NON-NLS-2$
                }

                boolean useEdtRuntime = useEdtRuntimeParam
                        || (config.edt != null && Boolean.TRUE.equals(config.edt.use_runtime));
                String projectName = projectNameParam;
                if (projectName == null || projectName.isBlank()) {
                    projectName = config.edt == null ? null : config.edt.project_name;
                }

                if (!useEdtRuntime) {
                    File binPath = QaPaths.resolve(config.platform == null ? null : config.platform.bin_path, workspaceRoot);
                    if (binPath != null && binPath.exists()) {
                        checks.add(Check.ok("platform.bin_path", "1cv8c найден", binPath)); //$NON-NLS-1$ //$NON-NLS-2$
                    } else {
                        checks.add(Check.error("platform.bin_path", "1cv8c не найден", binPath)); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                } else {
                    if (projectName == null || projectName.isBlank()) {
                        checks.add(Check.error("edt.project_name", "Не задано имя EDT проекта", null)); //$NON-NLS-1$ //$NON-NLS-2$
                    } else {
                        try {
                            EdtRuntimeService runtimeService = new EdtRuntimeService();
                            var infobase = runtimeService.resolveDefaultInfobase(projectName);
                            runtimeService.resolveThickClientInfo(infobase);
                            checks.add(Check.ok("edt.runtime", "EDT runtime и инфобаза доступны", null)); //$NON-NLS-1$ //$NON-NLS-2$
                        } catch (Exception e) {
                            checks.add(Check.error("edt.runtime", "EDT runtime недоступен: " + e.getMessage(), null)); //$NON-NLS-1$ //$NON-NLS-2$
                        }
                    }
                }

                File epfPath = resolveEpfPath(config, workspaceRoot);
                if (epfPath != null && epfPath.exists()) {
                    String message = "vanessa-automation.epf найден"; //$NON-NLS-1$
                    if (config.vanessa == null || config.vanessa.epf_path == null || config.vanessa.epf_path.isBlank()) {
                        message = "vanessa-automation.epf найден (из настроек)"; //$NON-NLS-1$
                    }
                    checks.add(Check.ok("vanessa.epf_path", message, epfPath)); //$NON-NLS-1$
                } else {
                    checks.add(Check.error("vanessa.epf_path", "vanessa-automation.epf не найден", epfPath)); //$NON-NLS-1$ //$NON-NLS-2$
                }

                File paramsTemplate = QaPaths.resolve(config.vanessa == null ? null : config.vanessa.params_template,
                        workspaceRoot);
                if (paramsTemplate != null && paramsTemplate.exists()) {
                    checks.add(Check.ok("vanessa.params_template", "VAParams template найден", paramsTemplate)); //$NON-NLS-1$ //$NON-NLS-2$
                } else if (paramsTemplate != null) {
                    checks.add(Check.warn("vanessa.params_template", "VAParams template не найден", paramsTemplate)); //$NON-NLS-1$ //$NON-NLS-2$
                }

                File stepsCatalog = QaPaths.resolve(config.vanessa == null ? null : config.vanessa.steps_catalog,
                        workspaceRoot);
                if (stepsCatalog != null && stepsCatalog.exists()) {
                    checks.add(Check.ok("vanessa.steps_catalog", "Каталог шагов найден", stepsCatalog)); //$NON-NLS-1$ //$NON-NLS-2$
                } else if (QaStepsCatalog.resourceExists(BUNDLED_STEPS_CATALOG, QaStatusTool.class.getClassLoader())) {
                    checks.add(Check.ok("vanessa.steps_catalog", "Используется встроенный каталог шагов", null)); //$NON-NLS-1$ //$NON-NLS-2$
                } else if (stepsCatalog != null) {
                    checks.add(Check.warn("vanessa.steps_catalog", "Каталог шагов не найден", stepsCatalog)); //$NON-NLS-1$ //$NON-NLS-2$
                } else {
                    checks.add(Check.warn("vanessa.steps_catalog", "Каталог шагов не задан", null)); //$NON-NLS-1$ //$NON-NLS-2$
                }

                File featuresDir = QaPaths.resolve(config.paths == null ? null : config.paths.features_dir, workspaceRoot);
                if (featuresDir != null && featuresDir.exists()) {
                    checks.add(Check.ok("paths.features_dir", "Каталог feature найден", featuresDir)); //$NON-NLS-1$ //$NON-NLS-2$
                } else {
                    checks.add(Check.warn("paths.features_dir", "Каталог feature не найден", featuresDir)); //$NON-NLS-1$ //$NON-NLS-2$
                }

                File stepsDir = QaPaths.resolve(config.paths == null ? null : config.paths.steps_dir, workspaceRoot);
                if (stepsDir != null && stepsDir.exists()) {
                    checks.add(Check.ok("paths.steps_dir", "Каталог шагов найден", stepsDir)); //$NON-NLS-1$ //$NON-NLS-2$
                } else if (stepsDir != null) {
                    checks.add(Check.warn("paths.steps_dir", "Каталог шагов не найден", stepsDir)); //$NON-NLS-1$ //$NON-NLS-2$
                }

                File resultsDir = QaPaths.resolve(config.paths == null ? null : config.paths.results_dir, workspaceRoot);
                if (resultsDir != null && resultsDir.exists()) {
                    checks.add(Check.ok("paths.results_dir", "Каталог результатов найден", resultsDir)); //$NON-NLS-1$ //$NON-NLS-2$
                } else {
                    checks.add(Check.warn("paths.results_dir", "Каталог результатов не найден", resultsDir)); //$NON-NLS-1$ //$NON-NLS-2$
                }

                if (validatePorts && config.test_clients != null) {
                    for (QaConfig.TestClient client : config.test_clients) {
                        if (client == null || client.port == null) {
                            continue;
                        }
                        String host = client.host == null ? "localhost" : client.host; //$NON-NLS-1$
                        String id = "test_client_port:" + host + ":" + client.port; //$NON-NLS-1$ //$NON-NLS-2$
                        if (!isLocalHost(host)) {
                            checks.add(Check.warn(id, "Удаленный порт не проверяется", null)); //$NON-NLS-1$
                            continue;
                        }
                        boolean free = isPortFree(client.port);
                        if (free) {
                            checks.add(Check.ok(id, "Порт свободен", null)); //$NON-NLS-1$
                        } else {
                            checks.add(Check.warn(id, "Порт занят", null)); //$NON-NLS-1$
                        }
                    }
                }

                return buildResult(opId, workspaceRoot, configFile, checks);
            } catch (Exception e) {
                LOG.error("[" + opId + "] qa_status failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure("QA_STATUS_ERROR: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private ToolResult buildResult(String opId, File workspaceRoot, File configFile, List<Check> checks) {
        JsonObject result = new JsonObject();
        result.addProperty("op_id", opId); //$NON-NLS-1$
        if (workspaceRoot != null) {
            result.addProperty("workspace_root", workspaceRoot.getAbsolutePath()); //$NON-NLS-1$
        }
        if (configFile != null) {
            result.addProperty("config_path", configFile.getAbsolutePath()); //$NON-NLS-1$
        }

        JsonArray checkArray = new JsonArray();
        int errors = 0;
        int warnings = 0;
        for (Check check : checks) {
            if (!check.ok && "error".equals(check.level)) { //$NON-NLS-1$
                errors++;
            } else if (!check.ok && "warn".equals(check.level)) { //$NON-NLS-1$
                warnings++;
            }
            checkArray.add(check.toJson());
        }
        result.add("checks", checkArray); //$NON-NLS-1$
        result.addProperty("errors", errors); //$NON-NLS-1$
        result.addProperty("warnings", warnings); //$NON-NLS-1$
        result.addProperty("status", errors > 0 ? "error" : (warnings > 0 ? "warning" : "ok")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        QaStatusState.record(workspaceRoot, configFile, errors, warnings);

        String json = new GsonBuilder().setPrettyPrinting().create().toJson(result);
        return ToolResult.success(json, ToolResult.ToolResultType.CODE);
    }

    private static File getWorkspaceRoot() {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        return root.getLocation().toFile();
    }

    private static boolean isLocalHost(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        String value = host.trim().toLowerCase();
        return "localhost".equals(value) || "127.0.0.1".equals(value); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static boolean isPortFree(int port) {
        if (port <= 0) {
            return true;
        }
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", port)); //$NON-NLS-1$
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static File resolveEpfPath(QaConfig config, File workspaceRoot) {
        String path = config.vanessa == null ? null : config.vanessa.epf_path;
        if (path == null || path.isBlank()) {
            String pref = getPreferenceEpfPath();
            if (pref != null && !pref.isBlank()) {
                path = pref;
            }
        }
        return QaPaths.resolve(path, workspaceRoot);
    }

    private static String getPreferenceEpfPath() {
        IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(VibeCorePlugin.PLUGIN_ID);
        return prefs.get(VibePreferenceConstants.PREF_QA_VA_EPF_PATH, ""); //$NON-NLS-1$
    }

    private static class Check {
        private final String id;
        private final boolean ok;
        private final String level;
        private final String message;
        private final File path;

        private Check(String id, boolean ok, String level, String message, File path) {
            this.id = id;
            this.ok = ok;
            this.level = level;
            this.message = message;
            this.path = path;
        }

        static Check ok(String id, String message, File path) {
            return new Check(id, true, "ok", message, path); //$NON-NLS-1$
        }

        static Check warn(String id, String message, File path) {
            return new Check(id, false, "warn", message, path); //$NON-NLS-1$
        }

        static Check error(String id, String message, File path) {
            return new Check(id, false, "error", message, path); //$NON-NLS-1$
        }

        JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", id); //$NON-NLS-1$
            obj.addProperty("ok", ok); //$NON-NLS-1$
            obj.addProperty("level", level); //$NON-NLS-1$
            obj.addProperty("message", message); //$NON-NLS-1$
            if (path != null) {
                obj.addProperty("path", path.getAbsolutePath()); //$NON-NLS-1$
            }
            return obj;
        }
    }
}
