package com.codepilot1c.core.tools.metadata;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;
import com.codepilot1c.core.edt.runtime.EdtToolErrorCode;
import com.codepilot1c.core.edt.runtime.EdtToolException;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonObject;

@ToolMeta(
        name = "edt_get_symbol_info",
        category = "metadata",
        mutating = false,
        tags = {"metadata", "read-only", "workspace", "edt", "navigation"})
public class GetSymbolInfoTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(GetSymbolInfoTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "projectName": {"type": "string", "description": "EDT project name"},
                "symbolFqn": {"type": "string", "description": "Optional symbol FQN"},
                "position": {
                  "type": "object",
                  "properties": {
                    "fileUri": {"type": "string"},
                    "line": {"type": "integer"},
                    "column": {"type": "integer"}
                  },
                  "required": ["fileUri", "line", "column"]
                }
              },
              "required": ["projectName"]
            }
            """; //$NON-NLS-1$

    private final EdtProjectAnalysisSupport support;

    public GetSymbolInfoTool() {
        this(new EdtMetadataGateway());
    }

    public GetSymbolInfoTool(EdtMetadataGateway gateway) {
        this.support = new EdtProjectAnalysisSupport(gateway);
    }

    @Override
    public String getDescription() {
        return "Возвращает детальную информацию о символе EDT по FQN или позиции."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("edt-symbol-info"); //$NON-NLS-1$
            String projectName = ""; //$NON-NLS-1$
            LOG.info("[%s] START edt_get_symbol_info", opId); //$NON-NLS-1$
            try {
                projectName = params.requireString("projectName").trim(); //$NON-NLS-1$
                Map<String, Object> position = EdtProjectAnalysisSupport.objectParam(params.getRaw(), "position"); //$NON-NLS-1$
                JsonObject result = support.symbolInfo(projectName, position, params.optString("symbolFqn", "")); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.success(EdtProjectAnalysisSupport.pretty(result), ToolResult.ToolResultType.CODE, result);
            } catch (ToolParameters.ToolParameterException e) {
                return failure(projectName, EdtToolErrorCode.INVALID_ARGUMENT.name(), e.getMessage());
            } catch (EdtToolException e) {
                return failure(projectName, e.getCode().name(), e.getMessage());
            } catch (Exception e) {
                LOG.error("[" + opId + "] edt_get_symbol_info failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return failure(projectName, "GET_SYMBOL_INFO_FAILED", e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private ToolResult failure(String projectName, String code, String message) {
        JsonObject error = EdtProjectAnalysisSupport.errorPayload(projectName, code, message);
        return ToolResult.failure(EdtProjectAnalysisSupport.pretty(error));
    }
}
