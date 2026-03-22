package com.codepilot1c.core.mcp.host.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import com.codepilot1c.core.evaluation.trace.AgentTraceSession;
import com.codepilot1c.core.evaluation.trace.TraceEventType;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.remote.AgentSessionController;
import com.codepilot1c.core.remote.IdeSnapshot;
import com.codepilot1c.core.remote.RemoteBootstrapResponse;
import com.codepilot1c.core.remote.RemoteCommandRequest;
import com.codepilot1c.core.remote.RemoteCommandResult;
import com.codepilot1c.core.remote.RemoteEvent;

/**
 * HTTP handlers for the embedded remote web companion.
 */
final class RemoteWebController implements AgentSessionController.RemoteEventListener {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(RemoteWebController.class);
    private static final String SESSION_COOKIE = "CP_REMOTE_SESSION"; //$NON-NLS-1$
    private static final Duration SESSION_TTL = Duration.ofHours(8);

    private final Gson gson = new Gson();
    private final McpHostOAuthService oauthService;
    private final com.codepilot1c.core.mcp.host.McpHostConfig.AuthMode authMode;
    private final AgentSessionController controller;
    private final Map<String, RemoteAuthSession> sessions = new ConcurrentHashMap<>();
    private final List<SseConnection> sseConnections = new CopyOnWriteArrayList<>();

    RemoteWebController(
            McpHostOAuthService oauthService,
            com.codepilot1c.core.mcp.host.McpHostConfig.AuthMode authMode,
            AgentSessionController controller) {
        this.oauthService = oauthService;
        this.authMode = authMode;
        this.controller = controller;
        this.controller.addRemoteEventListener(this, 0L);
    }

    HttpHandler staticHandler() {
        return this::handleStatic;
    }

    HttpHandler apiHandler() {
        return this::handleApi;
    }

    void dispose() {
        controller.removeRemoteEventListener(this);
        for (SseConnection connection : new ArrayList<>(sseConnections)) {
            connection.close();
        }
        sseConnections.clear();
        sessions.values().forEach(RemoteAuthSession::close);
        sessions.clear();
    }

