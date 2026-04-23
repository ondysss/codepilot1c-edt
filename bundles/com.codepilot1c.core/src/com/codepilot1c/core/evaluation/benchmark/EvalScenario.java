package com.codepilot1c.core.evaluation.benchmark;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;

/**
 * Deserialized evaluation scenario loaded from {@code evals/} JSON files.
 * <p>
 * Supports the full scenario schema including tool path expectations,
 * behavior assertions, and workspace state checks.
 */
public class EvalScenario {

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    private String id;
    private String title;
    private String category;
    private String channel;

    @SerializedName("risk_level")
    private String riskLevel;

    private String prompt;

    @SerializedName("expected_tool_path")
    private ExpectedToolPath expectedToolPath;

    private Assertions assertions;

    // -- Factory methods --

    public static EvalScenario load(Path jsonFile) throws IOException {
        try (Reader reader = Files.newBufferedReader(jsonFile, StandardCharsets.UTF_8)) {
            EvalScenario scenario = GSON.fromJson(reader, EvalScenario.class);
            if (scenario.id == null || scenario.id.isBlank()) {
                throw new IOException("Scenario file missing 'id': " + jsonFile); //$NON-NLS-1$
            }
            return scenario;
        }
    }

    public static List<EvalScenario> loadSuite(Path suiteDirectory) throws IOException {
        List<EvalScenario> scenarios = new ArrayList<>();
        try (var stream = Files.list(suiteDirectory)) {
            stream.filter(p -> p.toString().endsWith(".json")) //$NON-NLS-1$
                    .sorted()
                    .forEach(p -> {
                        try {
                            scenarios.add(load(p));
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to load scenario: " + p, e); //$NON-NLS-1$
                        }
                    });
        }
        return Collections.unmodifiableList(scenarios);
    }

    // -- Getters --

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getCategory() {
        return category;
    }

    public String getChannel() {
        return channel;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public String getPrompt() {
        return prompt;
    }

    public ExpectedToolPath getExpectedToolPath() {
        return expectedToolPath;
    }

    public Assertions getAssertions() {
        return assertions;
    }

    // -- Inner model classes --

    public static class ExpectedToolPath {

        @SerializedName("ordered_subsequence")
        private List<String> orderedSubsequence;

        @SerializedName("required_any_order")
        private List<String> requiredAnyOrder;

        private List<String> forbidden;
        private List<String> allowed;

        @SerializedName("required_rules")
        private List<String> requiredRules;

        public List<String> getOrderedSubsequence() {
            return orderedSubsequence != null ? orderedSubsequence : Collections.emptyList();
        }

        public List<String> getRequiredAnyOrder() {
            return requiredAnyOrder != null ? requiredAnyOrder : Collections.emptyList();
        }

        public List<String> getForbidden() {
            return forbidden != null ? forbidden : Collections.emptyList();
        }

        public List<String> getAllowed() {
            return allowed != null ? allowed : Collections.emptyList();
        }

        public List<String> getRequiredRules() {
            return requiredRules != null ? requiredRules : Collections.emptyList();
        }
    }

    public static class Assertions {

        @SerializedName("tool_behavior")
        private ToolBehavior toolBehavior;

        @SerializedName("workspace_state")
        private Map<String, Object> workspaceState;

        @SerializedName("final_answer")
        private FinalAnswer finalAnswer;

        public ToolBehavior getToolBehavior() {
            return toolBehavior;
        }

        public Map<String, Object> getWorkspaceState() {
            return workspaceState != null ? workspaceState : Collections.emptyMap();
        }

        public FinalAnswer getFinalAnswer() {
            return finalAnswer;
        }
    }

    public static class ToolBehavior {

        @SerializedName("max_mutating_calls")
        private Integer maxMutatingCalls;

        @SerializedName("must_have_validation_flow")
        private boolean mustHaveValidationFlow;

        @SerializedName("must_have_post_mutation_diagnostics")
        private boolean mustHavePostMutationDiagnostics;

        @SerializedName("must_call_qa_inspect_status_first")
        private boolean mustCallQaInspectStatusFirst;

        public Integer getMaxMutatingCalls() {
            return maxMutatingCalls;
        }

        public boolean isMustHaveValidationFlow() {
            return mustHaveValidationFlow;
        }

        public boolean isMustHavePostMutationDiagnostics() {
            return mustHavePostMutationDiagnostics;
        }

        public boolean isMustCallQaInspectStatusFirst() {
            return mustCallQaInspectStatusFirst;
        }
    }

    public static class FinalAnswer {

        @SerializedName("must_mention")
        private List<String> mustMention;

        @SerializedName("must_include_sections")
        private List<String> mustIncludeSections;

        @SerializedName("must_report_diagnostics_status")
        private boolean mustReportDiagnosticsStatus;

        @SerializedName("must_report_junit_path")
        private boolean mustReportJunitPath;

        @SerializedName("must_report_qa_inspect_status")
        private boolean mustReportQaInspectStatus;

        public List<String> getMustMention() {
            return mustMention != null ? mustMention : Collections.emptyList();
        }

        public List<String> getMustIncludeSections() {
            return mustIncludeSections != null ? mustIncludeSections : Collections.emptyList();
        }

        public boolean isMustReportDiagnosticsStatus() {
            return mustReportDiagnosticsStatus;
        }

        public boolean isMustReportJunitPath() {
            return mustReportJunitPath;
        }

        public boolean isMustReportQaInspectStatus() {
            return mustReportQaInspectStatus;
        }
    }

    @Override
    public String toString() {
        return "EvalScenario[" + id + ": " + title + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }
}
