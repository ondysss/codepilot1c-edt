package com.codepilot1c.core.tools;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;

import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.internal.VibeCorePlugin;
import com.codepilot1c.core.qa.QaConfig;
import com.codepilot1c.core.qa.QaJUnitReport;
import com.codepilot1c.core.qa.QaJson;
import com.codepilot1c.core.qa.QaPaths;
import com.codepilot1c.core.qa.QaStepsCatalog;
import com.codepilot1c.core.qa.QaStepsMatcher;
import com.codepilot1c.core.qa.QaStatusState;
import com.codepilot1c.core.settings.VibePreferenceConstants;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class QaRunTool implements ITool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(QaRunTool.class);

    private static final String DEFAULT_CONFIG_PATH = "tests/qa/qa-config.json"; //$NON-NLS-1$
    private static final String BUNDLED_STEPS_CATALOG = "com/codepilot1c/core/qa/steps_catalog.json"; //$NON-NLS-1$

    private static final int DEFAULT_TIMEOUT_SECONDS = 3600;
    private static final int MIN_TIMEOUT_SECONDS = 300;
    private static final int MAX_FAILURE_DETAILS = 20;
    private static final int MAX_TAIL_LINES = 200;

    private static final Pattern STEP_LINE_PATTERN = Pattern.compile(
            "^\\s*(Дано|Когда|Тогда|И|Но|Также|Пусть|Given|When|Then|And|But|\\*)\\b.*", //$NON-NLS-1$
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "config_path": {
                  "type": "string",
                  "description": "Path to qa-config.json (workspace-relative or absolute)"
                },
                "features": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "Список feature файлов или имен"
                },
                "tags_include": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "Теги для отбора (AND логика по VA)"
                },
                "tags_exclude": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "Теги для исключения"
                },
                "scenarios": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "Список сценариев для выполнения"
                },
                "timeout_s": {
                  "type": "integer",
                  "description": "Таймаут выполнения в секундах"
                },
                "skip_status_check": {
                  "type": "boolean",
                  "description": "Пропустить обязательный qa_status перед запуском"
                },
                "dry_run": {
                  "type": "boolean",
                  "description": "Проверить конфигурацию и вывести команду без запуска"
                },
                "clear_steps_cache": {
                  "type": "boolean",
                  "description": "Сбросить кеш шагов VA при запуске"
                },
                "allow_unknown_steps": {
                  "type": "boolean",
                  "description": "Разрешить неизвестные шаги в feature (по умолчанию false)"
                },
                "use_test_manager": {
                  "type": "boolean",
                  "description": "Использовать TestManager (по умолчанию true). Если false, запускает VA в одиночном клиенте"
                },
                "use_edt_runtime": {
                  "type": "boolean",
                  "description": "Использовать EDT runtime для запуска"
                },
                "use_project_infobase_for_clients": {
                  "type": "boolean",
                  "description": "Подменить пути тест-клиентов на инфобазу, связанную с EDT проектом (по умолчанию true при use_edt_runtime)"
                },
                "platform_version": {
                  "type": "string",
                  "description": "Версия платформы 1С для запуска через EDT runtime (например 8.3.27.1688)"
                },
                "project_name": {
                  "type": "string",
                  "description": "EDT проект для привязки к инфобазе"
                },
                "update_db": {
                  "type": "boolean",
                  "description": "Обновить базу перед запуском тестов (по умолчанию true)"
                }
              }
            }
            """; //$NON-NLS-1$

    @Override
    public String getName() {
        return "qa_run"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Запускает E2E тесты Vanessa Automation через TestManager."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("qa-run"); //$NON-NLS-1$
            LOG.info("[%s] START qa_run", opId); //$NON-NLS-1$

            try {
                File workspaceRoot = getWorkspaceRoot();
                String configPath = parameters == null ? null : (String) parameters.get("config_path"); //$NON-NLS-1$
                File configFile = QaPaths.resolveConfigFile(configPath, workspaceRoot, DEFAULT_CONFIG_PATH);
                boolean skipStatusCheck = parameters != null && Boolean.TRUE.equals(parameters.get("skip_status_check")); //$NON-NLS-1$
                if (configFile != null && workspaceRoot != null
                        && !QaPaths.isWithinWorkspace(workspaceRoot, configFile)) {
                    return ToolResult.failure("QA_RUN_ERROR: config_path must be within workspace"); //$NON-NLS-1$
                }
                if (!skipStatusCheck) {
                    String statusError = QaStatusState.validateRecentOk(workspaceRoot, configFile);
                    if (statusError != null) {
                        return ToolResult.failure("QA_RUN_ERROR: " + statusError); //$NON-NLS-1$
                    }
                }

                QaConfig config = QaConfig.load(configFile);
                boolean useEdtRuntimeParam = parameters != null && Boolean.TRUE.equals(parameters.get("use_edt_runtime")); //$NON-NLS-1$
                boolean useTestManager = parameters == null || !Boolean.FALSE.equals(parameters.get("use_test_manager")); //$NON-NLS-1$
                String projectNameParam = parameters == null ? null : (String) parameters.get("project_name"); //$NON-NLS-1$
                String platformVersion = parameters == null ? null : (String) parameters.get("platform_version"); //$NON-NLS-1$
                boolean allowUnknownSteps = parameters != null && Boolean.TRUE.equals(parameters.get("allow_unknown_steps")); //$NON-NLS-1$
                boolean updateDb = true;
                if (parameters != null && parameters.containsKey("update_db")) { //$NON-NLS-1$
                    updateDb = Boolean.TRUE.equals(parameters.get("update_db")); //$NON-NLS-1$
                } else if (config.update_db != null) {
                    updateDb = Boolean.TRUE.equals(config.update_db);
                }

                List<String> validationErrors = new ArrayList<>(config.validate());
                if (useEdtRuntimeParam) {
                    validationErrors.removeIf(error -> error.startsWith("platform.bin_path") //$NON-NLS-1$
                            || error.startsWith("test_manager.ib_connection")); //$NON-NLS-1$
                }
                if (!useTestManager) {
                    validationErrors.removeIf(error -> error.startsWith("test_manager.ib_connection")); //$NON-NLS-1$
                }
                if (projectNameParam != null && !projectNameParam.isBlank()) {
                    validationErrors.removeIf(error -> error.startsWith("edt.project_name")); //$NON-NLS-1$
                }
                if (getPreferenceEpfPath() != null && !getPreferenceEpfPath().isBlank()) {
                    validationErrors.removeIf(error -> error.startsWith("vanessa.epf_path")); //$NON-NLS-1$
                }
                if (!validationErrors.isEmpty()) {
                    return ToolResult.failure("QA_RUN_ERROR: " + String.join("; ", validationErrors)); //$NON-NLS-1$ //$NON-NLS-2$
                }

                boolean useEdtRuntime = useEdtRuntimeParam
                        || (config.edt != null && Boolean.TRUE.equals(config.edt.use_runtime));
                String projectName = projectNameParam;
                if (projectName == null || projectName.isBlank()) {
                    projectName = config.edt == null ? null : config.edt.project_name;
                }
                if (platformVersion != null && platformVersion.isBlank()) {
                    platformVersion = null;
                }
                boolean useProjectInfobaseForClients = useEdtRuntime;
                if (parameters != null && parameters.containsKey("use_project_infobase_for_clients")) { //$NON-NLS-1$
                    useProjectInfobaseForClients = Boolean.TRUE.equals(parameters.get("use_project_infobase_for_clients")); //$NON-NLS-1$
                }
                boolean needEdtRuntime = useEdtRuntime || updateDb;
                if (updateDb && (projectName == null || projectName.isBlank())) {
                    return ToolResult.failure("QA_RUN_ERROR: project_name is required for EDT update"); //$NON-NLS-1$
                }

                EdtRuntimeService runtimeService = null;
                String edtInfobaseConnection = null;
                if (needEdtRuntime) {
                    if (projectName == null || projectName.isBlank()) {
                        return ToolResult.failure("QA_RUN_ERROR: project_name is required for EDT runtime"); //$NON-NLS-1$
                    }
                    runtimeService = new EdtRuntimeService();
                    if (useEdtRuntime) {
                        try {
                            var infobase = runtimeService.resolveDefaultInfobase(projectName);
                            if (infobase != null && infobase.getConnectionString() != null) {
                                edtInfobaseConnection = infobase.getConnectionString().asConnectionString();
                            }
                        } catch (Exception e) {
                            LOG.warn("[%s] Failed to resolve EDT infobase connection: %s", opId, e.getMessage()); //$NON-NLS-1$
                        }
                    }
                }

                String runIbConnection = null;
                File binPath = null;
                if (!useEdtRuntime) {
                    binPath = QaPaths.resolve(config.platform.bin_path, workspaceRoot);
                    if (binPath == null || !binPath.exists()) {
                        return ToolResult.failure("QA_RUN_ERROR: 1cv8c not found: " +
                                (binPath == null ? "<null>" : binPath.getAbsolutePath())); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    runIbConnection = resolveRunInfobaseConnection(config, useTestManager);
                    if (runIbConnection == null || runIbConnection.isBlank()) {
                        return ToolResult.failure("QA_RUN_ERROR: infobase connection is not configured"); //$NON-NLS-1$
                    }
                }

                File epfPath = resolveEpfPath(config, workspaceRoot);
                if (epfPath == null || !epfPath.exists()) {
                    return ToolResult.failure("QA_RUN_ERROR: vanessa-automation.epf not found: " +
                            (epfPath == null ? "<null>" : epfPath.getAbsolutePath())); //$NON-NLS-1$ //$NON-NLS-2$
                }

                File featuresDir = QaPaths.resolve(config.paths.features_dir, workspaceRoot);
                File stepsDir = QaPaths.resolve(config.paths.steps_dir, workspaceRoot);
                File resultsRoot = QaPaths.resolve(config.paths.results_dir, workspaceRoot);
                if (resultsRoot == null) {
                    return ToolResult.failure("QA_RUN_ERROR: results_dir is not configured"); //$NON-NLS-1$
                }
                if (!resultsRoot.exists()) {
                    resultsRoot.mkdirs();
                }

                if (!allowUnknownSteps) {
                    File stepsCatalogFile = QaPaths.resolve(config.vanessa == null ? null : config.vanessa.steps_catalog,
                            workspaceRoot);
                    if (stepsCatalogFile == null) {
                        stepsCatalogFile = QaPaths.resolve("tests/va/steps_catalog.json", workspaceRoot); //$NON-NLS-1$
                    }
                    QaStepsCatalog catalog;
                    if (stepsCatalogFile != null && stepsCatalogFile.exists()) {
                        catalog = QaStepsCatalog.load(stepsCatalogFile);
                    } else {
                        catalog = QaStepsCatalog.loadFromResource(BUNDLED_STEPS_CATALOG,
                                QaRunTool.class.getClassLoader());
                    }
                    List<File> featureFiles = resolveFeatureFiles(featuresDir, asStringList(parameters == null ? null
                            : parameters.get("features"))); //$NON-NLS-1$
                    List<StepIssue> unknownSteps = findUnknownSteps(featureFiles, catalog);
                    if (!unknownSteps.isEmpty()) {
                        JsonObject error = new JsonObject();
                        error.addProperty("op_id", opId); //$NON-NLS-1$
                        error.addProperty("status", "unknown_steps"); //$NON-NLS-1$ //$NON-NLS-2$
                        JsonArray items = new JsonArray();
                        for (StepIssue issue : unknownSteps) {
                            JsonObject item = new JsonObject();
                            item.addProperty("file", issue.file); //$NON-NLS-1$
                            item.addProperty("line", issue.line); //$NON-NLS-1$
                            item.addProperty("step", issue.step); //$NON-NLS-1$
                            items.add(item);
                        }
                        error.add("unknown_steps", items); //$NON-NLS-1$
                        String json = new GsonBuilder().setPrettyPrinting().create().toJson(error);
                        return ToolResult.failure("QA_RUN_ERROR: unknown_steps\n" + json); //$NON-NLS-1$
                    }
                }

                File runDir = new File(resultsRoot, opId);
                if (!runDir.exists()) {
                    runDir.mkdirs();
                }

                File junitDir = new File(runDir, "junit"); //$NON-NLS-1$
                File screenshotsDir = new File(runDir, "screenshots"); //$NON-NLS-1$
                File logFile = new File(runDir, "va.log"); //$NON-NLS-1$

                junitDir.mkdirs();
                screenshotsDir.mkdirs();

                File templatePath = QaPaths.resolve(config.vanessa.params_template, workspaceRoot);
                JsonObject params = QaJson.loadObject(templatePath);

                applyCommonParams(params, featuresDir, stepsDir, junitDir, screenshotsDir);
                applyFilters(params, parameters);
                if (useTestManager) {
                    applyTestClients(params, config, edtInfobaseConnection, useProjectInfobaseForClients);
                }

                File paramsFile = new File(runDir, "va-params.json"); //$NON-NLS-1$
                QaJson.writeObject(paramsFile, params);

                boolean clearStepsCache = parameters != null && Boolean.TRUE.equals(parameters.get("clear_steps_cache")); //$NON-NLS-1$
                boolean quietInstall = config.vanessa != null && Boolean.TRUE.equals(config.vanessa.quiet_install_ext);
                boolean showMainForm = config.vanessa != null && Boolean.TRUE.equals(config.vanessa.show_main_form);

                ProcessBuilder processBuilder;
                List<String> command;
                if (useEdtRuntime) {
                    var builder = useTestManager
                            ? runtimeService.buildTestManagerCommand(projectName, epfPath, paramsFile,
                                    workspaceRoot, showMainForm, quietInstall, clearStepsCache, logFile,
                                    platformVersion)
                            : runtimeService.buildSingleClientCommand(projectName, epfPath, paramsFile,
                                    workspaceRoot, showMainForm, quietInstall, clearStepsCache, logFile,
                                    platformVersion);
                    processBuilder = builder.toProcessBuilder();
                    command = processBuilder.command();
                } else {
                    command = buildCommand(binPath, epfPath, workspaceRoot, paramsFile, parameters,
                            runIbConnection, useTestManager, quietInstall, showMainForm);
                    processBuilder = new ProcessBuilder(command);
                    processBuilder.directory(workspaceRoot);
                }

                boolean dryRun = parameters != null && Boolean.TRUE.equals(parameters.get("dry_run")); //$NON-NLS-1$
                if (dryRun) {
                    JsonObject result = buildDryRunResult(opId, configFile, command, runDir, paramsFile, junitDir,
                            screenshotsDir, logFile);
                    String json = new GsonBuilder().setPrettyPrinting().create().toJson(result);
                    return ToolResult.success(json, ToolResult.ToolResultType.CODE);
                }

                if (updateDb && runtimeService != null) {
                    File updateLog = new File(runDir, "update.log"); //$NON-NLS-1$
                    try {
                        boolean updated = runtimeService.updateInfobase(projectName);
                        if (updated) {
                            writeUpdateLog(updateLog, "EDT update succeeded"); //$NON-NLS-1$
                        } else {
                            writeUpdateLog(updateLog, "EDT update failed: result=false"); //$NON-NLS-1$
                            JsonObject result = buildRunResult(opId, configFile, List.of(), runDir,
                                    paramsFile, junitDir, screenshotsDir, logFile,
                                    new ProcessResult(1, true, List.of("EDT update returned false")), null, 0); //$NON-NLS-1$
                            result.addProperty("status", "update_failed"); //$NON-NLS-1$ //$NON-NLS-2$
                            String json = new GsonBuilder().setPrettyPrinting().create().toJson(result);
                            return ToolResult.success(json, ToolResult.ToolResultType.CODE);
                        }
                    } catch (Exception e) {
                        writeUpdateLog(updateLog, "EDT update failed: " + e.getMessage()); //$NON-NLS-1$
                        JsonObject result = buildRunResult(opId, configFile, List.of(), runDir,
                                paramsFile, junitDir, screenshotsDir, logFile,
                                new ProcessResult(1, true, List.of(e.getMessage())), null, 0);
                        result.addProperty("status", "update_failed"); //$NON-NLS-1$ //$NON-NLS-2$
                        String json = new GsonBuilder().setPrettyPrinting().create().toJson(result);
                        return ToolResult.success(json, ToolResult.ToolResultType.CODE);
                    }
                } else if (updateDb) {
                    return ToolResult.failure("QA_RUN_ERROR: EDT runtime is required for update_db"); //$NON-NLS-1$
                }

                int timeout = extractTimeout(parameters);
                long start = System.currentTimeMillis();
                ProcessResult processResult = runProcess(processBuilder, logFile, timeout, workspaceRoot);
                long durationMs = System.currentTimeMillis() - start;

                QaJUnitReport report = null;
                try {
                    report = QaJUnitReport.parseDirectory(junitDir, MAX_FAILURE_DETAILS);
                } catch (IOException e) {
                    LOG.warn("[%s] Failed to parse JUnit report: %s", opId, e.getMessage()); //$NON-NLS-1$
                }

                JsonObject result = buildRunResult(opId, configFile, command, runDir, paramsFile, junitDir,
                        screenshotsDir, logFile, processResult, report, durationMs);
                String json = new GsonBuilder().setPrettyPrinting().create().toJson(result);
                return ToolResult.success(json, ToolResult.ToolResultType.CODE);
            } catch (Exception e) {
                LOG.error("[" + opId + "] qa_run failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure("QA_RUN_ERROR: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private static File getWorkspaceRoot() {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        return root.getLocation().toFile();
    }

    private static void applyCommonParams(JsonObject params, File featuresDir, File stepsDir,
                                          File junitDir, File screenshotsDir) {
        if (featuresDir != null) {
            params.addProperty("КаталогФич", featuresDir.getAbsolutePath()); //$NON-NLS-1$
            params.addProperty("КаталогОтносительноКоторогоНадоСтроитьИерархию", featuresDir.getAbsolutePath()); //$NON-NLS-1$
        }
        if (stepsDir != null) {
            JsonArray libs = new JsonArray();
            libs.add(stepsDir.getAbsolutePath());
            params.add("КаталогиБиблиотек", libs); //$NON-NLS-1$
        }
        params.addProperty("ВыполнитьСценарии", true); //$NON-NLS-1$
        params.addProperty("ДелатьОтчетВФорматеjUnit", true); //$NON-NLS-1$
        params.addProperty("КаталогOutputjUnit", junitDir.getAbsolutePath()); //$NON-NLS-1$
        params.addProperty("КаталогOutputСкриншоты", screenshotsDir.getAbsolutePath()); //$NON-NLS-1$
        params.addProperty("ДелатьСкриншотПриВозникновенииОшибки", true); //$NON-NLS-1$
        params.addProperty("ЗакрытьTestClientПослеЗапускаСценариев", true); //$NON-NLS-1$
    }

    private static void applyFilters(JsonObject params, Map<String, Object> parameters) {
        if (parameters == null) {
            return;
        }
        List<String> tagsInclude = asStringList(parameters.get("tags_include")); //$NON-NLS-1$
        if (!tagsInclude.isEmpty()) {
            params.add("СписокТеговОтбор", toJsonArray(tagsInclude)); //$NON-NLS-1$
        }
        List<String> tagsExclude = asStringList(parameters.get("tags_exclude")); //$NON-NLS-1$
        if (!tagsExclude.isEmpty()) {
            params.add("СписокТеговИсключение", toJsonArray(tagsExclude)); //$NON-NLS-1$
        }
        List<String> features = asStringList(parameters.get("features")); //$NON-NLS-1$
        if (!features.isEmpty()) {
            params.add("СписокФичДляВыполнения", toJsonArray(features)); //$NON-NLS-1$
        }
        List<String> scenarios = asStringList(parameters.get("scenarios")); //$NON-NLS-1$
        if (!scenarios.isEmpty()) {
            params.add("СписокСценариевДляВыполнения", toJsonArray(scenarios)); //$NON-NLS-1$
        }
    }

    private static void applyTestClients(JsonObject params, QaConfig config,
                                         String edtInfobaseConnection, boolean useProjectInfobase) {
        if (config.test_clients == null || config.test_clients.isEmpty()) {
            return;
        }
        JsonArray clients = new JsonArray();
        for (QaConfig.TestClient client : config.test_clients) {
            if (client == null) {
                continue;
            }
            JsonObject obj = new JsonObject();
            obj.addProperty("Имя", safe(client.name)); //$NON-NLS-1$
            obj.addProperty("Синоним", safe(client.alias)); //$NON-NLS-1$
            String ibConnection = client.ib_connection;
            if (useProjectInfobase && edtInfobaseConnection != null && !edtInfobaseConnection.isBlank()) {
                ibConnection = edtInfobaseConnection;
            }
            obj.addProperty("ПутьКИнфобазе", safe(ibConnection)); //$NON-NLS-1$
            if (client.port != null) {
                obj.addProperty("ПортЗапускаТестКлиента", client.port); //$NON-NLS-1$
            }
            obj.addProperty("ДопПараметры", safe(client.additional)); //$NON-NLS-1$
            obj.addProperty("ТипКлиента", normalizeClientType(client.type)); //$NON-NLS-1$
            obj.addProperty("ИмяКомпьютера", safe(client.host)); //$NON-NLS-1$
            clients.add(obj);
        }
        if (clients.size() > 0) {
            params.add("ДанныеКлиентовТестирования", clients); //$NON-NLS-1$
        }
    }

    private static JsonArray toJsonArray(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static String safe(String value) {
        return value == null ? "" : value; //$NON-NLS-1$
    }

    private static String normalizeClientType(String value) {
        if (value == null || value.isBlank()) {
            return "Тонкий"; //$NON-NLS-1$
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "thin", "тонкий" -> "Тонкий"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "thick", "толстый" -> "Толстый"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "web" -> "Web"; //$NON-NLS-1$ //$NON-NLS-2$
            case "server", "сервер" -> "Сервер"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            default -> value;
        };
    }

    private static List<String> buildUpdateCommand(File binPath, String ibConnection, File logFile) {
        List<String> args = new ArrayList<>();
        args.add(binPath.getAbsolutePath());
        args.add("DESIGNER"); //$NON-NLS-1$
        args.add("/IBConnectionString"); //$NON-NLS-1$
        args.add(ibConnection);
        args.add("/UpdateDBCfg"); //$NON-NLS-1$
        args.add("/DisableStartupDialogs"); //$NON-NLS-1$
        args.add("/DisableStartupMessages"); //$NON-NLS-1$
        if (logFile != null) {
            args.add("/Out"); //$NON-NLS-1$
            args.add(logFile.getAbsolutePath());
        }
        return args;
    }

    private static void writeUpdateLog(File logFile, String message) {
        if (logFile == null || message == null) {
            return;
        }
        try (PrintWriter writer = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(logFile, true), StandardCharsets.UTF_8))) {
            writer.println(message);
        } catch (IOException e) {
            // ignore logging failures
        }
    }

    private static List<String> buildCommand(File binPath, File epfPath, File workspaceRoot,
                                             File paramsFile, Map<String, Object> parameters,
                                             String ibConnection, boolean useTestManager,
                                             boolean quietInstall, boolean showMainForm) {
        List<String> args = new ArrayList<>();
        args.add(binPath.getAbsolutePath());
        if (useTestManager) {
            args.add("/TestManager"); //$NON-NLS-1$
        }
        args.add("/Execute"); //$NON-NLS-1$
        args.add(epfPath.getAbsolutePath());
        args.add("/IBConnectionString"); //$NON-NLS-1$
        args.add(ibConnection);

        List<String> cParams = new ArrayList<>();
        cParams.add("StartFeaturePlayer"); //$NON-NLS-1$
        cParams.add("VAParams=" + formatValue(paramsFile.getAbsolutePath())); //$NON-NLS-1$
        cParams.add("WorkspaceRoot=" + formatValue(workspaceRoot.getAbsolutePath())); //$NON-NLS-1$

        if (quietInstall) {
            cParams.add("QuietInstallVanessaExt"); //$NON-NLS-1$
        }
        if (!showMainForm) {
            cParams.add("ShowMainForm=Ложь"); //$NON-NLS-1$
        }
        if (parameters != null && Boolean.TRUE.equals(parameters.get("clear_steps_cache"))) { //$NON-NLS-1$
            cParams.add("ClearStepsCache"); //$NON-NLS-1$
        }

        String cParamString = String.join(";", cParams); //$NON-NLS-1$
        args.add("/C" + cParamString); //$NON-NLS-1$
        return args;
    }

    private static String resolveRunInfobaseConnection(QaConfig config, boolean useTestManager) {
        if (config == null) {
            return null;
        }
        if (!useTestManager) {
            if (config.infobase != null && config.infobase.ib_connection != null
                    && !config.infobase.ib_connection.isBlank()) {
                return config.infobase.ib_connection;
            }
        }
        if (config.test_manager != null && config.test_manager.ib_connection != null
                && !config.test_manager.ib_connection.isBlank()) {
            return config.test_manager.ib_connection;
        }
        if (config.test_clients != null && !config.test_clients.isEmpty()) {
            for (QaConfig.TestClient client : config.test_clients) {
                if (client != null && client.ib_connection != null && !client.ib_connection.isBlank()) {
                    return client.ib_connection;
                }
            }
        }
        return null;
    }

    private static String formatValue(String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        if (value.contains(" ")) { //$NON-NLS-1$
            return "\"" + value + "\""; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return value;
    }

    private static int extractTimeout(Map<String, Object> parameters) {
        if (parameters == null) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        Object value = parameters.get("timeout_s"); //$NON-NLS-1$
        if (value instanceof Number number) {
            int timeout = number.intValue();
            if (timeout <= 0) {
                return DEFAULT_TIMEOUT_SECONDS;
            }
            return Math.max(timeout, MIN_TIMEOUT_SECONDS);
        }
        return DEFAULT_TIMEOUT_SECONDS;
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

    private static List<File> resolveFeatureFiles(File featuresDir, List<String> featureNames) throws IOException {
        if (featuresDir == null || !featuresDir.exists()) {
            return List.of();
        }
        if (featureNames == null || featureNames.isEmpty()) {
            return listAllFeatures(featuresDir);
        }
        List<File> result = new ArrayList<>();
        for (String name : featureNames) {
            if (name == null || name.isBlank()) {
                continue;
            }
            File candidate = new File(name);
            if (!candidate.isAbsolute()) {
                candidate = new File(featuresDir, name);
            }
            if (!candidate.exists() && !name.toLowerCase(Locale.ROOT).endsWith(".feature")) { //$NON-NLS-1$
                File withExt = new File(featuresDir, name + ".feature"); //$NON-NLS-1$
                if (withExt.exists()) {
                    candidate = withExt;
                }
            }
            if (!candidate.exists()) {
                File found = findByFileName(featuresDir, name);
                if (found != null) {
                    candidate = found;
                }
            }
            if (candidate.exists()) {
                result.add(candidate);
            }
        }
        return result;
    }

    private static File findByFileName(File root, String name) throws IOException {
        if (root == null || name == null || name.isBlank()) {
            return null;
        }
        String target = name.toLowerCase(Locale.ROOT);
        if (!target.endsWith(".feature")) { //$NON-NLS-1$
            target = target + ".feature"; //$NON-NLS-1$
        }
        final String finalTarget = target;
        try (var stream = Files.walk(root.toPath())) {
            return stream.filter(path -> path.getFileName() != null
                            && path.getFileName().toString().toLowerCase(Locale.ROOT).equals(finalTarget))
                    .map(Path::toFile)
                    .findFirst()
                    .orElse(null);
        }
    }

    private static List<File> listAllFeatures(File root) throws IOException {
        if (root == null || !root.exists()) {
            return List.of();
        }
        try (var stream = Files.walk(root.toPath())) {
            return stream.filter(path -> path.getFileName() != null
                            && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".feature")) //$NON-NLS-1$
                    .map(Path::toFile)
                    .collect(Collectors.toList());
        }
    }

    private static List<StepIssue> findUnknownSteps(List<File> featureFiles, QaStepsCatalog catalog)
            throws IOException {
        List<StepIssue> unknown = new ArrayList<>();
        if (featureFiles == null || featureFiles.isEmpty() || catalog == null) {
            return unknown;
        }
        List<Pattern> patterns = new ArrayList<>();
        for (String step : catalog.getSteps()) {
            if (step.contains("%")) { //$NON-NLS-1$
                patterns.add(QaStepsMatcher.compilePattern(step));
            }
        }
        for (File file : featureFiles) {
            if (file == null || !file.exists()) {
                continue;
            }
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String rawLine = lines.get(i);
                if (!isStepLine(rawLine)) {
                    continue;
                }
                String stepLine = normalizeStepLine(rawLine);
                String normalized = normalizeStep(stepLine);
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
                    unknown.add(new StepIssue(file.getAbsolutePath(), i + 1, stepLine));
                }
            }
        }
        return unknown;
    }

    private static boolean isStepLine(String line) {
        if (line == null) {
            return false;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (trimmed.startsWith("#") || trimmed.startsWith("@") || trimmed.startsWith("|")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return false;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("функциональность:") || lower.startsWith("сценарий:") //$NON-NLS-1$ //$NON-NLS-2$
                || lower.startsWith("структура сценария:") || lower.startsWith("примеры:") //$NON-NLS-1$ //$NON-NLS-2$
                || lower.startsWith("контекст:") || lower.startsWith("background:")) { //$NON-NLS-1$ //$NON-NLS-2$
            return false;
        }
        return STEP_LINE_PATTERN.matcher(trimmed).matches();
    }

    private static String normalizeStepLine(String line) {
        String trimmed = line == null ? "" : line.trim(); //$NON-NLS-1$
        if (trimmed.startsWith("*")) { //$NON-NLS-1$
            String rest = trimmed.substring(1).trim();
            if (!rest.isEmpty()) {
                return "И " + rest; //$NON-NLS-1$
            }
        }
        return trimmed;
    }

    private static String normalizeStep(String step) {
        if (step == null) {
            return ""; //$NON-NLS-1$
        }
        return step.replaceAll("\\s+", " ").trim(); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static final class StepIssue {
        private final String file;
        private final int line;
        private final String step;

        private StepIssue(String file, int line, String step) {
            this.file = file;
            this.line = line;
            this.step = step;
        }
    }

    private static ProcessResult runProcess(ProcessBuilder builder, File logFile, int timeoutSeconds, File workingDir)
            throws IOException, InterruptedException {
        if (builder == null) {
            throw new IOException("ProcessBuilder is null"); //$NON-NLS-1$
        }
        if (workingDir != null && builder.directory() == null) {
            builder.directory(workingDir);
        }
        builder.redirectErrorStream(true);
        Process process = builder.start();

        StreamTee tee = new StreamTee(process, logFile);
        Thread thread = new Thread(tee, "qa-run-tee"); //$NON-NLS-1$
        thread.start();

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            terminateProcessTree(process);
        }
        thread.join(5000);
        int exitCode = finished ? process.exitValue() : -1;
        List<String> tail = tee.getTailLines();

        return new ProcessResult(exitCode, finished, tail);
    }

    private static void terminateProcessTree(Process process) throws InterruptedException {
        if (process == null) {
            return;
        }
        List<ProcessHandle> descendants = process.descendants().collect(Collectors.toList());
        for (ProcessHandle handle : descendants) {
            handle.destroy();
        }
        process.destroy();
        process.waitFor(5, TimeUnit.SECONDS);
        if (process.isAlive()) {
            for (ProcessHandle handle : descendants) {
                handle.destroyForcibly();
            }
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private static JsonObject buildDryRunResult(String opId, File configFile, List<String> command,
                                               File runDir, File paramsFile, File junitDir,
                                               File screenshotsDir, File logFile) {
        JsonObject result = new JsonObject();
        result.addProperty("op_id", opId); //$NON-NLS-1$
        result.addProperty("status", "dry_run"); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("timestamp", Instant.now().toString()); //$NON-NLS-1$
        if (configFile != null) {
            result.addProperty("config_path", configFile.getAbsolutePath()); //$NON-NLS-1$
        }
        result.add("command", toCommandJson(command)); //$NON-NLS-1$
        result.add("paths", buildPaths(runDir, paramsFile, junitDir, screenshotsDir, logFile)); //$NON-NLS-1$
        return result;
    }

    private static JsonObject buildRunResult(String opId, File configFile, List<String> command,
                                            File runDir, File paramsFile, File junitDir,
                                            File screenshotsDir, File logFile, ProcessResult processResult,
                                            QaJUnitReport report, long durationMs) {
        JsonObject result = new JsonObject();
        result.addProperty("op_id", opId); //$NON-NLS-1$
        result.addProperty("timestamp", Instant.now().toString()); //$NON-NLS-1$
        if (configFile != null) {
            result.addProperty("config_path", configFile.getAbsolutePath()); //$NON-NLS-1$
        }
        result.add("command", toCommandJson(command)); //$NON-NLS-1$
        result.add("paths", buildPaths(runDir, paramsFile, junitDir, screenshotsDir, logFile)); //$NON-NLS-1$
        result.addProperty("exit_code", processResult.exitCode); //$NON-NLS-1$
        result.addProperty("finished", processResult.finished); //$NON-NLS-1$
        result.addProperty("duration_ms", durationMs); //$NON-NLS-1$

        String status = "infra_error"; //$NON-NLS-1$
        if (!processResult.finished) {
            status = "timeout"; //$NON-NLS-1$
        } else if (report != null) {
            status = (report.failures + report.errors) > 0 ? "tests_failed" : "passed"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        result.addProperty("status", status); //$NON-NLS-1$

        if (report != null) {
            result.add("junit", toJUnitJson(report)); //$NON-NLS-1$
        }
        JsonArray tail = new JsonArray();
        for (String line : processResult.tailLines) {
            tail.add(line);
        }
        result.add("log_tail", tail); //$NON-NLS-1$
        return result;
    }

    private static JsonObject toJUnitJson(QaJUnitReport report) {
        JsonObject json = new JsonObject();
        json.addProperty("tests", report.tests); //$NON-NLS-1$
        json.addProperty("failures", report.failures); //$NON-NLS-1$
        json.addProperty("errors", report.errors); //$NON-NLS-1$
        json.addProperty("skipped", report.skipped); //$NON-NLS-1$
        json.addProperty("time_seconds", report.timeSeconds); //$NON-NLS-1$
        JsonArray files = new JsonArray();
        for (String file : report.files) {
            files.add(file);
        }
        json.add("files", files); //$NON-NLS-1$
        if (report.failureDetails != null && !report.failureDetails.isEmpty()) {
            JsonArray failures = new JsonArray();
            for (QaJUnitReport.FailureDetail detail : report.failureDetails) {
                JsonObject item = new JsonObject();
                item.addProperty("name", detail.name); //$NON-NLS-1$
                item.addProperty("class_name", detail.className); //$NON-NLS-1$
                item.addProperty("message", detail.message); //$NON-NLS-1$
                item.addProperty("type", detail.type); //$NON-NLS-1$
                item.addProperty("file", detail.file); //$NON-NLS-1$
                item.addProperty("details", detail.details); //$NON-NLS-1$
                failures.add(item);
            }
            json.add("failure_details", failures); //$NON-NLS-1$
        }
        return json;
    }

    private static JsonObject buildPaths(File runDir, File paramsFile, File junitDir, File screenshotsDir,
                                         File logFile) {
        JsonObject paths = new JsonObject();
        paths.addProperty("run_dir", runDir.getAbsolutePath()); //$NON-NLS-1$
        paths.addProperty("va_params", paramsFile.getAbsolutePath()); //$NON-NLS-1$
        paths.addProperty("junit_dir", junitDir.getAbsolutePath()); //$NON-NLS-1$
        paths.addProperty("screenshots_dir", screenshotsDir.getAbsolutePath()); //$NON-NLS-1$
        paths.addProperty("log", logFile.getAbsolutePath()); //$NON-NLS-1$
        return paths;
    }

    private static JsonObject toCommandJson(List<String> command) {
        JsonObject obj = new JsonObject();
        if (command == null || command.isEmpty()) {
            return obj;
        }
        obj.addProperty("bin", command.get(0)); //$NON-NLS-1$
        JsonArray args = new JsonArray();
        for (int i = 1; i < command.size(); i++) {
            String arg = command.get(i);
            if (i > 0 && isConnectionStringArg(command, i)) {
                args.add("<redacted>"); //$NON-NLS-1$
            } else {
                args.add(arg);
            }
        }
        obj.add("args", args); //$NON-NLS-1$
        return obj;
    }

    private static boolean isConnectionStringArg(List<String> command, int index) {
        if (index <= 0 || index >= command.size()) {
            return false;
        }
        return "/IBConnectionString".equalsIgnoreCase(command.get(index - 1)); //$NON-NLS-1$
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

    private static class ProcessResult {
        private final int exitCode;
        private final boolean finished;
        private final List<String> tailLines;

        private ProcessResult(int exitCode, boolean finished, List<String> tailLines) {
            this.exitCode = exitCode;
            this.finished = finished;
            this.tailLines = tailLines == null ? List.of() : tailLines;
        }
    }

    private static class StreamTee implements Runnable {

        private final Process process;
        private final File logFile;
        private final Deque<String> tail = new ArrayDeque<>();

        private StreamTee(Process process, File logFile) {
            this.process = process;
            this.logFile = logFile;
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(),
                    StandardCharsets.UTF_8));
                 PrintWriter writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(logFile, true),
                         StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    writer.println(line);
                    appendTail(line);
                }
            } catch (IOException e) {
                appendTail("[log-read-error] " + e.getMessage()); //$NON-NLS-1$
            }
        }

        private void appendTail(String line) {
            if (tail.size() >= MAX_TAIL_LINES) {
                tail.pollFirst();
            }
            tail.addLast(line);
        }

        private List<String> getTailLines() {
            return new ArrayList<>(tail);
        }
    }
}
