/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.codepilot1c.core.gsd.GsdContentSecurity.ContentKind;
import com.codepilot1c.core.gsd.GsdContentSecurity.Finding;
import com.codepilot1c.core.gsd.GsdContentSecurity.Report;
import com.google.gson.JsonObject;

/**
 * Provider-neutral service that performs GSD workflow operations on a {@link GsdState}
 * persisted by a {@link GsdStateStore}. All mutations are optimistic-concurrency aware
 * (expected revision) and validated by {@link GsdGuard} before persistence.
 *
 * <p><strong>Service-level phase guards</strong> — the profile tool allowlist is
 * <em>not</em> a security boundary. Each operation checks the current phase at the
 * service layer:</p>
 * <ul>
 *   <li>{@link #recordDecision} — DISCOVERY only</li>
 *   <li>{@link #createPlan} — PLANNING only</li>
 *   <li>{@link #updateTask} — EXECUTING only</li>
 *   <li>{@link #recordEvidence} — EXECUTING or VERIFYING</li>
 *   <li>{@link #transitionPhase} — any phase (transition rules enforced separately)</li>
 * </ul>
 *
 * <p>The state machine is {@link GsdPhase#DISCOVERY} &rarr; {@link GsdPhase#PLANNING} &rarr;
 * {@link GsdPhase#EXECUTING} &rarr; {@link GsdPhase#VERIFYING} &rarr;
 * {@link GsdPhase#SHIPPING} &rarr; {@link GsdPhase#CLOSED}
 * with a single allowed rollback: {@link GsdPhase#VERIFYING} &rarr; {@link GsdPhase#EXECUTING}
 * (requires a non-blank reason, which is recorded as an audit decision). Phase changes are
 * performed exclusively by {@link #transitionPhase}; {@link #createPlan} never changes the phase.</p>
 */
public final class GsdWorkflowService {

    private GsdWorkflowService() {
    }

    @FunctionalInterface
    private interface IdentityCheck {
        void verify(GsdState state);
    }

    // ---- Revision guard ---------------------------------------------------

    /**
     * Validates that the loaded state's revision matches the caller's expected revision.
     * Throws {@link GsdStaleRevisionException} on mismatch.
     */
    private static void checkRevision(GsdState state, long expectedRevision) {
        if (state.revision() != expectedRevision) {
            throw new GsdStaleRevisionException(expectedRevision, state.revision());
        }
    }

    private static void checkToken(GsdState state, GsdConcurrencyToken expectedToken) {
        Objects.requireNonNull(expectedToken, "expectedToken"); //$NON-NLS-1$
        if (!state.concurrencyToken().equals(expectedToken)) {
            throw new GsdStaleTokenException(expectedToken, state.concurrencyToken());
        }
    }

    // ---- Phase-transition helpers -----------------------------------------

    /**
     * Transitions the phase forward or (once) backward. Validates preconditions.
     *
     * <p>Entry guards:</p>
     * <ul>
     *   <li>EXECUTING: requires non-blank goal, non-empty tasks, non-empty waves</li>
     *   <li>VERIFYING: all tasks must be DONE</li>
     *   <li>CLOSED: all tasks DONE + non-INFERRED evidence (enforced by {@link GsdGuard})</li>
     * </ul>
     *
     * <p>A VERIFYING&rarr;EXECUTING rollback records an audit decision with a deterministic
     * id {@code rollback-r<revision>}, summary "Verification rollback", and the caller's
     * reason as rationale.</p>
     *
     * @param projectRoot       the project root path
     * @param expectedRevision  optimistic-concurrency revision
     * @param targetPhase       the phase to transition to
     * @param reason            required only for VERIFYING&rarr;EXECUTING rollback; may be blank otherwise
     * @return the persisted state after transition
     * @throws IOException               on I/O error
     * @throws GsdGuardException         if the target state violates invariants
     * @throws GsdStaleRevisionException if revision mismatches
     * @throws IllegalArgumentException  if the transition is illegal or entry guard fails
     */
    public static GsdState transitionPhase(String projectRoot, long expectedRevision,
            GsdPhase targetPhase, String reason) throws IOException {
        return transitionPhaseInternal(projectRoot, expectedRevision, null, targetPhase, reason);
    }

    /** Token-aware transition API that prevents ABA across recovery and cycle changes. */
    public static GsdState transitionPhase(String projectRoot, GsdConcurrencyToken expectedToken,
            GsdPhase targetPhase, String reason) throws IOException {
        return transitionPhaseInternal(projectRoot, null, expectedToken, targetPhase, reason);
    }

