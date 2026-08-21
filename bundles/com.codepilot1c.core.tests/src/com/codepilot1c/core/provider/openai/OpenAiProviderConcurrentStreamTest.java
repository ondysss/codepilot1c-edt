/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider.openai;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

/** Exercises two real OpenAI chat-completions parser sessions on one provider. */
public class OpenAiProviderConcurrentStreamTest {

    @Test
    public void fragmentedToolCallsRemainIsolatedAcrossConcurrentSessions() {
        OpenAiProvider provider = new OpenAiProvider();
        OpenAiProvider.StreamState firstState = new OpenAiProvider.StreamState();
        OpenAiProvider.StreamState secondState = new OpenAiProvider.StreamState();
        List<LlmStreamChunk> firstChunks = new CopyOnWriteArrayList<>();
        List<LlmStreamChunk> secondChunks = new CopyOnWriteArrayList<>();
        CountDownLatch fragmentedSessionsReady = new CountDownLatch(2);

        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> feed(
                provider, firstState, firstChunks, fragmentedSessionsReady,
                "call-a", "tool_a", "alpha", "1")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> feed(
                provider, secondState, secondChunks, fragmentedSessionsReady,
                "call-b", "tool_b", "beta", "2")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$

        CompletableFuture.allOf(first, second).join();

        assertToolCall(firstChunks, "call-a", "tool_a", "{\"alpha\":1}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertToolCall(secondChunks, "call-b", "tool_b", "{\"beta\":2}"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    @Test
    public void cancellingOneHttpStreamDoesNotCancelOrCorruptTheOther() throws Exception {
        ConcurrentStreamHandler handler = new ConcurrentStreamHandler();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        ExecutorService serverExecutor = Executors.newCachedThreadPool();
        server.createContext("/v1/chat/completions", handler); //$NON-NLS-1$
        server.setExecutor(serverExecutor);
        server.start();
        try {
            TestOpenAiProvider provider = new TestOpenAiProvider(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"); //$NON-NLS-1$ //$NON-NLS-2$
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
            first.handle((ignored, error) -> null).get(2, TimeUnit.SECONDS);
            handler.releaseFirst.countDown();
            assertTrue(handler.firstDisconnected.await(5, TimeUnit.SECONDS));
            handler.releaseSecond.countDown();
            second.get(2, TimeUnit.SECONDS);

            assertFalse(firstChunks.stream().anyMatch(LlmStreamChunk::hasToolCalls));
            assertToolCall(secondChunks, "call-second", "inspect_second", //$NON-NLS-1$ //$NON-NLS-2$
                    "{\"project\":\"second\"}"); //$NON-NLS-1$
            assertFalse(secondCancellation.isCancelled());
        } finally {
            handler.releaseFirst.countDown();
            handler.releaseSecond.countDown();
            server.stop(0);
            serverExecutor.shutdown();
            serverExecutor.awaitTermination(2, TimeUnit.SECONDS);
        }
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

    private static void feed(OpenAiProvider provider, OpenAiProvider.StreamState state,
            List<LlmStreamChunk> chunks, CountDownLatch fragmentedSessionsReady,
            String id, String name, String key, String value) {
        provider.processStreamLine(state,
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\""
                        + id + "\",\"function\":{\"name\":\"" + name
                        + "\",\"arguments\":\"{\\\"" + key + "\\\":\"}}]},\"finish_reason\":null}]}", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                chunks::add, () -> { });
        fragmentedSessionsReady.countDown();
        await(fragmentedSessionsReady);
        provider.processStreamLine(state,
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\""
                        + value + "}\"}}]},\"finish_reason\":\"tool_calls\"}]}", //$NON-NLS-1$ //$NON-NLS-2$
                chunks::add, () -> { });
        provider.processStreamLine(state, "data: [DONE]", chunks::add, () -> { }); //$NON-NLS-1$
    }

    private static void assertToolCall(List<LlmStreamChunk> chunks,
            String id, String name, String arguments) {
        LlmStreamChunk toolChunk = chunks.stream()
                .filter(LlmStreamChunk::hasToolCalls)
                .findFirst()
                .orElseThrow();
        assertEquals(1, toolChunk.getToolCalls().size());
        ToolCall call = toolChunk.getToolCalls().get(0);
        assertEquals(id, call.getId());
        assertEquals(name, call.getName());
        assertEquals(arguments, call.getArguments());
        assertTrue(chunks.stream().anyMatch(LlmStreamChunk::isComplete));
    }

    private static final class TestOpenAiProvider extends OpenAiProvider {
        private final String apiUrl;

        private TestOpenAiProvider(String apiUrl) {
            this.apiUrl = apiUrl;
        }

        @Override protected String getApiKey() { return "test-key"; } //$NON-NLS-1$
        @Override protected String getApiUrl() { return apiUrl; }
        @Override protected String getModel() { return "test-model"; } //$NON-NLS-1$
        @Override protected int getMaxTokens() { return 256; }
        @Override protected int getRequestTimeout() { return 10; }
    }

    private static final class ConcurrentStreamHandler implements com.sun.net.httpserver.HttpHandler {
        private final CountDownLatch firstFragment = new CountDownLatch(1);
        private final CountDownLatch secondFragment = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final CountDownLatch releaseSecond = new CountDownLatch(1);
        private final CountDownLatch firstDisconnected = new CountDownLatch(1);

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean first = request.contains("first-view"); //$NON-NLS-1$
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream"); //$NON-NLS-1$ //$NON-NLS-2$
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream output = exchange.getResponseBody()) {
                if (first) {
                    write(output, fragment("call-first", "inspect_first", "fir")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    firstFragment.countDown();
                    await(releaseFirst);
                    writeUntilDisconnected(output);
                } else {
                    write(output, fragment("call-second", "inspect_second", "sec")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    secondFragment.countDown();
                    await(releaseSecond);
                    write(output, finalFragment("ond")); //$NON-NLS-1$
                }
            } catch (IOException e) {
                if (first) {
                    firstDisconnected.countDown();
                } else {
                    throw e;
                }
            } finally {
                exchange.close();
            }
        }

        private static String fragment(String id, String name, String value) {
            return "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\""
                    + id + "\",\"function\":{\"name\":\"" + name
                    + "\",\"arguments\":\"{\\\"project\\\":\\\"" + value
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

        private static void writeUntilDisconnected(OutputStream output) throws IOException {
            byte[] payload = new byte[64 * 1024];
            for (int i = 0; i < 1024; i++) {
                output.write(payload);
                output.flush();
            }
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

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for parser interleaving"); //$NON-NLS-1$
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
