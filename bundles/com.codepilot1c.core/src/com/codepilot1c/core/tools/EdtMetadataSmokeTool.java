package com.codepilot1c.core.tools;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.core.platform.IBmModelManager;
import com.codepilot1c.core.edt.metadata.AddMetadataChildRequest;
import com.codepilot1c.core.edt.metadata.CreateMetadataRequest;
import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;
import com.codepilot1c.core.edt.metadata.EdtMetadataService;
import com.codepilot1c.core.edt.metadata.MetadataChildKind;
import com.codepilot1c.core.edt.metadata.MetadataKind;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.metadata.MetadataProjectReadinessChecker;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Smoke regression scenarios for metadata creation flows in EDT runtime.
 */
public class EdtMetadataSmokeTool implements ITool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(EdtMetadataSmokeTool.class);
    private static final DateTimeFormatter RUN_SUFFIX_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss"); //$NON-NLS-1$

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {"type": "string", "description": "Имя EDT проекта"},
                "name_prefix": {"type": "string", "description": "Префикс для создаваемых объектов (default=Smoke)"},
                "dry_run": {"type": "boolean", "description": "Если true, выполняются только readiness/BM probes без мутаций"},
                "run_mutations": {"type": "boolean", "description": "Выполнять создающие операции (default=true)"}
              },
              "required": ["project"]
            }
            """; //$NON-NLS-1$

    private final EdtMetadataGateway gateway;
    private final EdtMetadataService metadataService;
    private final MetadataProjectReadinessChecker readinessChecker;

    public EdtMetadataSmokeTool() {
        this(new EdtMetadataGateway(), new EdtMetadataService());
    }

    EdtMetadataSmokeTool(EdtMetadataGateway gateway, EdtMetadataService metadataService) {
        this.gateway = gateway;
        this.metadataService = metadataService;
        this.readinessChecker = new MetadataProjectReadinessChecker(gateway);
    }

    @Override
    public String getName() {
        return "edt_metadata_smoke"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Прогоняет smoke-сценарии create/add_child/duplicate/readiness для EDT metadata API."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public boolean requiresConfirmation() {
        return true;
    }

    @Override
    public boolean isDestructive() {
        return true;
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("md-smoke"); //$NON-NLS-1$
            LOG.info("[%s] START edt_metadata_smoke", opId); //$NON-NLS-1$
            try {
                String projectName = asString(parameters.get("project")); //$NON-NLS-1$
                String prefix = asOptionalString(parameters.get("name_prefix")); //$NON-NLS-1$
                boolean dryRun = asBoolean(parameters.get("dry_run"), false); //$NON-NLS-1$
                boolean runMutations = asBoolean(parameters.get("run_mutations"), true); //$NON-NLS-1$
                if (dryRun) {
                    runMutations = false;
                }

                if (projectName == null || projectName.isBlank()) {
                    return ToolResult.failure("[INVALID_ARGUMENT] project is required"); //$NON-NLS-1$
                }
                if (!metadataService.isEdtAvailable()) {
                    return ToolResult.failure("[EDT_SERVICE_UNAVAILABLE] EDT runtime services are unavailable"); //$NON-NLS-1$
                }

                String runSuffix = LocalDateTime.now().format(RUN_SUFFIX_FORMAT);
                String safePrefix = sanitizePrefix(prefix == null ? "Smoke" : prefix); //$NON-NLS-1$
                String namePrefix = safePrefix + runSuffix;
                LOG.info("[%s] Smoke run project=%s dry_run=%s run_mutations=%s namePrefix=%s", // $NON-NLS-1$
                        opId, projectName, dryRun, runMutations, namePrefix);

                List<StepResult> steps = new ArrayList<>();
                IProject project = gateway.resolveProject(projectName);
                if (project == null || !project.exists()) {
                    steps.add(StepResult.failed("project_exists", "PROJECT_NOT_FOUND", "Project not found: " + projectName)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    return ToolResult.failure(renderReport(projectName, namePrefix, steps));
                }

                try {
                    readinessChecker.ensureReady(project);
                    steps.add(StepResult.ok("readiness_precheck", "Project is ready")); //$NON-NLS-1$ //$NON-NLS-2$
                } catch (MetadataOperationException e) {
                    steps.add(StepResult.failed("readiness_precheck", e.getCode().name(), e.getMessage())); //$NON-NLS-1$
                    return ToolResult.failure(renderReport(projectName, namePrefix, steps));
                }

                probeBmReadOnlyTransaction(project, steps);

                if (!runMutations) {
                    String reason = dryRun ? "dry_run=true" : "run_mutations=false"; //$NON-NLS-1$ //$NON-NLS-2$
                    steps.add(StepResult.ok("mutations_skipped", reason)); //$NON-NLS-1$
                    return ToolResult.success(renderReport(projectName, namePrefix, steps));
                }

                String catalogName = trimName(namePrefix + "Catalog", 40); //$NON-NLS-1$
                String docName = trimName(namePrefix + "Document", 40); //$NON-NLS-1$
                String reportName = trimName(namePrefix + "Report", 40); //$NON-NLS-1$
                String tableName = trimName("Items" + runSuffix.substring(Math.max(0, runSuffix.length() - 6)), 40); //$NON-NLS-1$
                String attrName = trimName("Qty" + runSuffix.substring(Math.max(0, runSuffix.length() - 6)), 40); //$NON-NLS-1$

                createTopLevel(projectName, MetadataKind.CATALOG, catalogName, steps, "create_catalog"); //$NON-NLS-1$
                checkDuplicateTopLevel(projectName, MetadataKind.CATALOG, catalogName, steps, "duplicate_catalog_expected"); //$NON-NLS-1$
                createTopLevel(projectName, MetadataKind.DOCUMENT, docName, steps, "create_document"); //$NON-NLS-1$
                createTopLevel(projectName, MetadataKind.REPORT, reportName, steps, "create_report"); //$NON-NLS-1$

                String docFqn = MetadataKind.DOCUMENT.getFqnPrefix() + "." + docName; //$NON-NLS-1$
                String tableFqn = addChild(projectName, docFqn, MetadataChildKind.TABULAR_SECTION, tableName,
                        steps, "add_document_tabular_section"); //$NON-NLS-1$
                if (tableFqn != null) {
                    addChild(projectName, tableFqn, MetadataChildKind.ATTRIBUTE, attrName,
                            steps, "add_tabular_section_attribute"); //$NON-NLS-1$
                }

                boolean failed = steps.stream().anyMatch(step -> !step.success);
                String report = renderReport(projectName, namePrefix, steps);
                if (failed) {
                    return ToolResult.failure(report);
                }
                return ToolResult.success(report);
            } catch (Exception e) {
                LOG.error("[" + opId + "] edt_metadata_smoke failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure("[INTERNAL_ERROR] " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private void probeBmReadOnlyTransaction(IProject project, List<StepResult> steps) {
        try {
            IBmModelManager modelManager = gateway.getBmModelManager();
            Boolean ok = modelManager.executeReadOnlyTask(project, tx -> Boolean.TRUE);
            if (Boolean.TRUE.equals(ok)) {
                steps.add(StepResult.ok("bm_read_tx_probe", "Read-only BM transaction is available")); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            steps.add(StepResult.failed("bm_read_tx_probe", "ASSERTION_FAILED", "Read-only BM transaction returned unexpected value")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        } catch (RuntimeException e) {
            steps.add(StepResult.failed("bm_read_tx_probe", MetadataOperationCode.EDT_SERVICE_UNAVAILABLE.name(), e.getMessage())); //$NON-NLS-1$
        }
    }

    private void createTopLevel(
            String projectName,
            MetadataKind kind,
            String name,
            List<StepResult> steps,
            String stepName
    ) {
        try {
            metadataService.createMetadata(new CreateMetadataRequest(projectName, kind, name, null, null, Map.of()));
            steps.add(StepResult.ok(stepName, kind.getFqnPrefix() + "." + name)); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            steps.add(StepResult.failed(stepName, e.getCode().name(), e.getMessage()));
        }
    }

    private void checkDuplicateTopLevel(
            String projectName,
            MetadataKind kind,
            String name,
            List<StepResult> steps,
            String stepName
    ) {
        try {
            metadataService.createMetadata(new CreateMetadataRequest(projectName, kind, name, null, null, Map.of()));
            steps.add(StepResult.failed(stepName, "ASSERTION_FAILED", "Duplicate create unexpectedly succeeded")); //$NON-NLS-1$ //$NON-NLS-2$
        } catch (MetadataOperationException e) {
            if (e.getCode() == MetadataOperationCode.METADATA_ALREADY_EXISTS) {
                steps.add(StepResult.ok(stepName, "Got expected METADATA_ALREADY_EXISTS")); //$NON-NLS-1$
                return;
            }
            steps.add(StepResult.failed(stepName, e.getCode().name(), e.getMessage()));
        }
    }

    private String addChild(
            String projectName,
            String parentFqn,
            MetadataChildKind childKind,
            String name,
            List<StepResult> steps,
            String stepName
    ) {
        try {
            var result = metadataService.addMetadataChild(new AddMetadataChildRequest(
                    projectName, parentFqn, childKind, name, null, null, Map.of()));
            steps.add(StepResult.ok(stepName, result.fqn()));
            return result.fqn();
        } catch (MetadataOperationException e) {
            steps.add(StepResult.failed(stepName, e.getCode().name(), e.getMessage()));
            return null;
        }
    }

    private String renderReport(String projectName, String namePrefix, List<StepResult> steps) {
        boolean failed = steps.stream().anyMatch(step -> !step.success);
        StringBuilder out = new StringBuilder();
        out.append("EDT Metadata Smoke Report\n"); //$NON-NLS-1$
        out.append("project: ").append(projectName).append('\n'); //$NON-NLS-1$
        out.append("name_prefix: ").append(namePrefix).append('\n'); //$NON-NLS-1$
        out.append("result: ").append(failed ? "FAILED" : "SUCCESS").append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (StepResult step : steps) {
            out.append(step.success ? "[OK] " : "[FAIL] "); //$NON-NLS-1$ //$NON-NLS-2$
            out.append(step.name);
            if (step.code != null && !step.code.isBlank()) {
                out.append(" code=").append(step.code); //$NON-NLS-1$
            }
            if (step.message != null && !step.message.isBlank()) {
                out.append(" message=").append(step.message); //$NON-NLS-1$
            }
            out.append('\n');
        }
        return out.toString();
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String asOptionalString(Object value) {
        String text = asString(value);
        return text == null || text.isBlank() ? null : text;
    }

    private boolean asBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean boolValue) {
            return boolValue;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private String sanitizePrefix(String prefix) {
        String raw = prefix == null ? "Smoke" : prefix; //$NON-NLS-1$
        String sanitized = raw.replaceAll("[^A-Za-z0-9_]", ""); //$NON-NLS-1$ //$NON-NLS-2$
        if (sanitized.isBlank()) {
            return "Smoke"; //$NON-NLS-1$
        }
        return sanitized;
    }

    private String trimName(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static final class StepResult {
        private final boolean success;
        private final String name;
        private final String code;
        private final String message;

        private StepResult(boolean success, String name, String code, String message) {
            this.success = success;
            this.name = name;
            this.code = code;
            this.message = message;
        }

        private static StepResult ok(String name, String message) {
            return new StepResult(true, name, "", message); //$NON-NLS-1$
        }

        private static StepResult failed(String name, String code, String message) {
            return new StepResult(false, name, code, message);
        }
    }
}