    private static GsdState transitionPhaseInternal(String projectRoot, Long expectedRevision,
            GsdConcurrencyToken expectedToken, GsdPhase targetPhase, String reason) throws IOException {
        Objects.requireNonNull(targetPhase, "targetPhase"); //$NON-NLS-1$
        // Sanitize rollback reason before any state store access.
        String safeReason = (reason != null && !reason.isEmpty())
                ? secureField(reason, "reason", ContentKind.DECISION) : reason; //$NON-NLS-1$
        GsdStateStore store = new GsdStateStore(projectRoot);
        GsdState current = store.load();
        validateTransition(current.phase(), targetPhase, safeReason);
        if (expectedToken != null) {
            checkToken(current, expectedToken);
        } else {
            checkRevision(current, expectedRevision.longValue());
        }

        // Entry guard for EXECUTING: goal+tasks+waves required.
        if (targetPhase == GsdPhase.EXECUTING) {
            validateExecutingEntry(current);
        }
        // Entry guard for VERIFYING: all tasks must be DONE.
        if (targetPhase == GsdPhase.VERIFYING) {
            validateVerifyingEntry(current);
        }

        GsdState next = current.withPhase(targetPhase);
        if (current.phase() == GsdPhase.VERIFYING && targetPhase == GsdPhase.EXECUTING) {
            // Rollback: record both the compatibility audit decision and the typed
            // transition event. Acceptance results are invalidated for re-verification.
            String auditId = "rollback-r" + current.revision(); //$NON-NLS-1$
            List<GsdDecision> decisions = new ArrayList<>(current.decisions());
            decisions.add(new GsdDecision(auditId, "Verification rollback", safeReason, List.of())); //$NON-NLS-1$
            next = next.withDecisions(decisions);
            List<GsdAcceptanceCriterion> resetCriteria = new ArrayList<>();
            for (GsdAcceptanceCriterion criterion : current.acceptanceCriteria()) {
                resetCriteria.add(criterion.withStatus(GsdAcceptanceStatus.PENDING));
            }
            next = next.withAcceptanceCriteria(resetCriteria).withShipment(GsdShipment.empty());
        }

        List<GsdTransition> history = new ArrayList<>(current.transitionHistory());
        history.add(new GsdTransition(current.cycleId(), current.generation(),
                current.revision() + 1L, current.phase(), targetPhase,
                safeReason, Instant.now()));
        next = next.withTransitionHistory(history);

        return store.save(next);
    }

    /**
     * Validates whether a transition from {@code from} to {@code to} is legal.
     *
     * @param from   current phase
     * @param to     target phase
     * @param reason rollback reason (required for VERIFYING&rarr;EXECUTING)
     * @throws IllegalArgumentException if the transition is illegal
     */
    public static void validateTransition(GsdPhase from, GsdPhase to, String reason) {
        if (from == to) {
            throw new IllegalArgumentException("already in phase " + to); //$NON-NLS-1$
        }
        // Allowed rollback: VERIFYING -> EXECUTING only with a required reason.
        if (from == GsdPhase.VERIFYING && to == GsdPhase.EXECUTING) {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException(
                        "rollback VERIFYING->EXECUTING requires a non-blank reason"); //$NON-NLS-1$
            }
            return;
        }
        boolean forward = (from == GsdPhase.DISCOVERY && to == GsdPhase.PLANNING)
                || (from == GsdPhase.PLANNING && to == GsdPhase.EXECUTING)
                || (from == GsdPhase.EXECUTING && to == GsdPhase.VERIFYING)
                || (from == GsdPhase.VERIFYING && to == GsdPhase.SHIPPING)
                || (from == GsdPhase.SHIPPING && to == GsdPhase.CLOSED);
        if (forward) {
            return;
        }
        throw new IllegalArgumentException(
                "illegal phase transition: " + from + " -> " + to); //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Entry guard: EXECUTING requires non-blank goal, non-empty tasks, non-empty waves.
     */
    private static void validateExecutingEntry(GsdState state) {
        if (state.goal() == null || state.goal().isBlank()) {
            throw new IllegalArgumentException(
                    "cannot enter EXECUTING: goal is required"); //$NON-NLS-1$
        }
        if (state.tasks().isEmpty()) {
            throw new IllegalArgumentException(
                    "cannot enter EXECUTING: tasks are required"); //$NON-NLS-1$
        }
        if (state.waves().isEmpty()) {
            throw new IllegalArgumentException(
                    "cannot enter EXECUTING: waves are required"); //$NON-NLS-1$
        }
    }

    /**
     * Entry guard: VERIFYING requires all tasks DONE.
     */
    private static void validateVerifyingEntry(GsdState state) {
        boolean allDone = !state.tasks().isEmpty();
        for (GsdTask task : state.tasks()) {
            if (task.status() != GsdTaskStatus.DONE) {
                allDone = false;
                break;
            }
        }
        if (!allDone) {
            throw new IllegalArgumentException(
                    "cannot enter VERIFYING: all tasks must be DONE"); //$NON-NLS-1$
        }
    }

