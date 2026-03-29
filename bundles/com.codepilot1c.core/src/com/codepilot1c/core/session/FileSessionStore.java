/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.session;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;

import com.codepilot1c.core.internal.VibeCorePlugin;
import com.codepilot1c.core.model.LlmContentPart;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

/**
 * Файловое хранилище сессий.
 *
 * <p>Сохраняет сессии в JSON файлы в директории плагина.
 * Поддерживает кроссплатформенность (Windows/macOS/Linux).</p>
 *
 * <p>Структура хранения:</p>
 * <pre>
 * {plugin-state}/sessions/
 *   {session-id}.json
 *   {session-id}.json
 *   ...
 * </pre>
 */
public class FileSessionStore implements ISessionStore {

    private static final String PLUGIN_ID = "com.codepilot1c.core";
    private static final ILog LOG = Platform.getLog(FileSessionStore.class);
    private static final String SESSIONS_DIR = "sessions";
    private static final String SESSION_EXTENSION = ".json";

    private final Path sessionsDirectory;
    private final Gson gson;

    /**
     * Создает хранилище в стандартной директории плагина.
     */
    public FileSessionStore() {
        this(getDefaultSessionsDirectory());
    }

    /**
     * Создает хранилище в указанной директории.
     *
     * @param sessionsDirectory директория для хранения сессий
     */
    public FileSessionStore(Path sessionsDirectory) {
        this.sessionsDirectory = sessionsDirectory;
        this.gson = createGson();
        ensureDirectoryExists();
    }

    /**
     * Возвращает директорию хранения сессий по умолчанию.
     */
    private static Path getDefaultSessionsDirectory() {
        VibeCorePlugin plugin = VibeCorePlugin.getDefault();
        if (plugin != null) {
            IPath stateLoc = plugin.getStateLocation();
            return Path.of(stateLoc.toOSString()).resolve(SESSIONS_DIR);
        }
        // Fallback for tests or when plugin not active
        return Path.of(System.getProperty("user.home"), ".vibe-sessions");
    }

    /**
     * Создает Gson с адаптерами для Instant.
     */
    private Gson createGson() {
        return new GsonBuilder()
                .setPrettyPrinting()
                .registerTypeAdapter(Instant.class, new InstantTypeAdapter())
                .create();
    }

    /**
     * Убеждается, что директория существует.
     */
    private void ensureDirectoryExists() {
        try {
            Files.createDirectories(sessionsDirectory);
        } catch (IOException e) {
            logError("Не удалось создать директорию сессий: " + sessionsDirectory, e);
        }
    }

    /**
     * Возвращает путь к файлу сессии.
     */
    private Path getSessionFile(String sessionId) {
        // Sanitize session ID for safe filename
        String safeId = sanitizeFileName(sessionId);
        return sessionsDirectory.resolve(safeId + SESSION_EXTENSION);
    }

    /**
     * Очищает имя файла от небезопасных символов.
     */
    private String sanitizeFileName(String name) {
        // Replace any non-alphanumeric, dash, or underscore with underscore
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    @Override
    public void save(Session session) throws SessionStoreException {
        if (session == null) {
            throw new SessionStoreException("Сессия не может быть null");
        }

        Path file = getSessionFile(session.getId());

        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            SessionData data = SessionData.from(session);
            gson.toJson(data, writer);
            logInfo("Сессия сохранена: " + session.getId());
        } catch (IOException e) {
            throw new SessionStoreException("Ошибка сохранения сессии: " + session.getId(), e);
        }
    }

