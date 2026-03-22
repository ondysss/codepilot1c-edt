package com.codepilot1c.core.tools.diagnostics;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.codepilot1c.core.edt.external.EdtExternalObjectService;
import com.codepilot1c.core.edt.external.ExternalCreateObjectResult;
import com.codepilot1c.core.edt.external.ExternalCreateProcessingRequest;
import com.codepilot1c.core.edt.external.ExternalCreateReportRequest;
import com.codepilot1c.core.edt.external.ExternalGetDetailsRequest;
import com.codepilot1c.core.edt.external.ExternalListObjectsRequest;
import com.codepilot1c.core.edt.external.ExternalListProjectsRequest;
import com.codepilot1c.core.edt.external.ExternalObjectDetailsResult;
import com.codepilot1c.core.edt.external.ExternalObjectSummary;
import com.codepilot1c.core.edt.external.ExternalObjectsResult;
import com.codepilot1c.core.edt.external.ExternalProjectSummary;
import com.codepilot1c.core.edt.external.ExternalProjectsResult;
import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * End-to-end smoke flow for EDT external object APIs.
 */
@ToolMeta(name = "edt_external_smoke", category = "external", tags = {"workspace", "edt"})
public class EdtExternalSmokeTool extends AbstractTool {

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {"type": "string", "description": "Имя базового EDT проекта"},
                "external_project": {"type": "string", "description": "Имя внешнего проекта (опционально, иначе авто-генерация)"},
                "kind": {"type": "string", "description": "Тип внешнего объекта: report|processing (default=report)"},
                "name": {"type": "string", "description": "Имя внешнего объекта (опционально)"},
                "cleanup_created": {"type": "boolean", "description": "Удалить созданный проект после smoke (default=true)"}
              },
              "required": ["project"]
            }
            """; //$NON-NLS-1$

    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyyMMddHHmmss"); //$NON-NLS-1$
    private static final int LIST_WAIT_ATTEMPTS = 8;
    private static final long LIST_WAIT_SLEEP_MS = 250L;

    private final EdtExternalObjectService externalService;
    private final EdtMetadataGateway gateway;

    public EdtExternalSmokeTool() {
        this(new EdtExternalObjectService(), new EdtMetadataGateway());
    }

    EdtExternalSmokeTool(EdtExternalObjectService externalService, EdtMetadataGateway gateway) {
        this.externalService = externalService;
        this.gateway = gateway;
    }

    @Override
    public String getDescription() {
        return "Прогоняет smoke-сценарий EDT external API: create/list/get_details/cleanup."; //$NON-NLS-1$
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
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> parameters = params.getRaw();
            List<StepResult> steps = new ArrayList<>();
            String baseProjectName = asString(parameters.get("project")); //$NON-NLS-1$
            if (baseProjectName == null || baseProjectName.isBlank()) {
                return ToolResult.failure("[INVALID_ARGUMENT] project is required"); //$NON-NLS-1$
            }
            baseProjectName = baseProjectName.trim();

            String requestedExternalProject = asOptionalString(parameters.get("external_project")); //$NON-NLS-1$
            String requestedName = asOptionalString(parameters.get("name")); //$NON-NLS-1$
            String kind = normalizeKind(asOptionalString(parameters.get("kind"))); //$NON-NLS-1$
            boolean cleanupCreated = asBoolean(parameters.get("cleanup_created"), true); //$NON-NLS-1$

            String generatedSuffix = LocalDateTime.now().format(SUFFIX);
            String generatedExternalProject = "SmokeExtObj" + generatedSuffix; //$NON-NLS-1$
            String externalProjectName = requestedExternalProject != null ? requestedExternalProject : generatedExternalProject;
            String generatedObjectName = "SmokeObj" + generatedSuffix; //$NON-NLS-1$
            String objectName = requestedName != null ? requestedName : generatedObjectName;
            boolean createdBySmoke = false;
            String objectFqn = null;

            try {
                gateway.ensureExternalObjectRuntimeAvailable();
                steps.add(StepResult.ok("runtime_precheck", "External object runtime is available")); //$NON-NLS-1$ //$NON-NLS-2$

                IProject baseProject = gateway.resolveProject(baseProjectName);
                if (baseProject == null || !baseProject.exists()) {
                    steps.add(StepResult.failed("base_project_exists", MetadataOperationCode.PROJECT_NOT_FOUND.name(), //$NON-NLS-1$
                            "Base project not found: " + baseProjectName)); //$NON-NLS-1$
                    return ToolResult.failure(renderReport(baseProjectName, externalProjectName, kind, steps));
                }
                steps.add(StepResult.ok("base_project_exists", baseProjectName)); //$NON-NLS-1$

                ExternalProjectsResult before = externalService.listProjects(new ExternalListProjectsRequest(
                        baseProjectName,
                        null,
                        Integer.valueOf(1000),
                        Integer.valueOf(0)));
                boolean existsBefore = containsExternalProject(before, externalProjectName);
                if (existsBefore) {
                    steps.add(StepResult.ok("external_project_exists_before", externalProjectName)); //$NON-NLS-1$
                } else {
                    ExternalCreateObjectResult created = "processing".equals(kind) //$NON-NLS-1$
                            ? externalService.createProcessing(new ExternalCreateProcessingRequest(
                                    baseProjectName,
                                    externalProjectName,
                                    objectName,
                                    null,
                                    null,
                                    null,
                                    null))
                            : externalService.createReport(new ExternalCreateReportRequest(
                                    baseProjectName,
                                    externalProjectName,
                                    objectName,
                                    null,
                                    null,
                                    null,
                                    null));
                    createdBySmoke = true;
                    objectFqn = created.objectFqn();
                    externalProjectName = created.externalProject();
                    steps.add(StepResult.ok("create_external_project", externalProjectName)); //$NON-NLS-1$
                    steps.add(StepResult.ok("create_external_object", objectFqn)); //$NON-NLS-1$
                }

                ExternalProjectsResult projectsAfter = externalService.listProjects(new ExternalListProjectsRequest(
                        baseProjectName,
                        null,
                        Integer.valueOf(1000),
                        Integer.valueOf(0)));
                if (!containsExternalProject(projectsAfter, externalProjectName)) {
                    projectsAfter = waitForExternalProjectListed(baseProjectName, externalProjectName);
                }
                if (!containsExternalProject(projectsAfter, externalProjectName)) {
                    steps.add(StepResult.failed("list_projects_after_create", "ASSERTION_FAILED", //$NON-NLS-1$ //$NON-NLS-2$
                            "External project is absent in external_list_projects")); //$NON-NLS-1$
                    return ToolResult.failure(renderReport(baseProjectName, externalProjectName, kind, steps));
                }
                steps.add(StepResult.ok("list_projects_after_create", "External project is listed")); //$NON-NLS-1$ //$NON-NLS-2$

                ExternalObjectsResult objects = externalService.listObjects(new ExternalListObjectsRequest(
                        baseProjectName,
                        externalProjectName,
                        "processing".equals(kind) ? "ExternalDataProcessor" : "ExternalReport", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        requestedName,
                        Integer.valueOf(100),
                        Integer.valueOf(0)));
                if (objects.items().isEmpty()) {
                    steps.add(StepResult.failed("list_objects", MetadataOperationCode.EXTERNAL_OBJECT_NOT_FOUND.name(), //$NON-NLS-1$
                            "No external objects returned by external_list_objects")); //$NON-NLS-1$
                    return ToolResult.failure(renderReport(baseProjectName, externalProjectName, kind, steps));
                }
                ExternalObjectSummary chosen = objects.items().get(0);
                if (objectFqn == null || objectFqn.isBlank()) {
                    objectFqn = chosen.fqn();
                }
                steps.add(StepResult.ok("list_objects", objectFqn)); //$NON-NLS-1$

                ExternalObjectDetailsResult details = externalService.getDetails(new ExternalGetDetailsRequest(
                        baseProjectName,
                        externalProjectName,
                        objectFqn));
                steps.add(StepResult.ok("get_details", details.kind() + " " + details.name())); //$NON-NLS-1$ //$NON-NLS-2$

                return ToolResult.success(renderReport(baseProjectName, externalProjectName, kind, steps));
            } catch (MetadataOperationException e) {
                steps.add(StepResult.failed("smoke_error", e.getCode().name(), e.getMessage())); //$NON-NLS-1$
                return ToolResult.failure(renderReport(baseProjectName, externalProjectName, kind, steps));
            } catch (Exception e) {
                steps.add(StepResult.failed("smoke_error", "INTERNAL_ERROR", e.getMessage())); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure(renderReport(baseProjectName, externalProjectName, kind, steps));
            } finally {
                cleanupCreatedExternalProject(externalProjectName, createdBySmoke, cleanupCreated, steps);
            }
        });
    }

    private void cleanupCreatedExternalProject(
            String externalProjectName,
            boolean createdBySmoke,
            boolean cleanupCreated,
            List<StepResult> steps
    ) {
        if (!createdBySmoke) {
            return;
        }
        if (!cleanupCreated) {
            steps.add(StepResult.ok("cleanup_skipped", "cleanup_created=false")); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        IProject project = gateway.resolveProject(externalProjectName);
        if (project == null || !project.exists()) {
            steps.add(StepResult.ok("cleanup_created_external_project", "Project already absent")); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        try {
            project.delete(true, true, new NullProgressMonitor());
            steps.add(StepResult.ok("cleanup_created_external_project", "Deleted " + externalProjectName)); //$NON-NLS-1$ //$NON-NLS-2$
        } catch (CoreException e) {
            steps.add(StepResult.failed("cleanup_created_external_project", MetadataOperationCode.EDT_TRANSACTION_FAILED.name(), //$NON-NLS-1$
                    e.getMessage()));
        }
    }

    private ExternalProjectsResult waitForExternalProjectListed(String baseProjectName, String externalProjectName) {
        ExternalProjectsResult last = null;
        for (int attempt = 1; attempt <= LIST_WAIT_ATTEMPTS; attempt++) {
            last = externalService.listProjects(new ExternalListProjectsRequest(
                    baseProjectName,
                    null,
                    Integer.valueOf(1000),
                    Integer.valueOf(0)));
            if (containsExternalProject(last, externalProjectName)) {
                return last;
            }
            sleep(LIST_WAIT_SLEEP_MS);
        }
        return last;
    }

    private boolean containsExternalProject(ExternalProjectsResult projects, String externalProjectName) {
        if (projects == null || projects.items() == null || externalProjectName == null) {
            return false;
        }
        for (ExternalProjectSummary item : projects.items()) {
            if (item == null || item.externalProject() == null) {
                continue;
            }
            if (item.externalProject().equalsIgnoreCase(externalProjectName)) {
                return true;
            }
        }
        return false;
    }

    private String renderReport(String baseProjectName, String externalProjectName, String kind, List<StepResult> steps) {
        boolean failed = steps.stream().anyMatch(step -> !step.success);
        StringBuilder out = new StringBuilder();
        out.append("EDT External Objects Smoke Report\n"); //$NON-NLS-1$
        out.append("project: ").append(baseProjectName).append('\n'); //$NON-NLS-1$
        out.append("external_project: ").append(externalProjectName).append('\n'); //$NON-NLS-1$
        out.append("kind: ").append(kind).append('\n'); //$NON-NLS-1$
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
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private boolean asBoolean(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean boolValue) {
            return boolValue.booleanValue();
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private String normalizeKind(String value) {
        if (value == null || value.isBlank()) {
            return "report"; //$NON-NLS-1$
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "report", "externalreport", "отчет", "внешнийотчет" -> "report"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "processing", "externaldataprocessor", "обработка", "внешняяобработка" -> "processing"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            default -> "report"; //$NON-NLS-1$
        };
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
