/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.filesystem;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Bounded, read-only traversal through Unix file descriptors.
 *
 * <p>The root and every descendant are opened with {@code open}/{@code openat}; symbolic links
 * are never followed. Each still-open descriptor is bound to both the entry observed before its
 * open and the currently visible entry. Native {@code fstat} supplies descriptor identity on both
 * platforms; Linux additionally corroborates it through {@code /proc/self/fd}. The complete
 * descriptor chain is revalidated after the byte read. There is no pathname data reopen and no
 * mutation fallback.</p>
 *
 * <p>Only macOS and Linux are supported. A missing JNA core package, missing libc primitive,
 * unsupported operating system, or unavailable stable file identity fails closed with
 * {@link SecureDirectoryCapabilityException}.</p>
 */
public final class AnchoredUnixRead {

    private static final int BUFFER_SIZE = 8192;
    private static final int EINTR = 4;
    private static final int ENOENT_MACOS = 2;
    private static final int ENOENT_LINUX = 2;

    private AnchoredUnixRead() {
    }

    /** Result of an anchored read. Missing means that traversal proved stable absence. */
    public record Result(boolean exists, byte[] bytes) {
        public Result {
            bytes = bytes == null ? new byte[0] : bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    /** Signals the preflight or incremental byte ceiling without weakening the I/O boundary. */
    public static final class ReadLimitExceededException extends IOException {
        private static final long serialVersionUID = 1L;

        ReadLimitExceededException(long limit) {
            super("anchored read exceeds limit of " + limit + " bytes"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    /**
     * Reads one relative regular file while retaining the complete open descriptor ancestry.
     *
     * @param root existing absolute or relative directory used as the traversal root
     * @param relativeFile non-empty relative file path below {@code root}
     * @param maximumBytes non-negative hard ceiling, checked before and during the read
     * @param hook optional deterministic test/adversarial boundary callback
     */
    public static Result read(Path root, Path relativeFile, long maximumBytes,
            SecureDirectoryMutation.MutationHook hook) throws IOException {
        return read(root, relativeFile, maximumBytes, hook, FileIdentitySource.DEFAULT);
    }

    static Result read(Path root, Path relativeFile, long maximumBytes,
            SecureDirectoryMutation.MutationHook hook, FileIdentitySource identities)
            throws IOException {
        Path logicalRoot = Objects.requireNonNull(root, "root").toAbsolutePath().normalize(); //$NON-NLS-1$
        Path relative = Objects.requireNonNull(relativeFile, "relativeFile").normalize(); //$NON-NLS-1$
        if (maximumBytes < 0L) {
            throw new IllegalArgumentException("maximumBytes must be non-negative"); //$NON-NLS-1$
        }
        if (relative.isAbsolute() || relative.getNameCount() == 0
                || relative.startsWith("..") || ".".equals(relative.toString())) { //$NON-NLS-1$ //$NON-NLS-2$
            throw denied(logicalRoot.resolve(relative), "read target is not a confined relative path"); //$NON-NLS-1$
        }
        for (Path component : relative) {
            String name = component.toString();
            if (name.isEmpty() || ".".equals(name) || "..".equals(name)) { //$NON-NLS-1$ //$NON-NLS-2$
                throw denied(logicalRoot.resolve(relative), "read path contains an invalid component"); //$NON-NLS-1$
            }
        }

        PlatformConstants platform = PlatformConstants.detect(logicalRoot);
        NativeAccess nativeAccess = NativeAccess.load(logicalRoot);
        SecureDirectoryMutation.MutationHook effectiveHook = hook == null ? operation -> {
            // no-op
        } : hook;
        List<OpenedDescriptor> opened = new ArrayList<>();
        Throwable primaryFailure = null;
        try {
            ExpectedIdentity rootExpected = identities.capture(logicalRoot);
            if (!rootExpected.exists()) {
                throw new NoSuchFileException(logicalRoot.toString());
            }
            effectiveHook.beforeMutation("unix-read-before-open:root"); //$NON-NLS-1$
            int rootFd;
            try {
                rootFd = nativeAccess.open(logicalRoot.toString(), platform.directoryFlags());
            } catch (NativeIOException e) {
                throw platform.mapNativeFailure(logicalRoot, e);
            }
            opened.add(new OpenedDescriptor(rootFd, logicalRoot, rootExpected, true));
            effectiveHook.beforeMutation("unix-read-after-open:root"); //$NON-NLS-1$
            validate(opened.get(0), platform, nativeAccess, identities);

            Path logical = logicalRoot;
            for (int i = 0; i < relative.getNameCount(); i++) {
                String name = relative.getName(i).toString();
                logical = logical.resolve(name);
                boolean finalComponent = i == relative.getNameCount() - 1;
                ExpectedIdentity expected = identities.capture(logical);
                String boundary = Integer.toString(i) + ":" + name; //$NON-NLS-1$
                effectiveHook.beforeMutation("unix-read-before-open:" + boundary); //$NON-NLS-1$

                int fd;
                try {
                    fd = nativeAccess.openat(opened.get(opened.size() - 1).fd(), name,
                            finalComponent ? platform.fileFlags() : platform.directoryFlags());
                } catch (NativeIOException e) {
                    if (platform.isMissing(e.errorCode()) && !expected.exists()) {
                        validateAll(opened, platform, nativeAccess, identities);
                        if (identities.capture(logical).exists()) {
                            throw denied(logical, "read path appeared during anchored absence check"); //$NON-NLS-1$
                        }
                        return new Result(false, new byte[0]);
                    }
                    if (platform.isMissing(e.errorCode())) {
                        throw denied(logical, "read path changed before descriptor open"); //$NON-NLS-1$
                    }
                    throw platform.mapNativeFailure(logical, e);
                }

                OpenedDescriptor descriptor = new OpenedDescriptor(
                        fd, logical, expected, !finalComponent);
                opened.add(descriptor);
                effectiveHook.beforeMutation("unix-read-after-open:" + boundary); //$NON-NLS-1$
                validate(descriptor, platform, nativeAccess, identities);
            }

            OpenedDescriptor file = opened.get(opened.size() - 1);
            validateAll(opened, platform, nativeAccess, identities);
            long size = nativeIdentity(nativeAccess, file.fd(), platform,
                    file.logicalPath()).size();
            requireWithinLimit(size, maximumBytes);
            requireSingleLinkBeforeRead(file, platform, nativeAccess, identities);

            byte[] bytes = readBytes(nativeAccess, file.fd(), file.logicalPath(),
                    platform, maximumBytes);

            requirePostReadLinkCount(file, platform, nativeAccess);
            validateAll(opened, platform, nativeAccess, identities);
            long currentLinks = identities.linkCount(
                    file.logicalPath(), false, file.logicalPath());
            if (currentLinks != 1L) {
                throw denied(file.logicalPath(),
                        "current regular file must have exactly one hard link after read"); //$NON-NLS-1$
            }
            return new Result(true, bytes);
        } catch (IOException | RuntimeException | Error e) {
            primaryFailure = e;
            throw e;
        } finally {
            IOException closeFailure = closeReverse(opened, nativeAccess);
            if (closeFailure != null) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(closeFailure);
                } else {
                    throw closeFailure;
                }
            }
        }
    }

    private static byte[] readBytes(NativeAccess nativeAccess, int fd, Path logicalPath,
            PlatformConstants platform, long maximumBytes) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            long total = 0L;
            while (true) {
                long count;
                try {
                    count = nativeAccess.read(fd, buffer, BUFFER_SIZE);
                } catch (NativeIOException e) {
                    if (e.errorCode() == EINTR) {
                        continue;
                    }
                    throw platform.mapNativeFailure(logicalPath, e);
                }
                if (count == 0L) {
                    break;
                }
                if (count < 0L || count > BUFFER_SIZE) {
                    throw new IOException("libc read returned an invalid byte count: " + count); //$NON-NLS-1$
                }
                total += count;
                requireWithinLimit(total, maximumBytes);
                output.write(buffer, 0, (int) count);
            }
            return output.toByteArray();
        }
    }

