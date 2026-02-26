package com.codepilot1c.core.agent.graph;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Built-in tool graph definitions.
 */
final class ToolGraphDefinitions {

    private ToolGraphDefinitions() {
    }

    static ToolGraph createGeneralGraph() {
        ToolNode general = ToolNode.builder("general") //$NON-NLS-1$
                .restrictive(false)
                .maxVisits(50)
                .build();

        Map<String, ToolNode> nodes = new HashMap<>();
        nodes.put(general.getId(), general);

        return new ToolGraph(
                ToolGraphRegistry.GENERAL_GRAPH_ID,
                "General", //$NON-NLS-1$
                "1", //$NON-NLS-1$
                general.getId(),
                nodes,
                List.of(new ToolEdge(general.getId(), general.getId(), EdgePredicates.always(), 0))
        );
    }

    static ToolGraph createBslGraph() {
        Set<String> allowed = Set.of(
                "read_file", "list_files", "edit_file", "write_file", "grep", "glob", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                "bsl_symbol_at_position", "bsl_type_at_position", "bsl_scope_members", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "bsl_list_methods", "bsl_get_method_body", //$NON-NLS-1$ //$NON-NLS-2$
                "edt_find_references", "edt_content_assist", //$NON-NLS-1$ //$NON-NLS-2$
                "edt_metadata_details", "scan_metadata_index", "inspect_platform_reference", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "ensure_module_artifact", "get_diagnostics" //$NON-NLS-1$ //$NON-NLS-2$
        );

        ToolNode node = ToolNode.builder("bsl_main") //$NON-NLS-1$
                .allowTools(allowed)
                .maxVisits(20)
                .build();

        Map<String, ToolNode> nodes = new HashMap<>();
        nodes.put(node.getId(), node);

        return new ToolGraph(
                ToolGraphRegistry.BSL_GRAPH_ID,
                "BSL", //$NON-NLS-1$
                "1", //$NON-NLS-1$
                node.getId(),
                nodes,
                List.of(new ToolEdge(node.getId(), node.getId(), EdgePredicates.always(), 0))
        );
    }

    static ToolGraph createMetadataGraph() {
        Set<String> inspectTools = Set.of(
                "scan_metadata_index", "edt_metadata_details", "edt_field_type_candidates", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "inspect_platform_reference", "edt_find_references" //$NON-NLS-1$ //$NON-NLS-2$
        );
        Set<String> mutateTools = Set.of(
                "create_metadata", "add_metadata_child", "update_metadata", "delete_metadata" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        );
        Set<String> diagTools = Set.of("get_diagnostics", "edt_trace_export"); //$NON-NLS-1$ //$NON-NLS-2$

        ToolNode inspect = ToolNode.builder("metadata_inspect") //$NON-NLS-1$
                .allowTools(inspectTools)
                .allowTool("edt_validate_request") //$NON-NLS-1$
                .maxVisits(10)
                .build();

        ToolNode mutate = ToolNode.builder("metadata_mutate") //$NON-NLS-1$
                .allowTools(mutateTools)
                .allowTool("ensure_module_artifact") //$NON-NLS-1$
                .allowTool("edt_validate_request") //$NON-NLS-1$
                .maxVisits(10)
                .build();

        ToolNode diagnostics = ToolNode.builder("metadata_diagnostics") //$NON-NLS-1$
                .allowTools(diagTools)
                .allowTools(inspectTools)
                .maxVisits(10)
                .build();

        Map<String, ToolNode> nodes = new HashMap<>();
        nodes.put(inspect.getId(), inspect);
        nodes.put(mutate.getId(), mutate);
        nodes.put(diagnostics.getId(), diagnostics);

        List<ToolEdge> edges = List.of(
                new ToolEdge(inspect.getId(), mutate.getId(),
                        EdgePredicates.and(
                                EdgePredicates.toolNameIs("edt_validate_request"), //$NON-NLS-1$
                                EdgePredicates.success()),
                        10),
                new ToolEdge(mutate.getId(), diagnostics.getId(),
                        EdgePredicates.and(
                                EdgePredicates.toolNameIn(mutateTools),
                                EdgePredicates.success()),
                        10)
        );

        return new ToolGraph(
                ToolGraphRegistry.METADATA_GRAPH_ID,
                "Metadata", //$NON-NLS-1$
                "1", //$NON-NLS-1$
                inspect.getId(),
                nodes,
                edges
        );
    }

    static ToolGraph createFormsGraph() {
        Set<String> inspectTools = Set.of(
                "inspect_form_layout", "edt_metadata_details", "scan_metadata_index" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        );
        Set<String> mutateTools = Set.of(
                "create_form", "apply_form_recipe", "mutate_form_model" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        );
        Set<String> diagTools = Set.of("get_diagnostics"); //$NON-NLS-1$

        ToolNode inspect = ToolNode.builder("form_inspect") //$NON-NLS-1$
                .allowTools(inspectTools)
                .allowTool("edt_validate_request") //$NON-NLS-1$
                .maxVisits(10)
                .build();

        ToolNode mutate = ToolNode.builder("form_mutate") //$NON-NLS-1$
                .allowTools(mutateTools)
                .allowTool("edt_validate_request") //$NON-NLS-1$
                .maxVisits(10)
                .build();

        ToolNode diagnostics = ToolNode.builder("form_diagnostics") //$NON-NLS-1$
                .allowTools(diagTools)
                .allowTools(inspectTools)
                .maxVisits(10)
                .build();

        Map<String, ToolNode> nodes = new HashMap<>();
        nodes.put(inspect.getId(), inspect);
        nodes.put(mutate.getId(), mutate);
        nodes.put(diagnostics.getId(), diagnostics);

        List<ToolEdge> edges = List.of(
                new ToolEdge(inspect.getId(), mutate.getId(),
                        EdgePredicates.and(
                                EdgePredicates.toolNameIs("edt_validate_request"), //$NON-NLS-1$
                                EdgePredicates.success()),
                        10),
                new ToolEdge(mutate.getId(), diagnostics.getId(),
                        EdgePredicates.and(
                                EdgePredicates.toolNameIn(mutateTools),
                                EdgePredicates.success()),
                        10)
        );

        return new ToolGraph(
                ToolGraphRegistry.FORMS_GRAPH_ID,
                "Forms", //$NON-NLS-1$
                "1", //$NON-NLS-1$
                inspect.getId(),
                nodes,
                edges
        );
    }
}
