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
import static org.junit.Assert.assertNull;
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
        assertEquals(List.of(GsdState.LEGACY_CYCLE_ID), migrated.usedCycleIds());
        assertTrue(migrated.acceptanceCriteria().isEmpty());
        assertTrue(migrated.transitionHistory().isEmpty());
        assertTrue(migrated.shipment().emptyRecord());
        assertTrue(Files.readString(statePath).contains("\"schemaVersion\": 2")); //$NON-NLS-1$
        assertTrue(Files.readString(statePath).contains("\"usedCycleIds\"")); //$NON-NLS-1$
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
                        || (from == GsdPhase.VERIFYING && to == GsdPhase.EXECUTING)
                        || (from == GsdPhase.SHIPPING && to == GsdPhase.VERIFYING)
                        || (from == GsdPhase.SHIPPING && to == GsdPhase.EXECUTING);
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

    @Test
    public void failedShipmentCanRollBackWithAuditAndReset() throws IOException {
        Path root = tmp.newFolder("failed-shipment-rollback").toPath(); //$NON-NLS-1$
        GsdState state = workflowToShipping(root);
        state = GsdWorkflowService.recordShipment(root.toString(), state.token(),
                new GsdShipment("shipment-failed", "release/failed", //$NON-NLS-1$ //$NON-NLS-2$
                        GsdShipmentStatus.FAILED, null));

        try {
            GsdWorkflowService.transitionPhase(root.toString(), state.token(),
                    GsdPhase.VERIFYING, null);
            fail("shipping rollback must require a reason"); //$NON-NLS-1$
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("reason")); //$NON-NLS-1$
        }

        state = GsdWorkflowService.transitionPhase(root.toString(), state.token(),
                GsdPhase.VERIFYING, "delivery packaging failed"); //$NON-NLS-1$
        assertTrue(state.shipment().emptyRecord());
        assertEquals(GsdAcceptanceStatus.PASSED, state.acceptanceCriteria().get(0).status());
        GsdTransition verifyAudit = state.transitionHistory()
                .get(state.transitionHistory().size() - 1);
        assertEquals(GsdPhase.SHIPPING, verifyAudit.fromPhase());
        assertEquals(GsdPhase.VERIFYING, verifyAudit.toPhase());
        assertEquals("delivery packaging failed", verifyAudit.reason()); //$NON-NLS-1$
        assertEquals("Shipping rollback", //$NON-NLS-1$
                state.decisions().get(state.decisions().size() - 1).summary());

        state = GsdWorkflowService.transitionPhase(root.toString(), state.token(),
                GsdPhase.SHIPPING, null);
        state = GsdWorkflowService.recordShipment(root.toString(), state.token(),
                new GsdShipment("shipment-failed-again", "release/failed-again", //$NON-NLS-1$ //$NON-NLS-2$
                        GsdShipmentStatus.FAILED, null));
        state = GsdWorkflowService.transitionPhase(root.toString(), state.token(),
                GsdPhase.EXECUTING, "implementation must change"); //$NON-NLS-1$
        assertTrue(state.shipment().emptyRecord());
        assertEquals(GsdAcceptanceStatus.PENDING, state.acceptanceCriteria().get(0).status());
        GsdTransition executeAudit = state.transitionHistory()
                .get(state.transitionHistory().size() - 1);
        assertEquals(GsdPhase.SHIPPING, executeAudit.fromPhase());
        assertEquals(GsdPhase.EXECUTING, executeAudit.toPhase());
        GsdGuard.validate(state);
    }

    @Test
    public void shipmentLifecycleIsIdempotentAndConflictSafe() throws IOException {
        Path root = tmp.newFolder("shipment-idempotency").toPath(); //$NON-NLS-1$
        GsdState shipping = workflowToShipping(root);
        GsdShipment inProgress = new GsdShipment(
                "shipment-1", "release/42", GsdShipmentStatus.IN_PROGRESS, null); //$NON-NLS-1$ //$NON-NLS-2$
        GsdCommitOutcome first = GsdWorkflowService.recordShipmentWithOutcome(
                root.toString(), shipping.token(), inProgress);
        assertTrue(first.committed());

        try {
            GsdWorkflowService.recordShipmentWithOutcome(
                    root.toString(), shipping.token(), inProgress);
            fail("an exact retry must not bypass a stale token fence"); //$NON-NLS-1$
        } catch (GsdStaleTokenException expected) {
            assertEquals(shipping.token(), expected.getExpectedToken());
            assertEquals(first.state().token(), expected.getActualToken());
        }

        GsdCommitOutcome duplicate = GsdWorkflowService.recordShipmentWithOutcome(
                root.toString(), first.state().token(), inProgress);
        assertFalse(duplicate.committed());
        assertEquals(first.state().token(), duplicate.state().token());

        GsdShipment completed = GsdShipment.completed(
                "shipment-1", "release/42", Instant.EPOCH); //$NON-NLS-1$ //$NON-NLS-2$
        GsdCommitOutcome terminal = GsdWorkflowService.recordShipmentWithOutcome(
                root.toString(), first.state().token(), completed);
        assertTrue(terminal.committed());
        assertTrue(terminal.state().shipment().completed());

        try {
            GsdWorkflowService.recordShipmentWithOutcome(root.toString(), terminal.state().token(),
                    new GsdShipment("shipment-2", "release/43", //$NON-NLS-1$ //$NON-NLS-2$
                            GsdShipmentStatus.FAILED, null));
            fail("terminal shipment replacement must be rejected"); //$NON-NLS-1$
        } catch (GsdShipmentConflictException expected) {
            assertTrue(expected.getMessage().contains("already recorded")); //$NON-NLS-1$
        }
    }

    @Test
    public void shipmentRetryComparesSanitizedValuesBeforeConflict() throws IOException {
        Path root = tmp.newFolder("shipment-sanitized-idempotency").toPath(); //$NON-NLS-1$
        GsdState shipping = workflowToShipping(root);
        GsdShipment request = new GsdShipment(
                "shipment\u200B-1", "release/\u200B42", //$NON-NLS-1$ //$NON-NLS-2$
                GsdShipmentStatus.IN_PROGRESS, null);

        GsdCommitOutcome first = GsdWorkflowService.recordShipmentWithOutcome(
                root.toString(), shipping.token(), request);
        assertTrue(first.committed());
        assertEquals("shipment-1", first.state().shipment().id()); //$NON-NLS-1$
        assertEquals("release/42", first.state().shipment().deliveryReference()); //$NON-NLS-1$

        GsdCommitOutcome retry = GsdWorkflowService.recordShipmentWithOutcome(
                root.toString(), first.state().token(), request);
        assertFalse(retry.committed());
        assertEquals(first.state().token(), retry.state().token());
    }

    @Test
    public void newCycleRejectsAnyCycleIdPresentInRealHistory() throws IOException {
        Path root = tmp.newFolder("cycle-reuse").toPath(); //$NON-NLS-1$
        GsdStateStore store = new GsdStateStore(root);
        GsdState closed = store.save(historicalClosedState());

        try {
            GsdWorkflowService.startNewCycle(root.toString(), closed.token(),
                    "cycle-a", "reuse old cycle"); //$NON-NLS-1$ //$NON-NLS-2$
            fail("expected explicit cycle reuse rejection"); //$NON-NLS-1$
        } catch (GsdCycleIdReuseException expected) {
            assertEquals("cycle-a", expected.getCycleId()); //$NON-NLS-1$
            assertTrue(expected.getMessage().contains("already been used")); //$NON-NLS-1$
        }

        GsdTransition reusedAudit = new GsdTransition("cycle-a", closed.generation(), //$NON-NLS-1$
                0L, GsdPhase.CLOSED, GsdPhase.DISCOVERY, "reuse", Instant.now()); //$NON-NLS-1$
        GsdState reused = closed.startCycle("cycle-a", reusedAudit); //$NON-NLS-1$
        try {
            store.commitNewCycle(closed.token(), reused);
            fail("store must enforce the same cycle reuse rule"); //$NON-NLS-1$
        } catch (GsdCycleIdReuseException expected) {
            assertEquals("cycle-a", expected.getCycleId()); //$NON-NLS-1$
        }
    }

    @Test
    public void compatibilityConstructorPreservesMultiCycleFenceOrder() throws IOException {
        Path root = tmp.newFolder("compatibility-cycle-order").toPath(); //$NON-NLS-1$
        GsdStateStore store = new GsdStateStore(root);
        GsdState persisted = store.save(multiCycleClosedState());

        GsdState reconstructed = new GsdState(
                persisted.schemaVersion(), persisted.cycleId(), persisted.generation(),
                persisted.revision(), persisted.phase(), persisted.goal(),
                persisted.acceptanceCriteria(), persisted.decisions(), persisted.tasks(),
                persisted.waves(), persisted.evidence(), persisted.shipment(),
                persisted.transitionHistory(), persisted.sessionPointer());

        assertEquals(List.of("cycle-a", "cycle-b", "cycle-c"), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                reconstructed.usedCycleIds());
        GsdState committed = store.commit(reconstructed).state();
        assertEquals(reconstructed.usedCycleIds(), committed.usedCycleIds());

        try {
            store.commit(withUsedCycleIds(committed,
                    List.of("cycle-a", "cycle-b", "cycle-c", "cycle-c"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            fail("duplicate cycle fence identity must be rejected"); //$NON-NLS-1$
        } catch (GsdGuardException expected) {
            assertTrue(expected.getViolations().stream()
                    .anyMatch(v -> v.contains("duplicate used cycleId"))); //$NON-NLS-1$
        }

        try {
            GsdWorkflowService.startNewCycle(root.toString(), committed.token(),
                    "cycle-a", "reuse historical cycle"); //$NON-NLS-1$ //$NON-NLS-2$
            fail("historical cycle reuse must remain rejected"); //$NON-NLS-1$
        } catch (GsdCycleIdReuseException expected) {
            assertEquals("cycle-a", expected.getCycleId()); //$NON-NLS-1$
        }
    }

    @Test
    public void commitNewCycleRejectsGenerationRegressionAndStaleAbaToken() throws IOException {
        Path root = tmp.newFolder("cycle-generation").toPath(); //$NON-NLS-1$
        GsdStateStore store = new GsdStateStore(root);
        GsdState closed = store.save(historicalClosedState());
        GsdTransition audit = new GsdTransition("cycle-c", closed.generation(), //$NON-NLS-1$
                0L, GsdPhase.CLOSED, GsdPhase.DISCOVERY, "next", Instant.now()); //$NON-NLS-1$
        GsdState next = closed.startCycle("cycle-c", audit); //$NON-NLS-1$

        try {
            store.commitNewCycle(closed.token(), next.withGeneration(closed.generation() - 1L));
            fail("generation regression must be rejected explicitly"); //$NON-NLS-1$
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("must not regress")); //$NON-NLS-1$
        }

        GsdState committed = store.commitNewCycle(closed.token(), next).state();
        assertEquals(closed.generation(), committed.generation());
        assertEquals("cycle-c", committed.cycleId()); //$NON-NLS-1$
        try {
            store.commitNewCycle(closed.token(), next);
            fail("old cycle token must not pass CAS after replacement"); //$NON-NLS-1$
        } catch (GsdStaleTokenException expected) {
            assertEquals(closed.token(), expected.getExpectedToken());
            assertEquals(committed.token(), expected.getActualToken());
        }
    }

    @Test
    public void workflowOutcomePropagatesProjectionWarningsAfterCommit() throws IOException {
        Path root = tmp.newFolder("workflow-projection-warning").toPath(); //$NON-NLS-1$
        GsdStateStore store = new GsdStateStore(root);
        GsdState initial = store.save(GsdState.fresh("cycle-warning")); //$NON-NLS-1$
        Path stateProjection = store.getGsdDirectory().resolve(GsdProjections.STATE_FILE);
        Files.delete(stateProjection);
        Files.createDirectory(stateProjection);

        GsdState state = warned(GsdWorkflowService.recordDecisionWithOutcome(
                root.toString(), initial.token(), "d1", "scope", "ship", List.of())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        state = warned(GsdWorkflowService.transitionPhaseWithOutcome(
                root.toString(), state.token(), GsdPhase.PLANNING, null));
        GsdTask task = new GsdTask("t1", "task", GsdTaskStatus.PENDING, "w1", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                List.of(), List.of(), GsdExecutionKind.READ_ONLY);
        GsdWave wave = new GsdWave("w1", "wave", "goal", List.of("t1")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        GsdAcceptanceCriterion criterion = new GsdAcceptanceCriterion(
                "ac-1", "release checks pass", true); //$NON-NLS-1$ //$NON-NLS-2$
        state = warned(GsdWorkflowService.createPlanWithOutcome(root.toString(), state.token(),
                "goal", List.of(criterion), List.of(task), List.of(wave))); //$NON-NLS-1$
        state = warned(GsdWorkflowService.transitionPhaseWithOutcome(
                root.toString(), state.token(), GsdPhase.EXECUTING, null));
        state = warned(GsdWorkflowService.recordEvidenceWithOutcome(root.toString(), state.token(),
                "e-exec", "implemented", GsdProvenance.TESTED, List.of("t1"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        state = warned(GsdWorkflowService.updateTaskWithOutcome(
                root.toString(), state.token(), "t1", GsdTaskStatus.DONE)); //$NON-NLS-1$
        state = warned(GsdWorkflowService.transitionPhaseWithOutcome(
                root.toString(), state.token(), GsdPhase.VERIFYING, null));
        state = warned(GsdWorkflowService.recordEvidenceWithOutcome(root.toString(), state.token(),
                "e-verify", "verified", GsdProvenance.TESTED, List.of("t1"))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        state = warned(GsdWorkflowService.updateAcceptanceCriterionWithOutcome(
                root.toString(), state.token(), "ac-1", GsdAcceptanceStatus.PASSED)); //$NON-NLS-1$
        state = warned(GsdWorkflowService.transitionPhaseWithOutcome(
                root.toString(), state.token(), GsdPhase.SHIPPING, null));
        state = warned(GsdWorkflowService.recordShipmentWithOutcome(root.toString(), state.token(),
                new GsdShipment("shipment-progress", "release/pending", //$NON-NLS-1$ //$NON-NLS-2$
                        GsdShipmentStatus.IN_PROGRESS, null)));
        state = warned(GsdWorkflowService.completeShipmentWithOutcome(root.toString(), state.token(),
                "shipment-progress", "release/pending")); //$NON-NLS-1$ //$NON-NLS-2$
        state = warned(GsdWorkflowService.transitionPhaseWithOutcome(
                root.toString(), state.token(), GsdPhase.CLOSED, null));
        state = warned(GsdWorkflowService.startNewCycleWithOutcome(root.toString(), state.token(),
                "cycle-warning-next", "next delivery")); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals(GsdPhase.DISCOVERY, state.phase());
        assertEquals(state, store.loadReadOnly());
    }

    @Test
    public void migratedLegacyCycleIdentitySurvivesRealWorkflowAndCannotBeReused()
            throws IOException {
        Path root = tmp.newFolder("legacy-cycle-fence").toPath(); //$NON-NLS-1$
        GsdStateStore store = new GsdStateStore(root);
        Files.createDirectories(store.getGsdDirectory());
        Files.writeString(store.getGsdDirectory().resolve(GsdStateStore.STATE_JSON),
                legacyClosedJson(), StandardCharsets.UTF_8);

        GsdState migrated = store.load();
        assertEquals(List.of(GsdState.LEGACY_CYCLE_ID), migrated.usedCycleIds());
        GsdState state = GsdWorkflowService.startNewCycle(root.toString(), migrated.token(),
                "cycle-after-legacy", "first native cycle"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(List.of(GsdState.LEGACY_CYCLE_ID, "cycle-after-legacy"), //$NON-NLS-1$
                state.usedCycleIds());
        assertEquals(state.usedCycleIds(), store.loadReadOnly().usedCycleIds());

        try {
            store.commit(withUsedCycleIds(state, List.of(state.cycleId())));
            fail("ordinary commit must not prune the historical cycle fence"); //$NON-NLS-1$
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("preserve the used-cycle")); //$NON-NLS-1$
        }

        state = completeCurrentCycle(root, state);
        try {
            GsdWorkflowService.startNewCycle(root.toString(), state.token(),
                    GsdState.LEGACY_CYCLE_ID, "attempt ABA reuse"); //$NON-NLS-1$
            fail("migrated legacy cycle identity must remain fenced"); //$NON-NLS-1$
        } catch (GsdCycleIdReuseException expected) {
            assertEquals(GsdState.LEGACY_CYCLE_ID, expected.getCycleId());
        }
    }

    @Test
    public void legacyMigratedShipmentCannotBeInjected() throws IOException {
        Path root = tmp.newFolder("legacy-shipment-injection").toPath(); //$NON-NLS-1$
        GsdState shipping = workflowToShipping(root);
        try {
            GsdWorkflowService.recordShipmentWithOutcome(
                    root.toString(), shipping.token(), GsdShipment.legacyMigrated());
            fail("legacy migration marker must not be accepted as a workflow shipment"); //$NON-NLS-1$
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("reserved for schema-v1 migration")); //$NON-NLS-1$
        }
        assertEquals(shipping, new GsdStateStore(root).loadReadOnly());

        try {
            GsdGuard.validate(closableState(
                    GsdAcceptanceStatus.PASSED, GsdShipment.legacyMigrated()));
            fail("legacy migration marker must not validate on a native aggregate"); //$NON-NLS-1$
        } catch (GsdGuardException expected) {
            assertTrue(expected.getViolations().stream()
                    .anyMatch(v -> v.contains("LEGACY_MIGRATED"))); //$NON-NLS-1$
        }
    }

    @Test
    public void migratedClosedShipmentIsExplicitlyLegacyNotCompleted() throws IOException {
        Path root = tmp.newFolder("legacy-closed-shipment").toPath(); //$NON-NLS-1$
        GsdStateStore store = new GsdStateStore(root);
        Path statePath = store.getGsdDirectory().resolve(GsdStateStore.STATE_JSON);
        Files.createDirectories(store.getGsdDirectory());
        Files.writeString(statePath, legacyClosedJson(), StandardCharsets.UTF_8);

        GsdState migrated = store.load();

        assertEquals(GsdPhase.CLOSED, migrated.phase());
        assertEquals(GsdShipmentStatus.LEGACY_MIGRATED, migrated.shipment().status());
        assertFalse(migrated.shipment().completed());
        assertTrue(migrated.shipment().satisfiesClosure());
        assertNull(migrated.shipment().completedAt());
        assertFalse(Files.readString(statePath).contains("\"status\": \"COMPLETED\"")); //$NON-NLS-1$
        GsdGuard.validate(migrated);
    }

    private static GsdState workflowToShipping(Path root) throws IOException {
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
        return GsdWorkflowService.transitionPhase(root.toString(), state.token(),
                GsdPhase.SHIPPING, null);
    }

    private static GsdState completeCurrentCycle(Path root, GsdState state) throws IOException {
        state = GsdWorkflowService.transitionPhase(root.toString(), state.token(),
                GsdPhase.PLANNING, null);
        GsdTask task = new GsdTask("native-t1", "task", GsdTaskStatus.PENDING, "native-w1", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                List.of(), List.of(), GsdExecutionKind.READ_ONLY);
        GsdWave wave = new GsdWave("native-w1", "wave", "goal", List.of("native-t1")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        GsdAcceptanceCriterion criterion = new GsdAcceptanceCriterion(
                "native-ac", "release checks pass", true); //$NON-NLS-1$ //$NON-NLS-2$
        state = GsdWorkflowService.createPlan(root.toString(), state.token(), "goal", //$NON-NLS-1$
                List.of(criterion), List.of(task), List.of(wave));
        state = GsdWorkflowService.transitionPhase(root.toString(), state.token(),
                GsdPhase.EXECUTING, null);
        state = GsdWorkflowService.recordEvidence(root.toString(), state.token(),
                "native-exec", "implemented", GsdProvenance.TESTED, List.of("native-t1")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        state = GsdWorkflowService.updateTask(root.toString(), state.token(),
                "native-t1", GsdTaskStatus.DONE); //$NON-NLS-1$
        state = GsdWorkflowService.transitionPhase(root.toString(), state.token(),
                GsdPhase.VERIFYING, null);
        state = GsdWorkflowService.recordEvidence(root.toString(), state.token(),
                "native-verify", "verified", GsdProvenance.TESTED, List.of("native-t1")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        state = GsdWorkflowService.updateAcceptanceCriterion(root.toString(), state.token(),
                "native-ac", GsdAcceptanceStatus.PASSED); //$NON-NLS-1$
        state = GsdWorkflowService.transitionPhase(root.toString(), state.token(),
                GsdPhase.SHIPPING, null);
        state = GsdWorkflowService.completeShipment(root.toString(), state.token(),
                "native-shipment", "release/native"); //$NON-NLS-1$ //$NON-NLS-2$
        return GsdWorkflowService.transitionPhase(root.toString(), state.token(),
                GsdPhase.CLOSED, null);
    }

    private static GsdState warned(GsdCommitOutcome outcome) {
        assertTrue(outcome.committed());
        assertTrue(outcome.hasWarnings());
        assertTrue(outcome.projectionWarnings().get(0).contains("state committed")); //$NON-NLS-1$
        return outcome.state();
    }

    private static GsdState withUsedCycleIds(GsdState state, List<String> usedCycleIds) {
        return new GsdState(state.schemaVersion(), state.cycleId(), state.generation(),
                state.revision(), state.phase(), state.goal(), state.acceptanceCriteria(),
                state.decisions(), state.tasks(), state.waves(), state.evidence(),
                state.shipment(), state.transitionHistory(), usedCycleIds,
                state.sessionPointer());
    }

    private static GsdState historicalClosedState() {
        GsdState base = closableState(GsdAcceptanceStatus.PASSED,
                GsdShipment.completed("shipment-history", "release/history", Instant.EPOCH)); //$NON-NLS-1$ //$NON-NLS-2$
        List<GsdTransition> history = List.of(
                new GsdTransition("cycle-a", 0L, 0L, GsdPhase.DISCOVERY, //$NON-NLS-1$
                        GsdPhase.PLANNING, "historical", Instant.EPOCH), //$NON-NLS-1$
                new GsdTransition("cycle-b", 3L, 0L, GsdPhase.SHIPPING, //$NON-NLS-1$
                        GsdPhase.CLOSED, "delivered", Instant.EPOCH)); //$NON-NLS-1$
        return new GsdState(GsdState.CURRENT_SCHEMA_VERSION, "cycle-b", 3L, 0L, //$NON-NLS-1$
                GsdPhase.CLOSED, base.goal(), base.acceptanceCriteria(), base.decisions(),
                base.tasks(), base.waves(), base.evidence(), base.shipment(), history,
                base.sessionPointer());
    }

    private static GsdState multiCycleClosedState() {
        GsdState base = closableState(GsdAcceptanceStatus.PASSED,
                GsdShipment.completed("shipment-multi", "release/multi", Instant.EPOCH)); //$NON-NLS-1$ //$NON-NLS-2$
        List<GsdTransition> history = List.of(
                new GsdTransition("cycle-a", 0L, 0L, GsdPhase.DISCOVERY, //$NON-NLS-1$
                        GsdPhase.PLANNING, "cycle a", Instant.EPOCH), //$NON-NLS-1$
                new GsdTransition("cycle-b", 0L, 0L, GsdPhase.CLOSED, //$NON-NLS-1$
                        GsdPhase.DISCOVERY, "cycle b", Instant.EPOCH), //$NON-NLS-1$
                new GsdTransition("cycle-b", 0L, 0L, GsdPhase.DISCOVERY, //$NON-NLS-1$
                        GsdPhase.PLANNING, "cycle b plan", Instant.EPOCH), //$NON-NLS-1$
                new GsdTransition("cycle-c", 0L, 0L, GsdPhase.CLOSED, //$NON-NLS-1$
                        GsdPhase.DISCOVERY, "cycle c", Instant.EPOCH), //$NON-NLS-1$
                new GsdTransition("cycle-c", 0L, 0L, GsdPhase.SHIPPING, //$NON-NLS-1$
                        GsdPhase.CLOSED, "cycle c delivered", Instant.EPOCH)); //$NON-NLS-1$
        return new GsdState(GsdState.CURRENT_SCHEMA_VERSION, "cycle-c", 0L, 0L, //$NON-NLS-1$
                GsdPhase.CLOSED, base.goal(), base.acceptanceCriteria(), base.decisions(),
                base.tasks(), base.waves(), base.evidence(), base.shipment(), history,
                List.of("cycle-a", "cycle-b", "cycle-c"), base.sessionPointer()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private static String legacyClosedJson() {
        return "{\"schemaVersion\":1,\"revision\":7,\"phase\":\"CLOSED\"," //$NON-NLS-1$
                + "\"goal\":\"legacy\",\"decisions\":[]," //$NON-NLS-1$
                + "\"tasks\":[{\"id\":\"t1\",\"title\":\"task\"," //$NON-NLS-1$
                + "\"status\":\"DONE\",\"waveId\":\"w1\",\"dependsOn\":[]," //$NON-NLS-1$
                + "\"evidenceIds\":[\"e1\"],\"executionKind\":\"READ_ONLY\"}]," //$NON-NLS-1$
                + "\"waves\":[{\"id\":\"w1\",\"name\":\"wave\"," //$NON-NLS-1$
                + "\"goal\":\"legacy\",\"taskIds\":[\"t1\"]}]," //$NON-NLS-1$
                + "\"evidence\":[{\"id\":\"e1\",\"description\":\"verified\"," //$NON-NLS-1$
                + "\"provenance\":\"TESTED\",\"taskIds\":[\"t1\"]," //$NON-NLS-1$
                + "\"createdAt\":\"2026-08-21T00:00:00Z\"," //$NON-NLS-1$
                + "\"capturedPhase\":\"VERIFYING\"}]," //$NON-NLS-1$
                + "\"sessionPointer\":{\"sessionId\":\"\",\"workstreamId\":\"\"}}"; //$NON-NLS-1$
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
