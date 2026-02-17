package com.codepilot1c.core.mcp.host.transport;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal OAuth 2.1 authorization server for MCP Host HTTP transport.
 *
 * <p>Implements:
 * <ul>
 * <li>OAuth Protected Resource Metadata (RFC 9728)</li>
 * <li>Authorization Server Metadata</li>
 * <li>Dynamic Client Registration (RFC 7591)</li>
 * <li>Authorization Code + PKCE (S256)</li>
 * <li>Refresh tokens</li>
 * </ul>
 */
public class McpHostOAuthService {

    private static final String DEFAULT_SCOPE = "mcp"; //$NON-NLS-1$
    private static final Duration AUTH_CODE_TTL = Duration.ofMinutes(5);
    private static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(60);
    private static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(30);

    private static final String AUTH_METHOD_NONE = "none"; //$NON-NLS-1$
    private static final String AUTH_METHOD_SECRET_POST = "client_secret_post"; //$NON-NLS-1$
    private static final Set<String> SUPPORTED_TOKEN_AUTH_METHODS = Set.of(
        AUTH_METHOD_NONE,
        AUTH_METHOD_SECRET_POST
    );

    private final String issuer;
    private final String resourceEndpoint;
    private final String protectedResourceMetadataEndpoint;
    private final String authorizationEndpoint;
    private final String tokenEndpoint;
    private final String registrationEndpoint;
    private final String staticBearerToken;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, RegisteredClient> clients = new ConcurrentHashMap<>();
    private final Map<String, AuthorizationCodeGrant> authorizationCodes = new ConcurrentHashMap<>();
    private final Map<String, AccessTokenGrant> accessTokens = new ConcurrentHashMap<>();
    private final Map<String, RefreshTokenGrant> refreshTokens = new ConcurrentHashMap<>();

    public McpHostOAuthService(String bindAddress, int port, String staticBearerToken) {
        String normalizedHost = normalizeHost(bindAddress);
        this.issuer = "http://" + normalizedHost + ":" + port; //$NON-NLS-1$ //$NON-NLS-2$
        this.resourceEndpoint = issuer + "/mcp"; //$NON-NLS-1$
        this.protectedResourceMetadataEndpoint = issuer + "/.well-known/oauth-protected-resource"; //$NON-NLS-1$
        this.authorizationEndpoint = issuer + "/oauth/authorize"; //$NON-NLS-1$
        this.tokenEndpoint = issuer + "/oauth/token"; //$NON-NLS-1$
        this.registrationEndpoint = issuer + "/oauth/register"; //$NON-NLS-1$
        this.staticBearerToken = staticBearerToken != null ? staticBearerToken.trim() : ""; //$NON-NLS-1$
    }

    public boolean isAuthorized(Map<String, List<String>> headers) {
        String bearerToken = extractBearerToken(headers);
        if (bearerToken == null) {
            return false;
        }
        if (!staticBearerToken.isBlank() && constantTimeEquals(staticBearerToken, bearerToken)) {
            return true;
        }
        cleanupExpired();
        AccessTokenGrant grant = accessTokens.get(bearerToken);
        return grant != null && !grant.isExpired();
    }

    public String buildWwwAuthenticateHeader() {
        return "Bearer realm=\"codepilot1c\", resource_metadata=\"" //$NON-NLS-1$
            + protectedResourceMetadataEndpoint
            + "\", scope=\"" + DEFAULT_SCOPE + "\""; //$NON-NLS-1$ //$NON-NLS-2$
    }

