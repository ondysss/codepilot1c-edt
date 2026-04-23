package com.codepilot1c.core.edt.forms;

import java.util.List;
import java.util.Map;

/**
 * Result of headless managed form layout inspection.
 */
public record InspectFormLayoutResult(
        String projectName,
        String formFqn,
        String formName,
        Map<String, Object> formProperties,
        int totalItems,
        boolean truncated,
        String mutationHint,
        List<FormItemNode> items,
        List<FormCommandNode> commands
) {
    /**
     * Constructor without mutationHint and commands for backward compatibility.
     */
    public InspectFormLayoutResult(
            String projectName,
            String formFqn,
            String formName,
            Map<String, Object> formProperties,
            int totalItems,
            boolean truncated,
            List<FormItemNode> items) {
        this(projectName, formFqn, formName, formProperties, totalItems, truncated, null, items, List.of());
    }

    /**
     * Constructor without commands for backward compatibility.
     */
    public InspectFormLayoutResult(
            String projectName,
            String formFqn,
            String formName,
            Map<String, Object> formProperties,
            int totalItems,
            boolean truncated,
            String mutationHint,
            List<FormItemNode> items) {
        this(projectName, formFqn, formName, formProperties, totalItems, truncated, mutationHint, items, List.of());
    }

    /**
     * Form item node representation.
     */
    public record FormItemNode(
            int id,
            Integer parentId,
            int indexInParent,
            String path,
            String name,
            String kind,
            Map<String, String> title,
            Boolean visible,
            Boolean enabled,
            Boolean readOnly,
            String dataPath,
            String fieldType,
            String commandRef,
            Map<String, Object> properties,
            List<FormItemNode> children
    ) {
        /**
         * Constructor without commandRef for backward compatibility.
         */
        public FormItemNode(
                int id,
                Integer parentId,
                int indexInParent,
                String path,
                String name,
                String kind,
                Map<String, String> title,
                Boolean visible,
                Boolean enabled,
                Boolean readOnly,
                String dataPath,
                String fieldType,
                Map<String, Object> properties,
                List<FormItemNode> children) {
            this(id, parentId, indexInParent, path, name, kind, title,
                    visible, enabled, readOnly, dataPath, fieldType, null, properties, children);
        }
    }

    /**
     * Form command node representation.
     */
    public record FormCommandNode(
            int id,
            String name,
            Map<String, String> title,
            String action
    ) {
    }
}
