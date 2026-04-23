/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.backend;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.settings.SecureStorageUtil;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Backend API client used for plugin account registration and usage tracking.
 */
public class BackendService {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(BackendService.class);
    private static final int MAX_LOG_BODY_LENGTH = 400;

    private static final String SECURE_KEY_API_KEY = "backend.apiKey"; //$NON-NLS-1$
    private static final String SECURE_KEY_USER_ID = "backend.userId"; //$NON-NLS-1$
    private static final String SECURE_KEY_USER_EMAIL = "backend.userEmail"; //$NON-NLS-1$

    private static final String CONTENT_TYPE_JSON = "application/json"; //$NON-NLS-1$
    private static final String HEADER_CONTENT_TYPE = "Content-Type"; //$NON-NLS-1$
    private static final String HEADER_AUTHORIZATION = "Authorization"; //$NON-NLS-1$
    private static final String BEARER_PREFIX = "Bearer "; //$NON-NLS-1$

    private static final long USAGE_CACHE_TTL = 5 * 60 * 1000L;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    private static volatile BackendService instance;

    /**
     * Listener notified when fresh usage data is fetched.
     */
    public interface UsageChangeListener {
        void onUsageChanged(UsageInfo newUsage);
    }

    private final HttpClient httpClient;
    private final Gson gson;
    private final List<UsageChangeListener> usageListeners = new CopyOnWriteArrayList<>();

    private volatile UsageInfo cachedUsage;
    private volatile long lastUsageFetch;

