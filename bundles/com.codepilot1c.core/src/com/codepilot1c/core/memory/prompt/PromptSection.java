/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.memory.prompt;

import com.codepilot1c.core.memory.MemoryVisibility;

/**
 * Structured output from an {@link IPromptContextContributor}.
 *
 * <p>Includes provenance tracking and token cost estimate for budget management.
 * The {@link #visibility()} field determines trust level in the assembled prompt.</p>
 */
public record PromptSection(
        /** Section identifier, e.g. "Project Metadata", "1C Platform Knowledge". */
        String sectionId,

        /** Formatted text content for injection into the system prompt. */
        String content,

        /** Estimated token cost of this section. */
        int estimatedTokens,

        /** Origin of this content, e.g. "auto-detected", "plugin-resource", "project.md". */
        String provenance,

        /** Trust boundary marker. */
        MemoryVisibility visibility
) {

    /** Returns an empty section that will be skipped during assembly. */
    public static PromptSection empty() {
        return new PromptSection("", "", 0, "", MemoryVisibility.CURATED); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /** Returns true if this section has no useful content. */
    public boolean isEmpty() {
        return content == null || content.isBlank();
    }
}
