package com.codepilot1c.core.mcp.host.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.codepilot1c.core.mcp.host.session.McpHostSession;
import com.codepilot1c.core.mcp.model.McpResource;
import com.codepilot1c.core.mcp.model.McpResourceContent;
import com.codepilot1c.core.state.VibeState;
import com.codepilot1c.core.state.VibeStateService;

public class StateResourceProvider implements IMcpResourceProvider {

    private static final String STATE_URI = "codepilot://state/session"; //$NON-NLS-1$

    @Override
    public List<McpResource> listResources(McpHostSession session) {
        McpResource resource = new McpResource(STATE_URI, "CodePilot Session State"); //$NON-NLS-1$
        resource.setDescription("Current plugin runtime state"); //$NON-NLS-1$
        resource.setMimeType("application/json"); //$NON-NLS-1$
        return List.of(resource);
    }

    @Override
    public Optional<McpResourceContent> readResource(String uri, McpHostSession session) {
        if (!STATE_URI.equals(uri)) {
            return Optional.empty();
        }

        VibeStateService stateService = VibeStateService.getInstance();
        VibeState state = stateService.getState();
        String payload = "{\"status\":\"" + escape(state.name()) + "\",\"message\":\"" //$NON-NLS-1$ //$NON-NLS-2$
            + escape(stateService.getStatusMessage()) + "\",\"error\":\"" + escape(stateService.getErrorMessage()) //$NON-NLS-1$
            + "\",\"sessionId\":\"" + escape(session.getSessionId()) + "\"}"; //$NON-NLS-1$ //$NON-NLS-2$
        McpResourceContent content = new McpResourceContent();
        McpResourceContent.ResourceContentItem item = new McpResourceContent.ResourceContentItem();
        item.setUri(uri);
        item.setMimeType("application/json"); //$NON-NLS-1$
        item.setText(payload);
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