    @Override
    public Optional<Session> load(String sessionId) throws SessionStoreException {
        if (sessionId == null || sessionId.isEmpty()) {
            return Optional.empty();
        }

        Path file = getSessionFile(sessionId);

        if (!Files.exists(file)) {
            return Optional.empty();
        }

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            SessionData data = gson.fromJson(reader, SessionData.class);
            if (data == null) {
                return Optional.empty();
            }
            Session session = data.toSession();
            logInfo("Сессия загружена: " + sessionId);
            return Optional.of(session);
        } catch (IOException e) {
            throw new SessionStoreException("Ошибка загрузки сессии: " + sessionId, e);
        } catch (Exception e) {
            logError("Ошибка парсинга сессии: " + sessionId, e);
            return Optional.empty();
        }
    }

    @Override
    public boolean delete(String sessionId) throws SessionStoreException {
        if (sessionId == null || sessionId.isEmpty()) {
            return false;
        }

        Path file = getSessionFile(sessionId);

        try {
            boolean deleted = Files.deleteIfExists(file);
            if (deleted) {
                logInfo("Сессия удалена: " + sessionId);
            }
            return deleted;
        } catch (IOException e) {
            throw new SessionStoreException("Ошибка удаления сессии: " + sessionId, e);
        }
    }

    @Override
    public boolean exists(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return false;
        }
        return Files.exists(getSessionFile(sessionId));
    }

    @Override
    public List<SessionSummary> listAll() throws SessionStoreException {
        return listSessionFiles().stream()
                .map(this::loadSummary)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .sorted(Comparator.comparing(SessionSummary::getUpdatedAt).reversed())
                .collect(Collectors.toList());
    }

    @Override
    public List<SessionSummary> listByProject(String projectPath) throws SessionStoreException {
        if (projectPath == null || projectPath.isEmpty()) {
            return new ArrayList<>();
        }

        return listAll().stream()
                .filter(s -> projectPath.equals(getProjectPathForSession(s.getId())))
                .collect(Collectors.toList());
    }

    @Override
    public List<SessionSummary> listRecent(int limit) throws SessionStoreException {
        return listAll().stream()
                .limit(Math.max(0, limit))
                .collect(Collectors.toList());
    }

    @Override
    public int purgeOldSessions(int maxAgeDays) throws SessionStoreException {
        if (maxAgeDays <= 0) {
            return 0;
        }

        Instant cutoff = Instant.now().minus(Duration.ofDays(maxAgeDays));
        int deleted = 0;

        for (Path file : listSessionFiles()) {
            try {
                Optional<SessionSummary> summary = loadSummary(file);
                if (summary.isPresent()) {
                    SessionSummary s = summary.get();
                    // Only purge archived sessions older than cutoff
                    if (s.getStatus() == Session.SessionStatus.ARCHIVED
                            && s.getUpdatedAt().isBefore(cutoff)) {
                        Files.deleteIfExists(file);
                        deleted++;
                    }
                }
            } catch (IOException e) {
                logWarning("Не удалось удалить старую сессию: " + file, e);
            }
        }

        if (deleted > 0) {
            logInfo("Удалено старых сессий: " + deleted);
        }

        return deleted;
    }

    /**
     * Возвращает список файлов сессий.
     */
    private List<Path> listSessionFiles() {
        try (Stream<Path> stream = Files.list(sessionsDirectory)) {
            return stream
                    .filter(p -> p.toString().endsWith(SESSION_EXTENSION))
                    .filter(Files::isRegularFile)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            logError("Ошибка чтения директории сессий", e);
            return new ArrayList<>();
        }
    }

    /**
     * Загружает краткую информацию о сессии из файла.
     */
    private Optional<SessionSummary> loadSummary(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            SessionData data = gson.fromJson(reader, SessionData.class);
            if (data != null) {
                return Optional.of(data.toSummary());
            }
        } catch (Exception e) {
            logWarning("Ошибка чтения сессии: " + file, e);
        }
        return Optional.empty();
    }

    /**
     * Получает путь к проекту для сессии (для фильтрации).
     */
    private String getProjectPathForSession(String sessionId) {
        try {
            Optional<Session> session = load(sessionId);
            return session.map(Session::getProjectPath).orElse(null);
        } catch (SessionStoreException e) {
            return null;
        }
    }

    /**
     * Директория хранения сессий.
     */
    public Path getSessionsDirectory() {
        return sessionsDirectory;
    }

    // --- Logging helpers ---

    private void logInfo(String message) {
        LOG.log(new Status(IStatus.INFO, PLUGIN_ID, message));
    }

    private void logWarning(String message, Throwable error) {
        LOG.log(new Status(IStatus.WARNING, PLUGIN_ID, message, error));
    }

    private void logError(String message, Throwable error) {
        LOG.log(new Status(IStatus.ERROR, PLUGIN_ID, message, error));
    }

    // --- Inner classes for JSON serialization ---

    /**
     * DTO для сериализации сессии в JSON.
     */
    private static class SessionData {
        String id;
        String title;
        String projectPath;
        String projectName;
        String status;
        Instant createdAt;
        Instant updatedAt;
        String systemPrompt;
        String agentProfile;
        int totalTokens;
        List<MessageData> messages;

        static SessionData from(Session session) {
            SessionData data = new SessionData();
            data.id = session.getId();
            data.title = session.getTitle();
            data.projectPath = session.getProjectPath();
            data.projectName = session.getProjectName();
            data.status = session.getStatus().name();
            data.createdAt = session.getCreatedAt();
            data.updatedAt = session.getUpdatedAt();
            data.systemPrompt = session.getSystemPrompt();
            data.agentProfile = session.getAgentProfile();
            data.totalTokens = session.getTotalTokens();
            data.messages = session.getMessages().stream()
                    .map(MessageData::from)
                    .collect(Collectors.toList());
            return data;
        }

        Session toSession() {
            Session session = new Session(id);
            session.setTitle(title);
            session.setProjectPath(projectPath);
            session.setProjectName(projectName);
            session.setStatus(Session.SessionStatus.valueOf(status));
            session.setUpdatedAt(updatedAt);
            session.setSystemPrompt(systemPrompt);
            session.setAgentProfile(agentProfile);
            session.setTotalTokens(totalTokens);

            if (messages != null) {
                for (MessageData msgData : messages) {
                    session.addMessage(msgData.toMessage());
                }
            }

            return session;
        }

        SessionSummary toSummary() {
            return new SessionSummary(
                    id,
                    title,
                    projectName,
                    Session.SessionStatus.valueOf(status),
                    createdAt,
                    updatedAt,
                    messages != null ? messages.size() : 0
            );
        }
    }

    /**
     * DTO для сериализации сообщения в JSON.
     */
    private static class MessageData {
        String id;
        String type;
        String content;
        Instant timestamp;
        List<LlmContentPart> contentParts;
        String toolCallId;
        String toolName;
        boolean isError;
        // Note: toolCalls are stored as part of content for simplicity

        static MessageData from(SessionMessage msg) {
            MessageData data = new MessageData();
            data.id = msg.getId();
            data.type = msg.getType().name();
            data.content = msg.getContent();
            data.timestamp = msg.getTimestamp();
            data.contentParts = msg.getContentParts();
            data.toolCallId = msg.getToolCallId();
            data.toolName = msg.getToolName();
            data.isError = msg.isError();
            return data;
        }

        SessionMessage toMessage() {
            return SessionMessage.builder()
                    .id(id)
                    .type(SessionMessage.MessageType.valueOf(type))
                    .content(content)
                    .contentParts(contentParts)
                    .timestamp(timestamp)
                    .toolCallId(toolCallId)
                    .toolName(toolName)
                    .isError(isError)
                    .build();
        }
    }

    /**
     * TypeAdapter для Instant (ISO-8601 формат).
     */
    private static class InstantTypeAdapter extends TypeAdapter<Instant> {
        @Override
        public void write(JsonWriter out, Instant value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toString());
            }
        }

        @Override
        public Instant read(JsonReader in) throws IOException {
            String value = in.nextString();
            if (value == null || value.isEmpty()) {
                return null;
            }
            return Instant.parse(value);
        }
    }
}
