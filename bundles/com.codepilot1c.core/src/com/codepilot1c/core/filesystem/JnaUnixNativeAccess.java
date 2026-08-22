/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.filesystem;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

import com.sun.jna.Function;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.NativeLong;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;

/** JNA-only implementation isolated so the core bundle can resolve when the optional package is absent. */
final class JnaUnixNativeAccess implements AnchoredUnixRead.NativeAccess {

    private final Path capabilityPath;
    private final LibC libc;
    private final Function fstat;
    private final NativeAbi nativeAbi;

    JnaUnixNativeAccess(Path capabilityPath) throws SecureDirectoryCapabilityException {
        this.capabilityPath = capabilityPath;
        nativeAbi = NativeAbi.detect(capabilityPath, System.getProperty("os.name", ""), //$NON-NLS-1$ //$NON-NLS-2$
                System.getProperty("os.arch", "")); //$NON-NLS-1$ //$NON-NLS-2$
        libc = Native.load(Platform.C_LIBRARY_NAME, LibC.class);
        try {
            fstat = NativeLibrary.getInstance(Platform.C_LIBRARY_NAME)
                    .getFunction(nativeAbi.fstatSymbol());
        } catch (LinkageError | RuntimeException e) {
            throw fstatUnavailable(capabilityPath, nativeAbi, e);
        }
    }

    @Override
    public int open(String path, int flags) throws IOException {
        try {
            int fd = libc.open(path, flags);
            if (fd < 0) {
                throw failure("open"); //$NON-NLS-1$
            }
            return fd;
        } catch (LinkageError e) {
            throw unavailable(e);
        }
    }

    @Override
    public int openat(int directoryFd, String name, int flags) throws IOException {
        try {
            int fd = libc.openat(directoryFd, name, flags);
            if (fd < 0) {
                throw failure("openat"); //$NON-NLS-1$
            }
            return fd;
        } catch (LinkageError e) {
            throw unavailable(e);
        }
    }

    @Override
    public long read(int fd, byte[] buffer, long count) throws IOException {
        try {
            long result = libc.read(fd, buffer, new NativeLong(count)).longValue();
            if (result < 0L) {
                throw failure("read"); //$NON-NLS-1$
            }
            return result;
        } catch (LinkageError e) {
            throw unavailable(e);
        }
    }

    @Override
    public AnchoredUnixRead.NativeFileIdentity fstat(
            int fd, AnchoredUnixRead.PlatformConstants platform) throws IOException {
        try (Memory stat = new Memory(256L)) {
            stat.clear();
            if (fstat.invokeInt(new Object[] {fd, stat}) != 0) {
                throw failure(nativeAbi.fstatSymbol());
            }
            return decodeIdentity(stat, platform.statLayout());
        } catch (LinkageError | RuntimeException e) {
            throw fstatUnavailable(capabilityPath, nativeAbi, e);
        }
    }

    static AnchoredUnixRead.NativeFileIdentity decodeIdentity(
            Pointer stat, AnchoredUnixRead.StatLayout layout) {
        return switch (layout) {
            case MACOS_64 -> identity(stat.getInt(0L) & 0xffffffffL,
                    stat.getLong(8L), stat.getShort(6L) & 0xffffL,
                    stat.getShort(4L) & 0xffff, stat.getLong(96L));
            case LINUX_X86_64 -> identity(stat.getLong(0L),
                    stat.getLong(8L), stat.getLong(16L),
                    stat.getInt(24L), stat.getLong(48L));
            case LINUX_AARCH64 -> identity(stat.getLong(0L),
                    stat.getLong(8L), stat.getInt(20L) & 0xffffffffL,
                    stat.getInt(16L), stat.getLong(48L));
        };
    }

    @Override
    public void close(int fd) throws IOException {
        try {
            if (libc.close(fd) != 0) {
                throw failure("close"); //$NON-NLS-1$
            }
        } catch (LinkageError e) {
            throw unavailable(e);
        }
    }

    private static AnchoredUnixRead.NativeFileIdentity identity(long device, long inode,
            long linkCount, int mode, long size) {
        int fileType = mode & 0170000;
        return new AnchoredUnixRead.NativeFileIdentity(device, inode, linkCount,
                fileType == 0040000, fileType == 0100000, size);
    }

