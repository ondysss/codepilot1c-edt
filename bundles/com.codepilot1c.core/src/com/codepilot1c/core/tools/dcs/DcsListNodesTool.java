package com.codepilot1c.core.tools.dcs;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.dcs.DcsListNodesRequest;
import com.codepilot1c.core.edt.dcs.DcsListNodesResult;
import com.codepilot1c.core.edt.dcs.EdtDcsService;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Lists DCS nodes (datasets/parameters/calculated fields/variants) with pagination.
 */
@ToolMeta(name = "dcs_list_nodes", category = "dcs", tags = {"read-only", "workspace", "edt"})
public class DcsListNodesTool extends AbstractTool {

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
                "node_kind": {
                  "type": "string",
                  "description": "Node kind: all|dataset|parameter|calculated|variant"
                },
                "name_contains": {
                  "type": "string",
                  "description": "Case-insensitive name filter"
                },
                "limit": {
                  "type": "integer",
                  "description": "Page size (1..1000, default 100)"
                },
                "offset": {
                  "type": "integer",
                  "description": "Pagination offset (default 0)"
                }
              },
              "required": ["project", "owner_fqn"]
            }
            """; //$NON-NLS-1$

    private final EdtDcsService service;

    public DcsListNodesTool() {
        this(new EdtDcsService());
    }

    DcsListNodesTool(EdtDcsService service) {
        this.service = service;
    }

    @Override
    public String getDescription() {
        return "Lists nodes of DCS model with filters and pagination."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> parameters = params.getRaw();
            try {
                DcsListNodesRequest request = new DcsListNodesRequest(
                        asString(parameters.get("project")), //$NON-NLS-1$
                        asString(parameters.get("owner_fqn")), //$NON-NLS-1$
                        asOptionalString(parameters.get("node_kind")), //$NON-NLS-1$
                        asOptionalString(parameters.get("name_contains")), //$NON-NLS-1$
                        asOptionalInteger(parameters.get("limit")), //$NON-NLS-1$
                        asOptionalInteger(parameters.get("offset"))); //$NON-NLS-1$
                DcsListNodesResult result = service.listNodes(request);
                return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.SEARCH_RESULTS);
            } catch (MetadataOperationException e) {
                return ToolResult.failure(toErrorJson(e));
            } catch (Exception e) {
                return ToolResult.failure("INTERNAL_ERROR: " + e.getMessage()); //$NON-NLS-1$
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
        String raw = String.valueOf(value);
        return raw.isBlank() ? null : raw;
    }

    private Integer asOptionalInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return Integer.valueOf(number.intValue());
        }
        String raw = String.valueOf(value);
        if (raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            throw new MetadataOperationException(
                    MetadataOperationCode.KNOWLEDGE_REQUIRED,
                    "Expected integer value, got: " + raw, false); //$NON-NLS-1$
        }
    }

    private String toErrorJson(MetadataOperationException e) {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", e.getCode().name()); //$NON-NLS-1$
        obj.addProperty("message", e.getMessage()); //$NON-NLS-1$
        obj.addProperty("recoverable", e.isRecoverable()); //$NON-NLS-1$
        return GSON.toJson(obj);
    }
}
