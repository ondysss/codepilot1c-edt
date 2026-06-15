package com.codepilot1c.core.tools.bsl;

import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.ast.EdtAstException;
import com.codepilot1c.core.edt.ql.QlIssue;
import com.codepilot1c.core.edt.ql.QlValidationRequest;
import com.codepilot1c.core.edt.ql.QlValidationResult;
import com.codepilot1c.core.edt.ql.QlValidationService;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Validates 1C:Enterprise query-language text against a project's metadata.
 */
@ToolMeta(name = "validate_query", category = "bsl", tags = {"read-only", "workspace", "edt"})
public class ValidateQueryTool extends AbstractTool {

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "projectName": {"type": "string", "description": "EDT project name"},
                "queryText": {"type": "string", "description": "1C query language text to validate, e.g. ВЫБРАТЬ Ссылка ИЗ Справочник.Номенклатура"},
                "dcsMode": {"type": "boolean", "description": "Validate as a Data Composition System query (allow {...} blocks and dataset fields). Default false."}
              },
              "required": ["projectName", "queryText"]
            }
            """; //$NON-NLS-1$

    @Override
    public String getDescription() {
        return "Проверяет текст запроса 1С на синтаксические и семантические ошибки в контексте проекта: " //$NON-NLS-1$
                + "разрешает имена таблиц и полей по метаданным конфигурации. Используй перед вставкой запроса в BSL " //$NON-NLS-1$
                + "или в макет СКД (dcsMode=true для запросов системы компоновки данных)."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            String projectName = params.requireString("projectName"); //$NON-NLS-1$
            String queryText = params.requireString("queryText"); //$NON-NLS-1$
            boolean dcsMode = params.optBoolean("dcsMode", false); //$NON-NLS-1$
            try {
                QlValidationResult result = new QlValidationService()
                        .validate(new QlValidationRequest(projectName, queryText, dcsMode));
                JsonObject structured = new Gson().toJsonTree(result).getAsJsonObject();
                return ToolResult.success(render(result), ToolResult.ToolResultType.CODE, structured);
            } catch (EdtAstException e) {
                return ToolResult.failure(e.getCode().name() + ": " + e.getMessage()); //$NON-NLS-1$
            } catch (Exception e) {
                return ToolResult.failure("INTERNAL_ERROR: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private static String render(QlValidationResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append(result.valid() ? "Запрос корректен." : "Найдены ошибки в запросе.") //$NON-NLS-1$ //$NON-NLS-2$
                .append(" (errors: ").append(result.errorCount()) //$NON-NLS-1$
                .append(", warnings: ").append(result.warningCount()) //$NON-NLS-1$
                .append(", info: ").append(result.infoCount()) //$NON-NLS-1$
                .append(result.dcsMode() ? ", dcsMode" : "").append(")\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        for (QlIssue issue : result.issues()) {
            sb.append("- [").append(issue.severity()).append("] "); //$NON-NLS-1$ //$NON-NLS-2$
            if (issue.line() > 0) {
                sb.append("line ").append(issue.line()); //$NON-NLS-1$
                if (issue.column() > 0) {
                    sb.append(":").append(issue.column()); //$NON-NLS-1$
                }
                sb.append(" — "); //$NON-NLS-1$
            }
            sb.append(issue.message()).append("\n"); //$NON-NLS-1$
        }
        return sb.toString();
    }
}
