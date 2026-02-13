package com.codepilot1c.core.tools;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.ast.EdtAstException;
import com.codepilot1c.core.edt.ast.EdtAstServices;
import com.codepilot1c.core.edt.ast.FindReferencesRequest;
import com.codepilot1c.core.edt.ast.MarkdownRenderer;
import com.codepilot1c.core.edt.ast.ReferenceSearchResult;

/**
 * Internal EDT AST reference-search tool.
 */
public class EdtFindReferencesTool implements ITool {

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "projectName": {"type": "string", "description": "EDT project name"},
                "objectFqn": {"type": "string", "description": "Object FQN, for example Catalog.Products"},
                "limit": {"type": "integer", "description": "Max references (default 100)"}
              },
              "required": ["projectName", "objectFqn"]
            }
            """; //$NON-NLS-1$

    private final MarkdownRenderer markdownRenderer = new MarkdownRenderer();

    @Override
    public String getName() {
        return "edt_find_references"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Find references for metadata object and return markdown report."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                FindReferencesRequest request = FindReferencesRequest.fromParameters(parameters);
                ReferenceSearchResult result = EdtAstServices.getInstance().findReferences(request);
                return ToolResult.success(markdownRenderer.renderReferences(result), ToolResult.ToolResultType.SEARCH_RESULTS);
            } catch (EdtAstException e) {
                return ToolResult.failure(e.getCode().name() + ": " + e.getMessage()); //$NON-NLS-1$
            } catch (Exception e) {
                return ToolResult.failure("INTERNAL_ERROR: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }
}
