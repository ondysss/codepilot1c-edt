/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cancellation scope owned by one logical provider request chain. */
public final class LlmRequestCancellation {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final ConcurrentLinkedQueue<Runnable> listeners = new ConcurrentLinkedQueue<>();

    public boolean isCancelled() {
        return cancelled.get();
    }

    /** Cancels only work registered with this request scope. */
    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        Runnable listener;
        while ((listener = listeners.poll()) != null) {
            runSafely(listener);
        }
    }

    /** Registers an idempotent cancellation action, including after cancellation won the race. */
    public void onCancel(Runnable listener) {
        if (listener == null) {
            return;
        }
        if (cancelled.get()) {
            runSafely(listener);
            return;
        }
        listeners.add(listener);
        if (cancelled.get() && listeners.remove(listener)) {
            runSafely(listener);
        }
    }

    private void runSafely(Runnable listener) {
        try {
            listener.run();
        } catch (RuntimeException ignored) {
            // One provider-specific cancellation callback must not block the rest.
        }
    }
}
