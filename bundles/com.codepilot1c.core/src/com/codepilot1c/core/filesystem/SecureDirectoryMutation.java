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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Performs filesystem mutations exclusively through stable, relative directory handles.
 *
 * <p>Java 17 exposes that primitive only through {@link SecureDirectoryStream}. Providers that do
 * not implement it fail closed before the first mutation. There is deliberately no pathname
 * fallback: validation followed by {@code FileChannel.open}, {@code Files.move}, or
 * {@code Files.delete} cannot prevent an ancestry swap. Java 17 also has no portable
 * {@code mkdirat}; every directory on the path to a mutation must therefore already exist.</p>
 */
public final class SecureDirectoryMutation implements AutoCloseable {

    /** Explicit capability policy, including a deterministic test-only fail-closed mode. */
    public enum CapabilityPolicy {
        REQUIRE_SECURE,
        FORCE_NON_SECURE_FOR_TESTS
    }

    /** Test seam invoked immediately before named binding and mutation boundaries. */
    @FunctionalInterface
    public interface MutationHook {
        void beforeMutation(String operation) throws IOException;
    }

    private static final MutationHook NOOP_HOOK = operation -> {
        // no-op
    };

    private final BoundRoot root;
    private final Path logicalDirectory;
    private final List<DirectoryStream<Path>> openedDirectories;
    private final SecureDirectoryStream<Path> directory;
    private final List<PathIdentity> ancestry;
    private final Object directoryKey;
    private final MutationHook hook;

    private SecureDirectoryMutation(BoundRoot root, Path logicalDirectory,
            List<DirectoryStream<Path>> openedDirectories,
            SecureDirectoryStream<Path> directory, List<PathIdentity> ancestry,
            Object directoryKey, MutationHook hook) {
        this.root = root;
        this.logicalDirectory = logicalDirectory;
        this.openedDirectories = openedDirectories;
        this.directory = directory;
        this.ancestry = ancestry;
        this.directoryKey = directoryKey;
        this.hook = hook == null ? NOOP_HOOK : hook;
    }

    /** Captures the stable identity of a mutation root without mutating it. */
    public static BoundRoot bindRoot(Path root) throws IOException {
        return bindRoot(root, CapabilityPolicy.REQUIRE_SECURE);
    }

    /** Captures a root with an explicit capability policy. Capability is checked on open. */
    public static BoundRoot bindRoot(Path root, CapabilityPolicy policy) throws IOException {
        return BoundRoot.capture(root, policy);
    }

    /**
     * Binds {@code project} as a physical descendant of {@code workspace}. The workspace identity
     * is retained in the result, so a later project-root bind cannot accept an escaped replacement.
     */
    public static BoundRoot bindRoot(Path workspace, Path project, MutationHook hook,
            CapabilityPolicy policy, String operation) throws IOException {
        return BoundRoot.captureWithin(workspace, project, hook, policy, operation);
    }

    /** Returns whether the provider currently exposes a real secure directory stream. */
    public static boolean supportsSecureDirectoryStreams(Path directory) throws IOException {
        try (DirectoryStream<Path> opened = Files.newDirectoryStream(
                Objects.requireNonNull(directory, "directory"))) { //$NON-NLS-1$
            return opened instanceof SecureDirectoryStream<?>;
        }
    }