    // ---- Phase-gate helpers -----------------------------------------------

    private static void requirePhase(String operation, GsdPhase actual, GsdPhase... allowed) {
        for (GsdPhase p : allowed) {
            if (actual == p) {
                return;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(operation).append(" requires phase "); //$NON-NLS-1$
        for (int i = 0; i < allowed.length; i++) {
            if (i > 0) sb.append(" or "); //$NON-NLS-1$
            sb.append(allowed[i]);
        }
        sb.append(", but current phase is ").append(actual); //$NON-NLS-1$
        throw new IllegalStateException(sb.toString());
    }

    // ---- Content security -------------------------------------------------

    /** Shared content-security instance with default caps and default policy. */
    private static final GsdContentSecurity CONTENT_SECURITY = GsdContentSecurity.create();

    /**
     * Validates and sanitizes a text field through {@link GsdContentSecurity}.
     * Rejects blocked text, CAP-EXCEEDED findings, and any INJECT-* finding
     * without exposing the raw input.
     *
     * @param text      the untrusted input text
     * @param fieldName the logical field name (for error messages)
     * @param kind      the content kind for cap enforcement
     * @return the sanitized text (safe for persistence)
     * @throws GsdContentRejectedException if the text is blocked or contains
     *         injection / cap-exceeded findings
     */
    static String secureField(String text, String fieldName, ContentKind kind) {
        if (text == null || text.isEmpty()) {
            return text != null ? text : ""; //$NON-NLS-1$
        }
        Report report = CONTENT_SECURITY.secure(text, kind);

        // Collect rejection reasons: blocked, CAP-EXCEEDED, or any INJECT-* finding.
        List<String> rejectionReasons = new ArrayList<>();
        for (Finding f : report.findings()) {
            if ("CAP-EXCEEDED".equals(f.ruleId()) //$NON-NLS-1$
                    || f.ruleId().startsWith("INJECT-")) { //$NON-NLS-1$
                rejectionReasons.add(f.ruleId());
            }
        }
        if (report.blocked() && rejectionReasons.isEmpty()) {
            rejectionReasons.add("BLOCKED"); //$NON-NLS-1$
        }
        if (!rejectionReasons.isEmpty()) {
            throw new GsdContentRejectedException(fieldName, rejectionReasons);
        }
        return report.sanitizedText();
    }

    // ---- Get state --------------------------------------------------------

    /**
     * Reads the current GSD state for a project without any filesystem writes.
     * Uses {@link GsdStateStore#loadReadOnly()} so no lock, projection regeneration,
     * or backup recovery occurs.
     *
     * @param projectRoot the project root path
     * @return the current state
     * @throws IOException on I/O error
     */
    public static GsdState getState(String projectRoot) throws IOException {
        return new GsdStateStore(projectRoot).loadReadOnly();
    }

    // ---- Record decision --------------------------------------------------

    /**
     * Records a decision in the current state.
     * <strong>Phase guard:</strong> DISCOVERY only.
     *
     * @param projectRoot       the project root path
     * @param expectedRevision  optimistic-concurrency revision
     * @param id                decision id
     * @param summary           short summary
     * @param rationale         rationale
     * @param alternatives      alternatives considered
     * @return the persisted state
     * @throws IOException               on I/O error
     * @throws GsdGuardException         if invariants violated
     * @throws GsdStaleRevisionException if revision mismatches
     * @throws IllegalStateException     if not in DISCOVERY phase
     */
    public static GsdState recordDecision(String projectRoot, long expectedRevision,
            String id, String summary, String rationale, List<String> alternatives) throws IOException {
        return recordDecisionInternal(projectRoot, state -> checkRevision(state, expectedRevision),
                id, summary, rationale, alternatives);
    }

    /** Token-aware decision API. */
    public static GsdState recordDecision(String projectRoot, GsdConcurrencyToken expectedToken,
            String id, String summary, String rationale, List<String> alternatives) throws IOException {
        return recordDecisionInternal(projectRoot, state -> checkToken(state, expectedToken),
                id, summary, rationale, alternatives);
    }

    private static GsdState recordDecisionInternal(String projectRoot, IdentityCheck identity,
            String id, String summary, String rationale, List<String> alternatives) throws IOException {
        // Validate and sanitize all supplied text before any state store access.
        String safeId = secureField(id, "id", ContentKind.DECISION); //$NON-NLS-1$
        String safeSummary = secureField(summary, "summary", ContentKind.DECISION); //$NON-NLS-1$
        String safeRationale = secureField(rationale, "rationale", ContentKind.DECISION); //$NON-NLS-1$
        List<String> safeAlternatives;
        if (alternatives != null) {
            safeAlternatives = new ArrayList<>(alternatives.size());
            for (int i = 0; i < alternatives.size(); i++) {
                safeAlternatives.add(secureField(alternatives.get(i),
                        "alternatives[" + i + "]", ContentKind.DECISION)); //$NON-NLS-1$
            }
        } else {
            safeAlternatives = List.of();
        }

        GsdStateStore store = new GsdStateStore(projectRoot);
        GsdState current = store.load();
        identity.verify(current);
        requirePhase("recordDecision", current.phase(), GsdPhase.DISCOVERY); //$NON-NLS-1$
        GsdDecision decision = new GsdDecision(safeId, safeSummary, safeRationale, safeAlternatives);
        List<GsdDecision> decisions = new ArrayList<>(current.decisions());
        decisions.add(decision);
        return store.save(current.withDecisions(decisions));
    }

    // ---- Create plan ------------------------------------------------------

    /**
     * Creates a plan: sets the goal, tasks, and waves. Does <em>not</em> change the phase;
     * phase transitions are performed exclusively by {@link #transitionPhase}.
     * <strong>Phase guard:</strong> PLANNING only.
     *
     * @param projectRoot       the project root path
     * @param expectedRevision  optimistic-concurrency revision
     * @param goal              the project goal (non-blank)
     * @param tasks             list of tasks (non-empty)
     * @param waves             list of waves (non-empty)
     * @return the persisted state
     * @throws IOException               on I/O error
     * @throws GsdGuardException         if invariants violated
     * @throws GsdStaleRevisionException if revision mismatches
     * @throws IllegalStateException     if not in PLANNING phase
     * @throws IllegalArgumentException  if goal/tasks/waves are empty
     */
    public static GsdState createPlan(String projectRoot, long expectedRevision,
            String goal, List<GsdTask> tasks, List<GsdWave> waves) throws IOException {
        return createPlan(projectRoot, expectedRevision, goal, List.of(), tasks, waves);
    }

    /** Token-aware compatibility overload for a plan without explicit criteria. */
    public static GsdState createPlan(String projectRoot, GsdConcurrencyToken expectedToken,
            String goal, List<GsdTask> tasks, List<GsdWave> waves) throws IOException {
        return createPlan(projectRoot, expectedToken, goal, List.of(), tasks, waves);
    }

    /** Creates a plan with persisted acceptance criteria. */
    public static GsdState createPlan(String projectRoot, long expectedRevision,
            String goal, List<GsdAcceptanceCriterion> acceptanceCriteria,
            List<GsdTask> tasks, List<GsdWave> waves) throws IOException {
        return createPlanInternal(projectRoot, state -> checkRevision(state, expectedRevision),
                goal, acceptanceCriteria, tasks, waves);
    }

    /** Token-aware plan API with persisted acceptance criteria. */
    public static GsdState createPlan(String projectRoot, GsdConcurrencyToken expectedToken,
            String goal, List<GsdAcceptanceCriterion> acceptanceCriteria,
            List<GsdTask> tasks, List<GsdWave> waves) throws IOException {
        return createPlanInternal(projectRoot, state -> checkToken(state, expectedToken),
                goal, acceptanceCriteria, tasks, waves);
    }

    private static GsdState createPlanInternal(String projectRoot, IdentityCheck identity,
            String goal, List<GsdAcceptanceCriterion> acceptanceCriteria,
            List<GsdTask> tasks, List<GsdWave> waves) throws IOException {
        // Validate and sanitize all supplied text before any state store access.
        String safeGoal = secureField(goal, "goal", ContentKind.GOAL); //$NON-NLS-1$
        Objects.requireNonNull(acceptanceCriteria, "acceptanceCriteria"); //$NON-NLS-1$
        Objects.requireNonNull(tasks, "tasks"); //$NON-NLS-1$
        Objects.requireNonNull(waves, "waves"); //$NON-NLS-1$
        if (safeGoal.isBlank()) {
            throw new IllegalArgumentException("goal must not be blank"); //$NON-NLS-1$
        }
        if (tasks.isEmpty()) {
            throw new IllegalArgumentException("tasks must not be empty"); //$NON-NLS-1$
        }
        if (waves.isEmpty()) {
            throw new IllegalArgumentException("waves must not be empty"); //$NON-NLS-1$
        }
        // Enforce new-plan contract: all tasks must be PENDING with empty evidenceIds.
        for (int i = 0; i < tasks.size(); i++) {
            GsdTask t = tasks.get(i);
            if (t.status() != GsdTaskStatus.PENDING) {
                throw new IllegalArgumentException(
                        "tasks[" + i + "].status must be PENDING for a new plan"); //$NON-NLS-1$
            }
            if (!t.evidenceIds().isEmpty()) {
                throw new IllegalArgumentException(
                        "tasks[" + i + "].evidence_ids must be empty for a new plan"); //$NON-NLS-1$
            }
        }
        // Rebuild tasks and waves with every String field sanitized.
        List<GsdTask> safeTasks = new ArrayList<>(tasks.size());
        for (int i = 0; i < tasks.size(); i++) {
            GsdTask t = tasks.get(i);
            String prefix = "tasks[" + i + "]."; //$NON-NLS-1$
            List<String> safeDeps = new ArrayList<>(t.dependsOn().size());
            for (int j = 0; j < t.dependsOn().size(); j++) {
                safeDeps.add(secureField(t.dependsOn().get(j),
                        prefix + "depends_on[" + j + "]", ContentKind.DECISION)); //$NON-NLS-1$
            }
            List<String> safeEvIds = new ArrayList<>(t.evidenceIds().size());
            for (int j = 0; j < t.evidenceIds().size(); j++) {
                safeEvIds.add(secureField(t.evidenceIds().get(j),
                        prefix + "evidence_ids[" + j + "]", ContentKind.DECISION)); //$NON-NLS-1$
            }
            safeTasks.add(new GsdTask(
                    secureField(t.id(), prefix + "id", ContentKind.DECISION), //$NON-NLS-1$
                    secureField(t.title(), prefix + "title", ContentKind.GOAL), //$NON-NLS-1$
                    t.status(),
                    secureField(t.waveId(), prefix + "wave_id", ContentKind.DECISION), //$NON-NLS-1$
                    safeDeps,
                    safeEvIds,
                    t.executionKind()));
        }
        List<GsdWave> safeWaves = new ArrayList<>(waves.size());
        for (int i = 0; i < waves.size(); i++) {
            GsdWave w = waves.get(i);
            String prefix = "waves[" + i + "]."; //$NON-NLS-1$
            List<String> safeTids = new ArrayList<>(w.taskIds().size());
            for (int j = 0; j < w.taskIds().size(); j++) {
                safeTids.add(secureField(w.taskIds().get(j),
                        prefix + "task_ids[" + j + "]", ContentKind.DECISION)); //$NON-NLS-1$
            }
            safeWaves.add(new GsdWave(
                    secureField(w.id(), prefix + "id", ContentKind.DECISION), //$NON-NLS-1$
                    secureField(w.name(), prefix + "name", ContentKind.GOAL), //$NON-NLS-1$
                    secureField(w.goal(), prefix + "goal", ContentKind.GOAL), //$NON-NLS-1$
                    safeTids));
        }
        List<GsdAcceptanceCriterion> safeCriteria = new ArrayList<>(acceptanceCriteria.size());
        for (int i = 0; i < acceptanceCriteria.size(); i++) {
            GsdAcceptanceCriterion criterion = acceptanceCriteria.get(i);
            if (criterion.status() != GsdAcceptanceStatus.PENDING) {
                throw new IllegalArgumentException("acceptanceCriteria[" + i //$NON-NLS-1$
                        + "].status must be PENDING for a new plan"); //$NON-NLS-1$
            }
            String prefix = "acceptanceCriteria[" + i + "]."; //$NON-NLS-1$ //$NON-NLS-2$
            safeCriteria.add(new GsdAcceptanceCriterion(
                    secureField(criterion.id(), prefix + "id", ContentKind.DECISION), //$NON-NLS-1$
                    secureField(criterion.description(), prefix + "description", ContentKind.GOAL), //$NON-NLS-1$
                    criterion.required(), criterion.status()));
        }

        GsdStateStore store = new GsdStateStore(projectRoot);
        GsdState current = store.load();
        identity.verify(current);
        requirePhase("createPlan", current.phase(), GsdPhase.PLANNING); //$NON-NLS-1$

        return store.save(current.withPlan(safeGoal, safeCriteria, safeTasks, safeWaves));
    }

    // ---- Update task ------------------------------------------------------

    /**
     * Updates a single task's status only. Title, wave, dependencies, and evidence
     * ids are preserved; plan changes go through {@link #createPlan}, evidence links
     * through {@link #recordEvidence}.
     * <strong>Phase guard:</strong> EXECUTING only.
     *
     * <p>If the new status is {@link GsdTaskStatus#IN_PROGRESS} or
     * {@link GsdTaskStatus#DONE}, all tasks listed in {@code dependsOn} must
     * already be {@link GsdTaskStatus#DONE}.</p>
     *
     * @param projectRoot       the project root path
     * @param expectedRevision  optimistic-concurrency revision
     * @param taskId            the task id to update
     * @param newStatus         the new status (must not be null)
     * @return the persisted state
     * @throws IOException               on I/O error
     * @throws GsdGuardException         if invariants violated
     * @throws GsdStaleRevisionException if revision mismatches
     * @throws IllegalArgumentException  if the task is not found or dependency guard fails
     */
    public static GsdState updateTask(String projectRoot, long expectedRevision,
            String taskId, GsdTaskStatus newStatus) throws IOException {
        return updateTaskInternal(projectRoot, state -> checkRevision(state, expectedRevision),
                taskId, newStatus);
    }

    /** Token-aware task update API. */
    public static GsdState updateTask(String projectRoot, GsdConcurrencyToken expectedToken,
            String taskId, GsdTaskStatus newStatus) throws IOException {
        return updateTaskInternal(projectRoot, state -> checkToken(state, expectedToken),
                taskId, newStatus);
    }

    private static GsdState updateTaskInternal(String projectRoot, IdentityCheck identity,
            String taskId, GsdTaskStatus newStatus) throws IOException {
        GsdStateStore store = new GsdStateStore(projectRoot);
        GsdState current = store.load();
        identity.verify(current);
        requirePhase("updateTask", current.phase(), GsdPhase.EXECUTING); //$NON-NLS-1$

        List<GsdTask> tasks = new ArrayList<>(current.tasks());
        int idx = -1;
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).id().equals(taskId)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            throw new IllegalArgumentException("task not found: " + taskId); //$NON-NLS-1$
        }

        GsdTask existing = tasks.get(idx);

        // Dependency guard: IN_PROGRESS / DONE requires all dependsOn tasks DONE.
        if (newStatus == GsdTaskStatus.IN_PROGRESS
                || newStatus == GsdTaskStatus.DONE) {
            for (String depId : existing.dependsOn()) {
                GsdTask dep = findTaskById(tasks, depId);
                if (dep == null) {
                    throw new IllegalArgumentException("task " + taskId //$NON-NLS-1$
                            + " depends on unknown task: " + depId); //$NON-NLS-1$
                }
                if (dep.status() != GsdTaskStatus.DONE) {
                    throw new IllegalArgumentException("task " + taskId //$NON-NLS-1$
                            + " cannot be " + newStatus
                            + ": dependency " + depId + " is " + dep.status()); //$NON-NLS-1$
                }
            }
        }

        GsdTask updated = new GsdTask(
                existing.id(),
                existing.title(),
                newStatus,
                existing.waveId(),
                existing.dependsOn(),
                existing.evidenceIds(),
                existing.executionKind());
        tasks.set(idx, updated);

        return store.save(current.withTasks(tasks));
    }

