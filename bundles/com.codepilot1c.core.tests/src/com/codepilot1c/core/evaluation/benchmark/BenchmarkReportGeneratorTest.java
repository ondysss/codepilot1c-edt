package com.codepilot1c.core.evaluation.benchmark;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.evaluation.benchmark.BenchmarkRun.ToolCallRecord;
import com.codepilot1c.core.evaluation.benchmark.ModelBenchmarkRunner.BenchmarkSuiteResult;

public class BenchmarkReportGeneratorTest {

    @Test
    public void generatesValidHtmlWithMultipleProviders() {
        BenchmarkRun runA = BenchmarkRun.builder()
                .scenarioId("S-001") //$NON-NLS-1$
                .providerId("qwen-coder") //$NON-NLS-1$
                .providerDisplayName("Qwen Coder 2.5") //$NON-NLS-1$
                .success(true)
                .agentSteps(8)
                .totalToolCalls(12)
                .promptTokens(10000)
                .completionTokens(8420)
                .totalTokens(18420)
                .executionTimeMs(34000)
                .finalResponse("Created catalog Nomenclature with 3 attributes") //$NON-NLS-1$
                .toolCalls(List.of(
                        new ToolCallRecord("edt_validate_request", "c1", Map.of(), true, 50), //$NON-NLS-1$ //$NON-NLS-2$
                        new ToolCallRecord("create_metadata", "c2", Map.of(), true, 200), //$NON-NLS-1$ //$NON-NLS-2$
                        new ToolCallRecord("get_diagnostics", "c3", Map.of(), true, 150))) //$NON-NLS-1$ //$NON-NLS-2$
                .assertionResults(List.of(
                        AssertionResult.pass("agent_success", AssertionResult.CATEGORY_EXECUTION), //$NON-NLS-1$
                        AssertionResult.pass("validation_flow", AssertionResult.CATEGORY_TOOL_BEHAVIOR))) //$NON-NLS-1$
                .build();

        BenchmarkRun runB = BenchmarkRun.builder()
                .scenarioId("S-001") //$NON-NLS-1$
                .providerId("glm-5") //$NON-NLS-1$
                .providerDisplayName("GLM-5") //$NON-NLS-1$
                .success(true)
                .agentSteps(12)
                .totalToolCalls(18)
                .promptTokens(15000)
                .completionTokens(10600)
                .totalTokens(25600)
                .executionTimeMs(45000)
                .finalResponse("Done") //$NON-NLS-1$
                .toolCalls(List.of(
                        new ToolCallRecord("create_metadata", "c1", Map.of(), true, 200))) //$NON-NLS-1$ //$NON-NLS-2$
                .assertionResults(List.of(
                        AssertionResult.pass("agent_success", AssertionResult.CATEGORY_EXECUTION), //$NON-NLS-1$
                        AssertionResult.fail("validation_flow", AssertionResult.CATEGORY_TOOL_BEHAVIOR, //$NON-NLS-1$
                                "Mutations performed without edt_validate_request"))) //$NON-NLS-1$
                .build();

        BenchmarkComparison comparison = new BenchmarkComparison(
                "S-001", "Create Catalog", "Create catalog Nomenclature", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                List.of(runA, runB));

        BenchmarkSuiteResult suiteResult = new BenchmarkSuiteResult(
                List.of("qwen-coder", "glm-5"), //$NON-NLS-1$ //$NON-NLS-2$
                List.of(comparison),
                Instant.now());

        String html = BenchmarkReportGenerator.buildHtml(suiteResult);

        // Basic structure checks
        assertTrue(html.contains("<!DOCTYPE html>")); //$NON-NLS-1$
        assertTrue(html.contains("Model Benchmark Report")); //$NON-NLS-1$
        assertTrue(html.contains("</html>")); //$NON-NLS-1$

        // Provider names present
        assertTrue(html.contains("Qwen Coder 2.5")); //$NON-NLS-1$
        assertTrue(html.contains("GLM-5")); //$NON-NLS-1$

        // Scenario info present
        assertTrue(html.contains("S-001")); //$NON-NLS-1$
        assertTrue(html.contains("Create Catalog")); //$NON-NLS-1$

        // Metrics rendered
        assertTrue(html.contains("18.4k")); // 18420 tokens formatted //$NON-NLS-1$
        assertTrue(html.contains("34.0s")); // 34000ms formatted //$NON-NLS-1$

        // Pass/fail indicators
        assertTrue(html.contains("PASS")); //$NON-NLS-1$
        assertTrue(html.contains("FAIL")); //$NON-NLS-1$

        // Failed assertion detail
        assertTrue(html.contains("validation_flow")); //$NON-NLS-1$
        assertTrue(html.contains("edt_validate_request")); //$NON-NLS-1$

        // No XSS — special chars should be escaped
        assertFalse(html.contains("<script>")); //$NON-NLS-1$
    }

    @Test
    public void suiteResultComputesPassRates() {
        BenchmarkRun passRun = BenchmarkRun.builder()
                .scenarioId("S-001").providerId("a").success(true) //$NON-NLS-1$ //$NON-NLS-2$
                .toolCalls(List.of())
                .assertionResults(List.of(
                        AssertionResult.pass("agent_success", AssertionResult.CATEGORY_EXECUTION))) //$NON-NLS-1$
                .build();

        BenchmarkRun failRun = BenchmarkRun.builder()
                .scenarioId("S-001").providerId("b").success(true) //$NON-NLS-1$ //$NON-NLS-2$
                .toolCalls(List.of())
                .assertionResults(List.of(
                        AssertionResult.fail("agent_success", AssertionResult.CATEGORY_EXECUTION, "err"))) //$NON-NLS-1$ //$NON-NLS-2$
                .build();

        BenchmarkComparison comparison = new BenchmarkComparison(
                "S-001", "Test", "test", List.of(passRun, failRun)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        BenchmarkSuiteResult result = new BenchmarkSuiteResult(
                List.of("a", "b"), List.of(comparison), Instant.now()); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, Double> rates = result.passRateByProvider();
        assertTrue(rates.get("a") > 0.9); // 100% //$NON-NLS-1$
        assertTrue(rates.get("b") < 0.1); // 0% //$NON-NLS-1$
    }
}
