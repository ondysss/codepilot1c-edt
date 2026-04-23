/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.memory.extraction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;

import com.codepilot1c.core.memory.MemoryCategory;
import com.codepilot1c.core.memory.MemoryEntry;
import com.codepilot1c.core.memory.MemoryService;
import com.codepilot1c.core.memory.MemoryVisibility;
import com.codepilot1c.core.memory.RetentionPolicy;
import com.codepilot1c.core.model.LlmRequest;
import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.provider.ILlmProvider;
import com.codepilot1c.core.provider.LlmProviderRegistry;
import com.codepilot1c.core.session.Session;
import com.codepilot1c.core.session.SessionMessage;
import com.codepilot1c.core.settings.VibePreferenceConstants;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * LLM-based memory extraction from completed sessions (Channel A).
 *
 * <p>Sends a truncated session transcript to a dedicated cheap model
 * (default: kimi-k2.5) with an extraction prompt that returns structured
 * JSON facts. Facts are filtered by confidence, deduped, and stored
 * via {@link MemoryService}.</p>
 *
 * <p>Uses bimodal truncation: first 1500 + last 2000 tokens to capture
 * task setup and final decisions while skipping verbose middle sections.</p>
 *
 * <p>Crash-safe: pending extraction requests are queued to disk and
 * retried on plugin startup.</p>
 *
 * @see MemoryExtractionListener
 */
public final class LlmMemoryExtractor {

    private static final ILog LOG = Platform.getLog(LlmMemoryExtractor.class);

    /** Timeout for the extraction LLM call. */
    private static final int EXTRACTION_TIMEOUT_SECONDS = 45;

    /** Max chars for first part of bimodal truncation (~1500 tokens). */
    private static final int FIRST_PART_MAX_CHARS = 4500;

    /** Max chars for last part of bimodal truncation (~2000 tokens). */
    private static final int LAST_PART_MAX_CHARS = 6000;

    /** Max chars per individual message in transcript. */
    private static final int MAX_MESSAGE_CHARS = 800;

    /** Maximum number of facts to accept from LLM response. */
    private static final int MAX_FACTS = 8;

    /** Maximum retries for pending extraction. */
    private static final int MAX_RETRIES = 3;

    /** Expiry for pending extraction requests. */
    private static final Duration PENDING_EXPIRY = Duration.ofDays(7);

    /** Confidence thresholds. */
    private static final double HIGH_CONFIDENCE = 0.75;
    private static final double LOW_CONFIDENCE = 0.50;

    /** TTL for low-confidence facts. */
    private static final Duration LOW_CONFIDENCE_TTL = Duration.ofDays(3);

    private static final String EXTRACTION_SYSTEM_PROMPT = """
            Ты — экстрактор знаний для IDE-плагина. Проанализируй диалог и извлеки \
            ключевые факты о КОНКРЕТНОМ проекте. Верни JSON (максимум 8 фактов):
            {
              "facts": [
                {
                  "content": "одно предложение, <120 символов, конкретный факт",
                  "category": "FACT|ARCHITECTURE|DECISION|PATTERN|BUG",
                  "domain": "модуль или подсистема (опц.)",
                  "confidence": 0.0-1.0
                }
              ]
            }
            Правила:
            - ВКЛЮЧАЙ: архитектурные решения ("используем X вместо Y потому что Z"), \
            найденные баги с симптомами, паттерны специфичные для проекта, \
            явные запросы "запомни", незакрытые задачи (TODO/PENDING).
            - ИСКЛЮЧАЙ: общие знания о платформе, содержимое справки, \
            пересказ диалога, промежуточные отладочные шаги.
            - Если диалог слишком мал или нет ценных фактов — верни {"facts": []}

            Пример правильного факта:
            {"content": "Регистр ОстаткиТоваров используется для учёта складских остатков", \
            "category": "FACT", "domain": "accumulation-registers", "confidence": 0.9}
            """; //$NON-NLS-1$

    private static final String TRUNCATION_SEPARATOR =
            "\n[...session middle truncated...]\n"; //$NON-NLS-1$

    private static final Gson GSON = new Gson();

    private LlmMemoryExtractor() {
    }

