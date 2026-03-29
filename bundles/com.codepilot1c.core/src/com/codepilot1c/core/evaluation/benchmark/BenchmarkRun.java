package com.codepilot1c.core.evaluation.benchmark;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of running a single {@link EvalScenario} against a single LLM provider.
 * <p>
 * Captures tool call sequence, token usage, timing, and assertion results.
 */
public class BenchmarkRun {

    private final String scenarioId;
    private final String providerId;
    private final String providerDisplayName;
    private final String modelName;
    private final Instant startedAt;
    private final Instant completedAt;

    // Tool call metrics
    private final List<ToolCallRecord> toolCalls;
    private final int totalToolCalls;
    private final Map<String, Integer> toolCallCounts;

    // Token metrics
    private final int promptTokens;
    private final int completionTokens;
    private final int totalTokens;

    // Execution metrics
    private final long executionTimeMs;
    private final int agentSteps;

    // Result
    private final boolean success;
    private final String finalResponse;
    private final String errorMessage;
    private final String traceRunId;

    // Assertions
    private final List<AssertionResult> assertionResults;

    private BenchmarkRun(Builder builder) {
        this.scenarioId = builder.scenarioId;
        this.providerId = builder.providerId;
        this.providerDisplayName = builder.providerDisplayName;
        this.modelName = builder.modelName;
        this.startedAt = builder.startedAt;
        this.completedAt = builder.completedAt;
        this.toolCalls = builder.toolCalls != null
                ? Collections.unmodifiableList(builder.toolCalls)
                : Collections.emptyList();
        this.totalToolCalls = builder.totalToolCalls;
        this.toolCallCounts = builder.toolCallCounts != null
                ? Collections.unmodifiableMap(builder.toolCallCounts)
                : Collections.emptyMap();
        this.promptTokens = builder.promptTokens;
        this.completionTokens = builder.completionTokens;
        this.totalTokens = builder.totalTokens;
        this.executionTimeMs = builder.executionTimeMs;
        this.agentSteps = builder.agentSteps;
        this.success = builder.success;
        this.finalResponse = builder.finalResponse;
        this.errorMessage = builder.errorMessage;
        this.traceRunId = builder.traceRunId;
        this.assertionResults = builder.assertionResults != null
                ? Collections.unmodifiableList(builder.assertionResults)
                : Collections.emptyList();
    }

    // -- Getters --

    public String getScenarioId() {
        return scenarioId;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getProviderDisplayName() {
        return providerDisplayName;
    }

    public String getModelName() {
        return modelName;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public List<ToolCallRecord> getToolCalls() {
        return toolCalls;
    }

    public int getTotalToolCalls() {
        return totalToolCalls;
    }

    public Map<String, Integer> getToolCallCounts() {
        return toolCallCounts;
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public int getTotalTokens() {
        return totalTokens;
    }

    public long getExecutionTimeMs() {
        return executionTimeMs;
    }

    public int getAgentSteps() {
        return agentSteps;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getFinalResponse() {
        return finalResponse;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getTraceRunId() {
        return traceRunId;
    }

    public List<AssertionResult> getAssertionResults() {
        return assertionResults;
    }

    /**
     * @return {@code true} if all assertions passed
     */
    public boolean allAssertionsPassed() {
        return assertionResults.stream().allMatch(AssertionResult::passed);
    }

    /**
     * @return count of failed assertions
     */
    public long failedAssertionCount() {
        return assertionResults.stream().filter(a -> !a.passed()).count();
    }

    /**
     * @return ordered list of tool names in the order they were called
     */
    public List<String> getToolCallSequence() {
        return toolCalls.stream()
                .map(ToolCallRecord::toolName)
                .toList();
    }

    // -- Inner types --

    /**
     * Record of a single tool invocation during the benchmark run.
     */
    public record ToolCallRecord(
            String toolName,
            String callId,
            Map<String, Object> arguments,
            boolean toolSuccess,
            long executionTimeMs) {

        public ToolCallRecord {
            arguments = arguments != null
                    ? Collections.unmodifiableMap(arguments)
                    : Collections.emptyMap();
        }
    }

    // -- Builder --

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String scenarioId;
        private String providerId;
        private String providerDisplayName;
        private String modelName;
        private Instant startedAt;
        private Instant completedAt;
        private List<ToolCallRecord> toolCalls;
        private int totalToolCalls;
        private Map<String, Integer> toolCallCounts;
        private int promptTokens;
        private int completionTokens;
        private int totalTokens;
        private long executionTimeMs;
        private int agentSteps;
        private boolean success;
        private String finalResponse;
        private String errorMessage;
        private String traceRunId;
        private List<AssertionResult> assertionResults;

        private Builder() {
        }

        public Builder scenarioId(String val) { this.scenarioId = val; return this; }
        public Builder providerId(String val) { this.providerId = val; return this; }
        public Builder providerDisplayName(String val) { this.providerDisplayName = val; return this; }
        public Builder modelName(String val) { this.modelName = val; return this; }
        public Builder startedAt(Instant val) { this.startedAt = val; return this; }
        public Builder completedAt(Instant val) { this.completedAt = val; return this; }
        public Builder toolCalls(List<ToolCallRecord> val) { this.toolCalls = val; return this; }
        public Builder totalToolCalls(int val) { this.totalToolCalls = val; return this; }
        public Builder toolCallCounts(Map<String, Integer> val) { this.toolCallCounts = val; return this; }
        public Builder promptTokens(int val) { this.promptTokens = val; return this; }
        public Builder completionTokens(int val) { this.completionTokens = val; return this; }
        public Builder totalTokens(int val) { this.totalTokens = val; return this; }
        public Builder executionTimeMs(long val) { this.executionTimeMs = val; return this; }
        public Builder agentSteps(int val) { this.agentSteps = val; return this; }
        public Builder success(boolean val) { this.success = val; return this; }
        public Builder finalResponse(String val) { this.finalResponse = val; return this; }
        public Builder errorMessage(String val) { this.errorMessage = val; return this; }
        public Builder traceRunId(String val) { this.traceRunId = val; return this; }
        public Builder assertionResults(List<AssertionResult> val) { this.assertionResults = val; return this; }

        public BenchmarkRun build() {
            if (toolCallCounts == null && toolCalls != null) {
                toolCallCounts = new LinkedHashMap<>();
                for (ToolCallRecord tc : toolCalls) {
                    toolCallCounts.merge(tc.toolName(), 1, Integer::sum);
                }
                totalToolCalls = toolCalls.size();
            }
            return new BenchmarkRun(this);
        }
    }
}
