/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp.auth;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.logging.VibeLogger;

/**
 * OAuth 2.1 auth provider backed by secure token storage.
 */
public class OAuth2AuthProvider implements IMcpAuthProvider {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(OAuth2AuthProvider.class);
    private static final long REFRESH_SKEW_SECONDS = 60L;

    private final String profileId;
    private final String resourceUrl;
    private final String clientId;
    private final SecureTokenStore tokenStore;
    private final McpOAuthService oauthService;

    public OAuth2AuthProvider(String profileId, String resourceUrl, String clientId,
                              SecureTokenStore tokenStore, McpOAuthService oauthService) {
        this.profileId = profileId;
        this.resourceUrl = resourceUrl;
        this.clientId = clientId != null && !clientId.isBlank() ? clientId : "codepilot1c"; //$NON-NLS-1$
        this.tokenStore = tokenStore;
        this.oauthService = oauthService;
    }

    @Override
    public CompletableFuture<Map<String, String>> getAuthHeaders() {
        return CompletableFuture.supplyAsync(() -> {
            Optional<SecureTokenStore.OAuthToken> maybeToken = tokenStore.read(profileId);
            if (maybeToken.isEmpty()) {
                return Collections.<String, String>emptyMap();
            }
            SecureTokenStore.OAuthToken token = maybeToken.get();
            if (token.willExpireSoon(REFRESH_SKEW_SECONDS) && token.refreshToken() != null
                && !token.refreshToken().isBlank()) {
                try {
                    String tokenEndpoint = oauthService.discoverTokenEndpoint(resourceUrl);
                    if (tokenEndpoint != null) {
                        token = oauthService.refreshToken(tokenEndpoint, clientId, token.refreshToken());
                        tokenStore.save(profileId, token);
                    }
                } catch (Exception e) {
                    LOG.warn("OAuth refresh failed for profile %s: %s", profileId, e.getMessage()); //$NON-NLS-1$
                }
            }
            return oauthService.buildAuthHeaders(token);
        });
    }

    @Override
    public void invalidate() {
        tokenStore.clear(profileId);
    }
}