    @Override
    public void onRemoteEvent(RemoteEvent event) {
        for (SseConnection connection : new ArrayList<>(sseConnections)) {
            if (!connection.send(event)) {
                sseConnections.remove(connection);
                connection.close();
            }
        }
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI() != null ? exchange.getRequestURI().getPath() : "/remote/"; //$NON-NLS-1$
        if ("/remote".equals(path)) { //$NON-NLS-1$
            exchange.getResponseHeaders().add("Location", "/remote/"); //$NON-NLS-1$ //$NON-NLS-2$
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
            return;
        }
        String resourcePath = switch (path) {
            case "/remote/", "/remote/index.html" -> "web/remote/index.html"; //$NON-NLS-1$ //$NON-NLS-2$
            case "/remote/app.js" -> "web/remote/app.js"; //$NON-NLS-1$
            case "/remote/app.css" -> "web/remote/app.css"; //$NON-NLS-1$
            default -> null;
        };
        if (resourcePath == null) {
            writeJson(exchange, 404, RemoteCommandResult.error("not_found", "Ресурс удаленного интерфейса не найден")); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        try (InputStream input = RemoteWebController.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (input == null) {
                writeJson(exchange, 404, RemoteCommandResult.error("not_found", "Ресурс удаленного интерфейса не найден")); //$NON-NLS-1$ //$NON-NLS-2$
                return;
            }
            byte[] bytes = input.readAllBytes();
            String mimeType = URLConnection.guessContentTypeFromName(resourcePath);
            exchange.getResponseHeaders().add("Content-Type", mimeType != null ? mimeType : "text/plain; charset=utf-8"); //$NON-NLS-1$ //$NON-NLS-2$
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private void handleApi(HttpExchange exchange) throws IOException {
        cleanupExpiredSessions();
        String path = exchange.getRequestURI() != null ? exchange.getRequestURI().getPath() : ""; //$NON-NLS-1$

        if ("/remote/api/auth/login".equals(path)) { //$NON-NLS-1$
            handleLogin(exchange);
            return;
        }
        if ("/remote/api/auth/logout".equals(path)) { //$NON-NLS-1$
            handleLogout(exchange);
            return;
        }

        RemoteAuthSession authSession = authenticated(exchange);
        if (authSession == null) {
            exchange.getResponseHeaders().add("WWW-Authenticate", oauthService.buildWwwAuthenticateHeader()); //$NON-NLS-1$
            writeJson(exchange, 401, RemoteCommandResult.error("unauthorized", "Cookie удаленной сессии отсутствует или истекла")); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        switch (path) {
            case "/remote/api/bootstrap" -> handleBootstrap(exchange, authSession); //$NON-NLS-1$
            case "/remote/api/events" -> handleEvents(exchange, authSession); //$NON-NLS-1$
            case "/remote/api/controller/claim" -> handleClaim(exchange, authSession); //$NON-NLS-1$
            case "/remote/api/controller/release" -> handleRelease(exchange, authSession); //$NON-NLS-1$
            case "/remote/api/agent/start" -> handleAgentStart(exchange, authSession); //$NON-NLS-1$
            case "/remote/api/agent/input" -> handleAgentInput(exchange, authSession); //$NON-NLS-1$
            case "/remote/api/agent/approve" -> handleApprove(exchange, authSession); //$NON-NLS-1$
            case "/remote/api/agent/reject" -> handleReject(exchange, authSession); //$NON-NLS-1$
            case "/remote/api/agent/stop" -> handleStop(exchange, authSession); //$NON-NLS-1$
            case "/remote/api/ide/snapshot" -> handleSnapshot(exchange, authSession); //$NON-NLS-1$
            case "/remote/api/ide/editor/open-file" -> handleEditorOpen(exchange, authSession); //$NON-NLS-1$
            case "/remote/api/ide/editor/reveal-range" -> handleEditorReveal(exchange, authSession); //$NON-NLS-1$
            case "/remote/api/ide/editor/get-selection" -> handleGetSelection(exchange, authSession); //$NON-NLS-1$
            case "/remote/api/ide/editor/replace-selection" -> handleReplaceSelection(exchange, authSession); //$NON-NLS-1$
            case "/remote/api/ide/editor/insert-at-cursor" -> handleInsertAtCursor(exchange, authSession); //$NON-NLS-1$
            case "/remote/api/ide/editor/apply-generated-code" -> handleApplyGeneratedCode(exchange, authSession); //$NON-NLS-1$
            case "/remote/api/ide/view/show" -> handleShowView(exchange, authSession); //$NON-NLS-1$
            case "/remote/api/ide/view/activate" -> handleActivatePart(exchange, authSession); //$NON-NLS-1$
            case "/remote/api/workbench/command" -> handleWorkbenchCommand(exchange, authSession); //$NON-NLS-1$
            default -> writeJson(exchange, 404, RemoteCommandResult.error("not_found", "Метод удаленного API не найден")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private void handleLogin(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { //$NON-NLS-1$
            writeJson(exchange, 405, RemoteCommandResult.error("method_not_allowed", "Для входа используйте POST")); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        if (authMode == com.codepilot1c.core.mcp.host.McpHostConfig.AuthMode.OAUTH_ONLY) {
            writeJson(exchange, 409, RemoteCommandResult.error("oauth_only", "Удаленный веб-интерфейс требует режим fallback bearer token")); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        Map<String, Object> payload = readJsonMap(exchange);
        String token = stringValue(payload.get("token")); //$NON-NLS-1$
        if (token == null || token.isBlank()) {
            writeJson(exchange, 422, RemoteCommandResult.error("missing_token", "Нужно указать bearer-токен")); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        if (!isStaticBearerAuthorized(token)) {
            writeJson(exchange, 403, RemoteCommandResult.error("invalid_token", "Bearer-токен недействителен")); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }

        RemoteAuthSession session = new RemoteAuthSession();
        sessions.put(session.sessionToken, session);
        exchange.getResponseHeaders().add("Set-Cookie", buildSessionCookie(session.sessionToken, true)); //$NON-NLS-1$ //$NON-NLS-2$
        controller.claimControllerLease(session.clientId, false);
        trace(session, TraceEventType.REMOTE_AUTH, Map.of("result", "login_success")); //$NON-NLS-1$ //$NON-NLS-2$
        writeJson(exchange, 200, RemoteCommandResult.ok("Вход в удаленный интерфейс выполнен", Map.of( //$NON-NLS-1$ //$NON-NLS-2$
                "clientId", session.clientId, //$NON-NLS-1$
                "controllerClientId", controller.getControllerClientId() != null ? controller.getControllerClientId() : ""))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void handleLogout(HttpExchange exchange) throws IOException {
        RemoteAuthSession session = authenticated(exchange);
        if (session != null) {
            sessions.remove(session.sessionToken);
            session.close();
        }
        exchange.getResponseHeaders().add("Set-Cookie", buildSessionCookie("", false)); //$NON-NLS-1$ //$NON-NLS-2$
        writeJson(exchange, 200, RemoteCommandResult.ok("Удаленная сессия очищена")); //$NON-NLS-1$
    }

    private void handleBootstrap(HttpExchange exchange, RemoteAuthSession authSession) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) { //$NON-NLS-1$
            writeJson(exchange, 405, RemoteCommandResult.error("method_not_allowed", "Для bootstrap используйте GET")); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        IdeSnapshot snapshot = controller.captureIdeSnapshot();
        RemoteBootstrapResponse response = controller.buildBootstrap(authSession.clientId, snapshot);
        trace(authSession, TraceEventType.REMOTE_COMMAND, Map.of("path", "/remote/api/bootstrap")); //$NON-NLS-1$ //$NON-NLS-2$
        writeJson(exchange, 200, response);
    }

    private void handleEvents(HttpExchange exchange, RemoteAuthSession authSession) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) { //$NON-NLS-1$
            writeJson(exchange, 405, RemoteCommandResult.error("method_not_allowed", "Для потока событий используйте GET")); //$NON-NLS-1$ //$NON-NLS-2$
            return;
        }
        Map<String, String> query = parseUrlEncoded(exchange.getRequestURI() != null ? exchange.getRequestURI().getRawQuery() : null);
        long fromSequence = parseLong(query.get("fromSequence")); //$NON-NLS-1$
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream"); //$NON-NLS-1$ //$NON-NLS-2$
        exchange.getResponseHeaders().add("Cache-Control", "no-cache"); //$NON-NLS-1$ //$NON-NLS-2$
        exchange.getResponseHeaders().add("Connection", "keep-alive"); //$NON-NLS-1$ //$NON-NLS-2$
        exchange.getResponseHeaders().add("X-Accel-Buffering", "no"); //$NON-NLS-1$ //$NON-NLS-2$
        exchange.sendResponseHeaders(200, 0);
        SseConnection connection = new SseConnection(exchange.getResponseBody(), authSession);
        sseConnections.add(connection);
        controller.addRemoteEventListener(connection, fromSequence);
        trace(authSession, TraceEventType.REMOTE_COMMAND, Map.of("path", "/remote/api/events", "fromSequence", Long.valueOf(fromSequence))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    private void handleClaim(HttpExchange exchange, RemoteAuthSession authSession) throws IOException {
        Map<String, Object> payload = readJsonMap(exchange);
        boolean force = booleanValue(payload.get("force")); //$NON-NLS-1$
        RemoteCommandResult result = controller.claimControllerLease(authSession.clientId, force);
        trace(authSession, TraceEventType.REMOTE_LEASE, Map.of("force", Boolean.valueOf(force), "ok", Boolean.valueOf(result.isOk()))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        writeJson(exchange, statusFor(result), result);
    }

    private void handleRelease(HttpExchange exchange, RemoteAuthSession authSession) throws IOException {
        RemoteCommandResult result = controller.releaseControllerLease(authSession.clientId);
        trace(authSession, TraceEventType.REMOTE_LEASE, Map.of("action", "release", "ok", Boolean.valueOf(result.isOk()))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        writeJson(exchange, statusFor(result), result);
    }

    private void handleAgentStart(HttpExchange exchange, RemoteAuthSession authSession) throws IOException {
        Map<String, Object> payload = readJsonMap(exchange);
        RemoteCommandResult result = controller.startNewSession(
                stringValue(payload.get("prompt")), //$NON-NLS-1$
                stringValue(payload.get("profileId")), //$NON-NLS-1$
                authSession.clientId);
        trace(authSession, TraceEventType.REMOTE_COMMAND, Map.of("path", "/remote/api/agent/start", "ok", Boolean.valueOf(result.isOk()))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        writeJson(exchange, statusFor(result), result);
    }

    private void handleAgentInput(HttpExchange exchange, RemoteAuthSession authSession) throws IOException {
        Map<String, Object> payload = readJsonMap(exchange);
        RemoteCommandResult result = controller.continueSession(
                stringValue(payload.get("prompt")), //$NON-NLS-1$
                stringValue(payload.get("profileId")), //$NON-NLS-1$
                authSession.clientId);
        trace(authSession, TraceEventType.REMOTE_COMMAND, Map.of("path", "/remote/api/agent/input", "ok", Boolean.valueOf(result.isOk()))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        writeJson(exchange, statusFor(result), result);
    }

    private void handleApprove(HttpExchange exchange, RemoteAuthSession authSession) throws IOException {
        Map<String, Object> payload = readJsonMap(exchange);
        String confirmationId = stringValue(payload.get("confirmationId")); //$NON-NLS-1$
        RemoteCommandResult result = controller.approvePending(confirmationId, authSession.clientId);
        trace(authSession, TraceEventType.REMOTE_CONFIRMATION, Map.of("action", "approve", "ok", Boolean.valueOf(result.isOk()))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        writeJson(exchange, statusFor(result), result);
    }

    private void handleReject(HttpExchange exchange, RemoteAuthSession authSession) throws IOException {
        Map<String, Object> payload = readJsonMap(exchange);
        String confirmationId = stringValue(payload.get("confirmationId")); //$NON-NLS-1$
        RemoteCommandResult result = controller.rejectPending(confirmationId, authSession.clientId);
        trace(authSession, TraceEventType.REMOTE_CONFIRMATION, Map.of("action", "reject", "ok", Boolean.valueOf(result.isOk()))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        writeJson(exchange, statusFor(result), result);
    }

    private void handleStop(HttpExchange exchange, RemoteAuthSession authSession) throws IOException {
        RemoteCommandResult result = controller.stop(authSession.clientId);
        trace(authSession, TraceEventType.REMOTE_COMMAND, Map.of("path", "/remote/api/agent/stop", "ok", Boolean.valueOf(result.isOk()))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        writeJson(exchange, statusFor(result), result);
    }

    private void handleSnapshot(HttpExchange exchange, RemoteAuthSession authSession) throws IOException {
        IdeSnapshot snapshot = controller.captureIdeSnapshot();
        trace(authSession, TraceEventType.REMOTE_COMMAND, Map.of("path", "/remote/api/ide/snapshot")); //$NON-NLS-1$ //$NON-NLS-2$
        writeJson(exchange, 200, snapshot);
    }

    private void handleEditorOpen(HttpExchange exchange, RemoteAuthSession authSession) throws IOException {
        Map<String, Object> payload = readJsonMap(exchange);
        RemoteCommandResult result = controller.openFile(authSession.clientId, stringValue(payload.get("path"))); //$NON-NLS-1$
        trace(authSession, TraceEventType.REMOTE_COMMAND, Map.of("path", "/remote/api/ide/editor/open-file", "ok", Boolean.valueOf(result.isOk()))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        writeJson(exchange, statusFor(result), result);
    }

    private void handleEditorReveal(HttpExchange exchange, RemoteAuthSession authSession) throws IOException {
        Map<String, Object> payload = readJsonMap(exchange);
        RemoteCommandResult result = controller.revealRange(
                authSession.clientId,
                stringValue(payload.get("path")), //$NON-NLS-1$
                parseInt(payload.get("startLine")), //$NON-NLS-1$
                parseInt(payload.get("startColumn")), //$NON-NLS-1$
                parseInt(payload.get("endLine")), //$NON-NLS-1$
                parseInt(payload.get("endColumn"))); //$NON-NLS-1$
        trace(authSession, TraceEventType.REMOTE_COMMAND, Map.of("path", "/remote/api/ide/editor/reveal-range", "ok", Boolean.valueOf(result.isOk()))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        writeJson(exchange, statusFor(result), result);
    }

    private void handleGetSelection(HttpExchange exchange, RemoteAuthSession authSession) throws IOException {
        RemoteCommandResult result = controller.getSelection(authSession.clientId);
        trace(authSession, TraceEventType.REMOTE_COMMAND, Map.of("path", "/remote/api/ide/editor/get-selection", "ok", Boolean.valueOf(result.isOk()))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        writeJson(exchange, statusFor(result), result);
    }

    private void handleReplaceSelection(HttpExchange exchange, RemoteAuthSession authSession) throws IOException {
        Map<String, Object> payload = readJsonMap(exchange);
        RemoteCommandResult result = controller.replaceSelection(authSession.clientId, stringValue(payload.get("text"))); //$NON-NLS-1$
        trace(authSession, TraceEventType.REMOTE_EDITOR_APPLY, Map.of("action", "replace_selection", "ok", Boolean.valueOf(result.isOk()))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        writeJson(exchange, statusFor(result), result);
    }

    private void handleInsertAtCursor(HttpExchange exchange, RemoteAuthSession authSession) throws IOException {
        Map<String, Object> payload = readJsonMap(exchange);
        RemoteCommandResult result = controller.insertAtCursor(authSession.clientId, stringValue(payload.get("text"))); //$NON-NLS-1$
        trace(authSession, TraceEventType.REMOTE_EDITOR_APPLY, Map.of("action", "insert_at_cursor", "ok", Boolean.valueOf(result.isOk()))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        writeJson(exchange, statusFor(result), result);
    }

    private void handleApplyGeneratedCode(HttpExchange exchange, RemoteAuthSession authSession) throws IOException {
        Map<String, Object> payload = readJsonMap(exchange);
        RemoteCommandResult result = controller.applyGeneratedCode(
                authSession.clientId,
                stringValue(payload.get("response")), //$NON-NLS-1$
                booleanValue(payload.get("replaceSelection"))); //$NON-NLS-1$
        trace(authSession, TraceEventType.REMOTE_EDITOR_APPLY, Map.of("action", "apply_generated_code", "ok", Boolean.valueOf(result.isOk()))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        writeJson(exchange, statusFor(result), result);
    }

    private void handleShowView(HttpExchange exchange, RemoteAuthSession authSession) throws IOException {
        Map<String, Object> payload = readJsonMap(exchange);
        RemoteCommandResult result = controller.showView(authSession.clientId, stringValue(payload.get("viewId"))); //$NON-NLS-1$
        trace(authSession, TraceEventType.REMOTE_COMMAND, Map.of("action", "show_view", "ok", Boolean.valueOf(result.isOk()))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        writeJson(exchange, statusFor(result), result);
    }

    private void handleActivatePart(HttpExchange exchange, RemoteAuthSession authSession) throws IOException {
        Map<String, Object> payload = readJsonMap(exchange);
        RemoteCommandResult result = controller.activatePart(authSession.clientId, stringValue(payload.get("partId"))); //$NON-NLS-1$
        trace(authSession, TraceEventType.REMOTE_COMMAND, Map.of("action", "activate_part", "ok", Boolean.valueOf(result.isOk()))); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        writeJson(exchange, statusFor(result), result);
    }

    private void handleWorkbenchCommand(HttpExchange exchange, RemoteAuthSession authSession) throws IOException {
        RemoteCommandRequest request = readCommand(exchange);
        Map<String, Object> payload = request.getPayload();
        RemoteCommandResult result = controller.executeWorkbenchCommand(
                authSession.clientId,
                stringValue(payload.get("commandId")), //$NON-NLS-1$
                payload.get("parameters") instanceof Map<?, ?> map ? castMap(map) : Map.of()); //$NON-NLS-1$
        trace(authSession, TraceEventType.REMOTE_COMMAND, Map.of( //$NON-NLS-1$
                "action", "workbench_command", //$NON-NLS-1$
                "commandId", stringValue(payload.get("commandId")) != null ? stringValue(payload.get("commandId")) : "", //$NON-NLS-1$ //$NON-NLS-2$
                "ok", Boolean.valueOf(result.isOk()))); //$NON-NLS-1$
        writeJson(exchange, statusFor(result), result);
    }

    private RemoteAuthSession authenticated(HttpExchange exchange) {
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie"); //$NON-NLS-1$
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return null;
        }
        String token = parseCookies(cookieHeader).get(SESSION_COOKIE);
        if (token == null || token.isBlank()) {
            return null;
        }
        RemoteAuthSession session = sessions.get(token);
        if (session == null || session.isExpired()) {
            sessions.remove(token);
            if (session != null) {
                session.close();
            }
            return null;
        }
        session.touch();
        return session;
    }

    private void cleanupExpiredSessions() {
        for (RemoteAuthSession session : new ArrayList<>(sessions.values())) {
            if (session.isExpired()) {
                sessions.remove(session.sessionToken);
                session.close();
            }
        }
    }

    private boolean isStaticBearerAuthorized(String token) {
        Map<String, List<String>> headers = Map.of("Authorization", List.of("Bearer " + token)); //$NON-NLS-1$ //$NON-NLS-2$
        return oauthService.isStaticBearerAuthorized(headers);
    }

    static String buildSessionCookie(String token, boolean active) {
        StringBuilder cookie = new StringBuilder();
        cookie.append(SESSION_COOKIE).append('=').append(token != null ? token : ""); //$NON-NLS-1$
        cookie.append("; Path=/remote/api; HttpOnly; SameSite=Lax"); //$NON-NLS-1$
        cookie.append("; Max-Age=").append(active ? SESSION_TTL.getSeconds() : 0L); //$NON-NLS-1$
        return cookie.toString();
    }

    static Map<String, String> parseCookies(String raw) {
        Map<String, String> result = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        String[] parts = raw.split(";"); //$NON-NLS-1$
        for (String part : parts) {
            int separator = part.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String key = part.substring(0, separator).trim();
            String value = normalizeCookieValue(part.substring(separator + 1).trim());
            result.put(key, value);
        }
        return result;
    }

    static String normalizeCookieValue(String raw) {
        if (raw == null) {
            return ""; //$NON-NLS-1$
        }
        String value = raw.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) { //$NON-NLS-1$ //$NON-NLS-2$
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private Map<String, String> parseUrlEncoded(String raw) {
        Map<String, String> result = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String pair : raw.split("&")) { //$NON-NLS-1$
            if (pair.isBlank()) {
                continue;
            }
            int separator = pair.indexOf('=');
            String key = separator >= 0 ? pair.substring(0, separator) : pair;
            String value = separator >= 0 ? pair.substring(separator + 1) : ""; //$NON-NLS-1$
            result.put(key, value);
        }
        return result;
    }

    private Map<String, Object> readJsonMap(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod()) && !"PUT".equalsIgnoreCase(exchange.getRequestMethod())) { //$NON-NLS-1$ //$NON-NLS-2$
            return Map.of();
        }
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        if (bytes.length == 0) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = gson.fromJson(new String(bytes, StandardCharsets.UTF_8), Map.class);
            return map != null ? map : Map.of();
        } catch (JsonSyntaxException e) {
            throw new IOException("Некорректное JSON-тело запроса", e); //$NON-NLS-1$
        }
    }

    private RemoteCommandRequest readCommand(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        if (bytes.length == 0) {
            return new RemoteCommandRequest();
        }
        try {
            RemoteCommandRequest request = gson.fromJson(new String(bytes, StandardCharsets.UTF_8), RemoteCommandRequest.class);
            return request != null ? request : new RemoteCommandRequest();
        } catch (JsonSyntaxException e) {
            throw new IOException("Некорректное JSON-тело запроса", e); //$NON-NLS-1$
        }
    }

    private int statusFor(RemoteCommandResult result) {
        if (result == null || result.isOk()) {
            return result != null && "confirmation_required".equals(result.getCode()) ? 202 : 200; //$NON-NLS-1$
        }
        return switch (result.getCode()) {
            case "unauthorized" -> 401; //$NON-NLS-1$
            case "lease_conflict", "agent_busy" -> 409; //$NON-NLS-1$ //$NON-NLS-2$
            case "missing_token", "missing_client", "bridge_unavailable", "provider_unavailable", "no_active_run", "no_pending_confirmation" -> 422; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
            default -> 403;
        };
    }

    private void writeJson(HttpExchange exchange, int statusCode, Object payload) throws IOException {
        byte[] bytes = gson.toJson(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8"); //$NON-NLS-1$ //$NON-NLS-2$
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void trace(RemoteAuthSession session, TraceEventType type, Map<String, Object> data) {
        if (session == null || session.traceSession == null) {
            return;
        }
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("client_id", session.clientId); //$NON-NLS-1$
        payload.put("session_token", session.sessionToken); //$NON-NLS-1$
        if (data != null) {
            payload.putAll(data);
        }
        session.traceSession.writeMcpEvent(type, null, payload);
    }

    private String stringValue(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool.booleanValue();
        }
        if (value == null) {
            return false;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private int parseInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static final class RemoteAuthSession {
        private final String sessionToken = UUID.randomUUID().toString();
        private final String clientId = UUID.randomUUID().toString();
        private final Instant createdAt = Instant.now();
        private volatile Instant expiresAt = createdAt.plus(SESSION_TTL);
        private final AgentTraceSession traceSession = AgentTraceSession.startMcpSession(
                "remote-" + clientId, //$NON-NLS-1$
                "remote-web", //$NON-NLS-1$
                null,
                "/remote/api"); //$NON-NLS-1$

        private boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }

        private void touch() {
            expiresAt = Instant.now().plus(SESSION_TTL);
        }

        private void close() {
            if (traceSession != null) {
                traceSession.markCompleted("STOPPED", null); //$NON-NLS-1$
            }
        }
    }

    private final class SseConnection implements AgentSessionController.RemoteEventListener {
        private final OutputStream outputStream;
        private final RemoteAuthSession authSession;

        private SseConnection(OutputStream outputStream, RemoteAuthSession authSession) {
            this.outputStream = outputStream;
            this.authSession = authSession;
        }

        @Override
        public void onRemoteEvent(RemoteEvent event) {
            send(event);
        }

        private synchronized boolean send(RemoteEvent event) {
            try {
                String payload = "event: " + event.getType() + "\n" //$NON-NLS-1$ //$NON-NLS-2$
                        + "data: " + gson.toJson(event) + "\n\n"; //$NON-NLS-1$ //$NON-NLS-2$
                outputStream.write(payload.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
                return true;
            } catch (IOException e) {
                trace(authSession, TraceEventType.REMOTE_COMMAND, Map.of("action", "sse_disconnect")); //$NON-NLS-1$ //$NON-NLS-2$
                return false;
            }
        }

        private void close() {
            try {
                outputStream.close();
            } catch (IOException e) {
                // Ignore close failures.
            }
        }
    }
}
