/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent.langgraph;

import com.codepilot1c.core.agent.graph.KeywordToolGraphSelectionStrategy;
import com.codepilot1c.core.agent.graph.ToolGraphRegistry;

/**
 * Domain routing for LangGraph-based multi-agent flow.
 */
public enum LangGraphAgentDomain {
    GENERAL(ToolGraphRegistry.GENERAL_GRAPH_ID, "General", null), //$NON-NLS-1$
    BSL(ToolGraphRegistry.BSL_GRAPH_ID, "BSL", //$NON-NLS-1$
            "Ты эксперт по BSL и модульному коду 1С. Фокусируйся на процедурах, функциях, типах и ссылках."), //$NON-NLS-1$
    METADATA(ToolGraphRegistry.METADATA_GRAPH_ID, "Metadata", //$NON-NLS-1$
            "Ты эксперт по метаданным 1С и EDT. Фокус на объектах, реквизитах, типах и связях."), //$NON-NLS-1$
    FORMS(ToolGraphRegistry.FORMS_GRAPH_ID, "Forms", //$NON-NLS-1$
            "Ты эксперт по управляемым формам 1С. Фокус на элементах формы, реквизитах и их типах."); //$NON-NLS-1$

    private static final KeywordToolGraphSelectionStrategy STRATEGY =
            new KeywordToolGraphSelectionStrategy();

    private final String graphId;
    private final String displayName;
    private final String promptAddition;

    LangGraphAgentDomain(String graphId, String displayName, String promptAddition) {
        this.graphId = graphId;
        this.displayName = displayName;
        this.promptAddition = promptAddition;
    }

    public String getGraphId() {
        return graphId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPromptAddition() {
        return promptAddition;
    }

    public String getId() {
        return graphId;
    }

    public static LangGraphAgentDomain fromPrompt(String prompt) {
        String graphId = STRATEGY.selectGraphId(prompt);
        return fromGraphId(graphId);
    }

    public static LangGraphAgentDomain fromGraphId(String graphId) {
        if (graphId == null) {
            return GENERAL;
        }
        for (LangGraphAgentDomain domain : values()) {
            if (domain.graphId.equals(graphId)) {
                return domain;
            }
        }
        return GENERAL;
    }
}
