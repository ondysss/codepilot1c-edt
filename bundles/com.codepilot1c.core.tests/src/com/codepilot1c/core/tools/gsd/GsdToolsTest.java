/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.gsd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestName;

import com.codepilot1c.core.agent.profiles.AgentCapability;
import com.codepilot1c.core.filesystem.SecureDirectoryMutation;
import com.codepilot1c.core.filesystem.SecureDirectoryCapabilityException;
import com.codepilot1c.core.gsd.GsdTestSupport;
import com.codepilot1c.core.gsd.RequiresSecureMutation;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolExecutionContext;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for GSD tool schemas, registration, names, metadata, and execution.
 */
public class GsdToolsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Rule
    public TestName testName = new TestName();

    private String projectPath;

    @Before
    public void setUp() throws IOException {
        projectPath = GsdTestSupport.projectForTest(getClass(), testName.getMethodName(),
                tmp.newFolder("project").toPath()).toString(); //$NON-NLS-1$
    }

    /** Executes through the same request-local identity and full token used by AgentRunner. */
    private CompletableFuture<ToolResult> execute(ITool tool, Map<String, Object> parameters) {
        Map<String, Object> effective = new LinkedHashMap<>(parameters);
        if (tool.isMutating()) {
            effective.putIfAbsent("expected_cycle_id", "cycle-1"); //$NON-NLS-1$ //$NON-NLS-2$
            effective.putIfAbsent("expected_generation", 0L); //$NON-NLS-1$
        }
        if (tool instanceof GsdCreatePlanTool) {
            effective.putIfAbsent("acceptance_criteria", List.of(Map.of( //$NON-NLS-1$
                    "id", "ac-1", //$NON-NLS-1$ //$NON-NLS-2$
                    "description", "release checks pass", //$NON-NLS-1$ //$NON-NLS-2$
                    "required", true))); //$NON-NLS-1$
        }
        ToolExecutionContext context = new ToolExecutionContext(
                "gsd-test", AgentCapability.MUTATING, 0, projectPath, "session-test"); //$NON-NLS-1$ //$NON-NLS-2$
        return tool.execute(effective, context);
    }

    private Map<String, Object> populatedTokenParams(Map<String, Object> primary) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("project_path", projectPath); //$NON-NLS-1$
        result.put("expected_cycle_id", "portable-cycle"); //$NON-NLS-1$ //$NON-NLS-2$
        result.put("expected_generation", 3L); //$NON-NLS-1$
        result.put("expected_revision", 7L); //$NON-NLS-1$
        result.putAll(primary);
        return result;
    }

    private static Map<String, String> treeSnapshot(Path root) throws IOException {
        Map<String, String> result = new TreeMap<>();
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                if (path.equals(root)) {
                    continue;
                }
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    result.put(relative, "directory"); //$NON-NLS-1$
                } else if (Files.isSymbolicLink(path)) {
                    result.put(relative, "link:" + Files.readSymbolicLink(path)); //$NON-NLS-1$
                } else {
                    result.put(relative, Base64.getEncoder().encodeToString(
                            Files.readAllBytes(path)));
                }
            }
        }
        return result;
    }

    @Test
    public void gsdCapabilityCauseWalkTerminatesOnIdentityCycle() {
        IOException first = new IOException("first"); //$NON-NLS-1$
        IOException second = new IOException("second"); //$NON-NLS-1$
        first.initCause(second);
        second.initCause(first);

        ToolResult ordinary = GsdToolSupport.ioFailure("cycle", "", first); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("io", ordinary.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$

        SecureDirectoryCapabilityException capability =
                new SecureDirectoryCapabilityException(Path.of("state"), "unsupported"); //$NON-NLS-1$ //$NON-NLS-2$
        IOException wrapper = new IOException("wrapper", capability); //$NON-NLS-1$
        capability.initCause(wrapper);
        ToolResult unsupported = GsdToolSupport.ioFailure(
                "cycle", "", wrapper); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("unsupported", unsupported.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- Registry registration -------------------------------------------

    @Test
    public void allGsdToolsRegisteredInDefaultTools() {
        ToolRegistry registry = ToolRegistry.getInstance();
        assertNotNull(registry.getTool("gsd_get_state")); //$NON-NLS-1$
        assertNotNull(registry.getTool("gsd_record_decision")); //$NON-NLS-1$
        assertNotNull(registry.getTool("gsd_create_plan")); //$NON-NLS-1$
        assertNotNull(registry.getTool("gsd_update_task")); //$NON-NLS-1$
        assertNotNull(registry.getTool("gsd_record_evidence")); //$NON-NLS-1$
        assertNotNull(registry.getTool("gsd_transition")); //$NON-NLS-1$
        assertNotNull(registry.getTool("gsd_record_verification_outcome")); //$NON-NLS-1$
        assertNotNull(registry.getTool("gsd_record_shipment")); //$NON-NLS-1$
    }

    @Test
    public void gsdGetStateHasCorrectMeta() {
        GsdGetStateTool tool = new GsdGetStateTool();
        assertEquals("gsd_get_state", tool.getName()); //$NON-NLS-1$
        assertEquals("gsd", tool.getCategory()); //$NON-NLS-1$
        assertFalse(tool.isMutating());
        assertTrue(tool.getTags().contains("gsd")); //$NON-NLS-1$
        assertTrue(tool.getTags().contains("read-only")); //$NON-NLS-1$
        ToolMeta meta = tool.getClass().getAnnotation(ToolMeta.class);
        assertNotNull(meta);
        assertEquals("gsd_get_state", meta.name()); //$NON-NLS-1$
        assertEquals("gsd", meta.category()); //$NON-NLS-1$
        assertFalse(meta.mutating());
    }

    @Test
    public void gsdRecordDecisionHasCorrectMeta() {
        GsdRecordDecisionTool tool = new GsdRecordDecisionTool();
        assertEquals("gsd_record_decision", tool.getName()); //$NON-NLS-1$
        assertTrue(tool.isMutating());
        ToolMeta meta = tool.getClass().getAnnotation(ToolMeta.class);
        assertNotNull(meta);
        assertTrue(meta.mutating());
    }

    @Test
    public void gsdCreatePlanHasCorrectMeta() {
        GsdCreatePlanTool tool = new GsdCreatePlanTool();
        assertEquals("gsd_create_plan", tool.getName()); //$NON-NLS-1$
        assertTrue(tool.isMutating());
    }

    @Test
    public void gsdUpdateTaskHasCorrectMeta() {
        GsdUpdateTaskTool tool = new GsdUpdateTaskTool();
        assertEquals("gsd_update_task", tool.getName()); //$NON-NLS-1$
        assertTrue(tool.isMutating());
    }

    @Test
    public void gsdRecordEvidenceHasCorrectMeta() {
        GsdRecordEvidenceTool tool = new GsdRecordEvidenceTool();
        assertEquals("gsd_record_evidence", tool.getName()); //$NON-NLS-1$
        assertTrue(tool.isMutating());
    }

    @Test
    public void gsdTransitionHasCorrectMeta() {
        GsdTransitionTool tool = new GsdTransitionTool();
        assertEquals("gsd_transition", tool.getName()); //$NON-NLS-1$
        assertTrue(tool.isMutating());
    }

    @Test
    public void verificationAndShipmentToolsAreConfirmedMutations() {
        for (ITool tool : List.of(
                new GsdRecordVerificationOutcomeTool(), new GsdRecordShipmentTool())) {
            assertTrue(tool.getName(), tool.isMutating());
            assertTrue(tool.getName(), tool.requiresConfirmation());
            assertTrue(tool.getName(), tool.isDestructive());
        }
    }

    // ---- Schema alignment ------------------------------------------------

    @Test
    public void allSchemasRequireProjectPath() {
        String[] schemas = {
            new GsdGetStateTool().getParameterSchema(),
            new GsdRecordDecisionTool().getParameterSchema(),
            new GsdCreatePlanTool().getParameterSchema(),
            new GsdUpdateTaskTool().getParameterSchema(),
            new GsdRecordEvidenceTool().getParameterSchema(),
            new GsdTransitionTool().getParameterSchema(),
            new GsdRecordVerificationOutcomeTool().getParameterSchema(),
            new GsdRecordShipmentTool().getParameterSchema(),
        };
        for (String schema : schemas) {
            assertTrue("schema must contain project_path: " + schema, schema.contains("\"project_path\"")); //$NON-NLS-1$
            assertTrue("schema must have additionalProperties=false: " + schema, schema.contains("\"additionalProperties\": false")); //$NON-NLS-1$
        }
    }

    @Test
    public void mutationSchemasRequireExpectedRevision() {
        String[] schemas = {
            new GsdRecordDecisionTool().getParameterSchema(),
            new GsdCreatePlanTool().getParameterSchema(),
            new GsdUpdateTaskTool().getParameterSchema(),
            new GsdRecordEvidenceTool().getParameterSchema(),
            new GsdTransitionTool().getParameterSchema(),
            new GsdRecordVerificationOutcomeTool().getParameterSchema(),
            new GsdRecordShipmentTool().getParameterSchema(),
        };
        for (String schema : schemas) {
            assertTrue("mutation schema must require expected_revision: " + schema, schema.contains("\"expected_revision\"")); //$NON-NLS-1$
            assertTrue("mutation schema must require expected_cycle_id: " + schema, schema.contains("\"expected_cycle_id\"")); //$NON-NLS-1$
            assertTrue("mutation schema must require expected_generation: " + schema, schema.contains("\"expected_generation\"")); //$NON-NLS-1$
        }
    }

    @Test
    public void allMutatingGsdToolsRejectNumericStringsForIntegerTokenFields()
            throws ExecutionException, InterruptedException {
        ITool[] tools = {
            new GsdRecordDecisionTool(), new GsdCreatePlanTool(),
            new GsdUpdateTaskTool(), new GsdRecordEvidenceTool(),
            new GsdTransitionTool(), new GsdRecordVerificationOutcomeTool(),
            new GsdRecordShipmentTool()
        };
        for (ITool tool : tools) {
            ToolResult stringGeneration = execute(tool, Map.of(
                    "project_path", projectPath, //$NON-NLS-1$
                    "expected_cycle_id", "cycle-1", //$NON-NLS-1$ //$NON-NLS-2$
                    "expected_generation", "0", //$NON-NLS-1$ //$NON-NLS-2$
                    "expected_revision", 0L)).get(); //$NON-NLS-1$
            assertFalse(tool.getName(), stringGeneration.isSuccess());
            assertEquals(tool.getName(), "invalid", //$NON-NLS-1$
                    stringGeneration.getStructuredString("error_code")); //$NON-NLS-1$
            assertTrue(tool.getName(), stringGeneration.getErrorMessage()
                    .contains("expected_generation")); //$NON-NLS-1$

            ToolResult stringRevision = execute(tool, Map.of(
                    "project_path", projectPath, //$NON-NLS-1$
                    "expected_cycle_id", "cycle-1", //$NON-NLS-1$ //$NON-NLS-2$
                    "expected_generation", 0L, //$NON-NLS-1$
                    "expected_revision", "0")).get(); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(tool.getName(), stringRevision.isSuccess());
            assertEquals(tool.getName(), "invalid", //$NON-NLS-1$
                    stringRevision.getStructuredString("error_code")); //$NON-NLS-1$
            assertTrue(tool.getName(), stringRevision.getErrorMessage()
                    .contains("expected_revision")); //$NON-NLS-1$
        }
    }

    @Test
    public void allSchemasAreStrictProviderNeutralObjects() {
        ITool[] tools = {
            new GsdGetStateTool(), new GsdRecordDecisionTool(), new GsdCreatePlanTool(),
            new GsdUpdateTaskTool(), new GsdRecordEvidenceTool(), new GsdTransitionTool(),
            new GsdRecordVerificationOutcomeTool(), new GsdRecordShipmentTool()
        };
        for (ITool tool : tools) {
            JsonObject schema = JsonParser.parseString(tool.getParameterSchema()).getAsJsonObject();
            assertEquals(tool.getName(), "object", schema.get("type").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
            assertTrue(tool.getName(), schema.has("properties")); //$NON-NLS-1$
            assertTrue(tool.getName(), schema.has("required")); //$NON-NLS-1$
            assertFalse(tool.getName(), schema.get("additionalProperties").getAsBoolean()); //$NON-NLS-1$
        }
    }

    @Test
    public void runtimeRejectsUnknownTopLevelProperty()
            throws ExecutionException, InterruptedException {
        ToolResult result = execute(new GsdGetStateTool(), Map.of(
                "project_path", projectPath, "unexpected", true)).get(); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void readOnlySchemaDoesNotRequireExpectedRevision() {
        String schema = new GsdGetStateTool().getParameterSchema();
        assertFalse("gsd_get_state should not require expected_revision", schema.contains("\"expected_revision\"")); //$NON-NLS-1$
    }

    @Test
    public void gsdUpdateTaskSchemaOnlyExposesStatus() {
        String schema = new GsdUpdateTaskTool().getParameterSchema();
        assertTrue(schema.contains("\"status\"")); //$NON-NLS-1$
        assertFalse("schema must not expose title", schema.contains("\"title\"")); //$NON-NLS-1$
        assertFalse("schema must not expose wave_id", schema.contains("\"wave_id\"")); //$NON-NLS-1$
        assertFalse("schema must not expose depends_on", schema.contains("\"depends_on\"")); //$NON-NLS-1$
        assertFalse("schema must not expose evidence_ids", schema.contains("\"evidence_ids\"")); //$NON-NLS-1$
    }

    @Test
    public void gsdUpdateTaskRequiresStatus() {
        String schema = new GsdUpdateTaskTool().getParameterSchema();
        assertTrue(schema.contains("\"required\"")); //$NON-NLS-1$
        assertTrue(schema.contains("\"status\"")); //$NON-NLS-1$
    }

    @Test
    public void allToolsHaveNonBlankDescription() {
        assertFalse(new GsdGetStateTool().getDescription().isBlank());
        assertFalse(new GsdRecordDecisionTool().getDescription().isBlank());
        assertFalse(new GsdCreatePlanTool().getDescription().isBlank());
        assertFalse(new GsdUpdateTaskTool().getDescription().isBlank());
        assertFalse(new GsdRecordEvidenceTool().getDescription().isBlank());
        assertFalse(new GsdTransitionTool().getDescription().isBlank());
        assertFalse(new GsdRecordVerificationOutcomeTool().getDescription().isBlank());
        assertFalse(new GsdRecordShipmentTool().getDescription().isBlank());
    }

    // ---- gsd_get_state execution -----------------------------------------

    @Test
    public void getStateReturnsFreshState() throws ExecutionException, InterruptedException {
        GsdGetStateTool tool = new GsdGetStateTool();
        ToolResult result = execute(tool, Map.of("project_path", projectPath)).get(); //$NON-NLS-1$
        assertTrue(result.isSuccess());
        assertTrue(result.getContent().contains("DISCOVERY")); //$NON-NLS-1$
        assertTrue(result.getContent().contains("Revision: 0")); //$NON-NLS-1$
        // Structured data must be present with full state
        assertTrue(result.hasStructuredData());
        assertEquals("success", result.getStructuredString("status")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("gsd_get_state", result.getStructuredString("operation")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(result.getStructuredData().get("tasks")); //$NON-NLS-1$
        assertNotNull(result.getStructuredData().get("decisions")); //$NON-NLS-1$
        assertNotNull(result.getStructuredData().get("waves")); //$NON-NLS-1$
        assertNotNull(result.getStructuredData().get("evidence")); //$NON-NLS-1$
        assertNotNull(result.getStructuredData().get("plan")); //$NON-NLS-1$
        assertNotNull(result.getStructuredData().get("acceptance_criteria")); //$NON-NLS-1$
        assertNotNull(result.getStructuredData().get("verification")); //$NON-NLS-1$
        assertNotNull(result.getStructuredData().get("shipment")); //$NON-NLS-1$
        assertNotNull(result.getStructuredData().get("warnings")); //$NON-NLS-1$
        assertNotNull(result.getStructuredData().get("concurrency_token")); //$NON-NLS-1$
        assertNotNull(result.getStructuredData().get("transition_history")); //$NON-NLS-1$
        assertEquals("cycle-1", result.getStructuredString("cycle_id")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, result.getStructuredData().getAsJsonArray("tasks").size()); //$NON-NLS-1$
    }

    @Test
    public void getStateReadsPopulatedAnchoredStateWithoutMutation()
            throws IOException, ExecutionException, InterruptedException {
        Path project = Path.of(projectPath);
        GsdTestSupport.seedPortablePopulatedState(project);
        Map<String, String> before = treeSnapshot(project);

        ToolResult result = execute(new GsdGetStateTool(),
                Map.of("project_path", projectPath)).get(); //$NON-NLS-1$

        assertTrue(result.isSuccess());
        assertEquals("success", result.getStructuredString("status")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("gsd_get_state", result.getStructuredString("operation")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("portable-cycle", result.getStructuredString("cycle_id")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("portable inspection", result.getStructuredString("goal")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(7, result.getStructuredInt("revision", -1)); //$NON-NLS-1$
        assertEquals(1, result.getStructuredData().getAsJsonArray("decisions").size()); //$NON-NLS-1$
        assertEquals(before, treeSnapshot(project));
    }

    @Test
    public void allSevenActualMutatingToolsReachNativeNonSdsCapabilityBoundary()
            throws IOException, ExecutionException, InterruptedException {
        Path project = Path.of(projectPath);
        if (SecureDirectoryMutation.supportsSecureDirectoryStreams(project)) {
            // This contract targets the real non-SDS envelope. Linux secure execution is covered
            // by every mutation lifecycle test without marking this method skipped.
            return;
        }
        GsdTestSupport.seedPortablePopulatedState(project);
        Map<String, String> projectBefore = treeSnapshot(project);
        Path outside = tmp.newFolder("all-tools-non-sds-outside").toPath(); //$NON-NLS-1$
        Files.writeString(outside.resolve("sentinel"), "unchanged"); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, String> outsideBefore = treeSnapshot(outside);

        Map<ITool, Map<String, Object>> invocations = new LinkedHashMap<>();
        invocations.put(new GsdRecordDecisionTool(), populatedTokenParams(Map.of(
                "id", "d2", "summary", "decision", "rationale", "reason", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                "alternatives", List.of()))); //$NON-NLS-1$
        invocations.put(new GsdCreatePlanTool(), populatedTokenParams(Map.of(
                "goal", "valid plan", //$NON-NLS-1$ //$NON-NLS-2$
                "acceptance_criteria", List.of(Map.of( //$NON-NLS-1$
                        "id", "ac-1", "description", "passes", "required", true)), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "tasks", List.of(Map.of( //$NON-NLS-1$
                        "id", "t1", "title", "task", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                        "execution_kind", "READ_ONLY", "wave_id", "w1", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                        "depends_on", List.of())), //$NON-NLS-1$
                "waves", List.of(Map.of( //$NON-NLS-1$
                        "id", "w1", "name", "wave", "goal", "deliver", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                        "task_ids", List.of("t1")))))); //$NON-NLS-1$ //$NON-NLS-2$
        invocations.put(new GsdUpdateTaskTool(), populatedTokenParams(Map.of(
                "task_id", "t1", "status", "IN_PROGRESS"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        invocations.put(new GsdRecordEvidenceTool(), populatedTokenParams(Map.of(
                "id", "e1", "description", "tested", "provenance", "TESTED", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
                "task_ids", List.of()))); //$NON-NLS-1$
        invocations.put(new GsdTransitionTool(), populatedTokenParams(Map.of(
                "target_phase", "PLANNING"))); //$NON-NLS-1$ //$NON-NLS-2$
        invocations.put(new GsdRecordVerificationOutcomeTool(), populatedTokenParams(Map.of(
                "criterion_id", "ac-1", "outcome", "PASSED"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        invocations.put(new GsdRecordShipmentTool(), populatedTokenParams(Map.of(
                "shipment_id", "release-1", //$NON-NLS-1$ //$NON-NLS-2$
                "delivery_reference", "refs/tags/v1", //$NON-NLS-1$ //$NON-NLS-2$
                "status", "IN_PROGRESS"))); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(7, invocations.size());
        for (Map.Entry<ITool, Map<String, Object>> invocation : invocations.entrySet()) {
            ITool tool = invocation.getKey();
            ToolResult result = execute(tool, invocation.getValue()).get();
            assertFalse(tool.getName(), result.isSuccess());
            assertTrue(tool.getName(), result.hasStructuredData());
            assertEquals(tool.getName(), "error", result.getStructuredString("status")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(tool.getName(), tool.getName(),
                    result.getStructuredString("operation")); //$NON-NLS-1$
            assertEquals(tool.getName(), "unsupported", //$NON-NLS-1$
                    result.getStructuredString("error_code")); //$NON-NLS-1$
            assertEquals(tool.getName(), projectBefore, treeSnapshot(project));
            assertEquals(tool.getName(), outsideBefore, treeSnapshot(outside));
        }
    }

    @Test
    public void recordDecisionUsesRealProviderCapabilityAndDeterministicEnvelope()
            throws IOException, ExecutionException, InterruptedException {
        Path project = Path.of(projectPath);
        Files.createDirectories(project.resolve(".codepilot1c/gsd")); //$NON-NLS-1$
        boolean secure = SecureDirectoryMutation.supportsSecureDirectoryStreams(project);
        Map<String, String> before = treeSnapshot(project);

        ToolResult result = execute(new GsdRecordDecisionTool(), Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 0L, //$NON-NLS-1$
                "id", "portable-decision", //$NON-NLS-1$ //$NON-NLS-2$
                "summary", "real tool path", //$NON-NLS-1$ //$NON-NLS-2$
                "rationale", "exercise provider capability")) //$NON-NLS-1$ //$NON-NLS-2$
                .get();

        if (secure) {
            assertTrue(result.getErrorMessage(), result.isSuccess());
            assertEquals(1, result.getStructuredInt("revision", -1)); //$NON-NLS-1$
            assertTrue(Files.exists(project.resolve(".codepilot1c/gsd/state.json"))); //$NON-NLS-1$
        } else {
            assertFalse(result.isSuccess());
            assertTrue(result.hasStructuredData());
            assertEquals("error", result.getStructuredString("status")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("gsd_record_decision", result.getStructuredString("operation")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals("unsupported", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
            assertEquals(0, result.getStructuredInt("revision", -1)); //$NON-NLS-1$
            assertEquals("", result.getStructuredString("phase")); //$NON-NLS-1$ //$NON-NLS-2$
            assertFalse(result.getErrorMessage().contains("pre-create")); //$NON-NLS-1$
            assertEquals(before, treeSnapshot(project));
        }
    }

    @Test
    public void getStateWithDifferentProjectFailsClosed() throws ExecutionException, InterruptedException {
        GsdGetStateTool tool = new GsdGetStateTool();
        ToolResult result = execute(tool, Map.of("project_path", "/nonexistent/path/xyz")).get(); //$NON-NLS-1$
        assertFalse(result.isSuccess());
        assertEquals("identity", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void mutationCannotCrossCapturedProjectBoundary()
            throws IOException, ExecutionException, InterruptedException {
        Path otherProject = tmp.newFolder("other-project").toPath(); //$NON-NLS-1$
        ToolResult result = execute(new GsdRecordDecisionTool(), Map.of(
                "project_path", otherProject.toString(), //$NON-NLS-1$
                "expected_revision", 0L, //$NON-NLS-1$
                "id", "d1", "summary", "scope", "rationale", "reason")).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertFalse(result.isSuccess());
        assertEquals("identity", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(Files.exists(otherProject.resolve(".gsd"))); //$NON-NLS-1$
    }

    @Test
    public void getStateWithoutExecutionIdentityFailsClosed()
            throws ExecutionException, InterruptedException {
        ToolResult result = new GsdGetStateTool()
                .execute(Map.of("project_path", projectPath)).get(); //$NON-NLS-1$
        assertFalse(result.isSuccess());
        assertEquals("identity", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void getStateMissingProjectPathFails() throws ExecutionException, InterruptedException {
        GsdGetStateTool tool = new GsdGetStateTool();
        ToolResult result = execute(tool, Map.of()).get();
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("project_path")); //$NON-NLS-1$
    }

    @Test
    public void boundedPreviewLimitsLongGoal() {
        String longGoal = "a".repeat(300);
        String preview = GsdGetStateTool.boundedPreview(longGoal, 240);
        assertTrue("preview must not exceed maxChars", preview.length() <= 240);
        assertTrue(preview.endsWith("\u2026")); //$NON-NLS-1$
    }

    @Test
    public void boundedPreviewShortGoalUnchanged() {
        assertEquals("short", GsdGetStateTool.boundedPreview("short", 240)); //$NON-NLS-1$
    }

    @Test
    public void boundedPreviewExactLengthNoEllipsis() {
        // text.length() == maxChars => returned as-is, no ellipsis.
        String text = "hello";
        assertEquals(text, GsdGetStateTool.boundedPreview(text, 5)); //$NON-NLS-1$
    }

    @Test
    public void boundedPreviewOneOverTruncates() {
        // text.length() == maxChars + 1 => truncated to maxChars.
        String text = "hellox";
        String preview = GsdGetStateTool.boundedPreview(text, 5);
        assertEquals("hell\u2026", preview); //$NON-NLS-1$
        assertEquals(5, preview.length());
    }

    @Test
    public void boundedPreviewNullBecomesNone() {
        assertEquals("(none)", GsdGetStateTool.boundedPreview(null, 240)); //$NON-NLS-1$
    }

    @Test
    public void boundedPreviewEmptyBecomesNone() {
        assertEquals("(none)", GsdGetStateTool.boundedPreview("", 240)); //$NON-NLS-1$
    }

    @Test
    public void boundedPreviewZeroMaxReturnsEmpty() {
        assertEquals("", GsdGetStateTool.boundedPreview("hello", 0)); //$NON-NLS-1$
    }

    @Test
    public void boundedPreviewNegativeMaxReturnsEmpty() {
        assertEquals("", GsdGetStateTool.boundedPreview("hello", -1)); //$NON-NLS-1$
    }

    @Test
    public void boundedPreviewMaxOneTruncatesToEllipsis() {
        // With maxChars=1, only room for the ellipsis itself.
        String result = GsdGetStateTool.boundedPreview("hello", 1);
        assertEquals("\u2026", result); //$NON-NLS-1$
        assertEquals(1, result.length());
    }

    @Test
    public void boundedPreviewDoesNotBreakSurrogatePair() {
        // U+1F600 (grinning face) is a surrogate pair: \uD83D\uDE00
        String emoji = "\uD83D\uDE00"; //$NON-NLS-1$
        String text = "abc" + emoji + "def";
        // text.length() == 8 chars. max=4 means substring budget 3 + ellipsis.
        String preview = GsdGetStateTool.boundedPreview(text, 4);
        assertEquals("abc\u2026", preview); //$NON-NLS-1$
        assertEquals(4, preview.length());
        // Verify no unpaired surrogates.
        assertNoUnpairedSurrogates(preview);
    }

    @Test
    public void boundedPreviewMaxTwoWithEmojiAtCut() {
        // "a\uD83D\uDE00bc", max=3 => budget 2 + ellipsis. Cut at index 2
        // leaves high surrogate orphaned, so back up to 1.
        String text = "a\uD83D\uDE00bc"; //$NON-NLS-1$
        String preview = GsdGetStateTool.boundedPreview(text, 3);
        assertEquals("a\u2026", preview); //$NON-NLS-1$
        assertEquals(2, preview.length());
        assertNoUnpairedSurrogates(preview);
    }

    @Test
    public void boundedPreviewAsciiNeverExceedsMax() {
        String text = "x".repeat(100);
        for (int max = 1; max <= 50; max++) {
            String preview = GsdGetStateTool.boundedPreview(text, max);
            assertTrue("max=" + max + " produced length=" + preview.length(), //$NON-NLS-1$
                    preview.length() <= max);
        }
    }

    private void assertNoUnpairedSurrogates(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertTrue("unpaired high surrogate at index " + i, //$NON-NLS-1$
                        i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1)));
            } else if (Character.isLowSurrogate(c)) {
                assertTrue("unpaired low surrogate at index " + i, //$NON-NLS-1$
                        i > 0 && Character.isHighSurrogate(s.charAt(i - 1)));
            }
        }
    }

    @Test
    @RequiresSecureMutation
    public void getStateReturnsFullStructuredPayloadAfterPopulate() throws ExecutionException, InterruptedException {
        // Transition DISCOVERY -> PLANNING, then create a plan.
        GsdTransitionTool tt = new GsdTransitionTool();
        execute(tt, Map.of("project_path", projectPath, "expected_revision", 0, "target_phase", "PLANNING")).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        GsdCreatePlanTool planTool = new GsdCreatePlanTool();
        execute(planTool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 1, //$NON-NLS-1$
                "goal", "Ship it", //$NON-NLS-1$
                "tasks", List.of(Map.of("id", "t1", "title", "task", "execution_kind", "READ_ONLY", "wave_id", "w1")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "waves", List.of(Map.of("id", "w1", "name", "wave 1", "task_ids", List.of("t1"))))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        GsdGetStateTool tool = new GsdGetStateTool();
        ToolResult result = execute(tool, Map.of("project_path", projectPath)).get(); //$NON-NLS-1$
        assertTrue(result.isSuccess());
        assertTrue(result.hasStructuredData());
        assertEquals("Ship it", result.getStructuredString("goal")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, result.getStructuredData().getAsJsonArray("tasks").size()); //$NON-NLS-1$
        assertEquals(1, result.getStructuredData().getAsJsonArray("waves").size()); //$NON-NLS-1$
        // execution_kind and captured_phase must appear in structured output.
        assertEquals("READ_ONLY", result.getStructuredData().getAsJsonArray("tasks") //$NON-NLS-1$
                .get(0).getAsJsonObject().get("execution_kind").getAsString()); //$NON-NLS-1$
    }

    // ---- gsd_record_decision execution -----------------------------------

    @Test
    @RequiresSecureMutation
    public void recordDecisionSuccess() throws ExecutionException, InterruptedException {
        GsdRecordDecisionTool tool = new GsdRecordDecisionTool();
        ToolResult result = execute(tool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 0, //$NON-NLS-1$
                "id", "d1", //$NON-NLS-1$
                "summary", "use JSON", //$NON-NLS-1$
                "rationale", "source of truth")).get(); //$NON-NLS-1$
        assertTrue(result.isSuccess());
        assertTrue(result.hasStructuredData());
        assertEquals("success", result.getStructuredString("status")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("gsd_record_decision", result.getStructuredString("operation")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, result.getStructuredInt("revision", 0)); //$NON-NLS-1$
    }

    @Test
    @RequiresSecureMutation
    public void recordDecisionStaleRevision() throws ExecutionException, InterruptedException {
        GsdRecordDecisionTool tool = new GsdRecordDecisionTool();
        execute(tool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 0, //$NON-NLS-1$
                "id", "d1", //$NON-NLS-1$
                "summary", "use JSON", //$NON-NLS-1$
                "rationale", "why")).get(); //$NON-NLS-1$
        ToolResult result = execute(tool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 0, //$NON-NLS-1$
                "id", "d2", //$NON-NLS-1$
                "summary", "use XML", //$NON-NLS-1$
                "rationale", "alt")).get(); //$NON-NLS-1$
        assertFalse(result.isSuccess());
        assertEquals("stale", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void recordDecisionMissingParamFails() throws ExecutionException, InterruptedException {
        GsdRecordDecisionTool tool = new GsdRecordDecisionTool();
        ToolResult result = execute(tool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 0, //$NON-NLS-1$
                "id", "d1")).get(); //$NON-NLS-1$
        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    @RequiresSecureMutation
    public void recordDecisionFromWrongPhaseReturnsInvalid() throws ExecutionException, InterruptedException {
        // recordDecision requires DISCOVERY; transition to PLANNING first.
        transitionToPlanning();
        GsdRecordDecisionTool tool = new GsdRecordDecisionTool();
        ToolResult result = execute(tool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 1, //$NON-NLS-1$
                "id", "d1", //$NON-NLS-1$
                "summary", "s", //$NON-NLS-1$
                "rationale", "r")).get(); //$NON-NLS-1$
        assertFalse("must not succeed in PLANNING phase", result.isSuccess()); //$NON-NLS-1$
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue("error message should mention illegal state", //$NON-NLS-1$
                result.getErrorMessage().toLowerCase().contains("illegal state") //$NON-NLS-1$
                        || result.getErrorMessage().toLowerCase().contains("phase")); //$NON-NLS-1$
    }

    // ---- gsd_create_plan execution ---------------------------------------
    // create_plan requires PLANNING phase; never changes the phase itself.

    /**
     * Helper: transitions DISCOVERY (rev 0) → PLANNING (rev 1).
     * Returns the new revision after transition (always 1).
     */
    private long transitionToPlanning() throws ExecutionException, InterruptedException {
        GsdTransitionTool tt = new GsdTransitionTool();
        execute(tt, Map.of("project_path", projectPath, "expected_revision", 0, "target_phase", "PLANNING")).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        return 1;
    }

    @Test
    @RequiresSecureMutation
    public void createPlanSuccess() throws ExecutionException, InterruptedException {
        long rev = transitionToPlanning();
        GsdCreatePlanTool tool = new GsdCreatePlanTool();
        ToolResult result = execute(tool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", rev, //$NON-NLS-1$
                "goal", "Ship it", //$NON-NLS-1$
                "tasks", List.of(Map.of("id", "t1", "title", "implement", "execution_kind", "READ_ONLY", "wave_id", "w1")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "waves", List.of(Map.of("id", "w1", "name", "wave 1", "task_ids", List.of("t1"))))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(result.isSuccess());
        assertEquals("success", result.getStructuredString("status")); //$NON-NLS-1$ //$NON-NLS-2$
        // Phase remains PLANNING — create_plan never advances the phase.
        assertEquals("PLANNING", result.getStructuredString("phase")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void createPlanEmptyTasksFails() throws ExecutionException, InterruptedException {
        GsdCreatePlanTool tool = new GsdCreatePlanTool();
        ToolResult result = execute(tool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 0, //$NON-NLS-1$
                "goal", "Ship it", //$NON-NLS-1$
                "tasks", List.of(),
                "waves", List.of(Map.of("id", "w1", "name", "w")))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(result.isSuccess());
    }

    @Test
    public void createPlanAllOptionalAcceptanceCriteriaReturnsInvalid()
            throws ExecutionException, InterruptedException {
        long rev = 0L;
        ToolResult result = execute(new GsdCreatePlanTool(), Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", rev, //$NON-NLS-1$
                "goal", "Ship it", //$NON-NLS-1$ //$NON-NLS-2$
                "acceptance_criteria", List.of(Map.of( //$NON-NLS-1$
                        "id", "ac-optional", //$NON-NLS-1$ //$NON-NLS-2$
                        "description", "nice to have", //$NON-NLS-1$ //$NON-NLS-2$
                        "required", false)), //$NON-NLS-1$
                "tasks", List.of(Map.of( //$NON-NLS-1$
                        "id", "t1", "title", "task", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                        "execution_kind", "READ_ONLY", "wave_id", "w1")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "waves", List.of(Map.of( //$NON-NLS-1$
                        "id", "w1", "name", "wave", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                        "task_ids", List.of("t1"))))).get(); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.getErrorMessage().contains("required criterion")); //$NON-NLS-1$
        ToolResult unchanged = execute(new GsdGetStateTool(),
                Map.of("project_path", projectPath)).get(); //$NON-NLS-1$
        assertEquals(rev, unchanged.getStructuredInt("revision", -1)); //$NON-NLS-1$
    }

    @Test
    public void createPlanMalformedTaskFails() throws ExecutionException, InterruptedException {
        GsdCreatePlanTool tool = new GsdCreatePlanTool();
        // tasks[0] missing id
        ToolResult result = execute(tool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 0, //$NON-NLS-1$
                "goal", "g", //$NON-NLS-1$
                "tasks", List.of(Map.of("title", "no-id")), //$NON-NLS-1$ //$NON-NLS-2$
                "waves", List.of(Map.of("id", "w1", "name", "w")))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void createPlanNonArrayTasksFails() throws ExecutionException, InterruptedException {
        GsdCreatePlanTool tool = new GsdCreatePlanTool();
        ToolResult result = execute(tool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 0, //$NON-NLS-1$
                "goal", "g", //$NON-NLS-1$
                "tasks", "not an array", //$NON-NLS-1$
                "waves", List.of(Map.of("id", "w1", "name", "w")))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void createPlanMissingExecutionKindFails() throws ExecutionException, InterruptedException {
        GsdCreatePlanTool tool = new GsdCreatePlanTool();
        ToolResult result = execute(tool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 0, //$NON-NLS-1$
                "goal", "g", //$NON-NLS-1$
                "tasks", List.of(Map.of("id", "t1", "title", "t")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "waves", List.of(Map.of("id", "w1", "name", "w")))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void createPlanUnknownExecutionKindFails() throws ExecutionException, InterruptedException {
        GsdCreatePlanTool tool = new GsdCreatePlanTool();
        ToolResult result = execute(tool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 0, //$NON-NLS-1$
                "goal", "g", //$NON-NLS-1$
                "tasks", List.of(Map.of("id", "t1", "title", "t", "execution_kind", "BOGUS")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "waves", List.of(Map.of("id", "w1", "name", "w")))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void createPlanMalformedWaveFails() throws ExecutionException, InterruptedException {
        GsdCreatePlanTool tool = new GsdCreatePlanTool();
        ToolResult result = execute(tool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 0, //$NON-NLS-1$
                "goal", "g", //$NON-NLS-1$
                "tasks", List.of(Map.of("id", "t1", "title", "t", "execution_kind", "READ_ONLY")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "waves", List.of(Map.of("name", "no-id")))).get(); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void createPlanTaskDependsOnNonStringElementFails() throws ExecutionException, InterruptedException {
        GsdCreatePlanTool tool = new GsdCreatePlanTool();
        ToolResult result = execute(tool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 0, //$NON-NLS-1$
                "goal", "g", //$NON-NLS-1$
                "tasks", List.of(Map.of("id", "t1", "title", "t", "execution_kind", "READ_ONLY", "depends_on", List.of(42))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "waves", List.of(Map.of("id", "w1", "name", "w")))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    @RequiresSecureMutation
    public void createPlanDoesNotChangePhase() throws ExecutionException, InterruptedException {
        long rev = transitionToPlanning();
        GsdCreatePlanTool tool = new GsdCreatePlanTool();
        ToolResult result = execute(tool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", rev, //$NON-NLS-1$
                "goal", "Ship it", //$NON-NLS-1$
                "tasks", List.of(Map.of("id", "t1", "title", "t", "execution_kind", "READ_ONLY", "wave_id", "w1")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "waves", List.of(Map.of("id", "w1", "name", "w", "task_ids", List.of("t1"))))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(result.isSuccess());
        // Phase must remain PLANNING (not auto-advance to EXECUTING).
        assertEquals("PLANNING", result.getStructuredString("phase")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void createPlanNestedExtraTaskKeyReturnsInvalid() throws ExecutionException, InterruptedException {
        long rev = 0L;
        GsdCreatePlanTool tool = new GsdCreatePlanTool();
        ToolResult result = execute(tool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", rev, //$NON-NLS-1$
                "goal", "g", //$NON-NLS-1$
                "tasks", List.of(Map.of("id", "t1", "title", "t", "execution_kind", "READ_ONLY", "bogus", "extra")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "waves", List.of(Map.of("id", "w1", "name", "w")))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void createPlanNestedExtraWaveKeyReturnsInvalid() throws ExecutionException, InterruptedException {
        long rev = 0L;
        GsdCreatePlanTool tool = new GsdCreatePlanTool();
        ToolResult result = execute(tool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", rev, //$NON-NLS-1$
                "goal", "g", //$NON-NLS-1$
                "tasks", List.of(Map.of("id", "t1", "title", "t", "execution_kind", "READ_ONLY")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                "waves", List.of(Map.of("id", "w1", "name", "w", "sneaky", true)))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    @RequiresSecureMutation
    public void createPlanFromInvalidPhaseFails() throws ExecutionException, InterruptedException {
        // In DISCOVERY (rev 0), createPlan must be rejected.
        GsdCreatePlanTool cpt = new GsdCreatePlanTool();
        ToolResult result = execute(cpt, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 0, //$NON-NLS-1$
                "goal", "g", //$NON-NLS-1$
                "tasks", List.of(Map.of("id", "t1", "title", "t", "execution_kind", "READ_ONLY", "wave_id", "w1")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "waves", List.of(Map.of("id", "w1", "name", "w", "task_ids", List.of("t1"))))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    @RequiresSecureMutation
    public void createPlanInExecutingPhaseFails() throws ExecutionException, InterruptedException {
        // DISCOVERY -> PLANNING -> create plan -> EXECUTING -> try createPlan (must fail).
        transitionToPlanning();
        GsdCreatePlanTool cpt = new GsdCreatePlanTool();
        execute(cpt, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 1, //$NON-NLS-1$
                "goal", "g", //$NON-NLS-1$
                "tasks", List.of(Map.of("id", "t1", "title", "t", "execution_kind", "READ_ONLY", "wave_id", "w1")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "waves", List.of(Map.of("id", "w1", "name", "w", "task_ids", List.of("t1"))))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        // Transition PLANNING -> EXECUTING (rev 2).
        GsdTransitionTool tt = new GsdTransitionTool();
        execute(tt, Map.of("project_path", projectPath, "expected_revision", 2, "target_phase", "EXECUTING")).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        // createPlan in EXECUTING must fail.
        ToolResult result = execute(cpt, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 3, //$NON-NLS-1$
                "goal", "g2", //$NON-NLS-1$
                "tasks", List.of(Map.of("id", "t1", "title", "t", "execution_kind", "READ_ONLY", "wave_id", "w1")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "waves", List.of(Map.of("id", "w1", "name", "w", "task_ids", List.of("t1"))))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertFalse(result.isSuccess());
    }

    // ---- gsd_update_task execution ---------------------------------------

    @Test
    @RequiresSecureMutation
    public void updateTaskSuccess() throws ExecutionException, InterruptedException {
        // DISCOVERY -> PLANNING -> create plan -> EXECUTING
        transitionToPlanning();
        GsdCreatePlanTool planTool = new GsdCreatePlanTool();
        execute(planTool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 1, //$NON-NLS-1$
                "goal", "Ship it", //$NON-NLS-1$
                "tasks", List.of(Map.of("id", "t1", "title", "task", "execution_kind", "READ_ONLY", "wave_id", "w1")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "waves", List.of(Map.of("id", "w1", "name", "w", "task_ids", List.of("t1"))))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        GsdTransitionTool tt = new GsdTransitionTool();
        execute(tt, Map.of("project_path", projectPath, "expected_revision", 2, "target_phase", "EXECUTING")).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        GsdUpdateTaskTool tool = new GsdUpdateTaskTool();
        ToolResult result = execute(tool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 3, //$NON-NLS-1$
                "task_id", "t1", //$NON-NLS-1$
                "status", "IN_PROGRESS")).get(); //$NON-NLS-1$
        assertTrue(result.isSuccess());
        assertEquals("gsd_update_task", result.getStructuredString("operation")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    @RequiresSecureMutation
    public void updateTaskNotFoundFails() throws ExecutionException, InterruptedException {
        GsdUpdateTaskTool tool = new GsdUpdateTaskTool();
        ToolResult result = execute(tool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 0, //$NON-NLS-1$
                "task_id", "nonexistent", //$NON-NLS-1$
                "status", "IN_PROGRESS")).get(); //$NON-NLS-1$
        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void updateTaskUnknownStatusFails() throws ExecutionException, InterruptedException {
        GsdUpdateTaskTool tool = new GsdUpdateTaskTool();
        ToolResult result = execute(tool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 0, //$NON-NLS-1$
                "task_id", "t1", //$NON-NLS-1$
                "status", "BOGUS")).get(); //$NON-NLS-1$
        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void updateTaskMissingStatusFails() throws ExecutionException, InterruptedException {
        GsdUpdateTaskTool tool = new GsdUpdateTaskTool();
        ToolResult result = execute(tool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 0, //$NON-NLS-1$
                "task_id", "t1")).get(); //$NON-NLS-1$
        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void updateTaskOnlyAcceptsStatusNoOtherFields() throws ExecutionException, InterruptedException {
        String schema = new GsdUpdateTaskTool().getParameterSchema();
        assertFalse("must not expose title", schema.contains("\"title\"")); //$NON-NLS-1$
        assertFalse("must not expose wave_id", schema.contains("\"wave_id\"")); //$NON-NLS-1$
        assertFalse("must not expose depends_on", schema.contains("\"depends_on\"")); //$NON-NLS-1$
        assertFalse("must not expose evidence_ids", schema.contains("\"evidence_ids\"")); //$NON-NLS-1$
    }

    // ---- gsd_record_evidence execution -----------------------------------
    // record_evidence requires EXECUTING or VERIFYING phase.

    /**
     * Helper: sets up a project in EXECUTING phase with one task t1.
     * Returns the revision after transitioning to EXECUTING.
     */
    private long setUpExecutingPhase() throws ExecutionException, InterruptedException {
        transitionToPlanning();
        GsdCreatePlanTool planTool = new GsdCreatePlanTool();
        execute(planTool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 1, //$NON-NLS-1$
                "goal", "g", //$NON-NLS-1$
                "tasks", List.of(Map.of("id", "t1", "title", "task", "execution_kind", "READ_ONLY", "wave_id", "w1")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "waves", List.of(Map.of("id", "w1", "name", "w", "task_ids", List.of("t1"))))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        GsdTransitionTool tt = new GsdTransitionTool();
        execute(tt, Map.of("project_path", projectPath, "expected_revision", 2, "target_phase", "EXECUTING")).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        return 3;
    }

    @Test
    @RequiresSecureMutation
    public void recordEvidenceSuccess() throws ExecutionException, InterruptedException {
        long rev = setUpExecutingPhase();
        GsdRecordEvidenceTool tool = new GsdRecordEvidenceTool();
        ToolResult result = execute(tool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", rev, //$NON-NLS-1$
                "id", "e1", //$NON-NLS-1$
                "description", "test passed", //$NON-NLS-1$
                "provenance", "TESTED", //$NON-NLS-1$
                "task_ids", List.of())).get(); //$NON-NLS-1$
        assertTrue(result.isSuccess());
        assertEquals("gsd_record_evidence", result.getStructuredString("operation")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void recordEvidenceInvalidProvenanceFails() throws ExecutionException, InterruptedException {
        long rev = 0L;
        GsdRecordEvidenceTool tool = new GsdRecordEvidenceTool();
        ToolResult result = execute(tool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", rev, //$NON-NLS-1$
                "id", "e1", //$NON-NLS-1$
                "description", "test passed", //$NON-NLS-1$
                "provenance", "BOGUS")).get(); //$NON-NLS-1$
        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // ---- gsd_transition execution ----------------------------------------

    @Test
    @RequiresSecureMutation
    public void transitionSuccess() throws ExecutionException, InterruptedException {
        GsdTransitionTool tool = new GsdTransitionTool();
        ToolResult result = execute(tool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 0, //$NON-NLS-1$
                "target_phase", "PLANNING")).get(); //$NON-NLS-1$
        assertTrue(result.isSuccess());
        assertEquals("PLANNING", result.getStructuredString("phase")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    @RequiresSecureMutation
    public void transitionIllegalFails() throws ExecutionException, InterruptedException {
        GsdTransitionTool tool = new GsdTransitionTool();
        ToolResult result = execute(tool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 0, //$NON-NLS-1$
                "target_phase", "EXECUTING")).get(); //$NON-NLS-1$
        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void transitionUnknownPhaseFails() throws ExecutionException, InterruptedException {
        GsdTransitionTool tool = new GsdTransitionTool();
        ToolResult result = execute(tool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 0, //$NON-NLS-1$
                "target_phase", "INVALID_PHASE")).get(); //$NON-NLS-1$
        assertFalse(result.isSuccess());
    }

    @Test
    @RequiresSecureMutation
    public void transitionRollbackWithReasonSucceeds() throws ExecutionException, InterruptedException {
        // Set up: PLANNING -> create plan -> EXECUTING -> VERIFYING -> rollback
        transitionToPlanning();
        GsdCreatePlanTool planTool = new GsdCreatePlanTool();
        execute(planTool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 1, //$NON-NLS-1$
                "goal", "g", //$NON-NLS-1$
                "tasks", List.of(Map.of("id", "t1", "title", "task", "execution_kind", "READ_ONLY", "wave_id", "w1")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "waves", List.of(Map.of("id", "w1", "name", "w", "task_ids", List.of("t1"))))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        GsdTransitionTool tool = new GsdTransitionTool();
        execute(tool, Map.of("project_path", projectPath, "expected_revision", 2, "target_phase", "EXECUTING")).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        ToolResult verifying = execute(tool, Map.of("project_path", projectPath, "expected_revision", 3, "target_phase", "VERIFYING")).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        // VERIFYING entry guard requires all tasks DONE; mark t1 DONE with evidence first.
        GsdRecordEvidenceTool evTool = new GsdRecordEvidenceTool();
        // Back up — VERIFYING can't be entered without all DONE. Reload and do it properly.
        // Actually the VERIFYING transition above failed because t1 is not DONE.
        // Let's go back: we need to reload the current revision.
        GsdGetStateTool gs = new GsdGetStateTool();
        ToolResult gsResult = execute(gs, Map.of("project_path", projectPath)).get(); //$NON-NLS-1$
        long rev = gsResult.getStructuredInt("revision", 0); //$NON-NLS-1$
        // Record evidence, then mark t1 DONE.
        execute(evTool, Map.of("project_path", projectPath, "expected_revision", rev, //$NON-NLS-1$
                "id", "e1", "description", "ok", "provenance", "TESTED", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "task_ids", List.of("t1"))).get(); //$NON-NLS-1$ //$NON-NLS-2$
        gsResult = execute(gs, Map.of("project_path", projectPath)).get(); //$NON-NLS-1$
        rev = gsResult.getStructuredInt("revision", 0); //$NON-NLS-1$
        GsdUpdateTaskTool ut = new GsdUpdateTaskTool();
        execute(ut, Map.of("project_path", projectPath, "expected_revision", rev, //$NON-NLS-1$
                "task_id", "t1", "status", "DONE")).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        gsResult = execute(gs, Map.of("project_path", projectPath)).get(); //$NON-NLS-1$
        rev = gsResult.getStructuredInt("revision", 0); //$NON-NLS-1$
        // Now VERIFYING should succeed.
        ToolResult vr = execute(tool, Map.of("project_path", projectPath, "expected_revision", rev, "target_phase", "VERIFYING")).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue("VERIFYING transition must succeed", vr.isSuccess()); //$NON-NLS-1$
        long verifyingRev = vr.getStructuredInt("revision", 0); //$NON-NLS-1$

        ToolResult result = execute(tool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", verifyingRev, //$NON-NLS-1$
                "target_phase", "EXECUTING", //$NON-NLS-1$
                "reason", "tests failed")).get(); //$NON-NLS-1$
        assertTrue(result.isSuccess());
        assertEquals("EXECUTING", result.getStructuredString("phase")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    @RequiresSecureMutation
    public void transitionRollbackWithoutReasonFails() throws ExecutionException, InterruptedException {
        // Set up same as above to reach VERIFYING.
        transitionToPlanning();
        GsdCreatePlanTool planTool = new GsdCreatePlanTool();
        execute(planTool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 1, //$NON-NLS-1$
                "goal", "g", //$NON-NLS-1$
                "tasks", List.of(Map.of("id", "t1", "title", "task", "execution_kind", "READ_ONLY", "wave_id", "w1")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "waves", List.of(Map.of("id", "w1", "name", "w", "task_ids", List.of("t1"))))).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        GsdTransitionTool tool = new GsdTransitionTool();
        execute(tool, Map.of("project_path", projectPath, "expected_revision", 2, "target_phase", "EXECUTING")).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        // Record evidence + mark DONE so VERIFYING entry guard passes.
        GsdGetStateTool gs = new GsdGetStateTool();
        ToolResult gsResult = execute(gs, Map.of("project_path", projectPath)).get(); //$NON-NLS-1$
        long rev = gsResult.getStructuredInt("revision", 0); //$NON-NLS-1$
        GsdRecordEvidenceTool evTool = new GsdRecordEvidenceTool();
        execute(evTool, Map.of("project_path", projectPath, "expected_revision", rev, //$NON-NLS-1$
                "id", "e1", "description", "ok", "provenance", "TESTED", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "task_ids", List.of("t1"))).get(); //$NON-NLS-1$ //$NON-NLS-2$
        gsResult = execute(gs, Map.of("project_path", projectPath)).get(); //$NON-NLS-1$
        rev = gsResult.getStructuredInt("revision", 0); //$NON-NLS-1$
        GsdUpdateTaskTool ut = new GsdUpdateTaskTool();
        execute(ut, Map.of("project_path", projectPath, "expected_revision", rev, //$NON-NLS-1$
                "task_id", "t1", "status", "DONE")).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        gsResult = execute(gs, Map.of("project_path", projectPath)).get(); //$NON-NLS-1$
        rev = gsResult.getStructuredInt("revision", 0); //$NON-NLS-1$
        ToolResult vr = execute(tool, Map.of("project_path", projectPath, "expected_revision", rev, "target_phase", "VERIFYING")).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertTrue("VERIFYING must succeed", vr.isSuccess()); //$NON-NLS-1$
        long verifyingRev = vr.getStructuredInt("revision", 0); //$NON-NLS-1$

        // Rollback without reason must fail with "invalid" (not "stale").
        ToolResult result = execute(tool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", verifyingRev, //$NON-NLS-1$
                "target_phase", "EXECUTING")).get(); //$NON-NLS-1$
        assertFalse(result.isSuccess());
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    @RequiresSecureMutation
    public void staleCycleTokenFailsBeforeMutation()
            throws ExecutionException, InterruptedException {
        GsdRecordDecisionTool tool = new GsdRecordDecisionTool();
        ToolResult result = execute(tool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_cycle_id", "wrong-cycle", //$NON-NLS-1$ //$NON-NLS-2$
                "expected_generation", 0L, //$NON-NLS-1$
                "expected_revision", 0L, //$NON-NLS-1$
                "id", "d1", "summary", "scope", "rationale", "reason")).get(); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        assertFalse(result.isSuccess());
        assertEquals("stale", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    @RequiresSecureMutation
    public void verificationOutcomeFromWrongPhaseReturnsStructuredInvalid()
            throws ExecutionException, InterruptedException {
        ToolResult result = execute(new GsdRecordVerificationOutcomeTool(), Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 0L, //$NON-NLS-1$
                "criterion_id", "ac-1", //$NON-NLS-1$ //$NON-NLS-2$
                "outcome", "PASSED")).get(); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(result.isSuccess());
        assertTrue(result.hasStructuredData());
        assertEquals("error", result.getStructuredString("status")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("gsd_record_verification_outcome", //$NON-NLS-1$
                result.getStructuredString("operation")); //$NON-NLS-1$
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.getErrorMessage().contains("requires phase VERIFYING")); //$NON-NLS-1$
    }

    @Test
    @RequiresSecureMutation
    public void shipmentFromWrongPhaseReturnsStructuredInvalid()
            throws ExecutionException, InterruptedException {
        ToolResult result = execute(new GsdRecordShipmentTool(), Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", 0L, //$NON-NLS-1$
                "shipment_id", "release-1", //$NON-NLS-1$ //$NON-NLS-2$
                "delivery_reference", "registry/release-1", //$NON-NLS-1$ //$NON-NLS-2$
                "status", "FAILED")).get(); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(result.isSuccess());
        assertTrue(result.hasStructuredData());
        assertEquals("error", result.getStructuredString("status")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("gsd_record_shipment", result.getStructuredString("operation")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("invalid", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(result.getErrorMessage().contains("requires phase SHIPPING")); //$NON-NLS-1$
    }

    @Test
    @RequiresSecureMutation
    public void failedVerificationCannotEnterShipping()
            throws ExecutionException, InterruptedException {
        long verifyingRevision = reachVerifying();
        GsdRecordVerificationOutcomeTool outcomeTool =
                new GsdRecordVerificationOutcomeTool();
        ToolResult failed = execute(outcomeTool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", verifyingRevision, //$NON-NLS-1$
                "criterion_id", "ac-1", //$NON-NLS-1$ //$NON-NLS-2$
                "outcome", "FAILED")).get(); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(failed.isSuccess());

        ToolResult shipping = execute(new GsdTransitionTool(), Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", verifyingRevision + 1L, //$NON-NLS-1$
                "target_phase", "SHIPPING")).get(); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(shipping.isSuccess());
        assertEquals("invalid", shipping.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    @RequiresSecureMutation
    public void shipmentExactRetryIsIdempotentAndReplacementConflicts()
            throws ExecutionException, InterruptedException {
        long verifyingRevision = reachVerifying();
        ToolResult passed = execute(new GsdRecordVerificationOutcomeTool(), Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", verifyingRevision, //$NON-NLS-1$
                "criterion_id", "ac-1", //$NON-NLS-1$ //$NON-NLS-2$
                "outcome", "PASSED")).get(); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(passed.isSuccess());
        ToolResult shipping = execute(new GsdTransitionTool(), Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", verifyingRevision + 1L, //$NON-NLS-1$
                "target_phase", "SHIPPING")).get(); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(shipping.isSuccess());
        long shippingRevision = shipping.getStructuredInt("revision", 0); //$NON-NLS-1$
        String completedAt = Instant.parse("2026-08-21T10:15:30Z").toString(); //$NON-NLS-1$
        Map<String, Object> shipment = Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", shippingRevision, //$NON-NLS-1$
                "shipment_id", "release-1", //$NON-NLS-1$ //$NON-NLS-2$
                "delivery_reference", "registry/release-1", //$NON-NLS-1$ //$NON-NLS-2$
                "status", "COMPLETED", //$NON-NLS-1$ //$NON-NLS-2$
                "completed_at", completedAt); //$NON-NLS-1$
        GsdRecordShipmentTool shipmentTool = new GsdRecordShipmentTool();
        ToolResult first = execute(shipmentTool, shipment).get();
        assertTrue(first.isSuccess());
        assertFalse(first.getStructuredData().get("idempotent").getAsBoolean()); //$NON-NLS-1$

        ToolResult staleDuplicate = execute(shipmentTool, shipment).get();
        assertFalse(staleDuplicate.isSuccess());
        assertEquals("stale", staleDuplicate.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$

        Map<String, Object> currentShipment = new LinkedHashMap<>(shipment);
        currentShipment.put("expected_revision", shippingRevision + 1L); //$NON-NLS-1$
        ToolResult duplicate = execute(shipmentTool, currentShipment).get();
        assertTrue(duplicate.isSuccess());
        assertTrue(duplicate.getStructuredData().get("idempotent").getAsBoolean()); //$NON-NLS-1$
        assertEquals(first.getStructuredInt("revision", 0), //$NON-NLS-1$
                duplicate.getStructuredInt("revision", 0)); //$NON-NLS-1$

        ToolResult conflict = execute(shipmentTool, Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", shippingRevision + 1L, //$NON-NLS-1$
                "shipment_id", "release-2", //$NON-NLS-1$ //$NON-NLS-2$
                "delivery_reference", "registry/release-2", //$NON-NLS-1$ //$NON-NLS-2$
                "status", "FAILED")).get(); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(conflict.isSuccess());
        assertEquals("conflict", conflict.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$

        ToolResult state = execute(new GsdGetStateTool(),
                Map.of("project_path", projectPath)).get(); //$NON-NLS-1$
        assertEquals("SHIPPING", state.getStructuredString("phase")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(state.getStructuredData().getAsJsonObject("verification") //$NON-NLS-1$
                .get("all_required_passed").getAsBoolean()); //$NON-NLS-1$
        assertEquals("COMPLETED", state.getStructuredData().getAsJsonObject("shipment") //$NON-NLS-1$ //$NON-NLS-2$
                .get("status").getAsString()); //$NON-NLS-1$
        assertEquals(completedAt, state.getStructuredData().getAsJsonObject("shipment") //$NON-NLS-1$
                .get("completed_at").getAsString()); //$NON-NLS-1$
    }

    private long reachVerifying() throws ExecutionException, InterruptedException {
        long executingRevision = setUpExecutingPhase();
        ToolResult evidence = execute(new GsdRecordEvidenceTool(), Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", executingRevision, //$NON-NLS-1$
                "id", "e-verify", //$NON-NLS-1$ //$NON-NLS-2$
                "description", "release checks observed", //$NON-NLS-1$ //$NON-NLS-2$
                "provenance", "TESTED", //$NON-NLS-1$ //$NON-NLS-2$
                "task_ids", List.of("t1"))).get(); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(evidence.isSuccess());
        ToolResult done = execute(new GsdUpdateTaskTool(), Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", executingRevision + 1L, //$NON-NLS-1$
                "task_id", "t1", //$NON-NLS-1$ //$NON-NLS-2$
                "status", "DONE")).get(); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(done.isSuccess());
        ToolResult verifying = execute(new GsdTransitionTool(), Map.of(
                "project_path", projectPath, //$NON-NLS-1$
                "expected_revision", executingRevision + 2L, //$NON-NLS-1$
                "target_phase", "VERIFYING")).get(); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(verifying.isSuccess());
        return verifying.getStructuredInt("revision", 0); //$NON-NLS-1$
    }
}
