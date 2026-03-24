package com.codepilot1c.core.tools.external;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.external.EdtExternalObjectService;
import com.codepilot1c.core.edt.external.ExternalCreateProcessingRequest;
import com.codepilot1c.core.edt.external.ExternalCreateObjectResult;
import com.codepilot1c.core.edt.external.ExternalCreateReportRequest;
import com.codepilot1c.core.edt.external.ExternalGetDetailsRequest;
import com.codepilot1c.core.edt.external.ExternalListObjectsRequest;
import com.codepilot1c.core.edt.external.ExternalListProjectsRequest;
import com.codepilot1c.core.edt.external.ExternalObjectDetailsResult;
import com.codepilot1c.core.edt.external.ExternalObjectsResult;
import com.codepilot1c.core.edt.external.ExternalProjectsResult;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Composite external object tool replacing 5 individual external tools.
 *
 * <p>Commands: list_projects, list_objects, details, create_report, create_processing</p>
 */
@ToolMeta(name = "external_manage", category = "external", mutating = true, tags = {"workspace", "edt"})
public class ExternalManageTool extends AbstractTool {

    private static final Gson GSON = new Gson();

    private static final Set<String> ALL_COMMANDS = Set.of(
            "list_projects", "list_objects", "details", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "create_report", "create_processing"); //$NON-NLS-1$ //$NON-NLS-2$

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "command": {
                  "type": "string",
                  "description": "Command: list_projects|list_objects|details|create_report|create_processing",
                  "enum": ["list_projects", "list_objects", "details", "create_report", "create_processing"]
                },
                "project": {
                  "type": "string",
                  "description": "Base EDT project name"
                },
                "external_project": {
                  "type": "string",
                  "description": "External object project name"
                },
                "object_fqn": {
                  "type": "string",
                  "description": "(details) FQN of external object"
                },
                "name": {
                  "type": "string",
                  "description": "(create_report/create_processing) Object name"
                },
                "type_filter": {
                  "type": "string",
                  "description": "(list_objects) Filter by type"
                },
                "name_contains": {
                  "type": "string",
                  "description": "(list_projects/list_objects) Case-insensitive name filter"
                },
                "limit": {
                  "type": "integer",
                  "description": "(list) Page size (1..1000, default 100)"
                },
                "offset": {
                  "type": "integer",
                  "description": "(list) Pagination offset"
                },
                "project_path": {
                  "type": "string",
                  "description": "(create) Path for new project"
                },
                "version": {
                  "type": "string",
                  "description": "(create) Platform version"
                },
                "synonym": {
                  "type": "string",
                  "description": "(create) Object synonym"
                },
                "comment": {
                  "type": "string",
                  "description": "(create) Object comment"
                },
                "validation_token": {
                  "type": "string",
                  "description": "(mutating commands) One-time token from edt_validate_request"
                }
              },
              "required": ["command", "project"]
            }
            """; //$NON-NLS-1$

    private final EdtExternalObjectService service;
    private final MetadataRequestValidationService validationService;

    public ExternalManageTool() {
        this(new EdtExternalObjectService(), new MetadataRequestValidationService());
    }

    ExternalManageTool(EdtExternalObjectService service, MetadataRequestValidationService validationService) {
        this.service = service;
        this.validationService = validationService;
    }

    @Override
    public String getDescription() {
        return "Manage external reports/processors: list, details, create."; //$NON-NLS-1$
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
            Map<String, Object> p = params.getRaw();
            String command = asString(p.get("command")); //$NON-NLS-1$
            if (command == null || !ALL_COMMANDS.contains(command)) {
                return ToolResult.failure("Unknown command: " + command + //$NON-NLS-1$
                        ". Use one of: " + String.join(", ", ALL_COMMANDS)); //$NON-NLS-1$ //$NON-NLS-2$
            }
            try {
                return switch (command) {
                    case "list_projects" -> doListProjects(p); //$NON-NLS-1$
                    case "list_objects" -> doListObjects(p); //$NON-NLS-1$
                    case "details" -> doGetDetails(p); //$NON-NLS-1$
                    case "create_report" -> doCreateReport(p); //$NON-NLS-1$
                    case "create_processing" -> doCreateProcessing(p); //$NON-NLS-1$
                    default -> ToolResult.failure("Unknown command: " + command); //$NON-NLS-1$
                };
            } catch (MetadataOperationException e) {
                return ToolResult.failure(toErrorJson(e));
            } catch (Exception e) {
                return ToolResult.failure("INTERNAL_ERROR: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private ToolResult doListProjects(Map<String, Object> p) throws MetadataOperationException {
        ExternalListProjectsRequest request = new ExternalListProjectsRequest(
                asString(p.get("project")), //$NON-NLS-1$
                asOptionalString(p.get("name_contains")), //$NON-NLS-1$
                asOptionalInteger(p.get("limit")), //$NON-NLS-1$
                asOptionalInteger(p.get("offset"))); //$NON-NLS-1$
        ExternalProjectsResult result = service.listProjects(request);
        return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.SEARCH_RESULTS);
    }

    private ToolResult doListObjects(Map<String, Object> p) throws MetadataOperationException {
        ExternalListObjectsRequest request = new ExternalListObjectsRequest(
                asString(p.get("project")), //$NON-NLS-1$
                asOptionalString(p.get("external_project")), //$NON-NLS-1$
                asOptionalString(p.get("type_filter")), //$NON-NLS-1$
                asOptionalString(p.get("name_contains")), //$NON-NLS-1$
                asOptionalInteger(p.get("limit")), //$NON-NLS-1$
                asOptionalInteger(p.get("offset"))); //$NON-NLS-1$
        ExternalObjectsResult result = service.listObjects(request);
        return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.SEARCH_RESULTS);
    }

    private ToolResult doGetDetails(Map<String, Object> p) throws MetadataOperationException {
        ExternalGetDetailsRequest request = new ExternalGetDetailsRequest(
                asString(p.get("project")), //$NON-NLS-1$
                asString(p.get("object_fqn")), //$NON-NLS-1$
                asOptionalString(p.get("external_project"))); //$NON-NLS-1$
        ExternalObjectDetailsResult result = service.getDetails(request);
        return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.SEARCH_RESULTS);
    }

    private ToolResult doCreateReport(Map<String, Object> p) throws MetadataOperationException {
        String project = asString(p.get("project")); //$NON-NLS-1$
        String externalProject = asString(p.get("external_project")); //$NON-NLS-1$
        String name = asString(p.get("name")); //$NON-NLS-1$
        String projectPath = asOptionalString(p.get("project_path")); //$NON-NLS-1$
        String version = asOptionalString(p.get("version")); //$NON-NLS-1$
        String synonym = asOptionalString(p.get("synonym")); //$NON-NLS-1$
        String comment = asOptionalString(p.get("comment")); //$NON-NLS-1$
        String validationToken = asString(p.get("validation_token")); //$NON-NLS-1$

        validationService.normalizeExternalCreateReportPayload(
                project, externalProject, name, projectPath, version, synonym, comment);

        Map<String, Object> v = validationService.consumeToken(
                validationToken, ValidationOperation.EXTERNAL_CREATE_REPORT, project);

        ExternalCreateReportRequest request = new ExternalCreateReportRequest(
                asString(v.get("project")), //$NON-NLS-1$
                asString(v.get("external_project")), //$NON-NLS-1$
                asString(v.get("name")), //$NON-NLS-1$
                asOptionalString(v.get("project_path")), //$NON-NLS-1$
                asOptionalString(v.get("version")), //$NON-NLS-1$
                asOptionalString(v.get("synonym")), //$NON-NLS-1$
                asOptionalString(v.get("comment"))); //$NON-NLS-1$
        ExternalCreateObjectResult result = service.createReport(request);
        return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.CONFIRMATION);
    }

    private ToolResult doCreateProcessing(Map<String, Object> p) throws MetadataOperationException {
        String project = asString(p.get("project")); //$NON-NLS-1$
        String externalProject = asString(p.get("external_project")); //$NON-NLS-1$
        String name = asString(p.get("name")); //$NON-NLS-1$
        String projectPath = asOptionalString(p.get("project_path")); //$NON-NLS-1$
        String version = asOptionalString(p.get("version")); //$NON-NLS-1$
        String synonym = asOptionalString(p.get("synonym")); //$NON-NLS-1$
        String comment = asOptionalString(p.get("comment")); //$NON-NLS-1$
        String validationToken = asString(p.get("validation_token")); //$NON-NLS-1$

        validationService.normalizeExternalCreateProcessingPayload(
                project, externalProject, name, projectPath, version, synonym, comment);

        Map<String, Object> v = validationService.consumeToken(
                validationToken, ValidationOperation.EXTERNAL_CREATE_PROCESSING, project);

        ExternalCreateProcessingRequest request = new ExternalCreateProcessingRequest(
                asString(v.get("project")), //$NON-NLS-1$
                asString(v.get("external_project")), //$NON-NLS-1$
                asString(v.get("name")), //$NON-NLS-1$
                asOptionalString(v.get("project_path")), //$NON-NLS-1$
                asOptionalString(v.get("version")), //$NON-NLS-1$
                asOptionalString(v.get("synonym")), //$NON-NLS-1$
                asOptionalString(v.get("comment"))); //$NON-NLS-1$
        ExternalCreateObjectResult result = service.createProcessing(request);
        return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.CONFIRMATION);
    }

    // --- Helpers ---

    private String toErrorJson(MetadataOperationException e) {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", e.getCode().name()); //$NON-NLS-1$
        obj.addProperty("message", e.getMessage()); //$NON-NLS-1$
        obj.addProperty("recoverable", e.isRecoverable()); //$NON-NLS-1$
        return GSON.toJson(obj);
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String asOptionalString(Object value) {
        if (value == null) return null;
        String raw = String.valueOf(value).trim();
        return raw.isEmpty() ? null : raw;
    }

    private Integer asOptionalInteger(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return Integer.valueOf(number.intValue());
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty()) return null;
        return Integer.valueOf(Integer.parseInt(raw));
    }
}
