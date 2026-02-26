package com.codepilot1c.core.agent.graph;

/**
 * Predicate for deciding graph transitions.
 */
@FunctionalInterface
public interface EdgePredicate {
    boolean matches(ToolGraphContext context);
}
