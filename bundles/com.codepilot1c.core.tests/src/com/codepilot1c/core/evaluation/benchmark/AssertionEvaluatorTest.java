package com.codepilot1c.core.evaluation.benchmark;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.codepilot1c.core.evaluation.benchmark.BenchmarkRun.ToolCallRecord;

public class AssertionEvaluatorTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private AssertionEvaluator evaluator;

    @Before
    public void setUp() {
        evaluator = new AssertionEvaluator();
    }

    // -- Ordered subsequence --

    @Test
    public void orderedSubsequencePassesWhenToolsInCorrectOrder() throws Exception {
        EvalScenario scenario = loadScenario("""
                {
                  "id": "T-001", "title": "T", "prompt": "p",
                  "expected_tool_path": {
                    "ordered_subsequence": ["read_file", "edit_file", "get_diagnostics"]
                  }
                }
                """); //$NON-NLS-1$

        BenchmarkRun run = buildRun(true, List.of(
                tc("glob"), tc("read_file"), tc("grep"), tc("edit_file"), tc("get_diagnostics"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$

        List<AssertionResult> results = evaluator.evaluate(scenario, run);
        AssertionResult subseq = findByName(results, "ordered_subsequence"); //$NON-NLS-1$

        assertTrue(subseq.passed());
    }

    @Test
    public void orderedSubsequenceFailsWhenOrderWrong() throws Exception {
        EvalScenario scenario = loadScenario("""
                {
                  "id": "T-002", "title": "T", "prompt": "p",
                  "expected_tool_path": {
                    "ordered_subsequence": ["edit_file", "read_file"]
                  }
                }
                """); //$NON-NLS-1$

        BenchmarkRun run = buildRun(true, List.of(tc("read_file"), tc("edit_file"))); //$NON-NLS-1$ //$NON-NLS-2$

        List<AssertionResult> results = evaluator.evaluate(scenario, run);
        AssertionResult subseq = findByName(results, "ordered_subsequence"); //$NON-NLS-1$

        assertFalse(subseq.passed());
    }

    // -- Forbidden tools --

    @Test
    public void forbiddenToolFailsWhenCalled() throws Exception {
        EvalScenario scenario = loadScenario("""
                {
                  "id": "T-003", "title": "T", "prompt": "p",
                  "expected_tool_path": {
                    "forbidden": ["write_file", "delete_metadata"]
                  }
                }
                """); //$NON-NLS-1$

        BenchmarkRun run = buildRun(true, List.of(tc("read_file"), tc("write_file"))); //$NON-NLS-1$ //$NON-NLS-2$

        List<AssertionResult> results = evaluator.evaluate(scenario, run);
        AssertionResult writeCheck = findByName(results, "forbidden_tool:write_file"); //$NON-NLS-1$
        AssertionResult deleteCheck = findByName(results, "forbidden_tool:delete_metadata"); //$NON-NLS-1$

        assertFalse(writeCheck.passed());
        assertTrue(deleteCheck.passed());
    }

    // -- Required any order --

    @Test
    public void requiredAnyOrderPassesWhenAllPresent() throws Exception {
        EvalScenario scenario = loadScenario("""
                {
                  "id": "T-004", "title": "T", "prompt": "p",
                  "expected_tool_path": {
                    "required_any_order": ["glob", "grep"]
                  }
                }
                """); //$NON-NLS-1$

        BenchmarkRun run = buildRun(true, List.of(tc("grep"), tc("read_file"), tc("glob"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        List<AssertionResult> results = evaluator.evaluate(scenario, run);
        assertTrue(findByName(results, "required_tool:glob").passed()); //$NON-NLS-1$
        assertTrue(findByName(results, "required_tool:grep").passed()); //$NON-NLS-1$
    }

    // -- Max mutating calls --

    @Test
    public void maxMutatingCallsFailsWhenExceeded() throws Exception {
        EvalScenario scenario = loadScenario("""
                {
                  "id": "T-005", "title": "T", "prompt": "p",
                  "assertions": {
                    "tool_behavior": {
                      "max_mutating_calls": 1
                    }
                  }
                }
                """); //$NON-NLS-1$

        BenchmarkRun run = buildRun(true, List.of(
                tc("create_metadata"), tc("update_metadata"), tc("get_diagnostics"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        List<AssertionResult> results = evaluator.evaluate(scenario, run);
        AssertionResult maxMut = findByName(results, "max_mutating_calls"); //$NON-NLS-1$

        assertFalse(maxMut.passed());
        assertTrue(maxMut.detail().contains("2")); //$NON-NLS-1$
    }

    @Test
    public void maxMutatingCallsPassesWhenWithinLimit() throws Exception {
        EvalScenario scenario = loadScenario("""
                {
                  "id": "T-006", "title": "T", "prompt": "p",
                  "assertions": {
                    "tool_behavior": {
                      "max_mutating_calls": 0
                    }
                  }
                }
                """); //$NON-NLS-1$

        BenchmarkRun run = buildRun(true, List.of(tc("read_file"), tc("glob"))); //$NON-NLS-1$ //$NON-NLS-2$

        List<AssertionResult> results = evaluator.evaluate(scenario, run);
        assertTrue(findByName(results, "max_mutating_calls").passed()); //$NON-NLS-1$
    }

    // -- Validation flow --

    @Test
    public void validationFlowFailsWhenMutationWithoutValidate() throws Exception {
        EvalScenario scenario = loadScenario("""
                {
                  "id": "T-007", "title": "T", "prompt": "p",
                  "assertions": {
                    "tool_behavior": {
                      "must_have_validation_flow": true
                    }
                  }
                }
                """); //$NON-NLS-1$

        BenchmarkRun run = buildRun(true, List.of(tc("create_metadata"))); //$NON-NLS-1$

        List<AssertionResult> results = evaluator.evaluate(scenario, run);
        assertFalse(findByName(results, "validation_flow").passed()); //$NON-NLS-1$
    }

    @Test
    public void validationFlowPassesWithValidateBeforeMutation() throws Exception {
        EvalScenario scenario = loadScenario("""
                {
                  "id": "T-008", "title": "T", "prompt": "p",
                  "assertions": {
                    "tool_behavior": {
                      "must_have_validation_flow": true
                    }
                  }
                }
                """); //$NON-NLS-1$

        BenchmarkRun run = buildRun(true, List.of(
                tc("edt_validate_request"), tc("create_metadata"))); //$NON-NLS-1$ //$NON-NLS-2$

        List<AssertionResult> results = evaluator.evaluate(scenario, run);
        assertTrue(findByName(results, "validation_flow").passed()); //$NON-NLS-1$
    }

    // -- Post-mutation diagnostics --

    @Test
    public void postMutationDiagnosticsFailsWhenMissing() throws Exception {
        EvalScenario scenario = loadScenario("""
                {
                  "id": "T-009", "title": "T", "prompt": "p",
                  "assertions": {
                    "tool_behavior": {
                      "must_have_post_mutation_diagnostics": true
                    }
                  }
                }
                """); //$NON-NLS-1$

        BenchmarkRun run = buildRun(true, List.of(
                tc("create_metadata"), tc("read_file"))); //$NON-NLS-1$ //$NON-NLS-2$

        List<AssertionResult> results = evaluator.evaluate(scenario, run);
        assertFalse(findByName(results, "post_mutation_diagnostics").passed()); //$NON-NLS-1$
    }

    @Test
    public void postMutationDiagnosticsPassesWhenPresent() throws Exception {
        EvalScenario scenario = loadScenario("""
                {
                  "id": "T-010", "title": "T", "prompt": "p",
                  "assertions": {
                    "tool_behavior": {
                      "must_have_post_mutation_diagnostics": true
                    }
                  }
                }
                """); //$NON-NLS-1$

        BenchmarkRun run = buildRun(true, List.of(
                tc("create_metadata"), tc("update_metadata"), tc("get_diagnostics"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        List<AssertionResult> results = evaluator.evaluate(scenario, run);
        assertTrue(findByName(results, "post_mutation_diagnostics").passed()); //$NON-NLS-1$
    }

    // -- QA inspect before run --

    @Test
    public void qaInspectBeforeRunFailsWhenMissing() throws Exception {
        EvalScenario scenario = loadScenario("""
                {
                  "id": "T-011", "title": "T", "prompt": "p",
                  "assertions": {
                    "tool_behavior": {
                      "must_call_qa_inspect_status_first": true
                    }
                  }
                }
                """); //$NON-NLS-1$

        BenchmarkRun run = buildRun(true, List.of(tc("qa_run"))); //$NON-NLS-1$

        List<AssertionResult> results = evaluator.evaluate(scenario, run);
        assertFalse(findByName(results, "qa_inspect_before_run").passed()); //$NON-NLS-1$
    }

    @Test
    public void qaInspectBeforeRunPassesWhenStatusCalledFirst() throws Exception {
        EvalScenario scenario = loadScenario("""
                {
                  "id": "T-012", "title": "T", "prompt": "p",
                  "assertions": {
                    "tool_behavior": {
                      "must_call_qa_inspect_status_first": true
                    }
                  }
                }
                """); //$NON-NLS-1$

        BenchmarkRun run = buildRun(true, List.of(
                tcWithArgs("qa_inspect", Map.of("command", "status")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                tc("qa_run"))); //$NON-NLS-1$

        List<AssertionResult> results = evaluator.evaluate(scenario, run);
        assertTrue(findByName(results, "qa_inspect_before_run").passed()); //$NON-NLS-1$
    }

    // -- Final answer --

    @Test
    public void mustMentionFailsWhenKeywordMissing() throws Exception {
        EvalScenario scenario = loadScenario("""
                {
                  "id": "T-013", "title": "T", "prompt": "p",
                  "assertions": {
                    "final_answer": {
                      "must_mention": ["junit", "diagnostics"]
                    }
                  }
                }
                """); //$NON-NLS-1$

        BenchmarkRun run = BenchmarkRun.builder()
                .scenarioId("T-013") //$NON-NLS-1$
                .providerId("test") //$NON-NLS-1$
                .success(true)
                .finalResponse("The junit report is ready.") //$NON-NLS-1$
                .toolCalls(List.of())
                .build();

        List<AssertionResult> results = evaluator.evaluate(scenario, run);
        assertTrue(findByName(results, "must_mention:junit").passed()); //$NON-NLS-1$
        assertFalse(findByName(results, "must_mention:diagnostics").passed()); //$NON-NLS-1$
    }

    // -- Agent failure --

    @Test
    public void agentFailureProducesFailedAssertion() throws Exception {
        EvalScenario scenario = loadScenario("""
                {
                  "id": "T-014", "title": "T", "prompt": "p"
                }
                """); //$NON-NLS-1$

        BenchmarkRun run = BenchmarkRun.builder()
                .scenarioId("T-014") //$NON-NLS-1$
                .providerId("test") //$NON-NLS-1$
                .success(false)
                .errorMessage("Timeout") //$NON-NLS-1$
                .toolCalls(List.of())
                .build();

        List<AssertionResult> results = evaluator.evaluate(scenario, run);
        AssertionResult agentSuccess = findByName(results, "agent_success"); //$NON-NLS-1$

        assertFalse(agentSuccess.passed());
        assertEquals("Timeout", agentSuccess.detail()); //$NON-NLS-1$
    }

    // -- Helpers --

    private EvalScenario loadScenario(String json) throws Exception {
        Path file = tempFolder.newFile().toPath();
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return EvalScenario.load(file);
    }

    private static BenchmarkRun buildRun(boolean success, List<ToolCallRecord> toolCalls) {
        return BenchmarkRun.builder()
                .scenarioId("test") //$NON-NLS-1$
                .providerId("test-provider") //$NON-NLS-1$
                .success(success)
                .finalResponse("Done") //$NON-NLS-1$
                .toolCalls(toolCalls)
                .build();
    }

    private static ToolCallRecord tc(String name) {
        return new ToolCallRecord(name, "call-" + name, Map.of(), true, 100); //$NON-NLS-1$
    }

    private static ToolCallRecord tcWithArgs(String name, Map<String, Object> args) {
        return new ToolCallRecord(name, "call-" + name, args, true, 100); //$NON-NLS-1$
    }

    private static AssertionResult findByName(List<AssertionResult> results, String name) {
        return results.stream()
                .filter(r -> name.equals(r.assertionName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Assertion not found: " + name //$NON-NLS-1$
                        + ", available: " + results.stream().map(AssertionResult::assertionName).toList())); //$NON-NLS-1$
    }
}
