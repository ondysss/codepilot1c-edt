package com.codepilot1c.core.tools.extension;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.extension.EdtExtensionService;
import com.codepilot1c.core.edt.extension.ExtensionAdoptObjectRequest;
import com.codepilot1c.core.edt.extension.ExtensionAdoptObjectResult;
import com.codepilot1c.core.edt.extension.ExtensionCreateProjectRequest;
import com.codepilot1c.core.edt.extension.ExtensionCreateProjectResult;
import com.codepilot1c.core.edt.extension.ExtensionListObjectsRequest;
import com.codepilot1c.core.edt.extension.ExtensionListProjectsRequest;
import com.codepilot1c.core.edt.extension.ExtensionObjectsResult;
import com.codepilot1c.core.edt.extension.ExtensionProjectsResult;
import com.codepilot1c.core.edt.extension.ExtensionSetPropertyStateRequest;
import com.codepilot1c.core.edt.extension.ExtensionSetPropertyStateResult;
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
 * Composite extension tool replacing 5 individual extension tools.
 *
 * <p>Commands: list_projects, list_objects, create, adopt, set_state</p>
 */
@ToolMeta(name = "extension_manage", category = "extension", mutating = true, tags = {"workspace", "edt"})
public class ExtensionManageTool extends AbstractTool {

    private static final Gson GSON = new Gson();