    private static void requireWithinLimit(long size, long maximumBytes)
            throws ReadLimitExceededException {
        if (size < 0L || size > maximumBytes) {
            throw new ReadLimitExceededException(maximumBytes);
        }
    }

    private static void requireSingleLinkBeforeRead(OpenedDescriptor file,
            PlatformConstants platform, NativeAccess nativeAccess,
            FileIdentitySource identities) throws IOException {
        long descriptorLinks = nativeIdentity(nativeAccess, file.fd(), platform,
                file.logicalPath()).linkCount();
        long currentLinks = identities.linkCount(file.logicalPath(), false, file.logicalPath());
        if (descriptorLinks != 1L || currentLinks != 1L) {
            throw denied(file.logicalPath(),
                    "anchored regular file must have exactly one hard link"); //$NON-NLS-1$
        }
    }

    private static void requirePostReadLinkCount(OpenedDescriptor file,
            PlatformConstants platform, NativeAccess nativeAccess) throws IOException {
        long descriptorLinks = nativeIdentity(nativeAccess, file.fd(), platform,
                file.logicalPath()).linkCount();
        if (descriptorLinks < 0L || descriptorLinks > 1L) {
            throw denied(file.logicalPath(),
                    "anchored regular file gained a hard link during read"); //$NON-NLS-1$
        }
        // nlink==0 is a benign unlink/atomic-replace state for the still-open fd. The following
        // current-entry validation deliberately fails closed unless the path again names this fd.
    }

