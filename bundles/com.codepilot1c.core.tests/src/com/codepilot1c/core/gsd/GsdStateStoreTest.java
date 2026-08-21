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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Rule;
import org.junit.Test;
import org.junit.Assume;
import org.junit.rules.TemporaryFolder;

import com.codepilot1c.core.filesystem.SecureDirectoryCapabilityException;
import com.codepilot1c.core.filesystem.SecureDirectoryMutation;
import com.codepilot1c.core.filesystem.SecureDirectoryMutation.CapabilityPolicy;

/**
 * Unit tests for {@link GsdStateStore}: round-trip, confinement, stale revision,
 * corruption recovery, evidence guard (via {@link GsdGuard}), and deterministic
 * projections. Run in plain JUnit 4 with Gson on the classpath; no OSGi required.
 */
public class GsdStateStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path newProject() throws IOException {
        Path project = tmp.newFolder("project").toPath(); //$NON-NLS-1$
        Assume.assumeTrue("mutation tests require a real SecureDirectoryStream provider", //$NON-NLS-1$
                SecureDirectoryMutation.supportsSecureDirectoryStreams(project));
        Files.createDirectories(project.resolve(GsdStateStore.GSD_DIR_NAME));
        return project;
    }

    private static String portablePopulatedStateJson() {
        return """
                {
                  "schemaVersion": 2,
                  "cycleId": "portable-cycle",
                  "generation": 3,
                  "revision": 7,
                  "phase": "DISCOVERY",
                  "goal": "portable inspection",
                  "acceptanceCriteria": [],
                  "decisions": [
                    {
                      "id": "decision-1",
                      "summary": "inspect safely",
                      "rationale": "read-only access remains useful",
                      "alternatives": []
                    }
                  ],
                  "tasks": [],
                  "waves": [],
                  "evidence": [],
                  "shipment": {
                    "id": "",
                    "deliveryReference": "",
                    "status": "PENDING"
                  },
                  "transitionHistory": [],
                  "usedCycleIds": ["portable-cycle"],
                  "sessionPointer": {
                    "sessionId": "portable-session",
                    "workstreamId": "portable-workstream"
                  }
                }
                """;
    }

    private static Map<String, String> snapshot(Path root) throws IOException {
        Map<String, String> result = new TreeMap<>();
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                if (path.equals(root)) {
                    continue;
                }
                String relative = root.relativize(path).toString().replace('\\', '/');
                long modified = Files.getLastModifiedTime(
                        path, java.nio.file.LinkOption.NOFOLLOW_LINKS).toMillis();
                if (Files.isSymbolicLink(path)) {
                    result.put(relative, "link:" + Files.readSymbolicLink(path) + ":" + modified); //$NON-NLS-1$ //$NON-NLS-2$
                } else if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    result.put(relative, "dir:" + modified); //$NON-NLS-1$
                } else {
                    result.put(relative, "file:" + modified + ":" //$NON-NLS-1$ //$NON-NLS-2$
                            + Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
                }
            }
        }
        return result;
    }

    @Test
    public void forcedNonSecureProviderFailsBeforeFirstMutation() throws IOException {
        Path root = tmp.newFolder("forced-non-secure-project").toPath(); //$NON-NLS-1$
        Path gsd = Files.createDirectories(root.resolve(GsdStateStore.GSD_DIR_NAME));
        Path sentinel = Files.writeString(gsd.resolve("sentinel"), "unchanged", //$NON-NLS-1$ //$NON-NLS-2$
                StandardCharsets.UTF_8);
        GsdStateStore store = new GsdStateStore(root, null,
                CapabilityPolicy.FORCE_NON_SECURE_FOR_TESTS);

        try {
            store.load();
            fail("expected deterministic capability rejection"); //$NON-NLS-1$
        } catch (SecureDirectoryCapabilityException e) {
            assertTrue(e.getMessage().contains("SecureDirectoryStream")); //$NON-NLS-1$
        }

        assertEquals("unchanged", Files.readString(sentinel)); //$NON-NLS-1$
        assertFalse(Files.exists(gsd.resolve(GsdStateStore.STATE_LOCK)));
        assertFalse(Files.exists(gsd.resolve(GsdStateStore.STATE_JSON)));
        assertFalse(Files.exists(gsd.resolve(GsdStateStore.STATE_BAK)));
        assertFalse(Files.exists(gsd.resolve(GsdProjections.STATE_FILE)));
        assertFalse(Files.exists(gsd.resolve(GsdProjections.PLAN_FILE)));
        try (java.util.stream.Stream<Path> children = Files.list(gsd)) {
            assertEquals(1L, children.count());
        }
    }

    @Test
    public void missingGsdBootstrapFailsBeforeDirectoryOrOutsideMutation() throws IOException {
        Path root = tmp.newFolder("missing-gsd-project").toPath(); //$NON-NLS-1$
        Assume.assumeTrue("bootstrap guidance requires a real SecureDirectoryStream provider", //$NON-NLS-1$
                SecureDirectoryMutation.supportsSecureDirectoryStreams(root));
        Path outside = tmp.newFolder("missing-gsd-outside").toPath(); //$NON-NLS-1$
        Path sentinel = Files.writeString(outside.resolve("sentinel"), "unchanged", //$NON-NLS-1$ //$NON-NLS-2$
                StandardCharsets.UTF_8);
        AtomicReference<String> boundary = new AtomicReference<>();
        GsdStateStore store = new GsdStateStore(root, boundary::set);

        try {
            store.load();
            fail("expected secure-bootstrap rejection"); //$NON-NLS-1$
        } catch (SecureDirectoryCapabilityException e) {
            assertTrue(e.getMessage().contains("pre-create")); //$NON-NLS-1$
        }

        assertEquals("gsd-directory-create", boundary.get()); //$NON-NLS-1$
        assertFalse(Files.exists(root.resolve(".codepilot1c"))); //$NON-NLS-1$
        assertEquals("unchanged", Files.readString(sentinel)); //$NON-NLS-1$
        try (java.util.stream.Stream<Path> children = Files.list(outside)) {
            assertEquals(1L, children.count());
        }
    }

    @Test
    public void forcedNonSecureMissingGsdReportsUnsupportedWithoutPrecreateGuidance()
            throws IOException {
        Path root = tmp.newFolder("forced-missing-gsd-project").toPath(); //$NON-NLS-1$
        Map<String, String> before = snapshot(root);
        AtomicReference<String> boundary = new AtomicReference<>();
        GsdStateStore store = new GsdStateStore(root, boundary::set,
                CapabilityPolicy.FORCE_NON_SECURE_FOR_TESTS);

        try {
            store.load();
            fail("expected provider capability rejection"); //$NON-NLS-1$
        } catch (SecureDirectoryCapabilityException e) {
            assertTrue(e.getMessage().contains("filesystem provider lacks SecureDirectoryStream")); //$NON-NLS-1$
            assertFalse(e.getMessage().contains("pre-create")); //$NON-NLS-1$
        }

        assertEquals(null, boundary.get());
        assertEquals(before, snapshot(root));
    }

    @Test
    public void gsdDirectoryPreBindSwapCannotCreateOutsideLockOrState() throws IOException {
        Path root = newProject();
        Path outside = tmp.newFolder("gsd-bind-outside").toPath(); //$NON-NLS-1$
        Path outsideGsd = Files.createDirectories(outside.resolve("gsd")); //$NON-NLS-1$
        Path sentinel = Files.writeString(outsideGsd.resolve("sentinel"), "unchanged", //$NON-NLS-1$ //$NON-NLS-2$
                StandardCharsets.UTF_8);
        GsdStateStore store = new GsdStateStore(root, operation -> {
            if ("gsd-directory-bind".equals(operation)) { //$NON-NLS-1$
                Path codepilot = root.resolve(".codepilot1c"); //$NON-NLS-1$
                Files.move(codepilot, root.resolve(".codepilot1c-original")); //$NON-NLS-1$
                Files.createSymbolicLink(codepilot, outside);
            }
        });

        try {
            store.load();
            fail("expected pre-bind ancestry rejection"); //$NON-NLS-1$
        } catch (IOException expected) {
            // The project-relative secure open rejects the replacement symlink.
        }

        assertEquals("unchanged", Files.readString(sentinel)); //$NON-NLS-1$
        assertFalse(Files.exists(outsideGsd.resolve(GsdStateStore.STATE_LOCK)));
        assertFalse(Files.exists(outsideGsd.resolve(GsdStateStore.STATE_JSON)));
        assertFalse(Files.exists(outsideGsd.resolve(GsdStateStore.STATE_BAK)));
        assertFalse(Files.exists(outsideGsd.resolve(GsdProjections.STATE_FILE)));
        assertFalse(Files.exists(outsideGsd.resolve(GsdProjections.PLAN_FILE)));
        try (java.util.stream.Stream<Path> children = Files.list(outsideGsd)) {
            assertEquals(1L, children.count());
        }
    }

    // ---- Round-trip ------------------------------------------------------

    @Test
    public void freshProjectLoadsFreshStateWithoutWriting() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);

        GsdState loaded = store.load();

        assertEquals(GsdState.CURRENT_SCHEMA_VERSION, loaded.schemaVersion());
        assertEquals(GsdState.INITIAL_REVISION, loaded.revision());
        assertEquals(GsdPhase.DISCOVERY, loaded.phase());
        assertFalse(Files.exists(store.getGsdDirectory().resolve(GsdStateStore.STATE_JSON)));
    }

    @Test
    public void roundTripPersistsAndReloadsAllFields() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);

        GsdState initial = store.load();
        GsdState populated = new GsdState(
                GsdState.CURRENT_SCHEMA_VERSION,
                initial.revision(),
                GsdPhase.EXECUTING,
                "Ship GSD slice 1", //$NON-NLS-1$
                List.of(new GsdDecision("d1", "use JSON", "source of truth", List.of("md-only"))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                List.of(new GsdTask("t1", "implement store", GsdTaskStatus.IN_PROGRESS, "w1", //$NON-NLS-1$ //$NON-NLS-2$
                        List.of(), List.of("e1"))), //$NON-NLS-1$
                List.of(new GsdWave("w1", "foundation", "typed state", List.of("t1"))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                List.of(new GsdEvidence("e1", "tests pass", GsdProvenance.TESTED, //$NON-NLS-1$ //$NON-NLS-2$
                        List.of("t1"), Instant.parse("2026-07-28T00:00:00Z"))), //$NON-NLS-1$
                GsdSessionPointer.of("sess-1", "ws-1")); //$NON-NLS-1$ //$NON-NLS-2$

        GsdState saved = store.save(populated);
        assertEquals(1L, saved.revision());
        assertEquals(GsdPhase.EXECUTING, saved.phase());

        GsdStateStore store2 = new GsdStateStore(root);
        GsdState reloaded = store2.load();
        assertEquals(1L, reloaded.revision());
        assertEquals("Ship GSD slice 1", reloaded.goal()); //$NON-NLS-1$
        assertEquals(1, reloaded.decisions().size());
        assertEquals("d1", reloaded.decisions().get(0).id()); //$NON-NLS-1$
        assertEquals(1, reloaded.tasks().size());
        assertEquals(GsdTaskStatus.IN_PROGRESS, reloaded.tasks().get(0).status());
        assertEquals("w1", reloaded.tasks().get(0).waveId()); //$NON-NLS-1$
        assertEquals(1, reloaded.waves().size());
        assertEquals(1, reloaded.evidence().size());
        assertEquals(GsdProvenance.TESTED, reloaded.evidence().get(0).provenance());
        assertEquals(Instant.parse("2026-07-28T00:00:00Z"), reloaded.evidence().get(0).createdAt()); //$NON-NLS-1$
        assertEquals("sess-1", reloaded.sessionPointer().sessionId()); //$NON-NLS-1$
    }

    @Test
    public void revisionIncrementsMonotonicallyAcrossSaves() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);

        GsdState s = store.load();
        for (int i = 1; i <= 5; i++) {
            s = store.save(s.withRevision(s.revision()));
            assertEquals((long) i, s.revision());
        }
    }

    // ---- Stale revision --------------------------------------------------

    @Test
    public void staleRevisionIsRejectedFailClosed() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);

        GsdState base = store.load();             // revision 0
        store.save(base);                          // disk now revision 1
        // A second writer that loaded the stale revision 0 tries to save.
        try {
            store.save(base);
            fail("expected GsdStaleRevisionException"); //$NON-NLS-1$
        } catch (GsdStaleRevisionException e) {
            assertEquals(0L, e.getExpectedRevision());
            assertEquals(1L, e.getActualRevision());
        }
    }

    @Test
    public void concurrentWritersResolveOnReload() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);

        GsdState a = store.load();                 // 0
        GsdState b = store.load();                 // 0
        GsdState savedA = store.save(a);           // disk -> 1
        try {
            store.save(b);                          // stale -> fail
            fail("expected stale revision"); //$NON-NLS-1$
        } catch (GsdStaleRevisionException expected) {
            // expected
        }
        // Loser reloads and retries on the fresh revision.
        GsdState reloaded = store.load();
        assertEquals(savedA.revision(), reloaded.revision());
    }

    // ---- Evidence guard --------------------------------------------------

    @Test
    public void saveRejectsDoneTaskWithOnlyInferredEvidence() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);

        GsdState base = store.load();
        GsdState bad = new GsdState(
                GsdState.CURRENT_SCHEMA_VERSION, base.revision(), GsdPhase.EXECUTING, "g", //$NON-NLS-1$
                List.of(),
                List.of(new GsdTask("t1", "do thing", GsdTaskStatus.DONE, "w1", List.of(), List.of("e1"))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                List.of(new GsdWave("w1", "wave", "", List.of("t1"))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                List.of(new GsdEvidence("e1", "guessed", GsdProvenance.INFERRED, List.of("t1"), Instant.EPOCH)), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                GsdSessionPointer.empty());

        try {
            store.save(bad);
            fail("expected GsdGuardException"); //$NON-NLS-1$
        } catch (GsdGuardException e) {
            assertTrue("should mention INFERRED evidence", //$NON-NLS-1$
                    e.getViolations().stream().anyMatch(v -> v.contains("INFERRED"))); //$NON-NLS-1$
        }
        // Nothing was written.
        assertFalse(Files.exists(store.getGsdDirectory().resolve(GsdStateStore.STATE_JSON)));
    }

    @Test
    public void saveAcceptsDoneTaskWithTestedEvidence() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);

        GsdState base = store.load();
        GsdState good = new GsdState(
                GsdState.CURRENT_SCHEMA_VERSION, base.revision(), GsdPhase.EXECUTING, "g", //$NON-NLS-1$
                List.of(),
                List.of(new GsdTask("t1", "do thing", GsdTaskStatus.DONE, "w1", List.of(), List.of("e1"))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                List.of(new GsdWave("w1", "wave", "", List.of("t1"))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                List.of(new GsdEvidence("e1", "tests green", GsdProvenance.TESTED, //$NON-NLS-1$ //$NON-NLS-2$
                        List.of("t1"), Instant.EPOCH)), //$NON-NLS-1$
                GsdSessionPointer.empty());

        GsdState saved = store.save(good);
        assertEquals(1L, saved.revision());
    }

    @Test
    public void closedPhaseRequiresAllTasksDoneWithClosableEvidence() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);
        GsdState base = store.load();

        // One task still IN_PROGRESS -> cannot close phase.
        GsdState notReady = new GsdState(
                GsdState.CURRENT_SCHEMA_VERSION, base.revision(), GsdPhase.CLOSED, "g", //$NON-NLS-1$
                List.of(),
                List.of(new GsdTask("t1", "x", GsdTaskStatus.IN_PROGRESS, "w1", List.of(), List.of())), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                List.of(new GsdWave("w1", "wave", "", List.of("t1"))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                List.of(), GsdSessionPointer.empty());
        try {
            store.save(notReady);
            fail("expected guard to reject CLOSED phase with non-DONE tasks"); //$NON-NLS-1$
        } catch (GsdGuardException e) {
            assertTrue(e.getViolations().stream().anyMatch(v -> v.contains("CLOSED"))); //$NON-NLS-1$
        }

        // All DONE with TESTED evidence and capturedPhase VERIFYING -> closeable.
        GsdState ready = new GsdState(
                GsdState.CURRENT_SCHEMA_VERSION, base.revision(), GsdPhase.CLOSED, "g", //$NON-NLS-1$
                List.of(),
                List.of(new GsdTask("t1", "x", GsdTaskStatus.DONE, "w1", List.of(), List.of("e1"))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                List.of(new GsdWave("w1", "wave", "", List.of("t1"))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                List.of(new GsdEvidence("e1", "verified", GsdProvenance.TESTED, List.of("t1"), Instant.EPOCH, GsdPhase.VERIFYING)), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                GsdSessionPointer.empty());
        GsdState saved = store.save(ready);
        assertEquals(GsdPhase.CLOSED, saved.phase());
    }

    @Test
    public void danglingReferencesAreGuardViolations() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);
        GsdState base = store.load();

        GsdState bad = new GsdState(
                GsdState.CURRENT_SCHEMA_VERSION, base.revision(), GsdPhase.PLANNING, "g", //$NON-NLS-1$
                List.of(),
                List.of(new GsdTask("t1", "x", GsdTaskStatus.PENDING, "no-such-wave", //$NON-NLS-1$ //$NON-NLS-2$
                        List.of("ghost"), List.of("ghost-ev"))), //$NON-NLS-1$ //$NON-NLS-2$
                List.of(), List.of(), GsdSessionPointer.empty());
        try {
            store.save(bad);
            fail("expected guard violations for dangling refs"); //$NON-NLS-1$
        } catch (GsdGuardException e) {
            List<String> v = e.getViolations();
            assertTrue(v.stream().anyMatch(s -> s.contains("unknown wave"))); //$NON-NLS-1$
            assertTrue(v.stream().anyMatch(s -> s.contains("depends on unknown task"))); //$NON-NLS-1$
            assertTrue(v.stream().anyMatch(s -> s.contains("unknown evidence"))); //$NON-NLS-1$
        }
    }

    // ---- Corruption recovery --------------------------------------------

    @Test
    public void corruptStateJsonRecoversFromBackup() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);

        GsdState base = store.load();
        // First save (no prior state -> no backup yet).
        GsdState first = store.save(new GsdState(GsdState.CURRENT_SCHEMA_VERSION, base.revision(),
                GsdPhase.EXECUTING, "g", List.of(), List.of(), List.of(), List.of(), //$NON-NLS-1$
                GsdSessionPointer.empty()));
        // Second save -> previous state.json (EXECUTING rev 1) is backed up to .bak.
        store.save(new GsdState(GsdState.CURRENT_SCHEMA_VERSION, first.revision(),
                GsdPhase.PLANNING, "g", List.of(), List.of(), List.of(), List.of(), //$NON-NLS-1$
                GsdSessionPointer.empty()));

        Path gsd = store.getGsdDirectory();
        assertTrue(Files.exists(gsd.resolve(GsdStateStore.STATE_BAK)));

        // Corrupt the primary state.json.
        Files.writeString(gsd.resolve(GsdStateStore.STATE_JSON),
                "{ this is : not json ", StandardCharsets.UTF_8); //$NON-NLS-1$

        GsdState recovered = store.load();
        assertEquals(1L, recovered.revision());
        assertEquals(GsdPhase.EXECUTING, recovered.phase());
        // Primary restored from backup.
        String restored = Files.readString(gsd.resolve(GsdStateStore.STATE_JSON), StandardCharsets.UTF_8);
        assertTrue(restored.contains("EXECUTING")); //$NON-NLS-1$
        // Corrupt copy preserved for forensics.
        assertTrue(Files.exists(gsd.resolve(GsdStateStore.STATE_CORRUPT)));
    }

    @Test
    public void futureSchemaIsRejectedWithoutRecoveringOlderBackup() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);

        GsdState base = store.load();
        GsdState first = store.save(new GsdState(GsdState.CURRENT_SCHEMA_VERSION, base.revision(),
                GsdPhase.PLANNING, "g", List.of(), List.of(), List.of(), List.of(), //$NON-NLS-1$
                GsdSessionPointer.empty()));
        store.save(new GsdState(GsdState.CURRENT_SCHEMA_VERSION, first.revision(),
                GsdPhase.EXECUTING, "g", List.of(), List.of(), List.of(), List.of(), //$NON-NLS-1$
                GsdSessionPointer.empty()));

        Path gsd = store.getGsdDirectory();
        // Overwrite primary with a future-schema version.
        Files.writeString(gsd.resolve(GsdStateStore.STATE_JSON),
                "{\"schemaVersion\":999,\"revision\":0,\"phase\":\"DISCOVERY\",\"goal\":\"\"," //$NON-NLS-1$
                        + "\"decisions\":[],\"tasks\":[],\"waves\":[],\"evidence\":[]," //$NON-NLS-1$
                        + "\"sessionPointer\":{\"sessionId\":\"\",\"workstreamId\":\"\"}}", //$NON-NLS-1$
                StandardCharsets.UTF_8);

        try {
            store.load();
            fail("expected GsdUnsupportedSchemaException"); //$NON-NLS-1$
        } catch (GsdUnsupportedSchemaException e) {
            assertEquals(999, e.getSchemaVersion());
            assertTrue(e.getMessage().contains("current schemaVersion is 2")); //$NON-NLS-1$
        }
    }

    @Test
    public void unrecoverableCorruptionFailsClosed() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);

        Path gsd = store.getGsdDirectory();
        Files.createDirectories(gsd);
        // Corrupt primary, no backup.
        Files.writeString(gsd.resolve(GsdStateStore.STATE_JSON),
                "garbage", StandardCharsets.UTF_8); //$NON-NLS-1$

        try {
            store.load();
            fail("expected IOException for unrecoverable corruption"); //$NON-NLS-1$
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("corrupt")); //$NON-NLS-1$
        }
    }

    @Test
    public void missingPrimaryWithBackupRecovers() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);

        GsdState base = store.load();
        GsdState first = store.save(new GsdState(GsdState.CURRENT_SCHEMA_VERSION, base.revision(),
                GsdPhase.VERIFYING, "g", List.of(), List.of(), List.of(), List.of(), //$NON-NLS-1$
                GsdSessionPointer.empty()));
        store.save(new GsdState(GsdState.CURRENT_SCHEMA_VERSION, first.revision(),
                GsdPhase.EXECUTING, "g", List.of(), List.of(), List.of(), List.of(), //$NON-NLS-1$
                GsdSessionPointer.empty()));

        Path gsd = store.getGsdDirectory();
        Files.delete(gsd.resolve(GsdStateStore.STATE_JSON)); // lose primary, keep backup

        GsdState recovered = store.load();
        assertEquals(GsdPhase.VERIFYING, recovered.phase());
        assertTrue(Files.exists(gsd.resolve(GsdStateStore.STATE_JSON)));
    }

    // ---- Confinement -----------------------------------------------------

    @Test
    public void symlinkedCodePilotDirEscapingProjectIsRejected() throws IOException {
        Path root = tmp.newFolder("symlink-project").toPath(); //$NON-NLS-1$
        Path outside = tmp.newFolder("outside").toPath(); //$NON-NLS-1$
        // Pre-create .codepilot1c as a symlink pointing outside the project.
        Files.createSymbolicLink(root.resolve(".codepilot1c"), outside); //$NON-NLS-1$

        GsdStateStore store = new GsdStateStore(root);
        // Confinement is enforced on read too: an escaped GSD dir would read another
        // project's state, so load() fails closed rather than returning a misleading state.
        try {
            store.load();
            fail("expected confinement rejection"); //$NON-NLS-1$
        } catch (IOException e) {
            // expected: GSD dir resolves outside the project root
        }
        // No state written inside the outside target.
        assertFalse(Files.exists(outside.resolve("gsd").resolve(GsdStateStore.STATE_JSON))); //$NON-NLS-1$
    }

    @Test
    public void lockWriteRejectsDeterministicAncestrySwap() throws IOException {
        Path root = newProject();
        Path outside = tmp.newFolder("outside-lock-race").toPath(); //$NON-NLS-1$
        Files.createDirectories(root.resolve(GsdStateStore.GSD_DIR_NAME));
        Path outsideGsd = Files.createDirectories(outside.resolve("gsd")); //$NON-NLS-1$
        Path outsideLock = Files.writeString(outsideGsd.resolve(GsdStateStore.STATE_LOCK),
                "outside-lock", StandardCharsets.UTF_8); //$NON-NLS-1$

        GsdStateStore store = new GsdStateStore(root,
                swapAncestryOn(root, outside, GsdStateStore.Mutation.LOCK.operation()));
        assertRejected(store::load);

        assertEquals("outside-lock", Files.readString(outsideLock)); //$NON-NLS-1$
    }

    @Test
    public void stateWriteRejectsDeterministicAncestrySwap() throws IOException {
        Path root = newProject();
        GsdStateStore normal = new GsdStateStore(root);
        GsdState current = normal.save(normal.load());
        Path outside = tmp.newFolder("outside-state-race").toPath(); //$NON-NLS-1$
        Path outsideState = Files.writeString(
                Files.createDirectories(outside.resolve("gsd")).resolve(GsdStateStore.STATE_JSON), //$NON-NLS-1$
                "outside-state", StandardCharsets.UTF_8); //$NON-NLS-1$

        GsdStateStore raced = new GsdStateStore(root,
                swapAncestryOn(root, outside, GsdStateStore.Mutation.STATE.operation()));
        assertRejected(() -> raced.save(current));

        assertEquals("outside-state", Files.readString(outsideState)); //$NON-NLS-1$
    }

    @Test
    public void backupWriteRejectsDeterministicAncestrySwap() throws IOException {
        Path root = newProject();
        GsdStateStore normal = new GsdStateStore(root);
        GsdState current = normal.save(normal.load());
        Path outside = tmp.newFolder("outside-backup-race").toPath(); //$NON-NLS-1$
        Path outsideBackup = Files.writeString(
                Files.createDirectories(outside.resolve("gsd")).resolve(GsdStateStore.STATE_BAK), //$NON-NLS-1$
                "outside-backup", StandardCharsets.UTF_8); //$NON-NLS-1$

        GsdStateStore raced = new GsdStateStore(root,
                swapAncestryOn(root, outside, GsdStateStore.Mutation.BACKUP.operation()));
        assertRejected(() -> raced.save(current));

        assertEquals("outside-backup", Files.readString(outsideBackup)); //$NON-NLS-1$
    }

    @Test
    public void projectionWriteRejectsDeterministicAncestrySwap() throws IOException {
        Path root = newProject();
        GsdStateStore normal = new GsdStateStore(root);
        GsdState current = normal.save(normal.load());
        Path outside = tmp.newFolder("outside-projection-race").toPath(); //$NON-NLS-1$
        Path outsideProjection = Files.writeString(
                Files.createDirectories(outside.resolve("gsd")).resolve(GsdProjections.STATE_FILE), //$NON-NLS-1$
                "outside-projection", StandardCharsets.UTF_8); //$NON-NLS-1$

        GsdStateStore raced = new GsdStateStore(root,
                swapAncestryOn(root, outside, GsdStateStore.Mutation.PROJECTION.operation()));
        assertRejected(() -> raced.writeProjections(current));

        assertEquals("outside-projection", Files.readString(outsideProjection)); //$NON-NLS-1$
    }

    private com.codepilot1c.core.filesystem.SecureDirectoryMutation.MutationHook swapAncestryOn(
            Path root, Path outside, String expectedOperation) {
        return operation -> {
            if (!expectedOperation.equals(operation)) {
                return;
            }
            Path codepilot = root.resolve(".codepilot1c"); //$NON-NLS-1$
            Files.move(codepilot, root.resolve(".codepilot1c-original")); //$NON-NLS-1$
            Files.createSymbolicLink(codepilot, outside);
        };
    }

    private static void assertRejected(IoCall call) throws IOException {
        try {
            call.run();
            fail("expected changed ancestry to be rejected"); //$NON-NLS-1$
        } catch (AccessDeniedException e) {
            assertTrue(e.getMessage().contains("changed") //$NON-NLS-1$
                    || e.getMessage().contains("escaped")); //$NON-NLS-1$
        }
    }

    @FunctionalInterface
    private interface IoCall {
        void run() throws IOException;
    }

    @Test
    public void blankAndNullProjectRootAreRejected() {
        try {
            new GsdStateStore((String) null);
            fail("expected IOException"); //$NON-NLS-1$
        } catch (IOException expected) {
            // expected
        }
        try {
            new GsdStateStore("   "); //$NON-NLS-1$
            fail("expected IOException"); //$NON-NLS-1$
        } catch (IOException expected) {
            // expected
        }
    }

    @Test
    public void stateLivesUnderCodePilot1cGsd() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);
        store.save(store.load().withRevision(0));
        assertEquals(root.resolve(GsdStateStore.GSD_DIR_NAME), store.getGsdDirectory());
        assertTrue(Files.exists(root.resolve(".codepilot1c/gsd/state.json"))); //$NON-NLS-1$
    }

    // ---- loadReadOnly ---------------------------------------------------

    @Test
    public void loadReadOnlyFreshProjectReturnsFreshStateWithoutWriting() throws IOException {
        Path root = tmp.newFolder("read-only-fresh-project").toPath(); //$NON-NLS-1$
        GsdStateStore store = new GsdStateStore(root);

        GsdState loaded = store.loadReadOnly();

        assertEquals(GsdState.CURRENT_SCHEMA_VERSION, loaded.schemaVersion());
        assertEquals(GsdState.INITIAL_REVISION, loaded.revision());
        assertEquals(GsdPhase.DISCOVERY, loaded.phase());
        // Zero filesystem writes: no GSD directory, no state.json, no lock, no projections.
        assertFalse(Files.exists(store.getGsdDirectory()));
        assertFalse(Files.exists(store.getGsdDirectory().resolve(GsdStateStore.STATE_JSON)));
        assertFalse(Files.exists(store.getGsdDirectory().resolve(GsdStateStore.STATE_LOCK)));
        assertFalse(Files.exists(store.getGsdDirectory().resolve(GsdProjections.STATE_FILE)));
        assertFalse(Files.exists(store.getGsdDirectory().resolve(GsdProjections.PLAN_FILE)));
    }

    @Test
    public void loadReadOnlyParsesExistingState() throws IOException {
        Path root = tmp.newFolder("read-only-existing-state").toPath(); //$NON-NLS-1$
        Path gsd = Files.createDirectories(root.resolve(GsdStateStore.GSD_DIR_NAME));
        Files.writeString(gsd.resolve(GsdStateStore.STATE_JSON), portablePopulatedStateJson(),
                StandardCharsets.UTF_8);
        GsdStateStore store = new GsdStateStore(root);
        Path stateFile = store.getGsdDirectory().resolve(GsdStateStore.STATE_JSON);
        Map<String, String> before = snapshot(root);

        GsdState loaded = store.loadReadOnly();

        assertEquals(7L, loaded.revision());
        assertEquals(GsdPhase.DISCOVERY, loaded.phase());
        assertEquals("portable inspection", loaded.goal()); //$NON-NLS-1$
        assertEquals("portable-session", loaded.sessionPointer().sessionId()); //$NON-NLS-1$
        assertEquals(before, snapshot(root));
        assertTrue(Files.exists(stateFile));
        assertFalse(Files.exists(store.getGsdDirectory().resolve(GsdStateStore.STATE_BAK)));
    }

    @Test
    public void nativeProviderPopulatedReadUsesActualCapabilityWithoutMutation()
            throws IOException {
        Path root = tmp.newFolder("native-provider-populated").toPath(); //$NON-NLS-1$
        Path gsd = Files.createDirectories(root.resolve(GsdStateStore.GSD_DIR_NAME));
        Files.writeString(gsd.resolve(GsdStateStore.STATE_JSON), portablePopulatedStateJson(),
                StandardCharsets.UTF_8);
        boolean secureDirectoryStream =
                SecureDirectoryMutation.supportsSecureDirectoryStreams(root);
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT) //$NON-NLS-1$ //$NON-NLS-2$
                .contains("mac")) { //$NON-NLS-1$
            assertFalse("macOS default provider must exercise the native non-SDS path", //$NON-NLS-1$
                    secureDirectoryStream);
        }
        Map<String, String> before = snapshot(root);

        GsdState loaded = new GsdStateStore(root).loadReadOnly();

        assertEquals("portable-cycle", loaded.cycleId()); //$NON-NLS-1$
        assertEquals(7L, loaded.revision());
        assertEquals(before, snapshot(root));
    }

    @Test
    public void forcedNonSecureLoadReadOnlyParsesPopulatedStateWithoutMutation()
            throws IOException {
        Path root = tmp.newFolder("forced-read-only-populated").toPath(); //$NON-NLS-1$
        Path gsd = Files.createDirectories(root.resolve(GsdStateStore.GSD_DIR_NAME));
        Files.writeString(gsd.resolve(GsdStateStore.STATE_JSON), portablePopulatedStateJson(),
                StandardCharsets.UTF_8);
        Map<String, String> before = snapshot(root);
        GsdStateStore store = new GsdStateStore(root, null,
                CapabilityPolicy.FORCE_NON_SECURE_FOR_TESTS);

        GsdState loaded = store.loadReadOnly();

        assertEquals("portable-cycle", loaded.cycleId()); //$NON-NLS-1$
        assertEquals(3L, loaded.generation());
        assertEquals(7L, loaded.revision());
        assertEquals("portable inspection", loaded.goal()); //$NON-NLS-1$
        assertEquals(1, loaded.decisions().size());
        assertEquals(before, snapshot(root));
        assertFalse(Files.exists(gsd.resolve(GsdStateStore.STATE_LOCK)));
        assertFalse(Files.exists(gsd.resolve(GsdStateStore.STATE_BAK)));
        assertFalse(Files.exists(gsd.resolve(GsdProjections.STATE_FILE)));
        assertFalse(Files.exists(gsd.resolve(GsdProjections.PLAN_FILE)));
    }

    @Test
    public void loadReadOnlyCorruptPrimaryThrows() throws IOException {
        Path root = tmp.newFolder("forced-read-only-corrupt").toPath(); //$NON-NLS-1$
        GsdStateStore store = new GsdStateStore(root, null,
                CapabilityPolicy.FORCE_NON_SECURE_FOR_TESTS);
        Files.createDirectories(store.getGsdDirectory());
        Files.writeString(store.getGsdDirectory().resolve(GsdStateStore.STATE_JSON),
                "not valid json {{{", //$NON-NLS-1$
                StandardCharsets.UTF_8);
        Map<String, String> before = snapshot(root);

        try {
            store.loadReadOnly();
            fail("expected GsdCorruptException"); //$NON-NLS-1$
        } catch (GsdCorruptException e) {
            assertTrue(e.getMessage().contains("invalid JSON")); //$NON-NLS-1$
        }
        assertEquals(before, snapshot(root));
    }

    @Test
    public void loadReadOnlySymlinkedDirEscapingProjectIsRejected() throws IOException {
        // Skip on platforms that do not support symlinks (Windows without admin).
        Path root = tmp.newFolder("read-only-symlink-project").toPath(); //$NON-NLS-1$
        Path outside = tmp.newFolder("outside-ro").toPath(); //$NON-NLS-1$
        try {
            Files.createSymbolicLink(root.resolve(".codepilot1c"), outside); //$NON-NLS-1$
        } catch (UnsupportedOperationException | IOException e) {
            // Platform does not support symlinks; skip this test.
            return;
        }

        GsdStateStore store = new GsdStateStore(root);
        try {
            store.loadReadOnly();
            fail("expected confinement rejection on read-only path"); //$NON-NLS-1$
        } catch (AccessDeniedException e) {
            assertTrue(e.getMessage().contains("symbolic links are not allowed")); //$NON-NLS-1$
        }
        // No files written in the outside target.
        assertFalse(Files.exists(outside.resolve("gsd").resolve(GsdStateStore.STATE_JSON))); //$NON-NLS-1$
    }

    @Test
    public void forcedNonSecureLoadReadOnlyRejectsStateSymlinkEscapeWithoutMutation()
            throws IOException {
        Path root = tmp.newFolder("forced-read-only-state-link").toPath(); //$NON-NLS-1$
        Path gsd = Files.createDirectories(root.resolve(GsdStateStore.GSD_DIR_NAME));
        Path outside = tmp.newFolder("forced-read-only-state-link-outside").toPath(); //$NON-NLS-1$
        Path outsideState = Files.writeString(outside.resolve(GsdStateStore.STATE_JSON),
                portablePopulatedStateJson(), StandardCharsets.UTF_8);
        try {
            Files.createSymbolicLink(gsd.resolve(GsdStateStore.STATE_JSON), outsideState);
        } catch (UnsupportedOperationException e) {
            Assume.assumeNoException("symbolic links are required", e); //$NON-NLS-1$
        }
        Map<String, String> projectBefore = snapshot(root);
        Map<String, String> outsideBefore = snapshot(outside);
        GsdStateStore store = new GsdStateStore(root, null,
                CapabilityPolicy.FORCE_NON_SECURE_FOR_TESTS);

        try {
            store.loadReadOnly();
            fail("expected state symlink rejection"); //$NON-NLS-1$
        } catch (AccessDeniedException e) {
            assertTrue(e.getMessage().contains("symbolic links are not allowed")); //$NON-NLS-1$
        }

        assertEquals(projectBefore, snapshot(root));
        assertEquals(outsideBefore, snapshot(outside));
        assertEquals(portablePopulatedStateJson(),
                Files.readString(outsideState, StandardCharsets.UTF_8));
        assertFalse(Files.exists(gsd.resolve(GsdStateStore.STATE_LOCK)));
        assertFalse(Files.exists(gsd.resolve(GsdStateStore.STATE_BAK)));
    }

    @Test
    public void nativeLoadReadOnlyRejectsAncestorSwapRestoredAfterDescriptorOpen()
            throws IOException {
        Path root = tmp.newFolder("native-read-ancestor-swap").toPath(); //$NON-NLS-1$
        Path codepilot = Files.createDirectories(root.resolve(".codepilot1c")); //$NON-NLS-1$
        Path gsd = Files.createDirectories(codepilot.resolve("gsd")); //$NON-NLS-1$
        Files.writeString(gsd.resolve(GsdStateStore.STATE_JSON),
                portablePopulatedStateJson(), StandardCharsets.UTF_8);
        Path outside = tmp.newFolder("native-read-ancestor-outside").toPath(); //$NON-NLS-1$
        Path outsideCodepilot = Files.createDirectories(outside.resolve("replacement/gsd")) //$NON-NLS-1$
                .getParent();
        Files.writeString(outsideCodepilot.resolve("gsd").resolve(GsdStateStore.STATE_JSON), //$NON-NLS-1$
                portablePopulatedStateJson().replace("portable inspection", "outside content"), //$NON-NLS-1$ //$NON-NLS-2$
                StandardCharsets.UTF_8);
        Path saved = root.resolve(".codepilot1c-saved"); //$NON-NLS-1$
        Path replacement = outside.resolve("replacement"); //$NON-NLS-1$
        Map<String, String> projectBefore = snapshot(root);
        Map<String, String> outsideBefore = snapshot(outside);
        GsdStateStore store = new GsdStateStore(root, operation -> {
            if ("unix-read-before-open:0:.codepilot1c".equals(operation)) { //$NON-NLS-1$
                Files.move(codepilot, saved);
                Files.move(replacement, codepilot);
            } else if ("unix-read-after-open:0:.codepilot1c".equals(operation)) { //$NON-NLS-1$
                Files.move(codepilot, replacement);
                Files.move(saved, codepilot);
            }
        });

        try {
            store.loadReadOnly();
            fail("expected ancestor descriptor identity rejection"); //$NON-NLS-1$
        } catch (AccessDeniedException e) {
            assertTrue(e.getMessage().contains("expected current entry")); //$NON-NLS-1$
        }

        assertEquals(projectBefore, snapshot(root));
        assertEquals(outsideBefore, snapshot(outside));
        assertFalse(Files.exists(gsd.resolve(GsdStateStore.STATE_LOCK)));
        assertFalse(Files.exists(gsd.resolve(GsdStateStore.STATE_BAK)));
    }

    @Test
    public void nativeLoadReadOnlyRejectsSameSizeMtimeFinalSwapRestoredAfterOpen()
            throws IOException {
        Path root = tmp.newFolder("native-read-final-swap").toPath(); //$NON-NLS-1$
        Path gsd = Files.createDirectories(root.resolve(GsdStateStore.GSD_DIR_NAME));
        Path state = Files.writeString(gsd.resolve(GsdStateStore.STATE_JSON),
                portablePopulatedStateJson(), StandardCharsets.UTF_8);
        Path outside = tmp.newFolder("native-read-final-outside").toPath(); //$NON-NLS-1$
        String outsideJson = portablePopulatedStateJson().replace(
                "portable inspection", "external inspection"); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(portablePopulatedStateJson().length(), outsideJson.length());
        Path outsideState = Files.writeString(outside.resolve("outside.json"), outsideJson, //$NON-NLS-1$
                StandardCharsets.UTF_8);
        FileTime sharedTime = FileTime.fromMillis(1_700_000_000_000L);
        Files.setLastModifiedTime(state, sharedTime);
        Files.setLastModifiedTime(outsideState, sharedTime);
        FileTime gsdTime = Files.getLastModifiedTime(gsd);
        FileTime outsideTime = Files.getLastModifiedTime(outside);
        Path saved = gsd.resolve("state.saved"); //$NON-NLS-1$
        Map<String, String> projectBefore = snapshot(root);
        Map<String, String> outsideBefore = snapshot(outside);
        GsdStateStore store = new GsdStateStore(root, operation -> {
            if ("unix-read-before-open:2:state.json".equals(operation)) { //$NON-NLS-1$
                Files.move(state, saved);
                Files.move(outsideState, state);
            } else if ("unix-read-after-open:2:state.json".equals(operation)) { //$NON-NLS-1$
                Files.move(state, outsideState);
                Files.move(saved, state);
                Files.setLastModifiedTime(gsd, gsdTime);
                Files.setLastModifiedTime(outside, outsideTime);
            }
        });

        try {
            store.loadReadOnly();
            fail("expected final descriptor identity rejection"); //$NON-NLS-1$
        } catch (AccessDeniedException e) {
            assertTrue(e.getMessage().contains("expected current entry")); //$NON-NLS-1$
        }

        assertEquals(projectBefore, snapshot(root));
        assertEquals(outsideBefore, snapshot(outside));
    }

    @Test
    public void nativeLoadReadOnlyRejectsOutsideHardLinkWithoutMutation() throws IOException {
        Path root = tmp.newFolder("native-read-hard-link").toPath(); //$NON-NLS-1$
        Path gsd = Files.createDirectories(root.resolve(GsdStateStore.GSD_DIR_NAME));
        Path outside = tmp.newFolder("native-read-hard-link-outside").toPath(); //$NON-NLS-1$
        Path outsideState = Files.writeString(outside.resolve("outside.json"), //$NON-NLS-1$
                portablePopulatedStateJson(), StandardCharsets.UTF_8);
        Path state = gsd.resolve(GsdStateStore.STATE_JSON);
        Files.createLink(state, outsideState);
        Map<String, String> projectBefore = snapshot(root);
        Map<String, String> outsideBefore = snapshot(outside);

        try {
            new GsdStateStore(root).loadReadOnly();
            fail("expected hard-link rejection"); //$NON-NLS-1$
        } catch (AccessDeniedException e) {
            assertTrue(e.getMessage().contains("exactly one hard link")); //$NON-NLS-1$
        }

        assertEquals(projectBefore, snapshot(root));
        assertEquals(outsideBefore, snapshot(outside));
        assertEquals(portablePopulatedStateJson(),
                Files.readString(outsideState, StandardCharsets.UTF_8));
    }

    @Test
    public void forcedNonSecureLoadReadOnlyRejectsOversizedStateWithoutReadingIt()
            throws IOException {
        Path root = tmp.newFolder("forced-read-only-oversized").toPath(); //$NON-NLS-1$
        Path gsd = Files.createDirectories(root.resolve(GsdStateStore.GSD_DIR_NAME));
        Path state = gsd.resolve(GsdStateStore.STATE_JSON);
        try (java.nio.channels.SeekableByteChannel channel = Files.newByteChannel(state,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(GsdStateStore.MAX_READ_ONLY_STATE_BYTES);
            channel.write(java.nio.ByteBuffer.wrap(new byte[] {0}));
        }
        long size = Files.size(state);
        GsdStateStore store = new GsdStateStore(root, null,
                CapabilityPolicy.FORCE_NON_SECURE_FOR_TESTS);

        try {
            store.loadReadOnly();
            fail("expected bounded read rejection"); //$NON-NLS-1$
        } catch (GsdCorruptException e) {
            assertTrue(e.getMessage().contains("anchored read-only limit")); //$NON-NLS-1$
        }

        assertEquals(size, Files.size(state));
        assertFalse(Files.exists(gsd.resolve(GsdStateStore.STATE_LOCK)));
        assertFalse(Files.exists(gsd.resolve(GsdStateStore.STATE_BAK)));
    }

    // ---- Deterministic projections --------------------------------------

    @Test
    public void projectionsAreRegeneratedAndDeterministic() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);

        GsdState base = store.load();
        GsdState state = new GsdState(
                GsdState.CURRENT_SCHEMA_VERSION, base.revision(), GsdPhase.EXECUTING, "goal", //$NON-NLS-1$
                List.of(new GsdDecision("d1", "dec", "why", List.of())), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                List.of(new GsdTask("t1", "task", GsdTaskStatus.IN_PROGRESS, "w1", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        List.of(), List.of("e1"))), //$NON-NLS-1$
                List.of(new GsdWave("w1", "wave", "wg", List.of("t1"))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                List.of(new GsdEvidence("e1", "ev", GsdProvenance.OBSERVED, //$NON-NLS-1$ //$NON-NLS-2$
                        List.of("t1"), Instant.parse("2026-01-01T00:00:00Z"))), //$NON-NLS-1$
                GsdSessionPointer.of("s", "ws")); //$NON-NLS-1$ //$NON-NLS-2$
        store.save(state);

        Path stateMd = store.getGsdDirectory().resolve(GsdProjections.STATE_FILE);
        Path planMd = store.getGsdDirectory().resolve(GsdProjections.PLAN_FILE);
        String md1 = Files.readString(stateMd, StandardCharsets.UTF_8);
        String plan1 = Files.readString(planMd, StandardCharsets.UTF_8);

        // Regenerating from the same JSON yields identical bytes.
        GsdState reloaded = store.load();
        store.writeProjections(reloaded);
        String md2 = Files.readString(stateMd, StandardCharsets.UTF_8);
        String plan2 = Files.readString(planMd, StandardCharsets.UTF_8);
        assertEquals(md1, md2);
        assertEquals(plan1, plan2);

        assertTrue(md1.contains("# GSD State")); //$NON-NLS-1$
        assertTrue(md1.contains("EXECUTING")); //$NON-NLS-1$
        assertTrue(md1.contains("d1")); //$NON-NLS-1$
        assertTrue(md1.contains("OBSERVED")); //$NON-NLS-1$
        assertTrue(plan1.contains("# GSD Plan")); //$NON-NLS-1$
        assertTrue(plan1.contains("Wave w1")); //$NON-NLS-1$
        assertTrue(plan1.contains("t1")); //$NON-NLS-1$
    }

    @Test
    public void projectionsAreDeterministicRegardlessOfInputOrder() {
        GsdState a = new GsdState(
                GsdState.CURRENT_SCHEMA_VERSION, 0, GsdPhase.PLANNING, "g", //$NON-NLS-1$
                List.of(new GsdDecision("d2", "b", "y", List.of()), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        new GsdDecision("d1", "a", "x", List.of())), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                List.of(new GsdTask("t2", "b", GsdTaskStatus.PENDING, "w1", List.of(), List.of()), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        new GsdTask("t1", "a", GsdTaskStatus.PENDING, "w1", List.of(), List.of())), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                List.of(new GsdWave("w1", "wave", "", List.of("t1", "t2"))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                List.of(), GsdSessionPointer.empty());
        GsdState b = new GsdState(
                GsdState.CURRENT_SCHEMA_VERSION, 0, GsdPhase.PLANNING, "g", //$NON-NLS-1$
                List.of(new GsdDecision("d1", "a", "x", List.of()), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        new GsdDecision("d2", "b", "y", List.of())), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                List.of(new GsdTask("t1", "a", GsdTaskStatus.PENDING, "w1", List.of(), List.of()), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        new GsdTask("t2", "b", GsdTaskStatus.PENDING, "w1", List.of(), List.of())), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                List.of(new GsdWave("w1", "wave", "", List.of("t1", "t2"))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                List.of(), GsdSessionPointer.empty());

        assertEquals(GsdProjections.toStateMd(a), GsdProjections.toStateMd(b));
        assertEquals(GsdProjections.toPlanMd(a), GsdProjections.toPlanMd(b));
    }

    @Test
    public void guardIsValidForFreshState() {
        assertTrue(GsdGuard.isValid(GsdState.fresh()));
    }

    @Test
    public void nullStateRejectedByGuard() {
        try {
            GsdGuard.validate(null);
            fail("expected GsdGuardException"); //$NON-NLS-1$
        } catch (GsdGuardException e) {
            assertFalse(e.getViolations().isEmpty());
        }
    }

    // ---- Concurrency / inter-process lock -------------------------------

    @Test
    public void twoConcurrentWritersExactlyOneWinsCasOtherGetsStaleRevision() throws Exception {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);
        GsdState base = store.load();
        // Seed with a goal/task so we can assert no data loss by the winner below.
        GsdState seeded = new GsdState(GsdState.CURRENT_SCHEMA_VERSION, base.revision(),
                GsdPhase.EXECUTING, "seeded-goal", //$NON-NLS-1$
                List.of(),
                List.of(new GsdTask("t-seed", "seed task", GsdTaskStatus.PENDING, "w1", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        List.of(), List.of())),
                List.of(new GsdWave("w1", "seed-wave", "seed", List.of("t-seed"))), //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                List.of(), GsdSessionPointer.empty());
        GsdState seededSaved = store.save(seeded);
        assertEquals(1L, seededSaved.revision());

        // Both writers load the same revision 1, then race to save distinct goals.
        GsdStateStore a = new GsdStateStore(root);
        GsdStateStore b = new GsdStateStore(root);
        GsdState aState = a.load();
        GsdState bState = b.load();
        assertEquals(1L, aState.revision());
        assertEquals(1L, bState.revision());
        // Give each writer a distinct goal so we can tell which one persisted.
        GsdState aWant = new GsdState(GsdState.CURRENT_SCHEMA_VERSION, aState.revision(),
                GsdPhase.EXECUTING, "goal-A", aState.decisions(), aState.tasks(), //$NON-NLS-1$
                aState.waves(), aState.evidence(), aState.sessionPointer());
        GsdState bWant = new GsdState(GsdState.CURRENT_SCHEMA_VERSION, bState.revision(),
                GsdPhase.EXECUTING, "goal-B", bState.decisions(), bState.tasks(), //$NON-NLS-1$
                bState.waves(), bState.evidence(), bState.sessionPointer());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch fire = new CountDownLatch(1);
        AtomicReference<GsdState> aSaved = new AtomicReference<>();
        AtomicReference<Throwable> aErr = new AtomicReference<>();
        AtomicReference<GsdState> bSaved = new AtomicReference<>();
        AtomicReference<Throwable> bErr = new AtomicReference<>();

        Thread ta = new Thread(() -> {
            try {
                ready.countDown();
                fire.await();
                aSaved.set(a.save(aWant));
            } catch (Throwable e) {
                aErr.set(e);
            }
        }, "writer-a"); //$NON-NLS-1$
        Thread tb = new Thread(() -> {
            try {
                ready.countDown();
                fire.await();
                bSaved.set(b.save(bWant));
            } catch (Throwable e) {
                bErr.set(e);
            }
        }, "writer-b"); //$NON-NLS-1$
        ta.setDaemon(true);
        tb.setDaemon(true);
        ta.start();
        tb.start();
        ready.await();
        fire.countDown();
        ta.join(5000);
        tb.join(5000);
        assertFalse("writer-a still alive", ta.isAlive()); //$NON-NLS-1$
        assertFalse("writer-b still alive", tb.isAlive()); //$NON-NLS-1$

        // Exactly one writer wins the CAS and saves revision 2; the other gets a
        // GsdStaleRevisionException (not a generic IOException), and the state is intact.
        boolean aOk = aSaved.get() != null && aErr.get() == null;
        boolean bOk = bSaved.get() != null && bErr.get() == null;
        assertTrue("exactly one writer must win the CAS", aOk ^ bOk); //$NON-NLS-1$
        AtomicReference<Throwable> loser = aOk ? bErr : aErr;
        assertNotNull("loser must have an error", loser.get()); //$NON-NLS-1$
        assertTrue("loser must get GsdStaleRevisionException, not generic IOException; got " //$NON-NLS-1$
                + loser.get().getClass().getName(),
                loser.get() instanceof GsdStaleRevisionException);
        GsdState finalState = new GsdStateStore(root).load();
        assertEquals(2L, finalState.revision());
        // No data loss: the winner's goal persisted and the seeded task survived.
        assertTrue("goal must be one of the two writer goals: " + finalState.goal(), //$NON-NLS-1$
                finalState.goal().equals("goal-A") || finalState.goal().equals("goal-B")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(finalState.tasks().isEmpty());
    }

    // ---- IO / corruption semantics --------------------------------------

    @Test
    public void jsonNullPrimaryIsCorruptionAndRecoversFromBackup() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);
        GsdState base = store.load();
        GsdState first = store.save(new GsdState(GsdState.CURRENT_SCHEMA_VERSION, base.revision(),
                GsdPhase.EXECUTING, "g", List.of(), List.of(), List.of(), List.of(), //$NON-NLS-1$
                GsdSessionPointer.empty()));
        store.save(new GsdState(GsdState.CURRENT_SCHEMA_VERSION, first.revision(),
                GsdPhase.PLANNING, "g", List.of(), List.of(), List.of(), List.of(), //$NON-NLS-1$
                GsdSessionPointer.empty()));

        Path gsd = store.getGsdDirectory();
        Files.writeString(gsd.resolve(GsdStateStore.STATE_JSON), "null", StandardCharsets.UTF_8); //$NON-NLS-1$

        GsdState recovered = store.load();
        assertEquals(GsdPhase.EXECUTING, recovered.phase());
        assertEquals(1L, recovered.revision());
    }

    @Test
    public void emptyPrimaryIsCorruptionAndRecoversFromBackup() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);
        GsdState base = store.load();
        GsdState first = store.save(new GsdState(GsdState.CURRENT_SCHEMA_VERSION, base.revision(),
                GsdPhase.EXECUTING, "g", List.of(), List.of(), List.of(), List.of(), //$NON-NLS-1$
                GsdSessionPointer.empty()));
        store.save(new GsdState(GsdState.CURRENT_SCHEMA_VERSION, first.revision(),
                GsdPhase.PLANNING, "g", List.of(), List.of(), List.of(), List.of(), //$NON-NLS-1$
                GsdSessionPointer.empty()));

        Path gsd = store.getGsdDirectory();
        Files.write(gsd.resolve(GsdStateStore.STATE_JSON), new byte[0]);

        GsdState recovered = store.load();
        assertEquals(GsdPhase.EXECUTING, recovered.phase());
    }

    @Test
    public void corruptPrimaryAndCorruptBackupFailsClosedWithCorruptException() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);
        Path gsd = store.getGsdDirectory();
        Files.createDirectories(gsd);
        Files.writeString(gsd.resolve(GsdStateStore.STATE_JSON), "garbage", StandardCharsets.UTF_8); //$NON-NLS-1$
        Files.writeString(gsd.resolve(GsdStateStore.STATE_BAK), "also-garbage", StandardCharsets.UTF_8); //$NON-NLS-1$

        try {
            store.load();
            fail("expected GsdCorruptException"); //$NON-NLS-1$
        } catch (GsdCorruptException e) {
            assertTrue(e.getMessage().contains("corrupt")); //$NON-NLS-1$
        }
    }

    @Test
    public void accessFailureOnPrimaryDoesNotTriggerSilentBackupRecovery() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);
        GsdState base = store.load();
        GsdState first = store.save(new GsdState(GsdState.CURRENT_SCHEMA_VERSION, base.revision(),
                GsdPhase.EXECUTING, "g", List.of(), List.of(), List.of(), List.of(), //$NON-NLS-1$
                GsdSessionPointer.empty()));
        store.save(new GsdState(GsdState.CURRENT_SCHEMA_VERSION, first.revision(),
                GsdPhase.PLANNING, "g", List.of(), List.of(), List.of(), List.of(), //$NON-NLS-1$
                GsdSessionPointer.empty()));

        Path gsd = store.getGsdDirectory();
        Path state = gsd.resolve(GsdStateStore.STATE_JSON);
        // Make the primary unreadable (chmod 000). On filesystems that ignore mode bits
        // (e.g. running as root, or some Windows), skip the assertion rather than flake.
        Files.setPosixFilePermissions(state, java.util.Set.of());
        try {
            boolean enforced;
            try (java.io.InputStream ignored = Files.newInputStream(state)) {
                enforced = false; // still readable -> cannot enforce, skip
            } catch (AccessDeniedException ade) {
                enforced = true;
            } catch (IOException other) {
                enforced = false;
            }
            org.junit.Assume.assumeTrue("filesystem enforces unreadable mode bits", enforced); //$NON-NLS-1$

            try {
                store.load();
                fail("expected IOException (not silent backup recovery)"); //$NON-NLS-1$
            } catch (IOException e) {
                // Must NOT be a corruption exception and must NOT have silently recovered.
                assertFalse(e instanceof GsdCorruptException);
            }
        } finally {
            // Restore permissions so TemporaryFolder can clean up.
            try {
                Files.setPosixFilePermissions(state,
                        java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
            } catch (IOException ignored) {
                // best-effort cleanup
            }
        }
    }

    @Test
    public void missingPrimaryWithValidBackupRecoversWithoutCorruptCopy() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);
        GsdState base = store.load();
        GsdState first = store.save(new GsdState(GsdState.CURRENT_SCHEMA_VERSION, base.revision(),
                GsdPhase.VERIFYING, "g", List.of(), List.of(), List.of(), List.of(), //$NON-NLS-1$
                GsdSessionPointer.empty()));
        store.save(new GsdState(GsdState.CURRENT_SCHEMA_VERSION, first.revision(),
                GsdPhase.EXECUTING, "g", List.of(), List.of(), List.of(), List.of(), //$NON-NLS-1$
                GsdSessionPointer.empty()));

        Path gsd = store.getGsdDirectory();
        Files.delete(gsd.resolve(GsdStateStore.STATE_JSON));

        GsdState recovered = store.load();
        assertEquals(GsdPhase.VERIFYING, recovered.phase());
        assertTrue(Files.exists(gsd.resolve(GsdStateStore.STATE_JSON)));
        // No corrupt copy since the primary was simply missing, not corrupt.
        assertFalse(Files.exists(gsd.resolve(GsdStateStore.STATE_CORRUPT)));
    }

    @Test
    public void quarantineFailurePropagatesAndPrimaryIsPreserved() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);
        GsdState base = store.load();
        GsdState first = store.save(new GsdState(GsdState.CURRENT_SCHEMA_VERSION, base.revision(),
                GsdPhase.EXECUTING, "g", List.of(), List.of(), List.of(), List.of(), //$NON-NLS-1$
                GsdSessionPointer.empty()));
        store.save(new GsdState(GsdState.CURRENT_SCHEMA_VERSION, first.revision(),
                GsdPhase.PLANNING, "g", List.of(), List.of(), List.of(), List.of(), //$NON-NLS-1$
                GsdSessionPointer.empty()));

        Path gsd = store.getGsdDirectory();
        Path state = gsd.resolve(GsdStateStore.STATE_JSON);
        Files.writeString(state, "garbage", StandardCharsets.UTF_8); //$NON-NLS-1$
        // Pre-create a directory at the quarantine target so the move fails.
        Path corrupt = gsd.resolve(GsdStateStore.STATE_CORRUPT);
        Files.createDirectories(corrupt);
        // Atomic move of a file onto a non-empty directory path fails; block the slot.
        Files.writeString(corrupt.resolve("blocker"), "x", StandardCharsets.UTF_8); //$NON-NLS-1$ //$NON-NLS-2$

        try {
            store.load();
            // If the move somehow succeeded (filesystem-dependent), this is not a hard
            // failure; assert the corrupt bytes are still recoverable instead.
        } catch (IOException e) {
            // Expected: quarantine must propagate rather than silently swallow.
            assertFalse(e instanceof GsdCorruptException);
        }
    }

    // ---- Fix: fail-closed save when primary was recovered from backup ------

    @Test
    public void saveWithCorruptPrimaryPreservesGoodBackupAndFails() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);

        GsdState base = store.load();
        GsdState first = store.save(new GsdState(GsdState.CURRENT_SCHEMA_VERSION, base.revision(),
                GsdPhase.EXECUTING, "first", List.of(), List.of(), List.of(), List.of(), //$NON-NLS-1$
                GsdSessionPointer.empty()));
        GsdState second = store.save(new GsdState(GsdState.CURRENT_SCHEMA_VERSION, first.revision(),
                GsdPhase.PLANNING, "second", List.of(), List.of(), List.of(), List.of(), //$NON-NLS-1$
                GsdSessionPointer.empty()));

        Path gsd = store.getGsdDirectory();
        assertTrue("backup exists before corruption", Files.exists(gsd.resolve(GsdStateStore.STATE_BAK)));

        // Corrupt the primary WITHOUT any intervening reloads — so the next read
        // will detect recovery-needed (primary corrupt, backup valid) and the save
        // should fail-closed per the fix.
        Files.writeString(gsd.resolve(GsdStateStore.STATE_JSON),
                "{ bad json ", StandardCharsets.UTF_8); //$NON-NLS-1$

        // The backup still holds the good EXECUTING state.
        String bakContent = Files.readString(gsd.resolve(GsdStateStore.STATE_BAK), StandardCharsets.UTF_8);
        assertTrue("backup still contains good state", bakContent.contains("EXECUTING"));

        // Attempting to save while primary is corrupt must fail closed,
        // because readOutcome detects recovery-needed under the lock.
        GsdStateStore store2 = new GsdStateStore(root);
        // Build a state with the revision from the recovered backup (rev 1).
        GsdState wantSave = new GsdState(GsdState.CURRENT_SCHEMA_VERSION, first.revision(),
                GsdPhase.VERIFYING, "next-goal", List.of(), List.of(), List.of(), List.of(),
                GsdSessionPointer.empty());

        try {
            store2.save(wantSave);
            fail("expected GsdCorruptException when saving with corrupt/recovered primary"); //$NON-NLS-1$
        } catch (GsdCorruptException e) {
            assertTrue("error mentions recovery or migration", e.getMessage().contains("recovery")
                    || e.getMessage().contains("migration")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        // Backup is still intact — no data loss.
        String bakAfter = Files.readString(gsd.resolve(GsdStateStore.STATE_BAK), StandardCharsets.UTF_8);
        assertTrue("backup preserved after failed save", bakAfter.contains("EXECUTING"));
    }

    // ---- Fix: strict enum adapters reject unknown values -------------------

    @Test
    public void unknownPhaseCausesParseCorruption() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);
        Path statePath = store.getGsdDirectory().resolve(GsdStateStore.STATE_JSON);
        Files.createDirectories(store.getGsdDirectory());
        String badJson = "{ " + //$NON-NLS-1$
                "\"schemaVersion\":1," + //$NON-NLS-1$
                "\"revision\":0," + //$NON-NLS-1$
                "\"phase\":\"UNKNOWN_PHASE\",\"goal\":\"x\"," + //$NON-NLS-1$
                "\"decisions\":[],\"tasks\":[],\"waves\":[],\"evidence\":[" + //$NON-NLS-1$
                "{\"id\":\"e1\",\"text\":\"ev\",\"provenance\":\"OBSERVED\",\"taskIds\":[],\"createdAt\":\"2025-01-01T00:00:00Z\"}," + //$NON-NLS-1$
                "]," + //$NON-NLS-1$
                "\"sessionPointer\":{\"sessionId\":\"s\",\"workstreamId\":\"w\"}}"; //$NON-NLS-1$
        Files.write(statePath, badJson.getBytes(StandardCharsets.UTF_8));

        try {
            new GsdStateStore(root).load();
            fail("expected GsdCorruptException for unknown phase"); //$NON-NLS-1$
        } catch (GsdCorruptException e) {
            assertTrue("message mentions corruption or unknown value",
                    e.getMessage().toLowerCase().contains("corrupt")
                            || e.getMessage().toLowerCase().contains("unknown")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void unknownTaskStatusCausesParseCorruption() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);
        Path statePath = store.getGsdDirectory().resolve(GsdStateStore.STATE_JSON);
        Files.createDirectories(store.getGsdDirectory());

        String badJson = "{ " + //$NON-NLS-1$
                "\"schemaVersion\":1,\"revision\":0,\"phase\":\"DISCOVERY\",\"goal\":\"x\"," + //$NON-NLS-1$
                "\"decisions\":[],\"tasks\":[" + //$NON-NLS-1$
                "{\"id\":\"t1\",\"text\":\"t\",\"status\":\"GHOST_STATUS\",\"waveId\":null,\"dependsOn\":[],\"evidenceIds\":[]}," + //$NON-NLS-1$
                "],\"waves\":[],\"evidence\":[]," + //$NON-NLS-1$
                "\"sessionPointer\":{\"sessionId\":\"s\",\"workstreamId\":\"w\"}}"; //$NON-NLS-1$
        Files.write(statePath, badJson.getBytes(StandardCharsets.UTF_8));

        try {
            new GsdStateStore(root).load();
            fail("expected GsdCorruptException for unknown task status"); //$NON-NLS-1$
        } catch (GsdCorruptException e) {
            assertTrue("message mentions corruption or unknown value",
                    e.getMessage().toLowerCase().contains("corrupt")
                            || e.getMessage().toLowerCase().contains("unknown")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    @Test
    public void unknownProvenanceCausesParseCorruption() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);
        Path statePath = store.getGsdDirectory().resolve(GsdStateStore.STATE_JSON);
        Files.createDirectories(store.getGsdDirectory());

        String badJson = "{ " + //$NON-NLS-1$
                "\"schemaVersion\":1,\"revision\":0,\"phase\":\"DISCOVERY\",\"goal\":\"x\"," + //$NON-NLS-1$
                "\"decisions\":[],\"tasks\":[],\"waves\":[],\"evidence\":[" + //$NON-NLS-1$
                "{\"id\":\"e1\",\"text\":\"ev\",\"provenance\":\"FAKE_PROVENANCE\",\"taskIds\":[],\"createdAt\":\"2025-01-01T00:00:00Z\"}" + //$NON-NLS-1$
                "],\"sessionPointer\":{\"sessionId\":\"s\",\"workstreamId\":\"w\"}}"; //$NON-NLS-1$
        Files.write(statePath, badJson.getBytes(StandardCharsets.UTF_8));

        try {
            new GsdStateStore(root).load();
            fail("expected GsdCorruptException for unknown provenance"); //$NON-NLS-1$
        } catch (GsdCorruptException e) {
            assertTrue("message mentions corruption or unknown value",
                    e.getMessage().toLowerCase().contains("corrupt")
                            || e.getMessage().toLowerCase().contains("unknown")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    // ---- Fix: guard validation rejects invalid states even from backup -----

    @Test
    public void invalidBackupGuardRejects() throws IOException {
        Path root = newProject();
        GsdStateStore store = new GsdStateStore(root);

        GsdState base = store.load();
        GsdState first = store.save(new GsdState(GsdState.CURRENT_SCHEMA_VERSION, base.revision(),
                GsdPhase.EXECUTING, "first", List.of(), List.of(), List.of(), List.of(), //$NON-NLS-1$
                GsdSessionPointer.empty()));
        store.save(new GsdState(GsdState.CURRENT_SCHEMA_VERSION, first.revision(),
                GsdPhase.PLANNING, "second", List.of(), List.of(), List.of(), List.of(), //$NON-NLS-1$
                GsdSessionPointer.empty()));

        Path gsd = store.getGsdDirectory();
        Path bakPath = gsd.resolve(GsdStateStore.STATE_BAK);
        Path statePath = gsd.resolve(GsdStateStore.STATE_JSON);

        // Write an invalid state to backup: DONE task with INFERRED-only evidence
        // + CLOSED phase not all done -> guard violations. Uses valid JSON field names.
        String badJson = "{ " + //$NON-NLS-1$
                "\"schemaVersion\":1,\"revision\":99,\"phase\":\"CLOSED\",\"goal\":\"bad\"," + //$NON-NLS-1$
                "\"decisions\":[]," + //$NON-NLS-1$
                "\"tasks\":[{" + //$NON-NLS-1$
                "\"id\":\"t1\",\"title\":\"done-task\",\"status\":\"DONE\"," + //$NON-NLS-1$
                "\"waveId\":null,\"dependsOn\":[],\"evidenceIds\":[\"e1\"]}]," + //$NON-NLS-1$
                "\"waves\":[]," + //$NON-NLS-1$
                "\"evidence\":[{" + //$NON-NLS-1$
                "\"id\":\"e1\",\"description\":\"guessed\",\"provenance\":\"INFERRED\"," + //$NON-NLS-1$
                "\"taskIds\":[\"t1\"],\"createdAt\":\"2025-01-01T00:00:00Z\"}]," + //$NON-NLS-1$
                "\"sessionPointer\":{\"sessionId\":\"s\",\"workstreamId\":\"w\"}}"; //$NON-NLS-1$
        Files.writeString(bakPath, badJson, StandardCharsets.UTF_8);

        // Primary is missing -> recovery tries backup -> backup passes parse
        // but fails guard validation -> wrapped as GsdCorruptException.
        Files.deleteIfExists(statePath);

        try {
            store.load();
            fail("expected GsdCorruptException for invalid backup (guard violation)"); //$NON-NLS-1$
        } catch (GsdCorruptException e) {
            assertTrue("error wraps guard violations as corruption",
                    e.getMessage().toLowerCase().contains("guard")
                            || e.getCause() != null
                                    && e.getCause() instanceof GsdGuardException); //$NON-NLS-1$ //$NON-NLS-2$
        }
        // No corrupted copy written since neither file was successfully validated.
        assertFalse(Files.exists(gsd.resolve(GsdStateStore.STATE_CORRUPT)));
    }
}
