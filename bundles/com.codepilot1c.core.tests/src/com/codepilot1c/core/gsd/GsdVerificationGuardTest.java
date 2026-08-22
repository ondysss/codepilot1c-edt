/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Tests for verification provenance and EDT-aware wave guards.
 * Covers: execution kind, captured phase, mutation isolation, dependency graph,
 * bidirectional wave-task consistency, and CLOSED phase requirements.
 */
public class GsdVerificationGuardTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path newProject() throws IOException {
        return GsdTestSupport.secureProject(
                tmp.newFolder("project").toPath()); //$NON-NLS-1$
    }

    // ---- ExecutionKind defaults and compatibility -------------------------

    @Test
    public void taskDefaultsToReadOnly() {
        GsdTask task = new GsdTask("t1", "read file", GsdTaskStatus.PENDING, "w1", List.of(), List.of()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(GsdExecutionKind.READ_ONLY, task.executionKind());
    }

    @Test
    public void taskWithExplicitExecutionKind() {
        GsdTask task = new GsdTask("t1", "edit file", GsdTaskStatus.PENDING, "w1", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                List.of(), List.of(), GsdExecutionKind.FILE_MUTATION);
        assertEquals(GsdExecutionKind.FILE_MUTATION, task.executionKind());
    }

    @Test
    public void executionKindIsMutation() {
        assertFalse(GsdExecutionKind.READ_ONLY.isMutation());
        assertTrue(GsdExecutionKind.FILE_MUTATION.isMutation());
        assertTrue(GsdExecutionKind.EDT_MUTATION.isMutation());
        assertTrue(GsdExecutionKind.GIT_MUTATION.isMutation());
    }

    // ---- CapturedPhase defaults and compatibility -------------------------

    @Test
    public void evidenceDefaultsToExecutingPhase() {
        GsdEvidence evidence = new GsdEvidence("e1", "test passed", GsdProvenance.TESTED, //$NON-NLS-1$ //$NON-NLS-2$
                List.of("t1"), Instant.EPOCH); //$NON-NLS-1$
        assertEquals(GsdPhase.EXECUTING, evidence.capturedPhase());
    }

    @Test
    public void evidenceWithExplicitCapturedPhase() {
        GsdEvidence evidence = new GsdEvidence("e1", "verified", GsdProvenance.TESTED, //$NON-NLS-1$ //$NON-NLS-2$
                List.of("t1"), Instant.EPOCH, GsdPhase.VERIFYING); //$NON-NLS-1$
        assertEquals(GsdPhase.VERIFYING, evidence.capturedPhase());
    }

    // ---- Parallel READ_ONLY tasks ----------------------------------------

    @Test
    public void multipleReadOnlyTasksInWaveIsValid() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);
        GsdState base = store.load();

        GsdState state = new GsdState(
                GsdState.CURRENT_SCHEMA_VERSION, base.revision(), GsdPhase.PLANNING, "g", //$NON-NLS-1$
                List.of(),
                List.of(
                        new GsdTask("t1", "read a", GsdTaskStatus.PENDING, "w1", List.of(), List.of(), GsdExecutionKind.READ_ONLY), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        new GsdTask("t2", "read b", GsdTaskStatus.PENDING, "w1", List.of(), List.of(), GsdExecutionKind.READ_ONLY), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        new GsdTask("t3", "read c", GsdTaskStatus.PENDING, "w1", List.of(), List.of(), GsdExecutionKind.READ_ONLY)), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                List.of(new GsdWave("w1", "parallel reads", "read multiple files", List.of("t1", "t2", "t3"))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                List.of(),
                GsdSessionPointer.empty());

        GsdState saved = store.save(state);
        assertEquals(3, saved.tasks().size());
        assertEquals(1, saved.waves().size());
    }

    // ---- Serialized EDT mutation -----------------------------------------

    @Test
    public void singleEdtMutationInWaveIsValid() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);
        GsdState base = store.load();

        GsdState state = new GsdState(
                GsdState.CURRENT_SCHEMA_VERSION, base.revision(), GsdPhase.PLANNING, "g", //$NON-NLS-1$
                List.of(),
                List.of(new GsdTask("t1", "edit metadata", GsdTaskStatus.PENDING, "w1", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        List.of(), List.of(), GsdExecutionKind.EDT_MUTATION)),
                List.of(new GsdWave("w1", "metadata edit", "update configuration", List.of("t1"))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                List.of(),
                GsdSessionPointer.empty());

        GsdState saved = store.save(state);
        assertEquals(1, saved.tasks().size());
        assertEquals(GsdExecutionKind.EDT_MUTATION, saved.tasks().get(0).executionKind());
    }

    @Test
    public void mutationWithOtherTasksInWaveIsRejected() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);
        GsdState base = store.load();

        GsdState bad = new GsdState(
                GsdState.CURRENT_SCHEMA_VERSION, base.revision(), GsdPhase.PLANNING, "g", //$NON-NLS-1$
                List.of(),
                List.of(
                        new GsdTask("t1", "edit file", GsdTaskStatus.PENDING, "w1", List.of(), List.of(), GsdExecutionKind.FILE_MUTATION), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        new GsdTask("t2", "read file", GsdTaskStatus.PENDING, "w1", List.of(), List.of(), GsdExecutionKind.READ_ONLY)), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                List.of(new GsdWave("w1", "mixed wave", "bad", List.of("t1", "t2"))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                List.of(),
                GsdSessionPointer.empty());

        try {
            store.save(bad);
            fail("expected GsdGuardException for mutation with other tasks"); //$NON-NLS-1$
        } catch (GsdGuardException e) {
            assertTrue("should mention mutation isolation", //$NON-NLS-1$
                    e.getViolations().stream().anyMatch(v -> v.contains("mutation"))); //$NON-NLS-1$
        }
    }

    // ---- Dependency graph validation -------------------------------------

    @Test
    public void selfDependencyIsRejected() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);
        GsdState base = store.load();

        GsdState bad = new GsdState(
                GsdState.CURRENT_SCHEMA_VERSION, base.revision(), GsdPhase.PLANNING, "g", //$NON-NLS-1$
                List.of(),
                List.of(new GsdTask("t1", "task", GsdTaskStatus.PENDING, "w1", List.of("t1"), List.of(), GsdExecutionKind.READ_ONLY)), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                List.of(new GsdWave("w1", "wave", "", List.of("t1"))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                List.of(),
                GsdSessionPointer.empty());

        try {
            store.save(bad);
            fail("expected GsdGuardException for self-dependency"); //$NON-NLS-1$
        } catch (GsdGuardException e) {
            assertTrue("should mention self-dependency", //$NON-NLS-1$
                    e.getViolations().stream().anyMatch(v -> v.contains("depends on itself"))); //$NON-NLS-1$
        }
    }

    @Test
    public void cycleIsRejected() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);
        GsdState base = store.load();

        GsdState bad = new GsdState(
                GsdState.CURRENT_SCHEMA_VERSION, base.revision(), GsdPhase.PLANNING, "g", //$NON-NLS-1$
                List.of(),
                List.of(
                        new GsdTask("t1", "task1", GsdTaskStatus.PENDING, "w1", List.of("t2"), List.of(), GsdExecutionKind.READ_ONLY), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                        new GsdTask("t2", "task2", GsdTaskStatus.PENDING, "w1", List.of("t1"), List.of(), GsdExecutionKind.READ_ONLY)), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                List.of(new GsdWave("w1", "wave", "", List.of("t1", "t2"))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                List.of(),
                GsdSessionPointer.empty());

        try {
            store.save(bad);
            fail("expected GsdGuardException for cycle"); //$NON-NLS-1$
        } catch (GsdGuardException e) {
            assertTrue("should mention cycle", //$NON-NLS-1$
                    e.getViolations().stream().anyMatch(v -> v.contains("cycle"))); //$NON-NLS-1$
        }
    }

    @Test
    public void sameWaveDependencyIsRejected() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);
        GsdState base = store.load();

        GsdState bad = new GsdState(
                GsdState.CURRENT_SCHEMA_VERSION, base.revision(), GsdPhase.PLANNING, "g", //$NON-NLS-1$
                List.of(),
                List.of(
                        new GsdTask("t1", "task1", GsdTaskStatus.PENDING, "w1", List.of(), List.of(), GsdExecutionKind.READ_ONLY), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        new GsdTask("t2", "task2", GsdTaskStatus.PENDING, "w1", List.of("t1"), List.of(), GsdExecutionKind.READ_ONLY)), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                List.of(new GsdWave("w1", "wave", "", List.of("t1", "t2"))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                List.of(),
                GsdSessionPointer.empty());

        try {
            store.save(bad);
            fail("expected GsdGuardException for same-wave dependency"); //$NON-NLS-1$
        } catch (GsdGuardException e) {
            assertTrue("should mention wave ordering", //$NON-NLS-1$
                    e.getViolations().stream().anyMatch(v -> v.contains("strictly earlier wave"))); //$NON-NLS-1$
        }
    }

    @Test
    public void laterWaveDependencyIsRejected() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);
        GsdState base = store.load();

        GsdState bad = new GsdState(
                GsdState.CURRENT_SCHEMA_VERSION, base.revision(), GsdPhase.PLANNING, "g", //$NON-NLS-1$
                List.of(),
                List.of(
                        new GsdTask("t1", "task1", GsdTaskStatus.PENDING, "w1", List.of("t2"), List.of(), GsdExecutionKind.READ_ONLY), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                        new GsdTask("t2", "task2", GsdTaskStatus.PENDING, "w2", List.of(), List.of(), GsdExecutionKind.READ_ONLY)), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                List.of(
                        new GsdWave("w1", "wave1", "", List.of("t1")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                        new GsdWave("w2", "wave2", "", List.of("t2"))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                List.of(),
                GsdSessionPointer.empty());

        try {
            store.save(bad);
            fail("expected GsdGuardException for later-wave dependency"); //$NON-NLS-1$
        } catch (GsdGuardException e) {
            assertTrue("should mention wave ordering", //$NON-NLS-1$
                    e.getViolations().stream().anyMatch(v -> v.contains("strictly earlier wave"))); //$NON-NLS-1$
        }
    }

    @Test
    public void earlierWaveDependencyIsValid() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);
        GsdState base = store.load();

        GsdState good = new GsdState(
                GsdState.CURRENT_SCHEMA_VERSION, base.revision(), GsdPhase.PLANNING, "g", //$NON-NLS-1$
                List.of(),
                List.of(
                        new GsdTask("t1", "task1", GsdTaskStatus.PENDING, "w1", List.of(), List.of(), GsdExecutionKind.READ_ONLY), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        new GsdTask("t2", "task2", GsdTaskStatus.PENDING, "w2", List.of("t1"), List.of(), GsdExecutionKind.READ_ONLY)), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                List.of(
                        new GsdWave("w1", "wave1", "", List.of("t1")), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                        new GsdWave("w2", "wave2", "", List.of("t2"))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                List.of(),
                GsdSessionPointer.empty());

        GsdState saved = store.save(good);
        assertEquals(2, saved.tasks().size());
        assertEquals(2, saved.waves().size());
    }

    // ---- Bidirectional wave-task consistency ------------------------------

    @Test
    public void taskWithoutWaveIsRejected() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);
        GsdState base = store.load();

        GsdState bad = new GsdState(
                GsdState.CURRENT_SCHEMA_VERSION, base.revision(), GsdPhase.PLANNING, "g", //$NON-NLS-1$
                List.of(),
                List.of(new GsdTask("t1", "task", GsdTaskStatus.PENDING, null, List.of(), List.of(), GsdExecutionKind.READ_ONLY)), //$NON-NLS-1$ //$NON-NLS-2$
                List.of(),
                List.of(),
                GsdSessionPointer.empty());

        try {
            store.save(bad);
            fail("expected GsdGuardException for unassigned task"); //$NON-NLS-1$
        } catch (GsdGuardException e) {
            assertTrue("should mention wave assignment", //$NON-NLS-1$
                    e.getViolations().stream().anyMatch(v -> v.contains("no wave assignment"))); //$NON-NLS-1$
        }
    }

    @Test
    public void waveTaskMismatchIsRejected() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);
        GsdState base = store.load();

        // Task references wave but wave doesn't list task
        GsdState bad = new GsdState(
                GsdState.CURRENT_SCHEMA_VERSION, base.revision(), GsdPhase.PLANNING, "g", //$NON-NLS-1$
                List.of(),
                List.of(new GsdTask("t1", "task", GsdTaskStatus.PENDING, "w1", List.of(), List.of(), GsdExecutionKind.READ_ONLY)), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                List.of(new GsdWave("w1", "wave", "", List.of())), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                List.of(),
                GsdSessionPointer.empty());

        try {
            store.save(bad);
            fail("expected GsdGuardException for bidirectional mismatch"); //$NON-NLS-1$
        } catch (GsdGuardException e) {
            assertTrue("should mention bidirectional mismatch", //$NON-NLS-1$
                    e.getViolations().stream().anyMatch(v -> v.contains("does not list this task") //$NON-NLS-1$
                            || v.contains("references unknown task"))); //$NON-NLS-1$
        }
    }

    // ---- Nonblank field validation ---------------------------------------

    @Test
    public void blankTaskTitleIsRejected() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);
        GsdState base = store.load();

        GsdState bad = new GsdState(
                GsdState.CURRENT_SCHEMA_VERSION, base.revision(), GsdPhase.PLANNING, "g", //$NON-NLS-1$
                List.of(),
                List.of(new GsdTask("t1", "", GsdTaskStatus.PENDING, "w1", List.of(), List.of(), GsdExecutionKind.READ_ONLY)), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                List.of(new GsdWave("w1", "wave", "", List.of("t1"))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                List.of(),
                GsdSessionPointer.empty());

        try {
            store.save(bad);
            fail("expected GsdGuardException for blank title"); //$NON-NLS-1$
        } catch (GsdGuardException e) {
            assertTrue("should mention blank title", //$NON-NLS-1$
                    e.getViolations().stream().anyMatch(v -> v.contains("blank title"))); //$NON-NLS-1$
        }
    }

    @Test
    public void blankWaveNameIsRejected() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);
        GsdState base = store.load();

        GsdState bad = new GsdState(
                GsdState.CURRENT_SCHEMA_VERSION, base.revision(), GsdPhase.PLANNING, "g", //$NON-NLS-1$
                List.of(),
                List.of(new GsdTask("t1", "task", GsdTaskStatus.PENDING, "w1", List.of(), List.of(), GsdExecutionKind.READ_ONLY)), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                List.of(new GsdWave("w1", "", "", List.of("t1"))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                List.of(),
                GsdSessionPointer.empty());

        try {
            store.save(bad);
            fail("expected GsdGuardException for blank wave name"); //$NON-NLS-1$
        } catch (GsdGuardException e) {
            assertTrue("should mention blank name", //$NON-NLS-1$
                    e.getViolations().stream().anyMatch(v -> v.contains("blank name"))); //$NON-NLS-1$
        }
    }

    @Test
    public void blankDecisionSummaryIsRejected() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);
        GsdState base = store.load();

        GsdState bad = new GsdState(
                GsdState.CURRENT_SCHEMA_VERSION, base.revision(), GsdPhase.PLANNING, "g", //$NON-NLS-1$
                List.of(new GsdDecision("d1", "", "rationale", List.of())), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                List.of(),
                List.of(),
                List.of(),
                GsdSessionPointer.empty());

        try {
            store.save(bad);
            fail("expected GsdGuardException for blank summary"); //$NON-NLS-1$
        } catch (GsdGuardException e) {
            assertTrue("should mention blank summary", //$NON-NLS-1$
                    e.getViolations().stream().anyMatch(v -> v.contains("blank summary"))); //$NON-NLS-1$
        }
    }

    @Test
    public void blankDecisionRationaleIsRejected() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);
        GsdState base = store.load();

        GsdState bad = new GsdState(
                GsdState.CURRENT_SCHEMA_VERSION, base.revision(), GsdPhase.PLANNING, "g", //$NON-NLS-1$
                List.of(new GsdDecision("d1", "summary", "", List.of())), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                List.of(),
                List.of(),
                List.of(),
                GsdSessionPointer.empty());

        try {
            store.save(bad);
            fail("expected GsdGuardException for blank rationale"); //$NON-NLS-1$
        } catch (GsdGuardException e) {
            assertTrue("should mention blank rationale", //$NON-NLS-1$
                    e.getViolations().stream().anyMatch(v -> v.contains("blank rationale"))); //$NON-NLS-1$
        }
    }

    @Test
    public void blankEvidenceDescriptionIsRejected() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);
        GsdState base = store.load();

        GsdState bad = new GsdState(
                GsdState.CURRENT_SCHEMA_VERSION, base.revision(), GsdPhase.PLANNING, "g", //$NON-NLS-1$
                List.of(),
                List.of(),
                List.of(),
                List.of(new GsdEvidence("e1", "", GsdProvenance.TESTED, List.of(), Instant.EPOCH)), //$NON-NLS-1$ //$NON-NLS-2$
                GsdSessionPointer.empty());

        try {
            store.save(bad);
            fail("expected GsdGuardException for blank description"); //$NON-NLS-1$
        } catch (GsdGuardException e) {
            assertTrue("should mention blank description", //$NON-NLS-1$
                    e.getViolations().stream().anyMatch(v -> v.contains("blank description"))); //$NON-NLS-1$
        }
    }

    // ---- String list element validation ----------------------------------

    @Test
    public void blankDependencyIdIsRejected() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);
        GsdState base = store.load();

        GsdState bad = new GsdState(
                GsdState.CURRENT_SCHEMA_VERSION, base.revision(), GsdPhase.PLANNING, "g", //$NON-NLS-1$
                List.of(),
                List.of(new GsdTask("t1", "task", GsdTaskStatus.PENDING, "w1", List.of(""), List.of(), GsdExecutionKind.READ_ONLY)), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                List.of(new GsdWave("w1", "wave", "", List.of("t1"))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                List.of(),
                GsdSessionPointer.empty());

        try {
            store.save(bad);
            fail("expected GsdGuardException for blank dependency"); //$NON-NLS-1$
        } catch (GsdGuardException e) {
            assertTrue("should mention blank element", //$NON-NLS-1$
                    e.getViolations().stream().anyMatch(v -> v.contains("blank element"))); //$NON-NLS-1$
        }
    }

    @Test
    public void duplicateDependencyIdIsRejected() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);
        GsdState base = store.load();

        GsdState bad = new GsdState(
                GsdState.CURRENT_SCHEMA_VERSION, base.revision(), GsdPhase.PLANNING, "g", //$NON-NLS-1$
                List.of(),
                List.of(
                        new GsdTask("t1", "task1", GsdTaskStatus.PENDING, "w1", List.of(), List.of(), GsdExecutionKind.READ_ONLY), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        new GsdTask("t2", "task2", GsdTaskStatus.PENDING, "w1", List.of("t1", "t1"), List.of(), GsdExecutionKind.READ_ONLY)), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                List.of(new GsdWave("w1", "wave", "", List.of("t1", "t2"))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                List.of(),
                GsdSessionPointer.empty());

        try {
            store.save(bad);
            fail("expected GsdGuardException for duplicate dependency"); //$NON-NLS-1$
        } catch (GsdGuardException e) {
            assertTrue("should mention duplicate element", //$NON-NLS-1$
                    e.getViolations().stream().anyMatch(v -> v.contains("duplicate element"))); //$NON-NLS-1$
        }
    }

    // ---- CLOSED phase with VERIFYING evidence ----------------------------

    @Test
    public void closedPhaseRequiresVerifyingEvidence() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);
        GsdState base = store.load();

        // Task is DONE with TESTED evidence but capturedPhase is EXECUTING, not VERIFYING
        GsdState bad = new GsdState(
                GsdState.CURRENT_SCHEMA_VERSION, base.revision(), GsdPhase.CLOSED, "g", //$NON-NLS-1$
                List.of(),
                List.of(new GsdTask("t1", "task", GsdTaskStatus.DONE, "w1", List.of(), List.of("e1"), GsdExecutionKind.READ_ONLY)), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                List.of(new GsdWave("w1", "wave", "", List.of("t1"))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                List.of(new GsdEvidence("e1", "tested", GsdProvenance.TESTED, List.of("t1"), Instant.EPOCH, GsdPhase.EXECUTING)), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                GsdSessionPointer.empty());

        try {
            store.save(bad);
            fail("expected GsdGuardException for CLOSED without VERIFYING evidence"); //$NON-NLS-1$
        } catch (GsdGuardException e) {
            assertTrue("should mention VERIFYING evidence requirement", //$NON-NLS-1$
                    e.getViolations().stream().anyMatch(v -> v.contains("capturedPhase VERIFYING"))); //$NON-NLS-1$
        }
    }

    @Test
    public void closedPhaseWithVerifyingEvidenceIsValid() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);
        GsdState base = store.load();

        GsdState good = new GsdState(
                GsdState.CURRENT_SCHEMA_VERSION, base.revision(), GsdPhase.CLOSED, "g", //$NON-NLS-1$
                List.of(),
                List.of(new GsdTask("t1", "task", GsdTaskStatus.DONE, "w1", List.of(), List.of("e1"), GsdExecutionKind.READ_ONLY)), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                List.of(new GsdWave("w1", "wave", "", List.of("t1"))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                List.of(new GsdEvidence("e1", "verified", GsdProvenance.TESTED, List.of("t1"), Instant.EPOCH, GsdPhase.VERIFYING)), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                GsdSessionPointer.empty());

        GsdState saved = store.save(good);
        assertEquals(GsdPhase.CLOSED, saved.phase());
        assertEquals(GsdPhase.VERIFYING, saved.evidence().get(0).capturedPhase());
    }

    // ---- Strict enum adapter for GsdExecutionKind ------------------------

    @Test
    public void unknownExecutionKindCausesParseCorruption() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);
        Path statePath = store.getGsdDirectory().resolve(GsdStateStore.STATE_JSON);
        Files.createDirectories(store.getGsdDirectory());

        String badJson = "{ " + //$NON-NLS-1$
                "\"schemaVersion\":1,\"revision\":0,\"phase\":\"DISCOVERY\",\"goal\":\"x\"," + //$NON-NLS-1$
                "\"decisions\":[],\"tasks\":[" + //$NON-NLS-1$
                "{\"id\":\"t1\",\"title\":\"t\",\"status\":\"PENDING\",\"waveId\":null," + //$NON-NLS-1$
                "\"dependsOn\":[],\"evidenceIds\":[],\"executionKind\":\"UNKNOWN_KIND\"}" + //$NON-NLS-1$
                "],\"waves\":[],\"evidence\":[]," + //$NON-NLS-1$
                "\"sessionPointer\":{\"sessionId\":\"s\",\"workstreamId\":\"w\"}}"; //$NON-NLS-1$
        Files.write(statePath, badJson.getBytes(StandardCharsets.UTF_8));

        try {
            new GsdStateStore(root).load();
            fail("expected GsdCorruptException for unknown execution kind"); //$NON-NLS-1$
        } catch (GsdCorruptException e) {
            assertTrue("message mentions corruption or unknown value", //$NON-NLS-1$
                    e.getMessage().toLowerCase().contains("corrupt") //$NON-NLS-1$
                            || e.getMessage().toLowerCase().contains("unknown")); //$NON-NLS-1$
        }
    }

    // ---- Projections show execution kind and captured phase --------------

    @Test
    public void projectionsShowExecutionKindAndCapturedPhase() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);
        GsdState base = store.load();

        GsdState state = new GsdState(
                GsdState.CURRENT_SCHEMA_VERSION, base.revision(), GsdPhase.EXECUTING, "g", //$NON-NLS-1$
                List.of(),
                List.of(new GsdTask("t1", "edit", GsdTaskStatus.PENDING, "w1", List.of(), List.of("e1"), GsdExecutionKind.EDT_MUTATION)), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                List.of(new GsdWave("w1", "wave", "", List.of("t1"))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                List.of(new GsdEvidence("e1", "verified", GsdProvenance.TESTED, List.of("t1"), Instant.EPOCH, GsdPhase.VERIFYING)), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                GsdSessionPointer.empty());
        store.save(state);

        String planMd = Files.readString(store.getGsdDirectory().resolve(GsdProjections.PLAN_FILE), StandardCharsets.UTF_8);
        String stateMd = Files.readString(store.getGsdDirectory().resolve(GsdProjections.STATE_FILE), StandardCharsets.UTF_8);

        assertTrue("PLAN.md should show EDT_MUTATION", planMd.contains("EDT_MUTATION")); //$NON-NLS-1$
        assertTrue("STATE.md should show capturedPhase VERIFYING", stateMd.contains("VERIFYING")); //$NON-NLS-1$
    }
}
