/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.mcp.client.McpClient;
import com.codepilot1c.core.mcp.model.McpContent;
import com.codepilot1c.core.mcp.model.McpResourceContent;
import com.codepilot1c.core.mcp.model.McpTool;
import com.codepilot1c.core.mcp.model.McpToolResult;
import com.codepilot1c.core.agent.profiles.DynamicToolCapability;
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
    private final DynamicToolCapability dynamicToolCapability;

    /**
     * Creates a new adapter.
     *
     * @param client the MCP client
     * @param mcpTool the MCP tool definition
     */
    public McpToolAdapter(McpClient client, McpTool mcpTool) {
        this(client, mcpTool, false);
    }

    /**
     * Creates an adapter with local provenance for an exact reviewed tool.
     * Remote annotations may raise this risk classification but never lower it.
     *
     * @param client connected MCP client
     * @param mcpTool remote tool definition
     * @param locallyTrustedReadOnly exact local per-server/tool trust decision
     */
    public McpToolAdapter(McpClient client, McpTool mcpTool, boolean locallyTrustedReadOnly) {
        this.client = client;
        this.mcpTool = mcpTool;
        this.serverName = client.getServerName();
        this.dynamicToolCapability = dynamicToolCapabilityOf(mcpTool, locallyTrustedReadOnly);
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
        Map<String, Object> normalizedParams = normalizeParams(params);
        return client.callTool(mcpTool.getName(), normalizedParams)
            .thenCompose(this::convertResultAsync)
            .exceptionally(e -> {
                String errorMsg = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                return ToolResult.failure("MCP tool error: " + errorMsg);
            });
    }

    private Map<String, Object> normalizeParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return params;
        }
        Map<String, Object> normalized = new HashMap<>(params);
        Object locationValue = normalized.get("location"); //$NON-NLS-1$
        if (locationValue == null) {
            return normalized;
        }
        String location = String.valueOf(locationValue).trim().toLowerCase(Locale.ROOT);
        if (!"cn".equals(location) && !"us".equals(location)) { //$NON-NLS-1$ //$NON-NLS-2$
            normalized.put("location", "us"); //$NON-NLS-1$ //$NON-NLS-2$
        } else {
            normalized.put("location", location); //$NON-NLS-1$
        }
        return normalized;
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
        return dynamicToolCapability == DynamicToolCapability.MUTATING;
    }

    @Override
    public boolean isDestructive() {
        if (dynamicToolCapability == DynamicToolCapability.MUTATING) {
            return true;
        }
        // Heuristic based on tool name
        String name = mcpTool.getName().toLowerCase();
        return name.contains("delete") || name.contains("remove") ||
               name.contains("write") || name.contains("create") ||
               name.contains("update") || name.contains("modify");
    }

    /**
     * Returns the local-provenance classification after applying remote hints
     * as risk raisers only.
     */
    public DynamicToolCapability getDynamicToolCapability() {
        return dynamicToolCapability;
    }

    /** Classifies a discovered MCP tool without requiring a connected client. */
    public static DynamicToolCapability dynamicToolCapabilityOf(McpTool tool) {
        return dynamicToolCapabilityOf(tool, false);
    }

    /**
     * Classifies a remote MCP tool. Untrusted tools are mutating regardless of
     * read-only hints. A locally trusted exact tool remains read-only only when
     * remote hints are absent or valid and non-risk-raising.
     */
    public static DynamicToolCapability dynamicToolCapabilityOf(
            McpTool tool, boolean locallyTrustedReadOnly) {
        if (tool == null) {
            return DynamicToolCapability.MUTATING;
        }
        var annotations = tool.getAnnotations();
        if (annotations == null) {
            return locallyTrustedReadOnly
                    ? DynamicToolCapability.READ_ONLY
                    : DynamicToolCapability.MUTATING;
        }
        boolean hasReadOnly = annotations.has("readOnlyHint"); //$NON-NLS-1$
        boolean hasDestructive = annotations.has("destructiveHint"); //$NON-NLS-1$
        if ((hasReadOnly && (!annotations.get("readOnlyHint").isJsonPrimitive() //$NON-NLS-1$
                || !annotations.getAsJsonPrimitive("readOnlyHint").isBoolean())) //$NON-NLS-1$
                || (hasDestructive && (!annotations.get("destructiveHint").isJsonPrimitive() //$NON-NLS-1$
                || !annotations.getAsJsonPrimitive("destructiveHint").isBoolean()))) { //$NON-NLS-1$
            return DynamicToolCapability.MUTATING;
        }
        boolean readOnly = hasReadOnly
                && annotations.get("readOnlyHint").getAsBoolean(); //$NON-NLS-1$
        boolean destructive = hasDestructive
                && annotations.get("destructiveHint").getAsBoolean(); //$NON-NLS-1$
        if (readOnly && destructive) {
            return DynamicToolCapability.MUTATING;
        }
        if (destructive || (hasReadOnly && !readOnly)) {
            return DynamicToolCapability.MUTATING;
        }
        return locallyTrustedReadOnly
                ? DynamicToolCapability.READ_ONLY
                : DynamicToolCapability.MUTATING;
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
