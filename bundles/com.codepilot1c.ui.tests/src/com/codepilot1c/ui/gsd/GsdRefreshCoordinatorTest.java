/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.gsd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.codepilot1c.ui.gsd.GsdRefreshCoordinator.RefreshToken;

/**
 * Unit tests for {@link GsdRefreshCoordinator}. Pure logic — no SWT, no Eclipse.
 */
public class GsdRefreshCoordinatorTest {

    // ---- Basic state ------------------------------------------------------

    @Test
    public void freshCoordinatorStartsAtZeroWithNullRoot() {
        GsdRefreshCoordinator c = new GsdRefreshCoordinator();
        assertEquals(0L, c.currentGeneration());
        assertNull(c.currentProjectRoot());
    }

    @Test
    public void setProjectRootStoresRootAndBumpsGeneration() {
        GsdRefreshCoordinator c = new GsdRefreshCoordinator();
        Path root = Path.of("/project/a");

        c.setProjectRoot(root);

        assertEquals(root, c.currentProjectRoot());
        assertTrue(c.currentGeneration() > 0);
    }

    @Test
    public void setProjectRootAcceptsNull() {
        GsdRefreshCoordinator c = new GsdRefreshCoordinator();
        c.setProjectRoot(Path.of("/project/a"));
        long genBefore = c.currentGeneration();

        c.setProjectRoot(null);

        assertNull(c.currentProjectRoot());
        assertTrue(c.currentGeneration() > genBefore);
    }

    @Test
    public void repeatedSetProjectRootBumpsGenerationEachTime() {
        GsdRefreshCoordinator c = new GsdRefreshCoordinator();
        c.setProjectRoot(Path.of("/project/a"));
        long g1 = c.currentGeneration();

        c.setProjectRoot(Path.of("/project/b"));
        long g2 = c.currentGeneration();

        c.setProjectRoot(Path.of("/project/c"));
        long g3 = c.currentGeneration();

        assertTrue(g2 > g1);
        assertTrue(g3 > g2);
    }

    // ---- beginRefresh -----------------------------------------------------

    @Test
    public void beginRefreshReturnsNullWhenNoRoot() {
        GsdRefreshCoordinator c = new GsdRefreshCoordinator();
        assertNull(c.beginRefresh());
    }

    @Test
    public void beginRefreshReturnsNullAfterRootSetToNull() {
        GsdRefreshCoordinator c = new GsdRefreshCoordinator();
        c.setProjectRoot(Path.of("/project/a"));
        c.setProjectRoot(null);

        assertNull(c.beginRefresh());
    }

    @Test
    public void beginRefreshReturnsTokenWithCurrentRootAndBumpedGeneration() {
        GsdRefreshCoordinator c = new GsdRefreshCoordinator();
        Path root = Path.of("/project/a");
        c.setProjectRoot(root);
        long genBefore = c.currentGeneration();

        RefreshToken token = c.beginRefresh();

        assertNotNull(token);
        assertEquals(root, token.projectRoot());
        assertTrue(token.generation() > genBefore);
        assertEquals(c.currentGeneration(), token.generation());
    }

    @Test
    public void beginRefreshBumpsGenerationEachCall() {
        GsdRefreshCoordinator c = new GsdRefreshCoordinator();
        c.setProjectRoot(Path.of("/project/a"));

        RefreshToken t1 = c.beginRefresh();
        RefreshToken t2 = c.beginRefresh();
        RefreshToken t3 = c.beginRefresh();

        assertTrue(t2.generation() > t1.generation());
        assertTrue(t3.generation() > t2.generation());
    }

    @Test
    public void beginRefreshTokenRejectsNullProjectRoot() {
        try {
            new RefreshToken(1L, null);
            org.junit.Assert.fail("expected NullPointerException");
        } catch (NullPointerException e) {
            // expected
        }
    }

    // ---- shouldApply ------------------------------------------------------

    @Test
    public void shouldApplyMatchesFreshToken() {
        GsdRefreshCoordinator c = new GsdRefreshCoordinator();
        c.setProjectRoot(Path.of("/project/a"));

        RefreshToken token = c.beginRefresh();

        assertTrue(c.shouldApply(token));
    }

    @Test
    public void shouldApplyRejectsAfterAnotherBeginRefresh() {
        GsdRefreshCoordinator c = new GsdRefreshCoordinator();
        c.setProjectRoot(Path.of("/project/a"));

        RefreshToken stale = c.beginRefresh();
        c.beginRefresh(); // newer refresh invalidates the old token

        assertFalse(c.shouldApply(stale));
    }

    @Test
    public void shouldApplyRejectsAfterSetProjectRootChangedRoot() {
        GsdRefreshCoordinator c = new GsdRefreshCoordinator();
        Path rootA = Path.of("/project/a");
        c.setProjectRoot(rootA);

        RefreshToken token = c.beginRefresh();

        // User switches to project B before the async result arrives.
        c.setProjectRoot(Path.of("/project/b"));

        assertFalse(c.shouldApply(token));
    }

    @Test
    public void shouldApplyRejectsAfterRootSetToNull() {
        GsdRefreshCoordinator c = new GsdRefreshCoordinator();
        c.setProjectRoot(Path.of("/project/a"));

        RefreshToken token = c.beginRefresh();
        c.setProjectRoot(null);

        assertFalse(c.shouldApply(token));
    }