    private BackendService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .build();
        this.gson = new Gson();
    }

    public static BackendService getInstance() {
        if (instance == null) {
            synchronized (BackendService.class) {
                if (instance == null) {
                    instance = new BackendService();
                }
            }
        }
        return instance;
    }

    public CompletableFuture<SignupStartResult> signupStart(String email, String name) {
        LOG.info("Signup start request for user: %s", email); //$NON-NLS-1$
        JsonObject body = new JsonObject();
        body.addProperty("email", email); //$NON-NLS-1$
        body.addProperty("name", name); //$NON-NLS-1$

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BackendConfig.AUTH_BASE_URL + BackendConfig.PLUGIN_SIGNUP_START_ENDPOINT))
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .timeout(HTTP_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(this::handleSignupStartResponse)
                .exceptionally(ex -> {
                    LOG.error("Signup start failed for user: " + email, unwrapException(ex)); //$NON-NLS-1$
                    return SignupStartResult.failure("Сервис регистрации временно недоступен", //$NON-NLS-1$
                            "service_unavailable", 0); //$NON-NLS-1$
                });
    }

    public CompletableFuture<RegistrationResult> signupConfirm(String email, String name, String password,
            String verificationCode) {
        LOG.info("Signup confirm request for user: %s", email); //$NON-NLS-1$
        JsonObject body = new JsonObject();
        body.addProperty("email", email); //$NON-NLS-1$
        body.addProperty("name", name); //$NON-NLS-1$
        body.addProperty("password", password); //$NON-NLS-1$
        body.addProperty("verification_code", verificationCode); //$NON-NLS-1$

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BackendConfig.AUTH_BASE_URL + BackendConfig.PLUGIN_SIGNUP_CONFIRM_ENDPOINT))
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .timeout(HTTP_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(this::handlePluginAuthResponse)
                .exceptionally(ex -> {
                    LOG.error("Signup confirm failed for user: " + email, unwrapException(ex)); //$NON-NLS-1$
                    return RegistrationResult.failure("Сервис регистрации временно недоступен", //$NON-NLS-1$
                            "service_unavailable", 0); //$NON-NLS-1$
                });
    }

    public CompletableFuture<RegistrationResult> login(String email, String password) {
        LOG.info("Login request for user: %s", email); //$NON-NLS-1$
        JsonObject body = new JsonObject();
        body.addProperty("email", email); //$NON-NLS-1$
        body.addProperty("password", password); //$NON-NLS-1$

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BackendConfig.AUTH_BASE_URL + BackendConfig.PLUGIN_LOGIN_ENDPOINT))
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .timeout(HTTP_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(this::handlePluginAuthResponse)
                .exceptionally(ex -> {
                    LOG.error("Login failed for user: " + email, unwrapException(ex)); //$NON-NLS-1$
                    return RegistrationResult.failure("Сервис авторизации временно недоступен", //$NON-NLS-1$
                            "service_unavailable", 0); //$NON-NLS-1$
                });
    }

    public CompletableFuture<UsageInfo> getUsage() {
        String userId = getUserId();
        String apiKey = getApiKey();

        if (userId == null || userId.isEmpty() || apiKey == null || apiKey.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new BackendException("Not configured: missing credentials", 0, "not_configured")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        String url = BackendConfig.BASE_URL + BackendConfig.USAGE_ENDPOINT + "?user_id=" + userId; //$NON-NLS-1$
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header(HEADER_AUTHORIZATION, BEARER_PREFIX + apiKey)
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .timeout(HTTP_TIMEOUT)
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(this::handleUsageResponse)
                .exceptionally(ex -> {
                    LOG.error("Failed to fetch usage", unwrapException(ex)); //$NON-NLS-1$
                    return cachedUsage;
                });
    }

    public CompletableFuture<RegistrationResult> rotateKey() {
        String userId = getUserId();
        String apiKey = getApiKey();

        if (userId == null || userId.isEmpty() || apiKey == null || apiKey.isEmpty()) {
            return CompletableFuture.failedFuture(
                    new BackendException("Not configured: missing credentials", 0, "not_configured")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        JsonObject body = new JsonObject();
        body.addProperty("user_id", userId); //$NON-NLS-1$

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BackendConfig.BASE_URL + BackendConfig.ROTATE_KEY_ENDPOINT))
                .header(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                .header(HEADER_AUTHORIZATION, BEARER_PREFIX + apiKey)
                .timeout(HTTP_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(this::handleRotateKeyResponse)
                .exceptionally(ex -> RegistrationResult.failure(
                        ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage()));
    }

    public void storeCredentials(String apiKey, String userId) {
        storeCredentials(apiKey, userId, null);
    }

    public void storeCredentials(String apiKey, String userId, String email) {
        SecureStorageUtil.storeSecurely(SECURE_KEY_API_KEY, apiKey);
        SecureStorageUtil.storeSecurely(SECURE_KEY_USER_ID, userId);
        if (email != null && !email.isEmpty()) {
            SecureStorageUtil.storeSecurely(SECURE_KEY_USER_EMAIL, email);
        }
    }

    public String getApiKey() {
        return SecureStorageUtil.retrieveSecurely(SECURE_KEY_API_KEY, ""); //$NON-NLS-1$
    }

    public String getUserId() {
        return SecureStorageUtil.retrieveSecurely(SECURE_KEY_USER_ID, ""); //$NON-NLS-1$
    }

    public String getUserEmail() {
        return SecureStorageUtil.retrieveSecurely(SECURE_KEY_USER_EMAIL, ""); //$NON-NLS-1$
    }

    public boolean isConfigured() {
        String apiKey = getApiKey();
        String userId = getUserId();
        return apiKey != null && !apiKey.isEmpty() && userId != null && !userId.isEmpty();
    }

    public void clearCredentials() {
        SecureStorageUtil.removeSecurely(SECURE_KEY_API_KEY);
        SecureStorageUtil.removeSecurely(SECURE_KEY_USER_ID);
        SecureStorageUtil.removeSecurely(SECURE_KEY_USER_EMAIL);
        cachedUsage = null;
        lastUsageFetch = 0;
    }

    public UsageInfo getCachedUsage() {
        return cachedUsage;
    }

    public boolean isUsageCacheValid() {
        return cachedUsage != null && (System.currentTimeMillis() - lastUsageFetch) < USAGE_CACHE_TTL;
    }

    public void refreshUsage() {
        if (!isConfigured()) {
            return;
        }
        getUsage().thenAccept(usage -> {
            if (usage != null) {
                LOG.debug("Usage refreshed: %s", usage); //$NON-NLS-1$
            }
        });
    }

    public void addUsageListener(UsageChangeListener listener) {
        if (listener != null) {
            usageListeners.add(listener);
        }
    }

    public void removeUsageListener(UsageChangeListener listener) {
        usageListeners.remove(listener);
    }

    public void dispose() {
        usageListeners.clear();
        cachedUsage = null;
        lastUsageFetch = 0;
    }

    private SignupStartResult handleSignupStartResponse(HttpResponse<String> response) {
        JsonObject json = parseJsonObject(response.body());
        if ((response.statusCode() == 200 || response.statusCode() == 201) && isUnifiedSuccess(json)) {
            JsonObject data = getDataObject(json);
            LOG.info("Signup start succeeded: status=%d verificationRequired=%s", //$NON-NLS-1$
                    Integer.valueOf(response.statusCode()),
                    Boolean.valueOf(getJsonBoolean(data, "verification_required", true))); //$NON-NLS-1$
            return SignupStartResult.success(
                    getJsonBoolean(data, "verification_required", true), //$NON-NLS-1$
                    getJsonLong(data, "expires_in_seconds"), //$NON-NLS-1$
                    getJsonLong(data, "resend_available_in_seconds")); //$NON-NLS-1$
        }
        ErrorDetails error = extractErrorDetails(json, response.body(), response.statusCode());
        LOG.warn("Signup start failed: status=%d errorCode=%s message=%s body=%s", //$NON-NLS-1$
                Integer.valueOf(response.statusCode()),
                error.errorCode(),
                error.message(),
                abbreviateForLog(response.body()));
        return SignupStartResult.failure(error.message, error.errorCode, error.retryAfterSeconds);
    }

    private RegistrationResult handlePluginAuthResponse(HttpResponse<String> response) {
        JsonObject json = parseJsonObject(response.body());
        if ((response.statusCode() == 200 || response.statusCode() == 201) && isUnifiedSuccess(json)) {
            JsonObject data = getDataObject(json);
            String apiKey = firstNonEmpty(
                    getJsonString(data, "backendApiKey"), //$NON-NLS-1$
                    getJsonString(data, "backend_api_key"), //$NON-NLS-1$
                    getJsonString(data, "api_key")); //$NON-NLS-1$
            String userId = firstNonEmpty(
                    getJsonString(data, "backendUserId"), //$NON-NLS-1$
                    getJsonString(data, "backend_user_id"), //$NON-NLS-1$
                    getJsonString(data, "user_id")); //$NON-NLS-1$
            String email = getJsonString(data, "email"); //$NON-NLS-1$

            if (apiKey != null && !apiKey.isEmpty() && userId != null && !userId.isEmpty()) {
                storeCredentials(apiKey, userId, email);
                LOG.info("Plugin auth succeeded: status=%d userId=%s email=%s apiKeyLength=%d", //$NON-NLS-1$
                        Integer.valueOf(response.statusCode()),
                        userId,
                        email != null ? email : "", //$NON-NLS-1$
                        Integer.valueOf(apiKey.length()));
                return RegistrationResult.success(apiKey, userId, email, 0);
            }
            LOG.warn("Plugin auth response missing credentials: status=%d body=%s", //$NON-NLS-1$
                    Integer.valueOf(response.statusCode()),
                    abbreviateForLog(response.body()));
            return RegistrationResult.failure("Не удалось получить ключ доступа", "service_unavailable", 0); //$NON-NLS-1$ //$NON-NLS-2$
        }

        ErrorDetails error = extractErrorDetails(json, response.body(), response.statusCode());
        LOG.warn("Plugin auth failed: status=%d errorCode=%s message=%s body=%s", //$NON-NLS-1$
                Integer.valueOf(response.statusCode()),
                error.errorCode(),
                error.message(),
                abbreviateForLog(response.body()));
        return RegistrationResult.failure(error.message, error.errorCode, error.retryAfterSeconds);
    }

    private UsageInfo handleUsageResponse(HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            return cachedUsage;
        }
        try {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            UsageInfo usage = new UsageInfo();
            usage.setSpend(getJsonDouble(json, "spend")); //$NON-NLS-1$
            usage.setMaxBudget(getJsonDouble(json, "max_budget")); //$NON-NLS-1$
            usage.setTotalTokens(getJsonLong(json, "total_tokens")); //$NON-NLS-1$
            usage.setPromptTokens(getJsonLong(json, "prompt_tokens")); //$NON-NLS-1$
            usage.setCompletionTokens(getJsonLong(json, "completion_tokens")); //$NON-NLS-1$
            usage.setBudgetDuration(getJsonString(json, "budget_duration")); //$NON-NLS-1$
            usage.setResetDate(getJsonString(json, "budget_reset_at")); //$NON-NLS-1$
            cachedUsage = usage;
            lastUsageFetch = System.currentTimeMillis();
            notifyUsageListeners(usage);
            return usage;
        } catch (Exception e) {
            LOG.error("Failed to parse usage response", e); //$NON-NLS-1$
            return cachedUsage;
        }
    }

    private RegistrationResult handleRotateKeyResponse(HttpResponse<String> response) {
        if (response.statusCode() == 200 || response.statusCode() == 201) {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            String newApiKey = getJsonString(json, "api_key"); //$NON-NLS-1$
            String userId = getUserId();
            double maxBudget = getJsonDouble(json, "max_budget"); //$NON-NLS-1$
            if (newApiKey != null && !newApiKey.isEmpty()) {
                storeCredentials(newApiKey, userId);
                return RegistrationResult.success(newApiKey, userId, maxBudget);
            }
            return RegistrationResult.failure("No API key in rotation response"); //$NON-NLS-1$
        }
        return RegistrationResult.failure(extractErrorMessage(response.body(), response.statusCode()));
    }

    private void notifyUsageListeners(UsageInfo usage) {
        for (UsageChangeListener listener : usageListeners) {
            try {
                listener.onUsageChanged(usage);
            } catch (Exception e) {
                LOG.warn("Error in usage listener", e); //$NON-NLS-1$
            }
        }
    }

    private static String getJsonString(JsonObject json, String key) {
        if (json != null && json.has(key) && !json.get(key).isJsonNull()) {
            return json.get(key).getAsString();
        }
        return null;
    }

    private static double getJsonDouble(JsonObject json, String key) {
        if (json != null && json.has(key) && !json.get(key).isJsonNull()) {
            return json.get(key).getAsDouble();
        }
        return 0.0;
    }

    private static long getJsonLong(JsonObject json, String key) {
        if (json != null && json.has(key) && !json.get(key).isJsonNull()) {
            return json.get(key).getAsLong();
        }
        return 0L;
    }

    private static boolean getJsonBoolean(JsonObject json, String key, boolean defaultValue) {
        if (json != null && json.has(key) && !json.get(key).isJsonNull()) {
            return json.get(key).getAsBoolean();
        }
        return defaultValue;
    }

    private static JsonObject parseJsonObject(String responseBody) {
        try {
            JsonElement element = JsonParser.parseString(responseBody);
            if (element != null && element.isJsonObject()) {
                return element.getAsJsonObject();
            }
        } catch (Exception e) {
            // ignore malformed bodies
        }
        return new JsonObject();
    }

    private static boolean isUnifiedSuccess(JsonObject json) {
        return json.has("success") && !json.get("success").isJsonNull() //$NON-NLS-1$ //$NON-NLS-2$
                && json.get("success").getAsBoolean();
    }

    private static JsonObject getDataObject(JsonObject json) {
        if (json.has("data") && json.get("data").isJsonObject()) { //$NON-NLS-1$ //$NON-NLS-2$
            return json.getAsJsonObject("data"); //$NON-NLS-1$
        }
        return json;
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private static ErrorDetails extractErrorDetails(JsonObject json, String responseBody, int statusCode) {
        String errorCode = firstNonEmpty(
                getJsonString(json, "error_code"), //$NON-NLS-1$
                getJsonString(json, "code")); //$NON-NLS-1$
        String message = firstNonEmpty(
                getJsonString(json, "message"), //$NON-NLS-1$
                getJsonString(json, "error"), //$NON-NLS-1$
                extractErrorMessage(responseBody, statusCode));
        long retryAfterSeconds = getJsonLong(json, "retry_after"); //$NON-NLS-1$
        if (errorCode == null || errorCode.isEmpty()) {
            errorCode = defaultErrorCode(statusCode);
        }
        return new ErrorDetails(message, errorCode, retryAfterSeconds);
    }

    private static String defaultErrorCode(int statusCode) {
        return switch (statusCode) {
            case 400 -> "bad_request"; //$NON-NLS-1$
            case 401 -> "unauthorized"; //$NON-NLS-1$
            case 403 -> "forbidden"; //$NON-NLS-1$
            case 404 -> "not_found"; //$NON-NLS-1$
            case 409 -> "conflict"; //$NON-NLS-1$
            case 429 -> "rate_limited"; //$NON-NLS-1$
            case 500, 502, 503, 504 -> "service_unavailable"; //$NON-NLS-1$
            default -> "request_failed"; //$NON-NLS-1$
        };
    }

    private static String extractErrorMessage(String responseBody, int statusCode) {
        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
            String message = firstNonEmpty(
                    getJsonString(json, "message"), //$NON-NLS-1$
                    getJsonString(json, "error"), //$NON-NLS-1$
                    getJsonString(json, "detail")); //$NON-NLS-1$
            if (message != null && !message.isEmpty()) {
                return message;
            }
        } catch (Exception e) {
            // ignore malformed bodies
        }
        return "Request failed with status " + statusCode; //$NON-NLS-1$
    }

    private static Throwable unwrapException(Throwable ex) {
        return ex != null && ex.getCause() != null ? ex.getCause() : ex;
    }

    private static String abbreviateForLog(String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        String normalized = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() <= MAX_LOG_BODY_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_LOG_BODY_LENGTH) + "..."; //$NON-NLS-1$
    }

    private record ErrorDetails(String message, String errorCode, long retryAfterSeconds) {
    }
}
