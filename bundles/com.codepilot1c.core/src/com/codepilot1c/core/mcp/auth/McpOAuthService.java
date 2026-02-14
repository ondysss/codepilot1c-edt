/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp.auth;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import com.codepilot1c.core.logging.VibeLogger;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * OAuth 2.1 service utilities for MCP remote auth.
 */
public class McpOAuthService {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(McpOAuthService.class);
    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    public McpOAuthService(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Builds PKCE code verifier.
     *
     * @return URL-safe verifier
     */
    public String generateCodeVerifier() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Builds S256 code challenge.
     *
     * @param verifier code verifier
     * @return challenge
     */
    public String generateCodeChallenge(String verifier) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); //$NON-NLS-1$
        }
    }

    /**
     * Discovers OAuth metadata endpoint from protected resource.
     *
     * @param resourceUrl protected resource URL
     * @return token endpoint URL if discovered, otherwise null
     */
    public String discoverTokenEndpoint(String resourceUrl) {
        try {
            URI resourceUri = URI.create(resourceUrl);
            URI prmUri = URI.create(resourceUri.getScheme() + "://" + resourceUri.getAuthority() //$NON-NLS-1$
                + "/.well-known/oauth-protected-resource"); //$NON-NLS-1$
            HttpRequest prmReq = HttpRequest.newBuilder(prmUri).GET().build();
            HttpResponse<String> prmResp = httpClient.send(prmReq, HttpResponse.BodyHandlers.ofString());
            if (prmResp.statusCode() / 100 != 2) {
                return null;
            }
            JsonObject prm = gson.fromJson(prmResp.body(), JsonObject.class);
            if (prm == null || !prm.has("authorization_servers")) { //$NON-NLS-1$
                return null;
            }
            String issuer = prm.getAsJsonArray("authorization_servers").get(0).getAsString(); //$NON-NLS-1$
            URI metadataUri = URI.create(issuer.endsWith("/") //$NON-NLS-1$
                ? issuer + ".well-known/openid-configuration" //$NON-NLS-1$
                : issuer + "/.well-known/openid-configuration"); //$NON-NLS-1$
            HttpRequest metadataReq = HttpRequest.newBuilder(metadataUri).GET().build();
            HttpResponse<String> metadataResp = httpClient.send(metadataReq, HttpResponse.BodyHandlers.ofString());
            if (metadataResp.statusCode() / 100 != 2) {
                return null;
            }
            JsonObject metadata = gson.fromJson(metadataResp.body(), JsonObject.class);
            if (metadata != null && metadata.has("token_endpoint")) { //$NON-NLS-1$
                return metadata.get("token_endpoint").getAsString(); //$NON-NLS-1$
            }
            return null;
        } catch (Exception e) {
            LOG.warn("OAuth metadata discovery failed: %s", e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Exchanges authorization code for token.
     */
    public SecureTokenStore.OAuthToken exchangeAuthorizationCode(String tokenEndpoint, String clientId,
                                                                  String code, String codeVerifier,
                                                                  String redirectUri) throws IOException, InterruptedException {
        String body = "grant_type=authorization_code" //$NON-NLS-1$
            + "&client_id=" + encode(clientId) //$NON-NLS-1$
            + "&code=" + encode(code) //$NON-NLS-1$
            + "&code_verifier=" + encode(codeVerifier) //$NON-NLS-1$
            + "&redirect_uri=" + encode(redirectUri); //$NON-NLS-1$
        return executeTokenRequest(tokenEndpoint, body);
    }

    /**
     * Refreshes token.
     */
    public SecureTokenStore.OAuthToken refreshToken(String tokenEndpoint, String clientId, String refreshToken)
            throws IOException, InterruptedException {
        String body = "grant_type=refresh_token" //$NON-NLS-1$
            + "&client_id=" + encode(clientId) //$NON-NLS-1$
            + "&refresh_token=" + encode(refreshToken); //$NON-NLS-1$
        return executeTokenRequest(tokenEndpoint, body);
    }

    private SecureTokenStore.OAuthToken executeTokenRequest(String tokenEndpoint, String body)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenEndpoint))
            .header("Content-Type", "application/x-www-form-urlencoded") //$NON-NLS-1$ //$NON-NLS-2$
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("OAuth token request failed: HTTP " + response.statusCode()); //$NON-NLS-1$
        }
        JsonObject token = gson.fromJson(response.body(), JsonObject.class);
        if (token == null || !token.has("access_token")) { //$NON-NLS-1$
            throw new IOException("OAuth token response missing access_token"); //$NON-NLS-1$
        }
        String access = token.get("access_token").getAsString(); //$NON-NLS-1$
        String refresh = token.has("refresh_token") ? token.get("refresh_token").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
        String tokenType = token.has("token_type") ? token.get("token_type").getAsString() : "Bearer"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        long expiresIn = token.has("expires_in") ? token.get("expires_in").getAsLong() : 3600L; //$NON-NLS-1$ //$NON-NLS-2$
        return new SecureTokenStore.OAuthToken(access, refresh, tokenType, Instant.now().plusSeconds(expiresIn).getEpochSecond());
    }

    public Map<String, String> buildAuthHeaders(SecureTokenStore.OAuthToken token) {
        Map<String, String> headers = new HashMap<>();
        String type = token.tokenType() != null ? token.tokenType() : "Bearer"; //$NON-NLS-1$
        headers.put("Authorization", type + " " + token.accessToken()); //$NON-NLS-1$ //$NON-NLS-2$
        return headers;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
