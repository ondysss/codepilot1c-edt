/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.gsd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.Test;

import com.codepilot1c.core.gsd.GsdEvidence;
import com.codepilot1c.core.gsd.GsdPhase;
import com.codepilot1c.core.gsd.GsdProvenance;
import com.codepilot1c.core.gsd.GsdSessionPointer;
import com.codepilot1c.core.gsd.GsdState;
import com.codepilot1c.core.gsd.GsdTask;
import com.codepilot1c.core.gsd.GsdTaskStatus;
import com.codepilot1c.core.gsd.GsdWave;

/**
 * Unit tests for {@link GsdUiSnapshotMapper} and {@link GsdUiSnapshot}.
 * Pure logic — no SWT, no Eclipse runtime required.
 */
public class GsdUiSnapshotMapperTest {

    // ---- Phase → Profile mapping ------------------------------------------

    @Test
    public void suggestProfileForPhase_discovery() {
        assertEquals("gsd-discuss", GsdUiSnapshotMapper.suggestProfileForPhase(GsdPhase.DISCOVERY));
    }

    @Test
    public void suggestProfileForPhase_planning() {
        assertEquals("gsd-plan", GsdUiSnapshotMapper.suggestProfileForPhase(GsdPhase.PLANNING));
    }

    @Test
    public void suggestProfileForPhase_executing() {
        assertEquals("gsd-execute", GsdUiSnapshotMapper.suggestProfileForPhase(GsdPhase.EXECUTING));
    }

    @Test
    public void suggestProfileForPhase_verifying() {
        assertEquals("gsd-verify", GsdUiSnapshotMapper.suggestProfileForPhase(GsdPhase.VERIFYING));
    }

    @Test
    public void suggestProfileForPhase_shipping() {
        assertEquals("gsd-ship", GsdUiSnapshotMapper.suggestProfileForPhase(GsdPhase.SHIPPING));
    }

    @Test
    public void suggestProfileForPhase_closed() {
        assertEquals("gsd-ship", GsdUiSnapshotMapper.suggestProfileForPhase(GsdPhase.CLOSED));
    }

    @Test
    public void suggestProfileForPhase_null() {
        assertEquals("", GsdUiSnapshotMapper.suggestProfileForPhase(null));
    }

    // ---- toSnapshot -------------------------------------------------------

    @Test
    public void toSnapshot_freshState() {
        GsdState fresh = GsdState.fresh();
        GsdUiSnapshot snapshot = GsdUiSnapshotMapper.toSnapshot(fresh);

        assertNotNull(snapshot);
        assertEquals("DISCOVERY", snapshot.phase());
        assertEquals("", snapshot.goal());
        assertEquals(0L, snapshot.revision());
        assertEquals("", snapshot.activeSession());
        assertEquals("", snapshot.activeWorkstream());
        assertEquals(0, snapshot.tasksDone());
        assertEquals(0, snapshot.tasksTotal());
        assertEquals("", snapshot.currentWaveName());
        assertEquals(0, snapshot.evidenceCount());
        assertEquals("gsd-discuss", snapshot.suggestedProfileId());
        assertTrue(snapshot.loadError().isEmpty());
        assertTrue(snapshot.isLoaded());
    }

    @Test
    public void toSnapshot_nullState() {
        GsdUiSnapshot snapshot = GsdUiSnapshotMapper.toSnapshot(null);
        assertNotNull(snapshot);
        assertFalse(snapshot.isLoaded());
        assertFalse(snapshot.loadError().isEmpty());
    }

    @Test
    public void toSnapshot_withTasksAndProgress() {
        List<GsdTask> tasks = List.of(
                new GsdTask("t1", "Task 1", GsdTaskStatus.DONE, "w1", List.of(), List.of()),
                new GsdTask("t2", "Task 2", GsdTaskStatus.IN_PROGRESS, "w1", List.of(), List.of()),
                new GsdTask("t3", "Task 3", GsdTaskStatus.PENDING, "w2", List.of(), List.of()),
                new GsdTask("t4", "Task 4", GsdTaskStatus.DONE, "w1", List.of(), List.of()));

        GsdState state = new GsdState(
                GsdState.CURRENT_SCHEMA_VERSION,
                5L,
                GsdPhase.EXECUTING,
                "Build the thing",
                List.of(),
                tasks,
                List.of(),
                List.of(),
                GsdSessionPointer.of("sess-123", "ws-456"));

        GsdUiSnapshot snapshot = GsdUiSnapshotMapper.toSnapshot(state);

        assertEquals("EXECUTING", snapshot.phase());
        assertEquals("Build the thing", snapshot.goal());
        assertEquals(5L, snapshot.revision());
        assertEquals("sess-123", snapshot.activeSession());
        assertEquals("ws-456", snapshot.activeWorkstream());
        assertEquals(2, snapshot.tasksDone());
        assertEquals(4, snapshot.tasksTotal());
        assertEquals("2/4", snapshot.tasksProgress());
        assertEquals("gsd-execute", snapshot.suggestedProfileId());
    }

