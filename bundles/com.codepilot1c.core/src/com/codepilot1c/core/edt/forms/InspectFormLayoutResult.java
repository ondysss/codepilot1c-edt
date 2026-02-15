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
        List<FormItemNode> items
) {
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
            Map<String, Object> properties,
            List<FormItemNode> children
    ) {
    }
}
