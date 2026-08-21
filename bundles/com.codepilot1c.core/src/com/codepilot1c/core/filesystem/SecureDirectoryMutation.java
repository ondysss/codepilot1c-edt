/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.filesystem;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Binds filesystem validation to an open directory handle and, when the provider supports it,
 * performs relative mutations through {@link SecureDirectoryStream}. The secure handle remains
 * attached to the validated directory when a pathname ancestor is concurrently renamed or
 * replaced with a symlink.
 *
 * <p>Java 17 has no portable {@code mkdirat} API. Callers may create missing directories before
 * opening this guard. A provider without {@link SecureDirectoryStream} uses the narrow Java 17
 * fallback: stable ancestry identity is checked immediately at every pathname mutation boundary
 * and again before atomic publication. Stable file identity is mandatory in either mode.</p>
 */
public final class SecureDirectoryMutation implements AutoCloseable {

    /** Test seam invoked after initial validation and before boundary revalidation. */
    @FunctionalInterface
    public interface MutationHook {
        void beforeMutation(String operation) throws IOException;
    }

    private static final MutationHook NOOP_HOOK = operation -> {
        // no-op
    };

    private final BoundRoot root;
    private final Path logicalDirectory;
    private final DirectoryStream<Path> openedDirectory;
    private final SecureDirectoryStream<Path> directory;
    private final List<PathIdentity> ancestry;
    private final Object directoryKey;
    private final MutationHook hook;

    private SecureDirectoryMutation(BoundRoot root, Path logicalDirectory,
            DirectoryStream<Path> openedDirectory, SecureDirectoryStream<Path> directory,
            List<PathIdentity> ancestry,
            Object directoryKey, MutationHook hook) {
        this.root = root;
        this.logicalDirectory = logicalDirectory;
        this.openedDirectory = openedDirectory;
        this.directory = directory;
        this.ancestry = ancestry;
        this.directoryKey = directoryKey;
        this.hook = hook == null ? NOOP_HOOK : hook;
    }

    /** Captures the real path and stable identity of a containment root. */
    public static BoundRoot bindRoot(Path root) throws IOException {
        return BoundRoot.capture(root);
    }

    /** Opens and validates {@code directory} below an already-bound containment root. */
    public static SecureDirectoryMutation open(BoundRoot root, Path directory,
            MutationHook hook) throws IOException {
        Objects.requireNonNull(root, "root"); //$NON-NLS-1$
        Path logical = Objects.requireNonNull(directory, "directory") //$NON-NLS-1$
                .toAbsolutePath().normalize();
        root.verifyCurrent();
        if (!logical.startsWith(root.logicalRoot) || logical.equals(root.logicalRoot)) {
            throw denied(logical, "mutation directory is outside the bound root"); //$NON-NLS-1$
        }

        DirectoryStream<Path> opened = Files.newDirectoryStream(logical);
        @SuppressWarnings("unchecked")
        SecureDirectoryStream<Path> secure = opened instanceof SecureDirectoryStream<?>
                ? (SecureDirectoryStream<Path>) opened : null;
        try {
            BasicFileAttributes anchored = secure != null
                    ? secure.getFileAttributeView(BasicFileAttributeView.class).readAttributes()
                    : Files.readAttributes(logical, BasicFileAttributes.class);
            Object key = requireFileKey(logical, anchored);
            List<PathIdentity> identities = captureAncestry(root, logical);
            SecureDirectoryMutation result = new SecureDirectoryMutation(
                    root, logical, opened, secure, identities, key, hook);
            result.validateCurrent();
            return result;
        } catch (IOException | RuntimeException e) {
            opened.close();
            throw e;
        }
    }

    /** Stable key suitable for in-process lock maps. */
    public Object identityKey() {
        return directoryKey;
    }

    /** Revalidates every lexical ancestor and the open directory's stable identity. */
    public void validateCurrent() throws IOException {
        root.verifyCurrent();
        for (PathIdentity identity : ancestry) {
            identity.verify();
        }
        Path real = logicalDirectory.toRealPath();
        if (!real.startsWith(root.realRoot)) {
            throw denied(logicalDirectory, "mutation directory escaped the bound root"); //$NON-NLS-1$
        }
        BasicFileAttributes current = Files.readAttributes(
                real, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!Objects.equals(directoryKey, requireFileKey(real, current))) {
            throw denied(logicalDirectory, "mutation directory identity changed"); //$NON-NLS-1$
        }
    }

