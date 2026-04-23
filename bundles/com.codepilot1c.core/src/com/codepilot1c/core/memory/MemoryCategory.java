/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.memory;

/**
 * Category of a memory entry, used for filtering and display.
 */
public enum MemoryCategory {

    /** Unfinished task from previous session. Shown first in prompt. */
    PENDING,

    /** User preference (coding style, naming, etc.). */
    PREFERENCE,

    /** Architectural decision or constraint. */
    ARCHITECTURE,

    /** Design decision with rationale. */
    DECISION,

    /** Factual information about the project. */
    FACT,

    /** Known bug or issue. */
    BUG,

    /** Code pattern or anti-pattern observed. */
    PATTERN
}
