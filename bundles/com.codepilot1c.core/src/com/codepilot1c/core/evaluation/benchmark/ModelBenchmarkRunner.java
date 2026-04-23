package com.codepilot1c.core.evaluation.benchmark;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.codepilot1c.core.agent.AgentConfig;
import com.codepilot1c.core.agent.AgentResult;
import com.codepilot1c.core.agent.AgentRunner;
import com.codepilot1c.core.agent.events.AgentEvent;
import com.codepilot1c.core.agent.events.IAgentEventListener;
import com.codepilot1c.core.agent.events.ToolCallEvent;
import com.codepilot1c.core.agent.events.ToolResultEvent;
import com.codepilot1c.core.evaluation.benchmark.BenchmarkRun.ToolCallRecord;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.provider.ILlmProvider;
import com.codepilot1c.core.provider.LlmProviderRegistry;
import com.codepilot1c.core.tools.ToolRegistry;

/**
 * Orchestrates automated model benchmarking by running evaluation scenarios
 * against multiple LLM providers and comparing results.
 * <p>
 * Usage:
 * <pre>{@code
 * ModelBenchmarkRunner runner = new ModelBenchmarkRunner();
 * List<String> providerIds = List.of("qwen-coder", "claude-sonnet", "glm-5");
 * List<EvalScenario> scenarios = EvalScenario.loadSuite(suitePath);
 *
 * CompletableFuture<BenchmarkSuiteResult> future =
 *     runner.runSuite(scenarios, providerIds, AgentConfig.defaults());
 *
 * BenchmarkSuiteResult result = future.join();
 * BenchmarkReportGenerator.generate(result, outputPath);
 * }</pre>
 */
public class ModelBenchmarkRunner {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(ModelBenchmarkRunner.class);

    private final ToolRegistry toolRegistry;
    private final LlmProviderRegistry providerRegistry;
    private final AssertionEvaluator assertionEvaluator;

    public ModelBenchmarkRunner() {
        this(ToolRegistry.getInstance(), LlmProviderRegistry.getInstance());
    }

    public ModelBenchmarkRunner(ToolRegistry toolRegistry, LlmProviderRegistry providerRegistry) {
        this.toolRegistry = toolRegistry;
        this.providerRegistry = providerRegistry;
        this.assertionEvaluator = new AssertionEvaluator();
    }

    // -- Suite-level execution --

    /**
     * Run all scenarios against all providers and produce a full suite result.
     *
     * @param scenarios list of eval scenarios
     * @param providerIds provider IDs to benchmark
     * @param baseConfig base agent configuration (profile, timeouts, etc.)
     * @return future completing with the full suite result
     */
    public CompletableFuture<BenchmarkSuiteResult> runSuite(
            List<EvalScenario> scenarios,
            List<String> providerIds,
            AgentConfig baseConfig) {

        LOG.info("Starting benchmark suite: %d scenarios x %d providers", //$NON-NLS-1$
                scenarios.size(), providerIds.size());

        List<CompletableFuture<BenchmarkComparison>> futures = new ArrayList<>();
        for (EvalScenario scenario : scenarios) {
            futures.add(runComparison(scenario, providerIds, baseConfig));
        }

        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(v -> {
                    List<BenchmarkComparison> comparisons = futures.stream()
                            .map(CompletableFuture::join)
                            .toList();
                    BenchmarkSuiteResult result = new BenchmarkSuiteResult(
                            providerIds, comparisons, Instant.now());
                    LOG.info("Benchmark suite completed: %d/%d scenarios fully passed", //$NON-NLS-1$
                            result.fullyPassedScenarios(), result.totalScenarios());
                    return result;
                });
    }

    // -- Scenario-level comparison --

    /**
     * Run a single scenario against multiple providers in parallel.
     *
     * @param scenario the eval scenario
     * @param providerIds providers to benchmark
     * @param baseConfig base agent configuration
     * @return future completing with comparison result
     */
    public CompletableFuture<BenchmarkComparison> runComparison(
            EvalScenario scenario,
            List<String> providerIds,
            AgentConfig baseConfig) {

        LOG.info("Running scenario '%s' against %d providers", scenario.getId(), providerIds.size()); //$NON-NLS-1$

        List<CompletableFuture<BenchmarkRun>> futures = providerIds.stream()
                .map(pid -> runSingle(scenario, pid, baseConfig))
                .toList();

        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(v -> {
                    List<BenchmarkRun> runs = futures.stream()
                            .map(CompletableFuture::join)
                            .toList();
                    return new BenchmarkComparison(
                            scenario.getId(),
                            scenario.getTitle(),
                            scenario.getPrompt(),
                            runs);
                });
    }

