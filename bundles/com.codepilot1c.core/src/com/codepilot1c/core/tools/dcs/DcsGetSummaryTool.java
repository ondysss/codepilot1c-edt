package com.codepilot1c.core.tools.dcs;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.dcs.DcsGetSummaryRequest;
import com.codepilot1c.core.edt.dcs.DcsSummaryResult;
import com.codepilot1c.core.edt.dcs.EdtDcsService;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Reads compact DCS summary for report/external report owner.
 */
@ToolMeta(name = "dcs_get_summary", category = "dcs", tags = {"read-only", "workspace", "edt"})
public class DcsGetSummaryTool extends AbstractTool {

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
                }
              },
              "required": ["project", "owner_fqn"]
            }
            """; //$NON-NLS-1$

    private final EdtDcsService service;

    public DcsGetSummaryTool() {
        this(new EdtDcsService());
    }

    DcsGetSummaryTool(EdtDcsService service) {
        this.service = service;
    }

    @Override
    public String getDescription() {
        return "Returns DCS summary for report/external report owner object."; //$NON-NLS-1$
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
                DcsGetSummaryRequest request = new DcsGetSummaryRequest(
                        asString(parameters.get("project")), //$NON-NLS-1$
                        asString(parameters.get("owner_fqn"))); //$NON-NLS-1$
                DcsSummaryResult result = service.getSummary(request);
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

    private String toErrorJson(MetadataOperationException e) {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", e.getCode().name()); //$NON-NLS-1$
        obj.addProperty("message", e.getMessage()); //$NON-NLS-1$
        obj.addProperty("recoverable", e.isRecoverable()); //$NON-NLS-1$
        return GSON.toJson(obj);
    }
}
