/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.gsd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.codepilot1c.core.gsd.GsdFeatureGate;
import com.codepilot1c.ui.gsd.GsdAsyncStateLoader.Result;
import com.codepilot1c.ui.gsd.GsdRefreshCoordinator.RefreshToken;

public class GsdAsyncStateLoaderTest {

    private String previousOverride;

    @Before
    public void rememberFeatureOverride() {
        previousOverride = System.getProperty(GsdFeatureGate.JVM_PROPERTY);
        System.clearProperty(GsdFeatureGate.JVM_PROPERTY);
    }

    @After
    public void restoreFeatureOverride() {
        if (previousOverride == null) {
            System.clearProperty(GsdFeatureGate.JVM_PROPERTY);
        } else {
            System.setProperty(GsdFeatureGate.JVM_PROPERTY, previousOverride);
        }
    }

    @Test
    public void disabledAfterEnqueueDoesNotInvokeLoader() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger loaderInvocations = new AtomicInteger();
        GsdAsyncStateLoader loader = new GsdAsyncStateLoader(executor, projectRoot -> {
            loaderInvocations.incrementAndGet();
            return null;
        });
        GsdRefreshCoordinator coordinator = new GsdRefreshCoordinator();
        Path projectRoot = Path.of("/project/queued-refresh"); //$NON-NLS-1$
        coordinator.setProjectRoot(projectRoot);
        RefreshToken token = coordinator.beginRefresh();

        CompletableFuture<Result> future = loader.load(projectRoot, coordinator, token);

        assertEquals(1, executor.queuedTaskCount());
        assertTrue(coordinator.shouldApply(token));
        System.setProperty(GsdFeatureGate.JVM_PROPERTY, Boolean.FALSE.toString());
        executor.runNext();

        assertTrue(future.join().skipped());
        assertEquals(0, loaderInvocations.get());
    }

    @Test
    public void staleTokenAfterEnqueueDoesNotInvokeLoader() {
        ManualExecutor executor = new ManualExecutor();
        AtomicInteger loaderInvocations = new AtomicInteger();
        GsdAsyncStateLoader loader = new GsdAsyncStateLoader(executor, projectRoot -> {
            loaderInvocations.incrementAndGet();
            return null;
        });
        GsdRefreshCoordinator coordinator = new GsdRefreshCoordinator();
        Path projectRoot = Path.of("/project/stale-refresh"); //$NON-NLS-1$
        coordinator.setProjectRoot(projectRoot);
        RefreshToken token = coordinator.beginRefresh();

        CompletableFuture<Result> future = loader.load(projectRoot, coordinator, token);

        coordinator.setProjectRoot(projectRoot);
        executor.runNext();

        assertTrue(future.join().skipped());
        assertEquals(0, loaderInvocations.get());
    }

    private static final class ManualExecutor implements Executor {

        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        int queuedTaskCount() {
            return tasks.size();
        }

        void runNext() {
            Runnable task = tasks.remove();
            task.run();
        }
    }
}
