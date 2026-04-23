/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.memory.extraction;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;

import com.codepilot1c.core.session.Session;
import com.codepilot1c.core.session.SessionManager.ISessionChangeListener;

/**
 * Listens for session completion and triggers memory extraction.
 *
 * <p>This listener is registered with {@link com.codepilot1c.core.session.SessionManager}
 * and triggers asynchronous extraction via two channels:</p>
 * <ul>
 *   <li><b>Channel C:</b> Rule-based regex extraction (TODO/FIXME, decisions) — instant</li>
 *   <li><b>Channel A:</b> LLM-based extraction via {@link LlmMemoryExtractor} — async</li>
 * </ul>
 *
 * <p><b>Behind feature flag.</b> The {@code enabledFlag} supplier must return {@code true}
 * for extraction to proceed. This allows runtime toggling of the feature.</p>
 *
 * <p>Deduplication is disk-queue-based: a pending extraction file in the queue directory
 * indicates an in-progress or unprocessed extraction. This avoids the memory leak of
 * the previous in-memory {@code Set<String>} approach (Issue 5).</p>
 *
 * <p>Extraction writes ONLY to {@code .auto-memory.md} (machine visibility),
 * never to user-curated {@code project.md}.</p>
 */
public class MemoryExtractionListener implements ISessionChangeListener {

    private static final ILog LOG = Platform.getLog(MemoryExtractionListener.class);
    private static final int MIN_MESSAGES_FOR_EXTRACTION = 4;

    /** Queue directory name inside plugin state location. */
    private static final String QUEUE_DIR_NAME = "memory-extraction-queue"; //$NON-NLS-1$

    private final Supplier<Boolean> enabledFlag;

    /**
     * Creates a listener gated by the given feature flag.
     *
     * @param enabledFlag supplier that returns true when extraction is enabled
     */
    public MemoryExtractionListener(Supplier<Boolean> enabledFlag) {
        this.enabledFlag = enabledFlag;
    }

    /**
     * Called when a session is saved. Triggers async extraction for
     * COMPLETED or ARCHIVED sessions.
     */
    @Override
    public void onSessionSaved(Session session) {
        if (session == null) {
            return;
        }
        if (session.getStatus() != Session.SessionStatus.COMPLETED
                && session.getStatus() != Session.SessionStatus.ARCHIVED) {
            return;
        }
        triggerExtraction(session);
    }

    /**
     * Called when a session is explicitly completed (e.g., via startNewSession()).
     * This ensures extraction runs even if the session was only completed but not
     * yet saved at the time of the completion event.
     */
    @Override
    public void onSessionCompleted(Session session) {
        triggerExtraction(session);
    }

    /**
     * Called when the current session changes (e.g., user switches sessions).
     * Extracts from the old session if it was ACTIVE with enough content.
     */
    @Override
    public void onCurrentSessionChanged(Session oldSession, Session newSession) {
        if (oldSession != null
                && oldSession.getMessages().size() >= MIN_MESSAGES_FOR_EXTRACTION
                && oldSession.getProjectPath() != null
                && !oldSession.getProjectPath().isBlank()) {
            triggerExtraction(oldSession);
        }
    }

    private void triggerExtraction(Session session) {
        if (session == null) {
            return;
        }
        if (!Boolean.TRUE.equals(enabledFlag.get())) {
            return;
        }
        if (session.getMessages().size() < MIN_MESSAGES_FOR_EXTRACTION) {
            return;
        }
        if (session.getProjectPath() == null || session.getProjectPath().isBlank()) {
            return;
        }

        String sessionId = session.getId();
        if (sessionId == null) {
            return;
        }

        // Disk-queue-based dedup (Issue 5 fix): if pending file already exists,
        // this session is either being extracted or was already queued.
        Path queueDir = getQueueDirectory();
        if (queueDir != null) {
            Path pendingFile = queueDir.resolve(sessionId + ".extraction-pending"); //$NON-NLS-1$
            if (Files.exists(pendingFile)) {
                LOG.info("MemoryExtractionListener: session " + sessionId //$NON-NLS-1$
                        + " already queued, skipping"); //$NON-NLS-1$
                return;
            }
        }

        // Channel C: rule-based extraction (fast, synchronous in async wrapper)
        // Channel A: LLM-based extraction (slow, after rule-based)
        CompletableFuture.runAsync(() -> {
            try {
                // Channel C: regex-based extraction (TODO/FIXME, decisions, patterns)
                MemoryExtractor.extract(session);
            } catch (Exception e) {
                LOG.warn("Rule-based memory extraction failed for session: " //$NON-NLS-1$
                        + session.getId(), e);
            }

            // Channel A: LLM-based extraction with disk queue
            try {
                Path llmQueueDir = getQueueDirectory();
                if (llmQueueDir != null) {
                    // Save pending request to disk first (crash-safe)
                    LlmMemoryExtractor.savePendingRequest(session, llmQueueDir);
                }

                // Run LLM extraction
                LlmMemoryExtractor.extract(session);

                // On success, remove pending file
                if (llmQueueDir != null) {
                    LlmMemoryExtractor.deletePendingRequest(sessionId, llmQueueDir);
                }
            } catch (Exception e) {
                LOG.warn("LLM memory extraction failed for session: " //$NON-NLS-1$
                        + session.getId() + " (will retry on next startup)", e); //$NON-NLS-1$
                // Pending file remains on disk for retry on next startup
            }
        });
    }

    /**
     * Returns the queue directory for pending extraction requests.
     * Falls back to a temp-based location if plugin state is unavailable.
     */
    static Path getQueueDirectory() {
        try {
            // Try Eclipse plugin state location
            var bundle = Platform.getBundle("com.codepilot1c.core"); //$NON-NLS-1$
            if (bundle != null) {
                var stateLocation = Platform.getStateLocation(bundle);
                if (stateLocation != null) {
                    return stateLocation.toFile().toPath().resolve(QUEUE_DIR_NAME);
                }
            }
        } catch (Exception e) {
            // Platform not available (e.g., in tests)
        }

        // Fallback: user home
        try {
            Path fallback = Path.of(System.getProperty("user.home", "/tmp")) //$NON-NLS-1$ //$NON-NLS-2$
                    .resolve(".codepilot1c").resolve(QUEUE_DIR_NAME); //$NON-NLS-1$
            return fallback;
        } catch (Exception e) {
            LOG.warn("Failed to resolve queue directory", e); //$NON-NLS-1$
            return null;
        }
    }
}
