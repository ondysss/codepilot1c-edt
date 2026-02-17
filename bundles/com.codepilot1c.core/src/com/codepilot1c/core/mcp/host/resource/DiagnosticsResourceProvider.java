package com.codepilot1c.core.mcp.host.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;

import com.codepilot1c.core.mcp.host.session.McpHostSession;
import com.codepilot1c.core.mcp.model.McpResource;
import com.codepilot1c.core.mcp.model.McpResourceContent;

public class DiagnosticsResourceProvider implements IMcpResourceProvider {

    private static final String DIAG_URI = "codepilot://state/diagnostics"; //$NON-NLS-1$

    @Override
    public List<McpResource> listResources(McpHostSession session) {
        McpResource resource = new McpResource(DIAG_URI, "Workspace Diagnostics"); //$NON-NLS-1$
        resource.setDescription("Current Eclipse workspace markers (errors/warnings)"); //$NON-NLS-1$
        resource.setMimeType("application/json"); //$NON-NLS-1$
        return List.of(resource);
    }

    @Override
    public Optional<McpResourceContent> readResource(String uri, McpHostSession session) {
        if (!DIAG_URI.equals(uri)) {
            return Optional.empty();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[\n"); //$NON-NLS-1$
        try {
            IMarker[] markers = ResourcesPlugin.getWorkspace().getRoot().findMarkers(
                IMarker.PROBLEM,
                true,
                IResource.DEPTH_INFINITE
            );
            int limit = Math.min(markers.length, 1000);
            for (int i = 0; i < limit; i++) {
                IMarker m = markers[i];
                if (i > 0) {
                    sb.append(",\n"); //$NON-NLS-1$
                }
                String path = m.getResource() != null ? m.getResource().getFullPath().toString() : ""; //$NON-NLS-1$
                int severity = m.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
                int line = m.getAttribute(IMarker.LINE_NUMBER, -1);
                String message = escape(m.getAttribute(IMarker.MESSAGE, "")); //$NON-NLS-1$
                sb.append("  {\"path\":\"").append(escape(path)).append("\",\"severity\":") //$NON-NLS-1$ //$NON-NLS-2$
                    .append(severity)
                    .append(",\"line\":").append(line) //$NON-NLS-1$
                    .append(",\"message\":\"").append(message).append("\"}"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        } catch (Exception e) {
            sb.append("  {\"error\":\"").append(escape(e.getMessage())).append("\"}"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append("\n]"); //$NON-NLS-1$

        McpResourceContent content = new McpResourceContent();
        McpResourceContent.ResourceContentItem item = new McpResourceContent.ResourceContentItem();
        item.setUri(uri);
        item.setMimeType("application/json"); //$NON-NLS-1$
        item.setText(sb.toString());
        List<McpResourceContent.ResourceContentItem> items = new ArrayList<>();
        items.add(item);
        content.setContents(items);
        return Optional.of(content);
    }

    private String escape(String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    }
}
