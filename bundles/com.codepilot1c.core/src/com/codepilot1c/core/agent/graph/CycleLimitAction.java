package com.codepilot1c.core.agent.graph;

/**
 * Action to take when a node visit limit is exceeded.
 */
public enum CycleLimitAction {
    ABORT,
    ESCALATE_TO_FALLBACK
}
