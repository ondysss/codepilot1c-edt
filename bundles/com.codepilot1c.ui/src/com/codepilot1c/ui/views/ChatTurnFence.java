/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.views;

/** Monotonic lifecycle fence for asynchronous work owned by one ChatView. */
public final class ChatTurnFence {

    private long generation;

    public synchronized long beginTurn() {
        return ++generation;
    }

    public synchronized void invalidate() {
        generation++;
    }

    public synchronized boolean isCurrent(long turnGeneration) {
        return generation == turnGeneration;
    }

    public synchronized boolean runIfCurrent(long turnGeneration, Runnable action) {
        if (!isCurrent(turnGeneration)) {
            return false;
        }
        action.run();
        return true;
    }
}
