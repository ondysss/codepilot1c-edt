/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.prompts;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads layered instruction files with source provenance.
 */
public final class InstructionContextService {

    public enum LayerKind {
        AGENTS,
        CODE
    }

    public record InstructionLayer(LayerKind kind, String sourcePath, String content) {
    }

    private final WorkspacePromptSourceResolver sourceResolver;

    public InstructionContextService() {
        this(new WorkspacePromptSourceResolver());
    }

    InstructionContextService(Path projectStart, Path userHome) {
        this(new WorkspacePromptSourceResolver(projectStart, userHome));
    }

    InstructionContextService(WorkspacePromptSourceResolver sourceResolver) {
        this.sourceResolver = sourceResolver;
    }

    public List<InstructionLayer> loadAgentsLayers() {
        return discoverLayers("AGENTS.md", LayerKind.AGENTS); //$NON-NLS-1$
    }

    public List<InstructionLayer> loadCodeLayers(boolean backendSelectedInUi) {
        if (!backendSelectedInUi) {
            return List.of();
        }
        return discoverLayers("Code.md", LayerKind.CODE); //$NON-NLS-1$
    }

    private List<InstructionLayer> discoverLayers(String fileName, LayerKind kind) {
        Map<String, InstructionLayer> layers = new LinkedHashMap<>();

        for (Path candidate : sourceResolver.hiddenPromptCandidates(fileName)) {
            addCandidate(layers, candidate, kind);
        }

        for (Path directory : sourceResolver.collectAncestors()) {
            addCandidate(layers, directCandidate(directory, fileName), kind);
        }

        return List.copyOf(layers.values());
    }

    private void addCandidate(Map<String, InstructionLayer> layers, Path candidate, LayerKind kind) {
        if (candidate == null || !Files.isRegularFile(candidate)) {
            return;
        }
        try {
            String content = Files.readString(candidate, StandardCharsets.UTF_8).strip();
            if (!content.isEmpty()) {
                layers.put(candidate.toAbsolutePath().normalize().toString(),
                        new InstructionLayer(kind, candidate.toAbsolutePath().normalize().toString(), content));
            }
        } catch (Exception e) {
            // Ignore unreadable layer and keep remaining context discovery alive.
        }
    }

    private Path directCandidate(Path directory, String fileName) {
        return directory != null ? directory.resolve(fileName) : null;
    }
}
