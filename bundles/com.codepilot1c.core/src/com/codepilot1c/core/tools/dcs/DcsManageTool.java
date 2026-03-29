package com.codepilot1c.core.tools.dcs;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.dcs.DcsCreateMainSchemaRequest;
import com.codepilot1c.core.edt.dcs.DcsCreateMainSchemaResult;
import com.codepilot1c.core.edt.dcs.DcsGetSummaryRequest;
import com.codepilot1c.core.edt.dcs.DcsListNodesRequest;
import com.codepilot1c.core.edt.dcs.DcsListNodesResult;
import com.codepilot1c.core.edt.dcs.DcsSummaryResult;
import com.codepilot1c.core.edt.dcs.DcsUpsertCalculatedFieldRequest;
import com.codepilot1c.core.edt.dcs.DcsUpsertCalculatedFieldResult;
import com.codepilot1c.core.edt.dcs.DcsUpsertParameterRequest;
import com.codepilot1c.core.edt.dcs.DcsUpsertParameterResult;
import com.codepilot1c.core.edt.dcs.DcsUpsertQueryDatasetRequest;
import com.codepilot1c.core.edt.dcs.DcsUpsertQueryDatasetResult;
import com.codepilot1c.core.edt.dcs.EdtDcsService;
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
 * Composite DCS tool that replaces 6 individual DCS tools.
 *
 * <p>Commands:</p>
 * <ul>
 *   <li>{@code get_summary} — read DCS summary for owner object</li>
 *   <li>{@code list_nodes} — list DCS nodes with filters and pagination</li>
 *   <li>{@code create_schema} — create and bind main DCS schema (mutating)</li>
 *   <li>{@code upsert_dataset} — create/update query dataset (mutating)</li>
 *   <li>{@code upsert_param} — create/update DCS parameter (mutating)</li>
 *   <li>{@code upsert_field} — create/update calculated field (mutating)</li>
 * </ul>
 */
@ToolMeta(name = "dcs_manage", category = "dcs", mutating = true, tags = {"workspace", "edt"})
public class DcsManageTool extends AbstractTool {

    private static final Gson GSON = new Gson();

    private static final Set<String> READ_COMMANDS = Set.of(
            "get_summary", "list_nodes"); //$NON-NLS-1$ //$NON-NLS-2$

