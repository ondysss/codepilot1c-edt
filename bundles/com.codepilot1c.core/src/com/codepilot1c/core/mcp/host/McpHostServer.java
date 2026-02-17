package com.codepilot1c.core.mcp.host;

import java.util.List;
import java.util.Map;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.mcp.host.prompt.PromptTemplateProvider;
import com.codepilot1c.core.mcp.host.resource.DiagnosticsResourceProvider;
import com.codepilot1c.core.mcp.host.resource.IMcpResourceProvider;
import com.codepilot1c.core.mcp.host.resource.StateResourceProvider;
import com.codepilot1c.core.mcp.host.resource.WorkspaceResourceProvider;
import com.codepilot1c.core.mcp.host.session.McpHostSession;
import com.codepilot1c.core.mcp.host.transport.McpHostHttpTransport;
import com.codepilot1c.core.mcp.host.transport.McpHostOAuthService;

public class McpHostServer implements IMcpHostServer {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(McpHostServer.class);

    private McpHostConfig config;
    private McpHostHttpTransport httpTransport;
    private McpHostRequestRouter router;
    private volatile boolean running;

    public McpHostServer(McpHostConfig config) {
        this.config = config;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (!config.isEnabled()) {
            LOG.info("MCP host is disabled"); //$NON-NLS-1$
            return;
        }

        DefaultMcpToolExposurePolicy exposurePolicy = new DefaultMcpToolExposurePolicy(config);
        List<IMcpResourceProvider> resourceProviders = List.of(
            new WorkspaceResourceProvider(),
            new DiagnosticsResourceProvider(),
            new StateResourceProvider()
        );
        router = new McpHostRequestRouter(
            exposurePolicy,
            resourceProviders,
            new PromptTemplateProvider(),
            config.getMutationPolicy()
        );

        if (config.isHttpEnabled()) {
            McpHostOAuthService oauthService = new McpHostOAuthService(
                config.getBindAddress(),
                config.getPort(),
                config.getBearerToken()
            );
            httpTransport = new McpHostHttpTransport(
                config.getBindAddress(),
                config.getPort(),
                oauthService,
                router
            );
            httpTransport.start();
        }

        running = true;
        LOG.info("MCP host server started (http=%s, oauth=%s)", //$NON-NLS-1$
            Boolean.valueOf(config.isHttpEnabled()), Boolean.TRUE);
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        if (httpTransport != null) {
            httpTransport.stop();
            httpTransport = null;
        }
        running = false;
        LOG.info("MCP host server stopped"); //$NON-NLS-1$
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public synchronized void reloadConfig() {
        this.config = McpHostConfigStore.getInstance().load();
        stop();
        start();
    }

    @Override
    public Map<String, Object> getCapabilities() {
        if (router == null) {
            return Map.of();
        }
        return router.capabilitiesSnapshot();
    }

    @Override
    public List<McpHostSession> getSessions() {
        if (httpTransport == null) {
            return List.of();
        }
        return List.copyOf(httpTransport.getSessionsSnapshot());
    }
}
