package com.codepilot1c.core.edt.ast;

import java.util.Map;

/**
 * Renders structured EDT AST responses to markdown.
 */
public class MarkdownRenderer {

    public String renderReferences(ReferenceSearchResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("# References: ").append(result.getObjectFqn()).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("Engine: `").append(result.getEngine()).append("`\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        if (result.getReferences().isEmpty()) {
            sb.append("No references found.\n"); //$NON-NLS-1$
            return sb.toString();
        }

        sb.append("| Category | Path | Line | Snippet |\n"); //$NON-NLS-1$
        sb.append("|---|---|---:|---|\n"); //$NON-NLS-1$
        for (ReferenceSearchResult.ReferenceItem item : result.getReferences()) {
            sb.append("| ").append(escape(item.getCategory())).append(" | ") //$NON-NLS-1$ //$NON-NLS-2$
                    .append(escape(item.getPath())).append(" | ") //$NON-NLS-1$
                    .append(item.getLine()).append(" | ") //$NON-NLS-1$
                    .append(escape(item.getSnippet())).append(" |\n"); //$NON-NLS-1$
        }
        return sb.toString();
    }

    public String renderMetadata(MetadataDetailsResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Metadata Details: ").append(result.getProjectName()).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("Engine: `").append(result.getEngine()).append("`\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        for (MetadataNode node : result.getNodes()) {
            sb.append("## ").append(escape(node.getType())).append(": ") //$NON-NLS-1$ //$NON-NLS-2$
                    .append(escape(node.getName())).append("\n\n"); //$NON-NLS-1$
            if (node.getPath() != null) {
                sb.append("Path: `").append(escape(node.getPath())).append("`\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            sb.append("Format: `").append(node.getFormatStyle()).append("`\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

            if (!node.getProperties().isEmpty()) {
                sb.append("| Property | Value |\n"); //$NON-NLS-1$
                sb.append("|---|---|\n"); //$NON-NLS-1$
                for (Map.Entry<String, Object> entry : node.getProperties().entrySet()) {
                    sb.append("| ").append(escape(entry.getKey())).append(" | ") //$NON-NLS-1$ //$NON-NLS-2$
                            .append(escape(String.valueOf(entry.getValue()))).append(" |\n"); //$NON-NLS-1$
                }
                sb.append("\n"); //$NON-NLS-1$
            }

            if (!node.getChildren().isEmpty()) {
                sb.append("### Children\n\n"); //$NON-NLS-1$
                for (MetadataNode child : node.getChildren()) {
                    sb.append("- `").append(escape(child.getType())).append("` ") //$NON-NLS-1$ //$NON-NLS-2$
                            .append(escape(child.getName()));
                    if (child.getPath() != null) {
                        sb.append(" (`").append(escape(child.getPath())).append("`)"); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    sb.append("\n"); //$NON-NLS-1$
                }
                sb.append("\n"); //$NON-NLS-1$
            }
            sb.append("---\n\n"); //$NON-NLS-1$
        }
        return sb.toString();
    }

    private String escape(String text) {
        if (text == null) {
            return "-"; //$NON-NLS-1$
        }
        return text.replace("|", "\\|").replace("\n", " "); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }
}
