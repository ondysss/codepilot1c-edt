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

/**
 * Schema-v2 aggregate for one or more GSD delivery cycles.
 *
 * <p>{@link #usedCycleIds()} is a correctness-critical ABA fence. Transition
 * history is an audit log, but a cycle is not guaranteed to have a transition of
 * its own (notably a migrated v1 cycle). Neither collection may be pruned unless
 * every removed cycle identity remains represented in {@code usedCycleIds}.</p>
 */
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
        List<String> usedCycleIds,
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
        usedCycleIds = usedCycleIds == null
                ? deriveUsedCycleIds(cycleId, transitionHistory)
                : List.copyOf(usedCycleIds);
        sessionPointer = sessionPointer == null ? GsdSessionPointer.empty() : sessionPointer;
    }

    /**
     * Compatibility constructor for schema-v2 callers compiled before the explicit
     * used-cycle fence was added. The fence is deterministically derived from all
     * cycle identities visible in that older aggregate shape.
     */
    public GsdState(int schemaVersion, String cycleId, long generation, long revision,
            GsdPhase phase, String goal, List<GsdAcceptanceCriterion> acceptanceCriteria,
            List<GsdDecision> decisions, List<GsdTask> tasks, List<GsdWave> waves,
            List<GsdEvidence> evidence, GsdShipment shipment,
            List<GsdTransition> transitionHistory, GsdSessionPointer sessionPointer) {
        this(schemaVersion, cycleId, generation, revision, phase, goal,
                acceptanceCriteria, decisions, tasks, waves, evidence, shipment,
                transitionHistory, deriveUsedCycleIds(cycleId, transitionHistory), sessionPointer);
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
                legacyShipment(phase), List.of(), List.of(LEGACY_CYCLE_ID), sessionPointer);
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
                List.of(cycleId), GsdSessionPointer.empty());
    }

    static GsdState migratedFromV1(long revision, GsdPhase phase, String goal,
            List<GsdDecision> decisions, List<GsdTask> tasks, List<GsdWave> waves,
            List<GsdEvidence> evidence, GsdSessionPointer sessionPointer) {
        return new GsdState(CURRENT_SCHEMA_VERSION, LEGACY_CYCLE_ID, INITIAL_GENERATION,
                revision, phase, goal, List.of(), decisions, tasks, waves, evidence,
                legacyShipment(phase), List.of(), List.of(LEGACY_CYCLE_ID), sessionPointer);
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
                decisions, tasks, waves, evidence, shipment, transitionHistory,
                usedCycleIds, sessionPointer);
    }

    public GsdState withGeneration(long newGeneration) {
        return copy(cycleId, newGeneration, revision, phase, goal, acceptanceCriteria,
                decisions, tasks, waves, evidence, shipment, transitionHistory,
                usedCycleIds, sessionPointer);
    }

    public GsdState withPhase(GsdPhase newPhase) {
        return copy(cycleId, generation, revision, Objects.requireNonNull(newPhase, "newPhase"), //$NON-NLS-1$
                goal, acceptanceCriteria, decisions, tasks, waves, evidence, shipment,
                transitionHistory, usedCycleIds, sessionPointer);
    }

    public GsdState withAcceptanceCriteria(List<GsdAcceptanceCriterion> criteria) {
        return copy(cycleId, generation, revision, phase, goal, criteria, decisions,
                tasks, waves, evidence, shipment, transitionHistory, usedCycleIds, sessionPointer);
    }

    public GsdState withShipment(GsdShipment newShipment) {
        return copy(cycleId, generation, revision, phase, goal, acceptanceCriteria,
                decisions, tasks, waves, evidence, newShipment, transitionHistory,
                usedCycleIds, sessionPointer);
    }

    public GsdState withTransitionHistory(List<GsdTransition> history) {
        return copy(cycleId, generation, revision, phase, goal, acceptanceCriteria,
                decisions, tasks, waves, evidence, shipment, history, usedCycleIds, sessionPointer);
    }

    GsdState withDecisions(List<GsdDecision> newDecisions) {
        return copy(cycleId, generation, revision, phase, goal, acceptanceCriteria,
                newDecisions, tasks, waves, evidence, shipment, transitionHistory,
                usedCycleIds, sessionPointer);
    }

    GsdState withPlan(String newGoal, List<GsdAcceptanceCriterion> criteria,
            List<GsdTask> newTasks, List<GsdWave> newWaves) {
        return copy(cycleId, generation, revision, phase, newGoal, criteria, decisions,
                newTasks, newWaves, evidence, shipment, transitionHistory,
                usedCycleIds, sessionPointer);
    }

    GsdState withTasks(List<GsdTask> newTasks) {
        return copy(cycleId, generation, revision, phase, goal, acceptanceCriteria,
                decisions, newTasks, waves, evidence, shipment, transitionHistory,
                usedCycleIds, sessionPointer);
    }

    GsdState withTasksAndEvidence(List<GsdTask> newTasks, List<GsdEvidence> newEvidence) {
        return copy(cycleId, generation, revision, phase, goal, acceptanceCriteria,
                decisions, newTasks, waves, newEvidence, shipment, transitionHistory,
                usedCycleIds, sessionPointer);
    }

    GsdState recovered() {
        return copy(cycleId, generation + 1L, revision, phase, goal, acceptanceCriteria,
                decisions, tasks, waves, evidence, shipment, transitionHistory,
                usedCycleIds, sessionPointer);
    }

    GsdState startCycle(String newCycleId, GsdTransition cycleTransition) {
        List<GsdTransition> history = new java.util.ArrayList<>(transitionHistory);
        history.add(cycleTransition);
        List<String> cycles = new java.util.ArrayList<>(usedCycleIds);
        cycles.add(newCycleId);
        return new GsdState(CURRENT_SCHEMA_VERSION, newCycleId, generation,
                INITIAL_REVISION, GsdPhase.DISCOVERY, "", List.of(), List.of(), //$NON-NLS-1$
                List.of(), List.of(), List.of(), GsdShipment.empty(), history, cycles,
                sessionPointer);
    }

    private GsdState copy(String newCycleId, long newGeneration, long newRevision,
            GsdPhase newPhase, String newGoal, List<GsdAcceptanceCriterion> criteria,
            List<GsdDecision> newDecisions, List<GsdTask> newTasks, List<GsdWave> newWaves,
            List<GsdEvidence> newEvidence, GsdShipment newShipment,
            List<GsdTransition> history, List<String> cycles, GsdSessionPointer pointer) {
        return new GsdState(CURRENT_SCHEMA_VERSION, newCycleId, newGeneration, newRevision,
                newPhase, newGoal, criteria, newDecisions, newTasks, newWaves,
                newEvidence, newShipment, history, cycles, pointer);
    }

    private static List<String> deriveUsedCycleIds(String currentCycleId,
            List<GsdTransition> history) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        if (currentCycleId != null) {
            ids.add(currentCycleId);
        }
        if (history != null) {
            for (GsdTransition transition : history) {
                if (transition != null) {
                    ids.add(transition.cycleId());
                }
            }
        }
        return List.copyOf(ids);
    }
}
