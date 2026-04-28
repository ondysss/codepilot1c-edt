/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.memory.prompt;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;

/**
 * Registry of {@link IPromptContextContributor} instances.
 *
 * <p>Contributors are registered dynamically and invoked in priority order
 * during prompt assembly. The registry is thread-safe.</p>
 *
 * <p>Built-in contributors (Platform Knowledge, Project Metadata, Memory)
 * are registered during plugin startup. Extension points or dynamic tools
 * can register additional contributors at runtime.</p>
 */
public final class PromptContextContributorRegistry {

    private static final String PLUGIN_ID = "com.codepilot1c.core"; //$NON-NLS-1$

    private static final PromptContextContributorRegistry INSTANCE = new PromptContextContributorRegistry();

    private final List<IPromptContextContributor> contributors = new CopyOnWriteArrayList<>();

    public static PromptContextContributorRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Registers a contributor.
     *
     * @param contributor the contributor to register
     */
    public void register(IPromptContextContributor contributor) {
        if (contributor != null) {
            contributors.add(contributor);
        }
    }

    /**
     * Unregisters a contributor.
     *
     * @param contributor the contributor to remove
     */
    public void unregister(IPromptContextContributor contributor) {
        contributors.remove(contributor);
    }

    /**
     * Returns all registered contributors sorted by priority (ascending).
     */
    public List<IPromptContextContributor> getSorted() {
        return contributors.stream()
                .sorted(Comparator.comparingInt(IPromptContextContributor::getPriority))
                .toList();
    }

    /**
     * Assembles all contributor sections into a single prompt fragment.
     *
     * <p>Invokes each contributor in priority order, respecting the global
     * token budget. Contributors that throw exceptions are logged and skipped.</p>
     *
     * @param ctx the assembly context with budget and project info
     * @return assembled prompt text (may be empty if no contributors or budget exhausted)
     */
    public String assembleContributions(PromptAssemblyContext ctx) {
        if (ctx == null || contributors.isEmpty()) {
            return ""; //$NON-NLS-1$
        }

        StringBuilder result = new StringBuilder();
        PromptAssemblyContext current = ctx;

        for (IPromptContextContributor contributor : getSorted()) {
            if (current.isBudgetExhausted()) {
                break;
            }

            try {
                PromptSection section = contributor.contribute(current);
                if (section != null && !section.isEmpty()) {
                    result.append("\n\n## ").append(section.sectionId()) //$NON-NLS-1$
                          .append("\n\n").append(section.content()).append('\n'); //$NON-NLS-1$
                    current = current.consumeTokens(section.estimatedTokens());
                }
            } catch (Exception e) {
                logWarning("Contributor failed: " + contributor.getSectionId(), e); //$NON-NLS-1$
            }
        }

        return result.toString();
    }

    private static void logWarning(String message, Throwable error) {
        try {
            ILog log = Platform.getLog(PromptContextContributorRegistry.class);
            log.log(new Status(IStatus.WARNING, PLUGIN_ID, message, error));
        } catch (RuntimeException | LinkageError e) {
            // Prompt assembly must still work in plain JVM tests where Eclipse logging is unavailable.
        }
    }

    /**
     * Returns the number of registered contributors (for testing).
     */
    public int size() {
        return contributors.size();
    }

    /**
     * Clears all registered contributors (for testing).
     */
    public void clear() {
        contributors.clear();
    }
}
