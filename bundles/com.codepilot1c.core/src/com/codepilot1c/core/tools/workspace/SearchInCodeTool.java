package com.codepilot1c.core.tools.workspace;

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
import com.codepilot1c.core.tools.metadata.EdtProjectAnalysisSupport;
import com.google.gson.JsonObject;

@ToolMeta(
        name = "edt_search_in_code",
        category = "workspace",
        mutating = false,
        tags = {"workspace", "read-only", "edt", "search", "bsl"})
public class SearchInCodeTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(SearchInCodeTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "projectName": {"type": "string", "description": "EDT project name"},
                "query": {"type": "string", "description": "Text or regular expression to search"},
                "searchType": {"type": "string", "enum": ["text", "regex"], "description": "Search mode"},
                "scope": {"type": "string", "enum": ["all", "modules", "queries"], "description": "Search scope"}
              },
              "required": ["projectName", "query"]
            }
            """; //$NON-NLS-1$

    private final EdtProjectAnalysisSupport support;

    public SearchInCodeTool() {
        this(new EdtMetadataGateway());
    }

    public SearchInCodeTool(EdtMetadataGateway gateway) {
        this.support = new EdtProjectAnalysisSupport(gateway);
    }

    @Override
    public String getDescription() {
        return "Ищет текст или регулярное выражение в коде 1С EDT проекта."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("edt-search-code"); //$NON-NLS-1$
            String projectName = ""; //$NON-NLS-1$
            LOG.info("[%s] START edt_search_in_code", opId); //$NON-NLS-1$
            try {
                projectName = params.requireString("projectName").trim(); //$NON-NLS-1$
                JsonObject result = support.searchInCode(
                        projectName,
                        params.requireString("query"), //$NON-NLS-1$
                        params.optString("searchType", "text"), //$NON-NLS-1$ //$NON-NLS-2$
                        params.optString("scope", "all")); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.success(EdtProjectAnalysisSupport.pretty(result), ToolResult.ToolResultType.SEARCH_RESULTS, result);
            } catch (ToolParameters.ToolParameterException e) {
                return failure(projectName, EdtToolErrorCode.INVALID_ARGUMENT.name(), e.getMessage());
            } catch (EdtToolException e) {
                return failure(projectName, e.getCode().name(), e.getMessage());
            } catch (Exception e) {
                LOG.error("[" + opId + "] edt_search_in_code failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return failure(projectName, "SEARCH_IN_CODE_FAILED", e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private ToolResult failure(String projectName, String code, String message) {
        JsonObject error = EdtProjectAnalysisSupport.errorPayload(projectName, code, message);
        return ToolResult.failure(EdtProjectAnalysisSupport.pretty(error));
    }
}
