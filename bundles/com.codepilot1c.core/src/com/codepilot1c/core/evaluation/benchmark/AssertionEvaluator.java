package com.codepilot1c.core.evaluation.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.codepilot1c.core.evaluation.benchmark.BenchmarkRun.ToolCallRecord;
import com.codepilot1c.core.evaluation.benchmark.EvalScenario.Assertions;
import com.codepilot1c.core.evaluation.benchmark.EvalScenario.ExpectedToolPath;
import com.codepilot1c.core.evaluation.benchmark.EvalScenario.FinalAnswer;
import com.codepilot1c.core.evaluation.benchmark.EvalScenario.ToolBehavior;

/**
 * Evaluates a {@link BenchmarkRun} against an {@link EvalScenario}'s assertions.
 * <p>
 * Mirrors the assertion checks from the Python {@code run-qwen-mcp-suite.py} runner:
 * tool path validation, mutation flow compliance, QA gate checks, and final answer checks.
 */
public class AssertionEvaluator {

    /** Tools that perform mutations requiring validation_token flow. */
    private static final Set<String> MUTATING_TOOLS = Set.of(
            "create_metadata", //$NON-NLS-1$
            "create_form", //$NON-NLS-1$
            "add_metadata_child", //$NON-NLS-1$
            "update_metadata", //$NON-NLS-1$
            "mutate_form_model", //$NON-NLS-1$
            "delete_metadata", //$NON-NLS-1$
            "qa_prepare_form_context" //$NON-NLS-1$
    );

    /**
     * Evaluate all applicable assertions for the given scenario against the run.
     *
     * @param scenario the eval scenario with expected behavior
     * @param run the completed benchmark run to evaluate
     * @return list of assertion results
     */
    public List<AssertionResult> evaluate(EvalScenario scenario, BenchmarkRun run) {
        List<AssertionResult> results = new ArrayList<>();
        List<String> toolSequence = run.getToolCallSequence();

        // -- Execution-level checks --
        results.add(run.isSuccess()
                ? AssertionResult.pass("agent_success", AssertionResult.CATEGORY_EXECUTION) //$NON-NLS-1$
                : AssertionResult.fail("agent_success", AssertionResult.CATEGORY_EXECUTION, //$NON-NLS-1$
                        run.getErrorMessage() != null ? run.getErrorMessage() : "Agent did not complete successfully")); //$NON-NLS-1$

        // -- Tool path assertions --
        ExpectedToolPath toolPath = scenario.getExpectedToolPath();
        if (toolPath != null) {
            evaluateToolPath(toolPath, toolSequence, results);
        }

        // -- Behavior assertions --
        Assertions assertions = scenario.getAssertions();
        if (assertions != null) {
            if (assertions.getToolBehavior() != null) {
                evaluateToolBehavior(assertions.getToolBehavior(), run, toolSequence, results);
            }
            if (assertions.getFinalAnswer() != null) {
                evaluateFinalAnswer(assertions.getFinalAnswer(), run.getFinalResponse(), results);
            }
        }

        return results;
    }

    // -- Tool path evaluation --

