package com.codepilot1c.core.provider.config;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Thin transport wrapper to isolate raw HTTP execution from provider orchestration.
 */
final class ProviderHttpTransport {

    private final HttpClient httpClient;

    ProviderHttpTransport(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    CompletableFuture<HttpResponse<String>> sendStringAsync(HttpRequest request) {
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<Stream<String>> sendStreamingLines(HttpRequest request) throws IOException, InterruptedException {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
    }
}
