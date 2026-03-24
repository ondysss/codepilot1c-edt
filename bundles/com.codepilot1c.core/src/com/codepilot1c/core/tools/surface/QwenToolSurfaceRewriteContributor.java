/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.surface;

import com.codepilot1c.core.model.ToolDefinition;

/**
 * Rewrites the model-facing backend tool surface with Qwen-specific descriptions.
 */
public final class QwenToolSurfaceRewriteContributor implements ToolSurfaceContributor {

    @Override
    public boolean supports(ToolSurfaceContext context) {
        return context != null && context.isBuiltIn() && context.isBackendSelectedInUi();
    }

    @Override
    public void contribute(ToolSurfaceContext context, ToolDefinition.Builder builder) {
        String name = builder.getName();
        String description = overrideDescription(name);
        if (description != null) {
            builder.description(description);
        }
        builder.parametersSchema(ToolSurfaceSchemaNormalizer.normalizeBuiltIn(name, builder.getParametersSchema()));
    }

    @Override
    public int getOrder() {
        return -200;
    }

    private String overrideDescription(String toolName) {
        return switch (toolName) {
            case "read_file" -> "Read an existing workspace file or a 1-based line range. Use it for exact source inspection after discovery and keep paths workspace-relative."; //$NON-NLS-1$
            case "list_files" -> "List projects or directory contents in the workspace. Use it for pathname discovery, not for text search or semantic symbol lookup."; //$NON-NLS-1$
            case "glob" -> "Find files by glob pattern under the workspace or a subdirectory. Use it for pathname discovery before read_file."; //$NON-NLS-1$
            case "grep" -> "Search plain text or regex across workspace files. Use it for string occurrences only; prefer EDT semantic tools for symbols, metadata, and platform behavior."; //$NON-NLS-1$
            case "edit_file" -> "Edit an existing workspace file in place via full replace, targeted search/replace, or SEARCH/REPLACE blocks. Do not use it to create files or mutate EDT metadata descriptors unless an explicit emergency override is intended."; //$NON-NLS-1$
            case "write_file" -> "Overwrite an existing workspace file with full content. Prefer edit_file for narrow patches and ensure_module_artifact before touching metadata-owned BSL modules."; //$NON-NLS-1$
            case "workspace_import_project" -> "Import an existing Eclipse/EDT project directory into the current workspace. Inspect repository and project state first, then import only when a .project-based project already exists."; //$NON-NLS-1$
            case "git_inspect" -> "Read git state through allowlisted inspection operations such as status, diff, log, branch, and show. Use it before any mutating git step and keep repo_path explicit."; //$NON-NLS-1$
            case "git_mutate" -> "Perform allowlisted git mutations such as clone, checkout, branch, add, commit, fetch, pull, and push. Use it only when the repository state and intended mutation are already explicit."; //$NON-NLS-1$
            case "git_clone_and_import_project" -> "Clone a git repository and import an existing Eclipse/EDT project from it into the workspace. Prefer it only when both clone and workspace import are required in one step."; //$NON-NLS-1$
            case "import_project_from_infobase" -> "Create a new EDT project by exporting configuration from the infobase associated with an existing EDT project. Use dry_run first when runtime or infobase availability is uncertain."; //$NON-NLS-1$
            case "edt_content_assist" -> "Return EDT AST-aware content assist for a BSL position. Prefer it over grep when you need semantic completions or symbol-aware editing help."; //$NON-NLS-1$
            case "edt_find_references" -> "Find semantic references for metadata objects or EDT-resolved symbols. Prefer it over raw text search for usage questions."; //$NON-NLS-1$
            case "edt_metadata_details" -> "Read structured EDT metadata details for one or more object FQNs. Use it for configuration structure, not for platform-language reference."; //$NON-NLS-1$
            case "scan_metadata_index" -> "List top-level metadata objects in an EDT configuration with scope and name filters. Use it before deeper metadata inspection or mutation."; //$NON-NLS-1$
            case "edt_field_type_candidates" -> "List valid EDT type candidates for a metadata field such as type. Use it to resolve diagnostics about missing or invalid types."; //$NON-NLS-1$
            case "inspect_platform_reference" -> "Read EDT platform-language documentation for builtin types, methods, and properties. Use it for platform API questions, not configuration metadata."; //$NON-NLS-1$
            case "bsl_symbol_at_position" -> "Resolve the semantic BSL symbol at a source position, including its kind and owning container."; //$NON-NLS-1$
            case "bsl_type_at_position" -> "Resolve the inferred BSL type at a source position. Use it instead of guessing expression types from text."; //$NON-NLS-1$
            case "bsl_scope_members" -> "List members available in scope at a BSL position. Use it when you need semantic completion candidates or visible API surface."; //$NON-NLS-1$
            case "bsl_list_methods" -> "List methods for a platform type or EDT-resolved semantic type. Prefer it over manual platform-doc scanning when you already know the target type."; //$NON-NLS-1$
            case "bsl_get_method_body" -> "Read the body of a resolved BSL method or procedure declaration when the EDT semantic layer can locate it."; //$NON-NLS-1$
            case "bsl_analyze_method" -> "Analyze one BSL method for complexity, call graph, unused parameters, and risky flow patterns."; //$NON-NLS-1$
            case "bsl_module_context" -> "Read module-level BSL context: module type, owner, default pragmas, and method counts."; //$NON-NLS-1$
            case "bsl_module_exports" -> "List exported procedures and functions of one BSL module with signatures and line ranges."; //$NON-NLS-1$
            case "edt_validate_request" -> "Validate a pending metadata, form, extension, external-object, DCS, or module-artifact mutation and issue a one-time validation_token. Call it immediately before the mutating tool and pass the validated payload unchanged."; //$NON-NLS-1$
            case "create_form" -> "Create a managed form through EDT BM APIs for a specific owner object. Re-run diagnostics after creation and fix type-related warnings instead of ignoring them."; //$NON-NLS-1$
            case "add_metadata_child" -> "Create a child metadata object such as an attribute, tabular section, or command under an existing metadata parent through EDT BM APIs. Respect reserved-name guardrails before adding attributes."; //$NON-NLS-1$
            case "ensure_module_artifact" -> "Ensure that a metadata-owned BSL module artifact exists and return its path. Use it after edt_validate_request and before edit_file or write_file when changing object-owned module code."; //$NON-NLS-1$
            case "update_metadata" -> "Apply BM-model property changes to an existing metadata object. Follow success with diagnostics and explicit remediation of remaining warnings."; //$NON-NLS-1$
            case "mutate_form_model" -> "Apply low-level managed-form operations to an existing form model. Inspect the form layout first and follow structural edits with diagnostics."; //$NON-NLS-1$
            case "delete_metadata" -> "Delete a metadata object through EDT BM APIs with an explicit validation_token. Use recursive or force only when the request truly requires it."; //$NON-NLS-1$
            case "apply_form_recipe" -> "Apply a higher-level managed-form recipe that can create or locate a form, upsert attributes, and mutate layout. Prefer it over low-level mutate_form_model when the intended change fits the recipe flow."; //$NON-NLS-1$
            case "inspect_form_layout" -> "Inspect managed-form structure headlessly through EDT BM APIs. Use it before form mutations to locate element ids, data paths, and layout nodes."; //$NON-NLS-1$
            // QA tools (still registered individually)
            case "qa_prepare_form_context" -> "Prepare structured QA context for a managed form, including any required pre-validation or form creation steps."; //$NON-NLS-1$
            case "qa_plan_scenario" -> "Generate a QA scenario plan from the prepared context and requested intent. Use it before compiling or running a feature."; //$NON-NLS-1$
            case "qa_validate_feature" -> "Validate generated QA feature assets and surface actionable defects before execution."; //$NON-NLS-1$
            case "qa_run" -> "Run the prepared QA scenario and return machine-readable execution results. Use it only after config, context, and feature validation are complete."; //$NON-NLS-1$
            case "author_yaxunit_tests" -> "Generate or update YAxUnit tests for a metadata object or module in a validated QA-oriented authoring flow."; //$NON-NLS-1$
            // Smoke tools (still registered individually, gated by ToolContextGate)
            case "edt_extension_smoke" -> "Run smoke verification focused on EDT extension workflows and report the exact failing stage."; //$NON-NLS-1$
            case "edt_external_smoke" -> "Run smoke verification focused on EDT external-object workflows and report the exact failing stage."; //$NON-NLS-1$
            // Composite tools
            case "dcs_manage" -> "Manage DCS schemas: get_summary, list_nodes, create_schema, upsert_dataset, upsert_param, upsert_field. Use command parameter."; //$NON-NLS-1$
            case "extension_manage" -> "Manage EDT extensions: list_projects, list_objects, create, adopt, set_state. Use command parameter."; //$NON-NLS-1$
            case "external_manage" -> "Manage EDT external objects: list_projects, list_objects, details, create_report, create_processing. Use command parameter."; //$NON-NLS-1$
            case "edt_diagnostics" -> "EDT diagnostics: metadata_smoke, trace_export, analyze_error, update_infobase, launch_app. Use command parameter."; //$NON-NLS-1$
            case "qa_inspect" -> "Inspect QA state: explain_config, status, steps_search. Use command parameter."; //$NON-NLS-1$
            case "qa_generate" -> "Generate QA assets: init_config, migrate_config, compile_feature. Use command parameter."; //$NON-NLS-1$
            case "discover_tools" -> "Discover domain tools by category. Use before calling domain-specific tools."; //$NON-NLS-1$
            default -> null;
        };
    }
}
