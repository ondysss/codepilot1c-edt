package com.codepilot1c.core.agent.graph;

import java.util.List;

/**
 * Simple keyword-based graph selection.
 */
public class KeywordToolGraphSelectionStrategy implements ToolGraphSelectionStrategy {

    @Override
    public String selectGraphId(String userPrompt) {
        if (userPrompt == null || userPrompt.isBlank()) {
            return ToolGraphRegistry.GENERAL_GRAPH_ID;
        }
        String prompt = userPrompt.toLowerCase();

        if (containsAny(prompt, List.of("форма", "форму", "формы", "управляемая форма"))) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            return ToolGraphRegistry.FORMS_GRAPH_ID;
        }

        if (containsAny(prompt, List.of("реквизит", "таблич", "справочник", "документ", "регистр", "метаданные"))) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            return ToolGraphRegistry.METADATA_GRAPH_ID;
        }

        if (containsAny(prompt, List.of("процедур", "функц", "модуль", "bsl", "код"))) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
            return ToolGraphRegistry.BSL_GRAPH_ID;
        }

        return ToolGraphRegistry.GENERAL_GRAPH_ID;
    }

    private boolean containsAny(String text, List<String> keywords) {
        if (text == null || keywords == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
