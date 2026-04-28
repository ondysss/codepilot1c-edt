package com.codepilot1c.core.tools.metadata;

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
        name = "edt_list_modules",
        category = "metadata",
        mutating = false,
        tags = {"metadata", "read-only", "workspace", "edt", "modules"})
public class ListModulesTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(ListModulesTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "projectName": {"type": "string", "description": "EDT project name"},
                "objectType": {"type": "string", "description": "Optional metadata object type filter"}
              },
              "required": ["projectName"]
            }
            """; //$NON-NLS-1$

    private final EdtProjectAnalysisSupport support;

    public ListModulesTool() {
        this(new EdtMetadataGateway());
    }

    public ListModulesTool(EdtMetadataGateway gateway) {
        this.support = new EdtProjectAnalysisSupport(gateway);
    }

    @Override
    public String getDescription() {
        return "Возвращает список BSL-модулей EDT проекта с владельцем и путем файла."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("edt-list-modules"); //$NON-NLS-1$
            String projectName = ""; //$NON-NLS-1$
            LOG.info("[%s] START edt_list_modules", opId); //$NON-NLS-1$
            try {
                projectName = params.requireString("projectName").trim(); //$NON-NLS-1$
                JsonObject result = support.listModules(projectName, params.optString("objectType", "")); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.success(EdtProjectAnalysisSupport.pretty(result), ToolResult.ToolResultType.CODE, result);
            } catch (ToolParameters.ToolParameterException e) {
                return failure(projectName, EdtToolErrorCode.INVALID_ARGUMENT.name(), e.getMessage());
            } catch (EdtToolException e) {
                return failure(projectName, e.getCode().name(), e.getMessage());
            } catch (Exception e) {
                LOG.error("[" + opId + "] edt_list_modules failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return failure(projectName, "LIST_MODULES_FAILED", e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private ToolResult failure(String projectName, String code, String message) {
        JsonObject error = EdtProjectAnalysisSupport.errorPayload(projectName, code, message);
        return ToolResult.failure(EdtProjectAnalysisSupport.pretty(error));
    }
}
