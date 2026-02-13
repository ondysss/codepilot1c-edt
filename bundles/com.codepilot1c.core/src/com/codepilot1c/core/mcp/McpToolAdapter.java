/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.mcp.client.McpClient;
import com.codepilot1c.core.mcp.model.McpContent;
import com.codepilot1c.core.mcp.model.McpResourceContent;
import com.codepilot1c.core.mcp.model.McpTool;
import com.codepilot1c.core.mcp.model.McpToolResult;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolResult;

/**
 * Adapts an MCP tool to the ITool interface.
 *
 * <p>This allows MCP tools to be used seamlessly with the existing tool system.</p>
 */
public class McpToolAdapter implements ITool {

    private final McpClient client;
    private final McpTool mcpTool;
    private final String serverName;

    /**
     * Creates a new adapter.
     *
     * @param client the MCP client
     * @param mcpTool the MCP tool definition
     */
    public McpToolAdapter(McpClient client, McpTool mcpTool) {
        this.client = client;
        this.mcpTool = mcpTool;
        this.serverName = client.getServerName();
    }

    @Override
    public String getName() {
        // Prefix with server name to avoid collisions
        // Sanitize server name for valid tool name
        String sanitizedName = serverName.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
        return "mcp_" + sanitizedName + "_" + mcpTool.getName();
    }

    @Override
    public String getDescription() {
        return "[MCP:" + serverName + "] " + mcpTool.getDescription();
    }

    @Override
    public String getParameterSchema() {
        if (mcpTool.getInputSchema() != null) {
            return mcpTool.getInputSchema().toString();
        }
        return "{\"type\":\"object\",\"properties\":{}}";
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> params) {
        return client.callTool(mcpTool.getName(), params)
            .thenCompose(this::convertResultAsync)
            .exceptionally(e -> {
                String errorMsg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                return ToolResult.failure("MCP tool error: " + errorMsg);
            });
    }

    private CompletableFuture<ToolResult> convertResultAsync(McpToolResult mcpResult) {
        if (mcpResult.isError()) {
            return CompletableFuture.completedFuture(ToolResult.failure(extractErrorText(mcpResult)));
        }

        StringBuilder text = new StringBuilder();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

        for (McpContent content : mcpResult.getContent()) {
            if (content.getType() == McpContent.Type.TEXT) {
                appendWithSeparator(text, content.getEffectiveText());
            } else if (content.getType() == McpContent.Type.RESOURCE) {
                String inlineText = content.getEffectiveText();
                if (inlineText != null && !inlineText.isBlank()) {
                    appendWithSeparator(text, inlineText);
                } else {
                    String uri = content.getUri();
                    if (uri != null && !uri.isBlank()) {
                        chain = chain.thenCompose(v -> client.readResource(uri)
                                .thenAccept(resource -> appendResourceContent(text, resource, uri))
                                .exceptionally(e -> {
                                    appendWithSeparator(text, "[MCP resource read failed] " + uri); //$NON-NLS-1$
                                    return null;
                                }));
                    }
                }
            } else if (content.getType() == McpContent.Type.IMAGE) {
                String mime = content.getEffectiveMimeType();
                appendWithSeparator(text, "[MCP image content omitted" //$NON-NLS-1$
                        + (mime != null ? ": " + mime : "") + "]"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        return chain.thenApply(v -> ToolResult.success(text.toString().trim()));
    }

    private String extractErrorText(McpToolResult mcpResult) {
        for (McpContent content : mcpResult.getContent()) {
            if (content.getType() == McpContent.Type.TEXT && content.getText() != null) {
                return content.getText();
            }
        }
        return "Unknown MCP error";
    }

    private void appendResourceContent(StringBuilder sb, McpResourceContent resourceContent, String uri) {
        if (resourceContent == null || resourceContent.getContents().isEmpty()) {
            appendWithSeparator(sb, "[MCP resource has no content] " + uri); //$NON-NLS-1$
            return;
        }
        boolean added = false;
        for (McpResourceContent.ResourceContentItem item : resourceContent.getContents()) {
            if (item.getText() != null && !item.getText().isBlank()) {
                appendWithSeparator(sb, item.getText());
                added = true;
            }
        }
        if (!added) {
            appendWithSeparator(sb, "[MCP resource fetched without text payload] " + uri); //$NON-NLS-1$
        }
    }

    private void appendWithSeparator(StringBuilder sb, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("\n");
        }
        sb.append(text);
    }

    @Override
    public boolean requiresConfirmation() {
        return false;
    }

    @Override
    public boolean isDestructive() {
        // Heuristic based on tool name
        String name = mcpTool.getName().toLowerCase();
        return name.contains("delete") || name.contains("remove") ||
               name.contains("write") || name.contains("create") ||
               name.contains("update") || name.contains("modify");
    }

    /**
     * Returns the original MCP tool.
     *
     * @return the MCP tool
     */
    public McpTool getMcpTool() {
        return mcpTool;
    }

    /**
     * Returns the server name.
     *
     * @return the server name
     */
    public String getServerName() {
        return serverName;
    }
}