    private static GsdTask findTaskById(List<GsdTask> tasks, String id) {
        for (GsdTask t : tasks) {
            if (t.id().equals(id)) {
                return t;
            }
        }
        return null;
    }

    // ---- Record evidence --------------------------------------------------

    /**
     * Records a piece of evidence and links it to tasks.
     * <strong>Phase guard:</strong> EXECUTING or VERIFYING.
     *
     * @param projectRoot       the project root path
     * @param expectedRevision  optimistic-concurrency revision
     * @param id                evidence id
     * @param description       what the evidence shows
     * @param provenance        how the evidence was obtained
     * @param taskIds           tasks this evidence supports
     * @return the persisted state
     * @throws IOException               on I/O error
     * @throws GsdGuardException         if invariants violated
     * @throws GsdStaleRevisionException if revision mismatches
     * @throws IllegalStateException     if not in EXECUTING or VERIFYING phase
     */
    public static GsdState recordEvidence(String projectRoot, long expectedRevision,
            String id, String description, GsdProvenance provenance, List<String> taskIds) throws IOException {
        return recordEvidenceInternal(projectRoot, state -> checkRevision(state, expectedRevision),
                id, description, provenance, taskIds);
    }

    /** Token-aware evidence API. */
    public static GsdState recordEvidence(String projectRoot, GsdConcurrencyToken expectedToken,
            String id, String description, GsdProvenance provenance,
            List<String> taskIds) throws IOException {
        return recordEvidenceInternal(projectRoot, state -> checkToken(state, expectedToken),
                id, description, provenance, taskIds);
    }

