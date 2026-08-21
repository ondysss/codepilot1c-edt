/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.profiles;

import java.util.HashSet;
import java.util.Set;

/** Authoritative static tool capability matrix for GSD phase profiles. */
public final class GsdProfileCapabilities {

    private static final Set<String> BASE_READ_TOOLS = Set.of(
            "read_file", "glob", "grep", "list_files", "git_inspect", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            "get_diagnostics", "get_bookmarks", "get_tasks", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "edt_content_assist", "edt_find_references", "edt_metadata_details", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "scan_metadata_index", "edt_get_configuration_properties", //$NON-NLS-1$ //$NON-NLS-2$
            "edt_get_problem_summary", "edt_get_tags", "edt_get_objects_by_tags", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "edt_list_modules", "edt_get_module_structure", "edt_search_in_code", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "edt_get_method_call_hierarchy", "edt_get_project_call_graph", //$NON-NLS-1$ //$NON-NLS-2$
            "edt_go_to_definition", "edt_get_symbol_info", "inspect_form_layout", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "bsl_symbol_at_position", "bsl_type_at_position", "bsl_scope_members", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "bsl_list_methods", "bsl_get_method_body", "bsl_analyze_method", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "bsl_module_context", "bsl_module_exports", "inspect_platform_reference", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            "skill", "discover_tools"); //$NON-NLS-1$ //$NON-NLS-2$

    private GsdProfileCapabilities() {
    }

    /** Returns the immutable static tool set for one registered GSD phase. */
    public static Set<String> allowedTools(String phaseId) {
        Set<String> result = new HashSet<>(BASE_READ_TOOLS);
        switch (phaseId) {
            case "gsd-discuss" -> add(result, "gsd_get_state", "gsd_record_decision", "gsd_transition"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "gsd-plan" -> add(result, "gsd_get_state", "gsd_create_plan", "gsd_transition"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            case "gsd-execute" -> add(result, //$NON-NLS-1$
                    "gsd_get_state", "gsd_update_task", "gsd_record_evidence", "gsd_transition", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    "edt_validate_request", "edit_file", "write_file", "ensure_module_artifact", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    "create_metadata", "create_form", "add_metadata_child", "update_metadata", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    "mutate_form_model", "delete_metadata", "remember_fact"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "gsd-verify" -> add(result, //$NON-NLS-1$
                    "gsd_get_state", "gsd_record_evidence", //$NON-NLS-1$ //$NON-NLS-2$
                    "gsd_record_verification_outcome", "gsd_transition", //$NON-NLS-1$ //$NON-NLS-2$
                    "inspect_role_rights", "inspect_template", "java_compile_probe", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "qa_validate_feature", "validate_query"); //$NON-NLS-1$ //$NON-NLS-2$
            case "gsd-ship" -> add(result, "gsd_get_state", "gsd_record_shipment", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    "gsd_transition", //$NON-NLS-1$
                    "git_mutate", "write_file", "remember_fact"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            default -> throw new IllegalArgumentException("Unknown GSD phase: " + phaseId); //$NON-NLS-1$
        }
        return Set.copyOf(result);
    }

    private static void add(Set<String> target, String... names) {
        target.addAll(Set.of(names));
    }
}
