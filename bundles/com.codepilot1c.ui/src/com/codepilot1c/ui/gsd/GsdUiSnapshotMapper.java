/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.gsd;

import java.util.List;
import java.util.Map;

import com.codepilot1c.core.gsd.GsdPhase;
import com.codepilot1c.core.gsd.GsdSessionPointer;
import com.codepilot1c.core.gsd.GsdState;
import com.codepilot1c.core.gsd.GsdTask;
import com.codepilot1c.core.gsd.GsdTaskStatus;
import com.codepilot1c.core.gsd.GsdWave;

/**
 * Pure mapper from {@link GsdState} to {@link GsdUiSnapshot}.
 *
 * <p>Has no SWT, Eclipse, or I/O dependencies. All methods are deterministic
 * and side-effect-free, making them trivially unit-testable.</p>
 *
 * <p>Also provides the phase→profile mapping used by the profile UX hint.</p>
 */
public final class GsdUiSnapshotMapper {

    /**
     * Maps a GSD lifecycle phase to the corresponding agent profile id.
     *
     * <p>Mapping:</p>
     * <ul>
     *   <li>{@code DISCOVERY} → {@code gsd-discuss}</li>
     *   <li>{@code PLANNING} → {@code gsd-plan}</li>
     *   <li>{@code EXECUTING} → {@code gsd-execute}</li>
     *   <li>{@code VERIFYING} → {@code gsd-verify}</li>
     *   <li>{@code SHIPPING} → {@code gsd-ship}</li>
     *   <li>{@code CLOSED} → {@code gsd-ship}</li>
     * </ul>
     */
    public static final Map<GsdPhase, String> PHASE_TO_PROFILE_ID = Map.of(
            GsdPhase.DISCOVERY, "gsd-discuss", //$NON-NLS-1$
            GsdPhase.PLANNING, "gsd-plan", //$NON-NLS-1$
            GsdPhase.EXECUTING, "gsd-execute", //$NON-NLS-1$
            GsdPhase.VERIFYING, "gsd-verify", //$NON-NLS-1$
            GsdPhase.SHIPPING, "gsd-ship", //$NON-NLS-1$
            GsdPhase.CLOSED, "gsd-ship"); //$NON-NLS-1$

    private GsdUiSnapshotMapper() {
    }

    /**
     * Maps a {@link GsdState} to a display-ready {@link GsdUiSnapshot}.
     *
     * @param state the state to map; must not be {@code null}
     * @return the snapshot, never {@code null}
     */
    public static GsdUiSnapshot toSnapshot(GsdState state) {
        if (state == null) {
            return GsdUiSnapshot.error("State is null"); //$NON-NLS-1$
        }

        String phaseName = state.phase() != null ? state.phase().name() : "DISCOVERY"; //$NON-NLS-1$
        String goal = safeString(state.goal());
        long revision = state.revision();

        // Session pointer
        GsdSessionPointer sp = state.sessionPointer();
        String sessionId = sp != null ? safeString(sp.sessionId()) : ""; //$NON-NLS-1$
        String workstreamId = sp != null ? safeString(sp.workstreamId()) : ""; //$NON-NLS-1$

        // Tasks
        List<GsdTask> tasks = state.tasks();
        int tasksTotal = tasks != null ? tasks.size() : 0;
        int tasksDone = 0;
        if (tasks != null) {
            for (GsdTask task : tasks) {
                if (task.status() == GsdTaskStatus.DONE) {
                    tasksDone++;
                }
            }
        }

        // Current wave: first wave that is not fully done
        String currentWaveName = resolveCurrentWaveName(state.waves(), tasks);

        // Evidence
        int evidenceCount = state.evidence() != null ? state.evidence().size() : 0;

        // Suggested profile
        String suggestedProfile = suggestProfileForPhase(state.phase());

        return new GsdUiSnapshot(
                phaseName,
                goal,
                revision,
                sessionId,
                workstreamId,
                tasksDone,
                tasksTotal,
                currentWaveName,
                evidenceCount,
                suggestedProfile,
                ""); //$NON-NLS-1$
    }

    /**
     * Returns the profile id suggested for the given phase.
     *
     * @param phase the phase; may be {@code null}
     * @return the suggested profile id, or empty string if phase is unknown
     */
    public static String suggestProfileForPhase(GsdPhase phase) {
        if (phase == null) {
            return ""; //$NON-NLS-1$
        }
        return PHASE_TO_PROFILE_ID.getOrDefault(phase, ""); //$NON-NLS-1$
    }

    /**
     * Resolves the name of the "current" wave — the first wave that has at least
     * one task not in DONE status. If all waves are fully done, returns empty.
     */
    private static String resolveCurrentWaveName(List<GsdWave> waves, List<GsdTask> tasks) {
        if (waves == null || waves.isEmpty() || tasks == null || tasks.isEmpty()) {
            return ""; //$NON-NLS-1$
        }

        // Build a quick lookup: taskId -> status
        Map<String, GsdTaskStatus> taskStatusMap = new java.util.HashMap<>();
        for (GsdTask task : tasks) {
            taskStatusMap.put(task.id(), task.status());
        }

        for (GsdWave wave : waves) {
            List<String> taskIds = wave.taskIds();
            if (taskIds == null || taskIds.isEmpty()) {
                continue;
            }
            boolean allDone = true;
            for (String taskId : taskIds) {
                GsdTaskStatus status = taskStatusMap.get(taskId);
                if (status != GsdTaskStatus.DONE) {
                    allDone = false;
                    break;
                }
            }
            if (!allDone) {
                return safeString(wave.name());
            }
        }
        return ""; //$NON-NLS-1$
    }

    private static String safeString(String value) {
        return value != null ? value : ""; //$NON-NLS-1$
    }
}