    private static GsdState recordEvidenceInternal(String projectRoot, IdentityCheck identity,
            String id, String description, GsdProvenance provenance,
            List<String> taskIds) throws IOException {
        // Validate and sanitize all supplied text before any state store access.
        String safeId = secureField(id, "id", ContentKind.EVIDENCE); //$NON-NLS-1$
        String safeDescription = secureField(description, "description", ContentKind.EVIDENCE); //$NON-NLS-1$
        List<String> safeTaskIds;
        if (taskIds != null) {
            safeTaskIds = new ArrayList<>(taskIds.size());
            for (int i = 0; i < taskIds.size(); i++) {
                safeTaskIds.add(secureField(taskIds.get(i),
                        "task_ids[" + i + "]", ContentKind.DECISION)); //$NON-NLS-1$
            }
        } else {
            safeTaskIds = List.of();
        }

        GsdStateStore store = new GsdStateStore(projectRoot);
        GsdState current = store.load();
        identity.verify(current);
        requirePhase("recordEvidence", current.phase(), GsdPhase.EXECUTING, GsdPhase.VERIFYING); //$NON-NLS-1$

        Instant now = Instant.now();
        // Record capturedPhase = current phase.
        GsdEvidence evidence = new GsdEvidence(safeId, safeDescription, provenance,
                safeTaskIds, now, current.phase());
        List<GsdEvidence> evidenceList = new ArrayList<>(current.evidence());
        evidenceList.add(evidence);

        // Also link evidence to tasks, preserving executionKind.
        List<GsdTask> tasks = new ArrayList<>(current.tasks());
        Set<String> linkedTaskIds = Set.copyOf(safeTaskIds);
        for (int i = 0; i < tasks.size(); i++) {
            GsdTask task = tasks.get(i);
            if (linkedTaskIds.contains(task.id())) {
                List<String> evIds = new ArrayList<>(task.evidenceIds());
                evIds.add(safeId);
                tasks.set(i, new GsdTask(
                        task.id(), task.title(), task.status(), task.waveId(),
                        task.dependsOn(), evIds, task.executionKind()));
            }
        }

        return store.save(current.withTasksAndEvidence(tasks, evidenceList));
    }

