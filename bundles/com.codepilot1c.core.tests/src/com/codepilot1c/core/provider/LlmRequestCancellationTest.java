/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.junit.Test;

import com.codepilot1c.core.model.LlmRequest;
import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.model.LlmStreamChunk;

/** Verifies independent request scopes when two ChatViews share one provider. */
public class LlmRequestCancellationTest {

    @Test
    public void cancellingOneViewDoesNotCancelSharedProviderOrOtherView() {
        SharedProvider provider = new SharedProvider();
        LlmRequestCancellation firstView = new LlmRequestCancellation();
        LlmRequestCancellation secondView = new LlmRequestCancellation();

        CompletableFuture<LlmResponse> first = provider.complete(request("first"), firstView); //$NON-NLS-1$
        CompletableFuture<LlmResponse> second = provider.complete(request("second"), secondView); //$NON-NLS-1$

        firstView.cancel();

        assertTrue(first.isCancelled());
        assertFalse(second.isCancelled());
        assertEquals(0, provider.providerWideCancelCount);
        provider.pending.get(1).complete(LlmResponse.builder().content("ok").build()); //$NON-NLS-1$
        assertEquals("ok", second.join().getContent()); //$NON-NLS-1$
    }

    @Test
    public void cancelledStreamingScopeSuppressesOnlyItsOwnChunks() {
        SharedProvider provider = new SharedProvider();
        LlmRequestCancellation firstView = new LlmRequestCancellation();
        LlmRequestCancellation secondView = new LlmRequestCancellation();
        List<String> firstChunks = new ArrayList<>();
        List<String> secondChunks = new ArrayList<>();

        provider.streamComplete(request("first"), //$NON-NLS-1$
                chunk -> firstChunks.add(chunk.getContent()), firstView);
        provider.streamComplete(request("second"), //$NON-NLS-1$
                chunk -> secondChunks.add(chunk.getContent()), secondView);
        firstView.cancel();
        provider.streamConsumers.get(0).accept(LlmStreamChunk.content("stale")); //$NON-NLS-1$
        provider.streamConsumers.get(1).accept(LlmStreamChunk.content("current")); //$NON-NLS-1$

        assertTrue(firstChunks.isEmpty());
        assertEquals(List.of("current"), secondChunks); //$NON-NLS-1$
        assertEquals(0, provider.providerWideCancelCount);
    }

    private LlmRequest request(String content) {
        return LlmRequest.builder().addMessage(
                com.codepilot1c.core.model.LlmMessage.user(content)).build();
    }

    private static final class SharedProvider implements ILlmProvider {
        private final List<CompletableFuture<LlmResponse>> pending = new ArrayList<>();
        private final List<Consumer<LlmStreamChunk>> streamConsumers = new ArrayList<>();
        private int providerWideCancelCount;

        @Override public String getId() { return "shared"; } //$NON-NLS-1$
        @Override public String getDisplayName() { return "Shared"; } //$NON-NLS-1$
        @Override public boolean isConfigured() { return true; }
        @Override public boolean supportsStreaming() { return true; }

        @Override
        public CompletableFuture<LlmResponse> complete(LlmRequest request) {
            CompletableFuture<LlmResponse> future = new CompletableFuture<>();
            pending.add(future);
            return future;
        }

        @Override
        public void streamComplete(LlmRequest request, Consumer<LlmStreamChunk> consumer) {
            streamConsumers.add(consumer);
        }

        @Override public void cancel() { providerWideCancelCount++; }
        @Override public void dispose() { }
    }
}
