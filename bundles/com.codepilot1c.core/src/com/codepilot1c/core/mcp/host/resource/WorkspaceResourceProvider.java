package com.codepilot1c.core.mcp.host.resource;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.eclipse.core.resources.ResourcesPlugin;

import com.codepilot1c.core.mcp.host.session.McpHostSession;
import com.codepilot1c.core.mcp.model.McpResource;
import com.codepilot1c.core.mcp.model.McpResourceContent;

public class WorkspaceResourceProvider implements IMcpResourceProvider {

    private static final String TREE_URI = "codepilot://workspace/tree"; //$NON-NLS-1$
    private static final String FILE_URI_PREFIX = "codepilot://workspace/file"; //$NON-NLS-1$

    @Override
    public List<McpResource> listResources(McpHostSession session) {
        McpResource tree = new McpResource(TREE_URI, "Workspace Tree"); //$NON-NLS-1$
        tree.setDescription("Top-level workspace entries"); //$NON-NLS-1$
        tree.setMimeType("text/plain"); //$NON-NLS-1$

        McpResource file = new McpResource(FILE_URI_PREFIX + "?path=<workspace-relative-path>", "Workspace File"); //$NON-NLS-1$ //$NON-NLS-2$
        file.setDescription("Read a file from workspace by query parameter 'path'"); //$NON-NLS-1$
        file.setMimeType("text/plain"); //$NON-NLS-1$

        return List.of(tree, file);
    }

    @Override
    public Optional<McpResourceContent> readResource(String uri, McpHostSession session) {
        if (TREE_URI.equals(uri)) {
            return Optional.of(textResource(uri, workspaceTree()));
        }
        if (uri != null && uri.startsWith(FILE_URI_PREFIX)) {
            return readWorkspaceFile(uri);
        }
        return Optional.empty();
    }

    private String workspaceTree() {
        Path root = workspaceRoot();
        if (root == null) {
            return "Workspace root is unavailable"; //$NON-NLS-1$
        }
        try {
            List<String> lines = Files.list(root)
                .limit(500)
                .map(p -> p.getFileName().toString())
                .sorted()
                .collect(Collectors.toList());
            return String.join("\n", lines); //$NON-NLS-1$
        } catch (IOException e) {
            return "Failed to list workspace tree: " + e.getMessage(); //$NON-NLS-1$
        }
    }

    private Optional<McpResourceContent> readWorkspaceFile(String uriText) {
        Path root = workspaceRoot();
        if (root == null) {
            return Optional.of(textResource(uriText, "Workspace root is unavailable")); //$NON-NLS-1$
        }
        try {
            URI uri = URI.create(uriText);
            String query = uri.getRawQuery();
            String pathArg = extractQueryParam(query, "path"); //$NON-NLS-1$
            if (pathArg == null || pathArg.isBlank()) {
                return Optional.of(textResource(uriText, "Missing 'path' query argument")); //$NON-NLS-1$
            }
            Path target = root.resolve(pathArg).normalize();
            if (!target.startsWith(root)) {
                return Optional.of(textResource(uriText, "Path traversal is not allowed")); //$NON-NLS-1$
            }
            if (!Files.exists(target) || Files.isDirectory(target)) {
                return Optional.of(textResource(uriText, "File not found: " + target)); //$NON-NLS-1$
            }
            String text = Files.readString(target, StandardCharsets.UTF_8);
            return Optional.of(textResource(uriText, text));
        } catch (Exception e) {
            return Optional.of(textResource(uriText, "Failed to read file: " + e.getMessage())); //$NON-NLS-1$
        }
    }

    private String extractQueryParam(String query, String name) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String[] pairs = query.split("&"); //$NON-NLS-1$
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2); //$NON-NLS-1$
            if (kv.length == 2 && name.equals(kv[0])) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private McpResourceContent textResource(String uri, String text) {
        McpResourceContent content = new McpResourceContent();
        McpResourceContent.ResourceContentItem item = new McpResourceContent.ResourceContentItem();
        item.setUri(uri);
        item.setMimeType("text/plain"); //$NON-NLS-1$
        item.setText(text != null ? text : ""); //$NON-NLS-1$
        List<McpResourceContent.ResourceContentItem> items = new ArrayList<>();
        items.add(item);
        content.setContents(items);
        return content;
    }

    private Path workspaceRoot() {
        var location = ResourcesPlugin.getWorkspace().getRoot().getLocation();
        if (location == null) {
            return null;
        }
        return Paths.get(location.toOSString());
    }
}
