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
import static org.junit.Assert.assertNotEquals;
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

/** Focused production-slice tests for schema v2 and its lifecycle boundaries. */
public class GsdCoreV2Test {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void v1MigrationIsExplicitPersistedAndIdempotent() throws IOException {
        Path root = tmp.newFolder("migration").toPath(); //$NON-NLS-1$
        GsdStateStore store = new GsdStateStore(root);
        Path statePath = store.getGsdDirectory().resolve(GsdStateStore.STATE_JSON);
        Files.createDirectories(store.getGsdDirectory());
        Files.writeString(statePath,
                "{\"schemaVersion\":1,\"revision\":7,\"phase\":\"PLANNING\"," //$NON-NLS-1$
                        + "\"goal\":\"ship\",\"decisions\":[],\"tasks\":[],\"waves\":[]," //$NON-NLS-1$
                        + "\"evidence\":[],\"sessionPointer\":{" //$NON-NLS-1$
                        + "\"sessionId\":\"s\",\"workstreamId\":\"w\"}}", //$NON-NLS-1$
                StandardCharsets.UTF_8);

        GsdState migrated = store.load();
        assertEquals(2, migrated.schemaVersion());
        assertEquals(GsdState.LEGACY_CYCLE_ID, migrated.cycleId());
        assertEquals(0L, migrated.generation());
        assertEquals(7L, migrated.revision());
        assertTrue(migrated.acceptanceCriteria().isEmpty());
        assertTrue(migrated.transitionHistory().isEmpty());
        assertTrue(migrated.shipment().emptyRecord());
        assertTrue(Files.readString(statePath).contains("\"schemaVersion\": 2")); //$NON-NLS-1$
        assertTrue(Files.readString(store.getGsdDirectory().resolve(GsdStateStore.STATE_BAK))
                .contains("\"schemaVersion\":1")); //$NON-NLS-1$

        GsdState loadedAgain = store.load();
        assertEquals(migrated.concurrencyToken(), loadedAgain.concurrencyToken());
        assertEquals(migrated, loadedAgain);
    }

