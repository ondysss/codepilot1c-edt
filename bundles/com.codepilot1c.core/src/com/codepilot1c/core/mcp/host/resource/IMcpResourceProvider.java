package com.codepilot1c.core.mcp.host.resource;

import java.util.List;
import java.util.Optional;

import com.codepilot1c.core.mcp.host.session.McpHostSession;
import com.codepilot1c.core.mcp.model.McpResource;
import com.codepilot1c.core.mcp.model.McpResourceContent;

public interface IMcpResourceProvider {

    List<McpResource> listResources(McpHostSession session);

    Optional<McpResourceContent> readResource(String uri, McpHostSession session);
}
