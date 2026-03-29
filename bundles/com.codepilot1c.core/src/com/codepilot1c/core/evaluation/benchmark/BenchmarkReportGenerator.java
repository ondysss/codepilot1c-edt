package com.codepilot1c.core.evaluation.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.codepilot1c.core.evaluation.benchmark.ModelBenchmarkRunner.BenchmarkSuiteResult;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Generates an HTML comparison report from benchmark suite results.
 * <p>
 * The report includes:
 * <ul>
 *   <li>Suite-level summary with per-provider pass rates</li>
 *   <li>Per-scenario comparison table: provider x metrics</li>
 *   <li>Detailed assertion results per run</li>
 *   <li>Token usage and timing comparison</li>
 * </ul>
 */
public class BenchmarkReportGenerator {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(BenchmarkReportGenerator.class);
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss") //$NON-NLS-1$
            .withZone(ZoneId.systemDefault());

    private BenchmarkReportGenerator() {
    }

    /**
     * Generate HTML report and write to the specified path.
     *
     * @param result the suite result
     * @param outputFile path to write the HTML report
     * @throws IOException if write fails
     */
    public static void generate(BenchmarkSuiteResult result, Path outputFile) throws IOException {
        String html = buildHtml(result);
        Files.createDirectories(outputFile.getParent());
        Files.writeString(outputFile, html, StandardCharsets.UTF_8);
        LOG.info("Benchmark report written to %s", outputFile); //$NON-NLS-1$
    }

    /**
     * Generate HTML report as a string.
     */
    public static String buildHtml(BenchmarkSuiteResult result) {
        StringBuilder sb = new StringBuilder(16384);
        sb.append("<!DOCTYPE html>\n<html lang=\"ru\">\n<head>\n"); //$NON-NLS-1$
        sb.append("<meta charset=\"UTF-8\">\n"); //$NON-NLS-1$
        sb.append("<title>Model Benchmark Report</title>\n"); //$NON-NLS-1$
        appendStyles(sb);
        sb.append("</head>\n<body>\n"); //$NON-NLS-1$

        appendHeader(sb, result);
        appendSummaryTable(sb, result);

        for (BenchmarkComparison comparison : result.comparisons()) {
            appendScenarioSection(sb, comparison, result.providerIds());
        }

        sb.append("</body>\n</html>\n"); //$NON-NLS-1$
        return sb.toString();
    }

    // -- HTML sections --