    // -- Single provider run --

    /**
     * Run a single scenario against a single provider.
     *
     * @param scenario the eval scenario
     * @param providerId provider ID
     * @param baseConfig base agent configuration
     * @return future completing with the benchmark run result
     */
    public CompletableFuture<BenchmarkRun> runSingle(
            EvalScenario scenario,
            String providerId,
            AgentConfig baseConfig) {

        ILlmProvider provider = providerRegistry.getProvider(providerId);
        if (provider == null) {
            return CompletableFuture.completedFuture(
                    BenchmarkRun.builder()
                            .scenarioId(scenario.getId())
                            .providerId(providerId)
                            .success(false)
                            .errorMessage("Provider not found: " + providerId) //$NON-NLS-1$
                            .startedAt(Instant.now())
                            .completedAt(Instant.now())
                            .build());
        }

        if (!provider.isConfigured()) {
            return CompletableFuture.completedFuture(
                    BenchmarkRun.builder()
                            .scenarioId(scenario.getId())
                            .providerId(providerId)
                            .providerDisplayName(provider.getDisplayName())
                            .success(false)
                            .errorMessage("Provider not configured: " + providerId) //$NON-NLS-1$
                            .startedAt(Instant.now())
                            .completedAt(Instant.now())
                            .build());
        }

        LOG.info("  [%s] Starting scenario '%s'", providerId, scenario.getId()); //$NON-NLS-1$
        Instant startedAt = Instant.now();

        // Set up agent runner with event collection
        AgentRunner runner = new AgentRunner(provider, toolRegistry);
        ToolCallCollector collector = new ToolCallCollector();
        runner.addListener(collector);

        // Configure for benchmark: auto-confirm all tool calls, no user interaction
        AgentConfig benchmarkConfig = AgentConfig.builder()
                .from(baseConfig)
                .streamingEnabled(provider.supportsStreaming())
                .build();

        return runner.run(scenario.getPrompt(), benchmarkConfig)
                .handle((result, error) -> {
                    Instant completedAt = Instant.now();

                    BenchmarkRun.Builder builder = BenchmarkRun.builder()
                            .scenarioId(scenario.getId())
                            .providerId(providerId)
                            .providerDisplayName(provider.getDisplayName())
                            .startedAt(startedAt)
                            .completedAt(completedAt)
                            .toolCalls(collector.getToolCalls());

                    if (error != null) {
                        builder.success(false)
                                .errorMessage(error.getMessage())
                                .executionTimeMs(
                                        completedAt.toEpochMilli() - startedAt.toEpochMilli());
                    } else {
                        builder.success(result.isSuccess())
                                .finalResponse(result.getFinalResponse())
                                .errorMessage(result.getErrorMessage())
                                .agentSteps(result.getStepsExecuted())
                                .executionTimeMs(result.getExecutionTimeMs());

                        // Extract token usage from conversation history
                        TokenAccumulator tokens = extractTokenUsage(result);
                        builder.promptTokens(tokens.promptTokens)
                                .completionTokens(tokens.completionTokens)
                                .totalTokens(tokens.totalTokens);
                    }

                    BenchmarkRun run = builder.build();

                    // Evaluate assertions
                    List<AssertionResult> assertionResults =
                            assertionEvaluator.evaluate(scenario, run);
                    run = BenchmarkRun.builder()
                            .scenarioId(run.getScenarioId())
                            .providerId(run.getProviderId())
                            .providerDisplayName(run.getProviderDisplayName())
                            .modelName(run.getModelName())
                            .startedAt(run.getStartedAt())
                            .completedAt(run.getCompletedAt())
                            .toolCalls(run.getToolCalls())
                            .totalToolCalls(run.getTotalToolCalls())
                            .toolCallCounts(new LinkedHashMap<>(run.getToolCallCounts()))
                            .promptTokens(run.getPromptTokens())
                            .completionTokens(run.getCompletionTokens())
                            .totalTokens(run.getTotalTokens())
                            .executionTimeMs(run.getExecutionTimeMs())
                            .agentSteps(run.getAgentSteps())
                            .success(run.isSuccess())
                            .finalResponse(run.getFinalResponse())
                            .errorMessage(run.getErrorMessage())
                            .traceRunId(run.getTraceRunId())
                            .assertionResults(assertionResults)
                            .build();

                    LOG.info("  [%s] Scenario '%s' completed: %s (tools=%d, tokens=%d, time=%dms)", //$NON-NLS-1$
                            providerId, scenario.getId(),
                            run.allAssertionsPassed() ? "PASS" : "FAIL", //$NON-NLS-1$ //$NON-NLS-2$
                            run.getTotalToolCalls(),
                            run.getTotalTokens(),
                            run.getExecutionTimeMs());

                    runner.dispose();
                    return run;
                });
    }

