/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.skills;

import java.util.List;

/**
 * Parsed skill definition with provenance and optional routing metadata.
 */
public record SkillDefinition(
        String name,
        String description,
        List<String> allowedTools,
        boolean backendOnly,
        String body,
        SourceType sourceType,
        String sourcePath) {

    public enum SourceType {
        PROJECT,
        USER,
        BUNDLED
    }
}