    /** Invokes the deterministic hook and revalidates at the mutation boundary. */
    public void beforeMutation(String operation) throws IOException {
        hook.beforeMutation(operation);
        validateCurrent();
    }

    /** Opens a regular file relative to the bound directory without following a final symlink. */
    public FileChannel openFileChannel(String fileName, Set<? extends OpenOption> options,
            String operation) throws IOException {
        requireSimpleName(fileName);
        beforeMutation(operation);
        List<OpenOption> secureOptions = new ArrayList<>(options);
        secureOptions.add(LinkOption.NOFOLLOW_LINKS);
        SeekableByteChannel channel = directory != null
                ? directory.newByteChannel(Path.of(fileName), Set.copyOf(secureOptions))
                : FileChannel.open(logicalDirectory.resolve(fileName), Set.copyOf(secureOptions));
        if (channel instanceof FileChannel fileChannel) {
            return fileChannel;
        }
        channel.close();
        throw denied(logicalDirectory.resolve(fileName),
                "filesystem provider did not expose a lockable/durable file channel"); //$NON-NLS-1$
    }

    public boolean exists(String fileName) throws IOException {
        requireSimpleName(fileName);
        try {
            if (directory != null) {
                directory.getFileAttributeView(Path.of(fileName),
                        BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                        .readAttributes();
            } else {
                Files.readAttributes(logicalDirectory.resolve(fileName),
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            }
            return true;
        } catch (NoSuchFileException e) {
            return false;
        }
    }

    public byte[] readAllBytes(String fileName) throws IOException {
        requireSimpleName(fileName);
        validateCurrent();
        try (SeekableByteChannel channel = directory != null
                ? directory.newByteChannel(Path.of(fileName),
                        Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
                : FileChannel.open(logicalDirectory.resolve(fileName),
                        Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                output.write(buffer.array(), 0, buffer.remaining());
                buffer.clear();
            }
            return output.toByteArray();
        }
    }

    /** Writes and force-syncs a temp file, then publishes it with a handle-relative rename. */
    public void atomicWrite(String fileName, byte[] bytes, String operation) throws IOException {
        requireSimpleName(fileName);
        Objects.requireNonNull(bytes, "bytes"); //$NON-NLS-1$
        beforeMutation(operation);
        Path temp = Path.of("." + fileName + "-" + UUID.randomUUID() + ".tmp"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        Path fallbackTemp = directory == null ? logicalDirectory.resolve(temp) : null;
        boolean moved = false;
        try {
            try (SeekableByteChannel raw = directory != null
                    ? directory.newByteChannel(temp,
                            Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW,
                                    LinkOption.NOFOLLOW_LINKS))
                    : FileChannel.open(fallbackTemp,
                            Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW,
                                    LinkOption.NOFOLLOW_LINKS))) {
                if (!(raw instanceof FileChannel channel)) {
                    throw denied(logicalDirectory.resolve(temp),
                            "filesystem provider did not expose a durable file channel"); //$NON-NLS-1$
                }
                writeAll(channel, bytes);
                channel.force(true);
            }
            // Revalidate the visible ancestry immediately before publication. With a secure
            // provider, move() still targets the already-open directory handle after this check.
            validateCurrent();
            if (directory != null) {
                directory.move(temp, directory, Path.of(fileName));
            } else {
                moveFallback(fallbackTemp, logicalDirectory.resolve(fileName));
            }
            moved = true;
            syncDirectoryBestEffort();
        } finally {
            if (!moved) {
                try {
                    if (directory != null) {
                        directory.deleteFile(temp);
                    } else {
                        // Never let best-effort cleanup follow ancestry that changed after the
                        // guarded write. Leaving an unpublished temp in the original directory is
                        // safer than deleting through an attacker-controlled replacement path.
                        validateCurrent();
                        Files.deleteIfExists(fallbackTemp);
                    }
                } catch (IOException ignored) {
                    // best-effort cleanup in the already-bound directory
                }
            }
        }
    }

    /** Renames a file within the bound directory without following pathname ancestry. */
    public void move(String source, String target, String operation) throws IOException {
        requireSimpleName(source);
        requireSimpleName(target);
        beforeMutation(operation);
        if (directory != null) {
            directory.move(Path.of(source), directory, Path.of(target));
        } else {
            moveFallback(logicalDirectory.resolve(source), logicalDirectory.resolve(target));
        }
        syncDirectoryBestEffort();
    }

    @Override
    public void close() throws IOException {
        openedDirectory.close();
    }

    private static void moveFallback(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void syncDirectoryBestEffort() {
        try {
            validateCurrent();
            try (FileChannel channel = FileChannel.open(logicalDirectory, StandardOpenOption.READ)) {
                channel.force(true);
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // Rename durability is provider/filesystem dependent; publication remains atomic.
        }
    }

    private static void writeAll(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            int written = channel.write(buffer);
            if (written < 0) {
                throw new IOException("unexpected end of file channel"); //$NON-NLS-1$
            }
        }
    }

    private static List<PathIdentity> captureAncestry(BoundRoot root, Path directory)
            throws IOException {
        List<PathIdentity> result = new ArrayList<>();
        Path relative = root.logicalRoot.relativize(directory);
        Path current = root.logicalRoot;
        for (Path segment : relative) {
            current = current.resolve(segment);
            result.add(PathIdentity.capture(current));
        }
        return List.copyOf(result);
    }

    private static void requireSimpleName(String fileName) {
        Path path = Path.of(Objects.requireNonNull(fileName, "fileName")); //$NON-NLS-1$
        if (path.isAbsolute() || path.getNameCount() != 1
                || ".".equals(fileName) || "..".equals(fileName)) { //$NON-NLS-1$ //$NON-NLS-2$
            throw new IllegalArgumentException("file name must be one relative path segment"); //$NON-NLS-1$
        }
    }

    private static Object requireFileKey(Path path, BasicFileAttributes attributes)
            throws IOException {
        Object key = attributes.fileKey();
        if (key == null) {
            throw denied(path, "filesystem does not expose stable file identity"); //$NON-NLS-1$
        }
        return key;
    }

    private static AccessDeniedException denied(Path path, String reason) {
        return new AccessDeniedException(path.toString(), null, reason);
    }

    /** Root identity captured before any mutable descendant is trusted. */
    public static final class BoundRoot {
        private final Path logicalRoot;
        private final Path realRoot;
        private final PathIdentity identity;

        private BoundRoot(Path logicalRoot, Path realRoot, PathIdentity identity) {
            this.logicalRoot = logicalRoot;
            this.realRoot = realRoot;
            this.identity = identity;
        }

        private static BoundRoot capture(Path root) throws IOException {
            Path logical = Objects.requireNonNull(root, "root").toAbsolutePath().normalize(); //$NON-NLS-1$
            Path real = logical.toRealPath();
            PathIdentity identity = PathIdentity.capture(logical);
            if (!identity.followedRealPath.equals(real)) {
                throw denied(logical, "containment root identity is inconsistent"); //$NON-NLS-1$
            }
            return new BoundRoot(logical, real, identity);
        }

        public void verifyCurrent() throws IOException {
            identity.verify();
            if (!logicalRoot.toRealPath().equals(realRoot)) {
                throw denied(logicalRoot, "containment root changed"); //$NON-NLS-1$
            }
        }
    }

    private static final class PathIdentity {
        private final Path logicalPath;
        private final Object entryKey;
        private final Object followedKey;
        private final Path followedRealPath;

        private PathIdentity(Path logicalPath, Object entryKey, Object followedKey,
                Path followedRealPath) {
            this.logicalPath = logicalPath;
            this.entryKey = entryKey;
            this.followedKey = followedKey;
            this.followedRealPath = followedRealPath;
        }

        private static PathIdentity capture(Path path) throws IOException {
            BasicFileAttributes entry = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            BasicFileAttributes followed = Files.readAttributes(path, BasicFileAttributes.class);
            return new PathIdentity(path, requireFileKey(path, entry),
                    requireFileKey(path, followed), path.toRealPath());
        }

        private void verify() throws IOException {
            BasicFileAttributes entry = Files.readAttributes(
                    logicalPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            BasicFileAttributes followed = Files.readAttributes(
                    logicalPath, BasicFileAttributes.class);
            if (!Objects.equals(entryKey, requireFileKey(logicalPath, entry))
                    || !Objects.equals(followedKey, requireFileKey(logicalPath, followed))
                    || !followedRealPath.equals(logicalPath.toRealPath())) {
                throw denied(logicalPath, "path ancestry changed before mutation"); //$NON-NLS-1$
            }
        }
    }
}
