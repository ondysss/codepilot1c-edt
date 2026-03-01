package com.codepilot1c.core.mcp.host;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * MCP host configuration.
 */
public class McpHostConfig {

    public enum AuthMode {
        OAUTH_OR_BEARER,
        OAUTH_ONLY,
        BEARER_ONLY,
        NONE;

        public static AuthMode from(String value) {
            if (value == null || value.isBlank()) {
                return OAUTH_OR_BEARER;
            }
            try {
                return AuthMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return OAUTH_OR_BEARER;
            }
        }
    }

    public enum MutationPolicy {
        ASK,
        DENY,
        ALLOW;

        public static MutationPolicy from(String value) {
            if (value == null || value.isBlank()) {
                return ALLOW;
            }
            try {
                return MutationPolicy.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return ALLOW;
            }
        }
    }

    private boolean enabled;
    private boolean httpEnabled;
    private String bindAddress;
    private int port;
    private String bearerToken;
    private AuthMode authMode;
    private MutationPolicy mutationPolicy;
    private String exposedToolsFilter;

    public static McpHostConfig defaults() {
        McpHostConfig cfg = new McpHostConfig();
        cfg.enabled = true;
        cfg.httpEnabled = true;
        cfg.bindAddress = "127.0.0.1"; //$NON-NLS-1$
        cfg.port = 8765;
        cfg.bearerToken = generateToken();
        cfg.authMode = AuthMode.OAUTH_OR_BEARER;
        cfg.mutationPolicy = MutationPolicy.ALLOW;
        cfg.exposedToolsFilter = "*"; //$NON-NLS-1$
        return cfg;
    }

    public static String generateToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", Byte.valueOf(b))); //$NON-NLS-1$
        }
        return sb.toString();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isHttpEnabled() {
        return httpEnabled;
    }

    public void setHttpEnabled(boolean httpEnabled) {
        this.httpEnabled = httpEnabled;
    }

    public String getBindAddress() {
        return bindAddress;
    }

    public void setBindAddress(String bindAddress) {
        this.bindAddress = bindAddress;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getBearerToken() {
        return bearerToken;
    }

    public void setBearerToken(String bearerToken) {
        this.bearerToken = bearerToken;
    }

    public AuthMode getAuthMode() {
        return authMode;
    }

    public void setAuthMode(AuthMode authMode) {
        this.authMode = authMode;
    }

    public MutationPolicy getMutationPolicy() {
        return mutationPolicy;
    }

    public void setMutationPolicy(MutationPolicy mutationPolicy) {
        this.mutationPolicy = mutationPolicy;
    }

    public String getExposedToolsFilter() {
        return exposedToolsFilter;
    }

    public void setExposedToolsFilter(String exposedToolsFilter) {
        this.exposedToolsFilter = exposedToolsFilter;
    }
}
