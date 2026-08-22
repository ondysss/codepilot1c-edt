/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.gsd;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Pure, thread-safe coordinator that prevents stale async refresh results from
 * overwriting fresh data when the user switches projects or triggers rapid
 * refreshes.
 *
 * <h2>Design</h2>
 * <p>{@link #setProjectRoot(Path)} is the <b>only</b> mutator of the stored project
 * root. {@link #beginRefresh()} atomically reads the current root and bumps the
 * generation counter, returning an immutable {@link RefreshToken} that the async
 * operation captures. When the result arrives, {@link #shouldApply(RefreshToken)}
 * checks whether the token still matches the current coordinator state.</p>
 *
 * <h2>Thread safety</h2>
 * <p>All public methods are {@code synchronized} on the coordinator instance, so
 * the read-bump-return in {@code beginRefresh} and the check in {@code shouldApply}
 * are atomic with respect to {@code setProjectRoot}. No {@link java.util.concurrent.atomic}
 * types are needed — the monitor guarantees mutual exclusion.</p>
 *
 * <p>This class has no SWT, Eclipse, or I/O dependencies — it is trivially
 * unit-testable in plain JUnit.</p>
 */
public final class GsdRefreshCoordinator {

    /**
     * Immutable token capturing the generation and project root at the moment
     * a refresh was initiated.
     *
     * @param generation  the generation counter at refresh start
     * @param projectRoot the project root used for this refresh
     */
    public record RefreshToken(long generation, Path projectRoot) {

        public RefreshToken {
            Objects.requireNonNull(projectRoot, "projectRoot"); //$NON-NLS-1$
        }
    }

    private long generation;
    private Path currentProjectRoot;
    private boolean disposed;

    /**
     * Sets the project root and bumps the generation counter.
     *
     * <p>This is the <b>only</b> mutator of the stored project root. Bumping
     * the generation ensures that any in-flight refresh results captured under
     * the old root are rejected by {@link #shouldApply(RefreshToken)}.</p>
     *
     * @param projectRoot the new root; may be {@code null} (no project bound)
     */
    public synchronized void setProjectRoot(Path projectRoot) {
        if (disposed) {
            return;
        }
        this.currentProjectRoot = projectRoot;
        this.generation++;
    }

    /**
     * Atomically reads the current project root, bumps the generation counter,
     * and returns a {@link RefreshToken} capturing both values.
     *
     * <p>Must be called <b>before</b> launching the async read. Because this
     * method is {@code synchronized}, a concurrent {@link #setProjectRoot(Path)}
     * cannot interleave between the read and the bump — the caller either sees
     * the old root at the old generation (and the setter will bump past it) or
     * the new root at the new generation.</p>
     *
     * <p>Bumping the generation here also invalidates any previously issued
     * token, so if two refreshes are in flight simultaneously only the latest
     * one will pass {@link #shouldApply(RefreshToken)}.</p>
     *
     * @return a token, or {@code null} if no project root is currently set
     */
    public synchronized RefreshToken beginRefresh() {
        if (disposed || this.currentProjectRoot == null) {
            return null;
        }
        this.generation++;
        return new RefreshToken(this.generation, this.currentProjectRoot);
    }

    /**
     * Returns {@code true} if the given token's generation and project root
     * still match the current coordinator state, meaning the snapshot is fresh
     * and should be applied.
     *
     * @param token the token from {@link #beginRefresh()}; must not be {@code null}
     * @return {@code true} if the result is still current
     */
    public synchronized boolean shouldApply(RefreshToken token) {
        Objects.requireNonNull(token, "token"); //$NON-NLS-1$
        return !disposed
                && token.generation == this.generation
                && rootsEqual(this.currentProjectRoot, token.projectRoot);
    }

    /**
     * Invalidates all in-flight work and permanently closes this coordinator.
     * Repeated disposal is safe.
     */
    public synchronized void dispose() {
        if (!disposed) {
            disposed = true;
            currentProjectRoot = null;
            generation++;
        }
    }

    /** @return whether this coordinator has been disposed */
    public synchronized boolean isDisposed() {
        return disposed;
    }

    /**
     * Returns the current generation value. Useful for tests and diagnostics.
     *
     * @return the current generation counter
     */
    public synchronized long currentGeneration() {
        return this.generation;
    }

    /**
     * Returns the currently stored project root.
     *
     * @return the current root, or {@code null}
     */
    public synchronized Path currentProjectRoot() {
        return this.currentProjectRoot;
    }

    private static boolean rootsEqual(Path a, Path b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.equals(b);
    }
}