    // ---- Acceptance and shipment ----------------------------------------

    /** Records an acceptance result while verifying or shipping. */
    public static GsdState updateAcceptanceCriterion(String projectRoot,
            long expectedRevision, String criterionId, GsdAcceptanceStatus status) throws IOException {
        GsdStateStore store = new GsdStateStore(projectRoot);
        GsdState current = store.load();
        checkRevision(current, expectedRevision);
        return updateAcceptanceCriterion(store, current, criterionId, status);
    }

    /** Token-aware acceptance update. */
    public static GsdState updateAcceptanceCriterion(String projectRoot,
            GsdConcurrencyToken expectedToken, String criterionId,
            GsdAcceptanceStatus status) throws IOException {
        GsdStateStore store = new GsdStateStore(projectRoot);
        GsdState current = store.load();
        checkToken(current, expectedToken);
        return updateAcceptanceCriterion(store, current, criterionId, status);
    }

    private static GsdState updateAcceptanceCriterion(GsdStateStore store, GsdState current,
            String criterionId, GsdAcceptanceStatus status) throws IOException {
        requirePhase("updateAcceptanceCriterion", current.phase(), //$NON-NLS-1$
                GsdPhase.VERIFYING, GsdPhase.SHIPPING);
        Objects.requireNonNull(status, "status"); //$NON-NLS-1$
        String safeId = secureField(criterionId, "criterionId", ContentKind.DECISION); //$NON-NLS-1$
        List<GsdAcceptanceCriterion> criteria = new ArrayList<>(current.acceptanceCriteria());
        boolean found = false;
        for (int i = 0; i < criteria.size(); i++) {
            GsdAcceptanceCriterion criterion = criteria.get(i);
            if (criterion.id().equals(safeId)) {
                criteria.set(i, criterion.withStatus(status));
                found = true;
                break;
            }
        }
        if (!found) {
            throw new IllegalArgumentException("acceptance criterion not found: " + safeId); //$NON-NLS-1$
        }
        return store.save(current.withAcceptanceCriteria(criteria));
    }

