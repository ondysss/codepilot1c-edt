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
        name = "edt_get_module_structure",
        category = "metadata",
        mutating = false,
        tags = {"metadata", "read-only", "workspace", "edt", "modules"})
public class GetModuleStructureTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(GetModuleStructureTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "projectName": {"type": "string", "description": "EDT project name"},
                "moduleFqn": {"type": "string", "description": "Module FQN or path relative to src/"},
                "full": {"type": "boolean", "description": "Include call sites"}
              },
              "required": ["projectName", "moduleFqn"]
            }
            """; //$NON-NLS-1$

    private final EdtProjectAnalysisSupport support;

    public GetModuleStructureTool() {
        this(new EdtMetadataGateway());
    }

    public GetModuleStructureTool(EdtMetadataGateway gateway) {
        this.support = new EdtProjectAnalysisSupport(gateway);
    }

    @Override
    public String getDescription() {
        return "Возвращает структуру BSL-модуля: области, методы и экспортируемые процедуры."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("edt-module-structure"); //$NON-NLS-1$
            String projectName = ""; //$NON-NLS-1$
            LOG.info("[%s] START edt_get_module_structure", opId); //$NON-NLS-1$
            try {
                projectName = params.requireString("projectName").trim(); //$NON-NLS-1$
                String moduleFqn = params.requireString("moduleFqn").trim(); //$NON-NLS-1$
                JsonObject result = support.moduleStructure(projectName, moduleFqn, params.optBoolean("full", false)); //$NON-NLS-1$
                return ToolResult.success(EdtProjectAnalysisSupport.pretty(result), ToolResult.ToolResultType.CODE, result);
            } catch (ToolParameters.ToolParameterException e) {
                return failure(projectName, EdtToolErrorCode.INVALID_ARGUMENT.name(), e.getMessage());
            } catch (EdtToolException e) {
                return failure(projectName, e.getCode().name(), e.getMessage());
            } catch (Exception e) {
                LOG.error("[" + opId + "] edt_get_module_structure failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return failure(projectName, "GET_MODULE_STRUCTURE_FAILED", e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private ToolResult failure(String projectName, String code, String message) {
        JsonObject error = EdtProjectAnalysisSupport.errorPayload(projectName, code, message);
        return ToolResult.failure(EdtProjectAnalysisSupport.pretty(error));
    }
}
