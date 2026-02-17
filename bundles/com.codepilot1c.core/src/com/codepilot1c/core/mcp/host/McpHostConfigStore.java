package com.codepilot1c.core.mcp.host;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;

import com.codepilot1c.core.internal.VibeCorePlugin;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.settings.SecureStorageUtil;
import com.codepilot1c.core.settings.VibePreferenceConstants;

/**
 * Preference-backed config store for MCP host.
 */
public class McpHostConfigStore {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(McpHostConfigStore.class);
    private static final String TOKEN_SECURE_KEY = "mcp.host.http.bearerToken"; //$NON-NLS-1$

    private static McpHostConfigStore instance;

    public static synchronized McpHostConfigStore getInstance() {
        if (instance == null) {
            instance = new McpHostConfigStore();
        }
        return instance;
    }

    public McpHostConfig load() {
        IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(VibeCorePlugin.PLUGIN_ID);
        McpHostConfig cfg = McpHostConfig.defaults();
        cfg.setEnabled(prefs.getBoolean(VibePreferenceConstants.PREF_MCP_HOST_ENABLED, cfg.isEnabled()));
        cfg.setHttpEnabled(prefs.getBoolean(VibePreferenceConstants.PREF_MCP_HOST_HTTP_ENABLED, cfg.isHttpEnabled()));
        cfg.setBindAddress(prefs.get(VibePreferenceConstants.PREF_MCP_HOST_HTTP_BIND_ADDRESS, cfg.getBindAddress()));
        cfg.setPort(prefs.getInt(VibePreferenceConstants.PREF_MCP_HOST_HTTP_PORT, cfg.getPort()));
        cfg.setMutationPolicy(McpHostConfig.MutationPolicy.from(
            prefs.get(VibePreferenceConstants.PREF_MCP_HOST_POLICY_DEFAULT_MUTATION_DECISION, cfg.getMutationPolicy().name())));
        cfg.setExposedToolsFilter(prefs.get(
            VibePreferenceConstants.PREF_MCP_HOST_POLICY_EXPOSED_TOOLS,
            cfg.getExposedToolsFilter()));

        String token = SecureStorageUtil.retrieveSecurely(TOKEN_SECURE_KEY, ""); //$NON-NLS-1$
        if (token.isBlank()) {
            token = McpHostConfig.generateToken();
            SecureStorageUtil.storeSecurely(TOKEN_SECURE_KEY, token);
        }
        cfg.setBearerToken(token);
        return cfg;
    }

    public void save(McpHostConfig cfg) {
        IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(VibeCorePlugin.PLUGIN_ID);
        prefs.putBoolean(VibePreferenceConstants.PREF_MCP_HOST_ENABLED, cfg.isEnabled());
        prefs.putBoolean(VibePreferenceConstants.PREF_MCP_HOST_HTTP_ENABLED, cfg.isHttpEnabled());
        prefs.put(VibePreferenceConstants.PREF_MCP_HOST_HTTP_BIND_ADDRESS, cfg.getBindAddress());
        prefs.putInt(VibePreferenceConstants.PREF_MCP_HOST_HTTP_PORT, cfg.getPort());
        prefs.put(VibePreferenceConstants.PREF_MCP_HOST_POLICY_DEFAULT_MUTATION_DECISION, cfg.getMutationPolicy().name());
        prefs.put(VibePreferenceConstants.PREF_MCP_HOST_POLICY_EXPOSED_TOOLS, cfg.getExposedToolsFilter());
        SecureStorageUtil.storeSecurely(TOKEN_SECURE_KEY, cfg.getBearerToken());

        try {
            prefs.flush();
        } catch (Exception e) {
            LOG.error("Failed to save MCP host preferences", e); //$NON-NLS-1$
        }
    }
}