    @Test
    public void toSnapshot_resolvesCurrentWave() {
        List<GsdTask> tasks = List.of(
                new GsdTask("t1", "Done task", GsdTaskStatus.DONE, "w1", List.of(), List.of()),
                new GsdTask("t2", "Pending task", GsdTaskStatus.PENDING, "w2", List.of(), List.of()));

        List<GsdWave> waves = List.of(
                new GsdWave("w1", "Wave 1", "first wave", List.of("t1")),
                new GsdWave("w2", "Wave 2", "second wave", List.of("t2")));

        GsdState state = new GsdState(
                GsdState.CURRENT_SCHEMA_VERSION,
                1L,
                GsdPhase.EXECUTING,
                "goal",
                List.of(),
                tasks,
                waves,
                List.of(),
                GsdSessionPointer.empty());

        GsdUiSnapshot snapshot = GsdUiSnapshotMapper.toSnapshot(state);

        assertEquals("Wave 2", snapshot.currentWaveName());
    }

    @Test
    public void toSnapshot_allWavesDone_returnsEmpty() {
        List<GsdTask> tasks = List.of(
                new GsdTask("t1", "Done task", GsdTaskStatus.DONE, "w1", List.of(), List.of()));

        List<GsdWave> waves = List.of(
                new GsdWave("w1", "Wave 1", "first wave", List.of("t1")));

        GsdState state = new GsdState(
                GsdState.CURRENT_SCHEMA_VERSION,
                1L,
                GsdPhase.VERIFYING,
                "goal",
                List.of(),
                tasks,
                waves,
                List.of(),
                GsdSessionPointer.empty());

        GsdUiSnapshot snapshot = GsdUiSnapshotMapper.toSnapshot(state);

        assertEquals("", snapshot.currentWaveName());
        assertEquals("gsd-verify", snapshot.suggestedProfileId());
    }

    @Test
    public void toSnapshot_withEvidence() {
        List<GsdEvidence> evidence = List.of(
                new GsdEvidence("e1", "test passed", GsdProvenance.OBSERVED, List.of("t1"), Instant.now()),
                new GsdEvidence("e2", "log verified", GsdProvenance.OBSERVED, List.of("t2"), Instant.now()));

        GsdState state = new GsdState(
                GsdState.CURRENT_SCHEMA_VERSION,
                3L,
                GsdPhase.CLOSED,
                "Ship it",
                List.of(),
                List.of(),
                List.of(),
                evidence,
                GsdSessionPointer.empty());

        GsdUiSnapshot snapshot = GsdUiSnapshotMapper.toSnapshot(state);

        assertEquals(2, snapshot.evidenceCount());
        assertEquals("gsd-ship", snapshot.suggestedProfileId());
    }

    // ---- GsdUiSnapshot record tests ----------------------------------------

    @Test
    public void snapshot_empty() {
        GsdUiSnapshot snapshot = GsdUiSnapshot.empty();
        assertNotNull(snapshot);
        assertEquals("—", snapshot.phase());
        assertEquals("—", snapshot.tasksProgress());
        assertFalse(snapshot.hasTasks());
        assertTrue(snapshot.loadError().isEmpty());
    }

    @Test
    public void snapshot_error() {
        GsdUiSnapshot snapshot = GsdUiSnapshot.error("File not found");
        assertNotNull(snapshot);
        assertFalse(snapshot.isLoaded());
        assertEquals("File not found", snapshot.loadError());
    }

    @Test
    public void snapshot_errorNull() {
        GsdUiSnapshot snapshot = GsdUiSnapshot.error(null);
        assertNotNull(snapshot);
        assertFalse(snapshot.isLoaded());
        assertEquals("Unknown error", snapshot.loadError());
    }

    @Test
    public void snapshot_nullFieldsDefaulted() {
        GsdUiSnapshot snapshot = new GsdUiSnapshot(null, null, 0L, null, null, 0, 0, null, 0, null, null);
        assertEquals("DISCOVERY", snapshot.phase());
        assertEquals("", snapshot.goal());
        assertEquals("", snapshot.activeSession());
        assertEquals("", snapshot.activeWorkstream());
        assertEquals("", snapshot.currentWaveName());
        assertEquals("", snapshot.suggestedProfileId());
        assertEquals("", snapshot.loadError());
        assertTrue(snapshot.isLoaded());
    }

    @Test
    public void snapshot_tasksProgress_noTasks() {
        GsdUiSnapshot snapshot = new GsdUiSnapshot("PLANNING", "", 0L, "", "", 0, 0, "", 0, "", "");
        assertEquals("—", snapshot.tasksProgress());
        assertFalse(snapshot.hasTasks());
    }

    @Test
    public void snapshot_tasksProgress_withTasks() {
        GsdUiSnapshot snapshot = new GsdUiSnapshot("EXECUTING", "", 0L, "", "", 3, 10, "", 0, "", "");
        assertEquals("3/10", snapshot.tasksProgress());
        assertTrue(snapshot.hasTasks());
    }

    // ---- PHASE_TO_PROFILE_ID map completeness -------------------------------

    @Test
    public void phaseToProfile_allPhasesMapped() {
        for (GsdPhase phase : GsdPhase.values()) {
            String profileId = GsdUiSnapshotMapper.PHASE_TO_PROFILE_ID.get(phase);
            assertNotNull("Phase " + phase + " should have a mapped profile", profileId);
            assertFalse("Phase " + phase + " profile should be non-empty", profileId.isEmpty());
        }
    }
}
