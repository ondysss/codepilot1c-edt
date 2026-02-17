/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.rag.RagContextBuilder.RagContext;
import com.codepilot1c.core.rag.RagService;

/**
 * Tool for semantic search in the codebase using RAG.
 */
public class SearchCodebaseTool implements ITool {

    private static final String SCHEMA = """
            {
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "Search query to find relevant code"
                    },
                    "max_results": {
                        "type": "integer",
                        "description": "Maximum number of results to return (default: 5)"
                    }
                },
                "required": ["query"]
            }
            """; //$NON-NLS-1$

    @Override
    public String getName() {
        return "search_codebase"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Search the codebase for relevant code using semantic search. " + //$NON-NLS-1$
               "Returns code snippets that are semantically similar to the query."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            String query = (String) parameters.get("query"); //$NON-NLS-1$
            if (query == null || query.isEmpty()) {
                return ToolResult.failure("Query parameter is required"); //$NON-NLS-1$
            }

            int maxResults = 5;
            Object maxResultsParam = parameters.get("max_results"); //$NON-NLS-1$
            if (maxResultsParam instanceof Number) {
                maxResults = ((Number) maxResultsParam).intValue();
            }

            RagService ragService = RagService.getInstance();
            if (!ragService.isReady()) {
                return ToolResult.failure(
                        "RAG service is not ready. Сначала выполните индексацию кода: EDT → Инструменты → Индексировать код, затем повторите search_codebase."); //$NON-NLS-1$
            }

            RagContext context = ragService.buildContext(query, maxResults);
            if (!context.hasContext()) {
                return ToolResult.success("No relevant code found for query: " + query, //$NON-NLS-1$
                        ToolResult.ToolResultType.SEARCH_RESULTS);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(context.getRenderedChunks().size()).append(" relevant code snippets:\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

            for (var rendered : context.getRenderedChunks()) {
                var chunk = rendered.getChunk();
                sb.append("**File:** `").append(chunk.getFilePath()).append("`\n"); //$NON-NLS-1$ //$NON-NLS-2$
                sb.append("**Lines:** ").append(chunk.getStartLine()).append("-").append(chunk.getEndLine()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                if (chunk.getSymbolName() != null && !chunk.getSymbolName().isEmpty()) {
                    sb.append("**Symbol:** ").append(chunk.getSymbolName()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                sb.append("```bsl\n").append(rendered.getContent()).append("\n```\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }

            return ToolResult.success(sb.toString(), ToolResult.ToolResultType.SEARCH_RESULTS);
        });
    }
}
