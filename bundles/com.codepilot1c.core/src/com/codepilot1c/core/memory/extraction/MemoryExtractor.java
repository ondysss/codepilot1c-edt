/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.memory.extraction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;

import com.codepilot1c.core.memory.MemoryCategory;
import com.codepilot1c.core.memory.MemoryEntry;
import com.codepilot1c.core.memory.MemoryService;
import com.codepilot1c.core.memory.MemoryVisibility;
import com.codepilot1c.core.memory.RetentionPolicy;
import com.codepilot1c.core.model.LlmContentPart;
import com.codepilot1c.core.session.Session;
import com.codepilot1c.core.session.SessionMessage;

/**
 * Extracts PENDING tasks and facts from completed sessions.
 *
 * <p>This is a rule-based extractor (no LLM needed) that looks for:</p>
 * <ul>
 *   <li>Explicit TODO/FIXME markers in assistant messages</li>
 *   <li>Unfinished task mentions</li>
 *   <li>Key decisions and findings</li>
 *   <li>User-requested facts ("Запомни/Remember" → "Запомнил/Noted")</li>
 *   <li>Project-specific facts from user messages</li>
 * </ul>
 *
 * <p>All extracted entries are written with {@link MemoryVisibility#MACHINE}
 * to {@code .auto-memory.md} only.</p>
 *
 * <p>Future versions may use a cheap LLM for more sophisticated extraction.</p>
 */
public final class MemoryExtractor {

    private static final ILog LOG = Platform.getLog(MemoryExtractor.class);

    // Patterns for PENDING task detection
    private static final Pattern TODO_PATTERN =
            Pattern.compile("(?m)^\\s*[-*]\\s*(?:TODO|FIXME|PENDING|\\u2753)\\s*:?\\s*(.+)$", Pattern.CASE_INSENSITIVE); //$NON-NLS-1$

    // Pattern for Russian task markers
    private static final Pattern RU_TASK_PATTERN =
            Pattern.compile("(?m)^\\s*[-*]\\s*(?:\u0417\u0430\u0434\u0430\u0447\u0430|\u0421\u0434\u0435\u043B\u0430\u0442\u044C|\u0414\u043E\u043F\u0438\u0441\u0430\u0442\u044C|\u0418\u0441\u043F\u0440\u0430\u0432\u0438\u0442\u044C)\\s*:?\\s*(.+)$"); // Задача|Сделать|Дописать|Исправить //$NON-NLS-1$

    // Patterns for architecture decisions and code patterns
    private static final Pattern DECISION_PATTERN =
            Pattern.compile("(?mi)^\\s*[-*]\\s*(?:Decision|Decided|Architecture|We decided|We chose|Решение|Решили|Архитектура|Выбрали)\\s*:?\\s*(.+)$"); //$NON-NLS-1$

    private static final Pattern PATTERN_PATTERN =
            Pattern.compile("(?mi)^\\s*[-*]\\s*(?:Pattern|Anti-pattern|Convention|Rule|Паттерн|Антипаттерн|Конвенция|Правило)\\s*:?\\s*(.+)$"); //$NON-NLS-1$

    // REMOVED: REMEMBERED_PATTERN and USER_REMEMBER_PATTERN
    // User-requested facts are now handled by RememberFactTool (Channel B)
    // and LLM-based extraction (Channel A) — see MEMORY_ARCHITECTURE_PLAN.md

    private MemoryExtractor() {
    }

