/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.memory.prompt;

/**
 * Contributes a section of contextual information to the system prompt.
 *
 * <p>Contributors are called in {@link #getPriority()} order during prompt assembly.
 * Each contributor receives a {@link PromptAssemblyContext} with remaining token budget
 * and should respect it by truncating or omitting content when budget is low.</p>
 *
 * <p>Priority ranges:</p>
 * <ul>
 *   <li>300 - Platform Knowledge (shipped with plugin)</li>
 *   <li>400 - Project Metadata (auto-detected from EDT)</li>
 *   <li>500 - Project Memory (curated + auto-memory files)</li>
 * </ul>
 *
 * <p>Implementations must be stateless and thread-safe. Any exception thrown by
 * {@link #contribute(PromptAssemblyContext)} is caught and logged; the contributor
 * is skipped and remaining contributors continue.</p>
 */
public interface IPromptContextContributor {

    /**
     * Returns a unique section identifier for this contributor.
     *
     * @return section id, e.g. "1C Platform Knowledge"
     */
    String getSectionId();

    /**
     * Returns the priority (lower = earlier in prompt). Standard ranges:
     * 300 (platform knowledge), 400 (metadata), 500 (memory).
     *
     * @return priority value
     */
    int getPriority();

    /**
     * Produces a prompt section given the current assembly context.
     *
     * <p>Implementations should check {@link PromptAssemblyContext#remainingBudget()}
     * and return {@link PromptSection#empty()} if budget is exhausted.</p>
     *
     * @param ctx assembly context with project info, profile, and budget
     * @return a prompt section, or {@link PromptSection#empty()} to skip
     */
    PromptSection contribute(PromptAssemblyContext ctx);
}
