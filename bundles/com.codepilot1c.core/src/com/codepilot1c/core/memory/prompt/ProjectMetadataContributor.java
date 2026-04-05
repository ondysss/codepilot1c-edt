/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.memory.prompt;

import java.util.List;
import java.util.stream.Collectors;

import com.codepilot1c.core.memory.MemoryVisibility;
import com.codepilot1c.core.memory.detection.DetectedLibrary;
import com.codepilot1c.core.memory.detection.ProjectContext;
import com.codepilot1c.core.memory.detection.ProjectContext.ProjectType;
import com.codepilot1c.core.memory.detection.ProjectMetadataDetector;

/**
 * Contributes auto-detected project metadata to the system prompt.
 *
 * <p>Priority 400: after Platform Knowledge (300), before Memory (500).</p>
 *
 * <p>Formats EDT-detected metadata (configuration name, platform version,
 * libraries, object counts, extensions) as a concise prompt section.</p>
 */
public class ProjectMetadataContributor implements IPromptContextContributor {

    private static final String SECTION_ID = "Project Metadata"; //$NON-NLS-1$
    private static final int PRIORITY = 400;

    @Override
    public String getSectionId() {
        return SECTION_ID;
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    @Override
    public PromptSection contribute(PromptAssemblyContext ctx) {
        if (ctx.projectPath() == null || ctx.isBudgetExhausted()) {
            return PromptSection.empty();
        }

        ProjectContext meta = ProjectMetadataDetector.getCached(ctx.projectPath());
        if (meta == null) {
            return PromptSection.empty();
        }

        String content = formatForPrompt(meta);
        if (content.isBlank()) {
            return PromptSection.empty();
        }

        int tokens = TokenEstimator.estimate(content);
        if (tokens > ctx.remainingBudget()) {
            content = TokenEstimator.truncateToFit(content, ctx.remainingBudget());
            tokens = TokenEstimator.estimate(content);
        }

        return new PromptSection(SECTION_ID, content, tokens,
                "auto-detected", MemoryVisibility.MACHINE); //$NON-NLS-1$
    }

    /**
     * Formats project context for prompt injection. Visible for testing.
     */
    static String formatForPrompt(ProjectContext ctx) {
        StringBuilder sb = new StringBuilder();

        // Configuration identity
        if (ctx.getProjectType() == ProjectType.EXTENSION) {
            appendExtensionHeader(sb, ctx);
        } else {
            appendBaseConfigHeader(sb, ctx);
        }

        // Platform
        sb.append("Platform: ").append(ctx.getPlatformVersion()); //$NON-NLS-1$
        if (!ctx.getCompatibilityMode().isEmpty()) {
            sb.append(" (compatibility: ").append(ctx.getCompatibilityMode()).append(')'); //$NON-NLS-1$
        }
        sb.append('\n');

        // Extension compatibility mode (for extension projects)
        if (ctx.getProjectType() == ProjectType.EXTENSION
                && !ctx.getExtensionCompatibilityMode().isEmpty()) {
            sb.append("Extension compatibility: ").append(ctx.getExtensionCompatibilityMode()).append('\n'); //$NON-NLS-1$
        }

        // Libraries
        List<DetectedLibrary> libs = ctx.getLibraries();
        if (!libs.isEmpty()) {
            sb.append("Libraries: "); //$NON-NLS-1$
            sb.append(libs.stream()
                    .map(lib -> {
                        String s = lib.name();
                        if (lib.versionHint() != null) {
                            s += " " + lib.versionHint(); //$NON-NLS-1$
                        }
                        return s;
                    })
                    .collect(Collectors.joining(", "))); //$NON-NLS-1$
            sb.append('\n');
        }

        // Structure
        sb.append("Objects: ").append(ctx.getDocumentCount()).append(" docs, ") //$NON-NLS-1$ //$NON-NLS-2$
          .append(ctx.getCatalogCount()).append(" catalogs, ") //$NON-NLS-1$
          .append(ctx.getRegisterCount()).append(" registers\n"); //$NON-NLS-1$

        // Extension scope (using "contains" not "adds" per review #6)
        if (ctx.getProjectType() == ProjectType.EXTENSION) {
            sb.append("Extension objects: ").append(ctx.getExtensionDocumentCount()) //$NON-NLS-1$
              .append(" docs, ").append(ctx.getExtensionCatalogCount()).append(" catalogs\n"); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // Extensions in workspace
        if (ctx.hasExtensions()) {
            sb.append("Extensions in workspace: ") //$NON-NLS-1$
              .append(String.join(", ", ctx.getExtensions())).append('\n'); //$NON-NLS-1$
        }

        if (ctx.hasManagedForms()) {
            sb.append("Forms: managed\n"); //$NON-NLS-1$
        }
        if (ctx.hasHttpServices()) {
            sb.append("Has HTTP services\n"); //$NON-NLS-1$
        }
        if (ctx.hasWebServices()) {
            sb.append("Has web services\n"); //$NON-NLS-1$
        }

        // Top-level subsystems (max 10)
        List<String> subsystems = ctx.getSubsystems();
        if (!subsystems.isEmpty()) {
            sb.append("Subsystems: ").append( //$NON-NLS-1$
                    String.join(", ", subsystems.subList(0, //$NON-NLS-1$
                            Math.min(subsystems.size(), 10)))).append('\n');
        }

        return sb.toString();
    }

    private static void appendExtensionHeader(StringBuilder sb, ProjectContext ctx) {
        sb.append("Extension: ").append(ctx.getExtensionName()); //$NON-NLS-1$
        if (!ctx.getExtensionSynonym().isEmpty()) {
            sb.append(" (").append(ctx.getExtensionSynonym()).append(')'); //$NON-NLS-1$
        }
        sb.append('\n');

        if (!ctx.getExtensionPurpose().isEmpty()) {
            sb.append("Purpose: ").append(ctx.getExtensionPurpose()).append('\n'); //$NON-NLS-1$
        }

        sb.append("Base configuration: ").append(ctx.getConfigurationName()); //$NON-NLS-1$
        if (!ctx.getConfigurationSynonym().isEmpty()) {
            sb.append(" (").append(ctx.getConfigurationSynonym()).append(')'); //$NON-NLS-1$
        }
        sb.append('\n');
    }

    private static void appendBaseConfigHeader(StringBuilder sb, ProjectContext ctx) {
        sb.append("Configuration: ").append(ctx.getConfigurationName()); //$NON-NLS-1$
        if (!ctx.getConfigurationSynonym().isEmpty()) {
            sb.append(" (").append(ctx.getConfigurationSynonym()).append(')'); //$NON-NLS-1$
        }
        sb.append('\n');

        if (ctx.isTypical()) {
            sb.append("Type: typical 1C configuration\n"); //$NON-NLS-1$
        }
    }
}
