package com.codepilot1c.core.agent.graph;

import com.codepilot1c.core.tools.meta.ToolCategory;
import com.codepilot1c.core.tools.meta.ToolDescriptor;
import com.codepilot1c.core.tools.meta.ToolDescriptorRegistry;

final class ToolGraphTestSupport {

    private ToolGraphTestSupport() {
    }

    static ToolGraphRouter createRouter() {
        return new ToolGraphRouter(
                ToolGraphRegistry.getInstance(),
                new KeywordToolGraphSelectionStrategy(),
                createDescriptorRegistry());
    }

    private static ToolDescriptorRegistry createDescriptorRegistry() {
        ToolDescriptorRegistry registry = ToolDescriptorRegistry.createDetached();
        register(registry, "create_metadata", ToolCategory.METADATA, true); //$NON-NLS-1$
        register(registry, "add_metadata_child", ToolCategory.METADATA, true); //$NON-NLS-1$
        register(registry, "update_metadata", ToolCategory.METADATA, true); //$NON-NLS-1$
        register(registry, "delete_metadata", ToolCategory.METADATA, true); //$NON-NLS-1$
        register(registry, "create_form", ToolCategory.FORMS, true); //$NON-NLS-1$
        register(registry, "apply_form_recipe", ToolCategory.FORMS, true); //$NON-NLS-1$
        register(registry, "mutate_form_model", ToolCategory.FORMS, true); //$NON-NLS-1$
        register(registry, "ensure_module_artifact", ToolCategory.METADATA, true); //$NON-NLS-1$
        register(registry, "dcs_create_main_schema", ToolCategory.DCS, true); //$NON-NLS-1$
        register(registry, "dcs_upsert_query_dataset", ToolCategory.DCS, true); //$NON-NLS-1$
        register(registry, "dcs_upsert_parameter", ToolCategory.DCS, true); //$NON-NLS-1$
        register(registry, "dcs_upsert_calculated_field", ToolCategory.DCS, true); //$NON-NLS-1$
        return registry;
    }

    private static void register(ToolDescriptorRegistry registry, String name, ToolCategory category,
            boolean requiresValidationToken) {
        registry.register(ToolDescriptor.builder(name)
                .category(category)
                .mutating(requiresValidationToken)
                .requiresValidationToken(requiresValidationToken)
                .build());
    }
}
