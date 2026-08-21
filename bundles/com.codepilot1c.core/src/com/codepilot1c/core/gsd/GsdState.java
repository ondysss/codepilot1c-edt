/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

import java.util.List;
import java.util.Objects;

/** Schema-v2 aggregate for one or more GSD delivery cycles. */
public record GsdState(
        int schemaVersion,
        String cycleId,
        long generation,
        long revision,
        GsdPhase phase,
        String goal,
        List<GsdAcceptanceCriterion> acceptanceCriteria,
        List<GsdDecision> decisions,
        List<GsdTask> tasks,
        List<GsdWave> waves,
        List<GsdEvidence> evidence,
        GsdShipment shipment,
        List<GsdTransition> transitionHistory,
        GsdSessionPointer sessionPointer) {

    /** Current persisted schema version. */
    public static final int CURRENT_SCHEMA_VERSION = 2;
    /** Only schema version accepted by the explicit migration path. */
    public static final int LEGACY_SCHEMA_VERSION = 1;
    /** Revision assigned to a new cycle before its first ordinary mutation. */
    public static final long INITIAL_REVISION = 0L;
    /** Recovery generation assigned to a newly-created aggregate. */
    public static final long INITIAL_GENERATION = 0L;
    /** Deterministic identity used when migrating schema-v1 state. */
    public static final String LEGACY_CYCLE_ID = "legacy-cycle"; //$NON-NLS-1$
    /** Stable identity used before the first state file is committed. */
    public static final String INITIAL_CYCLE_ID = "cycle-1"; //$NON-NLS-1$

    public GsdState {
        cycleId = cycleId == null ? "" : cycleId; //$NON-NLS-1$
        phase = phase == null ? GsdPhase.DISCOVERY : phase;
        goal = goal == null ? "" : goal; //$NON-NLS-1$
        acceptanceCriteria = acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        waves = waves == null ? List.of() : List.copyOf(waves);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        shipment = shipment == null ? GsdShipment.empty() : shipment;
        transitionHistory = transitionHistory == null ? List.of() : List.copyOf(transitionHistory);
        sessionPointer = sessionPointer == null ? GsdSessionPointer.empty() : sessionPointer;
    }

    /**
     * Schema-v1-shaped compatibility constructor.
     *
     * <p>A directly-constructed legacy CLOSED state receives an explicit migration
     * marker so previously valid v1 callers remain valid without fabricating a
     * completed delivery record.</p>
     */
    public GsdState(int schemaVersion, long revision, GsdPhase phase, String goal,
            List<GsdDecision> decisions, List<GsdTask> tasks, List<GsdWave> waves,
            List<GsdEvidence> evidence, GsdSessionPointer sessionPointer) {
        this(schemaVersion, LEGACY_CYCLE_ID, INITIAL_GENERATION, revision,
                phase, goal, List.of(), decisions, tasks, waves, evidence,
                legacyShipment(phase), List.of(), sessionPointer);
    }

    /** Returns an empty state for a newly initialized aggregate. */
    public static GsdState fresh() {
        return fresh(INITIAL_CYCLE_ID);
    }

    /** Returns an empty state with a caller-supplied cycle identifier. */
    public static GsdState fresh(String cycleId) {
        return new GsdState(CURRENT_SCHEMA_VERSION, cycleId, INITIAL_GENERATION,
                INITIAL_REVISION, GsdPhase.DISCOVERY, "", List.of(), List.of(), //$NON-NLS-1$
                List.of(), List.of(), List.of(), GsdShipment.empty(), List.of(),
                GsdSessionPointer.empty());
    }

    static GsdState migratedFromV1(long revision, GsdPhase phase, String goal,
            List<GsdDecision> decisions, List<GsdTask> tasks, List<GsdWave> waves,
            List<GsdEvidence> evidence, GsdSessionPointer sessionPointer) {
        return new GsdState(CURRENT_SCHEMA_VERSION, LEGACY_CYCLE_ID, INITIAL_GENERATION,
                revision, phase, goal, List.of(), decisions, tasks, waves, evidence,
                legacyShipment(phase), List.of(), sessionPointer);
    }

    private static GsdShipment legacyShipment(GsdPhase phase) {
        if (phase == GsdPhase.CLOSED) {
            return GsdShipment.legacyMigrated();
        }
        return GsdShipment.empty();
    }

    /** @return the complete optimistic-concurrency identity */
    public GsdConcurrencyToken concurrencyToken() {
        return new GsdConcurrencyToken(cycleId, generation, revision);
    }

    /** Concise alias for {@link #concurrencyToken()}. */
    public GsdConcurrencyToken token() {
        return concurrencyToken();
    }

    public GsdState withRevision(long newRevision) {
        return copy(cycleId, generation, newRevision, phase, goal, acceptanceCriteria,
                decisions, tasks, waves, evidence, shipment, transitionHistory, sessionPointer);
    }

    public GsdState withGeneration(long newGeneration) {
        return copy(cycleId, newGeneration, revision, phase, goal, acceptanceCriteria,
                decisions, tasks, waves, evidence, shipment, transitionHistory, sessionPointer);
    }

    public GsdState withPhase(GsdPhase newPhase) {
        return copy(cycleId, generation, revision, Objects.requireNonNull(newPhase, "newPhase"), //$NON-NLS-1$
                goal, acceptanceCriteria, decisions, tasks, waves, evidence, shipment,
                transitionHistory, sessionPointer);
    }

    public GsdState withAcceptanceCriteria(List<GsdAcceptanceCriterion> criteria) {
        return copy(cycleId, generation, revision, phase, goal, criteria, decisions,
                tasks, waves, evidence, shipment, transitionHistory, sessionPointer);
    }

    public GsdState withShipment(GsdShipment newShipment) {
        return copy(cycleId, generation, revision, phase, goal, acceptanceCriteria,
                decisions, tasks, waves, evidence, newShipment, transitionHistory, sessionPointer);
    }

    public GsdState withTransitionHistory(List<GsdTransition> history) {
        return copy(cycleId, generation, revision, phase, goal, acceptanceCriteria,
                decisions, tasks, waves, evidence, shipment, history, sessionPointer);
    }

    GsdState withDecisions(List<GsdDecision> newDecisions) {
        return copy(cycleId, generation, revision, phase, goal, acceptanceCriteria,
                newDecisions, tasks, waves, evidence, shipment, transitionHistory, sessionPointer);
    }

    GsdState withPlan(String newGoal, List<GsdAcceptanceCriterion> criteria,
            List<GsdTask> newTasks, List<GsdWave> newWaves) {
        return copy(cycleId, generation, revision, phase, newGoal, criteria, decisions,
                newTasks, newWaves, evidence, shipment, transitionHistory, sessionPointer);
    }

    GsdState withTasks(List<GsdTask> newTasks) {
        return copy(cycleId, generation, revision, phase, goal, acceptanceCriteria,
                decisions, newTasks, waves, evidence, shipment, transitionHistory, sessionPointer);
    }

    GsdState withTasksAndEvidence(List<GsdTask> newTasks, List<GsdEvidence> newEvidence) {
        return copy(cycleId, generation, revision, phase, goal, acceptanceCriteria,
                decisions, newTasks, waves, newEvidence, shipment, transitionHistory, sessionPointer);
    }

    GsdState recovered() {
        return copy(cycleId, generation + 1L, revision, phase, goal, acceptanceCriteria,
                decisions, tasks, waves, evidence, shipment, transitionHistory, sessionPointer);
    }

    GsdState startCycle(String newCycleId, GsdTransition cycleTransition) {
        List<GsdTransition> history = new java.util.ArrayList<>(transitionHistory);
        history.add(cycleTransition);
        return new GsdState(CURRENT_SCHEMA_VERSION, newCycleId, generation,
                INITIAL_REVISION, GsdPhase.DISCOVERY, "", List.of(), List.of(), //$NON-NLS-1$
                List.of(), List.of(), List.of(), GsdShipment.empty(), history, sessionPointer);
    }

    private GsdState copy(String newCycleId, long newGeneration, long newRevision,
            GsdPhase newPhase, String newGoal, List<GsdAcceptanceCriterion> criteria,
            List<GsdDecision> newDecisions, List<GsdTask> newTasks, List<GsdWave> newWaves,
            List<GsdEvidence> newEvidence, GsdShipment newShipment,
            List<GsdTransition> history, GsdSessionPointer pointer) {
        return new GsdState(CURRENT_SCHEMA_VERSION, newCycleId, newGeneration, newRevision,
                newPhase, newGoal, criteria, newDecisions, newTasks, newWaves,
                newEvidence, newShipment, history, pointer);
    }
}