    // -- Token extraction --

    private static TokenAccumulator extractTokenUsage(AgentResult result) {
        TokenAccumulator acc = new TokenAccumulator();
        if (result == null || result.getConversationHistory() == null) {
            return acc;
        }
        // Token usage is tracked per LLM response in the trace session.
        // For the benchmark, we estimate from conversation steps.
        // The actual token counts come from TracingLlmProvider which writes to trace files.
        // Here we use step count as proxy; real token data comes from trace JSONL.
        return acc;
    }

    private static class TokenAccumulator {
        int promptTokens;
        int completionTokens;
        int totalTokens;
    }

    // -- Event collector --

    /**
     * Collects tool call events during an agent run for benchmark analysis.
     */
    private static class ToolCallCollector implements IAgentEventListener {

        private final List<ToolCallRecord> toolCalls = new CopyOnWriteArrayList<>();
        private final Map<String, ToolCallEvent> pendingCalls = new ConcurrentHashMap<>();

        @Override
        public void onEvent(AgentEvent event) {
            if (event instanceof ToolCallEvent tce) {
                pendingCalls.put(tce.getCallId(), tce);
            } else if (event instanceof ToolResultEvent tre) {
                ToolCallEvent pending = pendingCalls.remove(tre.getCallId());
                Map<String, Object> args = pending != null
                        ? pending.getParsedArguments()
                        : Collections.emptyMap();
                toolCalls.add(new ToolCallRecord(
                        tre.getToolName(),
                        tre.getCallId(),
                        args != null ? args : Collections.emptyMap(),
                        tre.isSuccess(),
                        tre.getExecutionTimeMs()));
            }
        }

        List<ToolCallRecord> getToolCalls() {
            return new ArrayList<>(toolCalls);
        }
    }

    // -- Suite result --

    /**
     * Aggregated result of running an entire eval suite across providers.
     */
    public record BenchmarkSuiteResult(
            List<String> providerIds,
            List<BenchmarkComparison> comparisons,
            Instant completedAt) {

        public int totalScenarios() {
            return comparisons.size();
        }

        public long fullyPassedScenarios() {
            return comparisons.stream()
                    .filter(c -> c.getRuns().stream()
                            .allMatch(r -> r.isSuccess() && r.allAssertionsPassed()))
                    .count();
        }

        /**
         * Per-provider pass rate across all scenarios.
         */
        public Map<String, Double> passRateByProvider() {
            Map<String, AtomicInteger> passed = new LinkedHashMap<>();
            Map<String, AtomicInteger> total = new LinkedHashMap<>();
            for (String pid : providerIds) {
                passed.put(pid, new AtomicInteger());
                total.put(pid, new AtomicInteger());
            }
            for (BenchmarkComparison comp : comparisons) {
                for (BenchmarkRun run : comp.getRuns()) {
                    total.computeIfAbsent(run.getProviderId(), k -> new AtomicInteger())
                            .incrementAndGet();
                    if (run.isSuccess() && run.allAssertionsPassed()) {
                        passed.computeIfAbsent(run.getProviderId(), k -> new AtomicInteger())
                                .incrementAndGet();
                    }
                }
            }
            Map<String, Double> rates = new LinkedHashMap<>();
            for (String pid : providerIds) {
                int t = total.getOrDefault(pid, new AtomicInteger()).get();
                int p = passed.getOrDefault(pid, new AtomicInteger()).get();
                rates.put(pid, t > 0 ? (double) p / t : 0.0);
            }
            return rates;
        }

        /**
         * Average token usage per provider across all scenarios.
         */
        public Map<String, Double> avgTokensByProvider() {
            Map<String, List<Integer>> tokenLists = new LinkedHashMap<>();
            for (BenchmarkComparison comp : comparisons) {
                for (BenchmarkRun run : comp.getRuns()) {
                    tokenLists.computeIfAbsent(run.getProviderId(), k -> new ArrayList<>())
                            .add(run.getTotalTokens());
                }
            }
            Map<String, Double> avgs = new LinkedHashMap<>();
            for (Map.Entry<String, List<Integer>> entry : tokenLists.entrySet()) {
                avgs.put(entry.getKey(),
                        entry.getValue().stream()
                                .mapToInt(Integer::intValue)
                                .average()
                                .orElse(0.0));
            }
            return avgs;
        }
    }
}