    /**
     * Extracts memory entries from a completed session.
     *
     * @param session the completed session
     */
    public static void extract(Session session) {
        if (session == null || session.getProjectPath() == null) {
            return;
        }

        List<MemoryEntry> extracted = new ArrayList<>();
        List<SessionMessage> messages = session.getMessages();

        LOG.info("MemoryExtractor: processing session " + session.getId() //$NON-NLS-1$
                + " with " + messages.size() + " messages, projectPath=" //$NON-NLS-1$ //$NON-NLS-2$
                + session.getProjectPath()); //$NON-NLS-1$

        // Extract from assistant messages
        for (int i = 0; i < messages.size(); i++) {
            SessionMessage message = messages.get(i);
            if (message.getType() != SessionMessage.MessageType.ASSISTANT) {
                continue;
            }
            String content = message.getContent();
            // Fallback: reconstruct content from contentParts if content is blank
            if ((content == null || content.isBlank()) && message.getContentParts() != null) {
                StringBuilder sb = new StringBuilder();
                for (var part : message.getContentParts()) {
                    if (part.getText() != null) {
                        sb.append(part.getText());
                    }
                }
                content = sb.toString();
            }
            if (content == null || content.isBlank()) {
                LOG.info("MemoryExtractor: skipping message " + i + " - blank content"); //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            }

            LOG.info("MemoryExtractor: processing assistant message " + i //$NON-NLS-1$
                    + ", content length=" + content.length()); //$NON-NLS-1$

            // Extract PENDING tasks (TODO/FIXME markers)
            extractTasks(content, session.getId(), extracted);
            // Extract architecture decisions and code patterns
            extractDecisions(content, session.getId(), extracted);
            extractPatterns(content, session.getId(), extracted);
            // Note: user-requested facts ("запомни") are handled by
            // RememberFactTool (explicit) and LlmMemoryExtractor (automatic)
        }

        LOG.info("MemoryExtractor: found " + extracted.size() + " candidate entries"); //$NON-NLS-1$ //$NON-NLS-2$

        // Store extracted entries
        for (MemoryEntry entry : extracted) {
            // Secret guard check
            if (SecretGuard.containsSecrets(entry.getContent())) {
                String filtered = SecretGuard.filter(entry.getContent());
                entry = MemoryEntry.builder(entry.getKey(), filtered)
                        .category(entry.getCategory())
                        .visibility(MemoryVisibility.MACHINE)
                        .retention(entry.getRetention())
                        .sourceSessionId(entry.getSourceSessionId())
                        .build();
            }

            MemoryService.remember(session.getProjectPath(), entry);
        }

        if (!extracted.isEmpty()) {
            LOG.info("Extracted " + extracted.size() + " memory entries from session " + session.getId()); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static void extractTasks(String content, String sessionId, List<MemoryEntry> out) {
        extractMatchingTasks(TODO_PATTERN, content, sessionId, out);
        extractMatchingTasks(RU_TASK_PATTERN, content, sessionId, out);
    }

    private static void extractMatchingTasks(Pattern pattern, String content,
            String sessionId, List<MemoryEntry> out) {
        // IMPORTANT #7 fix: hash-based dedup to avoid duplicate entries
        Set<String> seenHashes = new HashSet<>();
        for (MemoryEntry existing : out) {
            seenHashes.add(existing.getContent().toLowerCase().strip());
        }

        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String task = matcher.group(1).strip();
            String normalizedTask = task.toLowerCase().strip();
            if (task.length() > 10 && !SecretGuard.isSensitiveKey(task)
                    && seenHashes.add(normalizedTask)) {
                out.add(MemoryEntry.builder("Pending Tasks", task) //$NON-NLS-1$
                        .category(MemoryCategory.PENDING)
                        .visibility(MemoryVisibility.MACHINE)
                        .retention(RetentionPolicy.DEFAULT_PENDING_TTL)
                        .sourceSessionId(sessionId)
                        .build());
            }
        }
    }

    private static void extractDecisions(String content, String sessionId, List<MemoryEntry> out) {
        extractMatchingEntries(DECISION_PATTERN, content, sessionId,
                "Architecture Decisions", MemoryCategory.ARCHITECTURE, out); //$NON-NLS-1$
    }

    private static void extractPatterns(String content, String sessionId, List<MemoryEntry> out) {
        extractMatchingEntries(PATTERN_PATTERN, content, sessionId,
                "Code Patterns", MemoryCategory.PATTERN, out); //$NON-NLS-1$
    }

    private static void extractMatchingEntries(Pattern pattern, String content,
            String sessionId, String key, MemoryCategory category, List<MemoryEntry> out) {
        Set<String> seenHashes = new HashSet<>();
        for (MemoryEntry existing : out) {
            seenHashes.add(existing.getContent().toLowerCase().strip());
        }

        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String entry = matcher.group(1).strip();
            String normalized = entry.toLowerCase().strip();
            if (entry.length() > 10 && !SecretGuard.isSensitiveKey(entry)
                    && seenHashes.add(normalized)) {
                out.add(MemoryEntry.builder(key, entry)
                        .category(category)
                        .visibility(MemoryVisibility.MACHINE)
                        .retention(RetentionPolicy.DEFAULT_FACT_TTL)
                        .sourceSessionId(sessionId)
                        .build());
            }
        }
    }

}