    @Test
    public void transitionMatrixIncludesShippingAndOnlyVerificationRollback() {
        for (GsdPhase from : GsdPhase.values()) {
            for (GsdPhase to : GsdPhase.values()) {
                boolean allowed = (from == GsdPhase.DISCOVERY && to == GsdPhase.PLANNING)
                        || (from == GsdPhase.PLANNING && to == GsdPhase.EXECUTING)
                        || (from == GsdPhase.EXECUTING && to == GsdPhase.VERIFYING)
                        || (from == GsdPhase.VERIFYING && to == GsdPhase.SHIPPING)
                        || (from == GsdPhase.SHIPPING && to == GsdPhase.CLOSED)
                        || (from == GsdPhase.VERIFYING && to == GsdPhase.EXECUTING);
                try {
                    GsdWorkflowService.validateTransition(from, to, "audit reason"); //$NON-NLS-1$
                    assertTrue("unexpected allowed transition " + from + " -> " + to, allowed); //$NON-NLS-1$ //$NON-NLS-2$
                } catch (IllegalArgumentException e) {
                    assertFalse("unexpected rejected transition " + from + " -> " + to, allowed); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
    }

    @Test
    public void closureRequiresPassedRequiredCriteriaAndCompletedShipment() {
        GsdState blocked = closableState(GsdAcceptanceStatus.FAILED, GsdShipment.empty());
        try {
            GsdGuard.validate(blocked);
            fail("expected closure guard failure"); //$NON-NLS-1$
        } catch (GsdGuardException e) {
            assertTrue(e.getViolations().stream().anyMatch(v -> v.contains("criterion ac-1"))); //$NON-NLS-1$
            assertTrue(e.getViolations().stream().anyMatch(v -> v.contains("shipment"))); //$NON-NLS-1$
        }

        GsdState closable = closableState(GsdAcceptanceStatus.PASSED,
                GsdShipment.completed("shipment-1", "release/42", Instant.EPOCH)); //$NON-NLS-1$ //$NON-NLS-2$
        GsdGuard.validate(closable);
    }

    @Test
    public void recoveryAdvancesGenerationAndRejectsAbaSnapshot() throws IOException {
        Path root = tmp.newFolder("recovery").toPath(); //$NON-NLS-1$
        GsdStateStore store = new GsdStateStore(root);
        GsdState first = store.save(GsdState.fresh("cycle-a")); //$NON-NLS-1$
        GsdState second = store.save(first.withPhase(GsdPhase.PLANNING));
        assertEquals(first.generation(), second.generation());

        Path statePath = store.getGsdDirectory().resolve(GsdStateStore.STATE_JSON);
        Files.writeString(statePath, "{ corrupt", StandardCharsets.UTF_8); //$NON-NLS-1$
        GsdState recovered = store.load();
        assertEquals(first.revision(), recovered.revision());
        assertEquals(first.generation() + 1L, recovered.generation());
        assertNotEquals(first.concurrencyToken(), recovered.concurrencyToken());

        try {
            store.save(first);
            fail("expected stale token after recovery"); //$NON-NLS-1$
        } catch (GsdStaleTokenException e) {
            assertEquals(first.concurrencyToken(), e.getExpectedToken());
            assertEquals(recovered.concurrencyToken(), e.getActualToken());
        }

        Files.writeString(statePath, "{ corrupt again", StandardCharsets.UTF_8); //$NON-NLS-1$
        GsdState recoveredAgain = store.load();
        assertEquals(recovered.revision(), recoveredAgain.revision());
        assertEquals(recovered.generation() + 1L, recoveredAgain.generation());
        assertNotEquals(recovered.concurrencyToken(), recoveredAgain.concurrencyToken());
    }

    @Test
    public void projectionFailureReturnsCommittedStateWithWarning() throws IOException {
        Path root = tmp.newFolder("projection-warning").toPath(); //$NON-NLS-1$
        GsdStateStore store = new GsdStateStore(root);
        Files.createDirectories(store.getGsdDirectory().resolve(GsdProjections.STATE_FILE));

        GsdCommitOutcome outcome = store.commit(GsdState.fresh("cycle-projection")); //$NON-NLS-1$

        assertTrue(outcome.committed());
        assertTrue(outcome.hasWarnings());
        assertEquals(1L, outcome.state().revision());
        assertTrue(Files.isRegularFile(store.getGsdDirectory().resolve(GsdStateStore.STATE_JSON)));
        assertEquals(outcome.state(), store.loadReadOnly());
    }

    @Test
    public void closedAggregateCanStartNewCycleWithoutTokenReuse() throws IOException {
        Path root = tmp.newFolder("new-cycle").toPath(); //$NON-NLS-1$
        GsdStateStore store = new GsdStateStore(root);
        GsdState closed = store.save(closableState(
                GsdAcceptanceStatus.PASSED,
                GsdShipment.completed("shipment-1", "release/42", Instant.EPOCH))); //$NON-NLS-1$ //$NON-NLS-2$

        GsdState next = GsdWorkflowService.startNewCycle(root.toString(),
                closed.concurrencyToken(), "cycle-b", "next delivery"); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(GsdPhase.DISCOVERY, next.phase());
        assertEquals("cycle-b", next.cycleId()); //$NON-NLS-1$
        assertEquals(0L, next.revision());
        assertNotEquals(closed.concurrencyToken(), next.concurrencyToken());
        assertTrue(next.tasks().isEmpty());
        GsdTransition audit = next.transitionHistory().get(next.transitionHistory().size() - 1);
        assertEquals(GsdPhase.CLOSED, audit.fromPhase());
        assertEquals(GsdPhase.DISCOVERY, audit.toPhase());
        assertEquals("next delivery", audit.reason()); //$NON-NLS-1$
    }

    @Test
    public void tokenAwareWorkflowClosesOnlyAfterShipping() throws IOException {
        Path root = tmp.newFolder("closure-workflow").toPath(); //$NON-NLS-1$
        GsdState state = new GsdStateStore(root).load();
        state = GsdWorkflowService.transitionPhase(root.toString(), state.token(),
                GsdPhase.PLANNING, null);
        GsdTask task = new GsdTask("t1", "task", GsdTaskStatus.PENDING, "w1", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                List.of(), List.of(), GsdExecutionKind.READ_ONLY);
        GsdWave wave = new GsdWave("w1", "wave", "goal", List.of("t1")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        GsdAcceptanceCriterion criterion = new GsdAcceptanceCriterion(
                "ac-1", "release checks pass", true); //$NON-NLS-1$ //$NON-NLS-2$
        state = GsdWorkflowService.createPlan(root.toString(), state.token(), "goal", //$NON-NLS-1$
                List.of(criterion), List.of(task), List.of(wave));
        state = GsdWorkflowService.transitionPhase(root.toString(), state.token(),
                GsdPhase.EXECUTING, null);
        state = GsdWorkflowService.recordEvidence(root.toString(), state.token(),
                "e-exec", "implemented", GsdProvenance.TESTED, List.of("t1")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        state = GsdWorkflowService.updateTask(root.toString(), state.token(),
                "t1", GsdTaskStatus.DONE); //$NON-NLS-1$
        state = GsdWorkflowService.transitionPhase(root.toString(), state.token(),
                GsdPhase.VERIFYING, null);
        state = GsdWorkflowService.recordEvidence(root.toString(), state.token(),
                "e-verify", "verified", GsdProvenance.TESTED, List.of("t1")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        state = GsdWorkflowService.updateAcceptanceCriterion(root.toString(), state.token(),
                "ac-1", GsdAcceptanceStatus.PASSED); //$NON-NLS-1$

        try {
            GsdWorkflowService.transitionPhase(root.toString(), state.token(),
                    GsdPhase.CLOSED, null);
            fail("VERIFYING must not skip SHIPPING"); //$NON-NLS-1$
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("illegal")); //$NON-NLS-1$
        }

        state = GsdWorkflowService.transitionPhase(root.toString(), state.token(),
                GsdPhase.SHIPPING, null);
        state = GsdWorkflowService.completeShipment(root.toString(), state.token(),
                "shipment-1", "release/42"); //$NON-NLS-1$ //$NON-NLS-2$
        state = GsdWorkflowService.transitionPhase(root.toString(), state.token(),
                GsdPhase.CLOSED, null);
        assertEquals(GsdPhase.CLOSED, state.phase());
        assertTrue(state.shipment().completed());
        assertEquals(GsdPhase.SHIPPING, state.transitionHistory()
                .get(state.transitionHistory().size() - 1).fromPhase());
    }

    private static GsdState closableState(GsdAcceptanceStatus acceptanceStatus,
            GsdShipment shipment) {
        GsdEvidence evidence = new GsdEvidence("e1", "verified", GsdProvenance.TESTED, //$NON-NLS-1$ //$NON-NLS-2$
                List.of("t1"), Instant.EPOCH, GsdPhase.VERIFYING); //$NON-NLS-1$
        GsdTask task = new GsdTask("t1", "task", GsdTaskStatus.DONE, "w1", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                List.of(), List.of("e1"), GsdExecutionKind.READ_ONLY); //$NON-NLS-1$
        GsdWave wave = new GsdWave("w1", "wave", "goal", List.of("t1")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        GsdAcceptanceCriterion criterion = new GsdAcceptanceCriterion(
                "ac-1", "release checks pass", true, acceptanceStatus); //$NON-NLS-1$ //$NON-NLS-2$
        return new GsdState(GsdState.CURRENT_SCHEMA_VERSION, "cycle-a", 0L, 0L, //$NON-NLS-1$
                GsdPhase.CLOSED, "goal", List.of(criterion), List.of(), //$NON-NLS-1$
                List.of(task), List.of(wave), List.of(evidence), shipment,
                List.of(), GsdSessionPointer.empty());
    }
}
