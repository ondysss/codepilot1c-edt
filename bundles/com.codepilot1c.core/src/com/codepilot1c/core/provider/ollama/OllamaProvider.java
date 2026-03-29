/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider.ollama;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.codepilot1c.core.model.LlmAttachment;
import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.model.LlmRequest;
import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.model.LlmStreamChunk;
import com.codepilot1c.core.provider.AbstractLlmProvider;
import com.codepilot1c.core.provider.LlmProviderException;
import com.codepilot1c.core.provider.ProviderCapabilities;
import com.codepilot1c.core.settings.VibePreferenceConstants;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * LLM provider implementation for Ollama (local models).
 */
public class OllamaProvider extends AbstractLlmProvider {

    @Override
    public String getId() {
        return "ollama"; //$NON-NLS-1$
    }

    @Override
    public String getDisplayName() {
        return "Ollama (Local)"; //$NON-NLS-1$
    }

    @Override
    public boolean isConfigured() {
        // Ollama doesn't require API key, just check if URL is set
        String apiUrl = getApiUrl();
        return apiUrl != null && !apiUrl.isEmpty();
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public ProviderCapabilities getCapabilities() {
        return ProviderCapabilities.builder()
                .imageInput(true)
                .documentInput(true)
                .attachmentMetadata(true)
                .maxAttachmentBytes(10L * 1024L * 1024L)
                .maxAttachmentsPerMessage(5)
                .build();
    }

    private String getApiUrl() {
        return getPreferences().get(VibePreferenceConstants.PREF_OLLAMA_API_URL,
                "http://localhost:11434"); //$NON-NLS-1$
    }

    private String getModel() {
        String model = getPreferences().get(VibePreferenceConstants.PREF_OLLAMA_MODEL, ""); //$NON-NLS-1$
        // Return fallback if preference is empty (not configured)
        return model.isEmpty() ? "llama3.2" : model; //$NON-NLS-1$
    }

    @Override
    public CompletableFuture<LlmResponse> complete(LlmRequest request) {
        resetCancelled();

        String requestBody = buildRequestBody(request, false);
        HttpRequest httpRequest = createPostRequest(getApiUrl() + "/api/chat") //$NON-NLS-1$
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        return sendAsync(httpRequest).thenApply(this::parseResponse);
    }

    @Override
    public void streamComplete(LlmRequest request, Consumer<LlmStreamChunk> consumer) {
        resetCancelled();

        String requestBody = buildRequestBody(request, true);
        HttpRequest httpRequest = createPostRequest(getApiUrl() + "/api/chat") //$NON-NLS-1$
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        final LlmProviderException[] error = { null };

        sendAsyncStreaming(
                httpRequest,
                (line, complete) -> processStreamLine(line, consumer, complete),
                ex -> {
                    if (ex instanceof LlmProviderException) {
                        error[0] = (LlmProviderException) ex;
                    } else {
                        error[0] = new LlmProviderException("Failed to stream from Ollama", ex); //$NON-NLS-1$
                    }
                    consumer.accept(LlmStreamChunk.error(error[0].getMessage()));
                }
        ).join(); // Block here to maintain method contract, but streaming happens async

        if (error[0] != null) {
            throw error[0];
        }
    }

    private void processStreamLine(String line, Consumer<LlmStreamChunk> consumer, Runnable complete) {
        if (line.trim().isEmpty()) {
            return;
        }

        try {
            JsonObject event = JsonParser.parseString(line).getAsJsonObject();

            if (event.has("message")) { //$NON-NLS-1$
                JsonObject message = event.getAsJsonObject("message"); //$NON-NLS-1$
                if (message.has("content")) { //$NON-NLS-1$
                    String content = message.get("content").getAsString(); //$NON-NLS-1$
                    consumer.accept(LlmStreamChunk.content(content));
                }
            }

            if (event.has("done") && event.get("done").getAsBoolean()) { //$NON-NLS-1$ //$NON-NLS-2$
                String reason = event.has("done_reason") //$NON-NLS-1$
                        ? event.get("done_reason").getAsString() : "stop"; //$NON-NLS-1$ //$NON-NLS-2$
                consumer.accept(LlmStreamChunk.complete(reason));
                complete.run();
            }
        } catch (Exception e) {
            // Skip malformed lines
        }
    }

    private String buildRequestBody(LlmRequest request, boolean stream) {
        JsonObject body = new JsonObject();

        String model = request.getModel() != null ? request.getModel() : getModel();
        body.addProperty("model", model); //$NON-NLS-1$
        body.addProperty("stream", stream); //$NON-NLS-1$

        JsonArray messages = new JsonArray();
        for (LlmMessage msg : request.getMessages()) {
            JsonObject msgObj = new JsonObject();
            msgObj.addProperty("role", msg.getRole().getValue()); //$NON-NLS-1$
            msgObj.addProperty("content", msg.hasContentParts()
                    ? msg.getTextualContentFallback()
                    : msg.getContent() != null ? msg.getContent() : ""); //$NON-NLS-1$ //$NON-NLS-2$
            JsonArray images = buildImages(msg);
            if (images.size() > 0) {
                msgObj.add("images", images); //$NON-NLS-1$
            }
            messages.add(msgObj);
        }
        body.add("messages", messages); //$NON-NLS-1$

        // Options
        JsonObject options = new JsonObject();
        if (request.getMaxTokens() > 0) {
            options.addProperty("num_predict", request.getMaxTokens()); //$NON-NLS-1$
        }
        options.addProperty("temperature", request.getTemperature()); //$NON-NLS-1$
        body.add("options", options); //$NON-NLS-1$

        return gson.toJson(body);
    }

    private JsonArray buildImages(LlmMessage message) {
        JsonArray images = new JsonArray();
        for (LlmAttachment attachment : message.getAttachments()) {
            if (!attachment.isImage()) {
                continue;
            }
            String encoded = encodeAttachmentBase64(attachment);
            if (encoded != null) {
                images.add(encoded);
            }
        }
        return images;
    }

    private String encodeAttachmentBase64(LlmAttachment attachment) {
        String effectivePath = attachment.getEffectivePath();
        if (effectivePath == null || effectivePath.isBlank()) {
            return null;
        }
        try {
            return Base64.getEncoder().encodeToString(Files.readAllBytes(Path.of(effectivePath)));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private LlmResponse parseResponse(HttpResponse<String> response) {
        if (!isSuccess(response.statusCode())) {
            throw parseError(response);
        }

        try {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();

            JsonObject message = json.getAsJsonObject("message"); //$NON-NLS-1$
            String content = message.get("content").getAsString(); //$NON-NLS-1$

            String model = json.has("model") ? json.get("model").getAsString() : null; //$NON-NLS-1$ //$NON-NLS-2$

            String doneReason = null;
            if (json.has("done_reason")) { //$NON-NLS-1$
                doneReason = json.get("done_reason").getAsString(); //$NON-NLS-1$
            }

            // Ollama provides token counts in different fields
            LlmResponse.Usage usage = null;
            if (json.has("prompt_eval_count") && json.has("eval_count")) { //$NON-NLS-1$ //$NON-NLS-2$
                int promptTokens = json.get("prompt_eval_count").getAsInt(); //$NON-NLS-1$
                int completionTokens = json.get("eval_count").getAsInt(); //$NON-NLS-1$
                usage = new LlmResponse.Usage(promptTokens, completionTokens,
                        promptTokens + completionTokens);
            }

            return new LlmResponse(content, model, usage, doneReason);
        } catch (Exception e) {
            throw new LlmProviderException("Failed to parse Ollama response", e); //$NON-NLS-1$
        }
    }

    private LlmProviderException parseError(HttpResponse<String> response) {
        try {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            if (json.has("error")) { //$NON-NLS-1$
                String message = json.get("error").getAsString(); //$NON-NLS-1$
                return new LlmProviderException(message, null, response.statusCode(), null);
            }
        } catch (Exception ignored) {
            // Fall through to default error
        }
        return new LlmProviderException(
                "Ollama API error: " + response.statusCode(), null, response.statusCode(), null); //$NON-NLS-1$
    }
}
