/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.model.LlmRequest;
import com.codepilot1c.core.model.LlmStreamChunk;
import com.codepilot1c.core.model.ToolCall;
import com.codepilot1c.core.model.ToolDefinition;
import com.codepilot1c.core.provider.LlmRequestCancellation;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Concurrent transport/parser regression coverage for one provider instance. */
public class DynamicLlmProviderConcurrentRequestTest {

    @Test
    public void cancellingOneFragmentedStreamLeavesOtherRequestAndParserIntact() throws Exception {
        ConcurrentStreamHandler handler = new ConcurrentStreamHandler();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        ExecutorService serverExecutor = Executors.newCachedThreadPool();
        server.createContext("/v1/chat/completions", handler); //$NON-NLS-1$
        server.setExecutor(serverExecutor);
        server.start();
        try {
            DynamicLlmProvider provider = provider(server);
            LlmRequestCancellation firstCancellation = new LlmRequestCancellation();
            LlmRequestCancellation secondCancellation = new LlmRequestCancellation();
            List<LlmStreamChunk> firstChunks = new CopyOnWriteArrayList<>();
            List<LlmStreamChunk> secondChunks = new CopyOnWriteArrayList<>();

            CompletableFuture<Void> first = CompletableFuture.runAsync(() ->
                    provider.streamComplete(request("first-view"), firstChunks::add, firstCancellation)); //$NON-NLS-1$
            assertTrue(handler.firstFragment.await(2, TimeUnit.SECONDS));
            CompletableFuture<Void> second = CompletableFuture.runAsync(() ->
                    provider.streamComplete(request("second-view"), secondChunks::add, secondCancellation)); //$NON-NLS-1$
            assertTrue(handler.secondFragment.await(2, TimeUnit.SECONDS));

            firstCancellation.cancel();
            first.get(2, TimeUnit.SECONDS);
            handler.releaseSecond.countDown();
            second.get(2, TimeUnit.SECONDS);

            ToolCall secondCall = secondChunks.stream()
                    .filter(LlmStreamChunk::hasToolCalls)
                    .findFirst().orElseThrow()
                    .getToolCalls().get(0);
            assertEquals("call-second", secondCall.getId()); //$NON-NLS-1$
            assertEquals("inspect_second", secondCall.getName()); //$NON-NLS-1$
            assertEquals("{\"project\":\"second\"}", secondCall.getArguments()); //$NON-NLS-1$
            assertTrue(secondChunks.stream().anyMatch(LlmStreamChunk::isComplete));
            assertFalse(secondCancellation.isCancelled());
            assertFalse(firstChunks.stream().anyMatch(LlmStreamChunk::hasToolCalls));
        } finally {
            handler.releaseFirst.countDown();
            handler.releaseSecond.countDown();
            server.stop(0);
            serverExecutor.shutdown();
            serverExecutor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    private static DynamicLlmProvider provider(HttpServer server) {
        LlmProviderConfig config = new LlmProviderConfig();
        config.setName("concurrent-test"); //$NON-NLS-1$
        config.setType(ProviderType.OPENAI_COMPATIBLE);
        config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"); //$NON-NLS-1$ //$NON-NLS-2$
        config.setApiKey("test-key"); //$NON-NLS-1$
        config.setModel("test-model"); //$NON-NLS-1$
        config.setStreamingEnabled(true);
        return new DynamicLlmProvider(config);
    }

    private static LlmRequest request(String marker) {
        return LlmRequest.builder()
                .addMessage(LlmMessage.user(marker))
                .addTool(ToolDefinition.builder()
                        .name("inspect_second") //$NON-NLS-1$
                        .description("test") //$NON-NLS-1$
                        .parametersSchema("{\"type\":\"object\"}") //$NON-NLS-1$
                        .build())
                .stream(true)
                .build();
    }

    private static final class ConcurrentStreamHandler implements com.sun.net.httpserver.HttpHandler {
        private final CountDownLatch firstFragment = new CountDownLatch(1);
        private final CountDownLatch secondFragment = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final CountDownLatch releaseSecond = new CountDownLatch(1);

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean first = request.contains("first-view"); //$NON-NLS-1$
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream"); //$NON-NLS-1$ //$NON-NLS-2$
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                if (first) {
                    write(output, fragment("call-first", "inspect_first", "project", "fir")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    firstFragment.countDown();
                    await(releaseFirst);
                    write(output, finalFragment("st")); //$NON-NLS-1$
                } else {
                    write(output, fragment("call-second", "inspect_second", "project", "sec")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                    secondFragment.countDown();
                    await(releaseSecond);
                    write(output, finalFragment("ond")); //$NON-NLS-1$
                }
            } finally {
                exchange.close();
            }
        }

        private static String fragment(String id, String name, String key, String value) {
            return "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\""
                    + id + "\",\"function\":{\"name\":\"" + name
                    + "\",\"arguments\":\"{\\\"" + key + "\\\":\\\"" + value
                    + "\"}}]},\"finish_reason\":null}]}\n"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }

        private static String finalFragment(String value) {
            return "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\""
                    + value + "\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n"
                    + "data: [DONE]\n"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        private static void write(OutputStream output, String value) throws IOException {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        private static void await(CountDownLatch latch) {
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out waiting for stream release"); //$NON-NLS-1$
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
