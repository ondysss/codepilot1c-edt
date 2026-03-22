package com.codepilot1c.core.tools.metadata;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.ast.EdtAstException;
import com.codepilot1c.core.edt.ast.EdtAstServices;
import com.codepilot1c.core.edt.ast.MarkdownRenderer;
import com.codepilot1c.core.edt.ast.MetadataDetailsRequest;
import com.codepilot1c.core.edt.ast.MetadataDetailsResult;

/**
 * Internal EDT AST metadata-inspection tool.
 */
@ToolMeta(name = "edt_metadata_details", category = "metadata", tags = {"read-only", "workspace", "edt"})
public class EdtMetadataDetailsTool extends AbstractTool {

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "projectName": {"type": "string", "description": "EDT project name"},
                "objectFqns": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "Metadata object FQNs"
                },
                "full": {"type": "boolean", "description": "Extended details"},
                "language": {"type": "string", "description": "Preferred language code"}
              },
              "required": ["projectName", "objectFqns"]
            }
            """; //$NON-NLS-1$

    private final MarkdownRenderer markdownRenderer = new MarkdownRenderer();

    @Override
    public String getDescription() {
        return "Возвращает структуру объектов метаданных конфигурации (не справку по встроенным типам языка)."; //$NON-NLS-1$
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
                MetadataDetailsRequest request = MetadataDetailsRequest.fromParameters(parameters);
                MetadataDetailsResult result = EdtAstServices.getInstance().getMetadataDetails(request);
                return ToolResult.success(markdownRenderer.renderMetadata(result), ToolResult.ToolResultType.CODE);
            } catch (EdtAstException e) {
                return ToolResult.failure(e.getCode().name() + ": " + e.getMessage()); //$NON-NLS-1$
            } catch (Exception e) {
                return ToolResult.failure("INTERNAL_ERROR: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }
}
