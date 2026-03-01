package com.codepilot1c.core.tools.meta;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.codepilot1c.core.logging.VibeLogger;

/**
 * Registry of tool metadata used by routing logic.
 */
public final class ToolDescriptorRegistry {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(ToolDescriptorRegistry.class);

    private static ToolDescriptorRegistry instance;

    private final Map<String, ToolDescriptor> descriptors = new HashMap<>();

    private ToolDescriptorRegistry() {
        registerDefaults();
    }

    public static synchronized ToolDescriptorRegistry getInstance() {
        if (instance == null) {
            instance = new ToolDescriptorRegistry();
        }
        return instance;
    }

    public void register(ToolDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        descriptors.put(descriptor.getName(), descriptor);
    }

    public ToolDescriptor get(String name) {
        if (name == null) {
            return null;
        }
        return descriptors.get(name);
    }

    public ToolDescriptor getOrDefault(String name) {
        ToolDescriptor descriptor = get(name);
        if (descriptor != null) {
            return descriptor;
        }
        return ToolDescriptor.builder(name)
                .category(ToolCategory.OTHER)
                .mutating(false)
                .requiresValidationToken(false)
                .build();
    }

    public Collection<ToolDescriptor> getAll() {
        return Collections.unmodifiableCollection(descriptors.values());
    }

