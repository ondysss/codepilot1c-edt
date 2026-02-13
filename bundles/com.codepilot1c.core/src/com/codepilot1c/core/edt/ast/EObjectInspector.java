package com.codepilot1c.core.edt.ast;

import java.util.Map;

import com.codepilot1c.core.edt.ast.MetadataNode.FormatStyle;

/**
 * Heuristic inspector inspired by EDT-MCP EObjectInspector.
 */
public final class EObjectInspector {

    private EObjectInspector() {
    }

    public static FormatStyle chooseFormatStyle(MetadataNode node) {
        if (node == null) {
            return FormatStyle.SIMPLE_VALUE;
        }
        if (!node.getChildren().isEmpty()) {
            return FormatStyle.EXPAND;
        }

        Map<String, Object> properties = node.getProperties();
        int meaningful = 0;
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            if (entry.getValue() != null && !String.valueOf(entry.getValue()).isBlank()) {
                meaningful++;
            }
        }

        if (meaningful <= 2) {
            return FormatStyle.SIMPLE_VALUE;
        }
        if (node.getPath() != null && node.getPath().contains(".")) { //$NON-NLS-1$
            return FormatStyle.REFERENCE;
        }
        return FormatStyle.EXPAND;
    }
}
