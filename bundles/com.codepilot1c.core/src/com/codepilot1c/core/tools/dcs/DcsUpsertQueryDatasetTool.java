package com.codepilot1c.core.tools.dcs;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.dcs.DcsUpsertQueryDatasetRequest;
import com.codepilot1c.core.edt.dcs.DcsUpsertQueryDatasetResult;
import com.codepilot1c.core.edt.dcs.EdtDcsService;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.google.gson.Gson;

/**
 * Creates or updates query dataset in owner DCS schema.
 */
@ToolMeta(name = "dcs_upsert_query_dataset", category = "dcs", mutating = true, tags = {"workspace", "edt"})
public class DcsUpsertQueryDatasetTool extends AbstractTool {

    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {
                  "type": "string",
                  "description": "EDT project name (base or external)"
                },
                "owner_fqn": {
                  "type": "string",
                  "description": "Owner FQN, e.g. Report.Sales or ExternalReport.MyReport"
                },
                "dataset_name": {
                  "type": "string",
                  "description": "Dataset name"
                },
                "query": {
                  "type": "string",
                  "description": "Optional query text"
                },
                "data_source": {
                  "type": "string",
                  "description": "Optional data source name"
                },
                "auto_fill_available_fields": {
                  "type": "boolean",
                  "description": "Optional auto fill flag"
                },
                "use_query_group_if_possible": {
                  "type": "boolean",
                  "description": "Optional use query group flag"
                },
                "validation_token": {
                  "type": "string",
                  "description": "One-time token from edt_validate_request"
                }
              },
              "required": ["project", "owner_fqn", "dataset_name", "validation_token"]
            }
            """; //$NON-NLS-1$

    private final EdtDcsService service;
    private final MetadataRequestValidationService validationService;

    public DcsUpsertQueryDatasetTool() {
        this(new EdtDcsService(), new MetadataRequestValidationService());
    }

    DcsUpsertQueryDatasetTool(EdtDcsService service, MetadataRequestValidationService validationService) {
        this.service = service;
        this.validationService = validationService;
    }

    @Override
    public String getDescription() {
        return "Creates or updates DCS query dataset for owner object."; //$NON-NLS-1$
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
            try {
                String project = asString(parameters.get("project")); //$NON-NLS-1$
                String ownerFqn = asString(parameters.get("owner_fqn")); //$NON-NLS-1$
                String datasetName = asString(parameters.get("dataset_name")); //$NON-NLS-1$
                String query = asOptionalString(parameters.get("query")); //$NON-NLS-1$
                String dataSource = asOptionalString(parameters.get("data_source")); //$NON-NLS-1$
                Boolean autoFill = asOptionalBoolean(parameters.get("auto_fill_available_fields")); //$NON-NLS-1$
                Boolean useGroup = asOptionalBoolean(parameters.get("use_query_group_if_possible")); //$NON-NLS-1$
                String validationToken = asString(parameters.get("validation_token")); //$NON-NLS-1$

                validationService.normalizeDcsUpsertQueryDatasetPayload(
                        project,
                        ownerFqn,
                        datasetName,
                        query,
                        dataSource,
                        autoFill,
                        useGroup);

                Map<String, Object> validatedPayload = validationService.consumeToken(
                        validationToken,
                        ValidationOperation.DCS_UPSERT_QUERY_DATASET,
                        project);

                DcsUpsertQueryDatasetRequest request = new DcsUpsertQueryDatasetRequest(
                        asString(validatedPayload.get("project")), //$NON-NLS-1$
                        asString(validatedPayload.get("owner_fqn")), //$NON-NLS-1$
                        asString(validatedPayload.get("dataset_name")), //$NON-NLS-1$
                        asOptionalString(validatedPayload.get("query")), //$NON-NLS-1$
                        asOptionalString(validatedPayload.get("data_source")), //$NON-NLS-1$
                        asOptionalBoolean(validatedPayload.get("auto_fill_available_fields")), //$NON-NLS-1$
                        asOptionalBoolean(validatedPayload.get("use_query_group_if_possible"))); //$NON-NLS-1$
                DcsUpsertQueryDatasetResult result = service.upsertQueryDataset(request);
                return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.CONFIRMATION);
            } catch (MetadataOperationException e) {
                return ToolResult.failure("[" + e.getCode() + "] " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (Exception e) {
                return ToolResult.failure("Ошибка dcs_upsert_query_dataset: " + e.getMessage()); //$NON-NLS-1$
            }
        });
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
}
