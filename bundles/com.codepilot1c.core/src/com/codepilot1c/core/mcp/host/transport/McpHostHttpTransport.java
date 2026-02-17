package com.codepilot1c.core.mcp.host.transport;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.mcp.host.IMcpHostTransport;
import com.codepilot1c.core.mcp.host.McpHostRequestRouter;
import com.codepilot1c.core.mcp.host.session.McpHostSession;
import com.codepilot1c.core.mcp.model.McpError;
import com.codepilot1c.core.mcp.model.McpMessage;
import com.codepilot1c.core.mcp.host.transport.McpHostOAuthService.OAuthResponse;

/**
 * Embedded HTTP endpoint for inbound MCP requests.
 */
public class McpHostHttpTransport implements IMcpHostTransport {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(McpHostHttpTransport.class);

    private final String bindAddress;
    private final int port;
    private final McpHostOAuthService oauthService;
    private final McpHostRequestRouter router;
    private final Gson gson = new Gson();
    private final Map<String, McpHostSession> sessions = new ConcurrentHashMap<>();

    private HttpServer server;
    private volatile boolean running;

    public McpHostHttpTransport(String bindAddress, int port, McpHostOAuthService oauthService, McpHostRequestRouter router) {
        this.bindAddress = bindAddress;
        this.port = port;
        this.oauthService = oauthService;
        this.router = router;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
            server.createContext("/mcp", new McpHandler()); //$NON-NLS-1$
            server.createContext("/health", exchange -> writeText(exchange, 200, "ok")); //$NON-NLS-1$ //$NON-NLS-2$
            server.createContext("/.well-known/oauth-authorization-server", new AuthorizationMetadataHandler()); //$NON-NLS-1$
            server.createContext("/.well-known/openid-configuration", new AuthorizationMetadataHandler()); //$NON-NLS-1$
            server.createContext("/.well-known/oauth-protected-resource", new ProtectedResourceMetadataHandler()); //$NON-NLS-1$
            server.createContext("/.well-known/oauth-protected-resource/", new ProtectedResourceMetadataHandler()); //$NON-NLS-1$
            server.createContext("/oauth/register", new RegistrationHandler()); //$NON-NLS-1$
            server.createContext("/register", new RegistrationHandler()); //$NON-NLS-1$
            server.createContext("/oauth/authorize", new AuthorizeHandler()); //$NON-NLS-1$
            server.createContext("/authorize", new AuthorizeHandler()); //$NON-NLS-1$
            server.createContext("/oauth/token", new TokenHandler()); //$NON-NLS-1$
            server.createContext("/token", new TokenHandler()); //$NON-NLS-1$
            server.createContext("/.well-known/", new NotFoundHandler()); //$NON-NLS-1$
            // Catch-all context to force JSON 404 for unknown endpoints (no HTML fallback).
            server.createContext("/", new NotFoundHandler()); //$NON-NLS-1$
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.start();
            running = true;
            LOG.info("MCP host HTTP transport started on %s:%d", bindAddress, Integer.valueOf(port)); //$NON-NLS-1$
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start MCP host HTTP transport", e); //$NON-NLS-1$
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (server != null) {
            server.stop(0);
            server = null;
        }
        sessions.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void close() {
        stop();
    }

    public List<McpHostSession> getSessionsSnapshot() {
        return new ArrayList<>(sessions.values());
    }

    private final class McpHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String requestMethod = exchange.getRequestMethod();
                if ("GET".equalsIgnoreCase(requestMethod) && acceptsSse(exchange)) { //$NON-NLS-1$
                    writeSseReady(exchange);
                    return;
                }
                if (!"POST".equalsIgnoreCase(requestMethod)) { //$NON-NLS-1$
                    writeJson(exchange, 405, Map.of("error", "method_not_allowed")); //$NON-NLS-1$ //$NON-NLS-2$
                    return;
                }
                if (!oauthService.isAuthorized(exchange.getRequestHeaders())) {
                    exchange.getResponseHeaders().add("WWW-Authenticate", oauthService.buildWwwAuthenticateHeader()); //$NON-NLS-1$
                    writeJson(exchange, 401, Map.of( //$NON-NLS-1$
                        "error", "unauthorized", //$NON-NLS-1$ //$NON-NLS-2$
                        "error_description", "Bearer token is missing, invalid or expired")); //$NON-NLS-1$ //$NON-NLS-2$
                    return;
                }

                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                McpMessage request = gson.fromJson(body, McpMessage.class);
                String requestedSessionId = exchange.getRequestHeaders().getFirst("Mcp-Session-Id"); //$NON-NLS-1$
                McpHostSession session;
                String responseSessionId;
                if (requestedSessionId != null && !requestedSessionId.isBlank()) {
                    session = sessions.computeIfAbsent(requestedSessionId, id -> new McpHostSession());
                    responseSessionId = requestedSessionId;
                } else {
                    session = new McpHostSession();
                    responseSessionId = session.getSessionId();
                    sessions.put(responseSessionId, session);
                }
                exchange.getResponseHeaders().add("Mcp-Session-Id", responseSessionId); //$NON-NLS-1$
                McpMessage response = router.route(request, session);
                String json = gson.toJson(response);
                String accept = exchange.getRequestHeaders().getFirst("Accept"); //$NON-NLS-1$
                boolean wantsSse = accept != null && accept.contains("text/event-stream"); //$NON-NLS-1$
                byte[] bytes;
                if (wantsSse) {
                    // Streamable HTTP uses standard SSE framing with real LF separators.
                    String sse = "event: message\n" //$NON-NLS-1$
                        + "data: " + json + "\n\n"; //$NON-NLS-1$ //$NON-NLS-2$
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream"); //$NON-NLS-1$ //$NON-NLS-2$
                    bytes = sse.getBytes(StandardCharsets.UTF_8);
                } else {
                    exchange.getResponseHeaders().add("Content-Type", "application/json"); //$NON-NLS-1$ //$NON-NLS-2$
                    bytes = json.getBytes(StandardCharsets.UTF_8);
                }
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (JsonSyntaxException e) {
                McpMessage error = new McpMessage();
                error.setError(new McpError(-32700, "Parse error", null)); //$NON-NLS-1$
                writeJson(exchange, 400, error);
            } catch (Exception e) {
                LOG.error("Unhandled MCP HTTP handler error", e); //$NON-NLS-1$
                writeJson(exchange, 500, Map.of("error", "internal_error")); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    private final class AuthorizationMetadataHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) { //$NON-NLS-1$
                writeJson(exchange, 405, Map.of("error", "method_not_allowed")); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            writeJson(exchange, 200, oauthService.authorizationServerMetadata());
        }
    }

    private final class ProtectedResourceMetadataHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) { //$NON-NLS-1$
                writeJson(exchange, 405, Map.of("error", "method_not_allowed")); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            writeJson(exchange, 200, oauthService.protectedResourceMetadata());
        }
    }

    private final class RegistrationHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { //$NON-NLS-1$
                writeJson(exchange, 405, Map.of("error", "method_not_allowed")); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = body.isBlank()
                    ? Map.of()
                    : gson.fromJson(body, Map.class);
                OAuthResponse response = oauthService.registerClient(payload != null ? payload : Map.of());
                writeOAuthResponse(exchange, response);
            } catch (JsonSyntaxException e) {
                writeJson(exchange, 400, Map.of( //$NON-NLS-1$
                    "error", "invalid_client_metadata", //$NON-NLS-1$ //$NON-NLS-2$
                    "error_description", "Invalid JSON body")); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }

    private final class AuthorizeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) { //$NON-NLS-1$
                writeJson(exchange, 405, Map.of("error", "method_not_allowed")); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            Map<String, String> query = parseUrlEncoded(exchange.getRequestURI().getRawQuery());
            OAuthResponse response = oauthService.authorize(query);
            writeOAuthResponse(exchange, response);
        }
    }

    private final class TokenHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { //$NON-NLS-1$
                writeJson(exchange, 405, Map.of("error", "method_not_allowed")); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> form = parseUrlEncoded(body);
            OAuthResponse response = oauthService.exchangeToken(form);
            writeOAuthResponse(exchange, response);
        }
    }

    private final class NotFoundHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            writeJson(exchange, 404, Map.of("error", "not_found")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private boolean acceptsSse(HttpExchange exchange) {
        String accept = exchange.getRequestHeaders().getFirst("Accept"); //$NON-NLS-1$
        return accept != null && accept.contains("text/event-stream"); //$NON-NLS-1$
    }

    private void writeSseReady(HttpExchange exchange) throws IOException {
        String payload = "event: ready\n" //$NON-NLS-1$
            + "data: {\"status\":\"ok\"}\n\n"; //$NON-NLS-1$
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream"); //$NON-NLS-1$ //$NON-NLS-2$
        exchange.getResponseHeaders().add("Cache-Control", "no-cache"); //$NON-NLS-1$ //$NON-NLS-2$
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private Map<String, String> parseUrlEncoded(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        String[] pairs = raw.split("&"); //$NON-NLS-1$
        for (String pair : pairs) {
            if (pair.isBlank()) {
                continue;
            }
            int separator = pair.indexOf('=');
            String rawKey = separator >= 0 ? pair.substring(0, separator) : pair;
            String rawValue = separator >= 0 ? pair.substring(separator + 1) : ""; //$NON-NLS-1$
            String key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
            String value = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
            out.put(key, value);
        }
        return out;
    }

    private void writeOAuthResponse(HttpExchange exchange, OAuthResponse response) throws IOException {
        if (response.isRedirect()) {
            exchange.getResponseHeaders().add("Location", response.getRedirectLocation()); //$NON-NLS-1$
            exchange.getResponseHeaders().add("Cache-Control", "no-store"); //$NON-NLS-1$ //$NON-NLS-2$
            exchange.getResponseHeaders().add("Pragma", "no-cache"); //$NON-NLS-1$ //$NON-NLS-2$
            exchange.sendResponseHeaders(response.getStatusCode(), -1);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().add("Cache-Control", "no-store"); //$NON-NLS-1$ //$NON-NLS-2$
        exchange.getResponseHeaders().add("Pragma", "no-cache"); //$NON-NLS-1$ //$NON-NLS-2$
        writeJson(exchange, response.getStatusCode(), response.getJsonBody() != null ? response.getJsonBody() : Map.of());
    }

    private void writeJson(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] bytes = gson.toJson(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8"); //$NON-NLS-1$ //$NON-NLS-2$
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void writeText(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8"); //$NON-NLS-1$ //$NON-NLS-2$
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
