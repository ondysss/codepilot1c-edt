/**
 * Model benchmarking infrastructure for automated comparison of LLM providers.
 * <p>
 * Core workflow:
 * <ol>
 *   <li>Load eval scenarios from {@code evals/} JSON files via {@link EvalScenario}</li>
 *   <li>Run scenarios against multiple providers via {@link ModelBenchmarkRunner}</li>
 *   <li>Evaluate assertions via {@link AssertionEvaluator}</li>
 *   <li>Generate HTML comparison report via {@link BenchmarkReportGenerator}</li>
 * </ol>
 *
 * @see com.codepilot1c.core.evaluation.trace
 */
package com.codepilot1c.core.evaluation.benchmark;
