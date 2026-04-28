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
            "dcs_manage"); //$NON-NLS-1$

    private static final Set<String> EXTENSION_TOOLS = Set.of(
            "extension_manage"); //$NON-NLS-1$

    private static final Set<String> EXTERNAL_TOOLS = Set.of(
            "external_manage"); //$NON-NLS-1$

    private static final Set<String> QA_TOOLS_EXCLUDING_INIT = Set.of(
            "qa_inspect", "qa_run", //$NON-NLS-1$ //$NON-NLS-2$
            "qa_prepare_form_context", "qa_plan_scenario", //$NON-NLS-1$ //$NON-NLS-2$
            "qa_validate_feature", "author_yaxunit_tests", //$NON-NLS-1$ //$NON-NLS-2$
            "run_yaxunit_tests", "debug_yaxunit_tests"); //$NON-NLS-1$ //$NON-NLS-2$

    private static final Set<String> EDT_PROJECT_TOOLS = Set.of(
            "edt_content_assist", "edt_find_references", "edt_metadata_details", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "scan_metadata_index", "edt_diagnostics", "edt_validate_request", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "edt_field_type_candidates", "inspect_platform_reference", //$NON-NLS-1$ //$NON-NLS-2$
            "bsl_symbol_at_position", "bsl_type_at_position", "bsl_scope_members", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "bsl_list_methods", "bsl_get_method_body", "bsl_analyze_method", "bsl_module_context", "bsl_module_exports", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "create_metadata", "update_metadata", //$NON-NLS-1$ //$NON-NLS-2$
            "delete_metadata", "add_metadata_child", //$NON-NLS-1$ //$NON-NLS-2$
            "create_form", "apply_form_recipe", "inspect_form_layout", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "mutate_form_model", "ensure_module_artifact", //$NON-NLS-1$ //$NON-NLS-2$
            "get_diagnostics", //$NON-NLS-1$
            "workspace_import_project", "import_project_from_infobase", //$NON-NLS-1$ //$NON-NLS-2$
            "git_clone_and_import_project"); //$NON-NLS-1$

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
        boolean hasQaConfig = false;

        for (IProject project : projects) {
            if (!project.isOpen()) {
                continue;
            }
            // DCS detection: look for DataCompositionSchema in src/
            if (!hasDcs) {
                hasDcs = hasDcsSchema(project);
            }
            // QA config detection
            if (!hasQaConfig) {
                hasQaConfig = hasQaConfiguration(project);
            }
            // Early exit if all found
            if (hasDcs && hasQaConfig) {
                break;
            }
        }

        if (!hasDcs) {
            excluded.addAll(DCS_TOOLS);
        }
        // extension_manage and external_manage are bootstrap composite tools:
        // list/create commands must stay visible when only a base EDT project exists.
        // Command-specific validation inside the tools reports missing extension/external
        // context for operations that actually require it.
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
