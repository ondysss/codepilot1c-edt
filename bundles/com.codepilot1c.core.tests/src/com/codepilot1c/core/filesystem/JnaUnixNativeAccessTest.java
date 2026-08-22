/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.filesystem;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.sun.jna.Memory;

/** Focused ABI and real-libc identity contracts for descriptor-anchored reads. */
public class JnaUnixNativeAccessTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void macOsX86_64SelectsOnlyExplicitInode64Symbol() throws IOException {
        Path path = Path.of("state.json"); //$NON-NLS-1$

        JnaUnixNativeAccess.NativeAbi abi = JnaUnixNativeAccess.NativeAbi.detect(
                path, "Mac OS X", "x86_64"); //$NON-NLS-1$ //$NON-NLS-2$

        assertSame(JnaUnixNativeAccess.NativeAbi.MACOS_X86_64_INODE64, abi);
        assertEquals("fstat$INODE64", abi.fstatSymbol()); //$NON-NLS-1$
        assertFalse("legacy fstat must not be a macOS x86_64 fallback", //$NON-NLS-1$
                "fstat".equals(abi.fstatSymbol())); //$NON-NLS-1$
    }

    @Test
    public void linuxArchitecturesKeepStandardFstatSymbol() throws IOException {
        Path path = Path.of("state.json"); //$NON-NLS-1$

        assertEquals("fstat", JnaUnixNativeAccess.NativeAbi.detect( //$NON-NLS-1$
                path, "Linux", "amd64").fstatSymbol()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("fstat", JnaUnixNativeAccess.NativeAbi.detect( //$NON-NLS-1$
                path, "Linux", "aarch64").fstatSymbol()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void decodesDarwinInode64IdentityOffsets() {
        try (Memory stat = new Memory(256L)) {
            stat.clear();
            stat.setInt(0L, 0xfedcba98);
            stat.setShort(4L, (short) 0100000);
            stat.setShort(6L, (short) 3);
            stat.setLong(8L, 0x1020304050607080L);
            stat.setLong(96L, 9876543210L);

            AnchoredUnixRead.NativeFileIdentity identity =
                    JnaUnixNativeAccess.decodeIdentity(
                            stat, AnchoredUnixRead.StatLayout.MACOS_64);

            assertEquals(0xfedcba98L, identity.device());
            assertEquals(0x1020304050607080L, identity.inode());
            assertEquals(3L, identity.linkCount());
            assertFalse(identity.directory());
            assertTrue(identity.regularFile());
            assertEquals(9876543210L, identity.size());
        }
    }

    @Test
    public void missingMacOsInode64SymbolExplainsFailClosedCapability() throws IOException {
        SecureDirectoryCapabilityException failure = JnaUnixNativeAccess.fstatUnavailable(
                Path.of("state.json"), //$NON-NLS-1$
                JnaUnixNativeAccess.NativeAbi.MACOS_X86_64_INODE64,
                new UnsatisfiedLinkError("missing test symbol")); //$NON-NLS-1$

        assertTrue(failure.getMessage().contains("fstat$INODE64")); //$NON-NLS-1$
        assertTrue(failure.getMessage().contains("legacy fstat")); //$NON-NLS-1$
        assertTrue(failure.getMessage().contains("disabled")); //$NON-NLS-1$
    }

    @Test
    public void realFstatIdentityMatchesJavaUnixIdentityOnCurrentAbi() throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT); //$NON-NLS-1$ //$NON-NLS-2$
        Assume.assumeTrue(os.contains("mac") || os.contains("darwin") //$NON-NLS-1$ //$NON-NLS-2$
                || os.contains("linux")); //$NON-NLS-1$
        Path file = Files.writeString(tmp.newFile("native-identity.json").toPath(), //$NON-NLS-1$
                "identity"); //$NON-NLS-1$
        AnchoredUnixRead.PlatformConstants platform =
                AnchoredUnixRead.PlatformConstants.detect(file);
        JnaUnixNativeAccess access = new JnaUnixNativeAccess(file);
        int fd = access.open(file.toString(), platform.fileFlags());
        try {
            AnchoredUnixRead.NativeFileIdentity identity = access.fstat(fd, platform);

            assertEquals(unixNumber(file, "unix:dev"), identity.device()); //$NON-NLS-1$
            assertEquals(unixNumber(file, "unix:ino"), identity.inode()); //$NON-NLS-1$
            assertEquals(unixNumber(file, "unix:nlink"), identity.linkCount()); //$NON-NLS-1$
            assertFalse(identity.directory());
            assertTrue(identity.regularFile());
            assertEquals(Files.size(file), identity.size());
        } finally {
            access.close(fd);
        }
    }

    private static long unixNumber(Path path, String attribute) throws IOException {
        return ((Number) Files.getAttribute(path, attribute)).longValue();
    }
}
