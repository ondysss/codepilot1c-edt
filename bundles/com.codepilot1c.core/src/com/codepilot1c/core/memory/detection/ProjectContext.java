/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.memory.detection;

import java.util.List;

/**
 * Auto-detected project context from EDT project model.
 *
 * <p>All fields are initialized with safe defaults (empty strings, empty lists, zeros)
 * to prevent NPE in partial detection scenarios (OSGi startup, degraded runtime).</p>
 *
 * <p>For extension projects, base configuration fields are populated from the parent
 * project via {@code IExtensionProject.getParentProject()}.</p>
 */
public class ProjectContext {

    /** Project type discriminator. */
    public enum ProjectType {
        /** Base configuration project. */
        BASE_CONFIGURATION,
        /** Configuration extension project. */
        EXTENSION
    }

    // --- Project type ---
    private ProjectType projectType = ProjectType.BASE_CONFIGURATION;

    // --- Platform ---
    private String platformVersion = "unknown"; //$NON-NLS-1$
    private String compatibilityMode = ""; //$NON-NLS-1$

    // --- Base configuration (always filled, even for extensions — from parent) ---
    private String configurationName = ""; //$NON-NLS-1$
    private String configurationSynonym = ""; //$NON-NLS-1$
    private boolean typical;

    // --- Extension-specific (only when projectType == EXTENSION) ---
    private String extensionName = ""; //$NON-NLS-1$
    private String extensionSynonym = ""; //$NON-NLS-1$
    private String extensionPurpose = ""; //$NON-NLS-1$
    private String extensionCompatibilityMode = ""; //$NON-NLS-1$
    private int extensionDocumentCount;
    private int extensionCatalogCount;

    // --- Extensions of this config (only when projectType == BASE_CONFIGURATION) ---
    private boolean hasExtensions;
    private List<String> extensions = List.of();

    // --- Libraries detected ---
    private List<DetectedLibrary> libraries = List.of();

    // --- Structure (from base config) ---
    private int documentCount;
    private int catalogCount;
    private int registerCount;
    private boolean hasHttpServices;
    private boolean hasWebServices;
    private boolean hasManagedForms;
    private List<String> subsystems = List.of();

    // --- Getters ---

    public ProjectType getProjectType() { return projectType; }
    public String getPlatformVersion() { return platformVersion; }
    public String getCompatibilityMode() { return compatibilityMode; }
    public String getConfigurationName() { return configurationName; }
    public String getConfigurationSynonym() { return configurationSynonym; }
    public boolean isTypical() { return typical; }
    public String getExtensionName() { return extensionName; }
    public String getExtensionSynonym() { return extensionSynonym; }
    public String getExtensionPurpose() { return extensionPurpose; }
    public String getExtensionCompatibilityMode() { return extensionCompatibilityMode; }
    public int getExtensionDocumentCount() { return extensionDocumentCount; }
    public int getExtensionCatalogCount() { return extensionCatalogCount; }
    public boolean hasExtensions() { return hasExtensions; }
    public List<String> getExtensions() { return extensions; }
    public List<DetectedLibrary> getLibraries() { return libraries; }
    public int getDocumentCount() { return documentCount; }
    public int getCatalogCount() { return catalogCount; }
    public int getRegisterCount() { return registerCount; }
    public boolean hasHttpServices() { return hasHttpServices; }
    public boolean hasWebServices() { return hasWebServices; }
    public boolean hasManagedForms() { return hasManagedForms; }
    public List<String> getSubsystems() { return subsystems; }

    // --- Setters ---

    public void setProjectType(ProjectType projectType) { this.projectType = projectType; }
    public void setPlatformVersion(String platformVersion) { this.platformVersion = platformVersion != null ? platformVersion : "unknown"; } //$NON-NLS-1$
    public void setCompatibilityMode(String compatibilityMode) { this.compatibilityMode = compatibilityMode != null ? compatibilityMode : ""; } //$NON-NLS-1$
    public void setConfigurationName(String configurationName) { this.configurationName = configurationName != null ? configurationName : ""; } //$NON-NLS-1$
    public void setConfigurationSynonym(String configurationSynonym) { this.configurationSynonym = configurationSynonym != null ? configurationSynonym : ""; } //$NON-NLS-1$
    public void setTypical(boolean typical) { this.typical = typical; }
    public void setExtensionName(String extensionName) { this.extensionName = extensionName != null ? extensionName : ""; } //$NON-NLS-1$
    public void setExtensionSynonym(String extensionSynonym) { this.extensionSynonym = extensionSynonym != null ? extensionSynonym : ""; } //$NON-NLS-1$
    public void setExtensionPurpose(String extensionPurpose) { this.extensionPurpose = extensionPurpose != null ? extensionPurpose : ""; } //$NON-NLS-1$
    public void setExtensionCompatibilityMode(String extensionCompatibilityMode) { this.extensionCompatibilityMode = extensionCompatibilityMode != null ? extensionCompatibilityMode : ""; } //$NON-NLS-1$
    public void setExtensionDocumentCount(int extensionDocumentCount) { this.extensionDocumentCount = extensionDocumentCount; }
    public void setExtensionCatalogCount(int extensionCatalogCount) { this.extensionCatalogCount = extensionCatalogCount; }
    public void setHasExtensions(boolean hasExtensions) { this.hasExtensions = hasExtensions; }
    public void setExtensions(List<String> extensions) { this.extensions = extensions != null ? List.copyOf(extensions) : List.of(); }
    public void setLibraries(List<DetectedLibrary> libraries) { this.libraries = libraries != null ? List.copyOf(libraries) : List.of(); }
    public void setDocumentCount(int documentCount) { this.documentCount = documentCount; }
    public void setCatalogCount(int catalogCount) { this.catalogCount = catalogCount; }
    public void setRegisterCount(int registerCount) { this.registerCount = registerCount; }
    public void setHasHttpServices(boolean hasHttpServices) { this.hasHttpServices = hasHttpServices; }
    public void setHasWebServices(boolean hasWebServices) { this.hasWebServices = hasWebServices; }
    public void setHasManagedForms(boolean hasManagedForms) { this.hasManagedForms = hasManagedForms; }
    public void setSubsystems(List<String> subsystems) { this.subsystems = subsystems != null ? List.copyOf(subsystems) : List.of(); }

    // --- Convenience methods ---

    /** Returns true if BSP (Standard Subsystems Library) is detected. */
    public boolean hasBsp() {
        return libraries.stream().anyMatch(l -> "bsp".equals(l.id())); //$NON-NLS-1$
    }

    /** Returns BSP version hint if available, null otherwise. */
    public String bspVersionHint() {
        return libraries.stream()
                .filter(l -> "bsp".equals(l.id())) //$NON-NLS-1$
                .map(DetectedLibrary::versionHint)
                .findFirst().orElse(null);
    }

    /** Returns true if the specified library is detected. */
    public boolean hasLibrary(String id) {
        return libraries.stream().anyMatch(l -> id.equals(l.id()));
    }

    /** Returns true if this is an extension project. */
    public boolean isExtension() {
        return projectType == ProjectType.EXTENSION;
    }
}
