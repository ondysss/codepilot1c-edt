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
        List<FormCommandNode> commands,
        List<CommandBarNode> commandBars
) {
    /**
     * Constructor without mutationHint, commands and commandBars for backward compatibility.
     */
    public InspectFormLayoutResult(
            String projectName,
            String formFqn,
            String formName,
            Map<String, Object> formProperties,
            int totalItems,
            boolean truncated,
            List<FormItemNode> items) {
        this(projectName, formFqn, formName, formProperties, totalItems, truncated, null, items, List.of(), List.of());
    }

    /**
     * Constructor without commands and commandBars for backward compatibility.
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
        this(projectName, formFqn, formName, formProperties, totalItems, truncated, mutationHint, items, List.of(), List.of());
    }

    /**
     * Constructor without commandBars for backward compatibility.
     */
    public InspectFormLayoutResult(
            String projectName,
            String formFqn,
            String formName,
            Map<String, Object> formProperties,
            int totalItems,
            boolean truncated,
            String mutationHint,
            List<FormItemNode> items,
            List<FormCommandNode> commands) {
        this(projectName, formFqn, formName, formProperties, totalItems, truncated, mutationHint, items, commands, List.of());
    }

    /**
     * Event handler surfaced on a form node (form root or a {@code FormItemNode}).
     */
    public record EventHandlerInfo(String event, String handlerName, String callType) {

        /** Constructor retained for native handler callers and binary/source compatibility. */
        public EventHandlerInfo(String event, String handlerName) {
            this(event, handlerName, null);
        }
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
            List<EventHandlerInfo> eventHandlers,
            Map<String, Object> properties,
            List<FormItemNode> children
    ) {
        /**
         * Constructor without commandRef and eventHandlers for backward compatibility.
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
                    visible, enabled, readOnly, dataPath, fieldType, null, List.of(), properties, children);
        }

        /**
         * Constructor without eventHandlers for backward compatibility (commandRef present).
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
                String commandRef,
                Map<String, Object> properties,
                List<FormItemNode> children) {
            this(id, parentId, indexInParent, path, name, kind, title,
                    visible, enabled, readOnly, dataPath, fieldType, commandRef, List.of(), properties, children);
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

    /**
     * Built-in, reference-backed command bar state discovered on the form root or a
     * {@code CommandBarHolder} item (e.g. a list {@code Table}). Distinct from ordinary
     * {@link FormItemNode} entries because {@code autoCommandBar}/{@code excludedCommands}
     * are EReferences outside {@code FormItemContainer.getItems()} and are rejected by the
     * generic set_form_props/set_item reflection path.
     */
    public record CommandBarNode(
            Integer ownerItemId,
            String ownerItemName,
            String ownerKind,
            String kind,
            Boolean autoFill,
            List<String> excludedCommands,
            List<String> availableCommands,
            List<FormItemNode> items
    ) {
    }
}