    /** Persists a shipment/delivery record in the SHIPPING phase. */
    public static GsdState recordShipment(String projectRoot, long expectedRevision,
            GsdShipment shipment) throws IOException {
        GsdStateStore store = new GsdStateStore(projectRoot);
        GsdState current = store.load();
        checkRevision(current, expectedRevision);
        return recordShipment(store, current, shipment);
    }

    /** Token-aware shipment update. */
    public static GsdState recordShipment(String projectRoot, GsdConcurrencyToken expectedToken,
            GsdShipment shipment) throws IOException {
        GsdStateStore store = new GsdStateStore(projectRoot);
        GsdState current = store.load();
        checkToken(current, expectedToken);
        return recordShipment(store, current, shipment);
    }

    private static GsdState recordShipment(GsdStateStore store, GsdState current,
            GsdShipment shipment) throws IOException {
        requirePhase("recordShipment", current.phase(), GsdPhase.SHIPPING); //$NON-NLS-1$
        Objects.requireNonNull(shipment, "shipment"); //$NON-NLS-1$
        GsdShipment safeShipment = new GsdShipment(
                secureField(shipment.id(), "shipment.id", ContentKind.DECISION), //$NON-NLS-1$
                secureField(shipment.deliveryReference(), "shipment.deliveryReference", //$NON-NLS-1$
                        ContentKind.EVIDENCE),
                shipment.status(), shipment.completedAt());
        return store.save(current.withShipment(safeShipment));
    }

