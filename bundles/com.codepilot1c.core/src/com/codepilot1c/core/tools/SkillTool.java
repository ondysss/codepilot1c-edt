/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.provider.ProviderSelectionGate;
import com.codepilot1c.core.skills.SkillCatalog;
import com.codepilot1c.core.skills.SkillDefinition;

/**
 * Lists and loads lazy skill instructions.
 */
@ToolMeta(name = "skill", category = "general", tags = {"workspace"})
public final class SkillTool extends AbstractTool {

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "name": {
                  "type": "string",
                  "description": "Имя навыка для загрузки. Если не указано, tool возвращает список доступных skills."
                },
                "list": {
                  "type": "boolean",
                  "description": "Вернуть только список доступных навыков без body"
                }
              }
            }
            """; //$NON-NLS-1$

    private final SkillCatalog skillCatalog;

    public SkillTool() {
        this(new SkillCatalog());
    }

    SkillTool(SkillCatalog skillCatalog) {
        this.skillCatalog = skillCatalog;
    }

    @Override
    public String getDescription() {
        return "Показывает доступные skills и загружает их инструкции по имени. Используй для lazy skill discovery вместо угадывания skill body."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> parameters = params.getRaw();
            boolean backendSelectedInUi = ProviderSelectionGate.isCodePilotSelectedInUi();
            boolean listOnly = parameters != null && Boolean.parseBoolean(String.valueOf(parameters.get("list"))); //$NON-NLS-1$
            String name = parameters != null && parameters.get("name") != null //$NON-NLS-1$
                    ? String.valueOf(parameters.get("name")).trim() //$NON-NLS-1$
                    : ""; //$NON-NLS-1$

            if (name.isEmpty() || listOnly) {
                return ToolResult.success(formatList(skillCatalog.discoverVisibleSkills(backendSelectedInUi)));
            }

            return skillCatalog.getSkill(name, backendSelectedInUi)
                    .map(skill -> ToolResult.success(formatSkill(skill)))
                    .orElseGet(() -> ToolResult.failure("Skill not found or unavailable for current provider selection: " + name)); //$NON-NLS-1$
        });
    }

    private String formatList(List<SkillDefinition> skills) {
        if (skills.isEmpty()) {
            return "No skills available."; //$NON-NLS-1$
        }
        StringBuilder result = new StringBuilder("Available skills:\n"); //$NON-NLS-1$
        for (SkillDefinition skill : skills) {
            result.append("- ").append(skill.name()).append(": ").append(skill.description()); //$NON-NLS-1$ //$NON-NLS-2$
            if (!skill.allowedTools().isEmpty()) {
                result.append(" | allowed-tools=").append(String.join(",", skill.allowedTools())); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (skill.backendOnly()) {
                result.append(" | backend-only"); //$NON-NLS-1$
            }
            result.append('\n');
        }
        return result.toString().stripTrailing();
    }

    private String formatSkill(SkillDefinition skill) {
        StringBuilder result = new StringBuilder();
        result.append("# Skill: ").append(skill.name()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        result.append("Source: ").append(skill.sourcePath()).append("\n"); //$NON-NLS-1$
        if (!skill.allowedTools().isEmpty()) {
            result.append("Allowed tools: ").append(String.join(", ", skill.allowedTools())).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (skill.backendOnly()) {
            result.append("Backend-only: true\n"); //$NON-NLS-1$
        }
        result.append("\n").append(skill.body()); //$NON-NLS-1$
        return result.toString();
    }
}
