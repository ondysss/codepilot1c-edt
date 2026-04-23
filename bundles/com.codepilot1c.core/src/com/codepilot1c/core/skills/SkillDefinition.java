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
 *
 * <p>Supports Codex-style skill policy: {@code implicit-triggers} defines keywords
 * that hint the model to auto-load this skill, and {@code allow-implicit} controls
 * whether implicit invocation is enabled for this skill.
 */
public record SkillDefinition(
        String name,
        String description,
        List<String> allowedTools,
        boolean backendOnly,
        boolean allowImplicit,
        List<String> implicitTriggers,
        String body,
        SourceType sourceType,
        String sourcePath) {

    /**
     * Backward-compatible constructor without implicit invocation fields.
     */
    public SkillDefinition(String name, String description, List<String> allowedTools,
            boolean backendOnly, String body, SourceType sourceType, String sourcePath) {
        this(name, description, allowedTools, backendOnly, false, List.of(), body, sourceType, sourcePath);
    }

    public enum SourceType {
        PROJECT,
        USER,
        BUNDLED
    }
}