    private AnchoredUnixRead.NativeIOException failure(String operation) {
        return new AnchoredUnixRead.NativeIOException(operation, Native.getLastError());
    }

    private SecureDirectoryCapabilityException unavailable(LinkageError cause) {
        return new SecureDirectoryCapabilityException(capabilityPath,
                "JNA/libc anchored-read primitive is unavailable", cause); //$NON-NLS-1$
    }

    static SecureDirectoryCapabilityException fstatUnavailable(
            Path path, NativeAbi abi, Throwable cause) {
        if (abi == NativeAbi.MACOS_X86_64_INODE64) {
            return new SecureDirectoryCapabilityException(path,
                    "macOS x86_64 anchored read requires the libc inode64 ABI symbol " //$NON-NLS-1$
                            + "fstat$INODE64; the symbol is unavailable and fallback to " //$NON-NLS-1$
                            + "legacy fstat is disabled", cause); //$NON-NLS-1$
        }
        return new SecureDirectoryCapabilityException(path,
                "libc anchored-read identity primitive " + abi.fstatSymbol() //$NON-NLS-1$
                        + " is unavailable for " + abi.diagnosticName(), cause); //$NON-NLS-1$
    }

    enum NativeAbi {
        MACOS_X86_64_INODE64("fstat$INODE64", "macOS x86_64 inode64 ABI"), //$NON-NLS-1$ //$NON-NLS-2$
        MACOS_AARCH64("fstat", "macOS aarch64 ABI"), //$NON-NLS-1$ //$NON-NLS-2$
        LINUX_X86_64("fstat", "Linux x86_64 ABI"), //$NON-NLS-1$ //$NON-NLS-2$
        LINUX_AARCH64("fstat", "Linux aarch64 ABI"); //$NON-NLS-1$ //$NON-NLS-2$

        private final String fstatSymbol;
        private final String diagnosticName;

        NativeAbi(String fstatSymbol, String diagnosticName) {
            this.fstatSymbol = fstatSymbol;
            this.diagnosticName = diagnosticName;
        }

        String fstatSymbol() {
            return fstatSymbol;
        }

        String diagnosticName() {
            return diagnosticName;
        }

        static NativeAbi detect(Path path, String osName, String architecture)
                throws SecureDirectoryCapabilityException {
            String os = osName.toLowerCase(Locale.ROOT);
            String arch = architecture.toLowerCase(Locale.ROOT);
            if (os.contains("mac") || os.contains("darwin")) { //$NON-NLS-1$ //$NON-NLS-2$
                if (arch.equals("x86_64") || arch.equals("amd64")) { //$NON-NLS-1$ //$NON-NLS-2$
                    return MACOS_X86_64_INODE64;
                }
                if (arch.equals("aarch64") || arch.equals("arm64")) { //$NON-NLS-1$ //$NON-NLS-2$
                    return MACOS_AARCH64;
                }
                throw unsupportedArchitecture(path, "macOS", architecture); //$NON-NLS-1$
            }
            if (os.contains("linux")) { //$NON-NLS-1$
                if (arch.equals("x86_64") || arch.equals("amd64")) { //$NON-NLS-1$ //$NON-NLS-2$
                    return LINUX_X86_64;
                }
                if (arch.equals("aarch64") || arch.equals("arm64")) { //$NON-NLS-1$ //$NON-NLS-2$
                    return LINUX_AARCH64;
                }
                throw unsupportedArchitecture(path, "Linux", architecture); //$NON-NLS-1$
            }
            throw new SecureDirectoryCapabilityException(path,
                    "anchored read native ABI is supported only on macOS and Linux"); //$NON-NLS-1$
        }

        private static SecureDirectoryCapabilityException unsupportedArchitecture(
                Path path, String os, String architecture) {
            return new SecureDirectoryCapabilityException(path,
                    "anchored read does not support " + os + " architecture " //$NON-NLS-1$ //$NON-NLS-2$
                            + architecture);
        }
    }

    private interface LibC extends Library {
        int open(String path, int flags);

        int openat(int directoryFd, String path, int flags);

        NativeLong read(int fd, byte[] buffer, NativeLong count);

        int close(int fd);
    }
}
