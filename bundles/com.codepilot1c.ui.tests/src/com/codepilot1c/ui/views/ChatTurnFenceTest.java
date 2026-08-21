/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.views;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

/** Focused lifecycle tests for clear/new-chat stale completion rejection. */
public class ChatTurnFenceTest {

    @Test
    public void clearRejectsPendingToolCompletionBeforeMutation() {
        ChatTurnFence fence = new ChatTurnFence();
        long turn = fence.beginTurn();
        AtomicBoolean historyMutated = new AtomicBoolean();
        CompletableFuture<String> tool = new CompletableFuture<>();
        tool.thenRun(() -> fence.runIfCurrent(turn, () -> historyMutated.set(true)));

        fence.invalidate();
        tool.complete("done"); //$NON-NLS-1$

        assertFalse(historyMutated.get());
    }

    @Test
    public void clearRejectsPendingConfirmationAndFollowUp() {
        ChatTurnFence fence = new ChatTurnFence();
        long turn = fence.beginTurn();
        AtomicBoolean confirmationExecuted = new AtomicBoolean();
        AtomicBoolean followUpSent = new AtomicBoolean();

        fence.invalidate();

        assertFalse(fence.runIfCurrent(turn, () -> confirmationExecuted.set(true)));
        assertFalse(fence.runIfCurrent(turn, () -> followUpSent.set(true)));
        assertFalse(confirmationExecuted.get());
        assertFalse(followUpSent.get());
    }

    @Test
    public void newTurnAcceptsOnlyItsOwnCompletions() {
        ChatTurnFence fence = new ChatTurnFence();
        long oldTurn = fence.beginTurn();
        long newTurn = fence.beginTurn();

        assertFalse(fence.runIfCurrent(oldTurn, () -> { }));
        assertTrue(fence.runIfCurrent(newTurn, () -> { }));
    }

    @Test
    public void dispatchAndExecutedRegistrationLinearizeBeforeInvalidation() throws Exception {
        ChatTurnFence fence = new ChatTurnFence();
        long turn = fence.beginTurn();
        CountDownLatch dispatchEntered = new CountDownLatch(1);
        CountDownLatch releaseDispatch = new CountDownLatch(1);
        CountDownLatch invalidationStarted = new CountDownLatch(1);
        AtomicBoolean dispatched = new AtomicBoolean();
        AtomicBoolean executedRegistered = new AtomicBoolean();
        AtomicBoolean invalidationDone = new AtomicBoolean();

        CompletableFuture<Void> dispatch = CompletableFuture.runAsync(() ->
                fence.runIfCurrent(turn, () -> {
                    dispatchEntered.countDown();
                    await(releaseDispatch);
                    dispatched.set(true);
                    executedRegistered.set(true);
                }));
        assertTrue(dispatchEntered.await(2, TimeUnit.SECONDS));
        CompletableFuture<Void> invalidate = CompletableFuture.runAsync(() -> {
            invalidationStarted.countDown();
            fence.invalidate();
            invalidationDone.set(true);
        });
        assertTrue(invalidationStarted.await(2, TimeUnit.SECONDS));
        assertFalse("invalidation must wait for atomic dispatch", invalidationDone.get()); //$NON-NLS-1$

        releaseDispatch.countDown();
        dispatch.get(2, TimeUnit.SECONDS);
        invalidate.get(2, TimeUnit.SECONDS);

        assertTrue(dispatched.get());
        assertTrue(executedRegistered.get());
        assertFalse(fence.runIfCurrent(turn, () -> dispatched.set(true)));
    }

    @Test
    public void cancelledCodeMdInitializationCannotAppendIntoNewChat() {
        ChatTurnFence fence = new ChatTurnFence();
        long initialization = fence.beginTurn();
        AtomicBoolean appended = new AtomicBoolean();
        CompletableFuture<String> pendingInitialization = new CompletableFuture<>();
        pendingInitialization.thenRun(() ->
                fence.runIfCurrent(initialization, () -> appended.set(true)));

        fence.invalidate();
        fence.beginTurn();
        pendingInitialization.complete("initialized"); //$NON-NLS-1$

        assertFalse(appended.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for test interleaving"); //$NON-NLS-1$
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
