/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.filesystem;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Real libc traversal contracts, including fail-closed identity-source transitions. */
public class AnchoredUnixReadTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void readsOnlyFromOpenDescriptorAndDoesNotMutate() throws IOException {
        Path root = tmp.newFolder("anchored-success").toPath(); //$NON-NLS-1$
        Path directory = Files.createDirectories(root.resolve("a/b")); //$NON-NLS-1$
        byte[] expected = "descriptor bytes".getBytes(StandardCharsets.UTF_8); //$NON-NLS-1$
        Path file = Files.write(directory.resolve("state.json"), expected); //$NON-NLS-1$
        long modified = Files.getLastModifiedTime(file).toMillis();

        AnchoredUnixRead.Result result = AnchoredUnixRead.read(
                root, Path.of("a/b/state.json"), 1024L, null); //$NON-NLS-1$

        assertTrue(result.exists());
        assertArrayEquals(expected, result.bytes());
        assertArrayEquals(expected, Files.readAllBytes(file));
        assertTrue(modified == Files.getLastModifiedTime(file).toMillis());
    }

    @Test
    public void staleErrnoDoesNotRejectOrLeakSuccessfullyOpenedDescriptor() throws IOException {
        Path root = tmp.newFolder("anchored-stale-errno").toPath(); //$NON-NLS-1$
        byte[] expected = "safe".getBytes(StandardCharsets.UTF_8); //$NON-NLS-1$
        Files.write(root.resolve("state.json"), expected); //$NON-NLS-1$
        Path descriptorDirectory = System.getProperty("os.name", "") //$NON-NLS-1$ //$NON-NLS-2$
                .toLowerCase(Locale.ROOT).contains("mac") //$NON-NLS-1$
                        ? Path.of("/dev/fd") : Path.of("/proc/self/fd"); //$NON-NLS-1$ //$NON-NLS-2$
        long before = descriptorCount(descriptorDirectory);
        com.sun.jna.Native.setLastError(22);

        AnchoredUnixRead.Result result = AnchoredUnixRead.read(
                root, Path.of("state.json"), 1024L, null); //$NON-NLS-1$

        assertArrayEquals(expected, result.bytes());
        assertTrue("successful descriptor leaked with stale errno", //$NON-NLS-1$
                descriptorCount(descriptorDirectory) <= before);
    }

    @Test
    public void missingRelativeEntryReturnsStableAbsenceWithoutMutation() throws IOException {
        Path root = tmp.newFolder("anchored-missing").toPath(); //$NON-NLS-1$

        AnchoredUnixRead.Result result = AnchoredUnixRead.read(
                root, Path.of("missing/child/state.json"), 1024L, null); //$NON-NLS-1$

        assertFalse(result.exists());
        assertFalse(Files.exists(root.resolve("missing"))); //$NON-NLS-1$
    }

    @Test
    public void nullCurrentFileKeyFailsInsideRealDescriptorTraversal() throws IOException {
        assertTransitioningFileKeyRejected(true);
    }

    @Test
    public void changedCurrentFileKeyFailsInsideRealDescriptorTraversal() throws IOException {
        assertTransitioningFileKeyRejected(false);
    }

    @Test
    public void descriptorsAreClosedAfterRepeatedHardLinkRejection() throws IOException {
        Path root = tmp.newFolder("anchored-fd-cleanup").toPath(); //$NON-NLS-1$
        Path outside = tmp.newFolder("anchored-fd-cleanup-outside").toPath(); //$NON-NLS-1$
        Path outsideFile = Files.writeString(outside.resolve("outside.json"), "outside"); //$NON-NLS-1$ //$NON-NLS-2$
        Files.createLink(root.resolve("state.json"), outsideFile); //$NON-NLS-1$
        Path descriptorDirectory = System.getProperty("os.name", "") //$NON-NLS-1$ //$NON-NLS-2$
                .toLowerCase(Locale.ROOT).contains("mac") //$NON-NLS-1$
                        ? Path.of("/dev/fd") : Path.of("/proc/self/fd"); //$NON-NLS-1$ //$NON-NLS-2$
        long before = descriptorCount(descriptorDirectory);

        for (int i = 0; i < 32; i++) {
            try {
                AnchoredUnixRead.read(root, Path.of("state.json"), 1024L, null); //$NON-NLS-1$
                fail("expected hard-link rejection"); //$NON-NLS-1$
            } catch (AccessDeniedException expected) {
                assertTrue(expected.getMessage().contains("exactly one hard link")); //$NON-NLS-1$
            }
        }

        assertTrue("native descriptors leaked after rejection", //$NON-NLS-1$
                descriptorCount(descriptorDirectory) <= before);
    }

    @Test
    public void errnoMappingIsDeterministicByFailureClass() throws IOException {
        AnchoredUnixRead.PlatformConstants platform =
                AnchoredUnixRead.PlatformConstants.detect(Path.of("state")); //$NON-NLS-1$

        if (platform.statLayout() == AnchoredUnixRead.StatLayout.LINUX_AARCH64) {
            assertEquals(0x00008000, platform.noFollow());
            assertEquals(0x00004000, platform.directory());
            assertEquals(0x00080000, platform.closeOnExec());
        }

        IOException denied = platform.mapNativeFailure(Path.of("state"), //$NON-NLS-1$
                new AnchoredUnixRead.NativeIOException("openat", 13)); //$NON-NLS-1$
        assertTrue(denied instanceof AccessDeniedException);

        int unsupportedErrno = platform.statLayout() == AnchoredUnixRead.StatLayout.MACOS_64
                ? 78 : 38;
        IOException unsupported = platform.mapNativeFailure(Path.of("state"), //$NON-NLS-1$
                new AnchoredUnixRead.NativeIOException("openat", unsupportedErrno)); //$NON-NLS-1$
        assertTrue(unsupported instanceof SecureDirectoryCapabilityException);

        IOException ordinary = platform.mapNativeFailure(Path.of("state"), //$NON-NLS-1$
                new AnchoredUnixRead.NativeIOException("read", 5)); //$NON-NLS-1$
        assertFalse(ordinary instanceof AccessDeniedException);
        assertFalse(ordinary instanceof SecureDirectoryCapabilityException);
    }

    @Test
    public void missingJnaFailsClosedWithoutPreventingFacadeClassLoad() throws Exception {
        String bundlePath = System.getProperty("core.bundle.path"); //$NON-NLS-1$
        assertTrue(bundlePath != null && !bundlePath.isBlank());
        try (URLClassLoader isolated = new URLClassLoader(
                new java.net.URL[] {Path.of(bundlePath).toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            Class<?> facade = Class.forName(
                    "com.codepilot1c.core.filesystem.AnchoredUnixRead", true, isolated); //$NON-NLS-1$
            assertEquals("com.codepilot1c.core.filesystem.AnchoredUnixRead", //$NON-NLS-1$
                    facade.getName());
            java.lang.reflect.Method read = java.util.Arrays.stream(facade.getMethods())
                    .filter(method -> method.getName().equals("read") //$NON-NLS-1$
                            && method.getParameterCount() == 4)
                    .findFirst().orElseThrow();
            Path root = tmp.newFolder("missing-jna-isolated").toPath(); //$NON-NLS-1$
            try {
                read.invoke(null, root, Path.of("state.json"), 1024L, null); //$NON-NLS-1$
                fail("expected isolated JNA capability failure"); //$NON-NLS-1$
            } catch (InvocationTargetException expected) {
                assertEquals("com.codepilot1c.core.filesystem.SecureDirectoryCapabilityException", //$NON-NLS-1$
                        expected.getCause().getClass().getName());
                assertTrue(expected.getCause().getMessage().contains("JNA/libc")); //$NON-NLS-1$
            }
        }
    }

    private static long descriptorCount(Path directory) throws IOException {
        try (java.util.stream.Stream<Path> descriptors = Files.list(directory)) {
            return descriptors.count();
        }
    }

    private void assertTransitioningFileKeyRejected(boolean useNull) throws IOException {
        Path root = tmp.newFolder("anchored-file-key-" + useNull).toPath(); //$NON-NLS-1$
        Path file = Files.writeString(root.resolve("state.json"), "safe"); //$NON-NLS-1$ //$NON-NLS-2$
        AtomicInteger fileCaptures = new AtomicInteger();
        AnchoredUnixRead.FileIdentitySource delegate =
                AnchoredUnixRead.FileIdentitySource.DEFAULT;
        AnchoredUnixRead.FileIdentitySource transitioning =
                new AnchoredUnixRead.FileIdentitySource() {
                    @Override
                    public AnchoredUnixRead.ExpectedIdentity capture(Path path)
                            throws IOException {
                        AnchoredUnixRead.ExpectedIdentity actual = delegate.capture(path);
                        if (path.equals(file) && fileCaptures.incrementAndGet() >= 2) {
                            return new AnchoredUnixRead.ExpectedIdentity(true,
                                    useNull ? null : new Object(), actual.device(), actual.inode(),
                                    actual.directory(), actual.regularFile(), actual.size());
                        }
                        return actual;
                    }

                    @Override
                    public AnchoredUnixRead.ExpectedIdentity captureDescriptor(
                            Path descriptorPath, Path logicalPath) throws IOException {
                        return delegate.captureDescriptor(descriptorPath, logicalPath);
                    }

                    @Override
                    public long linkCount(Path path, boolean followLinks, Path logicalPath)
                            throws IOException {
                        return delegate.linkCount(path, followLinks, logicalPath);
                    }
                };

        try {
            AnchoredUnixRead.read(root, Path.of("state.json"), 1024L, null, transitioning); //$NON-NLS-1$
            fail("expected unstable file-key rejection"); //$NON-NLS-1$
        } catch (AccessDeniedException expected) {
            assertTrue(expected.getMessage().contains("expected current entry")); //$NON-NLS-1$
        }
        assertTrue("the final descriptor must have been opened before the transition", //$NON-NLS-1$
                fileCaptures.get() >= 2);
        assertTrue(Files.readString(file).equals("safe")); //$NON-NLS-1$
    }
}
