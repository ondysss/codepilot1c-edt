/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.filesystem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.TreeMap;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.codepilot1c.core.filesystem.SecureDirectoryMutation.BoundRoot;
import com.codepilot1c.core.filesystem.SecureDirectoryMutation.CapabilityPolicy;

/** Deterministic adversarial coverage for every anchored mutation boundary. */
public class SecureDirectoryMutationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void forcedNonSecureCapabilityFailsBeforeAnyMutation() throws Exception {
        Path root = tmp.newFolder("forced-root").toPath(); //$NON-NLS-1$
        Path guarded = Files.createDirectories(root.resolve("guarded")); //$NON-NLS-1$
        Files.writeString(guarded.resolve("sentinel"), "unchanged", StandardCharsets.UTF_8); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, byte[]> before = snapshot(root);
        BoundRoot bound = SecureDirectoryMutation.bindRoot(
                root, CapabilityPolicy.FORCE_NON_SECURE_FOR_TESTS);

        try {
            SecureDirectoryMutation.open(bound, guarded, null, "forced-bind"); //$NON-NLS-1$
            fail("expected deterministic capability error"); //$NON-NLS-1$
        } catch (SecureDirectoryCapabilityException e) {
            assertTrue(e.getMessage().contains("SecureDirectoryStream")); //$NON-NLS-1$
        }

