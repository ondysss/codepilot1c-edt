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
}
