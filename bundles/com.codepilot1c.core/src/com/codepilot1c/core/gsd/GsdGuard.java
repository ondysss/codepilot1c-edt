/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Enforces the structural invariants of a {@link GsdState}. Invariants are checked by
 * {@link GsdStateStore} before any write reaches disk, so a state that violates them
 * can never be persisted — the guard is mandatory, not an optional helper.
 *
 * <p>The defining guard is the provenance rule: a task may be marked {@link GsdTaskStatus#DONE}
 * only if it is backed by at least one piece of evidence whose {@link GsdProvenance} is
 * not {@link GsdProvenance#INFERRED}, and that evidence must reference the task back
 * (bidirectional consistency). Likewise, a {@link GsdPhase#CLOSED} phase requires every
 * task to be {@link GsdTaskStatus#DONE}, so closure on INFERRED-only evidence is impossible.</p>
 */
public final class GsdGuard {

    private GsdGuard() {
    }

    /**
     * Validates the full invariant set, throwing {@link GsdGuardException} with every
     * discovered violation if any invariant fails.
     *
     * @param state the state to validate (may be {@code null})
     * @throws GsdGuardException if any invariant is violated
     */
    public static void validate(GsdState state) {
        if (state == null) {
            throw new GsdGuardException("state must not be null"); //$NON-NLS-1$
        }
        List<String> violations = new ArrayList<>();
        checkSchema(state, violations);
        checkIdUniqueness(state, violations);
        checkNonBlankFields(state, violations);
        checkStringListElements(state, violations);
        checkReferences(state, violations);
        checkWaveTaskConsistency(state, violations);
        checkDependencyGraph(state, violations);
        checkMutationIsolation(state, violations);
        checkProvenanceGuard(state, violations);
        checkAcceptanceAndShipment(state, violations);
        checkTransitionHistory(state, violations);
        if (!violations.isEmpty()) {
            throw new GsdGuardException("GSD state invariants violated", violations); //$NON-NLS-1$
        }
    }

    /**
     * Returns {@code true} if the state passes all invariants without throwing.
     *
     * @param state the state to validate
     * @return {@code true} if valid
     */
    public static boolean isValid(GsdState state) {
        try {
            validate(state);
            return true;
        } catch (GsdGuardException e) {
            return false;
        }
    }

    private static void checkSchema(GsdState state, List<String> violations) {
        if (state.schemaVersion() != GsdState.CURRENT_SCHEMA_VERSION) {
            violations.add("schemaVersion " + state.schemaVersion() //$NON-NLS-1$
                    + " does not match current " + GsdState.CURRENT_SCHEMA_VERSION); //$NON-NLS-1$
        }
        if (state.revision() < GsdState.INITIAL_REVISION) {
            violations.add("revision " + state.revision() + " is negative"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (state.cycleId() == null || state.cycleId().isBlank()) {
            violations.add("cycleId must not be blank"); //$NON-NLS-1$
        }
        Set<String> usedCycleIds = new HashSet<>();
        for (String usedCycleId : state.usedCycleIds()) {
            if (usedCycleId == null || usedCycleId.isBlank()) {
                violations.add("usedCycleIds must contain only non-blank ids"); //$NON-NLS-1$
            } else if (!usedCycleIds.add(usedCycleId)) {
                violations.add("duplicate used cycleId: " + usedCycleId); //$NON-NLS-1$
            }
        }
        if (!usedCycleIds.contains(state.cycleId())) {
            violations.add("usedCycleIds must preserve the current cycleId"); //$NON-NLS-1$
        }
        for (GsdTransition transition : state.transitionHistory()) {
            if (!usedCycleIds.contains(transition.cycleId())) {
                violations.add("usedCycleIds must preserve transition cycleId: " //$NON-NLS-1$
                        + transition.cycleId());
            }
        }
        if (state.generation() < GsdState.INITIAL_GENERATION) {
            violations.add("generation " + state.generation() + " is negative"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (state.phase() == null) {
            violations.add("phase must not be null"); //$NON-NLS-1$
        }
        if (state.sessionPointer() == null) {
            violations.add("sessionPointer must not be null"); //$NON-NLS-1$
        }
    }

    private static void checkIdUniqueness(GsdState state, List<String> violations) {
        checkUniqueIds("decision", state.decisions().stream().map(GsdDecision::id).iterator(), violations); //$NON-NLS-1$
        checkUniqueIds("task", state.tasks().stream().map(GsdTask::id).iterator(), violations); //$NON-NLS-1$
        checkUniqueIds("wave", state.waves().stream().map(GsdWave::id).iterator(), violations); //$NON-NLS-1$
        checkUniqueIds("evidence", state.evidence().stream().map(GsdEvidence::id).iterator(), violations); //$NON-NLS-1$
        checkUniqueIds("acceptance criterion", //$NON-NLS-1$
                state.acceptanceCriteria().stream().map(GsdAcceptanceCriterion::id).iterator(), violations);
    }

    private static void checkUniqueIds(String kind, java.util.Iterator<String> ids, List<String> violations) {
        Set<String> seen = new HashSet<>();
        while (ids.hasNext()) {
            String id = ids.next();
            if (id == null || id.isBlank()) {
                violations.add(kind + " id must be non-blank"); //$NON-NLS-1$
            } else if (!seen.add(id)) {
                violations.add("duplicate " + kind + " id: " + id); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    private static void checkReferences(GsdState state, List<String> violations) {
        Set<String> taskIds = index(state.tasks(), GsdTask::id);
        Set<String> waveIds = index(state.waves(), GsdWave::id);
        Set<String> evidenceIds = index(state.evidence(), GsdEvidence::id);

        for (GsdTask task : state.tasks()) {
            String waveId = task.waveId();
            if (waveId != null && !waveId.isBlank() && !waveIds.contains(waveId)) {
                violations.add("task " + task.id() + " references unknown wave: " + waveId); //$NON-NLS-1$ //$NON-NLS-2$
            }
            for (String dep : task.dependsOn()) {
                if (!taskIds.contains(dep)) {
                    violations.add("task " + task.id() + " depends on unknown task: " + dep); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
            for (String evId : task.evidenceIds()) {
                if (!evidenceIds.contains(evId)) {
                    violations.add("task " + task.id() + " references unknown evidence: " + evId); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
        for (GsdWave wave : state.waves()) {
            for (String tid : wave.taskIds()) {
                if (!taskIds.contains(tid)) {
                    violations.add("wave " + wave.id() + " references unknown task: " + tid); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
        for (GsdEvidence evidence : state.evidence()) {
            for (String tid : evidence.taskIds()) {
                if (!taskIds.contains(tid)) {
                    violations.add("evidence " + evidence.id() + " references unknown task: " + tid); //$NON-NLS-1$ //$NON-NLS-2$
                }
            }
        }
    }

    private static void checkNonBlankFields(GsdState state, List<String> violations) {
        for (GsdDecision d : state.decisions()) {
            if (d.summary().isBlank()) {
                violations.add("decision " + d.id() + " has blank summary"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (d.rationale().isBlank()) {
                violations.add("decision " + d.id() + " has blank rationale"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        for (GsdTask t : state.tasks()) {
            if (t.title().isBlank()) {
                violations.add("task " + t.id() + " has blank title"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        for (GsdWave w : state.waves()) {
            if (w.name().isBlank()) {
                violations.add("wave " + w.id() + " has blank name"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        for (GsdEvidence e : state.evidence()) {
            if (e.description().isBlank()) {
                violations.add("evidence " + e.id() + " has blank description"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        for (GsdAcceptanceCriterion criterion : state.acceptanceCriteria()) {
            if (criterion.description().isBlank()) {
                violations.add("acceptance criterion " + criterion.id() + " has blank description"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    private static void checkStringListElements(GsdState state, List<String> violations) {
        for (GsdDecision d : state.decisions()) {
            checkStringList("decision " + d.id() + " alternatives", d.alternatives(), violations); //$NON-NLS-1$
        }
        for (GsdTask t : state.tasks()) {
            checkStringList("task " + t.id() + " dependsOn", t.dependsOn(), violations); //$NON-NLS-1$
            checkStringList("task " + t.id() + " evidenceIds", t.evidenceIds(), violations); //$NON-NLS-1$
        }
        for (GsdWave w : state.waves()) {
            checkStringList("wave " + w.id() + " taskIds", w.taskIds(), violations); //$NON-NLS-1$
        }
        for (GsdEvidence e : state.evidence()) {
            checkStringList("evidence " + e.id() + " taskIds", e.taskIds(), violations); //$NON-NLS-1$
        }
    }

    private static void checkStringList(String context, List<String> items, List<String> violations) {
        Set<String> seen = new HashSet<>();
        for (String item : items) {
            if (item == null || item.isBlank()) {
                violations.add(context + " contains blank element"); //$NON-NLS-1$
            } else if (!seen.add(item)) {
                violations.add(context + " contains duplicate element: " + item); //$NON-NLS-1$
            }
        }
    }

    private static void checkWaveTaskConsistency(GsdState state, List<String> violations) {
        // Build wave->tasks map
        java.util.Map<String, Set<String>> waveToTasks = new java.util.HashMap<>();
        for (GsdWave w : state.waves()) {
            waveToTasks.put(w.id(), new HashSet<>(w.taskIds()));
        }

        // Every task must belong to exactly one wave
        java.util.Map<String, String> taskToWave = new java.util.HashMap<>();
        for (GsdTask t : state.tasks()) {
            String waveId = t.waveId();
            if (waveId == null || waveId.isBlank()) {
                violations.add("task " + t.id() + " has no wave assignment"); //$NON-NLS-1$ //$NON-NLS-2$
                continue;
            }
            taskToWave.put(t.id(), waveId);

            // Check bidirectional consistency
            Set<String> waveTasks = waveToTasks.get(waveId);
            if (waveTasks != null && !waveTasks.contains(t.id())) {
                violations.add("task " + t.id() + " references wave " + waveId //$NON-NLS-1$
                        + " but wave does not list this task"); //$NON-NLS-1$
            }
        }

        // Check wave->task references match task->wave
        for (GsdWave w : state.waves()) {
            for (String tid : w.taskIds()) {
                String taskWave = taskToWave.get(tid);
                if (taskWave != null && !taskWave.equals(w.id())) {
                    violations.add("wave " + w.id() + " lists task " + tid //$NON-NLS-1$
                            + " but task belongs to wave " + taskWave); //$NON-NLS-1$
                }
            }
        }
    }

    private static void checkDependencyGraph(GsdState state, List<String> violations) {
        java.util.Map<String, GsdTask> tasksById = new java.util.HashMap<>();
        for (GsdTask t : state.tasks()) {
            tasksById.put(t.id(), t);
        }

        // Build wave order index
        java.util.Map<String, Integer> waveOrder = new java.util.HashMap<>();
        for (int i = 0; i < state.waves().size(); i++) {
            waveOrder.put(state.waves().get(i).id(), i);
        }

        // Check for self-dependency and cycle detection
        for (GsdTask t : state.tasks()) {
            if (t.dependsOn().contains(t.id())) {
                violations.add("task " + t.id() + " depends on itself"); //$NON-NLS-1$ //$NON-NLS-2$
            }

            // Check dependencies are in strictly earlier waves
            String taskWaveId = t.waveId();
            Integer taskWaveIdx = waveOrder.get(taskWaveId);
            if (taskWaveIdx != null) {
                for (String depId : t.dependsOn()) {
                    GsdTask dep = tasksById.get(depId);
                    if (dep != null) {
                        String depWaveId = dep.waveId();
                        Integer depWaveIdx = waveOrder.get(depWaveId);
                        if (depWaveIdx != null && depWaveIdx >= taskWaveIdx) {
                            violations.add("task " + t.id() + " in wave " + taskWaveId //$NON-NLS-1$
                                    + " depends on task " + depId + " in wave " + depWaveId //$NON-NLS-1$
                                    + " (must be in strictly earlier wave)"); //$NON-NLS-1$
                        }
                    }
                }
            }
        }

        // Cycle detection via DFS
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();
        for (GsdTask t : state.tasks()) {
            if (hasCycle(t.id(), tasksById, visited, inStack)) {
                violations.add("dependency cycle detected involving task " + t.id()); //$NON-NLS-1$
                break; // Report once
            }
        }
    }

    private static boolean hasCycle(String taskId, java.util.Map<String, GsdTask> tasksById,
                                    Set<String> visited, Set<String> inStack) {
        if (inStack.contains(taskId)) {
            return true;
        }
        if (visited.contains(taskId)) {
            return false;
        }
        visited.add(taskId);
        inStack.add(taskId);
        GsdTask task = tasksById.get(taskId);
        if (task != null) {
            for (String depId : task.dependsOn()) {
                if (hasCycle(depId, tasksById, visited, inStack)) {
                    return true;
                }
            }
        }
        inStack.remove(taskId);
        return false;
    }

    private static void checkMutationIsolation(GsdState state, List<String> violations) {
        for (GsdWave w : state.waves()) {
            List<GsdTask> waveTasks = new ArrayList<>();
            for (GsdTask t : state.tasks()) {
                if (w.id().equals(t.waveId())) {
                    waveTasks.add(t);
                }
            }

            // Check if any task is a mutation
            boolean hasMutation = false;
            for (GsdTask t : waveTasks) {
                if (t.executionKind().isMutation()) {
                    hasMutation = true;
                    break;
                }
            }

            // If mutation exists, wave must contain exactly one task
            if (hasMutation && waveTasks.size() > 1) {
                violations.add("wave " + w.id() + " contains mutation task(s) and multiple tasks; " //$NON-NLS-1$
                        + "mutation tasks must be alone in their wave"); //$NON-NLS-1$
            }
        }
    }

    private static void checkProvenanceGuard(GsdState state, List<String> violations) {
        // Index evidence by id for lookup.
        java.util.Map<String, GsdEvidence> byId = new java.util.HashMap<>();
        for (GsdEvidence evidence : state.evidence()) {
            byId.put(evidence.id(), evidence);
        }
        boolean allDone = !state.tasks().isEmpty();
        for (GsdTask task : state.tasks()) {
            if (task.status() == GsdTaskStatus.DONE) {
                boolean hasClosable = false;
                for (String evId : task.evidenceIds()) {
                    GsdEvidence evidence = byId.get(evId);
                    if (evidence == null) {
                        continue; // already reported as a dangling reference
                    }
                    if (!evidence.taskIds().contains(task.id())) {
                        violations.add("evidence " + evidence.id() //$NON-NLS-1$
                                + " is referenced by task " + task.id() //$NON-NLS-1$
                                + " but does not list it in taskIds"); //$NON-NLS-1$
                        continue;
                    }
                    if (evidence.provenance().isClosable()) {
                        hasClosable = true;
                    }
                }
                if (!hasClosable) {
                    violations.add("task " + task.id() //$NON-NLS-1$
                            + " is DONE but has no closable (non-INFERRED) evidence"); //$NON-NLS-1$
                }
            } else {
                allDone = false;
            }
        }
        if (state.phase() == GsdPhase.CLOSED) {
            if (state.tasks().isEmpty()) {
                violations.add("phase is CLOSED but there are no tasks"); //$NON-NLS-1$
            } else if (!allDone) {
                violations.add("phase is CLOSED but not all tasks are DONE"); //$NON-NLS-1$
            } else {
                // CLOSED additionally requires each task has closable evidence with capturedPhase VERIFYING
                for (GsdTask task : state.tasks()) {
                    boolean hasVerifyEvidence = false;
                    for (String evId : task.evidenceIds()) {
                        GsdEvidence evidence = byId.get(evId);
                        if (evidence != null && evidence.provenance().isClosable()
                                && evidence.capturedPhase() == GsdPhase.VERIFYING) {
                            hasVerifyEvidence = true;
                            break;
                        }
                    }
                    if (!hasVerifyEvidence) {
                        violations.add("task " + task.id() //$NON-NLS-1$
                                + " is DONE in CLOSED phase but lacks closable evidence " //$NON-NLS-1$
                                + "with capturedPhase VERIFYING"); //$NON-NLS-1$
                    }
                }
            }
        }
    }

    private static void checkAcceptanceAndShipment(GsdState state, List<String> violations) {
        GsdShipment shipment = state.shipment();
        if (!shipment.emptyRecord()) {
            if (shipment.id().isBlank()) {
                violations.add("shipment id must not be blank"); //$NON-NLS-1$
            }
            if (shipment.deliveryReference().isBlank()) {
                violations.add("shipment deliveryReference must not be blank"); //$NON-NLS-1$
            }
        }
        if (shipment.status() == GsdShipmentStatus.COMPLETED && shipment.completedAt() == null) {
            violations.add("completed shipment requires completedAt"); //$NON-NLS-1$
        }
        if (shipment.status() != GsdShipmentStatus.COMPLETED && shipment.completedAt() != null) {
            violations.add("non-completed shipment must not have completedAt"); //$NON-NLS-1$
        }
        boolean legacyClosed = state.phase() == GsdPhase.CLOSED
                && shipment.status() == GsdShipmentStatus.LEGACY_MIGRATED;
        if ((state.phase() == GsdPhase.SHIPPING || state.phase() == GsdPhase.CLOSED)
                && !legacyClosed) {
            boolean hasRequired = false;
            for (GsdAcceptanceCriterion criterion : state.acceptanceCriteria()) {
                if (criterion.required()) {
                    hasRequired = true;
                    if (!criterion.passed()) {
                        violations.add("required acceptance criterion " + criterion.id() //$NON-NLS-1$
                                + " has not passed"); //$NON-NLS-1$
                    }
                }
            }
            if (!hasRequired) {
                violations.add("SHIPPING/CLOSED requires at least one required acceptance criterion"); //$NON-NLS-1$
            }
        }
        if (state.phase() == GsdPhase.CLOSED) {
            if (!shipment.satisfiesClosure()) {
                violations.add("phase is CLOSED but shipment is not completed"); //$NON-NLS-1$
            }
        }
        if (shipment.status() == GsdShipmentStatus.LEGACY_MIGRATED
                && (state.phase() != GsdPhase.CLOSED
                        || !GsdState.LEGACY_CYCLE_ID.equals(state.cycleId())
                        || !state.transitionHistory().isEmpty()
                        || !state.usedCycleIds().equals(List.of(GsdState.LEGACY_CYCLE_ID)))) {
            violations.add("LEGACY_MIGRATED shipment is valid only for an unmoved CLOSED v1 migration"); //$NON-NLS-1$
        }
    }

    private static void checkTransitionHistory(GsdState state, List<String> violations) {
        long previousRevision = -1L;
        for (GsdTransition transition : state.transitionHistory()) {
            if (transition.cycleId().isBlank()) {
                violations.add("transition cycleId must not be blank"); //$NON-NLS-1$
            }
            if (transition.generation() < 0L || transition.revision() < 0L) {
                violations.add("transition token values must not be negative"); //$NON-NLS-1$
            }
            if (transition.fromPhase() == null || transition.toPhase() == null) {
                violations.add("transition phases must not be null"); //$NON-NLS-1$
            }
            if (transition.occurredAt() == null) {
                violations.add("transition occurredAt must not be null"); //$NON-NLS-1$
            }
            boolean forward = (transition.fromPhase() == GsdPhase.DISCOVERY
                    && transition.toPhase() == GsdPhase.PLANNING)
                    || (transition.fromPhase() == GsdPhase.PLANNING
                            && transition.toPhase() == GsdPhase.EXECUTING)
                    || (transition.fromPhase() == GsdPhase.EXECUTING
                            && transition.toPhase() == GsdPhase.VERIFYING)
                    || (transition.fromPhase() == GsdPhase.VERIFYING
                            && transition.toPhase() == GsdPhase.SHIPPING)
                    || (transition.fromPhase() == GsdPhase.SHIPPING
                            && transition.toPhase() == GsdPhase.CLOSED);
            boolean verificationRollback = transition.fromPhase() == GsdPhase.VERIFYING
                    && transition.toPhase() == GsdPhase.EXECUTING
                    && !transition.reason().isBlank();
            boolean shippingRollback = transition.fromPhase() == GsdPhase.SHIPPING
                    && (transition.toPhase() == GsdPhase.VERIFYING
                            || transition.toPhase() == GsdPhase.EXECUTING)
                    && !transition.reason().isBlank();
            boolean newCycle = transition.fromPhase() == GsdPhase.CLOSED
                    && transition.toPhase() == GsdPhase.DISCOVERY
                    && !transition.reason().isBlank();
            if (!forward && !verificationRollback && !shippingRollback && !newCycle) {
                violations.add("illegal transition history entry: " + transition.fromPhase() //$NON-NLS-1$
                        + " -> " + transition.toPhase()); //$NON-NLS-1$
            }
            if (transition.generation() > state.generation()) {
                violations.add("transition generation is newer than aggregate generation"); //$NON-NLS-1$
            }
            if (transition.cycleId().equals(state.cycleId())
                    && transition.generation() == state.generation()) {
                if (transition.revision() < previousRevision) {
                    violations.add("transition history revisions are not monotonic"); //$NON-NLS-1$
                }
                previousRevision = transition.revision();
            }
        }
        if (!state.transitionHistory().isEmpty()) {
            GsdTransition latest = state.transitionHistory()
                    .get(state.transitionHistory().size() - 1);
            if (latest.toPhase() != state.phase()) {
                violations.add("latest transition does not end at current phase " + state.phase()); //$NON-NLS-1$
            }
        }
    }

    private static <T> Set<String> index(List<T> items, java.util.function.Function<T, String> key) {
        Set<String> ids = new HashSet<>();
        for (T item : items) {
            ids.add(key.apply(item));
        }
        return ids;
    }
}
