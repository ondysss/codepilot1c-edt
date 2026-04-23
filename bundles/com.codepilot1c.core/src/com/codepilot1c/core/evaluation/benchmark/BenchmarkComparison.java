package com.codepilot1c.core.evaluation.benchmark;

import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Comparison result for a single scenario executed across multiple providers.
 */
public class BenchmarkComparison {

    private final String scenarioId;
    private final String scenarioTitle;
    private final String prompt;
    private final List<BenchmarkRun> runs;
    private final Instant timestamp;

    public BenchmarkComparison(String scenarioId, String scenarioTitle, String prompt,
            List<BenchmarkRun> runs) {
        this.scenarioId = scenarioId;
        this.scenarioTitle = scenarioTitle;
        this.prompt = prompt;
        this.runs = Collections.unmodifiableList(runs);
        this.timestamp = Instant.now();
    }

    public String getScenarioId() {
        return scenarioId;
    }

    public String getScenarioTitle() {
        return scenarioTitle;
    }

    public String getPrompt() {
        return prompt;
    }

    public List<BenchmarkRun> getRuns() {
        return runs;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * @return the run that used the fewest tokens among successful runs
     */
    public Optional<BenchmarkRun> getMostTokenEfficient() {
        return runs.stream()
                .filter(BenchmarkRun::isSuccess)
                .filter(r -> r.allAssertionsPassed())
                .min(Comparator.comparingInt(BenchmarkRun::getTotalTokens));
    }

    /**
     * @return the fastest successful run
     */
    public Optional<BenchmarkRun> getFastest() {
        return runs.stream()
                .filter(BenchmarkRun::isSuccess)
                .filter(r -> r.allAssertionsPassed())
                .min(Comparator.comparingLong(BenchmarkRun::getExecutionTimeMs));
    }

    /**
     * @return the run with the fewest tool calls among successful runs
     */
    public Optional<BenchmarkRun> getFewestToolCalls() {
        return runs.stream()
                .filter(BenchmarkRun::isSuccess)
                .filter(r -> r.allAssertionsPassed())
                .min(Comparator.comparingInt(BenchmarkRun::getTotalToolCalls));
    }

    /**
     * @return how many providers passed all assertions
     */
    public long passedCount() {
        return runs.stream()
                .filter(r -> r.isSuccess() && r.allAssertionsPassed())
                .count();
    }

    /**
     * @return total number of provider runs
     */
    public int totalRuns() {
        return runs.size();
    }
}
