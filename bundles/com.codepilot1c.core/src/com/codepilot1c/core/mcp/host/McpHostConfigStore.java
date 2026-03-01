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
        cfg.setAuthMode(McpHostConfig.AuthMode.from(
            prefs.get(VibePreferenceConstants.PREF_MCP_HOST_AUTH_MODE, cfg.getAuthMode().name())));
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

        applySystemOverrides(cfg);
        return cfg;
    }

    private void applySystemOverrides(McpHostConfig cfg) {
        String enabled = coalesce(
                System.getProperty("codepilot.mcp.host.enabled"), //$NON-NLS-1$
                System.getProperty("codepilot.mcp.enabled")); //$NON-NLS-1$
        if (enabled != null) {
            cfg.setEnabled(Boolean.parseBoolean(enabled.trim()));
        }

        String httpEnabled = System.getProperty("codepilot.mcp.host.http.enabled"); //$NON-NLS-1$
        if (httpEnabled != null) {
            cfg.setHttpEnabled(Boolean.parseBoolean(httpEnabled.trim()));
        }

        String bind = System.getProperty("codepilot.mcp.host.http.bindAddress"); //$NON-NLS-1$
        if (bind != null && !bind.isBlank()) {
            cfg.setBindAddress(bind.trim());
        }

        String port = System.getProperty("codepilot.mcp.host.http.port"); //$NON-NLS-1$
        if (port != null && !port.isBlank()) {
            try {
                cfg.setPort(Integer.parseInt(port.trim()));
            } catch (NumberFormatException ignored) {
                // keep preference value
            }
        }

        String authMode = System.getProperty("codepilot.mcp.host.auth.mode"); //$NON-NLS-1$
        if (authMode != null && !authMode.isBlank()) {
            cfg.setAuthMode(McpHostConfig.AuthMode.from(authMode));
        }

        String mutationPolicy = System.getProperty("codepilot.mcp.host.policy.defaultMutationDecision"); //$NON-NLS-1$
        if (mutationPolicy != null && !mutationPolicy.isBlank()) {
            cfg.setMutationPolicy(McpHostConfig.MutationPolicy.from(mutationPolicy));
        }

        String exposedTools = System.getProperty("codepilot.mcp.host.policy.exposedTools"); //$NON-NLS-1$
        if (exposedTools != null && !exposedTools.isBlank()) {
            cfg.setExposedToolsFilter(exposedTools.trim());
        }

        String bearer = System.getProperty("codepilot.mcp.host.http.bearerToken"); //$NON-NLS-1$
        if (bearer != null && !bearer.isBlank()) {
            cfg.setBearerToken(bearer.trim());
        }
    }

    private String coalesce(String primary, String secondary) {
        return primary != null ? primary : secondary;
    }

    public void save(McpHostConfig cfg) {
        IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(VibeCorePlugin.PLUGIN_ID);
        prefs.putBoolean(VibePreferenceConstants.PREF_MCP_HOST_ENABLED, cfg.isEnabled());
        prefs.putBoolean(VibePreferenceConstants.PREF_MCP_HOST_HTTP_ENABLED, cfg.isHttpEnabled());
        prefs.put(VibePreferenceConstants.PREF_MCP_HOST_HTTP_BIND_ADDRESS, cfg.getBindAddress());
        prefs.putInt(VibePreferenceConstants.PREF_MCP_HOST_HTTP_PORT, cfg.getPort());
        prefs.put(VibePreferenceConstants.PREF_MCP_HOST_AUTH_MODE, cfg.getAuthMode().name());
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