    private static final Set<String> ALL_COMMANDS = Set.of(
            "list_projects", "list_objects", "create", "adopt", "set_state"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "command": {
                  "type": "string",
                  "description": "Command for extension workflows: list_projects, list_objects, create, adopt, or set_state",
                  "enum": ["list_projects", "list_objects", "create", "adopt", "set_state"]
                },
                "project": {
                  "type": "string",
                  "description": "Base EDT project name"
                },
                "base_project": {
                  "type": "string",
                  "description": "Base configuration project name"
                },
                "extension_project": {
                  "type": "string",
                  "description": "Extension project name"
                },
                "type_filter": {
                  "type": "string",
                  "description": "(list_objects) Filter by type token"
                },
                "name_contains": {
                  "type": "string",
                  "description": "(list_objects) Case-insensitive name filter"
                },
                "limit": {
                  "type": "integer",
                  "description": "(list_objects) Page size (1..1000, default 100)"
                },
                "offset": {
                  "type": "integer",
                  "description": "(list_objects) Pagination offset"
                },
                "project_path": {
                  "type": "string",
                  "description": "(create) Path for new project"
                },
                "version": {
                  "type": "string",
                  "description": "(create) Platform version"
                },
                "configuration_name": {
                  "type": "string",
                  "description": "(create) Root Configuration name"
                },
                "purpose": {
                  "type": "string",
                  "description": "(create) Extension purpose: ADD_ON|CUSTOMIZATION|PATCH"
                },
                "compatibility_mode": {
                  "type": "string",
                  "description": "(create) Compatibility mode"
                },
                "source_object_fqn": {
                  "type": "string",
                  "description": "(adopt/set_state) FQN of base configuration object"
                },
                "update_if_exists": {
                  "type": "boolean",
                  "description": "(adopt) Update if already adopted"
                },
                "property_name": {
                  "type": "string",
                  "description": "(set_state) Property name"
                },
                "state": {
                  "type": "string",
                  "description": "(set_state) Property state: NONE|CHECKED|EXTENDED|NOTIFY"
                },
                "validation_token": {
                  "type": "string",
                  "description": "(mutating commands) One-time token from edt_validate_request; required for create, adopt, and set_state"
                }
              },
              "required": ["command"]
            }
            """; //$NON-NLS-1$

    private final EdtExtensionService service;
    private final MetadataRequestValidationService validationService;

    public ExtensionManageTool() {
        this(new EdtExtensionService(), new MetadataRequestValidationService());
    }

    ExtensionManageTool(EdtExtensionService service, MetadataRequestValidationService validationService) {
        this.service = service;
        this.validationService = validationService;
    }

    @Override
    public String getDescription() {
        return "Управляет расширениями EDT: показывает проекты и объекты, создаёт расширение, заимствует объект из базы и меняет состояние свойства."; //$NON-NLS-1$
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
                    case "create" -> doCreate(p); //$NON-NLS-1$
                    case "adopt" -> doAdopt(p); //$NON-NLS-1$
                    case "set_state" -> doSetState(p); //$NON-NLS-1$
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
        String baseProject = asOptionalString(p.get("base_project")); //$NON-NLS-1$
        ExtensionProjectsResult result = service.listProjects(new ExtensionListProjectsRequest(baseProject));
        return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.SEARCH_RESULTS);
    }

    private ToolResult doListObjects(Map<String, Object> p) throws MetadataOperationException {
        ExtensionListObjectsRequest request = new ExtensionListObjectsRequest(
                asString(p.get("extension_project")), //$NON-NLS-1$
                asOptionalString(p.get("type_filter")), //$NON-NLS-1$
                asOptionalString(p.get("name_contains")), //$NON-NLS-1$
                asOptionalInteger(p.get("limit")), //$NON-NLS-1$
                asOptionalInteger(p.get("offset"))); //$NON-NLS-1$
        ExtensionObjectsResult result = service.listObjects(request);
        return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.SEARCH_RESULTS);
    }

    private ToolResult doCreate(Map<String, Object> p) throws MetadataOperationException {
        String project = asString(p.get("project")); //$NON-NLS-1$
        String baseProject = asString(p.get("base_project")); //$NON-NLS-1$
        String extensionProject = asString(p.get("extension_project")); //$NON-NLS-1$
        String projectPath = asOptionalString(p.get("project_path")); //$NON-NLS-1$
        String version = asOptionalString(p.get("version")); //$NON-NLS-1$
        String configurationName = asOptionalString(p.get("configuration_name")); //$NON-NLS-1$
        String purpose = asOptionalString(p.get("purpose")); //$NON-NLS-1$
        String compatibilityMode = asOptionalString(p.get("compatibility_mode")); //$NON-NLS-1$
        String validationToken = asString(p.get("validation_token")); //$NON-NLS-1$

        validationService.normalizeExtensionCreateProjectPayload(
                project, extensionProject, baseProject, projectPath,
                version, configurationName, purpose, compatibilityMode);

        Map<String, Object> v = validationService.consumeToken(
                validationToken, ValidationOperation.EXTENSION_CREATE_PROJECT, project);

        ExtensionCreateProjectRequest request = new ExtensionCreateProjectRequest(
                asString(v.get("base_project")), //$NON-NLS-1$
                asString(v.get("extension_project")), //$NON-NLS-1$
                asOptionalString(v.get("project_path")), //$NON-NLS-1$
                asOptionalString(v.get("version")), //$NON-NLS-1$
                asOptionalString(v.get("configuration_name")), //$NON-NLS-1$
                asOptionalString(v.get("purpose")), //$NON-NLS-1$
                asOptionalString(v.get("compatibility_mode"))); //$NON-NLS-1$
        ExtensionCreateProjectResult result = service.createProject(request);
        return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.CONFIRMATION);
    }

    private ToolResult doAdopt(Map<String, Object> p) throws MetadataOperationException {
        String project = asString(p.get("project")); //$NON-NLS-1$
        String baseProject = asString(p.get("base_project")); //$NON-NLS-1$
        String extensionProject = asString(p.get("extension_project")); //$NON-NLS-1$
        String sourceObjectFqn = asString(p.get("source_object_fqn")); //$NON-NLS-1$
        Boolean updateIfExists = asOptionalBoolean(p.get("update_if_exists")); //$NON-NLS-1$
        String validationToken = asString(p.get("validation_token")); //$NON-NLS-1$

        validationService.normalizeExtensionAdoptPayload(
                project, extensionProject, baseProject, sourceObjectFqn, updateIfExists);

        Map<String, Object> v = validationService.consumeToken(
                validationToken, ValidationOperation.EXTENSION_ADOPT_OBJECT, project);

        ExtensionAdoptObjectRequest request = new ExtensionAdoptObjectRequest(
                asString(v.get("base_project")), //$NON-NLS-1$
                asString(v.get("extension_project")), //$NON-NLS-1$
                asString(v.get("source_object_fqn")), //$NON-NLS-1$
                asOptionalBoolean(v.get("update_if_exists"))); //$NON-NLS-1$
        ExtensionAdoptObjectResult result = service.adoptObject(request);
        return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.CONFIRMATION);
    }

    private ToolResult doSetState(Map<String, Object> p) throws MetadataOperationException {
        String project = asString(p.get("project")); //$NON-NLS-1$
        String baseProject = asString(p.get("base_project")); //$NON-NLS-1$
        String extensionProject = asString(p.get("extension_project")); //$NON-NLS-1$
        String sourceObjectFqn = asString(p.get("source_object_fqn")); //$NON-NLS-1$
        String propertyName = asString(p.get("property_name")); //$NON-NLS-1$
        String state = asString(p.get("state")); //$NON-NLS-1$
        String validationToken = asString(p.get("validation_token")); //$NON-NLS-1$

        validationService.normalizeExtensionSetPropertyStatePayload(
                project, extensionProject, baseProject, sourceObjectFqn, propertyName, state);

        Map<String, Object> v = validationService.consumeToken(
                validationToken, ValidationOperation.EXTENSION_SET_PROPERTY_STATE, project);

        ExtensionSetPropertyStateRequest request = new ExtensionSetPropertyStateRequest(
                asString(v.get("extension_project")), //$NON-NLS-1$
                asString(v.get("base_project")), //$NON-NLS-1$
                asString(v.get("source_object_fqn")), //$NON-NLS-1$
                asString(v.get("property_name")), //$NON-NLS-1$
                asString(v.get("state"))); //$NON-NLS-1$
        ExtensionSetPropertyStateResult result = service.setPropertyState(request);
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

    private Boolean asOptionalBoolean(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.intValue() != 0;
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty()) return null;
        return Boolean.valueOf(Boolean.parseBoolean(raw));
    }

    private Integer asOptionalInteger(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return Integer.valueOf(number.intValue());
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty()) return null;
        return Integer.valueOf(Integer.parseInt(raw));
    }
}
