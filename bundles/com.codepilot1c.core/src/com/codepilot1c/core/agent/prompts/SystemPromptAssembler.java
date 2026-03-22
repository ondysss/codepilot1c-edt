/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.prompts;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.codepilot1c.core.provider.ProviderSelectionGate;
import com.codepilot1c.core.settings.PromptTemplateService;
import com.codepilot1c.core.skills.SkillCatalog;
import com.codepilot1c.core.skills.SkillDefinition;

/**
 * Single prompt assembly path for runtime, UI, and MCP host.
 */
public final class SystemPromptAssembler {

    public record PromptAssembly(
            String prompt,
            List<InstructionContextService.InstructionLayer> agentsLayers,
            List<InstructionContextService.InstructionLayer> codeLayers,
            List<SkillDefinition> skills,
            boolean backendSelectedInUi) {
    }

    private static final SystemPromptAssembler INSTANCE =
            new SystemPromptAssembler(new InstructionContextService(), new SkillCatalog());

    private final InstructionContextService instructionContextService;
    private final SkillCatalog skillCatalog;

    public static SystemPromptAssembler getInstance() {
        return INSTANCE;
    }

    public SystemPromptAssembler(InstructionContextService instructionContextService, SkillCatalog skillCatalog) {
        this.instructionContextService = Objects.requireNonNull(instructionContextService, "instructionContextService"); //$NON-NLS-1$
        this.skillCatalog = Objects.requireNonNull(skillCatalog, "skillCatalog"); //$NON-NLS-1$
    }

    public String assemble(String basePrompt, String promptAddition, String profileName, List<String> requestedSkills) {
        return assembleDetailed(basePrompt, promptAddition, profileName, requestedSkills).prompt();
    }

    public String assemble(String basePrompt, String promptAddition, String profileName, Set<String> requestedSkills) {
        return assemble(basePrompt, promptAddition, profileName,
                requestedSkills != null ? List.copyOf(requestedSkills) : List.of());
    }

    public PromptAssembly assembleDetailed(String basePrompt, String promptAddition, String profileName,
            List<String> requestedSkills) {
        return assembleDetailed(basePrompt, promptAddition, profileName, requestedSkills,
                ProviderSelectionGate.isCodePilotSelectedInUi());
    }

    PromptAssembly assembleDetailed(String basePrompt, String promptAddition, String profileName,
            List<String> requestedSkills, boolean backendSelectedInUi) {
        List<InstructionContextService.InstructionLayer> agentsLayers = instructionContextService.loadAgentsLayers();
        List<InstructionContextService.InstructionLayer> codeLayers =
                instructionContextService.loadCodeLayers(backendSelectedInUi);
        List<SkillDefinition> skills = loadRequestedSkills(requestedSkills, backendSelectedInUi);

        StringBuilder prompt = new StringBuilder();
        appendDistinct(prompt, basePrompt);
        appendDistinct(prompt, promptAddition);
        appendInstructionLayers(prompt, "AGENTS.md", agentsLayers); //$NON-NLS-1$
        appendInstructionLayers(prompt, "Code.md", codeLayers); //$NON-NLS-1$
        appendSkills(prompt, skills);

        String effectivePrompt = PromptTemplateService.getInstance().applySystemPrompt(prompt.toString().strip());
        return new PromptAssembly(effectivePrompt, agentsLayers, codeLayers, skills, backendSelectedInUi);
    }

    private List<SkillDefinition> loadRequestedSkills(List<String> requestedSkills, boolean backendSelectedInUi) {
        if (requestedSkills == null || requestedSkills.isEmpty()) {
            return List.of();
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String requestedSkill : requestedSkills) {
            if (requestedSkill != null && !requestedSkill.isBlank()) {
                distinct.add(requestedSkill.trim());
            }
        }

        List<SkillDefinition> resolved = new ArrayList<>();
        for (String skillName : distinct) {
            skillCatalog.getSkill(skillName, backendSelectedInUi).ifPresent(resolved::add);
        }
        return List.copyOf(resolved);
    }

    private void appendDistinct(StringBuilder prompt, String section) {
        String normalized = section != null ? section.strip() : ""; //$NON-NLS-1$
        if (normalized.isEmpty()) {
            return;
        }
        if (prompt.indexOf(normalized) >= 0) {
            return;
        }
        if (!prompt.isEmpty()) {
            prompt.append("\n\n"); //$NON-NLS-1$
        }
        prompt.append(normalized);
    }

    private void appendInstructionLayers(StringBuilder prompt, String fileLabel,
            List<InstructionContextService.InstructionLayer> layers) {
        if (layers == null || layers.isEmpty()) {
            return;
        }
        prompt.append("\n\n## Layered Context: ").append(fileLabel).append("\n"); //$NON-NLS-1$
        for (InstructionContextService.InstructionLayer layer : layers) {
            prompt.append("\n### Source: ").append(layer.sourcePath()).append("\n\n"); //$NON-NLS-1$
            prompt.append(layer.content()).append("\n"); //$NON-NLS-1$
        }
    }

    private void appendSkills(StringBuilder prompt, List<SkillDefinition> skills) {
        if (skills == null || skills.isEmpty()) {
            return;
        }
        prompt.append("\n\n## Loaded Skills\n"); //$NON-NLS-1$
        for (SkillDefinition skill : skills) {
            prompt.append("\n### Skill: ").append(skill.name()).append("\n"); //$NON-NLS-1$
            prompt.append("Source: ").append(skill.sourcePath()).append("\n"); //$NON-NLS-1$
            if (!skill.allowedTools().isEmpty()) {
                prompt.append("Allowed tools: ").append(String.join(", ", skill.allowedTools())).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (skill.backendOnly()) {
                prompt.append("Backend-only: true\n"); //$NON-NLS-1$
            }
            prompt.append("\n").append(skill.body()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}
