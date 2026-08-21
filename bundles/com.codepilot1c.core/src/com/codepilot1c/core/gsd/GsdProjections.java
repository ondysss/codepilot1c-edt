/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Generates the deterministic Markdown projections {@code STATE.md} and {@code PLAN.md}
 * from a {@link GsdState}. JSON ({@code state.json}) is the source of truth; these
 * projections are always recomputed from it, so they carry no independent state and are
 * safe to delete/regenerate at any time.
 *
 * <p>Output is deterministic: collections are rendered in stable, id-sorted order and
 * formatting never depends on wall-clock time or locale beyond fixed UTC ISO-8601 instants.</p>
 */
public final class GsdProjections {

    /** File name of the state projection. */
    public static final String STATE_FILE = "STATE.md"; //$NON-NLS-1$
    /** File name of the plan projection. */
    public static final String PLAN_FILE = "PLAN.md"; //$NON-NLS-1$

    private GsdProjections() {
    }

    /**
     * Renders the STATE.md projection.
     *
     * @param state the state (must not be {@code null})
     * @return the markdown content (never {@code null})
     */
    public static String toStateMd(GsdState state) {
        GsdState s = Objects.requireNonNull(state, "state"); //$NON-NLS-1$
        StringBuilder sb = new StringBuilder();
        sb.append("# GSD State\n\n"); //$NON-NLS-1$
        sb.append("<!-- AUTO-GENERATED from state.json. Do not edit; re-run to regenerate. -->\n\n"); //$NON-NLS-1$
        sb.append("| Field | Value |\n"); //$NON-NLS-1$
        sb.append("|---|---|\n"); //$NON-NLS-1$
        sb.append("| schemaVersion | ").append(s.schemaVersion()).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("| cycleId | ").append(escape(s.cycleId())).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("| generation | ").append(s.generation()).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("| revision | ").append(s.revision()).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("| phase | ").append(s.phase() == null ? "" : s.phase().name()).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        sb.append("| goal | ").append(escape(s.goal())).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$
        GsdSessionPointer p = s.sessionPointer();
        sb.append("| session | ").append(escape(p.sessionId())).append(" |\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("| workstream | ").append(escape(p.workstreamId())).append(" |\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        sb.append("## Acceptance criteria\n\n"); //$NON-NLS-1$
        List<GsdAcceptanceCriterion> criteria = sortedById(
                s.acceptanceCriteria(), GsdAcceptanceCriterion::id);
        if (criteria.isEmpty()) {
            sb.append("_None._\n\n"); //$NON-NLS-1$
        } else {
            sb.append("| id | required | status | description |\n"); //$NON-NLS-1$
            sb.append("|---|---|---|---|\n"); //$NON-NLS-1$
            for (GsdAcceptanceCriterion criterion : criteria) {
                sb.append("| ").append(escape(criterion.id())).append(" | ") //$NON-NLS-1$ //$NON-NLS-2$
                        .append(criterion.required()).append(" | ") //$NON-NLS-1$
                        .append(criterion.status()).append(" | ") //$NON-NLS-1$
                        .append(escape(criterion.description())).append(" |\n"); //$NON-NLS-1$
            }
            sb.append("\n"); //$NON-NLS-1$
        }

        GsdShipment shipment = s.shipment();
        sb.append("## Shipment\n\n"); //$NON-NLS-1$
        sb.append("- Id: ").append(escape(shipment.id())).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("- Delivery reference: ").append(escape(shipment.deliveryReference())).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("- Status: ").append(shipment.status()).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("- Completed at: ").append(shipment.completedAt() == null
                ? "" : shipment.completedAt()).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        sb.append("## Decisions\n\n"); //$NON-NLS-1$
        List<GsdDecision> decisions = sortedById(s.decisions(), GsdDecision::id);
        if (decisions.isEmpty()) {
            sb.append("_None._\n\n"); //$NON-NLS-1$
        } else {
            for (GsdDecision d : decisions) {
                sb.append("### ").append(d.id()).append(" — ").append(escape(d.summary())).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                sb.append("- Rationale: ").append(escape(d.rationale())).append("\n"); //$NON-NLS-1$
                if (!d.alternatives().isEmpty()) {
                    sb.append("- Alternatives:"); //$NON-NLS-1$
                    for (String alt : d.alternatives()) {
                        sb.append(" ").append(escape(alt)).append(";"); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    sb.append("\n"); //$NON-NLS-1$
                }
                sb.append("\n"); //$NON-NLS-1$
            }
        }

        sb.append("## Evidence\n\n"); //$NON-NLS-1$
        List<GsdEvidence> evidence = sortedById(s.evidence(), GsdEvidence::id);
        if (evidence.isEmpty()) {
            sb.append("_None._\n\n"); //$NON-NLS-1$
        } else {
            sb.append("| id | provenance | capturedPhase | tasks | description |\n"); //$NON-NLS-1$
            sb.append("|---|---|---|---|---|\n"); //$NON-NLS-1$
            for (GsdEvidence e : evidence) {
                sb.append("| ").append(e.id()).append(" | ").append(e.provenance().name()) //$NON-NLS-1$ //$NON-NLS-2$
                        .append(" | ").append(e.capturedPhase().name()).append(" | ") //$NON-NLS-1$ //$NON-NLS-2$
                        .append(String.join(", ", e.taskIds())).append(" | ") //$NON-NLS-1$ //$NON-NLS-2$
                        .append(escape(e.description())).append(" |\n"); //$NON-NLS-1$
            }
            sb.append("\n"); //$NON-NLS-1$
        }

        sb.append("## Transition history\n\n"); //$NON-NLS-1$
        if (s.transitionHistory().isEmpty()) {
            sb.append("_None._\n\n"); //$NON-NLS-1$
        } else {
            sb.append("| cycle | generation | revision | from | to | reason | occurredAt |\n"); //$NON-NLS-1$
            sb.append("|---|---:|---:|---|---|---|---|\n"); //$NON-NLS-1$
            for (GsdTransition transition : s.transitionHistory()) {
                sb.append("| ").append(escape(transition.cycleId())).append(" | ") //$NON-NLS-1$ //$NON-NLS-2$
                        .append(transition.generation()).append(" | ") //$NON-NLS-1$
                        .append(transition.revision()).append(" | ") //$NON-NLS-1$
                        .append(transition.fromPhase()).append(" | ") //$NON-NLS-1$
                        .append(transition.toPhase()).append(" | ") //$NON-NLS-1$
                        .append(escape(transition.reason())).append(" | ") //$NON-NLS-1$
                        .append(transition.occurredAt()).append(" |\n"); //$NON-NLS-1$
            }
            sb.append("\n"); //$NON-NLS-1$
        }
        return sb.toString();
    }

    /**
     * Renders the PLAN.md projection.
     *
     * @param state the state (must not be {@code null})
     * @return the markdown content (never {@code null})
     */
    public static String toPlanMd(GsdState state) {
        GsdState s = Objects.requireNonNull(state, "state"); //$NON-NLS-1$
        StringBuilder sb = new StringBuilder();
        sb.append("# GSD Plan\n\n"); //$NON-NLS-1$
        sb.append("<!-- AUTO-GENERATED from state.json. Do not edit; re-run to regenerate. -->\n\n"); //$NON-NLS-1$
        sb.append("- Goal: ").append(escape(s.goal())).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("- Phase: ").append(s.phase() == null ? "" : s.phase().name()).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        if (!s.acceptanceCriteria().isEmpty()) {
            sb.append("## Acceptance criteria\n\n"); //$NON-NLS-1$
            for (GsdAcceptanceCriterion criterion : sortedById(
                    s.acceptanceCriteria(), GsdAcceptanceCriterion::id)) {
                sb.append("- [").append(criterion.passed() ? "x" : " ").append("] ") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        .append(criterion.id()).append(" — ") //$NON-NLS-1$
                        .append(escape(criterion.description()));
                if (criterion.required()) {
                    sb.append(" (required)"); //$NON-NLS-1$
                }
                sb.append("\n"); //$NON-NLS-1$
            }
            sb.append("\n"); //$NON-NLS-1$
        }

        List<GsdWave> waves = sortedById(s.waves(), GsdWave::id);
        if (waves.isEmpty()) {
            sb.append("## Waves\n\n_None._\n\n"); //$NON-NLS-1$
        } else {
            for (GsdWave w : waves) {
                sb.append("## Wave ").append(w.id()).append(" — ").append(escape(w.name())).append("\n\n"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                if (!w.goal().isBlank()) {
                    sb.append("_Goal: ").append(escape(w.goal())).append("_\n\n"); //$NON-NLS-1$ //$NON-NLS-2$
                }
                List<GsdTask> tasks = tasksForWave(s, w.id());
                if (tasks.isEmpty()) {
                    sb.append("- _no tasks_\n"); //$NON-NLS-1$
                } else {
                    for (GsdTask t : tasks) {
                        sb.append("- [").append(statusMark(t.status())).append("] ") //$NON-NLS-1$ //$NON-NLS-2$
                                .append(t.id()).append(" — ").append(escape(t.title())) //$NON-NLS-1$
                                .append(" (").append(t.status().name()) //$NON-NLS-1$
                                .append(", ").append(t.executionKind().name()).append(")"); //$NON-NLS-1$ //$NON-NLS-2$
                        if (!t.dependsOn().isEmpty()) {
                            sb.append(" depends: ").append(String.join(", ", t.dependsOn())); //$NON-NLS-1$ //$NON-NLS-2$
                        }
                        if (!t.evidenceIds().isEmpty()) {
                            sb.append(" evidence: ").append(String.join(", ", t.evidenceIds())); //$NON-NLS-1$ //$NON-NLS-2$
                        }
                        sb.append("\n"); //$NON-NLS-1$
                    }
                }
                sb.append("\n"); //$NON-NLS-1$
            }
        }

        // Tasks not assigned to any wave.
        List<GsdTask> unassigned = new java.util.ArrayList<>();
        for (GsdTask t : sortedById(s.tasks(), GsdTask::id)) {
            if (t.waveId() == null || t.waveId().isBlank()) {
                unassigned.add(t);
            }
        }
        if (!unassigned.isEmpty()) {
            sb.append("## Unassigned tasks\n\n"); //$NON-NLS-1$
            for (GsdTask t : unassigned) {
                sb.append("- [").append(statusMark(t.status())).append("] ") //$NON-NLS-1$ //$NON-NLS-2$
                        .append(t.id()).append(" — ").append(escape(t.title())) //$NON-NLS-1$
                        .append(" (").append(t.status().name()) //$NON-NLS-1$
                        .append(", ").append(t.executionKind().name()).append(")\n"); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
        return sb.toString();
    }

    private static List<GsdTask> tasksForWave(GsdState s, String waveId) {
        List<GsdTask> result = new java.util.ArrayList<>();
        for (GsdTask t : s.tasks()) {
            if (waveId.equals(t.waveId())) {
                result.add(t);
            }
        }
        result.sort(Comparator.comparing(GsdTask::id));
        return result;
    }

    private static String statusMark(GsdTaskStatus status) {
        if (status == null) {
            return " "; //$NON-NLS-1$
        }
        return switch (status) {
            case DONE -> "x"; //$NON-NLS-1$
            case IN_PROGRESS -> "~"; //$NON-NLS-1$
            case BLOCKED -> "!"; //$NON-NLS-1$
            case PENDING -> " "; //$NON-NLS-1$
        };
    }

    private static <T> List<T> sortedById(List<T> items, java.util.function.Function<T, String> key) {
        List<T> copy = new java.util.ArrayList<>(items);
        copy.sort(Comparator.comparing(key));
        return copy;
    }

    private static String escape(String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        return value.replace("|", "\\|").replace("\n", " "); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }

}
