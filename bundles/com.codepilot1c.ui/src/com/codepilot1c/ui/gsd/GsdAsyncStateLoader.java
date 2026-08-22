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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import com.codepilot1c.core.gsd.GsdFeatureGate;
import com.codepilot1c.core.gsd.GsdState;
import com.codepilot1c.ui.gsd.GsdRefreshCoordinator.RefreshToken;

/**
 * Testable asynchronous boundary for loading GSD state.
 *
 * <p>The eligibility check deliberately runs inside the queued task, directly
 * before the loader. This prevents a refresh accepted while GSD was enabled
 * from touching the filesystem after the feature is disabled or its refresh
 * token becomes stale.</p>
 */
final class GsdAsyncStateLoader {

    @FunctionalInterface
    interface Loader {
        GsdState load(Path projectRoot) throws Exception;
    }

    record Result(GsdState state, Exception error, boolean skipped) {

        static Result success(GsdState state) {
            return new Result(state, null, false);
        }

        static Result failure(Exception error) {
            return new Result(null, Objects.requireNonNull(error, "error"), false); //$NON-NLS-1$
        }

        static Result skippedResult() {
            return new Result(null, null, true);
        }
    }

    private final Executor executor;
    private final Loader loader;

    GsdAsyncStateLoader(Executor executor, Loader loader) {
        this.executor = Objects.requireNonNull(executor, "executor"); //$NON-NLS-1$
        this.loader = Objects.requireNonNull(loader, "loader"); //$NON-NLS-1$
    }

    CompletableFuture<Result> load(
            Path projectRoot,
            GsdRefreshCoordinator coordinator,
            RefreshToken token) {
        Objects.requireNonNull(projectRoot, "projectRoot"); //$NON-NLS-1$
        Objects.requireNonNull(coordinator, "coordinator"); //$NON-NLS-1$
        Objects.requireNonNull(token, "token"); //$NON-NLS-1$
        return CompletableFuture.supplyAsync(() -> {
            if (!GsdFeatureGate.getInstance().isEnabled()
                    || !coordinator.shouldApply(token)) {
                return Result.skippedResult();
            }
            try {
                return Result.success(loader.load(projectRoot));
            } catch (Exception e) {
                return Result.failure(e);
            }
        }, executor);
    }
}