    private static void validateAll(List<OpenedDescriptor> opened, PlatformConstants platform,
            NativeAccess nativeAccess, FileIdentitySource identities) throws IOException {
        for (OpenedDescriptor descriptor : opened) {
            validate(descriptor, platform, nativeAccess, identities);
        }
    }

    private static void validate(OpenedDescriptor descriptor, PlatformConstants platform,
            NativeAccess nativeAccess, FileIdentitySource identities) throws IOException {
        ExpectedIdentity expected = descriptor.expected();
        if (!expected.exists()) {
            throw denied(descriptor.logicalPath(),
                    "read path was absent before descriptor open"); //$NON-NLS-1$
        }
        ExpectedIdentity anchored = platform.requireJavaSameFile()
                ? identities.captureDescriptor(
                        platform.descriptorPath(descriptor.fd()), descriptor.logicalPath())
                : null;
        ExpectedIdentity current = identities.capture(descriptor.logicalPath());
        NativeFileIdentity nativeIdentity = nativeIdentity(nativeAccess, descriptor.fd(),
                platform, descriptor.logicalPath());
        boolean sameFile = platform.requireJavaSameFile()
                && Files.isSameFile(platform.descriptorPath(descriptor.fd()),
                        descriptor.logicalPath());
        if (!current.exists()
                || expected.fileKey() == null
                || current.fileKey() == null
                || (anchored != null && anchored.fileKey() == null)
                || !Objects.equals(expected.fileKey(), current.fileKey())
                || expected.device() != nativeIdentity.device()
                || expected.inode() != nativeIdentity.inode()
                || current.device() != nativeIdentity.device()
                || current.inode() != nativeIdentity.inode()
                || (anchored != null && descriptor.directory() != anchored.directory())
                || descriptor.directory() != current.directory()
                || descriptor.directory() != nativeIdentity.directory()
                || (!descriptor.directory() && ((anchored != null && !anchored.regularFile())
                        || !current.regularFile() || !nativeIdentity.regularFile()))
                || (platform.requireJavaSameFile() && !sameFile)) {
            throw denied(descriptor.logicalPath(),
                    "opened descriptor does not match the expected current entry" //$NON-NLS-1$
                            + " [sameFile=" + sameFile //$NON-NLS-1$
                            + ", expectedKey=" + expected.fileKey() //$NON-NLS-1$
                            + ", currentKey=" + current.fileKey() //$NON-NLS-1$
                            + ", expectedDeviceInode=" + expected.device() + ":" //$NON-NLS-1$ //$NON-NLS-2$
                            + expected.inode()
                            + ", descriptorDeviceInode=" + nativeIdentity.device() + ":" //$NON-NLS-1$ //$NON-NLS-2$
                            + nativeIdentity.inode()
                            + ", expectedDirectory=" + descriptor.directory() //$NON-NLS-1$
                            + ", anchoredDirectory=" //$NON-NLS-1$
                            + (anchored == null ? "native-only" : anchored.directory()) //$NON-NLS-1$
                            + ", currentDirectory=" + current.directory() + "]"); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private static IOException closeReverse(List<OpenedDescriptor> opened,
            NativeAccess nativeAccess) {
        IOException failure = null;
        List<OpenedDescriptor> reverse = new ArrayList<>(opened);
        Collections.reverse(reverse);
        for (OpenedDescriptor descriptor : reverse) {
            try {
                nativeAccess.close(descriptor.fd());
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        return failure;
    }

    private static NativeFileIdentity nativeIdentity(NativeAccess nativeAccess, int fd,
            PlatformConstants platform, Path logicalPath) throws IOException {
        try {
            return nativeAccess.fstat(fd, platform);
        } catch (NativeIOException e) {
            throw platform.mapNativeFailure(logicalPath, e);
        }
    }

    private static AccessDeniedException denied(Path path, String reason) {
        return new AccessDeniedException(path.toString(), null, reason);
    }

    private record OpenedDescriptor(
            int fd, Path logicalPath, ExpectedIdentity expected, boolean directory) {
    }

    record ExpectedIdentity(boolean exists, Object fileKey, long device, long inode,
            boolean directory, boolean regularFile, long size) {
        static ExpectedIdentity missing() {
            return new ExpectedIdentity(false, null, 0L, 0L, false, false, 0L);
        }
    }

    record NativeFileIdentity(long device, long inode, long linkCount,
            boolean directory, boolean regularFile, long size) {
    }

    interface FileIdentitySource {
        FileIdentitySource DEFAULT = new FileIdentitySource() {
            @Override
            public ExpectedIdentity capture(Path path) throws IOException {
                try {
                    BasicFileAttributes attributes = Files.readAttributes(
                            path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attributes.isSymbolicLink()) {
                        throw denied(path, "symbolic links are not allowed in anchored reads"); //$NON-NLS-1$
                    }
                    return fromAttributes(path, path, attributes);
                } catch (NoSuchFileException e) {
                    return ExpectedIdentity.missing();
                }
            }

            @Override
            public ExpectedIdentity captureDescriptor(Path descriptorPath, Path logicalPath)
                    throws IOException {
                return fromAttributes(logicalPath, descriptorPath, Files.readAttributes(
                        descriptorPath, BasicFileAttributes.class));
            }

            @Override
            public long linkCount(Path path, boolean followLinks, Path logicalPath)
                    throws IOException {
                Object value = followLinks
                        ? Files.getAttribute(path, "unix:nlink") //$NON-NLS-1$
                        : Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS); //$NON-NLS-1$
                if (!(value instanceof Number number)) {
                    throw new SecureDirectoryCapabilityException(logicalPath,
                            "filesystem does not expose unix:nlink"); //$NON-NLS-1$
                }
                return number.longValue();
            }

            private ExpectedIdentity fromAttributes(Path logicalPath, Path attributePath,
                    BasicFileAttributes attributes) throws IOException {
                Object key = attributes.fileKey();
                if (key == null) {
                    throw new SecureDirectoryCapabilityException(logicalPath,
                            "filesystem does not expose stable file identity"); //$NON-NLS-1$
                }
                return new ExpectedIdentity(true, key,
                        unixNumber(attributePath, "unix:dev"), //$NON-NLS-1$
                        unixNumber(attributePath, "unix:ino"), //$NON-NLS-1$
                        attributes.isDirectory(), attributes.isRegularFile(), attributes.size());
            }

            private long unixNumber(Path path, String attribute) throws IOException {
                Object value = Files.getAttribute(path, attribute, LinkOption.NOFOLLOW_LINKS);
                if (!(value instanceof Number number)) {
                    throw new SecureDirectoryCapabilityException(path,
                            "filesystem does not expose " + attribute); //$NON-NLS-1$
                }
                return number.longValue();
            }
        };

        ExpectedIdentity capture(Path path) throws IOException;

        ExpectedIdentity captureDescriptor(Path descriptorPath, Path logicalPath)
                throws IOException;

        long linkCount(Path path, boolean followLinks, Path logicalPath) throws IOException;
    }

    record PlatformConstants(int noFollow, int directory, int closeOnExec,
            String descriptorRoot, int missingError, StatLayout statLayout,
            boolean requireJavaSameFile) {
        static PlatformConstants detect(Path path) throws SecureDirectoryCapabilityException {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT); //$NON-NLS-1$ //$NON-NLS-2$
            if (os.contains("mac") || os.contains("darwin")) { //$NON-NLS-1$ //$NON-NLS-2$
                return new PlatformConstants(0x00000100, 0x00100000, 0x01000000,
                        null, ENOENT_MACOS, StatLayout.MACOS_64, false);
            }
            if (os.contains("linux")) { //$NON-NLS-1$
                String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT); //$NON-NLS-1$ //$NON-NLS-2$
                StatLayout layout;
                if (architecture.equals("aarch64") || architecture.equals("arm64")) { //$NON-NLS-1$ //$NON-NLS-2$
                    layout = StatLayout.LINUX_AARCH64;
                } else if (architecture.equals("x86_64") || architecture.equals("amd64")) { //$NON-NLS-1$ //$NON-NLS-2$
                    layout = StatLayout.LINUX_X86_64;
                } else {
                    throw new SecureDirectoryCapabilityException(path,
                            "anchored read does not support Linux architecture " //$NON-NLS-1$
                                    + architecture);
                }
                int noFollow = layout == StatLayout.LINUX_AARCH64
                        ? 0x00008000 : 0x00020000;
                int directory = layout == StatLayout.LINUX_AARCH64
                        ? 0x00004000 : 0x00010000;
                return new PlatformConstants(noFollow, directory, 0x00080000,
                        "/proc/self/fd", ENOENT_LINUX, layout, true); //$NON-NLS-1$
            }
            throw new SecureDirectoryCapabilityException(path,
                    "anchored read is supported only on macOS and Linux"); //$NON-NLS-1$
        }

        int directoryFlags() {
            return noFollow | directory | closeOnExec;
        }

        int fileFlags() {
            return noFollow | closeOnExec;
        }

        Path descriptorPath(int fd) {
            if (descriptorRoot == null) {
                throw new IllegalStateException(
                        "descriptor pseudo-path is not used on this platform"); //$NON-NLS-1$
            }
            return Path.of(descriptorRoot, Integer.toString(fd));
        }

        boolean isMissing(int errorCode) {
            return errorCode == missingError;
        }

        IOException mapNativeFailure(Path path, NativeIOException failure) {
            int error = failure.errorCode();
            int loop = statLayout == StatLayout.MACOS_64 ? 62 : 40;
            int notSupported = statLayout == StatLayout.MACOS_64 ? 102 : 95;
            int noSystemCall = statLayout == StatLayout.MACOS_64 ? 78 : 38;
            if (error == 1 || error == 13 || error == 20 || error == loop) {
                AccessDeniedException denied = new AccessDeniedException(path.toString(), null,
                        failure.operation() + " rejected anchored traversal (errno " //$NON-NLS-1$
                                + error + ")"); //$NON-NLS-1$
                denied.initCause(failure);
                return denied;
            }
            if (error == noSystemCall || error == notSupported) {
                return new SecureDirectoryCapabilityException(path,
                        failure.operation() + " is unsupported by libc/filesystem (errno " //$NON-NLS-1$
                                + error + ")", failure); //$NON-NLS-1$
            }
            return failure.withPath(path);
        }
    }

    enum StatLayout {
        MACOS_64,
        LINUX_X86_64,
        LINUX_AARCH64
    }

    interface NativeAccess {
        static NativeAccess load(Path path) throws SecureDirectoryCapabilityException {
            try {
                return new JnaUnixNativeAccess(path);
            } catch (LinkageError | RuntimeException e) {
                throw new SecureDirectoryCapabilityException(path,
                        "JNA/libc anchored-read primitives are unavailable", e); //$NON-NLS-1$
            }
        }

        int open(String path, int flags) throws IOException;

        int openat(int directoryFd, String name, int flags) throws IOException;

        long read(int fd, byte[] buffer, long count) throws IOException;

        NativeFileIdentity fstat(int fd, PlatformConstants platform) throws IOException;

        void close(int fd) throws IOException;
    }

    static final class NativeIOException extends IOException {
        private static final long serialVersionUID = 1L;
        private final String operation;
        private final int errorCode;

        NativeIOException(String operation, int errorCode) {
            super(operation + " failed with errno " + errorCode); //$NON-NLS-1$
            this.operation = operation;
            this.errorCode = errorCode;
        }

        int errorCode() {
            return errorCode;
        }

        String operation() {
            return operation;
        }

        IOException withPath(Path path) {
            return new IOException(path + ": " + operation + " failed with errno " + errorCode, //$NON-NLS-1$ //$NON-NLS-2$
                    this);
        }
    }
}