    private void evaluateToolPath(ExpectedToolPath expected, List<String> actual,
            List<AssertionResult> results) {

        // ordered_subsequence: tools must appear in this order (not necessarily adjacent)
        if (!expected.getOrderedSubsequence().isEmpty()) {
            boolean ok = isOrderedSubsequence(expected.getOrderedSubsequence(), actual);
            results.add(ok
                    ? AssertionResult.pass("ordered_subsequence", AssertionResult.CATEGORY_TOOL_PATH) //$NON-NLS-1$
                    : AssertionResult.fail("ordered_subsequence", AssertionResult.CATEGORY_TOOL_PATH, //$NON-NLS-1$
                            "Expected order: " + expected.getOrderedSubsequence() //$NON-NLS-1$
                                    + ", actual: " + actual)); //$NON-NLS-1$
        }

        // required_any_order: all these tools must be called (order irrelevant)
        for (String required : expected.getRequiredAnyOrder()) {
            boolean found = actual.contains(required);
            results.add(found
                    ? AssertionResult.pass("required_tool:" + required, AssertionResult.CATEGORY_TOOL_PATH) //$NON-NLS-1$
                    : AssertionResult.fail("required_tool:" + required, AssertionResult.CATEGORY_TOOL_PATH, //$NON-NLS-1$
                            "Tool '" + required + "' was not called")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // forbidden: these tools must NOT be called
        for (String forbidden : expected.getForbidden()) {
            boolean absent = !actual.contains(forbidden);
            results.add(absent
                    ? AssertionResult.pass("forbidden_tool:" + forbidden, AssertionResult.CATEGORY_TOOL_PATH) //$NON-NLS-1$
                    : AssertionResult.fail("forbidden_tool:" + forbidden, AssertionResult.CATEGORY_TOOL_PATH, //$NON-NLS-1$
                            "Forbidden tool '" + forbidden + "' was called")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // -- Tool behavior evaluation --

    private void evaluateToolBehavior(ToolBehavior behavior, BenchmarkRun run,
            List<String> toolSequence, List<AssertionResult> results) {

        // max_mutating_calls
        if (behavior.getMaxMutatingCalls() != null) {
            int maxAllowed = behavior.getMaxMutatingCalls();
            long actual = toolSequence.stream().filter(MUTATING_TOOLS::contains).count();
            results.add(actual <= maxAllowed
                    ? AssertionResult.pass("max_mutating_calls", AssertionResult.CATEGORY_TOOL_BEHAVIOR) //$NON-NLS-1$
                    : AssertionResult.fail("max_mutating_calls", AssertionResult.CATEGORY_TOOL_BEHAVIOR, //$NON-NLS-1$
                            "Mutation count " + actual + " exceeds limit " + maxAllowed)); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // must_have_validation_flow
        if (behavior.isMustHaveValidationFlow()) {
            boolean hasValidateRequest = toolSequence.contains("edt_validate_request"); //$NON-NLS-1$
            boolean hasMutation = toolSequence.stream().anyMatch(MUTATING_TOOLS::contains);
            boolean ok = !hasMutation || hasValidateRequest;
            results.add(ok
                    ? AssertionResult.pass("validation_flow", AssertionResult.CATEGORY_TOOL_BEHAVIOR) //$NON-NLS-1$
                    : AssertionResult.fail("validation_flow", AssertionResult.CATEGORY_TOOL_BEHAVIOR, //$NON-NLS-1$
                            "Mutations performed without edt_validate_request")); //$NON-NLS-1$
        }

        // must_have_post_mutation_diagnostics
        if (behavior.isMustHavePostMutationDiagnostics()) {
            boolean ok = checkPostMutationDiagnostics(toolSequence);
            results.add(ok
                    ? AssertionResult.pass("post_mutation_diagnostics", AssertionResult.CATEGORY_TOOL_BEHAVIOR) //$NON-NLS-1$
                    : AssertionResult.fail("post_mutation_diagnostics", AssertionResult.CATEGORY_TOOL_BEHAVIOR, //$NON-NLS-1$
                            "get_diagnostics not called after last mutation")); //$NON-NLS-1$
        }

        // must_call_qa_inspect_status_first
        if (behavior.isMustCallQaInspectStatusFirst()) {
            boolean ok = checkQaInspectBeforeRun(run);
            results.add(ok
                    ? AssertionResult.pass("qa_inspect_before_run", AssertionResult.CATEGORY_TOOL_BEHAVIOR) //$NON-NLS-1$
                    : AssertionResult.fail("qa_inspect_before_run", AssertionResult.CATEGORY_TOOL_BEHAVIOR, //$NON-NLS-1$
                            "qa_inspect(command=status) not called before qa_run")); //$NON-NLS-1$
        }
    }

    // -- Final answer evaluation --

    private void evaluateFinalAnswer(FinalAnswer expected, String response,
            List<AssertionResult> results) {
        if (response == null) {
            response = ""; //$NON-NLS-1$
        }
        String lower = response.toLowerCase();

        for (String keyword : expected.getMustMention()) {
            boolean found = lower.contains(keyword.toLowerCase());
            results.add(found
                    ? AssertionResult.pass("must_mention:" + keyword, AssertionResult.CATEGORY_FINAL_ANSWER) //$NON-NLS-1$
                    : AssertionResult.fail("must_mention:" + keyword, AssertionResult.CATEGORY_FINAL_ANSWER, //$NON-NLS-1$
                            "Response does not mention '" + keyword + "'")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // -- Helper methods --

    /**
     * Check whether {@code expected} appears as a subsequence of {@code actual}.
     */
    private static boolean isOrderedSubsequence(List<String> expected, List<String> actual) {
        int ei = 0;
        for (String tool : actual) {
            if (ei < expected.size() && tool.equals(expected.get(ei))) {
                ei++;
            }
        }
        return ei == expected.size();
    }

    /**
     * Check that get_diagnostics is called after the last mutating tool.
     */
    private static boolean checkPostMutationDiagnostics(List<String> toolSequence) {
        int lastMutationIndex = -1;
        for (int i = 0; i < toolSequence.size(); i++) {
            if (MUTATING_TOOLS.contains(toolSequence.get(i))) {
                lastMutationIndex = i;
            }
        }
        if (lastMutationIndex < 0) {
            return true; // no mutations — passes
        }
        for (int i = lastMutationIndex + 1; i < toolSequence.size(); i++) {
            if ("get_diagnostics".equals(toolSequence.get(i))) { //$NON-NLS-1$
                return true;
            }
        }
        return false;
    }

    /**
     * Check that qa_inspect appears before qa_run in tool calls,
     * and that qa_inspect was called with command=status.
     */
    private static boolean checkQaInspectBeforeRun(BenchmarkRun run) {
        List<ToolCallRecord> calls = run.getToolCalls();
        int qaInspectIndex = -1;
        int qaRunIndex = -1;
        for (int i = 0; i < calls.size(); i++) {
            ToolCallRecord tc = calls.get(i);
            if ("qa_inspect".equals(tc.toolName())) { //$NON-NLS-1$
                Object cmd = tc.arguments().get("command"); //$NON-NLS-1$
                if ("status".equals(cmd)) { //$NON-NLS-1$
                    qaInspectIndex = i;
                }
            }
            if ("qa_run".equals(tc.toolName()) && qaRunIndex < 0) { //$NON-NLS-1$
                qaRunIndex = i;
            }
        }
        if (qaRunIndex < 0) {
            return true; // no qa_run — passes
        }
        return qaInspectIndex >= 0 && qaInspectIndex < qaRunIndex;
    }
}
