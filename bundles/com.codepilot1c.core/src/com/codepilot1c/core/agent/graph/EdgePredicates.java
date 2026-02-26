package com.codepilot1c.core.agent.graph;

import java.util.Collection;
import java.util.Objects;

/**
 * Common edge predicates.
 */
public final class EdgePredicates {

    private EdgePredicates() {
    }

    public static EdgePredicate always() {
        return context -> true;
    }

    public static EdgePredicate toolNameIs(String name) {
        return context -> context != null && Objects.equals(context.getToolName(), name);
    }

    public static EdgePredicate toolNameIn(Collection<String> names) {
        return context -> {
            if (context == null || names == null || names.isEmpty()) {
                return false;
            }
            return names.contains(context.getToolName());
        };
    }

    public static EdgePredicate success() {
        return context -> context != null && context.isSuccess();
    }

    public static EdgePredicate failure() {
        return context -> context != null && !context.isSuccess();
    }

    public static EdgePredicate and(EdgePredicate left, EdgePredicate right) {
        return context -> (left == null || left.matches(context))
                && (right == null || right.matches(context));
    }

    public static EdgePredicate or(EdgePredicate left, EdgePredicate right) {
        return context -> (left != null && left.matches(context))
                || (right != null && right.matches(context));
    }
}
