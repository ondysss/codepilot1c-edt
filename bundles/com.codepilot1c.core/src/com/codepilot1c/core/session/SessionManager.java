/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.session;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;

import com.codepilot1c.core.session.ISessionStore.SessionStoreException;
import com.codepilot1c.core.session.ISessionStore.SessionSummary;

/**
 * Менеджер сессий - центральная точка для работы с сессиями.
 *
 * <p>Предоставляет:</p>
 * <ul>
 *   <li>Создание и управление сессиями</li>
 *   <li>Привязка сессий к проектам Eclipse</li>
 *   <li>Кэширование активной сессии</li>
 *   <li>Уведомления об изменениях</li>
 * </ul>
 */
public class SessionManager {

    private static final String PLUGIN_ID = "com.codepilot1c.core";
    private static final ILog LOG = Platform.getLog(SessionManager.class);

    private static SessionManager instance;

    private final ISessionStore sessionStore;
    private final List<ISessionChangeListener> listeners = new CopyOnWriteArrayList<>();
    private Session currentSession;

    /**
     * Возвращает единственный экземпляр менеджера.
     */
    public static synchronized SessionManager getInstance() {
        if (instance == null) {
            instance = new SessionManager(new FileSessionStore());
        }
        return instance;
    }

    /**
     * Создает менеджер с указанным хранилищем.
     */
    public SessionManager(ISessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    /**
     * Создает новую сессию.
     *
     * @return новая сессия
     */
    public Session createSession() {
        Session session = new Session();
        notifySessionCreated(session);
        return session;
    }

    /**
     * Создает новую сессию для проекта.
     *
     * @param project проект Eclipse
     * @return новая сессия
     */
    public Session createSessionForProject(IProject project) {
        Session session = new Session();

        if (project != null && project.exists()) {
            session.setProjectName(project.getName());
            IPath location = project.getLocation();
            if (location != null) {
                session.setProjectPath(location.toOSString());
            }
        }

        notifySessionCreated(session);
        return session;
    }

    /**
     * Создает сессию для текущего выбранного проекта.
     *
     * @return новая сессия или сессия без привязки к проекту
     */
    public Session createSessionForCurrentProject() {
        IProject project = getCurrentProject();
        if (project != null) {
            return createSessionForProject(project);
        }
        return createSession();
    }

    /**
     * Сохраняет сессию.
     *
     * @param session сессия
     * @return true если сохранено успешно
     */
    public boolean saveSession(Session session) {
        try {
            sessionStore.save(session);
            notifySessionSaved(session);
            return true;
        } catch (SessionStoreException e) {
            logError("Ошибка сохранения сессии: " + session.getId(), e);
            return false;
        }
    }

    /**
     * Загружает сессию по ID.
     *
     * @param sessionId ID сессии
     * @return сессия или empty
     */
    public Optional<Session> loadSession(String sessionId) {
        try {
            Optional<Session> session = sessionStore.load(sessionId);
            session.ifPresent(this::notifySessionLoaded);
            return session;
        } catch (SessionStoreException e) {
            logError("Ошибка загрузки сессии: " + sessionId, e);
            return Optional.empty();
        }
    }

    /**
     * Удаляет сессию.
     *
     * @param sessionId ID сессии
     * @return true если удалено успешно
     */
    public boolean deleteSession(String sessionId) {
        try {
            boolean deleted = sessionStore.delete(sessionId);
            if (deleted) {
                notifySessionDeleted(sessionId);
                // Clear current session if it was deleted
                if (currentSession != null && currentSession.getId().equals(sessionId)) {
                    currentSession = null;
                }
            }
            return deleted;
        } catch (SessionStoreException e) {
            logError("Ошибка удаления сессии: " + sessionId, e);
            return false;
        }
    }

    /**
     * Возвращает список всех сессий.
     */
    public List<SessionSummary> listAllSessions() {
        try {
            return sessionStore.listAll();
        } catch (SessionStoreException e) {
            logError("Ошибка получения списка сессий", e);
            return List.of();
        }
    }

    /**
     * Возвращает список сессий для проекта.
     *
     * @param project проект
     */
    public List<SessionSummary> listSessionsForProject(IProject project) {
        if (project == null || !project.exists()) {
            return List.of();
        }

        IPath location = project.getLocation();
        if (location == null) {
            return List.of();
        }

        try {
            return sessionStore.listByProject(location.toOSString());
        } catch (SessionStoreException e) {
            logError("Ошибка получения сессий для проекта: " + project.getName(), e);
            return List.of();
        }
    }

    /**
     * Возвращает список недавних сессий.
     *
     * @param limit максимальное количество
     */
    public List<SessionSummary> listRecentSessions(int limit) {
        try {
            return sessionStore.listRecent(limit);
        } catch (SessionStoreException e) {
            logError("Ошибка получения недавних сессий", e);
            return List.of();
        }
    }

    /**
     * Текущая активная сессия.
     */
    public Session getCurrentSession() {
        return currentSession;
    }

    /**
     * Устанавливает текущую сессию.
     */
    public void setCurrentSession(Session session) {
        Session oldSession = this.currentSession;
        this.currentSession = session;
        notifyCurrentSessionChanged(oldSession, session);
    }

    /**
     * Возвращает или создает текущую сессию.
     * If the session exists but has no project binding, attempts to bind it
     * to the current project (projectPath must never remain null in normal usage).
     */
    public Session getOrCreateCurrentSession() {
        if (currentSession == null) {
            currentSession = createSessionForCurrentProject();
        } else {
            // Ensure existing session is bound to a project
            ensureSessionProjectBound(currentSession);
        }
        return currentSession;
    }

    /**
     * Ensures the session is bound to a project.
     * If projectPath is null, resolves and binds the current project.
     * Should be called when a project is opened or activated.
     */
    public void ensureSessionProjectBound(Session session) {
        if (session != null && session.getProjectPath() == null) {
            IProject project = getCurrentProject();
            if (project != null && project.exists()) {
                session.setProjectName(project.getName());
                IPath location = project.getLocation();
                if (location != null) {
                    session.setProjectPath(location.toOSString());
                }
            }
        }
    }

    /**
     * Очищает текущую сессию и создает новую.
     */
    public Session startNewSession() {
        LOG.info("startNewSession: currentSession=" //$NON-NLS-1$
                + (currentSession != null ? currentSession.getId() : "null") //$NON-NLS-1$
                + ", isEmpty=" + (currentSession != null ? currentSession.isEmpty() : "N/A") //$NON-NLS-1$ //$NON-NLS-2$
                + ", messageCount=" + (currentSession != null ? currentSession.getMessages().size() : 0) //$NON-NLS-1$
                + ", projectPath=" + (currentSession != null ? currentSession.getProjectPath() : "null")); //$NON-NLS-1$ //$NON-NLS-2$

        // Save current session if it has messages
        if (currentSession != null && !currentSession.isEmpty()) {
            currentSession.setStatus(Session.SessionStatus.COMPLETED);
            // IMPORTANT #9 fix: only notify completed if save succeeds
            boolean saved = saveSession(currentSession);
            LOG.info("startNewSession: session saved=" + saved); //$NON-NLS-1$
            if (saved) {
                notifySessionCompleted(currentSession);
            }
        }

        currentSession = createSessionForCurrentProject();
        LOG.info("startNewSession: new session created, id=" + currentSession.getId() //$NON-NLS-1$
                + ", projectPath=" + currentSession.getProjectPath()); //$NON-NLS-1$
        return currentSession;
    }

    /**
     * Архивирует сессию.
     */
    public void archiveSession(String sessionId) {
        loadSession(sessionId).ifPresent(session -> {
            session.setStatus(Session.SessionStatus.ARCHIVED);
            // IMPORTANT #9 fix: only notify completed if save succeeds
            if (saveSession(session)) {
                notifySessionCompleted(session);
            }
        });
    }

    /**
     * Очищает старые архивированные сессии.
     *
     * @param maxAgeDays максимальный возраст в днях
     * @return количество удаленных сессий
     */
    public int purgeOldSessions(int maxAgeDays) {
        try {
            return sessionStore.purgeOldSessions(maxAgeDays);
        } catch (SessionStoreException e) {
            logError("Ошибка очистки старых сессий", e);
            return 0;
        }
    }

    // --- Project helpers ---

    /**
     * Находит проект Eclipse по пути.
     *
     * @param projectPath абсолютный путь к проекту
     * @return проект или null
     */
    public IProject findProjectByPath(String projectPath) {
        if (projectPath == null || projectPath.isEmpty()) {
            return null;
        }

        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IWorkspaceRoot root = workspace.getRoot();

        for (IProject project : root.getProjects()) {
            if (project.exists() && project.isOpen()) {
                IPath location = project.getLocation();
                if (location != null && location.toOSString().equals(projectPath)) {
                    return project;
                }
            }
        }

        return null;
    }

    /**
     * Возвращает текущий проект 1С.
     *
     * <p>Resolution order:</p>
     * <ol>
     *   <li>V8ProjectManager: first managed V8 project in workspace</li>
     *   <li>Workspace fallback: first open project</li>
     * </ol>
     */
    private IProject getCurrentProject() {
        // Try V8ProjectManager first — this gives us real 1C projects
        try {
            com._1c.g5.v8.dt.core.platform.IV8ProjectManager v8pm =
                    com.codepilot1c.core.internal.VibeCorePlugin.getDefault().getV8ProjectManager();
            if (v8pm != null) {
                for (com._1c.g5.v8.dt.core.platform.IV8Project v8p : v8pm.getProjects()) {
                    IProject project = v8p.getProject();
                    if (project != null && project.exists() && project.isOpen()) {
                        return project;
                    }
                }
            }
        } catch (Exception e) {
            // V8ProjectManager not available (startup race or non-EDT workspace)
        }

        // Fallback: first open project in workspace
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IWorkspaceRoot root = workspace.getRoot();
        for (IProject project : root.getProjects()) {
            if (project.exists() && project.isOpen()) {
                return project;
            }
        }

        return null;
    }

    /**
     * Привязывает сессию к проекту.
     */
    public void bindSessionToProject(Session session, IProject project) {
        if (session == null) {
            return;
        }

        if (project != null && project.exists()) {
            session.setProjectName(project.getName());
            IPath location = project.getLocation();
            if (location != null) {
                session.setProjectPath(location.toOSString());
            }
        } else {
            session.setProjectName(null);
            session.setProjectPath(null);
        }
    }

    // --- Listener management ---

    /**
     * Добавляет слушатель изменений.
     */
    public void addListener(ISessionChangeListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * Удаляет слушатель.
     */
    public void removeListener(ISessionChangeListener listener) {
        listeners.remove(listener);
    }

    private void notifySessionCreated(Session session) {
        for (ISessionChangeListener listener : listeners) {
            try {
                listener.onSessionCreated(session);
            } catch (Exception e) {
                logWarning("Ошибка в слушателе событий сессий", e);
            }
        }
    }

    private void notifySessionSaved(Session session) {
        for (ISessionChangeListener listener : listeners) {
            try {
                listener.onSessionSaved(session);
            } catch (Exception e) {
                logWarning("Ошибка в слушателе событий сессий", e);
            }
        }
    }

    private void notifySessionLoaded(Session session) {
        for (ISessionChangeListener listener : listeners) {
            try {
                listener.onSessionLoaded(session);
            } catch (Exception e) {
                logWarning("Ошибка в слушателе событий сессий", e);
            }
        }
    }

    private void notifySessionDeleted(String sessionId) {
        for (ISessionChangeListener listener : listeners) {
            try {
                listener.onSessionDeleted(sessionId);
            } catch (Exception e) {
                logWarning("Ошибка в слушателе событий сессий", e);
            }
        }
    }

    private void notifySessionCompleted(Session session) {
        LOG.info("notifySessionCompleted: session=" + session.getId() //$NON-NLS-1$
                + ", listeners=" + listeners.size() //$NON-NLS-1$
                + ", messages=" + session.getMessages().size() //$NON-NLS-1$
                + ", projectPath=" + session.getProjectPath()); //$NON-NLS-1$
        for (ISessionChangeListener listener : listeners) {
            try {
                LOG.info("notifySessionCompleted: calling " + listener.getClass().getSimpleName()); //$NON-NLS-1$
                listener.onSessionCompleted(session);
            } catch (Exception e) {
                logWarning("Ошибка в слушателе событий сессий (completed)", e);
            }
        }
    }

    private void notifyCurrentSessionChanged(Session oldSession, Session newSession) {
        for (ISessionChangeListener listener : listeners) {
            try {
                listener.onCurrentSessionChanged(oldSession, newSession);
            } catch (Exception e) {
                logWarning("Ошибка в слушателе событий сессий", e);
            }
        }
    }

    // --- Logging ---

    private void logError(String message, Throwable error) {
        LOG.log(new Status(IStatus.ERROR, PLUGIN_ID, message, error));
    }

    private void logWarning(String message, Throwable error) {
        LOG.log(new Status(IStatus.WARNING, PLUGIN_ID, message, error));
    }

    /**
     * Интерфейс слушателя изменений сессий.
     */
    public interface ISessionChangeListener {
        /** Вызывается при создании сессии */
        default void onSessionCreated(Session session) {}

        /** Вызывается при сохранении сессии */
        default void onSessionSaved(Session session) {}

        /** Вызывается при загрузке сессии */
        default void onSessionLoaded(Session session) {}

        /** Вызывается при удалении сессии */
        default void onSessionDeleted(String sessionId) {}

        /** Вызывается при смене текущей сессии */
        default void onCurrentSessionChanged(Session oldSession, Session newSession) {}

        /** Вызывается при завершении сессии (status = COMPLETED или ARCHIVED) */
        default void onSessionCompleted(Session session) {}
    }
}
