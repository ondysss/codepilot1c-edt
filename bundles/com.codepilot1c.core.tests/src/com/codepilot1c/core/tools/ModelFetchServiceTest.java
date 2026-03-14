package com.codepilot1c.core.tools;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.codepilot1c.core.provider.config.ModelFetchService;
import com.codepilot1c.core.provider.config.ModelFetchService.FetchResult;
import com.codepilot1c.core.provider.config.ProviderType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class ModelFetchServiceTest {

    @Test
    public void openAiCompatible404FallsBackToManualModelEntry() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/models", new FixedStatusHandler(404, "")); //$NON-NLS-1$ //$NON-NLS-2$
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"; //$NON-NLS-1$ //$NON-NLS-2$
            FetchResult result = ModelFetchService.getInstance()
                    .fetchModels(baseUrl, null, ProviderType.OPENAI_COMPATIBLE)
                    .get(10, TimeUnit.SECONDS);

            assertFalse(result.isSuccess());
            assertTrue(result.requiresManualModelEntry());
        } finally {
            server.stop(0);
        }
    }

    private static final class FixedStatusHandler implements HttpHandler {
        private final int statusCode;
        private final byte[] body;

        private FixedStatusHandler(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try (OutputStream outputStream = exchange.getResponseBody()) {
                exchange.sendResponseHeaders(statusCode, body.length);
                outputStream.write(body);
            }
        }
    }
}