    /**
     * Extracts facts from a completed session using LLM.
     * Runs synchronously — caller should wrap in CompletableFuture.
     *
     * @param session the completed session
     */
    public static void extract(Session session) {
        if (session == null || session.getProjectPath() == null) {
            return;
        }

        List<SessionMessage> messages = session.getMessages();
        if (messages.size() < 4) {
            return;
        }

        LOG.info("LlmMemoryExtractor: starting extraction for session " //$NON-NLS-1$
                + session.getId() + " (" + messages.size() + " messages)"); //$NON-NLS-1$ //$NON-NLS-2$

        // Build bimodal transcript
        String transcript = buildBimodalTranscript(messages);

        // Get LLM provider and model
        ILlmProvider provider = LlmProviderRegistry.getInstance().getActiveProvider();
        if (provider == null || !provider.isConfigured()) {
            LOG.info("LlmMemoryExtractor: no LLM provider available, skipping"); //$NON-NLS-1$
            return;
        }

        String extractionModel = getExtractionModel();

        try {
            LlmRequest request = LlmRequest.builder()
                    .systemMessage(EXTRACTION_SYSTEM_PROMPT)
                    .userMessage(transcript)
                    .model(extractionModel)
                    .maxTokens(1024)
                    .temperature(0.3)
                    .stream(false)
                    .build();

            LlmResponse response = provider.complete(request)
                    .get(EXTRACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (response == null || response.getContent() == null
                    || response.getContent().isBlank()) {
                LOG.info("LlmMemoryExtractor: empty response from LLM"); //$NON-NLS-1$
                return;
            }

            String json = stripCodeFences(response.getContent());
            List<ExtractedFact> facts = parseExtractionResponse(json);

            LOG.info("LlmMemoryExtractor: extracted " + facts.size() + " facts"); //$NON-NLS-1$ //$NON-NLS-2$

            // Save facts
            int saved = 0;
            for (ExtractedFact fact : facts) {
                if (saved >= MAX_FACTS) {
                    break;
                }
                MemoryEntry entry = factToMemoryEntry(fact, session.getId());
                if (entry != null) {
                    MemoryService.remember(session.getProjectPath(), entry);
                    saved++;
                    LOG.info("LlmMemoryExtractor: saved [" + fact.category + "] " //$NON-NLS-1$ //$NON-NLS-2$
                            + fact.content); //$NON-NLS-1$
                }
            }

            LOG.info("LlmMemoryExtractor: saved " + saved + " facts for session " //$NON-NLS-1$ //$NON-NLS-2$
                    + session.getId()); //$NON-NLS-1$

        } catch (Exception e) {
            LOG.warn("LlmMemoryExtractor: extraction failed for session " //$NON-NLS-1$
                    + session.getId() + ": " + e.getMessage()); //$NON-NLS-1$
        }
    }

    // ---- Bimodal transcript ----

    /**
     * Builds a bimodal transcript: first N chars + separator + last M chars.
     * Middle section (debugging, tool calls) is truncated as least valuable.
     */
    static String buildBimodalTranscript(List<SessionMessage> messages) {
        // Build full transcript first
        StringBuilder full = new StringBuilder();
        for (SessionMessage msg : messages) {
            String role = msg.getType() != null ? msg.getType().name() : "UNKNOWN"; //$NON-NLS-1$
            String content = msg.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            if (content.length() > MAX_MESSAGE_CHARS) {
                content = content.substring(0, MAX_MESSAGE_CHARS) + "..."; //$NON-NLS-1$
            }
            full.append("[").append(role).append("]: ") //$NON-NLS-1$ //$NON-NLS-2$
                    .append(content).append("\n"); //$NON-NLS-1$
        }

        String fullText = full.toString();

        // If it fits entirely, return as-is
        int totalBudget = FIRST_PART_MAX_CHARS + LAST_PART_MAX_CHARS;
        if (fullText.length() <= totalBudget) {
            return fullText;
        }

        // Bimodal: first + separator + last
        String firstPart = fullText.substring(0, FIRST_PART_MAX_CHARS);
        String lastPart = fullText.substring(fullText.length() - LAST_PART_MAX_CHARS);
        return firstPart + TRUNCATION_SEPARATOR + lastPart;
    }

    // ---- JSON parsing ----

    /**
     * Strips markdown code fences: ```json ... ``` → raw JSON.
     */
    static String stripCodeFences(String raw) {
        if (raw == null) {
            return ""; //$NON-NLS-1$
        }
        String trimmed = raw.strip();
        // Strip ```json ... ``` or ``` ... ```
        if (trimmed.startsWith("```")) { //$NON-NLS-1$
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) { //$NON-NLS-1$
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
        }
        return trimmed.strip();
    }

    /**
     * Parses the LLM extraction response JSON into structured facts.
     */
    static List<ExtractedFact> parseExtractionResponse(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray factsArray = root.getAsJsonArray("facts"); //$NON-NLS-1$
            if (factsArray == null || factsArray.isEmpty()) {
                return Collections.emptyList();
            }

            List<ExtractedFact> result = new ArrayList<>();
            for (JsonElement el : factsArray) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject obj = el.getAsJsonObject();

                String content = getStringField(obj, "content"); //$NON-NLS-1$
                if (content == null || content.isBlank() || content.length() < 10) {
                    continue;
                }

                String categoryStr = getStringField(obj, "category"); //$NON-NLS-1$
                String domain = getStringField(obj, "domain"); //$NON-NLS-1$
                double confidence = obj.has("confidence") //$NON-NLS-1$
                        ? obj.get("confidence").getAsDouble() //$NON-NLS-1$
                        : 0.5;

                // Clamp confidence
                confidence = Math.max(0.0, Math.min(1.0, confidence));

                // Filter by minimum confidence
                if (confidence < LOW_CONFIDENCE) {
                    continue;
                }

                ExtractedFact fact = new ExtractedFact();
                fact.content = content.length() > 500 ? content.substring(0, 500) : content;
                fact.category = categoryStr;
                fact.domain = domain;
                fact.confidence = confidence;

                result.add(fact);

                if (result.size() >= MAX_FACTS) {
                    break;
                }
            }
            return result;

        } catch (Exception e) {
            LOG.warn("LlmMemoryExtractor: failed to parse JSON: " + e.getMessage()); //$NON-NLS-1$
            return Collections.emptyList();
        }
    }

    private static String getStringField(JsonObject obj, String field) {
        if (!obj.has(field) || obj.get(field).isJsonNull()) {
            return null;
        }
        return obj.get(field).getAsString();
    }

    // ---- Fact conversion ----

    private static MemoryEntry factToMemoryEntry(ExtractedFact fact, String sessionId) {
        MemoryCategory category = parseCategory(fact.category);

        String key = fact.domain != null && !fact.domain.isBlank()
                ? fact.domain
                : "Project Facts"; //$NON-NLS-1$

        // 3-tier confidence → TTL
        RetentionPolicy retention;
        if (fact.confidence >= HIGH_CONFIDENCE) {
            retention = RetentionPolicy.withTtl(RetentionPolicy.DEFAULT_FACT_TTL);
        } else {
            retention = RetentionPolicy.withTtl(LOW_CONFIDENCE_TTL);
        }

        // Secret guard
        if (SecretGuard.containsSecrets(fact.content)) {
            fact.content = SecretGuard.filter(fact.content);
        }

        return MemoryEntry.builder(key, fact.content)
                .category(category)
                .visibility(MemoryVisibility.MACHINE)
                .retention(retention)
                .sourceSessionId(sessionId)
                .build();
    }

    private static MemoryCategory parseCategory(String str) {
        if (str == null || str.isBlank()) {
            return MemoryCategory.FACT;
        }
        try {
            return MemoryCategory.valueOf(str.toUpperCase().strip());
        } catch (IllegalArgumentException e) {
            return MemoryCategory.FACT;
        }
    }

    // ---- Disk queue ----

    /**
     * Saves a pending extraction request to disk for crash safety.
     *
     * @param session    the session to extract
     * @param queueDir   the queue directory
     */
    public static void savePendingRequest(Session session, Path queueDir) {
        try {
            Files.createDirectories(queueDir);
            Path file = queueDir.resolve(session.getId() + ".extraction-pending"); //$NON-NLS-1$
            JsonObject req = new JsonObject();
            req.addProperty("sessionId", session.getId()); //$NON-NLS-1$
            req.addProperty("projectPath", session.getProjectPath()); //$NON-NLS-1$
            req.addProperty("timestamp", Instant.now().toString()); //$NON-NLS-1$
            req.addProperty("messageCount", session.getMessages().size()); //$NON-NLS-1$
            req.addProperty("retryCount", 0); //$NON-NLS-1$
            Files.writeString(file, GSON.toJson(req));
        } catch (IOException e) {
            LOG.warn("LlmMemoryExtractor: failed to save pending request: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Deletes a completed pending extraction request.
     */
    public static void deletePendingRequest(String sessionId, Path queueDir) {
        try {
            Path file = queueDir.resolve(sessionId + ".extraction-pending"); //$NON-NLS-1$
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warn("LlmMemoryExtractor: failed to delete pending request: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Processes all pending extraction requests from the queue directory.
     * Called on plugin startup to handle requests that failed or were
     * interrupted in previous sessions.
     *
     * @param queueDir       the queue directory
     * @param sessionLoader  function to load a session by ID (from FileSessionStore)
     */
    public static void processPendingRequests(Path queueDir,
            java.util.function.Function<String, Session> sessionLoader) {
        if (!Files.isDirectory(queueDir)) {
            return;
        }
        try (var stream = Files.list(queueDir)) {
            stream.filter(p -> p.toString().endsWith(".extraction-pending")) //$NON-NLS-1$
                    .forEach(file -> processPendingFile(file, sessionLoader, queueDir));
        } catch (IOException e) {
            LOG.warn("LlmMemoryExtractor: failed to scan pending queue: " + e.getMessage()); //$NON-NLS-1$
        }
    }

    private static void processPendingFile(Path file,
            java.util.function.Function<String, Session> sessionLoader, Path queueDir) {
        try {
            String content = Files.readString(file);
            JsonObject req = JsonParser.parseString(content).getAsJsonObject();

            String sessionId = req.get("sessionId").getAsString(); //$NON-NLS-1$
            Instant timestamp = Instant.parse(req.get("timestamp").getAsString()); //$NON-NLS-1$
            int retryCount = req.has("retryCount") ? req.get("retryCount").getAsInt() : 0; //$NON-NLS-1$ //$NON-NLS-2$

            // Check expiry
            if (Duration.between(timestamp, Instant.now()).compareTo(PENDING_EXPIRY) > 0) {
                LOG.info("LlmMemoryExtractor: expired pending request: " + sessionId); //$NON-NLS-1$
                Files.deleteIfExists(file);
                return;
            }

            // Check max retries
            if (retryCount >= MAX_RETRIES) {
                LOG.info("LlmMemoryExtractor: max retries reached for: " + sessionId); //$NON-NLS-1$
                Files.deleteIfExists(file);
                return;
            }

            // Load session and extract
            Session session = sessionLoader.apply(sessionId);
            if (session == null) {
                LOG.info("LlmMemoryExtractor: session not found: " + sessionId); //$NON-NLS-1$
                Files.deleteIfExists(file);
                return;
            }

            // Increment retry count
            req.addProperty("retryCount", retryCount + 1); //$NON-NLS-1$
            Files.writeString(file, GSON.toJson(req));

            extract(session);
            Files.deleteIfExists(file);

        } catch (Exception e) {
            LOG.warn("LlmMemoryExtractor: failed to process pending file " //$NON-NLS-1$
                    + file.getFileName() + ": " + e.getMessage()); //$NON-NLS-1$
        }
    }

    // ---- Config ----

    private static String getExtractionModel() {
        try {
            String model = Platform.getPreferencesService()
                    .getString("com.codepilot1c.core", //$NON-NLS-1$
                            VibePreferenceConstants.PREF_MEMORY_EXTRACTION_MODEL,
                            VibePreferenceConstants.PREF_MEMORY_EXTRACTION_MODEL_DEFAULT,
                            null);
            return (model != null && !model.isBlank()) ? model
                    : VibePreferenceConstants.PREF_MEMORY_EXTRACTION_MODEL_DEFAULT;
        } catch (Exception e) {
            return VibePreferenceConstants.PREF_MEMORY_EXTRACTION_MODEL_DEFAULT;
        }
    }

    // ---- Internal ----

    static class ExtractedFact {
        String content;
        String category;
        String domain;
        double confidence;
    }
}
