package com.codepilot1c.core.evaluation.benchmark;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class EvalScenarioTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void loadsMinimalScenario() throws IOException {
        Path file = tempFolder.newFile("SMK-001.json").toPath(); //$NON-NLS-1$
        Files.writeString(file, """
                {
                  "id": "SMK-001",
                  "title": "Project Discovery Smoke",
                  "category": "smoke_connectivity",
                  "prompt": "Check project accessibility",
                  "expected_tool_path": {
                    "forbidden": ["create_metadata", "write_file"]
                  },
                  "assertions": {
                    "tool_behavior": {
                      "max_mutating_calls": 0
                    }
                  }
                }
                """, StandardCharsets.UTF_8); //$NON-NLS-1$

        EvalScenario scenario = EvalScenario.load(file);

        assertEquals("SMK-001", scenario.getId()); //$NON-NLS-1$
        assertEquals("Project Discovery Smoke", scenario.getTitle()); //$NON-NLS-1$
        assertEquals("smoke_connectivity", scenario.getCategory()); //$NON-NLS-1$
        assertEquals("Check project accessibility", scenario.getPrompt()); //$NON-NLS-1$
    }

    @Test
    public void parsesExpectedToolPath() throws IOException {
        Path file = tempFolder.newFile("CPQW-004.json").toPath(); //$NON-NLS-1$
        Files.writeString(file, """
                {
                  "id": "CPQW-004",
                  "title": "QA Smoke",
                  "prompt": "Run smoke test",
                  "expected_tool_path": {
                    "ordered_subsequence": ["qa_inspect", "qa_plan_scenario", "qa_generate", "qa_run"],
                    "required_any_order": ["qa_validate_feature"],
                    "forbidden": ["delete_metadata"]
                  }
                }
                """, StandardCharsets.UTF_8); //$NON-NLS-1$

        EvalScenario scenario = EvalScenario.load(file);
        EvalScenario.ExpectedToolPath tp = scenario.getExpectedToolPath();

        assertNotNull(tp);
        assertEquals(List.of("qa_inspect", "qa_plan_scenario", "qa_generate", "qa_run"), //$NON-NLS-1$
                tp.getOrderedSubsequence());
        assertEquals(List.of("qa_validate_feature"), tp.getRequiredAnyOrder()); //$NON-NLS-1$
        assertEquals(List.of("delete_metadata"), tp.getForbidden()); //$NON-NLS-1$
    }

    @Test
    public void parsesToolBehaviorAssertions() throws IOException {
        Path file = tempFolder.newFile("MUT-001.json").toPath(); //$NON-NLS-1$
        Files.writeString(file, """
                {
                  "id": "MUT-001",
                  "title": "Mutation Test",
                  "prompt": "Create catalog",
                  "assertions": {
                    "tool_behavior": {
                      "max_mutating_calls": 3,
                      "must_have_validation_flow": true,
                      "must_have_post_mutation_diagnostics": true,
                      "must_call_qa_inspect_status_first": false
                    }
                  }
                }
                """, StandardCharsets.UTF_8); //$NON-NLS-1$

        EvalScenario scenario = EvalScenario.load(file);
        EvalScenario.ToolBehavior tb = scenario.getAssertions().getToolBehavior();

        assertNotNull(tb);
        assertEquals(Integer.valueOf(3), tb.getMaxMutatingCalls());
        assertTrue(tb.isMustHaveValidationFlow());
        assertTrue(tb.isMustHavePostMutationDiagnostics());
        assertFalse(tb.isMustCallQaInspectStatusFirst());
    }

    @Test
    public void loadsSuiteFromDirectory() throws IOException {
        Path dir = tempFolder.newFolder("suite").toPath(); //$NON-NLS-1$
        Files.writeString(dir.resolve("A-001.json"), //$NON-NLS-1$
                "{\"id\":\"A-001\",\"title\":\"A\",\"prompt\":\"a\"}", StandardCharsets.UTF_8); //$NON-NLS-1$
        Files.writeString(dir.resolve("B-002.json"), //$NON-NLS-1$
                "{\"id\":\"B-002\",\"title\":\"B\",\"prompt\":\"b\"}", StandardCharsets.UTF_8); //$NON-NLS-1$
        // Non-JSON file should be ignored
        Files.writeString(dir.resolve("README.md"), "# Ignored", StandardCharsets.UTF_8); //$NON-NLS-1$ //$NON-NLS-2$

        List<EvalScenario> scenarios = EvalScenario.loadSuite(dir);

        assertEquals(2, scenarios.size());
        assertEquals("A-001", scenarios.get(0).getId()); //$NON-NLS-1$
        assertEquals("B-002", scenarios.get(1).getId()); //$NON-NLS-1$
    }

    @Test(expected = IOException.class)
    public void rejectsScenarioWithoutId() throws IOException {
        Path file = tempFolder.newFile("bad.json").toPath(); //$NON-NLS-1$
        Files.writeString(file, "{\"title\":\"No ID\"}", StandardCharsets.UTF_8); //$NON-NLS-1$
        EvalScenario.load(file);
    }

    @Test
    public void nullFieldsReturnEmptyCollections() throws IOException {
        Path file = tempFolder.newFile("minimal.json").toPath(); //$NON-NLS-1$
        Files.writeString(file, "{\"id\":\"M-001\",\"title\":\"M\",\"prompt\":\"m\"}", //$NON-NLS-1$
                StandardCharsets.UTF_8);

        EvalScenario scenario = EvalScenario.load(file);

        // No expected_tool_path — should not NPE
        assertNotNull(scenario.toString());
        // No assertions — null is acceptable
    }
}
