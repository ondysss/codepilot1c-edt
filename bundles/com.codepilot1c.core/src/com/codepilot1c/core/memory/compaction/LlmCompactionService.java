/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.memory.compaction;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.Platform;

import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.model.LlmRequest;
import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.provider.ILlmProvider;
import com.codepilot1c.core.provider.LlmProviderRegistry;
import com.codepilot1c.core.settings.VibePreferenceConstants;

/**
 * LLM-based conversation history compaction.
 *
 * <p>When enabled via feature flag, replaces lossy truncation-based history
 * compaction with an LLM summarization pass. The summary preserves key context
 * (decisions, code references, tool results) while reducing token count.</p>
 *
 * <p><b>Behind feature flag:</b> {@code codepilot.feature.llm_compaction}.
 * When disabled, falls back to the existing truncation-based approach.</p>
 */
public final class LlmCompactionService {

    private static final LlmCompactionService INSTANCE = new LlmCompactionService();

    /** Timeout for the compaction LLM call in seconds. */
    private static final int COMPACTION_TIMEOUT_SECONDS = 30;

    /** Maximum characters per message in the compaction transcript. */
    private static final int MAX_MESSAGE_CHARS = 500;

    /** Truncation suffix. */
    private static final String ELLIPSIS = "..."; //$NON-NLS-1$

    private LlmCompactionService() {
        // singleton
    }

    public static LlmCompactionService getInstance() {
        return INSTANCE;
    }

    /**
     * Returns {@code true} if LLM compaction feature is enabled.
     */
    public boolean isEnabled() {
        try {
            return Platform.getPreferencesService()
                    .getBoolean("com.codepilot1c.core", //$NON-NLS-1$
                            VibePreferenceConstants.PREF_ENABLE_LLM_COMPACTION,
                            false, null);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Compacts a conversation history using LLM summarization.
     *
     * @param messages  the conversation messages to compact
     * @param maxTokens target token budget for the compacted result
     * @return compacted summary, or {@code null} if compaction is not possible
     */
    public String compact(List<LlmMessage> messages, int maxTokens) {
        if (!isEnabled() || messages == null || messages.isEmpty()) {
            return null;
        }

        String transcript = buildTranscript(messages);
        String compactionPrompt = buildCompactionPrompt(transcript, maxTokens);

        try {
            ILlmProvider provider = LlmProviderRegistry.getInstance().getActiveProvider();
            if (provider == null || !provider.isConfigured()) {
                return null;
            }

            LlmRequest request = LlmRequest.builder()
                    .systemMessage("You are a conversation compactor. Produce a concise summary.") //$NON-NLS-1$
                    .userMessage(compactionPrompt)
                    .build();

            LlmResponse response = provider.complete(request)
                    .get(COMPACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (response != null && response.getContent() != null && !response.getContent().isBlank()) {
                return response.getContent().strip();
            }
        } catch (Exception e) {
            // Fall back to null — caller uses truncation instead
            Platform.getLog(LlmCompactionService.class)
                    .warn("LLM compaction failed, falling back to truncation", e); //$NON-NLS-1$
        }

        return null;
    }

    private String buildTranscript(List<LlmMessage> messages) {
        StringBuilder transcript = new StringBuilder();
        for (LlmMessage msg : messages) {
            String role = msg.getRole() != null ? msg.getRole().name() : "UNKNOWN"; //$NON-NLS-1$
            transcript.append("[").append(role).append("]: "); //$NON-NLS-1$ //$NON-NLS-2$
            String text = msg.getContent();
            if (text != null) {
                if (text.length() > MAX_MESSAGE_CHARS) {
                    text = text.substring(0, MAX_MESSAGE_CHARS - ELLIPSIS.length()) + ELLIPSIS;
                }
                transcript.append(text);
            }
            transcript.append("\n"); //$NON-NLS-1$
        }
        return transcript.toString();
    }

    private String buildCompactionPrompt(String transcript, int maxTokens) {
        return String.format("""
                Summarize the following conversation in under %d tokens.
                Preserve: key decisions, code file references, tool call results, pending tasks.
                Omit: greetings, repeated context, verbose tool outputs.
                Format as a structured summary with bullet points.

                Conversation:
                %s
                """, maxTokens, transcript);
    }
}