    private void registerDefaults() {
        // File tools
        register(ToolDescriptor.builder("read_file").category(ToolCategory.FILES).build()); //$NON-NLS-1$
        register(ToolDescriptor.builder("list_files").category(ToolCategory.FILES).build()); //$NON-NLS-1$
        register(ToolDescriptor.builder("edit_file").category(ToolCategory.FILES).mutating(true).build()); //$NON-NLS-1$
        register(ToolDescriptor.builder("write_file").category(ToolCategory.FILES).mutating(true).build()); //$NON-NLS-1$
        register(ToolDescriptor.builder("grep").category(ToolCategory.FILES).build()); //$NON-NLS-1$
        register(ToolDescriptor.builder("glob").category(ToolCategory.FILES).build()); //$NON-NLS-1$

        // BSL tools
        register(ToolDescriptor.builder("bsl_symbol_at_position").category(ToolCategory.BSL).build()); //$NON-NLS-1$
        register(ToolDescriptor.builder("bsl_type_at_position").category(ToolCategory.BSL).build()); //$NON-NLS-1$
        register(ToolDescriptor.builder("bsl_scope_members").category(ToolCategory.BSL).build()); //$NON-NLS-1$
        register(ToolDescriptor.builder("bsl_list_methods").category(ToolCategory.BSL).build()); //$NON-NLS-1$
        register(ToolDescriptor.builder("bsl_get_method_body").category(ToolCategory.BSL).build()); //$NON-NLS-1$

        // Metadata inspection
        register(ToolDescriptor.builder("edt_metadata_details").category(ToolCategory.METADATA).build()); //$NON-NLS-1$
        register(ToolDescriptor.builder("scan_metadata_index").category(ToolCategory.METADATA).build()); //$NON-NLS-1$
        register(ToolDescriptor.builder("edt_field_type_candidates").category(ToolCategory.METADATA).build()); //$NON-NLS-1$
        register(ToolDescriptor.builder("inspect_platform_reference").category(ToolCategory.METADATA).build()); //$NON-NLS-1$
        register(ToolDescriptor.builder("edt_find_references").category(ToolCategory.BSL).build()); //$NON-NLS-1$
        register(ToolDescriptor.builder("edt_content_assist").category(ToolCategory.BSL).build()); //$NON-NLS-1$

        // Diagnostics
        register(ToolDescriptor.builder("get_diagnostics").category(ToolCategory.DIAGNOSTICS).build()); //$NON-NLS-1$
        register(ToolDescriptor.builder("edt_trace_export").category(ToolCategory.DIAGNOSTICS).build()); //$NON-NLS-1$
        register(ToolDescriptor.builder("edt_metadata_smoke").category(ToolCategory.DIAGNOSTICS).build()); //$NON-NLS-1$
        register(ToolDescriptor.builder("edt_extension_smoke").category(ToolCategory.DIAGNOSTICS).build()); //$NON-NLS-1$
        register(ToolDescriptor.builder("edt_external_smoke").category(ToolCategory.DIAGNOSTICS).build()); //$NON-NLS-1$
        register(ToolDescriptor.builder("qa_status").category(ToolCategory.DIAGNOSTICS).build()); //$NON-NLS-1$
        register(ToolDescriptor.builder("qa_run").category(ToolCategory.DIAGNOSTICS).mutating(true).build()); //$NON-NLS-1$
        register(ToolDescriptor.builder("qa_scaffold").category(ToolCategory.FILES).mutating(true).build()); //$NON-NLS-1$
        register(ToolDescriptor.builder("qa_steps_search").category(ToolCategory.DIAGNOSTICS).build()); //$NON-NLS-1$

        // Validation
        register(ToolDescriptor.builder("edt_validate_request")
                .category(ToolCategory.METADATA)
                .build()); //$NON-NLS-1$

        // Metadata mutation tools
        registerMutation("create_metadata", ToolCategory.METADATA); //$NON-NLS-1$
        registerMutation("create_form", ToolCategory.FORMS); //$NON-NLS-1$
        registerMutation("apply_form_recipe", ToolCategory.FORMS); //$NON-NLS-1$
        registerMutation("add_metadata_child", ToolCategory.METADATA); //$NON-NLS-1$
        registerMutation("update_metadata", ToolCategory.METADATA); //$NON-NLS-1$
        registerMutation("mutate_form_model", ToolCategory.FORMS); //$NON-NLS-1$
        registerMutation("delete_metadata", ToolCategory.METADATA); //$NON-NLS-1$

        // Forms
        register(ToolDescriptor.builder("inspect_form_layout").category(ToolCategory.FORMS).build()); //$NON-NLS-1$
        register(ToolDescriptor.builder("ensure_module_artifact").category(ToolCategory.METADATA).build()); //$NON-NLS-1$

        // Extension tools
        registerMutation("extension_create_project", ToolCategory.EXTENSION); //$NON-NLS-1$
        registerMutation("extension_adopt_object", ToolCategory.EXTENSION); //$NON-NLS-1$
        registerMutation("extension_set_property_state", ToolCategory.EXTENSION); //$NON-NLS-1$
        register(ToolDescriptor.builder("extension_list_projects").category(ToolCategory.EXTENSION).build()); //$NON-NLS-1$
        register(ToolDescriptor.builder("extension_list_objects").category(ToolCategory.EXTENSION).build()); //$NON-NLS-1$

        // External tools
        registerMutation("external_create_report", ToolCategory.EXTERNAL); //$NON-NLS-1$
        registerMutation("external_create_processing", ToolCategory.EXTERNAL); //$NON-NLS-1$
        register(ToolDescriptor.builder("external_list_projects").category(ToolCategory.EXTERNAL).build()); //$NON-NLS-1$
        register(ToolDescriptor.builder("external_list_objects").category(ToolCategory.EXTERNAL).build()); //$NON-NLS-1$
        register(ToolDescriptor.builder("external_get_details").category(ToolCategory.EXTERNAL).build()); //$NON-NLS-1$

        // DCS tools
        registerMutation("dcs_create_main_schema", ToolCategory.DCS); //$NON-NLS-1$
        registerMutation("dcs_upsert_query_dataset", ToolCategory.DCS); //$NON-NLS-1$
        registerMutation("dcs_upsert_parameter", ToolCategory.DCS); //$NON-NLS-1$
        registerMutation("dcs_upsert_calculated_field", ToolCategory.DCS); //$NON-NLS-1$
        register(ToolDescriptor.builder("dcs_get_summary").category(ToolCategory.DCS).build()); //$NON-NLS-1$
        register(ToolDescriptor.builder("dcs_list_nodes").category(ToolCategory.DCS).build()); //$NON-NLS-1$

        LOG.debug("ToolDescriptorRegistry initialized with %d descriptors", descriptors.size()); //$NON-NLS-1$
    }

    private void registerMutation(String name, ToolCategory category) {
        register(ToolDescriptor.builder(name)
                .category(category)
                .mutating(true)
                .requiresValidationToken(true)
                .build());
    }
}