    private static final Set<String> ALL_COMMANDS = Set.of(
            "get_summary", "list_nodes", "create_schema", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "upsert_dataset", "upsert_param", "upsert_field"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "command": {
                  "type": "string",
                  "description": "DCS command: get_summary, list_nodes, create_schema, upsert_dataset, upsert_param, or upsert_field",
                  "enum": ["get_summary", "list_nodes", "create_schema", "upsert_dataset", "upsert_param", "upsert_field"]
                },
                "project": {
                  "type": "string",
                  "description": "EDT project name (base or external)"
                },
                "owner_fqn": {
                  "type": "string",
                  "description": "Owner FQN for the report or external report that owns the DCS schema"
                },
                "node_kind": {
                  "type": "string",
                  "description": "(list_nodes) Node kind: all|dataset|parameter|calculated|variant"
                },
                "name_contains": {
                  "type": "string",
                  "description": "(list_nodes) Case-insensitive name filter"
                },
                "limit": {
                  "type": "integer",
                  "description": "(list_nodes) Page size (1..1000, default 100)"
                },
                "offset": {
                  "type": "integer",
                  "description": "(list_nodes) Pagination offset (default 0)"
                },
                "template_name": {
                  "type": "string",
                  "description": "(create_schema) Optional DCS template name"
                },
                "force_replace": {
                  "type": "boolean",
                  "description": "(create_schema) Replace binding if schema exists"
                },
                "dataset_name": {
                  "type": "string",
                  "description": "(upsert_dataset) Dataset name"
                },
                "query": {
                  "type": "string",
                  "description": "(upsert_dataset) Query text"
                },
                "data_source": {
                  "type": "string",
                  "description": "(upsert_dataset) Data source name"
                },
                "auto_fill_available_fields": {
                  "type": "boolean",
                  "description": "(upsert_dataset) Auto fill flag"
                },
                "use_query_group_if_possible": {
                  "type": "boolean",
                  "description": "(upsert_dataset) Use query group flag"
                },
                "parameter_name": {
                  "type": "string",
                  "description": "(upsert_param) Parameter name"
                },
                "expression": {
                  "type": "string",
                  "description": "(upsert_param/upsert_field) Expression"
                },
                "available_as_field": {
                  "type": "boolean",
                  "description": "(upsert_param) Available as field flag"
                },
                "value_list_allowed": {
                  "type": "boolean",
                  "description": "(upsert_param) Value list allowed flag"
                },
                "deny_incomplete_values": {
                  "type": "boolean",
                  "description": "(upsert_param) Deny incomplete values flag"
                },
                "use_restriction": {
                  "type": "boolean",
                  "description": "(upsert_param) Use restriction flag"
                },
                "data_path": {
                  "type": "string",
                  "description": "(upsert_field) Calculated field data path"
                },
                "presentation_expression": {
                  "type": "string",
                  "description": "(upsert_field) Presentation expression"
                },
                "validation_token": {
                  "type": "string",
                  "description": "(mutating commands) One-time token from edt_validate_request; required for create_schema and all upsert commands"
                }
              },
              "required": ["command", "project", "owner_fqn"]
            }
            """; //$NON-NLS-1$

    private final EdtDcsService service;
    private final MetadataRequestValidationService validationService;

    public DcsManageTool() {
        this(new EdtDcsService(), new MetadataRequestValidationService());
    }

    DcsManageTool(EdtDcsService service, MetadataRequestValidationService validationService) {
        this.service = service;
        this.validationService = validationService;
    }

    @Override
    public String getDescription() {
        return "Управляет схемой компоновки данных через один tool: читает состояние, создаёт основную схему и обновляет наборы данных, параметры и вычисляемые поля."; //$NON-NLS-1$
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
                    case "get_summary" -> doGetSummary(p); //$NON-NLS-1$
                    case "list_nodes" -> doListNodes(p); //$NON-NLS-1$
                    case "create_schema" -> doCreateSchema(p); //$NON-NLS-1$
                    case "upsert_dataset" -> doUpsertDataset(p); //$NON-NLS-1$
                    case "upsert_param" -> doUpsertParameter(p); //$NON-NLS-1$
                    case "upsert_field" -> doUpsertCalculatedField(p); //$NON-NLS-1$
                    default -> ToolResult.failure("Unknown command: " + command); //$NON-NLS-1$
                };
            } catch (MetadataOperationException e) {
                return ToolResult.failure(toErrorJson(e));
            } catch (Exception e) {
                return ToolResult.failure("INTERNAL_ERROR: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    // --- Read commands ---

    private ToolResult doGetSummary(Map<String, Object> p) throws MetadataOperationException {
        DcsGetSummaryRequest request = new DcsGetSummaryRequest(
                asString(p.get("project")), //$NON-NLS-1$
                asString(p.get("owner_fqn"))); //$NON-NLS-1$
        DcsSummaryResult result = service.getSummary(request);
        return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.SEARCH_RESULTS);
    }

    private ToolResult doListNodes(Map<String, Object> p) throws MetadataOperationException {
        DcsListNodesRequest request = new DcsListNodesRequest(
                asString(p.get("project")), //$NON-NLS-1$
                asString(p.get("owner_fqn")), //$NON-NLS-1$
                asOptionalString(p.get("node_kind")), //$NON-NLS-1$
                asOptionalString(p.get("name_contains")), //$NON-NLS-1$
                asOptionalInteger(p.get("limit")), //$NON-NLS-1$
                asOptionalInteger(p.get("offset"))); //$NON-NLS-1$
        DcsListNodesResult result = service.listNodes(request);
        return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.SEARCH_RESULTS);
    }

    // --- Mutating commands ---

    private ToolResult doCreateSchema(Map<String, Object> p) throws MetadataOperationException {
        String project = asString(p.get("project")); //$NON-NLS-1$
        String ownerFqn = asString(p.get("owner_fqn")); //$NON-NLS-1$
        String templateName = asOptionalString(p.get("template_name")); //$NON-NLS-1$
        Boolean forceReplace = asOptionalBoolean(p.get("force_replace")); //$NON-NLS-1$
        String validationToken = asString(p.get("validation_token")); //$NON-NLS-1$

        validationService.normalizeDcsCreateMainSchemaPayload(
                project, ownerFqn, templateName, forceReplace);

        Map<String, Object> v = validationService.consumeToken(
                validationToken, ValidationOperation.DCS_CREATE_MAIN_SCHEMA, project);

        DcsCreateMainSchemaRequest request = new DcsCreateMainSchemaRequest(
                asString(v.get("project")), //$NON-NLS-1$
                asString(v.get("owner_fqn")), //$NON-NLS-1$
                asOptionalString(v.get("template_name")), //$NON-NLS-1$
                asOptionalBoolean(v.get("force_replace"))); //$NON-NLS-1$
        DcsCreateMainSchemaResult result = service.createMainSchema(request);
        return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.CONFIRMATION);
    }

    private ToolResult doUpsertDataset(Map<String, Object> p) throws MetadataOperationException {
        String project = asString(p.get("project")); //$NON-NLS-1$
        String ownerFqn = asString(p.get("owner_fqn")); //$NON-NLS-1$
        String datasetName = asString(p.get("dataset_name")); //$NON-NLS-1$
        String query = asOptionalString(p.get("query")); //$NON-NLS-1$
        String dataSource = asOptionalString(p.get("data_source")); //$NON-NLS-1$
        Boolean autoFill = asOptionalBoolean(p.get("auto_fill_available_fields")); //$NON-NLS-1$
        Boolean useGroup = asOptionalBoolean(p.get("use_query_group_if_possible")); //$NON-NLS-1$
        String validationToken = asString(p.get("validation_token")); //$NON-NLS-1$

        validationService.normalizeDcsUpsertQueryDatasetPayload(
                project, ownerFqn, datasetName, query, dataSource, autoFill, useGroup);

        Map<String, Object> v = validationService.consumeToken(
                validationToken, ValidationOperation.DCS_UPSERT_QUERY_DATASET, project);

        DcsUpsertQueryDatasetRequest request = new DcsUpsertQueryDatasetRequest(
                asString(v.get("project")), //$NON-NLS-1$
                asString(v.get("owner_fqn")), //$NON-NLS-1$
                asString(v.get("dataset_name")), //$NON-NLS-1$
                asOptionalString(v.get("query")), //$NON-NLS-1$
                asOptionalString(v.get("data_source")), //$NON-NLS-1$
                asOptionalBoolean(v.get("auto_fill_available_fields")), //$NON-NLS-1$
                asOptionalBoolean(v.get("use_query_group_if_possible"))); //$NON-NLS-1$
        DcsUpsertQueryDatasetResult result = service.upsertQueryDataset(request);
        return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.CONFIRMATION);
    }

    private ToolResult doUpsertParameter(Map<String, Object> p) throws MetadataOperationException {
        String project = asString(p.get("project")); //$NON-NLS-1$
        String ownerFqn = asString(p.get("owner_fqn")); //$NON-NLS-1$
        String parameterName = asString(p.get("parameter_name")); //$NON-NLS-1$
        String expression = asOptionalString(p.get("expression")); //$NON-NLS-1$
        Boolean availableAsField = asOptionalBoolean(p.get("available_as_field")); //$NON-NLS-1$
        Boolean valueListAllowed = asOptionalBoolean(p.get("value_list_allowed")); //$NON-NLS-1$
        Boolean denyIncomplete = asOptionalBoolean(p.get("deny_incomplete_values")); //$NON-NLS-1$
        Boolean useRestriction = asOptionalBoolean(p.get("use_restriction")); //$NON-NLS-1$
        String validationToken = asString(p.get("validation_token")); //$NON-NLS-1$

        validationService.normalizeDcsUpsertParameterPayload(
                project, ownerFqn, parameterName, expression,
                availableAsField, valueListAllowed, denyIncomplete, useRestriction);

        Map<String, Object> v = validationService.consumeToken(
                validationToken, ValidationOperation.DCS_UPSERT_PARAMETER, project);

        DcsUpsertParameterRequest request = new DcsUpsertParameterRequest(
                asString(v.get("project")), //$NON-NLS-1$
                asString(v.get("owner_fqn")), //$NON-NLS-1$
                asString(v.get("parameter_name")), //$NON-NLS-1$
                asOptionalString(v.get("expression")), //$NON-NLS-1$
                asOptionalBoolean(v.get("available_as_field")), //$NON-NLS-1$
                asOptionalBoolean(v.get("value_list_allowed")), //$NON-NLS-1$
                asOptionalBoolean(v.get("deny_incomplete_values")), //$NON-NLS-1$
                asOptionalBoolean(v.get("use_restriction"))); //$NON-NLS-1$
        DcsUpsertParameterResult result = service.upsertParameter(request);
        return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.CONFIRMATION);
    }

    private ToolResult doUpsertCalculatedField(Map<String, Object> p) throws MetadataOperationException {
        String project = asString(p.get("project")); //$NON-NLS-1$
        String ownerFqn = asString(p.get("owner_fqn")); //$NON-NLS-1$
        String dataPath = asString(p.get("data_path")); //$NON-NLS-1$
        String expression = asOptionalString(p.get("expression")); //$NON-NLS-1$
        String presentationExpression = asOptionalString(p.get("presentation_expression")); //$NON-NLS-1$
        String validationToken = asString(p.get("validation_token")); //$NON-NLS-1$

        validationService.normalizeDcsUpsertCalculatedFieldPayload(
                project, ownerFqn, dataPath, expression, presentationExpression);

        Map<String, Object> v = validationService.consumeToken(
                validationToken, ValidationOperation.DCS_UPSERT_CALCULATED_FIELD, project);

        DcsUpsertCalculatedFieldRequest request = new DcsUpsertCalculatedFieldRequest(
                asString(v.get("project")), //$NON-NLS-1$
                asString(v.get("owner_fqn")), //$NON-NLS-1$
                asString(v.get("data_path")), //$NON-NLS-1$
                asOptionalString(v.get("expression")), //$NON-NLS-1$
                asOptionalString(v.get("presentation_expression"))); //$NON-NLS-1$
        DcsUpsertCalculatedFieldResult result = service.upsertCalculatedField(request);
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
        if (value == null) {
            return null;
        }
        String raw = String.valueOf(value).trim();
        return raw.isEmpty() ? null : raw;
    }

    private Boolean asOptionalBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty()) {
            return null;
        }
        if ("1".equals(raw)) { //$NON-NLS-1$
            return Boolean.TRUE;
        }
        if ("0".equals(raw)) { //$NON-NLS-1$
            return Boolean.FALSE;
        }
        return Boolean.valueOf(Boolean.parseBoolean(raw));
    }

    private Integer asOptionalInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return Integer.valueOf(number.intValue());
        }
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty()) {
            return null;
        }
        return Integer.valueOf(Integer.parseInt(raw));
    }
}
