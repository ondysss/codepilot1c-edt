/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.ui;

/**
 * Pure helper that renders the compact "# Инструменты" section of the
 * ChatView system prompt.
 *
 * <p>Extracted from {@code ChatView} so it can be unit-tested without PDE/SWT
 * scaffolding. The UI bundle builds a {@link StringBuilder} and delegates
 * here when it reaches the TOOLS block of the system prompt.</p>
 *
 * <h2>Why this is not a manifest</h2>
 *
 * <p>The full tool manifest (names, descriptions, JSON schemas) is delivered
 * to the model through the structured {@code tools} parameter on the LLM
 * request — not through the system prompt. Earlier versions of
 * {@code ChatView.appendToolsSection} duplicated the manifest inside the
 * system prompt as a markdown list, which was ~1200 tokens of redundant input
 * on every request (for 57 built-in tools at ~80 chars per line). Codex's
 * review of the Phase 3 plan flagged this as the single largest source of
 * stable token overhead in ChatView.</p>
 *
 * <p>This helper is intentionally minimal: it prints a short usage hint and
 * points the model at {@code discover_tools} (the per-category discovery tool
 * already registered in {@code ToolRegistry}) when it wants to explore the
 * surface beyond what the structured manifest provides.</p>
 *
 * <h2>Invariants</h2>
 *
 * <ul>
 *   <li>No enumeration of tool names. Ever.</li>
 *   <li>No per-tool descriptions. Ever.</li>
 *   <li>Output length is O(1) regardless of how many tools are registered.</li>
 *   <li>Tests in {@code core.tests} pin the no-enumeration invariant against
 *       drift — if someone re-introduces a loop here, the tests fail loudly.</li>
 * </ul>
 */
public final class ChatSystemPromptToolsSection {

    private ChatSystemPromptToolsSection() {
    }

    /**
     * Appends the compact tools section to the given prompt builder.
     *
     * @param prompt   the system-prompt builder to append to (mutated)
     * @param hasTools whether at least one tool is registered. When
     *                 {@code false} the prompt explains tools are unavailable
     *                 rather than claiming tool access the model cannot use.
     */
    public static void append(StringBuilder prompt, boolean hasTools) {
        if (prompt == null) {
            throw new IllegalArgumentException("prompt must not be null"); //$NON-NLS-1$
        }
        if (!hasTools) {
            prompt.append(UNAVAILABLE_TEXT);
            return;
        }
        prompt.append(AVAILABLE_TEXT);
    }

    /** Marker text used by regression tests to pin the no-enumeration invariant. */
    static final String AVAILABLE_MARKER = "discover_tools"; //$NON-NLS-1$

    private static final String UNAVAILABLE_TEXT = """
            # Инструменты

            Инструменты недоступны в текущей конфигурации.

            """; //$NON-NLS-1$

    private static final String AVAILABLE_TEXT = """
            # Инструменты

            Используйте инструменты при работе с кодом, файлами и проектом.
            Если нужна информация из проекта, сначала вызывайте подходящий инструмент.
            Полный список инструментов и их параметры переданы отдельно в структурированном виде;
            для детального обзора по категории вызывайте discover_tools.

            """; //$NON-NLS-1$
}