    private static void appendHeader(StringBuilder sb, BenchmarkSuiteResult result) {
        sb.append("<h1>Model Benchmark Report</h1>\n"); //$NON-NLS-1$
        sb.append("<div class=\"meta\">\n"); //$NON-NLS-1$
        sb.append("  <p>Completed: ").append(TS_FMT.format(result.completedAt())).append("</p>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("  <p>Scenarios: ").append(result.totalScenarios()); //$NON-NLS-1$
        sb.append(" | Providers: ").append(result.providerIds().size()).append("</p>\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("</div>\n"); //$NON-NLS-1$
    }

    private static void appendSummaryTable(StringBuilder sb, BenchmarkSuiteResult result) {
        sb.append("<h2>Summary</h2>\n"); //$NON-NLS-1$
        sb.append("<table class=\"summary\">\n<thead><tr>"); //$NON-NLS-1$
        sb.append("<th>Provider</th><th>Pass Rate</th><th>Avg Tokens</th>"); //$NON-NLS-1$
        sb.append("</tr></thead>\n<tbody>\n"); //$NON-NLS-1$

        Map<String, Double> passRates = result.passRateByProvider();
        Map<String, Double> avgTokens = result.avgTokensByProvider();

        for (String pid : result.providerIds()) {
            double rate = passRates.getOrDefault(pid, 0.0);
            double tokens = avgTokens.getOrDefault(pid, 0.0);
            String rateClass = rate >= 0.8 ? "pass" : rate >= 0.5 ? "warn" : "fail"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

            sb.append("<tr>"); //$NON-NLS-1$
            sb.append("<td>").append(esc(pid)).append("</td>"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("<td class=\"").append(rateClass).append("\">"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append(String.format(Locale.ROOT, "%.0f%%", rate * 100)).append("</td>"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("<td>").append(String.format(Locale.ROOT, "%.0f", tokens)).append("</td>"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("</tr>\n"); //$NON-NLS-1$
        }
        sb.append("</tbody></table>\n"); //$NON-NLS-1$
    }

    private static void appendScenarioSection(StringBuilder sb, BenchmarkComparison comparison,
            List<String> providerOrder) {
        sb.append("<h2 id=\"").append(esc(comparison.getScenarioId())).append("\">"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(esc(comparison.getScenarioId()));
        sb.append(": ").append(esc(comparison.getScenarioTitle())); //$NON-NLS-1$
        sb.append("</h2>\n"); //$NON-NLS-1$

        sb.append("<div class=\"prompt\">").append(esc(comparison.getPrompt())).append("</div>\n"); //$NON-NLS-1$ //$NON-NLS-2$

        // Comparison table
        sb.append("<table class=\"comparison\">\n<thead><tr>"); //$NON-NLS-1$
        sb.append("<th>Provider</th><th>Result</th><th>Steps</th>"); //$NON-NLS-1$
        sb.append("<th>Tool Calls</th><th>Tokens</th><th>Time</th>"); //$NON-NLS-1$
        sb.append("<th>Failed Assertions</th>"); //$NON-NLS-1$
        sb.append("</tr></thead>\n<tbody>\n"); //$NON-NLS-1$

        for (BenchmarkRun run : comparison.getRuns()) {
            boolean passed = run.isSuccess() && run.allAssertionsPassed();
            String statusClass = passed ? "pass" : "fail"; //$NON-NLS-1$ //$NON-NLS-2$
            String statusIcon = passed ? "\u2713 PASS" : "\u2717 FAIL"; //$NON-NLS-1$ //$NON-NLS-2$

            sb.append("<tr>"); //$NON-NLS-1$
            sb.append("<td>").append(esc(run.getProviderDisplayName() != null //$NON-NLS-1$
                    ? run.getProviderDisplayName() : run.getProviderId())).append("</td>"); //$NON-NLS-1$
            sb.append("<td class=\"").append(statusClass).append("\">").append(statusIcon).append("</td>"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            sb.append("<td>").append(run.getAgentSteps()).append("</td>"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("<td>").append(run.getTotalToolCalls()).append("</td>"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("<td>").append(formatTokens(run.getTotalTokens())).append("</td>"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("<td>").append(formatTime(run.getExecutionTimeMs())).append("</td>"); //$NON-NLS-1$ //$NON-NLS-2$

            // Failed assertions detail
            sb.append("<td>"); //$NON-NLS-1$
            List<AssertionResult> failures = run.getAssertionResults().stream()
                    .filter(a -> !a.passed())
                    .toList();
            if (failures.isEmpty()) {
                sb.append("-"); //$NON-NLS-1$
            } else {
                sb.append("<ul class=\"failures\">"); //$NON-NLS-1$
                for (AssertionResult f : failures) {
                    sb.append("<li><b>").append(esc(f.assertionName())).append("</b>"); //$NON-NLS-1$ //$NON-NLS-2$
                    if (f.detail() != null) {
                        sb.append(": ").append(esc(f.detail())); //$NON-NLS-1$
                    }
                    sb.append("</li>"); //$NON-NLS-1$
                }
                sb.append("</ul>"); //$NON-NLS-1$
            }
            sb.append("</td>"); //$NON-NLS-1$
            sb.append("</tr>\n"); //$NON-NLS-1$
        }
        sb.append("</tbody></table>\n"); //$NON-NLS-1$

        // Tool call sequence per provider
        sb.append("<details><summary>Tool call sequences</summary>\n"); //$NON-NLS-1$
        for (BenchmarkRun run : comparison.getRuns()) {
            String name = run.getProviderDisplayName() != null
                    ? run.getProviderDisplayName() : run.getProviderId();
            sb.append("<h4>").append(esc(name)).append("</h4>\n"); //$NON-NLS-1$ //$NON-NLS-2$
            sb.append("<ol class=\"tool-seq\">\n"); //$NON-NLS-1$
            for (BenchmarkRun.ToolCallRecord tc : run.getToolCalls()) {
                String tcClass = tc.toolSuccess() ? "tc-ok" : "tc-err"; //$NON-NLS-1$ //$NON-NLS-2$
                sb.append("<li class=\"").append(tcClass).append("\">"); //$NON-NLS-1$ //$NON-NLS-2$
                sb.append(esc(tc.toolName()));
                sb.append(" <span class=\"tc-time\">").append(tc.executionTimeMs()).append("ms</span>"); //$NON-NLS-1$ //$NON-NLS-2$
                sb.append("</li>\n"); //$NON-NLS-1$
            }
            sb.append("</ol>\n"); //$NON-NLS-1$
        }
        sb.append("</details>\n"); //$NON-NLS-1$
    }

    // -- CSS --

    private static void appendStyles(StringBuilder sb) {
        sb.append("<style>\n"); //$NON-NLS-1$
        sb.append("""
                :root { --bg: #1e1e2e; --fg: #cdd6f4; --surface: #313244; --accent: #89b4fa;
                        --green: #a6e3a1; --red: #f38ba8; --yellow: #f9e2af; --border: #45475a; }
                body { font-family: 'JetBrains Mono', 'Fira Code', monospace; background: var(--bg);
                       color: var(--fg); margin: 2rem; line-height: 1.6; }
                h1 { color: var(--accent); border-bottom: 2px solid var(--accent); padding-bottom: 0.5rem; }
                h2 { color: var(--fg); margin-top: 2rem; }
                .meta { color: #a6adc8; font-size: 0.9rem; }
                .prompt { background: var(--surface); padding: 1rem; border-radius: 8px;
                          border-left: 4px solid var(--accent); margin: 1rem 0; white-space: pre-wrap; }
                table { border-collapse: collapse; width: 100%; margin: 1rem 0; }
                th, td { padding: 0.6rem 1rem; text-align: left; border: 1px solid var(--border); }
                th { background: var(--surface); color: var(--accent); }
                tr:hover { background: rgba(137, 180, 250, 0.05); }
                .pass { color: var(--green); font-weight: bold; }
                .fail { color: var(--red); font-weight: bold; }
                .warn { color: var(--yellow); font-weight: bold; }
                .failures { margin: 0; padding-left: 1.2rem; font-size: 0.85rem; }
                .failures li { color: var(--red); }
                .failures b { color: var(--yellow); }
                details { margin: 1rem 0; }
                summary { cursor: pointer; color: var(--accent); font-weight: bold; }
                .tool-seq { font-size: 0.85rem; }
                .tool-seq li { margin: 0.2rem 0; }
                .tc-ok { color: var(--green); }
                .tc-err { color: var(--red); }
                .tc-time { color: #a6adc8; font-size: 0.8rem; }
                """); //$NON-NLS-1$
        sb.append("</style>\n"); //$NON-NLS-1$
    }

    // -- Formatting helpers --

    private static String formatTokens(int tokens) {
        if (tokens >= 1000) {
            return String.format(Locale.ROOT, "%.1fk", tokens / 1000.0); //$NON-NLS-1$
        }
        return String.valueOf(tokens);
    }

    private static String formatTime(long ms) {
        if (ms >= 60_000) {
            return String.format(Locale.ROOT, "%.1fm", ms / 60_000.0); //$NON-NLS-1$
        }
        if (ms >= 1000) {
            return String.format(Locale.ROOT, "%.1fs", ms / 1000.0); //$NON-NLS-1$
        }
        return ms + "ms"; //$NON-NLS-1$
    }

    private static String esc(String text) {
        if (text == null) {
            return ""; //$NON-NLS-1$
        }
        return text.replace("&", "&amp;") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("<", "&lt;") //$NON-NLS-1$ //$NON-NLS-2$
                .replace(">", "&gt;") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("\"", "&quot;"); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
