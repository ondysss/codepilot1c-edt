/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp.transport;

import java.util.HashMap;
import java.util.Map;

import com.codepilot1c.core.internal.VibeCorePlugin;
import com.codepilot1c.core.mcp.auth.IMcpAuthProvider;
import com.codepilot1c.core.mcp.auth.McpAuthHttpInterceptor;
import com.codepilot1c.core.mcp.auth.McpOAuthService;
import com.codepilot1c.core.mcp.auth.NoAuthProvider;
import com.codepilot1c.core.mcp.auth.OAuth2AuthProvider;
import com.codepilot1c.core.mcp.auth.SecureTokenStore;
import com.codepilot1c.core.mcp.auth.StaticHeadersAuthProvider;
import com.codepilot1c.core.mcp.config.McpServerConfig;
import com.codepilot1c.core.mcp.config.McpServerConfig.AuthMode;
import com.codepilot1c.core.mcp.config.McpServerConfig.TransportType;

/**
 * Factory for MCP transports.
 */
public class McpTransportFactory {

    private final SecureTokenStore tokenStore = new SecureTokenStore();

    public IMcpTransport create(McpServerConfig config) {
        if (config.getTransportType() == TransportType.STDIO) {
            return new McpStdioTransport(
                config.getCommand(),
                config.getArgs(),
                config.getEnv(),
                config.getWorkingDirectory() != null ? new java.io.File(config.getWorkingDirectory()) : null,
                config.getRequestTimeoutMs()
            );
        }

        IMcpAuthProvider authProvider = createAuthProvider(config);
        McpAuthHttpInterceptor interceptor = new McpAuthHttpInterceptor(authProvider);
        Map<String, String> configuredHeaders = new HashMap<>(config.getStaticHeaders());
        // Avoid duplicate headers when STATIC_HEADERS are provided via auth interceptor.
        Map<String, String> transportHeaders = config.getAuthMode() == AuthMode.STATIC_HEADERS
            ? Map.of()
            : configuredHeaders;
        boolean allowInsecureHttp = "true".equalsIgnoreCase(System.getProperty("codepilot.mcp.allowInsecureHttp")); //$NON-NLS-1$ //$NON-NLS-2$

        if (config.getTransportType() == TransportType.HTTP_SSE_LEGACY) {
            return new McpLegacySseTransport(
                config.getRemoteSseUrl() != null ? config.getRemoteSseUrl() : config.getRemoteUrl(),
                transportHeaders,
                interceptor,
                config.getRequestTimeoutMs(),
                allowInsecureHttp
            );
        }

        McpStreamableHttpTransport primary = new McpStreamableHttpTransport(
            config.getRemoteUrl(),
            transportHeaders,
            interceptor,
            config.getRequestTimeoutMs(),
            false,
            allowInsecureHttp
        );

        if (!config.isAllowLegacyFallback()) {
            return primary;
        }

        String fallbackUrl = config.getRemoteSseUrl() != null ? config.getRemoteSseUrl() : config.getRemoteUrl();
        McpLegacySseTransport legacyFallback = new McpLegacySseTransport(
            fallbackUrl,
            transportHeaders,
            interceptor,
            config.getRequestTimeoutMs(),
            allowInsecureHttp
        );
        return new FallbackMcpTransport(primary, legacyFallback);
    }

    private IMcpAuthProvider createAuthProvider(McpServerConfig config) {
        if (config.getAuthMode() == AuthMode.STATIC_HEADERS) {
            return new StaticHeadersAuthProvider(config.getStaticHeaders());
        }
        if (config.getAuthMode() == AuthMode.OAUTH2 && config.getOauthProfileId() != null
            && !config.getOauthProfileId().isBlank()) {
            var plugin = VibeCorePlugin.getDefault();
            var httpClient = plugin != null && plugin.getHttpClientFactory() != null
                ? plugin.getHttpClientFactory().getSharedClient()
                : java.net.http.HttpClient.newHttpClient();
            McpOAuthService oauthService = new McpOAuthService(httpClient);
            return new OAuth2AuthProvider(
                config.getOauthProfileId(),
                config.getRemoteUrl(),
                "codepilot1c", //$NON-NLS-1$
                tokenStore,
                oauthService
            );
        }
        return new NoAuthProvider();
    }
}
