package com.codepilot1c.core.mcp.host;

import java.util.List;
import java.util.Map;

import com.codepilot1c.core.mcp.host.session.McpHostSession;

public interface IMcpHostServer {

    void start();

    void stop();

    boolean isRunning();

    void reloadConfig();

    Map<String, Object> getCapabilities();

    List<McpHostSession> getSessions();
}
