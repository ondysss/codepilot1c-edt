/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import java.util.HashSet;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.Bundle;

/**
 * Context-aware tool gating: excludes tools that are irrelevant
 * for the current workspace state.
 *
 * <p>Scans workspace projects to determine which tool families
 * are applicable. Results are cached with a configurable TTL.</p>
 */
public class ToolContextGate {

    private static final String PLUGIN_ID = "com.codepilot1c.core"; //$NON-NLS-1$

    private static final long CACHE_TTL_MS = 5 * 60 * 1000L; // 5 minutes

    private static final Set<String> DCS_TOOLS = Set.of(
            "dcs_get_summary", "dcs_list_nodes", "dcs_create_main_schema", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "dcs_upsert_query_dataset", "dcs_upsert_parameter", "dcs_upsert_calculated_field"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static final Set<String> EXTENSION_TOOLS = Set.of(
            "extension_list_projects", "extension_list_objects", "extension_create_project", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "extension_adopt_object", "extension_set_property_state", "edt_extension_smoke"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static final Set<String> EXTERNAL_TOOLS = Set.of(
            "external_list_projects", "external_list_objects", "external_get_details", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "external_create_report", "external_create_processing", "edt_external_smoke"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static final Set<String> QA_TOOLS_EXCLUDING_INIT = Set.of(
            "qa_explain_config", "qa_status", "qa_migrate_config", "qa_run", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "qa_prepare_form_context", "qa_plan_scenario", "qa_compile_feature", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "qa_validate_feature", "qa_steps_search", "author_yaxunit_tests"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    private static final Set<String> EDT_PROJECT_TOOLS = Set.of(
            "edt_content_assist", "edt_find_references", "edt_metadata_details", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "scan_metadata_index", "edt_metadata_smoke", "edt_validate_request", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "edt_field_type_candidates", "inspect_platform_reference", //$NON-NLS-1$ //$NON-NLS-2$
            "bsl_symbol_at_position", "bsl_type_at_position", "bsl_scope_members", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "bsl_list_methods", "bsl_get_method_body", "bsl_module_context", "bsl_module_exports", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "create_metadata", "update_metadata", "update_metadata_properties", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "delete_metadata", "delete_metadata_object", "add_metadata_child", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "create_form", "apply_form_recipe", "inspect_form_layout", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "mutate_form_model", "ensure_module_artifact", //$NON-NLS-1$ //$NON-NLS-2$
            "get_diagnostics", "edt_trace_export", //$NON-NLS-1$ //$NON-NLS-2$
            "workspace_import_project", "import_project_from_infobase", //$NON-NLS-1$ //$NON-NLS-2$
            "git_clone_and_import_project", //$NON-NLS-1$
            "edt_update_infobase", "edt_launch_app"); //$NON-NLS-1$ //$NON-NLS-2$

    // Cache
    private volatile Set<String> cachedExcluded;
    private volatile long cacheTimestamp;

    /**
     * Computes the set of tool names to exclude based on current workspace state.
     * Results are cached for {@link #CACHE_TTL_MS} milliseconds.
     *
     * @return set of tool names to exclude (never null)
     */
    public Set<String> computeExcludedTools() {
        long now = System.currentTimeMillis();
        Set<String> cached = this.cachedExcluded;
        if (cached != null && (now - cacheTimestamp) < CACHE_TTL_MS) {
            return cached;
        }
        Set<String> excluded = computeExcludedToolsUncached();
        this.cachedExcluded = excluded;
        this.cacheTimestamp = now;
        if (!excluded.isEmpty()) {
            logInfo(String.format("ToolContextGate: excluding %d tools based on workspace state", //$NON-NLS-1$
                    excluded.size()));
        }
        return excluded;
    }

    /**
     * Invalidates the cache, forcing a re-scan on next call.
     */
    public void invalidateCache() {
        this.cachedExcluded = null;
        this.cacheTimestamp = 0;
    }

    private Set<String> computeExcludedToolsUncached() {
        Set<String> excluded = new HashSet<>();

        IProject[] projects;
        try {
            IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
            projects = root.getProjects();
        } catch (IllegalStateException e) {
            // Workspace not available (e.g., headless mode)
            return Set.of();
        }

        if (projects == null || projects.length == 0) {
            // No open projects — exclude all EDT-specific tools
            excluded.addAll(EDT_PROJECT_TOOLS);
            excluded.addAll(DCS_TOOLS);
            excluded.addAll(EXTENSION_TOOLS);
            excluded.addAll(EXTERNAL_TOOLS);
            excluded.addAll(QA_TOOLS_EXCLUDING_INIT);
            return excluded;
        }

        boolean hasDcs = false;
        boolean hasExtension = false;
        boolean hasExternal = false;
        boolean hasQaConfig = false;

        for (IProject project : projects) {
            if (!project.isOpen()) {
                continue;
            }
            // DCS detection: look for DataCompositionSchema in src/
            if (!hasDcs) {
                hasDcs = hasDcsSchema(project);
            }
            // Extension detection: check project nature or naming convention
            if (!hasExtension) {
                hasExtension = isExtensionProject(project);
            }
            // External reports/processing detection
            if (!hasExternal) {
                hasExternal = isExternalProject(project);
            }
            // QA config detection
            if (!hasQaConfig) {
                hasQaConfig = hasQaConfiguration(project);
            }
            // Early exit if all found
            if (hasDcs && hasExtension && hasExternal && hasQaConfig) {
                break;
            }
        }

        if (!hasDcs) {
            excluded.addAll(DCS_TOOLS);
        }
        if (!hasExtension) {
            excluded.addAll(EXTENSION_TOOLS);
        }
        if (!hasExternal) {
            excluded.addAll(EXTERNAL_TOOLS);
        }
        if (!hasQaConfig) {
            excluded.addAll(QA_TOOLS_EXCLUDING_INIT);
        }

        return excluded;
    }

    private boolean hasDcsSchema(IProject project) {
        // DCS schemas live in src/<ConfigName>/<ObjectType>/<Name>/Ext/MainDataCompositionSchema.xml
        // Quick heuristic: check common locations for Reports and DataProcessors
        IFolder src = project.getFolder("src"); //$NON-NLS-1$
        if (!src.exists()) {
            return false;
        }
        // Check Reports and DataProcessors folders — most common DCS locations
        for (String container : new String[] {"Reports", "DataProcessors"}) { //$NON-NLS-1$ //$NON-NLS-2$
            IFolder containerFolder = src.getFolder(container);
            if (containerFolder.exists()) {
                try {
                    for (org.eclipse.core.resources.IResource member : containerFolder.members()) {
                        if (member instanceof IFolder objFolder) {
                            IFolder extFolder = objFolder.getFolder("Ext"); //$NON-NLS-1$
                            if (extFolder.exists()) {
                                IFile dcsFile = extFolder.getFile("MainDataCompositionSchema.xml"); //$NON-NLS-1$
                                if (dcsFile.exists()) {
                                    return true;
                                }
                            }
                        }
                    }
                } catch (org.eclipse.core.runtime.CoreException e) {
                    // ignore, continue checking
                }
            }
        }
        return false;
    }

    private boolean isExtensionProject(IProject project) {
        // Extension projects typically have a specific nature or contain
        // Configuration.Extension.xml or similar markers
        IFile extensionMarker = project.getFile("src/Configuration/Configuration.Extension.mdo"); //$NON-NLS-1$
        return extensionMarker.exists();
    }

    private boolean isExternalProject(IProject project) {
        // External report/processing projects have ExternalReport.mdo or ExternalDataProcessor.mdo
        IFile reportMarker = project.getFile("src/ExternalReports/ExternalReport.mdo"); //$NON-NLS-1$
        IFile procMarker = project.getFile("src/ExternalDataProcessors/ExternalDataProcessor.mdo"); //$NON-NLS-1$
        return reportMarker.exists() || procMarker.exists();
    }

    private boolean hasQaConfiguration(IProject project) {
        // Default QA config location
        IFile qaConfig = project.getFile("tests/qa/qa-config.json"); //$NON-NLS-1$
        if (qaConfig.exists()) {
            return true;
        }
        // Alternative: .qa/ directory in project root
        IFolder qaDir = project.getFolder(".qa"); //$NON-NLS-1$
        return qaDir.exists();
    }

    private static void logInfo(String message) {
        try {
            Bundle bundle = Platform.getBundle(PLUGIN_ID);
            if (bundle != null) {
                Platform.getLog(bundle).log(new Status(IStatus.INFO, PLUGIN_ID, message));
            }
        } catch (RuntimeException ignored) {
            // Plain JUnit execution can run without an initialized OSGi bundle.
        }
    }
}
