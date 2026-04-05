/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.memory;

/**
 * Trust boundary marker for memory entries.
 *
 * <p>{@link #CURATED} entries come from user-edited files (project.md) and
 * are treated as high-confidence authoritative context.</p>
 *
 * <p>{@link #MACHINE} entries are auto-generated (.auto-memory.md, metadata
 * detection) and are framed as non-authoritative in the prompt.</p>
 */
public enum MemoryVisibility {

    /** User-curated memory (project.md, git-tracked). */
    CURATED,

    /** Machine-generated memory (.auto-memory.md, auto-detected metadata). */
    MACHINE
}
