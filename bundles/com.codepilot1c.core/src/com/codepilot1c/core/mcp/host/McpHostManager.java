package com.codepilot1c.core.mcp.host;

import com.codepilot1c.core.logging.VibeLogger;

/**
 * Singleton manager for MCP host lifecycle.
 */
public class McpHostManager {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(McpHostManager.class);

    private static McpHostManager instance;

    private IMcpHostServer server;

    public static synchronized McpHostManager getInstance() {
        if (instance == null) {
            instance = new McpHostManager();
        }
        return instance;
    }

    public synchronized void startIfEnabled() {
        McpHostConfig cfg = McpHostConfigStore.getInstance().load();
        if (!cfg.isEnabled()) {
            LOG.info("MCP host is disabled by preference"); //$NON-NLS-1$
            return;
        }
        if (server == null) {
            server = new McpHostServer(cfg);
        }
        server.start();
    }

    public synchronized void restart() {
        stopAll();
        startIfEnabled();
    }

    public synchronized void stopAll() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    public synchronized boolean isRunning() {
        return server != null && server.isRunning();
    }

    public synchronized IMcpHostServer getServer() {
        return server;
    }
}
