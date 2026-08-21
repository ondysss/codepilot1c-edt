/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.memory.project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import com.codepilot1c.core.agent.AgentResult;
import com.codepilot1c.core.agent.profiles.InitAgentProfile;
import com.codepilot1c.core.remote.AgentSessionController;

/**
 * Starts the dedicated init-profile workflow that creates or refreshes
 * project-level Code.md memory.
 */
public class ProjectMemoryInitializationService {

    public enum Mode {
        CREATE,
        UPDATE
    }

    public enum Status {
        SUCCESS,
        AGENT_FAILED,
        CODE_MD_NOT_WRITTEN,
        PROVIDER_UNAVAILABLE,
        AGENT_BUSY
    }

    @FunctionalInterface
    public interface AgentLauncher {
        CompletableFuture<AgentResult> launch(String prompt, String profileId);

        default CompletableFuture<AgentResult> launch(String prompt, String profileId,
                String projectPath, String sessionId) {
            return launch(prompt, profileId);
        }
    }

    private final ProjectMemoryContextService memoryContextService;
    private final AgentLauncher launcher;

    public ProjectMemoryInitializationService() {
        this(new ProjectMemoryContextService(), new AgentLauncher() {
            @Override
            public CompletableFuture<AgentResult> launch(String prompt, String profileId) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("Execution identity is required")); //$NON-NLS-1$
            }

            @Override
            public CompletableFuture<AgentResult> launch(String prompt, String profileId,
                    String projectPath, String sessionId) {
                return AgentSessionController.getInstance().submitFromDesktopFreshScoped(
                        prompt, profileId, projectPath, sessionId);
            }
        });
    }

    public ProjectMemoryInitializationService(ProjectMemoryContextService memoryContextService, AgentLauncher launcher) {
        this.memoryContextService = Objects.requireNonNull(memoryContextService, "memoryContextService"); //$NON-NLS-1$
        this.launcher = Objects.requireNonNull(launcher, "launcher"); //$NON-NLS-1$
    }

    public CompletableFuture<Result> initialize(Request request) {
        Objects.requireNonNull(request, "request"); //$NON-NLS-1$
        if (!request.hasExecutionIdentity()) {
            return CompletableFuture.completedFuture(failure(Status.AGENT_FAILED,
                    "Explicit project and session identity are required", null)); //$NON-NLS-1$
        }
        String prompt = buildPrompt(request);
        MemorySnapshot before = MemorySnapshot.capture(memoryContextService.readFull(request.getProjectRoot()));
        CompletableFuture<AgentResult> run;
        try {
            run = launcher.launch(prompt, InitAgentProfile.ID,
                    request.getProjectRoot().toString(), request.getSessionId());
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(failure(mapLaunchFailure(e), e.getMessage(), null));
        }
        if (run == null) {
            return CompletableFuture.completedFuture(failure(Status.AGENT_FAILED,
                    "Agent launcher did not return a completion future", null)); //$NON-NLS-1$
        }
        CompletableFuture<Result> transformed = run.handle((agentResult, error) -> {
            if (error != null) {
                Throwable cause = unwrap(error);
                return failure(mapLaunchFailure(cause), cause.getMessage(), null);
            }
            if (agentResult == null || !agentResult.isSuccess()) {
                String message = agentResult != null && agentResult.getErrorMessage() != null
                        ? agentResult.getErrorMessage()
                        : "Agent did not complete successfully"; //$NON-NLS-1$
                return failure(Status.AGENT_FAILED, message, null);
            }
            ProjectMemoryContextService.ReadResult memory = memoryContextService.readFull(request.getProjectRoot());
            if (isUsableMemory(memory.getStatus())) {
                MemorySnapshot after = MemorySnapshot.capture(memory);
                if (request.getMode() == Mode.UPDATE && before.isUsable() && !after.differsFrom(before)) {
                    return failure(Status.CODE_MD_NOT_WRITTEN,
                            "Agent completed but project memory file was not updated", memory.getSourcePath()); //$NON-NLS-1$
                }
                return new Result(Status.SUCCESS, "Project memory initialized", memory.getSourcePath()); //$NON-NLS-1$
            }
            return failure(Status.CODE_MD_NOT_WRITTEN,
                    "Agent completed but project memory file was not created", memory.getSourcePath()); //$NON-NLS-1$
        });
        CompletableFuture<Result> result = new CompletableFuture<>();
        transformed.whenComplete((value, error) -> {
            if (error != null) {
                result.completeExceptionally(error);
            } else {
                result.complete(value);
            }
        });
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) {
                transformed.cancel(true);
                run.cancel(true);
            }
        });
        return result;
    }

    String buildPrompt(Request request) {
        String modeInstruction = request.getMode() == Mode.UPDATE
                ? "Обнови существующую проектную память Code.md. Сохрани полезные пользовательские разделы и обнови устаревшие факты." //$NON-NLS-1$
                : "Создай проектную память Code.md на основе структуры проекта."; //$NON-NLS-1$
        return """
                Ты запущен под профилем инициализации проектной памяти.

                Цель:
                %s

                Проект: %s
                Корень проекта: %s
                Целевой путь для write_file: %s

                Правила:
                - Работай в текущем запуске; не запускай подагентов и не делегируй задачу.
                - Используй только доступные инструменты чтения и поиска, чтобы собрать важный контекст проекта.
                - До записи сделай не больше 8 поисковых/читающих вызовов; не пытайся полностью просканировать проект.
                - Не редактируй EDT metadata, .mdo или другие исходные файлы проекта.
                - Обязательно вызови write_file с path="%s", overwrite=true и полным Markdown-содержимым до финального ответа.
                - Если контекста недостаточно, всё равно создай краткий Code.md с проверенными фактами и ограничениями анализа.
                - Не меняй никакие другие файлы.

                Содержимое Code.md должно быть полезным для будущих чат-сессий: архитектура, важные подсистемы,
                соглашения разработки, ограничения окружения и проверенные факты о проекте.
                """.formatted(
                modeInstruction,
                safe(request.getProjectName()),
                request.getProjectRoot().toAbsolutePath().normalize(),
                request.getToolPath(),
                request.getToolPath());
    }

    private static boolean isUsableMemory(ProjectMemoryContextService.Status status) {
        return status == ProjectMemoryContextService.Status.FOUND;
    }

    private record MemorySnapshot(ProjectMemoryContextService.Status status, Path sourcePath, long sizeBytes, String content,
            long modifiedMillis) {

        private static MemorySnapshot capture(ProjectMemoryContextService.ReadResult result) {
            if (result == null) {
                return new MemorySnapshot(ProjectMemoryContextService.Status.MISSING, null, 0, "", -1); //$NON-NLS-1$
            }
            return new MemorySnapshot(
                    result.getStatus(),
                    normalize(result.getSourcePath()),
                    result.getSizeBytes(),
                    result.getContent(),
                    lastModifiedMillis(result.getSourcePath()));
        }

        private boolean isUsable() {
            return isUsableMemory(status);
        }

        private boolean differsFrom(MemorySnapshot other) {
            return status != other.status
                    || !Objects.equals(sourcePath, other.sourcePath)
                    || sizeBytes != other.sizeBytes
                    || modifiedMillis != other.modifiedMillis
                    || !Objects.equals(content, other.content);
        }

        private static Path normalize(Path path) {
            return path != null ? path.toAbsolutePath().normalize() : null;
        }

        private static long lastModifiedMillis(Path path) {
            if (path == null) {
                return -1;
            }
            try {
                return Files.getLastModifiedTime(path).toMillis();
            } catch (IOException e) {
                return -1;
            }
        }
    }

    private static Status mapLaunchFailure(Throwable error) {
        String message = error != null && error.getMessage() != null ? error.getMessage().toLowerCase() : ""; //$NON-NLS-1$
        if (message.contains("already running") || message.contains("agent_busy")) { //$NON-NLS-1$ //$NON-NLS-2$
            return Status.AGENT_BUSY;
        }
        if (message.contains("provider_unavailable") || message.contains("провайдер") || message.contains("provider")) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return Status.PROVIDER_UNAVAILABLE;
        }
        return Status.AGENT_FAILED;
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof CompletionException completion && completion.getCause() != null) {
            return completion.getCause();
        }
        return error;
    }

    private static Result failure(Status status, String message, Path sourcePath) {
        return new Result(status, message != null ? message : "", sourcePath); //$NON-NLS-1$
    }

    private static String safe(String value) {
        return value != null && !value.isBlank() ? value : "unknown"; //$NON-NLS-1$
    }

    public static final class Request {

        private final Mode mode;
        private final Path projectRoot;
        private final String projectName;
        private final String toolPath;
        private final String sessionId;

        public Request(Mode mode, Path projectRoot, String projectName, String toolPath) {
            this(mode, projectRoot, projectName, toolPath, null);
        }

        public Request(Mode mode, Path projectRoot, String projectName,
                String toolPath, String sessionId) {
            this.mode = mode != null ? mode : Mode.CREATE;
            this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize(); //$NON-NLS-1$
            this.projectName = projectName;
            this.toolPath = Objects.requireNonNull(toolPath, "toolPath"); //$NON-NLS-1$
            this.sessionId = sessionId;
        }

        public Mode getMode() {
            return mode;
        }

        public Path getProjectRoot() {
            return projectRoot;
        }

        public String getProjectName() {
            return projectName;
        }

        public String getToolPath() {
            return toolPath;
        }

        public String getSessionId() {
            return sessionId;
        }

        boolean hasExecutionIdentity() {
            return !projectRoot.toString().isBlank()
                    && sessionId != null && !sessionId.isBlank();
        }
    }

    public static final class Result {

        private final Status status;
        private final String message;
        private final Path sourcePath;

        private Result(Status status, String message, Path sourcePath) {
            this.status = Objects.requireNonNull(status, "status"); //$NON-NLS-1$
            this.message = message != null ? message : ""; //$NON-NLS-1$
            this.sourcePath = sourcePath;
        }

        public Status getStatus() {
            return status;
        }

        public String getMessage() {
            return message;
        }

        public Path getSourcePath() {
            return sourcePath;
        }
    }
}