    /** Convenience API for recording a completed shipment at the current time. */
    public static GsdState completeShipment(String projectRoot, long expectedRevision,
            String shipmentId, String deliveryReference) throws IOException {
        return recordShipment(projectRoot, expectedRevision,
                GsdShipment.completed(shipmentId, deliveryReference, Instant.now()));
    }

    /** Token-aware convenience API for completing a shipment. */
    public static GsdState completeShipment(String projectRoot, GsdConcurrencyToken expectedToken,
            String shipmentId, String deliveryReference) throws IOException {
        return recordShipment(projectRoot, expectedToken,
                GsdShipment.completed(shipmentId, deliveryReference, Instant.now()));
    }

    /**
     * Starts a clean DISCOVERY cycle after CLOSED while retaining the transition audit
     * history. The cycle id changes and revision restarts at zero; generation is kept.
     */
    public static GsdState startNewCycle(String projectRoot,
            GsdConcurrencyToken expectedToken, String newCycleId, String reason) throws IOException {
        String safeCycleId = secureField(newCycleId, "cycleId", ContentKind.DECISION); //$NON-NLS-1$
        String safeReason = secureField(reason, "reason", ContentKind.DECISION); //$NON-NLS-1$
        if (safeCycleId.isBlank()) {
            throw new IllegalArgumentException("new cycleId must not be blank"); //$NON-NLS-1$
        }
        if (safeReason.isBlank()) {
            throw new IllegalArgumentException("new cycle reason must not be blank"); //$NON-NLS-1$
        }
        GsdStateStore store = new GsdStateStore(projectRoot);
        GsdState current = store.load();
        checkToken(current, expectedToken);
        requirePhase("startNewCycle", current.phase(), GsdPhase.CLOSED); //$NON-NLS-1$
        GsdTransition transition = new GsdTransition(safeCycleId, current.generation(),
                GsdState.INITIAL_REVISION, GsdPhase.CLOSED, GsdPhase.DISCOVERY,
                safeReason, Instant.now());
        GsdState next = current.startCycle(safeCycleId, transition);
        return store.commitNewCycle(expectedToken, next).state();
    }

    /** Revision-compatible new-cycle API; token-aware callers should use the overload. */
    public static GsdState startNewCycle(String projectRoot, long expectedRevision,
            String newCycleId, String reason) throws IOException {
        GsdState current = new GsdStateStore(projectRoot).load();
        checkRevision(current, expectedRevision);
        return startNewCycle(projectRoot, current.concurrencyToken(), newCycleId, reason);
    }

    // ---- Structured result builder ----------------------------------------

    /**
     * Builds a deterministic structured result for tool responses.
     *
     * @param success    whether the operation succeeded
     * @param operation  the operation name
     * @param revision   the new revision (0 on failure)
     * @param phase      the resulting phase
     * @param errorCode  the error code (null on success)
     * @return a JSON object suitable for ToolResult structured data
     */
    public static JsonObject buildResult(boolean success, String operation,
            long revision, GsdPhase phase, String errorCode) {
        JsonObject obj = new JsonObject();
        obj.addProperty("status", success ? "success" : "error"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        obj.addProperty("operation", operation); //$NON-NLS-1$
        obj.addProperty("revision", revision); //$NON-NLS-1$
        if (phase != null) {
            obj.addProperty("phase", phase.name()); //$NON-NLS-1$
        } else {
            obj.addProperty("phase", ""); //$NON-NLS-1$
        }
        if (errorCode != null) {
            obj.addProperty("error_code", errorCode); //$NON-NLS-1$
        }
        return obj;
    }

    // ---- Error codes ------------------------------------------------------

    /** Error code: revision mismatch (stale state). */
    public static final String ERR_STALE = "stale"; //$NON-NLS-1$
    /** Error code: corrupt state file. */
    public static final String ERR_CORRUPT = "corrupt"; //$NON-NLS-1$
    /** Error code: guard/invariant violation. */
    public static final String ERR_GUARD = "guard"; //$NON-NLS-1$
    /** Error code: I/O failure. */
    public static final String ERR_IO = "io"; //$NON-NLS-1$
    /** Error code: invalid parameters / illegal transition. */
    public static final String ERR_INVALID = "invalid"; //$NON-NLS-1$
    /** Error code: content-security rejection (injection, cap exceeded, blocked). */
    public static final String ERR_SECURITY = "security"; //$NON-NLS-1$
}
