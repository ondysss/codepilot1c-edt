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
 * with audited, reason-required rollbacks from VERIFYING to EXECUTING and from SHIPPING
 * to VERIFYING or EXECUTING. Phase changes are
 * performed exclusively by {@link #transitionPhase}; {@link #createPlan} never changes the phase.</p>
 */
public final class GsdWorkflowService {

    private static final System.Logger LOGGER =
            System.getLogger(GsdWorkflowService.class.getName());

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
     * <p>Rollbacks record both a typed transition and a compatibility audit decision.
     * SHIPPING rollback clears the failed shipment; rollback to EXECUTING also resets
     * acceptance results to PENDING.</p>
     *
     * @param projectRoot       the project root path
     * @param expectedRevision  optimistic-concurrency revision
     * @param targetPhase       the phase to transition to
     * @param reason            required for every rollback; may be blank for forward transitions
     * @return the persisted state after transition
     * @throws IOException               on I/O error
     * @throws GsdGuardException         if the target state violates invariants
     * @throws GsdStaleRevisionException if revision mismatches
     * @throws IllegalArgumentException  if the transition is illegal or entry guard fails
     */
    public static GsdState transitionPhase(String projectRoot, long expectedRevision,
            GsdPhase targetPhase, String reason) throws IOException {
        return compatibilityState("transitionPhase", //$NON-NLS-1$
                transitionPhaseInternal(projectRoot, expectedRevision, null, targetPhase, reason));
    }

    /** Token-aware transition API that prevents ABA across recovery and cycle changes. */
    public static GsdState transitionPhase(String projectRoot, GsdConcurrencyToken expectedToken,
            GsdPhase targetPhase, String reason) throws IOException {
        return compatibilityState("transitionPhase", //$NON-NLS-1$
                transitionPhaseInternal(projectRoot, null, expectedToken, targetPhase, reason));
    }

    /** Revision-compatible transition that exposes authoritative commit warnings. */
    public static GsdCommitOutcome transitionPhaseWithOutcome(String projectRoot,
            long expectedRevision, GsdPhase targetPhase, String reason) throws IOException {
        return transitionPhaseInternal(projectRoot, expectedRevision, null, targetPhase, reason);
    }

    /** Token-aware transition that exposes authoritative commit warnings. */
    public static GsdCommitOutcome transitionPhaseWithOutcome(String projectRoot,
            GsdConcurrencyToken expectedToken, GsdPhase targetPhase, String reason) throws IOException {
        return transitionPhaseInternal(projectRoot, null, expectedToken, targetPhase, reason);
    }

    private static GsdCommitOutcome transitionPhaseInternal(String projectRoot, Long expectedRevision,
            GsdConcurrencyToken expectedToken, GsdPhase targetPhase, String reason) throws IOException {
        Objects.requireNonNull(targetPhase, "targetPhase"); //$NON-NLS-1$
        // Sanitize rollback reason before any state store access.
        String safeReason = (reason != null && !reason.isEmpty())
                ? secureField(reason, "reason", ContentKind.DECISION) : reason; //$NON-NLS-1$
        GsdStateStore store = new GsdStateStore(projectRoot);
        GsdState current = store.load();
        if (expectedToken != null) {
            checkToken(current, expectedToken);
        } else {
            checkRevision(current, expectedRevision.longValue());
        }
        validateTransition(current.phase(), targetPhase, safeReason);

        // Entry guard for EXECUTING: goal+tasks+waves required.
        if (targetPhase == GsdPhase.EXECUTING) {
            validateExecutingEntry(current);
        }
        // Entry guard for VERIFYING: all tasks must be DONE.
        if (targetPhase == GsdPhase.VERIFYING) {
            validateVerifyingEntry(current);
        }
        if (targetPhase == GsdPhase.SHIPPING) {
            validateShippingEntry(current);
        }

        GsdState next = current.withPhase(targetPhase);
        boolean verificationRollback = current.phase() == GsdPhase.VERIFYING
                && targetPhase == GsdPhase.EXECUTING;
        boolean shippingRollback = current.phase() == GsdPhase.SHIPPING
                && (targetPhase == GsdPhase.VERIFYING || targetPhase == GsdPhase.EXECUTING);
        if (verificationRollback || shippingRollback) {
            // Rollback: record both the compatibility audit decision and the typed
            // transition event. Shipping state is always cleared; execution rollback
            // also invalidates acceptance results for re-verification.
            String auditId = shippingRollback
                    ? "shipping-rollback-r" + current.revision() //$NON-NLS-1$
                    : "rollback-r" + current.revision(); //$NON-NLS-1$
            String summary = shippingRollback ? "Shipping rollback" : "Verification rollback"; //$NON-NLS-1$ //$NON-NLS-2$
            List<GsdDecision> decisions = new ArrayList<>(current.decisions());
            decisions.add(new GsdDecision(auditId, summary, safeReason, List.of()));
            next = next.withDecisions(decisions);
            if (targetPhase == GsdPhase.EXECUTING) {
                List<GsdAcceptanceCriterion> resetCriteria = new ArrayList<>();
                for (GsdAcceptanceCriterion criterion : current.acceptanceCriteria()) {
                    resetCriteria.add(criterion.withStatus(GsdAcceptanceStatus.PENDING));
                }
                next = next.withAcceptanceCriteria(resetCriteria);
            }
            next = next.withShipment(GsdShipment.empty());
        }

        List<GsdTransition> history = new ArrayList<>(current.transitionHistory());
        history.add(new GsdTransition(current.cycleId(), current.generation(),
                current.revision() + 1L, current.phase(), targetPhase,
                safeReason, Instant.now()));
        next = next.withTransitionHistory(history);

        return store.commit(next);
    }

    /**
     * Validates whether a transition from {@code from} to {@code to} is legal.
     *
     * @param from   current phase
     * @param to     target phase
     * @param reason rollback reason (required for every backward transition)
     * @throws IllegalArgumentException if the transition is illegal
     */
    public static void validateTransition(GsdPhase from, GsdPhase to, String reason) {
        if (from == to) {
            throw new IllegalArgumentException("already in phase " + to); //$NON-NLS-1$
        }
        boolean rollback = (from == GsdPhase.VERIFYING && to == GsdPhase.EXECUTING)
                || (from == GsdPhase.SHIPPING
                        && (to == GsdPhase.VERIFYING || to == GsdPhase.EXECUTING));
        if (rollback) {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException(
                        "rollback " + from + "->" + to + " requires a non-blank reason"); //$NON-NLS-1$ //$NON-NLS-2$
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

    /** Entry guard: SHIPPING requires an explicit, successful verification outcome. */
    private static void validateShippingEntry(GsdState state) {
        if (state.acceptanceCriteria().isEmpty()) {
            throw new IllegalArgumentException(
                    "cannot enter SHIPPING: acceptance criteria are required"); //$NON-NLS-1$
        }
        boolean hasRequired = false;
        for (GsdAcceptanceCriterion criterion : state.acceptanceCriteria()) {
            if (criterion.required()) {
                hasRequired = true;
                if (!criterion.passed()) {
                    throw new IllegalArgumentException(
                            "cannot enter SHIPPING: required acceptance criterion " //$NON-NLS-1$
                                    + criterion.id() + " is " + criterion.status()); //$NON-NLS-1$
                }
            }
        }
        if (!hasRequired) {
            throw new IllegalArgumentException(
                    "cannot enter SHIPPING: at least one required acceptance criterion is required"); //$NON-NLS-1$
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
        return compatibilityState("recordDecision", //$NON-NLS-1$
                recordDecisionWithOutcome(projectRoot, expectedRevision,
                        id, summary, rationale, alternatives));
    }

    /** Token-aware decision API. */
    public static GsdState recordDecision(String projectRoot, GsdConcurrencyToken expectedToken,
            String id, String summary, String rationale, List<String> alternatives) throws IOException {
        return compatibilityState("recordDecision", //$NON-NLS-1$
                recordDecisionWithOutcome(projectRoot, expectedToken,
                        id, summary, rationale, alternatives));
    }

    /** Revision-compatible decision API that exposes projection warnings. */
    public static GsdCommitOutcome recordDecisionWithOutcome(String projectRoot,
            long expectedRevision, String id, String summary, String rationale,
            List<String> alternatives) throws IOException {
        return recordDecisionInternal(projectRoot, state -> checkRevision(state, expectedRevision),
                id, summary, rationale, alternatives);
    }

    /** Token-aware decision API that exposes projection warnings. */
    public static GsdCommitOutcome recordDecisionWithOutcome(String projectRoot,
            GsdConcurrencyToken expectedToken, String id, String summary, String rationale,
            List<String> alternatives) throws IOException {
        return recordDecisionInternal(projectRoot, state -> checkToken(state, expectedToken),
                id, summary, rationale, alternatives);
    }

    private static GsdCommitOutcome recordDecisionInternal(String projectRoot, IdentityCheck identity,
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
        return store.commit(current.withDecisions(decisions));
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
        return compatibilityState("createPlan", //$NON-NLS-1$
                createPlanWithOutcome(projectRoot, expectedRevision,
                        goal, List.of(), tasks, waves));
    }

    /** Token-aware compatibility overload for a plan without explicit criteria. */
    public static GsdState createPlan(String projectRoot, GsdConcurrencyToken expectedToken,
            String goal, List<GsdTask> tasks, List<GsdWave> waves) throws IOException {
        return compatibilityState("createPlan", //$NON-NLS-1$
                createPlanWithOutcome(projectRoot, expectedToken,
                        goal, List.of(), tasks, waves));
    }

    /** Creates a plan with persisted acceptance criteria. */
    public static GsdState createPlan(String projectRoot, long expectedRevision,
            String goal, List<GsdAcceptanceCriterion> acceptanceCriteria,
            List<GsdTask> tasks, List<GsdWave> waves) throws IOException {
        return compatibilityState("createPlan", //$NON-NLS-1$
                createPlanWithOutcome(projectRoot, expectedRevision,
                        goal, acceptanceCriteria, tasks, waves));
    }

    /** Revision-compatible plan API that exposes projection warnings. */
    public static GsdCommitOutcome createPlanWithOutcome(String projectRoot,
            long expectedRevision, String goal, List<GsdTask> tasks,
            List<GsdWave> waves) throws IOException {
        return createPlanWithOutcome(projectRoot, expectedRevision, goal, List.of(), tasks, waves);
    }

    /** Revision-compatible plan API with acceptance criteria and projection warnings. */
    public static GsdCommitOutcome createPlanWithOutcome(String projectRoot,
            long expectedRevision, String goal,
            List<GsdAcceptanceCriterion> acceptanceCriteria,
            List<GsdTask> tasks, List<GsdWave> waves) throws IOException {
        return createPlanInternal(projectRoot, state -> checkRevision(state, expectedRevision),
                goal, acceptanceCriteria, tasks, waves);
    }

    /** Token-aware plan API with persisted acceptance criteria. */
    public static GsdState createPlan(String projectRoot, GsdConcurrencyToken expectedToken,
            String goal, List<GsdAcceptanceCriterion> acceptanceCriteria,
            List<GsdTask> tasks, List<GsdWave> waves) throws IOException {
        return compatibilityState("createPlan", //$NON-NLS-1$
                createPlanWithOutcome(projectRoot, expectedToken,
                        goal, acceptanceCriteria, tasks, waves));
    }

    /** Token-aware plan API that exposes projection warnings. */
    public static GsdCommitOutcome createPlanWithOutcome(String projectRoot,
            GsdConcurrencyToken expectedToken, String goal, List<GsdTask> tasks,
            List<GsdWave> waves) throws IOException {
        return createPlanWithOutcome(projectRoot, expectedToken, goal, List.of(), tasks, waves);
    }

    /** Token-aware plan API with acceptance criteria and projection warnings. */
    public static GsdCommitOutcome createPlanWithOutcome(String projectRoot,
            GsdConcurrencyToken expectedToken, String goal,
            List<GsdAcceptanceCriterion> acceptanceCriteria,
            List<GsdTask> tasks, List<GsdWave> waves) throws IOException {
        return createPlanInternal(projectRoot, state -> checkToken(state, expectedToken),
                goal, acceptanceCriteria, tasks, waves);
    }

    private static GsdCommitOutcome createPlanInternal(String projectRoot, IdentityCheck identity,
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

        return store.commit(current.withPlan(safeGoal, safeCriteria, safeTasks, safeWaves));
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
        return compatibilityState("updateTask", //$NON-NLS-1$
                updateTaskWithOutcome(projectRoot, expectedRevision, taskId, newStatus));
    }

    /** Revision-compatible task update API that exposes projection warnings. */
    public static GsdCommitOutcome updateTaskWithOutcome(String projectRoot,
            long expectedRevision, String taskId, GsdTaskStatus newStatus) throws IOException {
        return updateTaskInternal(projectRoot, state -> checkRevision(state, expectedRevision),
                taskId, newStatus);
    }

    /** Token-aware task update API. */
    public static GsdState updateTask(String projectRoot, GsdConcurrencyToken expectedToken,
            String taskId, GsdTaskStatus newStatus) throws IOException {
        return compatibilityState("updateTask", //$NON-NLS-1$
                updateTaskWithOutcome(projectRoot, expectedToken, taskId, newStatus));
    }

    /** Token-aware task update API that exposes projection warnings. */
    public static GsdCommitOutcome updateTaskWithOutcome(String projectRoot,
            GsdConcurrencyToken expectedToken, String taskId,
            GsdTaskStatus newStatus) throws IOException {
        return updateTaskInternal(projectRoot, state -> checkToken(state, expectedToken),
                taskId, newStatus);
    }

    private static GsdCommitOutcome updateTaskInternal(String projectRoot, IdentityCheck identity,
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

        return store.commit(current.withTasks(tasks));
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
        return compatibilityState("recordEvidence", //$NON-NLS-1$
                recordEvidenceWithOutcome(projectRoot, expectedRevision,
                        id, description, provenance, taskIds));
    }

    /** Revision-compatible evidence API that exposes projection warnings. */
    public static GsdCommitOutcome recordEvidenceWithOutcome(String projectRoot,
            long expectedRevision, String id, String description,
            GsdProvenance provenance, List<String> taskIds) throws IOException {
        return recordEvidenceInternal(projectRoot, state -> checkRevision(state, expectedRevision),
                id, description, provenance, taskIds);
    }

    /** Token-aware evidence API. */
    public static GsdState recordEvidence(String projectRoot, GsdConcurrencyToken expectedToken,
            String id, String description, GsdProvenance provenance,
            List<String> taskIds) throws IOException {
        return compatibilityState("recordEvidence", //$NON-NLS-1$
                recordEvidenceWithOutcome(projectRoot, expectedToken,
                        id, description, provenance, taskIds));
    }

    /** Token-aware evidence API that exposes projection warnings. */
    public static GsdCommitOutcome recordEvidenceWithOutcome(String projectRoot,
            GsdConcurrencyToken expectedToken, String id, String description,
            GsdProvenance provenance, List<String> taskIds) throws IOException {
        return recordEvidenceInternal(projectRoot, state -> checkToken(state, expectedToken),
                id, description, provenance, taskIds);
    }

    private static GsdCommitOutcome recordEvidenceInternal(String projectRoot, IdentityCheck identity,
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

        return store.commit(current.withTasksAndEvidence(tasks, evidenceList));
    }

    // ---- Acceptance and shipment ----------------------------------------

    /** Records an acceptance result while verifying or shipping. */
    public static GsdState updateAcceptanceCriterion(String projectRoot,
            long expectedRevision, String criterionId, GsdAcceptanceStatus status) throws IOException {
        return compatibilityState("updateAcceptanceCriterion", //$NON-NLS-1$
                updateAcceptanceCriterionWithOutcome(
                        projectRoot, expectedRevision, criterionId, status));
    }

    /** Revision-compatible acceptance update that exposes projection warnings. */
    public static GsdCommitOutcome updateAcceptanceCriterionWithOutcome(String projectRoot,
            long expectedRevision, String criterionId,
            GsdAcceptanceStatus status) throws IOException {
        GsdStateStore store = new GsdStateStore(projectRoot);
        GsdState current = store.load();
        checkRevision(current, expectedRevision);
        return updateAcceptanceCriterionWithOutcome(store, current, criterionId, status);
    }

    /** Token-aware acceptance update. */
    public static GsdState updateAcceptanceCriterion(String projectRoot,
            GsdConcurrencyToken expectedToken, String criterionId,
            GsdAcceptanceStatus status) throws IOException {
        return compatibilityState("updateAcceptanceCriterion", //$NON-NLS-1$
                updateAcceptanceCriterionWithOutcome(
                        projectRoot, expectedToken, criterionId, status));
    }

    /** Token-aware acceptance update that exposes projection warnings. */
    public static GsdCommitOutcome updateAcceptanceCriterionWithOutcome(String projectRoot,
            GsdConcurrencyToken expectedToken, String criterionId,
            GsdAcceptanceStatus status) throws IOException {
        GsdStateStore store = new GsdStateStore(projectRoot);
        GsdState current = store.load();
        checkToken(current, expectedToken);
        return updateAcceptanceCriterionWithOutcome(store, current, criterionId, status);
    }

    private static GsdCommitOutcome updateAcceptanceCriterionWithOutcome(
            GsdStateStore store, GsdState current, String criterionId,
            GsdAcceptanceStatus status) throws IOException {
        requirePhase("updateAcceptanceCriterion", current.phase(), GsdPhase.VERIFYING); //$NON-NLS-1$
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
        return store.commit(current.withAcceptanceCriteria(criteria));
    }

    /** Persists a shipment/delivery record in the SHIPPING phase. */
    public static GsdState recordShipment(String projectRoot, long expectedRevision,
            GsdShipment shipment) throws IOException {
        return compatibilityState("recordShipment", //$NON-NLS-1$
                recordShipmentWithOutcome(projectRoot, expectedRevision, shipment));
    }

    /** Revision-compatible shipment update that exposes projection warnings. */
    public static GsdCommitOutcome recordShipmentWithOutcome(String projectRoot,
            long expectedRevision, GsdShipment shipment) throws IOException {
        GsdStateStore store = new GsdStateStore(projectRoot);
        GsdState current = store.load();
        checkRevision(current, expectedRevision);
        requirePhase("recordShipment", current.phase(), GsdPhase.SHIPPING); //$NON-NLS-1$
        if (sameShipment(current.shipment(), shipment)) {
            return new GsdCommitOutcome(current, false, List.of());
        }
        return recordShipmentWithOutcome(store, current, shipment);
    }

    /** Token-aware shipment update. */
    public static GsdState recordShipment(String projectRoot, GsdConcurrencyToken expectedToken,
            GsdShipment shipment) throws IOException {
        return compatibilityState("recordShipment", //$NON-NLS-1$
                recordShipmentWithOutcome(projectRoot, expectedToken, shipment));
    }

    /** Token-aware shipment update that exposes projection warnings. */
    public static GsdCommitOutcome recordShipmentWithOutcome(String projectRoot,
            GsdConcurrencyToken expectedToken, GsdShipment shipment) throws IOException {
        GsdStateStore store = new GsdStateStore(projectRoot);
        GsdState current = store.load();
        checkToken(current, expectedToken);
        requirePhase("recordShipment", current.phase(), GsdPhase.SHIPPING); //$NON-NLS-1$
        if (sameShipment(current.shipment(), shipment)) {
            return new GsdCommitOutcome(current, false, List.of());
        }
        return recordShipmentWithOutcome(store, current, shipment);
    }

    private static GsdCommitOutcome recordShipmentWithOutcome(GsdStateStore store, GsdState current,
            GsdShipment shipment) throws IOException {
        requirePhase("recordShipment", current.phase(), GsdPhase.SHIPPING); //$NON-NLS-1$
        Objects.requireNonNull(shipment, "shipment"); //$NON-NLS-1$
        if (shipment.status() == GsdShipmentStatus.LEGACY_MIGRATED) {
            throw new IllegalArgumentException(
                    "LEGACY_MIGRATED shipment is reserved for schema-v1 migration"); //$NON-NLS-1$
        }
        GsdShipment safeShipment = new GsdShipment(
                secureField(shipment.id(), "shipment.id", ContentKind.DECISION), //$NON-NLS-1$
                secureField(shipment.deliveryReference(), "shipment.deliveryReference", //$NON-NLS-1$
                        ContentKind.EVIDENCE),
                shipment.status(), shipment.completedAt());
        if (!current.shipment().emptyRecord()
                && !mayAdvanceShipment(current.shipment(), safeShipment)) {
            throw new GsdShipmentConflictException(
                    "shipment already recorded for cycle " + current.cycleId()); //$NON-NLS-1$
        }
        return store.commit(current.withShipment(safeShipment));
    }

    private static boolean sameShipment(GsdShipment current, GsdShipment requested) {
        return requested != null && current != null && !current.emptyRecord()
                && current.equals(requested);
    }

    /** Allows the one-way lifecycle of the same shipment, never replacement. */
    private static boolean mayAdvanceShipment(GsdShipment current, GsdShipment requested) {
        return current.status() == GsdShipmentStatus.IN_PROGRESS
                && (requested.status() == GsdShipmentStatus.COMPLETED
                        || requested.status() == GsdShipmentStatus.FAILED)
                && current.id().equals(requested.id())
                && current.deliveryReference().equals(requested.deliveryReference());
    }

    /** Convenience API for recording a completed shipment at the current time. */
    public static GsdState completeShipment(String projectRoot, long expectedRevision,
            String shipmentId, String deliveryReference) throws IOException {
        return compatibilityState("completeShipment", //$NON-NLS-1$
                completeShipmentWithOutcome(
                        projectRoot, expectedRevision, shipmentId, deliveryReference));
    }

    /** Revision-compatible shipment completion that exposes projection warnings. */
    public static GsdCommitOutcome completeShipmentWithOutcome(String projectRoot,
            long expectedRevision, String shipmentId, String deliveryReference) throws IOException {
        return recordShipmentWithOutcome(projectRoot, expectedRevision,
                GsdShipment.completed(shipmentId, deliveryReference, Instant.now()));
    }

    /** Token-aware convenience API for completing a shipment. */
    public static GsdState completeShipment(String projectRoot, GsdConcurrencyToken expectedToken,
            String shipmentId, String deliveryReference) throws IOException {
        return compatibilityState("completeShipment", //$NON-NLS-1$
                completeShipmentWithOutcome(
                        projectRoot, expectedToken, shipmentId, deliveryReference));
    }

    /** Token-aware shipment completion that exposes projection warnings. */
    public static GsdCommitOutcome completeShipmentWithOutcome(String projectRoot,
            GsdConcurrencyToken expectedToken, String shipmentId,
            String deliveryReference) throws IOException {
        return recordShipmentWithOutcome(projectRoot, expectedToken,
                GsdShipment.completed(shipmentId, deliveryReference, Instant.now()));
    }

    /**
     * Starts a clean DISCOVERY cycle after CLOSED while retaining the transition audit
     * history. The cycle id changes and revision restarts at zero; generation is kept.
     */
    public static GsdState startNewCycle(String projectRoot,
            GsdConcurrencyToken expectedToken, String newCycleId, String reason) throws IOException {
        return compatibilityState("startNewCycle", //$NON-NLS-1$
                startNewCycleWithOutcome(projectRoot, expectedToken, newCycleId, reason));
    }

    /** Token-aware new-cycle operation that exposes projection warnings. */
    public static GsdCommitOutcome startNewCycleWithOutcome(String projectRoot,
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
        GsdCycleRules.validateRequestedIdentity(current, safeCycleId, current.generation());
        GsdTransition transition = new GsdTransition(safeCycleId, current.generation(),
                GsdState.INITIAL_REVISION, GsdPhase.CLOSED, GsdPhase.DISCOVERY,
                safeReason, Instant.now());
        GsdState next = current.startCycle(safeCycleId, transition);
        return store.commitNewCycle(expectedToken, next);
    }

    /** Revision-compatible new-cycle API; token-aware callers should use the overload. */
    public static GsdState startNewCycle(String projectRoot, long expectedRevision,
            String newCycleId, String reason) throws IOException {
        return compatibilityState("startNewCycle", //$NON-NLS-1$
                startNewCycleWithOutcome(projectRoot, expectedRevision, newCycleId, reason));
    }

    /** Revision-compatible new-cycle operation that exposes projection warnings. */
    public static GsdCommitOutcome startNewCycleWithOutcome(String projectRoot,
            long expectedRevision, String newCycleId, String reason) throws IOException {
        GsdState current = new GsdStateStore(projectRoot).load();
        checkRevision(current, expectedRevision);
        return startNewCycleWithOutcome(
                projectRoot, current.concurrencyToken(), newCycleId, reason);
    }

    private static GsdState compatibilityState(String operation, GsdCommitOutcome outcome) {
        for (String warning : outcome.projectionWarnings()) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "GSD workflow {0} committed {1} with projection warning: {2}", //$NON-NLS-1$
                    operation, outcome.state().concurrencyToken(), warning);
        }
        return outcome.state();
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
    /** Error code: execution identity is missing or does not match the request. */
    public static final String ERR_IDENTITY = "identity"; //$NON-NLS-1$
    /** Error code: a distinct immutable record already exists. */
    public static final String ERR_CONFLICT = "conflict"; //$NON-NLS-1$
}
