package com.codepilot1c.core.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com._1c.g5.v8.dt.core.platform.IDerivedDataManagerProvider;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.core.platform.IDtProjectManager;
import com._1c.g5.v8.derived.DerivedDataStatus;
import com._1c.g5.v8.derived.IDerivedDataManager;
import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;
import com.codepilot1c.core.edt.metadata.MetadataKind;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Diagnostic tool for tracing EDT export pipeline for one metadata FQN.
 */
public class EdtTraceExportTool implements ITool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(EdtTraceExportTool.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS"); //$NON-NLS-1$
    private static final String EXPORT_SEGMENT_OBJECTS = "EXP_O"; //$NON-NLS-1$
    private static final String EXPORT_SEGMENT_BLOBS = "EXP_B"; //$NON-NLS-1$
    private static final long DEFAULT_WAIT_MS = 20_000L;
    private static final long DEFAULT_POLL_MS = 500L;

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {"type": "string", "description": "Имя проекта EDT"},
                "fqn": {"type": "string", "description": "FQN объекта, например Catalog.Тест"},
                "kind": {"type": "string", "description": "Тип для проверки Configuration.mdo (если fqn отсутствует)"},
                "wait_ms": {"type": "integer", "description": "Таймаут ожидания экспорта и сериализации (default=20000)"},
                "poll_ms": {"type": "integer", "description": "Интервал опроса статуса (default=500)"},
                "run_force_export": {"type": "boolean", "description": "Запускать forceExport для FQN (default=true)"},
                "check_configuration_file": {"type": "boolean", "description": "Проверять запись в src/Configuration/Configuration.mdo (default=true)"}
              },
              "required": ["project"]
            }
            """; //$NON-NLS-1$

    private final EdtMetadataGateway gateway;

    public EdtTraceExportTool() {
        this(new EdtMetadataGateway());
    }

    EdtTraceExportTool(EdtMetadataGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public String getName() {
        return "edt_trace_export"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Диагностика цепочки forceExport -> derived-data -> Configuration.mdo для метаданных EDT."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("edt-trace"); //$NON-NLS-1$
            long startedAt = System.currentTimeMillis();
            LOG.info("[%s] START edt_trace_export", opId); //$NON-NLS-1$
            try {
                String projectName = asString(parameters.get("project")); //$NON-NLS-1$
                String fqn = blankToNull(asString(parameters.get("fqn"))); //$NON-NLS-1$
                String kindArg = blankToNull(asString(parameters.get("kind"))); //$NON-NLS-1$
                long waitMs = asLong(parameters.get("wait_ms"), DEFAULT_WAIT_MS); //$NON-NLS-1$
                long pollMs = Math.max(100L, asLong(parameters.get("poll_ms"), DEFAULT_POLL_MS)); //$NON-NLS-1$
                boolean runForceExport = asBoolean(parameters.get("run_force_export"), true); //$NON-NLS-1$
                boolean checkConfigFile = asBoolean(parameters.get("check_configuration_file"), true); //$NON-NLS-1$

                if (projectName == null || projectName.isBlank()) {
                    return ToolResult.failure("[INVALID_ARGUMENT] project is required"); //$NON-NLS-1$
                }
                gateway.ensureMutationRuntimeAvailable();

                IProject project = gateway.resolveProject(projectName);
                if (project == null || !project.exists()) {
                    return ToolResult.failure("[PROJECT_NOT_FOUND] Project not found: " + projectName); //$NON-NLS-1$
                }
                if (!project.isOpen()) {
                    return ToolResult.failure("[PROJECT_NOT_READY] Project is closed: " + projectName); //$NON-NLS-1$
                }

                IDtProjectManager projectManager = gateway.getDtProjectManager();
                IDtProject dtProject = projectManager.getDtProject(project);
                if (dtProject == null) {
                    return ToolResult.failure("[PROJECT_NOT_READY] Not an EDT project: " + projectName); //$NON-NLS-1$
                }
                IDerivedDataManagerProvider ddProvider = gateway.getDerivedDataManagerProvider();
                IDerivedDataManager ddManager = ddProvider.get(dtProject);
                if (ddManager == null) {
                    return ToolResult.failure("[EDT_SERVICE_UNAVAILABLE] Derived-data manager unavailable"); //$NON-NLS-1$
                }
                IBmModelManager modelManager = gateway.getBmModelManager();

                MetadataKind kind = resolveKind(kindArg, fqn);
                IFile configFile = project.getFile("src/Configuration/Configuration.mdo"); //$NON-NLS-1$

                StringBuilder out = new StringBuilder();
                out.append("EDT Export Trace\n"); //$NON-NLS-1$
                out.append("op_id: ").append(opId).append('\n'); //$NON-NLS-1$
                out.append("project: ").append(projectName).append('\n'); //$NON-NLS-1$
                out.append("dt_project: ").append(dtProject.getName()).append('\n'); //$NON-NLS-1$
                out.append("fqn: ").append(fqn != null ? fqn : "<not-set>").append('\n'); //$NON-NLS-1$ //$NON-NLS-2$
                out.append("kind: ").append(kind != null ? kind.name() : "<unknown>").append('\n'); //$NON-NLS-1$ //$NON-NLS-2$
                out.append("wait_ms: ").append(waitMs).append('\n'); //$NON-NLS-1$
                out.append("poll_ms: ").append(pollMs).append('\n'); //$NON-NLS-1$
                out.append("run_force_export: ").append(runForceExport).append('\n'); //$NON-NLS-1$
                out.append("check_configuration_file: ").append(checkConfigFile).append('\n'); //$NON-NLS-1$
                out.append('\n');

                appendServiceSnapshot(out, modelManager, ddManager, opId);

                Boolean bmExistsBefore = null;
                if (fqn != null) {
                    bmExistsBefore = existsInBm(modelManager, project, fqn);
                    out.append("[precheck] bm_exists=").append(bmExistsBefore).append('\n'); //$NON-NLS-1$
                }

                Snapshot previous = Snapshot.capture(ddManager);
                out.append("[status] ").append(previous.format(0L)).append('\n'); //$NON-NLS-1$
                if (checkConfigFile && configFile.exists() && fqn != null && kind != null) {
                    boolean preCfg = hasConfigurationEntry(configFile, kind, fqn);
                    out.append("[precheck] config_entry=").append(preCfg).append(" file=") //$NON-NLS-1$ //$NON-NLS-2$
                            .append(configFile.getFullPath()).append('\n');
                }

                if (runForceExport) {
                    if (fqn == null) {
                        out.append("[forceExport] skipped: fqn is not set\n"); //$NON-NLS-1$
                    } else {
                        boolean exported = false;
                        try {
                            exported = modelManager.forceExport(dtProject, java.util.List.of(fqn));
                            out.append("[forceExport] byList=").append(exported).append('\n'); //$NON-NLS-1$
                        } catch (RuntimeException e) {
                            out.append("[forceExport] byList failed: ").append(e.getMessage()).append('\n'); //$NON-NLS-1$
                        }
                        if (!exported) {
                            try {
                                exported = modelManager.forceExport(dtProject, fqn);
                                out.append("[forceExport] byString=").append(exported).append('\n'); //$NON-NLS-1$
                            } catch (RuntimeException e) {
                                out.append("[forceExport] byString failed: ").append(e.getMessage()).append('\n'); //$NON-NLS-1$
                            }
                        }
                        if (!exported) {
                            out.append("[forceExport] export was not scheduled\n"); //$NON-NLS-1$
                        }

                        waitExportSegments(ddManager, waitMs, out);
                        flushDerivedData(ddManager, waitMs, out);
                        modelManager.waitModelSynchronization(project);
                        out.append("[sync] waitModelSynchronization completed\n"); //$NON-NLS-1$
                    }
                }

                long timelineStart = System.currentTimeMillis();
                long deadline = timelineStart + waitMs;
                boolean configAppeared = false;
                while (System.currentTimeMillis() < deadline) {
                    Snapshot current = Snapshot.capture(ddManager);
                    if (!current.sameAs(previous)) {
                        out.append("[status] ").append(current.format(System.currentTimeMillis() - timelineStart)).append('\n'); //$NON-NLS-1$
                        previous = current;
                    }
                    if (checkConfigFile && configFile.exists() && fqn != null && kind != null) {
                        if (hasConfigurationEntry(configFile, kind, fqn)) {
                            configAppeared = true;
                            out.append("[config] entry detected after ") //$NON-NLS-1$
                                    .append(System.currentTimeMillis() - timelineStart).append("ms\n"); //$NON-NLS-1$
                            break;
                        }
                    }
                    Thread.sleep(pollMs);
                }

                Boolean bmExistsAfter = null;
                if (fqn != null) {
                    bmExistsAfter = existsInBm(modelManager, project, fqn);
                    out.append("[postcheck] bm_exists=").append(bmExistsAfter).append('\n'); //$NON-NLS-1$
                }
                if (checkConfigFile && configFile.exists() && fqn != null && kind != null) {
                    boolean postCfg = hasConfigurationEntry(configFile, kind, fqn);
                    out.append("[postcheck] config_entry=").append(postCfg) //$NON-NLS-1$
                            .append(" (appeared_during_timeline=").append(configAppeared).append(")\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }

                out.append('\n');
                out.append("duration: ").append(LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt)).append('\n'); //$NON-NLS-1$
                if (fqn != null && Boolean.TRUE.equals(bmExistsAfter) && checkConfigFile && kind != null && !configAppeared) {
                    out.append("diagnostic: BM object exists but Configuration.mdo entry was not observed within timeout.\n"); //$NON-NLS-1$
                    out.append("recommendation: inspect export contributors/segments and delayed serialization queue in EDT runtime.\n"); //$NON-NLS-1$
                }

                LOG.info("[%s] SUCCESS edt_trace_export in %s", opId, // $NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt));
                return ToolResult.success(out.toString());
            } catch (MetadataOperationException e) {
                LOG.warn("[%s] FAILED edt_trace_export: %s (%s)", opId, e.getMessage(), e.getCode()); //$NON-NLS-1$
                return ToolResult.failure("[" + e.getCode() + "] " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.warn("[%s] FAILED edt_trace_export: interrupted", opId); //$NON-NLS-1$
                return ToolResult.failure("[INTERRUPTED] edt_trace_export interrupted"); //$NON-NLS-1$
            } catch (Exception e) {
                LOG.error("[" + opId + "] edt_trace_export failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure("[INTERNAL_ERROR] " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private void appendServiceSnapshot(StringBuilder out, IBmModelManager modelManager, IDerivedDataManager ddManager, String opId) {
        out.append("[services] bmModelManager=").append(modelManager.getClass().getName()).append('\n'); //$NON-NLS-1$
        out.append("[services] derivedDataManager=").append(ddManager.getClass().getName()).append('\n'); //$NON-NLS-1$
        out.append("[services] timestamp=").append(LocalDateTime.now().format(TS)).append('\n'); //$NON-NLS-1$
        LOG.debug("[%s] Service snapshot bm=%s dd=%s", opId, // $NON-NLS-1$
                modelManager.getClass().getName(), ddManager.getClass().getName());
    }

    private void waitExportSegments(IDerivedDataManager ddManager, long waitMs, StringBuilder out) throws InterruptedException {
        boolean done = ddManager.waitComputation(waitMs, true, EXPORT_SEGMENT_OBJECTS, EXPORT_SEGMENT_BLOBS);
        out.append("[derived] waitComputation(") //$NON-NLS-1$
                .append(EXPORT_SEGMENT_OBJECTS).append(',').append(EXPORT_SEGMENT_BLOBS)
                .append(")=").append(done).append('\n'); //$NON-NLS-1$
    }

    private void flushDerivedData(IDerivedDataManager ddManager, long waitMs, StringBuilder out) throws InterruptedException {
        ddManager.applyForcedUpdates();
        out.append("[derived] applyForcedUpdates completed\n"); //$NON-NLS-1$
        boolean allDone = ddManager.waitAllComputations(waitMs);
        out.append("[derived] waitAllComputations=").append(allDone).append('\n'); //$NON-NLS-1$
    }

    private boolean existsInBm(IBmModelManager modelManager, IProject project, String fqn) {
        Boolean exists = modelManager.executeReadOnlyTask(project, tx -> tx.getTopObjectByFqn(fqn) != null);
        return Boolean.TRUE.equals(exists);
    }

    private MetadataKind resolveKind(String kindArg, String fqn) {
        if (kindArg != null) {
            try {
                return MetadataKind.fromString(kindArg);
            } catch (RuntimeException e) {
                return null;
            }
        }
        if (fqn == null || !fqn.contains(".")) { //$NON-NLS-1$
            return null;
        }
        String prefix = fqn.substring(0, fqn.indexOf('.'));
        try {
            return MetadataKind.fromString(prefix);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String configurationTag(MetadataKind kind) {
        return switch (kind) {
            case CATALOG -> "catalogs"; //$NON-NLS-1$
            case DOCUMENT -> "documents"; //$NON-NLS-1$
            case INFORMATION_REGISTER -> "informationRegisters"; //$NON-NLS-1$
            case ACCUMULATION_REGISTER -> "accumulationRegisters"; //$NON-NLS-1$
            case ACCOUNTING_REGISTER -> "accountingRegisters"; //$NON-NLS-1$
            case CALCULATION_REGISTER -> "calculationRegisters"; //$NON-NLS-1$
            case COMMON_MODULE -> "commonModules"; //$NON-NLS-1$
            case COMMON_ATTRIBUTE -> "commonAttributes"; //$NON-NLS-1$
            case ENUM -> "enums"; //$NON-NLS-1$
            case REPORT -> "reports"; //$NON-NLS-1$
            case DATA_PROCESSOR -> "dataProcessors"; //$NON-NLS-1$
            case CONSTANT -> "constants"; //$NON-NLS-1$
            case COMMAND_GROUP -> "commandGroups"; //$NON-NLS-1$
            case INTERFACE -> "interfaces"; //$NON-NLS-1$
            case LANGUAGE -> "languages"; //$NON-NLS-1$
            case STYLE -> "styles"; //$NON-NLS-1$
            case STYLE_ITEM -> "styleItems"; //$NON-NLS-1$
            case SESSION_PARAMETER -> "sessionParameters"; //$NON-NLS-1$
            case SETTINGS_STORAGE -> "settingsStorages"; //$NON-NLS-1$
            case XDTO_PACKAGE -> "xdtoPackages"; //$NON-NLS-1$
            case WS_REFERENCE -> "wsReferences"; //$NON-NLS-1$
            case ROLE -> "roles"; //$NON-NLS-1$
            case SUBSYSTEM -> "subsystems"; //$NON-NLS-1$
            case EXCHANGE_PLAN -> "exchangePlans"; //$NON-NLS-1$
            case CHART_OF_ACCOUNTS -> "chartsOfAccounts"; //$NON-NLS-1$
            case CHART_OF_CHARACTERISTIC_TYPES -> "chartsOfCharacteristicTypes"; //$NON-NLS-1$
            case CHART_OF_CALCULATION_TYPES -> "chartsOfCalculationTypes"; //$NON-NLS-1$
            case BUSINESS_PROCESS -> "businessProcesses"; //$NON-NLS-1$
            case TASK -> "tasks"; //$NON-NLS-1$
            case COMMON_FORM -> "commonForms"; //$NON-NLS-1$
            case COMMON_COMMAND -> "commonCommands"; //$NON-NLS-1$
            case COMMON_TEMPLATE -> "commonTemplates"; //$NON-NLS-1$
            case COMMON_PICTURE -> "commonPictures"; //$NON-NLS-1$
            case SCHEDULED_JOB -> "scheduledJobs"; //$NON-NLS-1$
            case FILTER_CRITERION -> "filterCriteria"; //$NON-NLS-1$
            case DEFINED_TYPE -> "definedTypes"; //$NON-NLS-1$
            case SEQUENCE -> "sequences"; //$NON-NLS-1$
            case DOCUMENT_JOURNAL -> "documentJournals"; //$NON-NLS-1$
            case DOCUMENT_NUMERATOR -> "documentNumerators"; //$NON-NLS-1$
            case EVENT_SUBSCRIPTION -> "eventSubscriptions"; //$NON-NLS-1$
            case FUNCTIONAL_OPTION -> "functionalOptions"; //$NON-NLS-1$
            case FUNCTIONAL_OPTIONS_PARAMETER -> "functionalOptionsParameters"; //$NON-NLS-1$
            case WEB_SERVICE -> "webServices"; //$NON-NLS-1$
            case HTTP_SERVICE -> "httpServices"; //$NON-NLS-1$
            case EXTERNAL_DATA_SOURCE -> "externalDataSources"; //$NON-NLS-1$
            case INTEGRATION_SERVICE -> "integrationServices"; //$NON-NLS-1$
            case BOT -> "bots"; //$NON-NLS-1$
            case WEB_SOCKET_CLIENT -> "webSocketClients"; //$NON-NLS-1$
        };
    }

    private boolean hasConfigurationEntry(IFile configFile, MetadataKind kind, String fqn) {
        refreshFile(configFile);
        String content = readFile(configFile);
        if (containsConfigurationEntry(content, kind, fqn)) {
            return true;
        }
        String diskContent = readDiskFile(configFile);
        return containsConfigurationEntry(diskContent, kind, fqn);
    }

    private void refreshFile(IFile file) {
        if (file == null || !file.exists()) {
            return;
        }
        try {
            file.refreshLocal(IResource.DEPTH_ZERO, null);
        } catch (CoreException e) {
            LOG.warn("refreshFile failed for %s: %s", file.getFullPath(), e.getMessage()); //$NON-NLS-1$
        }
    }

    private String readFile(IFile file) {
        if (file == null || !file.exists()) {
            return null;
        }
        try (var in = file.getContents()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (CoreException | IOException e) {
            LOG.warn("readFile failed for %s: %s", file.getFullPath(), e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private String readDiskFile(IFile file) {
        if (file == null || file.getLocation() == null) {
            return null;
        }
        Path path = file.getLocation().toFile().toPath();
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("readDiskFile failed for %s: %s", file.getFullPath(), e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private boolean containsConfigurationEntry(String content, MetadataKind kind, String fqn) {
        if (content == null || content.isBlank() || kind == null || fqn == null) {
            return false;
        }
        String tagName = configurationTag(kind);
        String entry = "<" + tagName + ">" + fqn + "</" + tagName + ">"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        return content.contains(entry) || content.toLowerCase(Locale.ROOT).contains(entry.toLowerCase(Locale.ROOT));
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private long asLong(Object value, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private boolean asBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool.booleanValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(text);
    }

    private static final class Snapshot {
        private final boolean idle;
        private final boolean allComputed;
        private final String status;

        private Snapshot(boolean idle, boolean allComputed, String status) {
            this.idle = idle;
            this.allComputed = allComputed;
            this.status = status;
        }

        static Snapshot capture(IDerivedDataManager ddManager) {
            DerivedDataStatus status = null;
            try {
                status = ddManager.getDerivedDataStatus();
            } catch (RuntimeException ignored) {
                // Best effort snapshot only.
            }
            return new Snapshot(ddManager.isIdle(), ddManager.isAllComputed(), describeStatus(status));
        }

        boolean sameAs(Snapshot other) {
            if (other == null) {
                return false;
            }
            return idle == other.idle
                    && allComputed == other.allComputed
                    && String.valueOf(status).equals(String.valueOf(other.status));
        }

        String format(long elapsedMs) {
            return "t=" + elapsedMs //$NON-NLS-1$
                    + "ms idle=" + idle //$NON-NLS-1$
                    + " allComputed=" + allComputed //$NON-NLS-1$
                    + " status=" + status; //$NON-NLS-1$
        }

        private static String describeStatus(DerivedDataStatus status) {
            if (status == null) {
                return "null"; //$NON-NLS-1$
            }
            StringBuilder out = new StringBuilder(status.getClass().getSimpleName());
            out.append('{');
            boolean hasData = false;
            for (var method : status.getClass().getMethods()) {
                String name = method.getName();
                if (method.getParameterCount() != 0 || "getClass".equals(name)) { //$NON-NLS-1$
                    continue;
                }
                boolean getter = name.startsWith("get") || name.startsWith("is"); //$NON-NLS-1$ //$NON-NLS-2$
                if (!getter || method.getReturnType() == Void.TYPE) {
                    continue;
                }
                try {
                    Object value = method.invoke(status);
                    if (hasData) {
                        out.append(", "); //$NON-NLS-1$
                    }
                    out.append(name).append('=').append(value);
                    hasData = true;
                } catch (ReflectiveOperationException ignored) {
                    // Ignore reflection errors and continue with best effort.
                }
            }
            if (!hasData) {
                out.append("toString=").append(status); //$NON-NLS-1$
            }
            out.append('}');
            return out.toString();
        }
    }
}
