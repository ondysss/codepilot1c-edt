/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.filesystem;

import java.io.IOException;
import java.nio.file.Path;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;

/** JNA-only implementation isolated so the core bundle can resolve when the optional package is absent. */
final class JnaUnixNativeAccess implements AnchoredUnixRead.NativeAccess {

    private final Path capabilityPath;
    private final LibC libc;

    JnaUnixNativeAccess(Path capabilityPath) {
        this.capabilityPath = capabilityPath;
        libc = Native.load(Platform.C_LIBRARY_NAME, LibC.class);
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
            if (libc.fstat(fd, stat) != 0) {
                throw failure("fstat"); //$NON-NLS-1$
            }
            return switch (platform.statLayout()) {
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
        } catch (LinkageError e) {
            throw unavailable(e);
        }
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

    private AnchoredUnixRead.NativeFileIdentity identity(long device, long inode,
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

    private interface LibC extends Library {
        int open(String path, int flags);

        int openat(int directoryFd, String path, int flags);

        NativeLong read(int fd, byte[] buffer, NativeLong count);

        int fstat(int fd, Pointer stat);

        int close(int fd);
    }
}