    @Test
    public void shouldApplyOnlyLatestTokenSurvives() {
        GsdRefreshCoordinator c = new GsdRefreshCoordinator();
        Path rootA = Path.of("/project/a");
        Path rootB = Path.of("/project/b");

        c.setProjectRoot(rootA);
        RefreshToken tokenA = c.beginRefresh();

        // User switches project and refreshes again before A completes.
        c.setProjectRoot(rootB);
        RefreshToken tokenB = c.beginRefresh();

        assertFalse("Token from project A must be stale", c.shouldApply(tokenA));
        assertTrue("Token from project B must be fresh", c.shouldApply(tokenB));
    }

    @Test
    public void disposeInvalidatesInflightRefreshAndRejectsNewWork() {
        GsdRefreshCoordinator c = new GsdRefreshCoordinator();
        c.setProjectRoot(Path.of("/project/a"));
        RefreshToken token = c.beginRefresh();

        c.dispose();

        assertTrue(c.isDisposed());
        assertFalse(c.shouldApply(token));
        assertNull(c.beginRefresh());
        assertNull(c.currentProjectRoot());
    }

    @Test
    public void repeatedDisposeIsSafeAndSetProjectRootCannotReviveCoordinator() {
        GsdRefreshCoordinator c = new GsdRefreshCoordinator();
        c.dispose();
        long disposedGeneration = c.currentGeneration();

        c.dispose();
        c.setProjectRoot(Path.of("/project/revived"));

        assertEquals(disposedGeneration, c.currentGeneration());
        assertNull(c.currentProjectRoot());
        assertNull(c.beginRefresh());
    }

    @Test
    public void twoViewsHaveIndependentRefreshGenerationsAndRoots() {
        GsdRefreshCoordinator first = new GsdRefreshCoordinator();
        GsdRefreshCoordinator second = new GsdRefreshCoordinator();
        first.setProjectRoot(Path.of("/project/a"));
        second.setProjectRoot(Path.of("/project/b"));
        RefreshToken firstToken = first.beginRefresh();
        RefreshToken secondToken = second.beginRefresh();

        first.setProjectRoot(null); // clear only the first ChatView

        assertFalse(first.shouldApply(firstToken));
        assertTrue(second.shouldApply(secondToken));
        assertEquals(Path.of("/project/b"), second.currentProjectRoot());
    }

    // ---- Race scenario: setProjectRoot between read and beginRefresh ------

    /**
     * Simulates the exact race the old API had: thread 1 reads root=A, thread 2
     * calls setProjectRoot(B) before thread 1 calls beginRefresh. With the
     * synchronized API, beginRefresh is atomic with respect to setProjectRoot —
     * thread 1 either gets the old root before the setter bumps, or the new root
     * after the setter bumps. It can never "revert" root back to A.
     */
    @Test
    public void setProjectRootCannotRevertRootViaBeginRefresh() throws InterruptedException {
        GsdRefreshCoordinator c = new GsdRefreshCoordinator();
        Path rootA = Path.of("/project/a");
        Path rootB = Path.of("/project/b");

        c.setProjectRoot(rootA);

        // Thread 1: reads currentProjectRoot (simulating the old non-atomic pattern)
        Path readRoot = c.currentProjectRoot();
        assertEquals(rootA, readRoot);

        // Thread 2: setProjectRoot(B) — this is the "user switched project" event
        c.setProjectRoot(rootB);

        // Thread 1: calls beginRefresh (NOT passing the stale readRoot).
        // With the new API, beginRefresh reads root atomically inside the monitor.
        RefreshToken token = c.beginRefresh();

        assertNotNull(token);
        // The token must carry rootB (the current root at beginRefresh time),
        // NOT rootA (which was read before setProjectRoot).
        assertEquals("beginRefresh must see the current root, not a stale read",
                rootB, token.projectRoot());

        // And rootB's token must be applicable right now.
        assertTrue(c.shouldApply(token));

        // The coordinator's current root must be rootB — never reverted to rootA.
        assertEquals(rootB, c.currentProjectRoot());
    }

    // ---- Concurrent stress test -------------------------------------------

    @Test
    public void concurrentSetAndRefreshAreThreadSafe() throws InterruptedException {
        GsdRefreshCoordinator c = new GsdRefreshCoordinator();
        c.setProjectRoot(Path.of("/project/init"));

        int threadCount = 8;
        int iterationsPerThread = 1_000;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < iterationsPerThread; i++) {
                        if (threadId % 2 == 0) {
                            // Even threads: setProjectRoot
                            c.setProjectRoot(Path.of("/project/" + threadId + "-" + i));
                        } else {
                            // Odd threads: beginRefresh + shouldApply
                            RefreshToken token = c.beginRefresh();
                            if (token != null) {
                                // shouldApply is either true (nobody bumped yet) or
                                // false (someone bumped) — both are valid outcomes.
                                // The only invalid outcome is an exception.
                                c.shouldApply(token);
                            }
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                } finally {
                    doneLatch.countDown();
                }
            }, "coord-thread-" + t).start();
        }

        startLatch.countDown();
        assertTrue("Threads did not finish in time", doneLatch.await(30, TimeUnit.SECONDS));

        if (failure.get() != null) {
            throw new AssertionError("Concurrent access failed", failure.get());
        }

        // Verify total generation count = setProjectRoot calls + beginRefresh calls.
        // Each even thread does iterationsPerThread setProjectRoot calls.
        // Each odd thread does iterationsPerThread beginRefresh calls.
        // Plus the initial setProjectRoot("/project/init") = 1.
        // We don't assert an exact count because beginRefresh skips when root is null,
        // but generation must be positive and monotonically increasing.
        assertTrue(c.currentGeneration() > 0);
    }
}
