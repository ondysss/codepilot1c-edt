/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.memory.prompt;

/**
 * Rich context passed to each {@link IPromptContextContributor}.
 *
 * <p>Contains everything a contributor might need to decide what to include
 * and how much budget it can consume. The {@link #remainingBudget()} decreases
 * as earlier contributors consume tokens.</p>
 *
 * <p>Immutable record; use {@link #consumeTokens(int)} to derive a new context
 * with reduced budget for the next contributor in the chain.</p>
 */
public record PromptAssemblyContext(
        /** Resolved project path (may be null if no project is bound). */
        String projectPath,

        /** Agent profile name: "build", "plan", "explore", or null. */
        String profileName,

        /** True when the UI has a CodePilot backend selection active. */
        boolean backendSelectedInUi,

        /** Current session ID (may be null if no active session). */
        String sessionId,

        /** Tokens remaining in global memory budget for subsequent contributors. */
        int remainingBudget,

        /** Total token budget allocated for all contributors combined. */
        int globalBudget,

        /** Current user query for relevance scoring (may be null). */
        String currentUserQuery
) {

    /** Default global token budget for all memory contributors. */
    public static final int DEFAULT_GLOBAL_BUDGET = 1200;

    /**
     * Creates a context with remaining budget equal to the global budget.
     */
    public static PromptAssemblyContext of(String projectPath, String profileName,
            boolean backendSelectedInUi, String sessionId, int globalBudget) {
        return new PromptAssemblyContext(projectPath, profileName,
                backendSelectedInUi, sessionId, globalBudget, globalBudget, null);
    }

    /**
     * Creates a context with default budget.
     */
    public static PromptAssemblyContext of(String projectPath, String profileName,
            boolean backendSelectedInUi, String sessionId) {
        return of(projectPath, profileName, backendSelectedInUi, sessionId, DEFAULT_GLOBAL_BUDGET);
    }

    /**
     * Creates a context with a user query for relevance scoring.
     */
    public static PromptAssemblyContext of(String projectPath, String profileName,
            boolean backendSelectedInUi, String sessionId, int globalBudget,
            String currentUserQuery) {
        return new PromptAssemblyContext(projectPath, profileName,
                backendSelectedInUi, sessionId, globalBudget, globalBudget, currentUserQuery);
    }

    /**
     * Returns a new context with reduced remaining budget.
     *
     * @param tokens number of tokens consumed by the current contributor
     * @return new context with updated remaining budget (never negative)
     */
    public PromptAssemblyContext consumeTokens(int tokens) {
        return new PromptAssemblyContext(projectPath, profileName,
                backendSelectedInUi, sessionId,
                Math.max(0, remainingBudget - tokens), globalBudget, currentUserQuery);
    }

    /**
     * Returns true if budget is exhausted (no tokens remaining).
     */
    public boolean isBudgetExhausted() {
        return remainingBudget <= 0;
    }
}
