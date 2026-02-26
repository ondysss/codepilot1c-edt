package com.codepilot1c.core.agent.graph;

/**
 * Strategy for selecting a tool graph based on prompt/context.
 */
public interface ToolGraphSelectionStrategy {
    String selectGraphId(String userPrompt);
}
