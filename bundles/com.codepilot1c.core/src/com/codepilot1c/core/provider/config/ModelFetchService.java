/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.internal.VibeCorePlugin;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Service for fetching available models from LLM provider APIs.
 *
 * <p>Supports OpenAI-compatible (/models) and Ollama (/api/tags) endpoints.</p>
 */
public class ModelFetchService {

    private static ModelFetchService instance;
    private final HttpClient httpClient;

    private ModelFetchService() {
        this.httpClient = HttpClient.newBuilder()
                // Keep this compatible with plain-HTTP OpenAI-compatible deployments (vLLM/uvicorn),
                // where Java HttpClient HTTP/2 (h2c) can lead to "missing body" validation errors.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Returns the singleton instance.
     */
    public static synchronized ModelFetchService getInstance() {
        if (instance == null) {
            instance = new ModelFetchService();
        }
        return instance;
    }

    /**
     * Represents a model available from a provider.
     */
    public static class ModelInfo {
        private final String id;
        private final String name;
        private final String ownedBy;

        public ModelInfo(String id, String name, String ownedBy) {
            this.id = id;
            this.name = name != null ? name : id;
            this.ownedBy = ownedBy;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getOwnedBy() {
            return ownedBy;
        }

        @Override
        public String toString() {
            if (ownedBy != null && !ownedBy.isEmpty()) {
                return name + " (" + ownedBy + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            return name;
        }
    }

    /**
     * Result of a model fetch operation.
     */
    public static class FetchResult {
        private final List<ModelInfo> models;
        private final String error;
        private final boolean success;
        private final boolean manualModelEntryRequired;

        private FetchResult(List<ModelInfo> models) {
            this.models = models;
            this.error = null;
            this.success = true;
            this.manualModelEntryRequired = false;
        }

        private FetchResult(String error) {
            this(error, false);
        }

        private FetchResult(String error, boolean manualModelEntryRequired) {
            this.models = Collections.emptyList();
            this.error = error;
            this.success = false;
            this.manualModelEntryRequired = manualModelEntryRequired;
        }

        public List<ModelInfo> getModels() {
            return models;
        }

        public String getError() {
            return error;
        }

        public boolean isSuccess() {
            return success;
        }

        public boolean requiresManualModelEntry() {
            return manualModelEntryRequired;
        }

        public static FetchResult success(List<ModelInfo> models) {
            return new FetchResult(models);
        }

        public static FetchResult failure(String error) {
            return new FetchResult(error);
        }

        public static FetchResult manualModelEntryRequired() {
            return new FetchResult(null, true);
        }
    }

    /**
     * Fetches available models from a provider configuration.
     *
     * @param baseUrl the API base URL
     * @param apiKey the API key (may be null for local providers)
     * @param type the provider type
     * @return a future with the fetch result
     */
    public CompletableFuture<FetchResult> fetchModels(String baseUrl, String apiKey, ProviderType type) {
        if (!type.supportsModelListing()) {
            return CompletableFuture.completedFuture(
                    FetchResult.failure("This provider type does not support model listing")); //$NON-NLS-1$
        }

        String url = normalizeUrl(baseUrl) + type.getModelsEndpoint();

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET();

        // Add authorization if API key provided
        if (apiKey != null && !apiKey.isEmpty()) {
            builder.header("Authorization", "Bearer " + apiKey); //$NON-NLS-1$ //$NON-NLS-2$
        }

        HttpRequest request = builder.build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> parseModelsResponse(response, type))
                .exceptionally(e -> {
                    VibeCorePlugin.logWarn("Failed to fetch models: " + e.getMessage()); //$NON-NLS-1$
                    return FetchResult.failure(e.getMessage());
                });
    }

    /**
     * Fetches models using a provider configuration.
     */
    public CompletableFuture<FetchResult> fetchModels(LlmProviderConfig config) {
        return fetchModels(config.getBaseUrl(), config.getApiKey(), config.getType());
    }

    /**
     * Parses the API response based on provider type.
     */
    private FetchResult parseModelsResponse(HttpResponse<String> response, ProviderType type) {
        if (response.statusCode() != 200) {
            if (type == ProviderType.OPENAI_COMPATIBLE && response.statusCode() == 404) {
                return FetchResult.manualModelEntryRequired();
            }
            return FetchResult.failure("API error: " + response.statusCode()); //$NON-NLS-1$
        }

        try {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();

            switch (type) {
                case OLLAMA:
                    return parseOllamaModels(json);
                case OPENAI_COMPATIBLE:
                default:
                    return parseOpenAiModels(json);
            }
        } catch (Exception e) {
            VibeCorePlugin.logWarn("Failed to parse models response: " + e.getMessage()); //$NON-NLS-1$
            return FetchResult.failure("Failed to parse response: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Parses OpenAI-compatible /models response.
     *
     * Response format:
     * {
     *   "data": [
     *     { "id": "gpt-4o", "object": "model", "owned_by": "openai" },
     *     ...
     *   ]
     * }
     */
    private FetchResult parseOpenAiModels(JsonObject json) {
        List<ModelInfo> models = new ArrayList<>();

        JsonArray data = json.getAsJsonArray("data"); //$NON-NLS-1$
        if (data == null) {
            return FetchResult.failure("No 'data' array in response"); //$NON-NLS-1$
        }

        for (JsonElement element : data) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject model = element.getAsJsonObject();
            String id = model.has("id") ? model.get("id").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
            if (id == null || id.isEmpty()) {
                continue;
            }

            String ownedBy = model.has("owned_by") ? model.get("owned_by").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$

            // Filter to only include chat/completion models (optional heuristic)
            // Some APIs return embedding models too
            models.add(new ModelInfo(id, id, ownedBy));
        }

        // Sort by ID
        models.sort((a, b) -> a.getId().compareToIgnoreCase(b.getId()));

        return FetchResult.success(models);
    }

    /**
     * Parses Ollama /api/tags response.
     *
     * Response format:
     * {
     *   "models": [
     *     { "name": "llama3.2:latest", "model": "llama3.2:latest", "size": 123456 },
     *     ...
     *   ]
     * }
     */
    private FetchResult parseOllamaModels(JsonObject json) {
        List<ModelInfo> models = new ArrayList<>();

        JsonArray modelsArray = json.getAsJsonArray("models"); //$NON-NLS-1$
        if (modelsArray == null) {
            return FetchResult.failure("No 'models' array in response"); //$NON-NLS-1$
        }

        for (JsonElement element : modelsArray) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject model = element.getAsJsonObject();
            String name = model.has("name") ? model.get("name").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$
            if (name == null || name.isEmpty()) {
                continue;
            }

            // For Ollama, name and model are the same
            models.add(new ModelInfo(name, name, "local")); //$NON-NLS-1$
        }

        // Sort by name
        models.sort((a, b) -> a.getId().compareToIgnoreCase(b.getId()));

        return FetchResult.success(models);
    }

    /**
     * Normalizes the URL by removing trailing slashes.
     */
    private String normalizeUrl(String url) {
        if (url == null) {
            return ""; //$NON-NLS-1$
        }
        url = url.trim();
        while (url.endsWith("/")) { //$NON-NLS-1$
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
