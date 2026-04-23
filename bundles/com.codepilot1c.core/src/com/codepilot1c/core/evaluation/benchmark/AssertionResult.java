package com.codepilot1c.core.evaluation.benchmark;

/**
 * Result of evaluating a single assertion against a benchmark run.
 */
public record AssertionResult(
        String assertionName,
        String category,
        boolean passed,
        String detail) {

    /** Assertion categories matching the Python eval runner's failure taxonomy. */
    public static final String CATEGORY_TOOL_PATH = "tool_path"; //$NON-NLS-1$
    public static final String CATEGORY_TOOL_BEHAVIOR = "tool_behavior"; //$NON-NLS-1$
    public static final String CATEGORY_FINAL_ANSWER = "final_answer"; //$NON-NLS-1$
    public static final String CATEGORY_EXECUTION = "execution"; //$NON-NLS-1$

    public static AssertionResult pass(String name, String category) {
        return new AssertionResult(name, category, true, null);
    }

    public static AssertionResult fail(String name, String category, String detail) {
        return new AssertionResult(name, category, false, detail);
    }
}
