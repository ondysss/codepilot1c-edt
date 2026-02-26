package com.codepilot1c.core.agent.graph;

import java.util.Objects;

/**
 * Directed edge between tool graph nodes.
 */
public class ToolEdge {

    private final String from;
    private final String to;
    private final EdgePredicate predicate;
    private final int priority;

    public ToolEdge(String from, String to, EdgePredicate predicate, int priority) {
        this.from = Objects.requireNonNull(from, "from"); //$NON-NLS-1$
        this.to = Objects.requireNonNull(to, "to"); //$NON-NLS-1$
        this.predicate = predicate != null ? predicate : EdgePredicates.always();
        this.priority = priority;
    }

    public String getFrom() {
        return from;
    }

    public String getTo() {
        return to;
    }

    public EdgePredicate getPredicate() {
        return predicate;
    }

    public int getPriority() {
        return priority;
    }
}