        assertSnapshotsEqual(before, snapshot(root));
    }

    @Test
    public void actualProviderEitherBindsSecurelyOrFailsBeforeAnyMutation() throws Exception {
        Path root = tmp.newFolder("actual-provider-root").toPath(); //$NON-NLS-1$
        boolean secure = SecureDirectoryMutation.supportsSecureDirectoryStreams(root);
        Path guarded = Files.createDirectories(root.resolve("guarded")); //$NON-NLS-1$
        Files.writeString(guarded.resolve("sentinel"), "unchanged", //$NON-NLS-1$ //$NON-NLS-2$
                StandardCharsets.UTF_8);
        Map<String, byte[]> before = snapshot(root);
        BoundRoot bound = SecureDirectoryMutation.bindRoot(root);

        if (secure) {
            try (SecureDirectoryMutation opened = SecureDirectoryMutation.open(
                    bound, guarded, null, "native-bind")) { //$NON-NLS-1$
                assertTrue(opened.exists("sentinel")); //$NON-NLS-1$
            }
        } else {
            try {
                SecureDirectoryMutation.open(bound, guarded, null, "native-bind"); //$NON-NLS-1$
                fail("expected native provider capability error"); //$NON-NLS-1$
            } catch (SecureDirectoryCapabilityException e) {
                assertTrue(e.getMessage().contains("mutation is disabled")); //$NON-NLS-1$
            }
        }

        assertSnapshotsEqual(before, snapshot(root));
    }

    @Test
    public void actualSecureProviderSupportsOrdinaryAtomicWriteMoveAndLockOpen() throws Exception {
        Fixture fixture = secureFixture("ordinary"); //$NON-NLS-1$
        BoundRoot bound = SecureDirectoryMutation.bindRoot(fixture.root);
        try (SecureDirectoryMutation directory = SecureDirectoryMutation.open(
                bound, fixture.guarded, null, "ordinary-bind")) { //$NON-NLS-1$
            directory.atomicWrite("state.json", "one".getBytes(StandardCharsets.UTF_8), //$NON-NLS-1$ //$NON-NLS-2$
                    "state"); //$NON-NLS-1$
            directory.atomicWrite("state.json.bak", directory.readAllBytes("state.json"), //$NON-NLS-1$ //$NON-NLS-2$
                    "backup"); //$NON-NLS-1$
            try (java.nio.channels.FileChannel channel = directory.openFileChannel(
                    "state.lock", //$NON-NLS-1$
                    java.util.Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE),
                    "lock")) { //$NON-NLS-1$
                assertTrue(channel.isOpen());
            }
            directory.move("state.json", "state.json.corrupt", "quarantine"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }

        assertEquals("one", Files.readString(fixture.guarded.resolve("state.json.bak"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("one", Files.readString(fixture.guarded.resolve("state.json.corrupt"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(Files.exists(fixture.guarded.resolve("state.lock"))); //$NON-NLS-1$
    }

    @Test
    public void preBindSwapCannotBindEscapedDirectory() throws Exception {
        Fixture fixture = secureFixture("prebind"); //$NON-NLS-1$
        Map<String, byte[]> outsideBefore = snapshot(fixture.outside);
        BoundRoot bound = SecureDirectoryMutation.bindRoot(fixture.root);

        try {
            SecureDirectoryMutation.open(bound, fixture.guarded, operation -> {
                if ("guarded-bind".equals(operation)) { //$NON-NLS-1$
                    fixture.swapGuardedToOutside();
                }
            }, "guarded-bind"); //$NON-NLS-1$
            fail("expected pre-bind ancestry rejection"); //$NON-NLS-1$
        } catch (IOException expected) {
            // The secure relative open rejects the replacement symlink.
        }

        assertSnapshotsEqual(outsideBefore, snapshot(fixture.outside));
        assertFalse(Files.exists(fixture.outside.resolve("state.json"))); //$NON-NLS-1$
    }

    @Test
    public void lockOpenSwapCreatesNoOutsideLock() throws Exception {
        Fixture fixture = secureFixture("lock-open"); //$NON-NLS-1$
        assertBoundarySwapRejected(fixture, "lock:open", directory -> { //$NON-NLS-1$
            try (java.nio.channels.FileChannel ignored = directory.openFileChannel(
                    "state.lock", //$NON-NLS-1$
                    java.util.Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE),
                    "lock")) { //$NON-NLS-1$
                // rejected before open
            }
        });
        assertFalse(Files.exists(fixture.outside.resolve("state.lock"))); //$NON-NLS-1$
    }

    @Test
    public void tempCreationSwapCreatesNoOutsideTempOrState() throws Exception {
        Fixture fixture = secureFixture("temp-create"); //$NON-NLS-1$
        assertBoundarySwapRejected(fixture, "state:temp-create", directory -> //$NON-NLS-1$
                directory.atomicWrite("state.json", bytes("new"), "state")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertNoNamedOrTemp(fixture.outside, "state.json"); //$NON-NLS-1$
    }

    @Test
    public void publicationSwapLeavesOutsideAndOriginalTargetUnchangedAndCleansTemp()
            throws Exception {
        Fixture fixture = secureFixture("publish"); //$NON-NLS-1$
        Files.writeString(fixture.guarded.resolve("state.json"), "old", StandardCharsets.UTF_8); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, byte[]> outsideBefore = snapshot(fixture.outside);
        BoundRoot bound = SecureDirectoryMutation.bindRoot(fixture.root);
        try (SecureDirectoryMutation directory = SecureDirectoryMutation.open(
                bound, fixture.guarded, operation -> {
                    if ("state:publish".equals(operation)) { //$NON-NLS-1$
                        fixture.swapGuardedToOutside();
                    }
                }, "guarded-bind")) { //$NON-NLS-1$
            try {
                directory.atomicWrite("state.json", bytes("new"), "state"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                fail("expected publication rejection"); //$NON-NLS-1$
            } catch (IOException expected) {
                // rejected after temp fsync and before publication
            }
        }

        assertSnapshotsEqual(outsideBefore, snapshot(fixture.outside));
        assertEquals("old", Files.readString(fixture.originalGuarded().resolve("state.json"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertNoTemp(fixture.originalGuarded());
    }

    @Test
    public void cleanupSwapDeletesOnlyThroughOriginalHandle() throws Exception {
        Fixture fixture = secureFixture("cleanup"); //$NON-NLS-1$
        Map<String, byte[]> outsideBefore = snapshot(fixture.outside);
        BoundRoot bound = SecureDirectoryMutation.bindRoot(fixture.root);
        try (SecureDirectoryMutation directory = SecureDirectoryMutation.open(
                bound, fixture.guarded, operation -> {
                    if ("state:publish".equals(operation)) { //$NON-NLS-1$
                        throw new IOException("force cleanup"); //$NON-NLS-1$
                    }
                    if ("state:cleanup".equals(operation)) { //$NON-NLS-1$
                        fixture.swapGuardedToOutside();
                    }
                }, "guarded-bind")) { //$NON-NLS-1$
            try {
                directory.atomicWrite("state.json", bytes("new"), "state"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                fail("expected forced publication failure"); //$NON-NLS-1$
            } catch (IOException expected) {
                assertEquals("force cleanup", expected.getMessage()); //$NON-NLS-1$
            }
        }

        assertSnapshotsEqual(outsideBefore, snapshot(fixture.outside));
        assertNoTemp(fixture.originalGuarded());
    }

    @Test
    public void renameSwapCannotQuarantineOutsideFile() throws Exception {
        Fixture fixture = secureFixture("move"); //$NON-NLS-1$
        Files.writeString(fixture.guarded.resolve("state.json"), "inside", //$NON-NLS-1$ //$NON-NLS-2$
                StandardCharsets.UTF_8);
        Files.writeString(fixture.outside.resolve("state.json"), "outside", //$NON-NLS-1$ //$NON-NLS-2$
                StandardCharsets.UTF_8);
        Map<String, byte[]> outsideBefore = snapshot(fixture.outside);
        assertBoundarySwapRejected(fixture, "quarantine:move", directory -> //$NON-NLS-1$
                directory.move("state.json", "state.json.corrupt", "quarantine")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertSnapshotsEqual(outsideBefore, snapshot(fixture.outside));
    }

    private void assertBoundarySwapRejected(Fixture fixture, String boundary,
            IoMutation mutation) throws Exception {
        Map<String, byte[]> outsideBefore = snapshot(fixture.outside);
        BoundRoot bound = SecureDirectoryMutation.bindRoot(fixture.root);
        try (SecureDirectoryMutation directory = SecureDirectoryMutation.open(
                bound, fixture.guarded, operation -> {
                    if (boundary.equals(operation)) {
                        fixture.swapGuardedToOutside();
                    }
                }, "guarded-bind")) { //$NON-NLS-1$
            try {
                mutation.run(directory);
                fail("expected changed ancestry rejection at " + boundary); //$NON-NLS-1$
            } catch (IOException expected) {
                // expected
            }
        }
        assertSnapshotsEqual(outsideBefore, snapshot(fixture.outside));
    }

    private Fixture secureFixture(String name) throws IOException {
        Path root = tmp.newFolder(name + "-root").toPath(); //$NON-NLS-1$
        Assume.assumeTrue("test requires the provider's actual SecureDirectoryStream", //$NON-NLS-1$
                SecureDirectoryMutation.supportsSecureDirectoryStreams(root));
        Path guarded = Files.createDirectories(root.resolve("guarded")); //$NON-NLS-1$
        Path outside = tmp.newFolder(name + "-outside").toPath(); //$NON-NLS-1$
        Files.writeString(outside.resolve("sentinel"), "unchanged", StandardCharsets.UTF_8); //$NON-NLS-1$ //$NON-NLS-2$
        return new Fixture(root, guarded, outside);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void assertNoNamedOrTemp(Path directory, String name) throws IOException {
        assertFalse(Files.exists(directory.resolve(name)));
        assertNoTemp(directory);
    }

    private static void assertNoTemp(Path directory) throws IOException {
        try (java.util.stream.Stream<Path> children = Files.list(directory)) {
            assertFalse(children.anyMatch(path -> path.getFileName().toString().endsWith(".tmp"))); //$NON-NLS-1$
        }
    }

    private static Map<String, byte[]> snapshot(Path root) throws IOException {
        Map<String, byte[]> result = new TreeMap<>();
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                String relative = root.relativize(path).toString();
                result.put(relative, Files.isDirectory(path) ? null : Files.readAllBytes(path));
            }
        }
        return result;
    }

    private static void assertSnapshotsEqual(Map<String, byte[]> expected,
            Map<String, byte[]> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        for (String path : expected.keySet()) {
            org.junit.Assert.assertArrayEquals("bytes changed for " + path, //$NON-NLS-1$
                    expected.get(path), actual.get(path));
        }
    }

    @FunctionalInterface
    private interface IoMutation {
        void run(SecureDirectoryMutation directory) throws IOException;
    }

    private static final class Fixture {
        private final Path root;
        private final Path guarded;
        private final Path outside;

        private Fixture(Path root, Path guarded, Path outside) {
            this.root = root;
            this.guarded = guarded;
            this.outside = outside;
        }

        private void swapGuardedToOutside() throws IOException {
            if (Files.isSymbolicLink(guarded)) {
                return;
            }
            Files.move(guarded, originalGuarded());
            Files.createSymbolicLink(guarded, outside);
        }

        private Path originalGuarded() {
            return root.resolve("guarded-original"); //$NON-NLS-1$
        }
    }
}