    public Map<String, Object> protectedResourceMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("resource", resourceEndpoint); //$NON-NLS-1$
        metadata.put("authorization_servers", List.of(issuer)); //$NON-NLS-1$
        metadata.put("scopes_supported", List.of(DEFAULT_SCOPE)); //$NON-NLS-1$
        metadata.put("bearer_methods_supported", List.of("header")); //$NON-NLS-1$ //$NON-NLS-2$
        return metadata;
    }

    public Map<String, Object> authorizationServerMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("issuer", issuer); //$NON-NLS-1$
        metadata.put("authorization_endpoint", authorizationEndpoint); //$NON-NLS-1$
        metadata.put("token_endpoint", tokenEndpoint); //$NON-NLS-1$
        metadata.put("registration_endpoint", registrationEndpoint); //$NON-NLS-1$
        metadata.put("response_types_supported", List.of("code")); //$NON-NLS-1$ //$NON-NLS-2$
        metadata.put("grant_types_supported", List.of("authorization_code", "refresh_token")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        metadata.put("token_endpoint_auth_methods_supported", List.of(AUTH_METHOD_NONE, AUTH_METHOD_SECRET_POST)); //$NON-NLS-1$
        metadata.put("code_challenge_methods_supported", List.of("S256")); //$NON-NLS-1$ //$NON-NLS-2$
        metadata.put("scopes_supported", List.of(DEFAULT_SCOPE)); //$NON-NLS-1$
        return metadata;
    }

    public OAuthResponse registerClient(Map<String, Object> request) {
        cleanupExpired();
        List<String> redirectUris = toStringList(request.get("redirect_uris")); //$NON-NLS-1$
        if (redirectUris.isEmpty()) {
            return OAuthResponse.json(400, oauthError("invalid_client_metadata", "redirect_uris is required")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        for (String redirectUri : redirectUris) {
            if (!isValidRedirectUri(redirectUri)) {
                return OAuthResponse.json(400, oauthError("invalid_client_metadata", "Invalid redirect URI: " + redirectUri)); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }

        String tokenAuthMethod = stringOrDefault(request.get("token_endpoint_auth_method"), AUTH_METHOD_NONE); //$NON-NLS-1$
        if (!SUPPORTED_TOKEN_AUTH_METHODS.contains(tokenAuthMethod)) {
            return OAuthResponse.json(400, oauthError(
                "invalid_client_metadata", //$NON-NLS-1$
                "Unsupported token_endpoint_auth_method: " + tokenAuthMethod)); //$NON-NLS-1$
        }

        String clientId = "cp1c_" + generateRandomToken(18); //$NON-NLS-1$
        String clientSecret = AUTH_METHOD_SECRET_POST.equals(tokenAuthMethod)
            ? generateRandomToken(24)
            : null;
        RegisteredClient client = new RegisteredClient(clientId, clientSecret, tokenAuthMethod, Set.copyOf(redirectUris));
        clients.put(clientId, client);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("client_id", clientId); //$NON-NLS-1$
        payload.put("client_id_issued_at", Long.valueOf(Instant.now().getEpochSecond())); //$NON-NLS-1$
        if (clientSecret != null) {
            payload.put("client_secret", clientSecret); //$NON-NLS-1$
            payload.put("client_secret_expires_at", Long.valueOf(0)); //$NON-NLS-1$
        }
        payload.put("redirect_uris", new ArrayList<>(redirectUris)); //$NON-NLS-1$
        payload.put("grant_types", List.of("authorization_code", "refresh_token")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        payload.put("response_types", List.of("code")); //$NON-NLS-1$ //$NON-NLS-2$
        payload.put("token_endpoint_auth_method", tokenAuthMethod); //$NON-NLS-1$
        payload.put("scope", DEFAULT_SCOPE); //$NON-NLS-1$
        return OAuthResponse.json(201, payload);
    }

    public OAuthResponse authorize(Map<String, String> query) {
        cleanupExpired();
        String responseType = query.get("response_type"); //$NON-NLS-1$
        if (!"code".equals(responseType)) { //$NON-NLS-1$
            return OAuthResponse.json(400, oauthError("unsupported_response_type", "response_type must be code")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        String clientId = query.get("client_id"); //$NON-NLS-1$
        String redirectUri = query.get("redirect_uri"); //$NON-NLS-1$
        String codeChallenge = query.get("code_challenge"); //$NON-NLS-1$
        String codeChallengeMethod = query.get("code_challenge_method"); //$NON-NLS-1$
        String state = query.get("state"); //$NON-NLS-1$
        String requestedScope = query.get("scope"); //$NON-NLS-1$

        if (isBlank(clientId)) {
            return OAuthResponse.json(400, oauthError("invalid_request", "client_id is required")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (isBlank(redirectUri) || !isValidRedirectUri(redirectUri)) {
            return OAuthResponse.json(400, oauthError("invalid_request", "redirect_uri is invalid")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (isBlank(codeChallenge)) {
            return OAuthResponse.json(400, oauthError("invalid_request", "code_challenge is required")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!"S256".equals(codeChallengeMethod)) { //$NON-NLS-1$
            return OAuthResponse.json(400, oauthError("invalid_request", "code_challenge_method must be S256")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!isBlank(requestedScope) && !requestedScope.contains(DEFAULT_SCOPE)) {
            return OAuthResponse.json(400, oauthError("invalid_scope", "Scope mcp is required")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        RegisteredClient client = clients.get(clientId);
        if (client == null) {
            client = new RegisteredClient(clientId, null, AUTH_METHOD_NONE, Set.of(redirectUri));
            clients.put(clientId, client);
        }
        if (!client.redirectUris.contains(redirectUri)) {
            return OAuthResponse.json(400, oauthError("invalid_request", "redirect_uri is not registered")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        String code = generateRandomToken(24);
        String scope = isBlank(requestedScope) ? DEFAULT_SCOPE : requestedScope.trim();
        authorizationCodes.put(code, new AuthorizationCodeGrant(
            code, clientId, redirectUri, codeChallenge, scope, Instant.now().plus(AUTH_CODE_TTL).getEpochSecond()));

        Map<String, String> redirectParams = new LinkedHashMap<>();
        redirectParams.put("code", code); //$NON-NLS-1$
        if (state != null && !state.isBlank()) {
            redirectParams.put("state", state); //$NON-NLS-1$
        }
        redirectParams.put("iss", issuer); //$NON-NLS-1$
        String location = appendQuery(redirectUri, redirectParams);
        return OAuthResponse.redirect(302, location);
    }

    public OAuthResponse exchangeToken(Map<String, String> form) {
        cleanupExpired();
        String grantType = form.get("grant_type"); //$NON-NLS-1$
        if (isBlank(grantType)) {
            return OAuthResponse.json(400, oauthError("invalid_request", "grant_type is required")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return switch (grantType) {
            case "authorization_code" -> exchangeAuthorizationCode(form); //$NON-NLS-1$
            case "refresh_token" -> exchangeRefreshToken(form); //$NON-NLS-1$
            default -> OAuthResponse.json(400, oauthError("unsupported_grant_type", "Unsupported grant_type: " + grantType)); //$NON-NLS-1$ //$NON-NLS-2$
        };
    }

    private OAuthResponse exchangeAuthorizationCode(Map<String, String> form) {
        String code = form.get("code"); //$NON-NLS-1$
        String clientId = form.get("client_id"); //$NON-NLS-1$
        String redirectUri = form.get("redirect_uri"); //$NON-NLS-1$
        String codeVerifier = form.get("code_verifier"); //$NON-NLS-1$

        if (isBlank(code) || isBlank(clientId) || isBlank(redirectUri) || isBlank(codeVerifier)) {
            return OAuthResponse.json(400, oauthError("invalid_request", "Missing required parameters")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        RegisteredClient client = clients.get(clientId);
        if (client == null) {
            return OAuthResponse.json(401, oauthError("invalid_client", "Unknown client_id")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!isClientAuthenticated(client, form)) {
            return OAuthResponse.json(401, oauthError("invalid_client", "Client authentication failed")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        AuthorizationCodeGrant grant = authorizationCodes.remove(code);
        if (grant == null || grant.isExpired()) {
            return OAuthResponse.json(400, oauthError("invalid_grant", "Authorization code is invalid or expired")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!constantTimeEquals(clientId, grant.clientId) || !constantTimeEquals(redirectUri, grant.redirectUri)) {
            return OAuthResponse.json(400, oauthError("invalid_grant", "Authorization code validation failed")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!matchesCodeChallenge(codeVerifier, grant.codeChallenge)) {
            return OAuthResponse.json(400, oauthError("invalid_grant", "PKCE verification failed")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        return OAuthResponse.json(200, issueTokens(clientId, grant.scope));
    }

    private OAuthResponse exchangeRefreshToken(Map<String, String> form) {
        String refreshToken = form.get("refresh_token"); //$NON-NLS-1$
        String clientId = form.get("client_id"); //$NON-NLS-1$
        if (isBlank(refreshToken) || isBlank(clientId)) {
            return OAuthResponse.json(400, oauthError("invalid_request", "Missing required parameters")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        RegisteredClient client = clients.get(clientId);
        if (client == null) {
            return OAuthResponse.json(401, oauthError("invalid_client", "Unknown client_id")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (!isClientAuthenticated(client, form)) {
            return OAuthResponse.json(401, oauthError("invalid_client", "Client authentication failed")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        RefreshTokenGrant refreshGrant = refreshTokens.remove(refreshToken);
        if (refreshGrant == null || refreshGrant.isExpired() || !constantTimeEquals(refreshGrant.clientId, clientId)) {
            return OAuthResponse.json(400, oauthError("invalid_grant", "Refresh token is invalid or expired")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        return OAuthResponse.json(200, issueTokens(clientId, refreshGrant.scope));
    }

    private boolean isClientAuthenticated(RegisteredClient client, Map<String, String> form) {
        if (AUTH_METHOD_NONE.equals(client.tokenAuthMethod)) {
            return true;
        }
        if (AUTH_METHOD_SECRET_POST.equals(client.tokenAuthMethod)) {
            String suppliedSecret = form.get("client_secret"); //$NON-NLS-1$
            return suppliedSecret != null
                && client.clientSecret != null
                && constantTimeEquals(client.clientSecret, suppliedSecret);
        }
        return false;
    }

    private Map<String, Object> issueTokens(String clientId, String scope) {
        String accessToken = generateRandomToken(32);
        String refreshToken = generateRandomToken(32);
        long accessExpiry = Instant.now().plus(ACCESS_TOKEN_TTL).getEpochSecond();
        long refreshExpiry = Instant.now().plus(REFRESH_TOKEN_TTL).getEpochSecond();

        accessTokens.put(accessToken, new AccessTokenGrant(accessToken, clientId, scope, accessExpiry));
        refreshTokens.put(refreshToken, new RefreshTokenGrant(refreshToken, clientId, scope, refreshExpiry));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("access_token", accessToken); //$NON-NLS-1$
        response.put("token_type", "Bearer"); //$NON-NLS-1$ //$NON-NLS-2$
        response.put("expires_in", Long.valueOf(ACCESS_TOKEN_TTL.toSeconds())); //$NON-NLS-1$
        response.put("refresh_token", refreshToken); //$NON-NLS-1$
        response.put("scope", scope); //$NON-NLS-1$
        return response;
    }

    private void cleanupExpired() {
        long now = Instant.now().getEpochSecond();
        authorizationCodes.entrySet().removeIf(entry -> entry.getValue().expiresAtEpochSecond <= now);
        accessTokens.entrySet().removeIf(entry -> entry.getValue().expiresAtEpochSecond <= now);
        refreshTokens.entrySet().removeIf(entry -> entry.getValue().expiresAtEpochSecond <= now);
    }

    private String extractBearerToken(Map<String, List<String>> headers) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        List<String> authHeaders = headers.get("Authorization"); //$NON-NLS-1$
        if (authHeaders == null || authHeaders.isEmpty()) {
            authHeaders = headers.get("authorization"); //$NON-NLS-1$
        }
        if (authHeaders == null || authHeaders.isEmpty()) {
            return null;
        }
        for (String authHeader : authHeaders) {
            if (authHeader == null) {
                continue;
            }
            if (authHeader.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) { //$NON-NLS-1$ //$NON-NLS-2$
                String token = authHeader.substring("Bearer ".length()).trim(); //$NON-NLS-1$
                if (!token.isEmpty()) {
                    return token;
                }
            }
        }
        return null;
    }

    private boolean matchesCodeChallenge(String codeVerifier, String expectedChallenge) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.getBytes(StandardCharsets.US_ASCII)); //$NON-NLS-1$
            String actualChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            return constantTimeEquals(expectedChallenge, actualChallenge);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e); //$NON-NLS-1$
        }
    }

    private boolean isValidRedirectUri(String redirectUri) {
        if (isBlank(redirectUri)) {
            return false;
        }
        try {
            URI uri = URI.create(redirectUri);
            if (uri.getScheme() == null || uri.getScheme().isBlank()) {
                return false;
            }
            return !"javascript".equalsIgnoreCase(uri.getScheme()); //$NON-NLS-1$
        } catch (Exception e) {
            return false;
        }
    }

    private String appendQuery(String redirectUri, Map<String, String> params) {
        String fragment = ""; //$NON-NLS-1$
        int fragmentIndex = redirectUri.indexOf('#');
        String base = redirectUri;
        if (fragmentIndex >= 0) {
            fragment = redirectUri.substring(fragmentIndex);
            base = redirectUri.substring(0, fragmentIndex);
        }

        StringBuilder sb = new StringBuilder(base);
        sb.append(base.contains("?") ? '&' : '?'); //$NON-NLS-1$

        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            first = false;
            String value = entry.getValue() != null ? entry.getValue() : ""; //$NON-NLS-1$
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            sb.append('=');
            sb.append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
        sb.append(fragment);
        return sb.toString();
    }

    private Map<String, Object> oauthError(String error, String description) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error", error); //$NON-NLS-1$
        payload.put("error_description", description); //$NON-NLS-1$
        return payload;
    }

    @SuppressWarnings("unchecked")
    private List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            String str = item != null ? String.valueOf(item).trim() : ""; //$NON-NLS-1$
            if (!str.isEmpty()) {
                out.add(str);
            }
        }
        return out;
    }

    private String stringOrDefault(Object value, String defaultValue) {
        String str = value != null ? String.valueOf(value).trim() : ""; //$NON-NLS-1$
        return str.isEmpty() ? defaultValue : str;
    }

    private boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
        byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(leftBytes, rightBytes);
    }

    private String generateRandomToken(int byteLength) {
        byte[] bytes = new byte[byteLength];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String normalizeHost(String bindAddress) {
        String host = bindAddress != null && !bindAddress.isBlank() ? bindAddress.trim() : "127.0.0.1"; //$NON-NLS-1$
        if ("0.0.0.0".equals(host)) { //$NON-NLS-1$
            host = "127.0.0.1"; //$NON-NLS-1$
        }
        if (host.contains(":") && !host.startsWith("[") && !host.endsWith("]")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return "[" + host + "]"; //$NON-NLS-1$ //$NON-NLS-2$
        }
        return host;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static final class OAuthResponse {
        private final int statusCode;
        private final Map<String, Object> jsonBody;
        private final String redirectLocation;

        private OAuthResponse(int statusCode, Map<String, Object> jsonBody, String redirectLocation) {
            this.statusCode = statusCode;
            this.jsonBody = jsonBody;
            this.redirectLocation = redirectLocation;
        }

        public static OAuthResponse json(int statusCode, Map<String, Object> payload) {
            return new OAuthResponse(statusCode, payload, null);
        }

        public static OAuthResponse redirect(int statusCode, String redirectLocation) {
            return new OAuthResponse(statusCode, null, redirectLocation);
        }

        public int getStatusCode() {
            return statusCode;
        }

        public Map<String, Object> getJsonBody() {
            return jsonBody;
        }

        public String getRedirectLocation() {
            return redirectLocation;
        }

        public boolean isRedirect() {
            return redirectLocation != null && !redirectLocation.isBlank();
        }
    }

    private static final class RegisteredClient {
        private final String clientId;
        private final String clientSecret;
        private final String tokenAuthMethod;
        private final Set<String> redirectUris;

        private RegisteredClient(String clientId, String clientSecret, String tokenAuthMethod, Set<String> redirectUris) {
            this.clientId = clientId;
            this.clientSecret = clientSecret;
            this.tokenAuthMethod = tokenAuthMethod;
            this.redirectUris = redirectUris;
        }
    }

    private static final class AuthorizationCodeGrant {
        private final String code;
        private final String clientId;
        private final String redirectUri;
        private final String codeChallenge;
        private final String scope;
        private final long expiresAtEpochSecond;

        private AuthorizationCodeGrant(
                String code,
                String clientId,
                String redirectUri,
                String codeChallenge,
                String scope,
                long expiresAtEpochSecond) {
            this.code = code;
            this.clientId = clientId;
            this.redirectUri = redirectUri;
            this.codeChallenge = codeChallenge;
            this.scope = scope;
            this.expiresAtEpochSecond = expiresAtEpochSecond;
        }

        private boolean isExpired() {
            return expiresAtEpochSecond <= Instant.now().getEpochSecond();
        }
    }

    private static final class AccessTokenGrant {
        private final String token;
        private final String clientId;
        private final String scope;
        private final long expiresAtEpochSecond;

        private AccessTokenGrant(String token, String clientId, String scope, long expiresAtEpochSecond) {
            this.token = token;
            this.clientId = clientId;
            this.scope = scope;
            this.expiresAtEpochSecond = expiresAtEpochSecond;
        }

        private boolean isExpired() {
            return expiresAtEpochSecond <= Instant.now().getEpochSecond();
        }
    }

    private static final class RefreshTokenGrant {
        private final String token;
        private final String clientId;
        private final String scope;
        private final long expiresAtEpochSecond;

        private RefreshTokenGrant(String token, String clientId, String scope, long expiresAtEpochSecond) {
            this.token = token;
            this.clientId = clientId;
            this.scope = scope;
            this.expiresAtEpochSecond = expiresAtEpochSecond;
        }

        private boolean isExpired() {
            return expiresAtEpochSecond <= Instant.now().getEpochSecond();
        }
    }
}