    /**
     * Verifies that the bound root still has a real secure directory stream.
     * This check is read-only and is used to order bootstrap errors before any
     * mutation directory precreation guidance is returned.
     *
     * @param root the already-bound mutation root
     * @throws IOException if the root changed or the provider lacks the capability
     */
    public static void requireSecureDirectoryStreams(BoundRoot root) throws IOException {
        Objects.requireNonNull(root, "root"); //$NON-NLS-1$
        List<DirectoryStream<Path>> opened = new ArrayList<>();
        try {
            openSecureRoot(root, opened);
        } catch (IOException e) {
            closeReverse(opened, e);
            throw e;
        }
        IOException closeFailure = null;
        List<DirectoryStream<Path>> reverse = new ArrayList<>(opened);
        Collections.reverse(reverse);
        for (DirectoryStream<Path> stream : reverse) {
            try {
                stream.close();
            } catch (IOException e) {
                if (closeFailure == null) {
                    closeFailure = e;
                } else {
                    closeFailure.addSuppressed(e);
                }
            }
        }
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    /** Opens an existing directory below an already-bound root. */
    public static SecureDirectoryMutation open(BoundRoot root, Path directory,
            MutationHook hook) throws IOException {
        return open(root, directory, hook, "directory-bind"); //$NON-NLS-1$
    }

    /** Opens an existing directory and exposes a deterministic pre-bind hook. */
    public static SecureDirectoryMutation open(BoundRoot root, Path directory,
            MutationHook hook, String bindOperation) throws IOException {
        Objects.requireNonNull(root, "root"); //$NON-NLS-1$
        Path logical = Objects.requireNonNull(directory, "directory") //$NON-NLS-1$
                .toAbsolutePath().normalize();
        if (!logical.startsWith(root.logicalRoot) || logical.equals(root.logicalRoot)) {
            throw denied(logical, "mutation directory is outside the bound root"); //$NON-NLS-1$
        }

        MutationHook effectiveHook = hook == null ? NOOP_HOOK : hook;
        root.verifyCurrent();
        effectiveHook.beforeMutation(bindOperation);

        List<DirectoryStream<Path>> opened = new ArrayList<>();
        try {
            SecureDirectoryStream<Path> current = openSecureRoot(root, opened);
            List<PathIdentity> identities = new ArrayList<>();
            Path currentLogical = root.logicalRoot;
            for (Path segment : root.logicalRoot.relativize(logical)) {
                requireSimpleName(segment.toString());
                DirectoryStream<Path> child;
                try {
                    child = current.newDirectoryStream(segment, LinkOption.NOFOLLOW_LINKS);
                } catch (NoSuchFileException e) {
                    throw new SecureDirectoryCapabilityException(logical,
                            "secure directory creation is unavailable on Java 17; " //$NON-NLS-1$
                                    + "the mutation directory must already exist", e); //$NON-NLS-1$
                }
                opened.add(child);
                current = requireSecure(child, currentLogical.resolve(segment), root.policy);
                currentLogical = currentLogical.resolve(segment);
                BasicFileAttributes anchored = anchoredAttributes(current);
                requireDirectory(currentLogical, anchored);
                Object anchoredKey = requireFileKey(currentLogical, anchored);
                PathIdentity visible = PathIdentity.captureDirectory(currentLogical);
                if (!Objects.equals(anchoredKey, visible.followedKey)) {
                    throw denied(currentLogical, "path ancestry changed during anchored binding"); //$NON-NLS-1$
                }
                if (!visible.followedRealPath.startsWith(root.realRoot)) {
                    throw denied(currentLogical, "mutation directory escaped the bound root"); //$NON-NLS-1$
                }
                identities.add(visible);
            }
            Object key = requireFileKey(logical, anchoredAttributes(current));
            SecureDirectoryMutation result = new SecureDirectoryMutation(root, logical,
                    List.copyOf(opened), current, List.copyOf(identities), key, effectiveHook);
            result.validateCurrent();
            return result;
        } catch (IOException | RuntimeException e) {
            closeReverse(opened, e);
            throw e;
        }
    }

    /** Stable key suitable for in-process lock maps. */
    public Object identityKey() {
        return directoryKey;
    }

    /** Revalidates visible ancestry while retaining the open anchored target. */
    public void validateCurrent() throws IOException {
        root.verifyCurrent();
        for (PathIdentity identity : ancestry) {
            identity.verify();
        }
        BasicFileAttributes anchored = anchoredAttributes(directory);
        if (!Objects.equals(directoryKey, requireFileKey(logicalDirectory, anchored))) {
            throw denied(logicalDirectory, "anchored mutation directory identity changed"); //$NON-NLS-1$
        }
        Path real = logicalDirectory.toRealPath();
        if (!real.startsWith(root.realRoot)) {
            throw denied(logicalDirectory, "mutation directory escaped the bound root"); //$NON-NLS-1$
        }
    }

    /** Invokes a deterministic hook and revalidates immediately before an operation. */
    public void beforeMutation(String operation) throws IOException {
        hook.beforeMutation(operation);
        validateCurrent();
    }

    /** Opens a file relative to the anchored directory without following a final symlink. */
    public FileChannel openFileChannel(String fileName, Set<? extends OpenOption> options,
            String operation) throws IOException {
        requireSimpleName(fileName);
        beforeMutation(operation);
        hook.beforeMutation(operation + ":open"); //$NON-NLS-1$
        validateCurrent();
        List<OpenOption> secureOptions = new ArrayList<>(options);
        secureOptions.add(LinkOption.NOFOLLOW_LINKS);
        SeekableByteChannel channel = directory.newByteChannel(
                Path.of(fileName), Set.copyOf(secureOptions));
        if (channel instanceof FileChannel fileChannel) {
            return fileChannel;
        }
        channel.close();
        throw denied(logicalDirectory.resolve(fileName),
                "filesystem provider did not expose a lockable/durable file channel"); //$NON-NLS-1$
    }

    public boolean exists(String fileName) throws IOException {
        requireSimpleName(fileName);
        validateCurrent();
        try {
            directory.getFileAttributeView(Path.of(fileName),
                    BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                    .readAttributes();
            return true;
        } catch (NoSuchFileException e) {
            return false;
        }
    }

    public byte[] readAllBytes(String fileName) throws IOException {
        requireSimpleName(fileName);
        validateCurrent();
        try (SeekableByteChannel channel = directory.newByteChannel(Path.of(fileName),
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

    /** Writes and force-syncs a temp file, then publishes it by an anchored relative rename. */
    public void atomicWrite(String fileName, byte[] bytes, String operation) throws IOException {
        requireSimpleName(fileName);
        Objects.requireNonNull(bytes, "bytes"); //$NON-NLS-1$
        beforeMutation(operation);
        hook.beforeMutation(operation + ":temp-create"); //$NON-NLS-1$
        validateCurrent();
        Path temp = Path.of("." + fileName + "-" + UUID.randomUUID() + ".tmp"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        boolean created = false;
        boolean moved = false;
        try {
            try (SeekableByteChannel raw = directory.newByteChannel(temp,
                    Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW,
                            LinkOption.NOFOLLOW_LINKS))) {
                created = true;
                if (!(raw instanceof FileChannel channel)) {
                    throw denied(logicalDirectory.resolve(temp),
                            "filesystem provider did not expose a durable file channel"); //$NON-NLS-1$
                }
                writeAll(channel, bytes);
                channel.force(true);
            }
            hook.beforeMutation(operation + ":publish"); //$NON-NLS-1$
            validateCurrent();
            directory.move(temp, directory, Path.of(fileName));
            moved = true;
        } finally {
            if (created && !moved) {
                try {
                    hook.beforeMutation(operation + ":cleanup"); //$NON-NLS-1$
                } catch (IOException ignored) {
                    // Cleanup remains safe because it is relative to the already-open handle.
                }
                try {
                    directory.deleteFile(temp);
                } catch (IOException ignored) {
                    // best-effort cleanup in the already-bound directory
                }
            }
        }
    }

    /** Renames a file within the anchored directory. */
    public void move(String source, String target, String operation) throws IOException {
        requireSimpleName(source);
        requireSimpleName(target);
        beforeMutation(operation);
        hook.beforeMutation(operation + ":move"); //$NON-NLS-1$
        validateCurrent();
        directory.move(Path.of(source), directory, Path.of(target));
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        List<DirectoryStream<Path>> reverse = new ArrayList<>(openedDirectories);
        Collections.reverse(reverse);
        for (DirectoryStream<Path> opened : reverse) {
            try {
                opened.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static SecureDirectoryStream<Path> openSecureRoot(BoundRoot root,
            List<DirectoryStream<Path>> opened) throws IOException {
        DirectoryStream<Path> rootStream = Files.newDirectoryStream(root.logicalRoot);
        opened.add(rootStream);
        SecureDirectoryStream<Path> secure = requireSecure(
                rootStream, root.logicalRoot, root.policy);
        BasicFileAttributes anchored = anchoredAttributes(secure);
        Object anchoredKey = requireFileKey(root.logicalRoot, anchored);
        if (!Objects.equals(anchoredKey, root.identity.followedKey)) {
            throw denied(root.logicalRoot, "containment root changed before anchored binding"); //$NON-NLS-1$
        }
        root.verifyCurrent();
        return secure;
    }

    private static SecureDirectoryStream<Path> requireSecure(DirectoryStream<Path> opened,
            Path path, CapabilityPolicy policy) throws IOException {
        if (policy == CapabilityPolicy.FORCE_NON_SECURE_FOR_TESTS
                || !(opened instanceof SecureDirectoryStream<?>)) {
            throw new SecureDirectoryCapabilityException(path,
                    "filesystem provider lacks SecureDirectoryStream; mutation is disabled"); //$NON-NLS-1$
        }
        @SuppressWarnings("unchecked")
        SecureDirectoryStream<Path> secure = (SecureDirectoryStream<Path>) opened;
        return secure;
    }

    private static BasicFileAttributes anchoredAttributes(SecureDirectoryStream<Path> directory)
            throws IOException {
        return directory.getFileAttributeView(BasicFileAttributeView.class).readAttributes();
    }

    private static void requireDirectory(Path path, BasicFileAttributes attributes)
            throws IOException {
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            throw denied(path, "anchored path component is not a directory"); //$NON-NLS-1$
        }
    }

    private static void closeReverse(List<DirectoryStream<Path>> opened, Throwable failure) {
        List<DirectoryStream<Path>> reverse = new ArrayList<>(opened);
        Collections.reverse(reverse);
        for (DirectoryStream<Path> stream : reverse) {
            try {
                stream.close();
            } catch (IOException e) {
                failure.addSuppressed(e);
            }
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

    /** Root identity, including the expected workspace boundary when one was supplied. */
    public static final class BoundRoot {
        private final Path logicalRoot;
        private final Path realRoot;
        private final PathIdentity identity;
        private final PathIdentity workspaceBoundary;
        private final CapabilityPolicy policy;

        private BoundRoot(Path logicalRoot, Path realRoot, PathIdentity identity,
                PathIdentity workspaceBoundary, CapabilityPolicy policy) {
            this.logicalRoot = logicalRoot;
            this.realRoot = realRoot;
            this.identity = identity;
            this.workspaceBoundary = workspaceBoundary;
            this.policy = policy;
        }

        private static BoundRoot capture(Path root, CapabilityPolicy policy) throws IOException {
            Path logical = normalize(root, "root"); //$NON-NLS-1$
            PathIdentity identity = PathIdentity.captureDirectory(logical);
            identity.verify();
            return new BoundRoot(logical, identity.followedRealPath, identity, null,
                    Objects.requireNonNull(policy, "policy")); //$NON-NLS-1$
        }

        private static BoundRoot captureWithin(Path workspace, Path project,
                MutationHook hook, CapabilityPolicy policy, String operation) throws IOException {
            Path logicalWorkspace = normalize(workspace, "workspace"); //$NON-NLS-1$
            Path logicalProject = normalize(project, "project"); //$NON-NLS-1$
            if (logicalProject.equals(logicalWorkspace)
                    || !logicalProject.startsWith(logicalWorkspace)) {
                throw denied(logicalProject, "project is outside the workspace boundary"); //$NON-NLS-1$
            }
            PathIdentity workspaceIdentity = PathIdentity.captureDirectory(logicalWorkspace);
            MutationHook effectiveHook = hook == null ? NOOP_HOOK : hook;
            effectiveHook.beforeMutation(operation);

            BoundRoot workspaceRoot = new BoundRoot(logicalWorkspace,
                    workspaceIdentity.followedRealPath, workspaceIdentity, null,
                    Objects.requireNonNull(policy, "policy")); //$NON-NLS-1$
            List<DirectoryStream<Path>> opened = new ArrayList<>();
            IOException closeFailure = null;
            try {
                SecureDirectoryStream<Path> current = openSecureRoot(workspaceRoot, opened);
                Path currentLogical = logicalWorkspace;
                for (Path segment : logicalWorkspace.relativize(logicalProject)) {
                    DirectoryStream<Path> child = current.newDirectoryStream(
                            segment, LinkOption.NOFOLLOW_LINKS);
                    opened.add(child);
                    currentLogical = currentLogical.resolve(segment);
                    current = requireSecure(child, currentLogical, policy);
                }
                BasicFileAttributes anchored = anchoredAttributes(current);
                requireDirectory(logicalProject, anchored);
                Object anchoredKey = requireFileKey(logicalProject, anchored);
                PathIdentity projectIdentity = PathIdentity.captureDirectory(logicalProject);
                if (!Objects.equals(anchoredKey, projectIdentity.followedKey)
                        || !projectIdentity.followedRealPath.startsWith(
                                workspaceIdentity.followedRealPath)) {
                    throw denied(logicalProject,
                            "project changed or escaped during workspace-bound binding"); //$NON-NLS-1$
                }
                return new BoundRoot(logicalProject, projectIdentity.followedRealPath,
                        projectIdentity, workspaceIdentity, policy);
            } finally {
                List<DirectoryStream<Path>> reverse = new ArrayList<>(opened);
                Collections.reverse(reverse);
                for (DirectoryStream<Path> stream : reverse) {
                    try {
                        stream.close();
                    } catch (IOException e) {
                        if (closeFailure == null) {
                            closeFailure = e;
                        } else {
                            closeFailure.addSuppressed(e);
                        }
                    }
                }
                if (closeFailure != null) {
                    throw closeFailure;
                }
            }
        }

        public void verifyCurrent() throws IOException {
            if (workspaceBoundary != null) {
                workspaceBoundary.verify();
            }
            identity.verify();
            if (!logicalRoot.toRealPath().equals(realRoot)) {
                throw denied(logicalRoot, "containment root changed"); //$NON-NLS-1$
            }
            if (workspaceBoundary != null
                    && !realRoot.startsWith(workspaceBoundary.followedRealPath)) {
                throw denied(logicalRoot, "containment root escaped its workspace boundary"); //$NON-NLS-1$
            }
        }
    }

    private static Path normalize(Path path, String label) {
        return Objects.requireNonNull(path, label).toAbsolutePath().normalize();
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

        private static PathIdentity captureDirectory(Path path) throws IOException {
            BasicFileAttributes entry = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!entry.isDirectory() || entry.isSymbolicLink()) {
                throw denied(path, "directory identity cannot be a symbolic link"); //$NON-NLS-1$
            }
            BasicFileAttributes followed = Files.readAttributes(path, BasicFileAttributes.class);
            if (!followed.isDirectory()) {
                throw denied(path, "path is not a directory"); //$NON-NLS-1$
            }
            Path real = path.toRealPath();
            PathIdentity result = new PathIdentity(path, requireFileKey(path, entry),
                    requireFileKey(path, followed), real);
            result.verify();
            return result;
        }

        private void verify() throws IOException {
            BasicFileAttributes entry = Files.readAttributes(
                    logicalPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            BasicFileAttributes followed = Files.readAttributes(
                    logicalPath, BasicFileAttributes.class);
            if (entry.isSymbolicLink()
                    || !Objects.equals(entryKey, requireFileKey(logicalPath, entry))
                    || !Objects.equals(followedKey, requireFileKey(logicalPath, followed))
                    || !followedRealPath.equals(logicalPath.toRealPath())) {
                throw denied(logicalPath, "path ancestry changed before mutation"); //$NON-NLS-1$
            }
        }
    }
}
