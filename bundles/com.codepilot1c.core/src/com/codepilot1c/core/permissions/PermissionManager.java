/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.permissions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;

import com.codepilot1c.core.permissions.IPermissionCallback.PermissionRequest;
import com.codepilot1c.core.permissions.IPermissionCallback.PermissionResponse;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

/**
 * Реализация менеджера разрешений.
 *
 * <p>Поддерживает:</p>
 * <ul>
 *   <li>Постоянные правила (сохраняются в preferences)</li>
 *   <li>Временные правила сессии (очищаются при перезапуске)</li>
 *   <li>Запрос подтверждения через callback</li>
 *   <li>Правила по умолчанию</li>
 * </ul>
 */
public class PermissionManager implements IPermissionManager {

    private static final String PLUGIN_ID = "com.codepilot1c.core";
    private static final String PREF_RULES = "permission.rules";
    private static final ILog LOG = Platform.getLog(PermissionManager.class);

    private static PermissionManager instance;

    private final List<PermissionRule> rules = new CopyOnWriteArrayList<>();
    private final Map<String, PermissionDecision> sessionPermissions = new ConcurrentHashMap<>();
    private final Gson gson = new GsonBuilder().create();
    private volatile IPermissionCallback callback;

    /**
     * Возвращает единственный экземпляр менеджера.
     */
    public static synchronized PermissionManager getInstance() {
        if (instance == null) {
            instance = new PermissionManager();
            instance.loadRules();
        }
        return instance;
    }

    /**
     * Создает менеджер разрешений.
     */
    public PermissionManager() {
        loadDefaultRules();
    }

    @Override
    public CompletableFuture<PermissionDecision> check(
            String toolName, String action, Map<String, Object> context) {

        // Extract resource from context
        String resource = extractResource(context);

        // Check session permissions first
        String sessionKey = toolName + ":" + resource;
        PermissionDecision sessionDecision = sessionPermissions.get(sessionKey);
        if (sessionDecision != null) {
            return CompletableFuture.completedFuture(sessionDecision);
        }

        // Check rules
        PermissionDecision ruleDecision = checkSync(toolName, resource);

        // If ASK, request confirmation from user
        if (ruleDecision == PermissionDecision.ASK && callback != null) {
            return requestUserConfirmation(toolName, action, resource, context);
        }

        return CompletableFuture.completedFuture(ruleDecision);
    }

    @Override
    public PermissionDecision checkSync(String toolName, String resource) {
        // Check session permissions first
        String sessionKey = toolName + ":" + resource;
        PermissionDecision sessionDecision = sessionPermissions.get(sessionKey);
        if (sessionDecision != null) {
            return sessionDecision;
        }

        // Find matching rules, sorted by priority (highest first)
        List<PermissionRule> matchingRules = new ArrayList<>();

        for (PermissionRule rule : rules) {
            if (rule.matchesTool(toolName) && rule.matches(resource)) {
                matchingRules.add(rule);
            }
        }

        if (matchingRules.isEmpty()) {
            // No rules match - default to ASK
            return PermissionDecision.ASK;
        }

        // Sort by priority (highest first)
        matchingRules.sort(Comparator.comparingInt(PermissionRule::getPriority).reversed());

        // Return decision of highest priority rule
        return matchingRules.get(0).getDecision();
    }

    /**
     * Запрашивает подтверждение у пользователя через callback.
     */
    private CompletableFuture<PermissionDecision> requestUserConfirmation(
            String toolName, String action, String resource, Map<String, Object> context) {

        IPermissionCallback cb = callback;
        if (cb == null) {
            // No callback - deny by default
            return CompletableFuture.completedFuture(PermissionDecision.DENY);
        }

        PermissionRequest request = new PermissionRequest(
                toolName,
                getToolDescription(toolName),
                action,
                resource,
                context,
                isDestructiveTool(toolName)
        );

        return cb.requestPermission(request)
                .thenApply(response -> {
                    PermissionDecision decision = response.getDecision();

                    // Remember if requested
                    if (response.isRememberForSession()) {
                        addSessionPermission(toolName, resource, decision);
                    } else if (response.isRememberPermanently()) {
                        // Add permanent rule
                        PermissionRule rule = PermissionRule.builder(toolName, decision)
                                .forResourcePattern(resource)
                                .withPriority(100) // High priority for user decisions
                                .withDescription("Пользовательское правило")
                                .build();
                        addRule(rule);
                        saveRules();
                    }

                    return decision;
                })
                .exceptionally(error -> {
                    logError("Ошибка при запросе подтверждения", error);
                    return PermissionDecision.DENY;
                });
    }

    /**
     * Извлекает ресурс из контекста.
     */
    private String extractResource(Map<String, Object> context) {
        if (context == null) {
            return "*";
        }

        // Try common keys
        for (String key : new String[]{"path", "file", "filePath", "command", "resource"}) {
            Object value = context.get(key);
            if (value != null) {
                return value.toString();
            }
        }

        return "*";
    }

    /**
     * Возвращает описание инструмента.
     */
    private String getToolDescription(String toolName) {
        // This could be enhanced to look up tool descriptions from registry
        switch (toolName) {
            case "shell":
                return "Выполнение shell команд";
            case "read_file":
                return "Чтение файлов";
            case "write_file":
                return "Создание файлов";
            case "edit_file":
                return "Редактирование файлов";
            case "glob":
                return "Поиск файлов по паттерну";
            case "grep":
                return "Поиск в содержимом файлов";
            default:
                return "Инструмент: " + toolName;
        }
    }

    /**
     * Проверяет, является ли инструмент деструктивным.
     */
    private boolean isDestructiveTool(String toolName) {
        switch (toolName) {
            case "shell":
            case "write_file":
            case "edit_file":
                return true;
            default:
                return false;
        }
    }

    @Override
    public List<PermissionRule> getRulesForTool(String toolName) {
        List<PermissionRule> result = new ArrayList<>();
        for (PermissionRule rule : rules) {
            if (rule.matchesTool(toolName)) {
                result.add(rule);
            }
        }
        return result;
    }

    @Override
    public List<PermissionRule> getAllRules() {
        return Collections.unmodifiableList(new ArrayList<>(rules));
    }

    @Override
    public void addRule(PermissionRule rule) {
        if (rule != null) {
            // Remove existing rule with same tool+pattern
            rules.removeIf(r ->
                    r.getToolName().equals(rule.getToolName()) &&
                    String.valueOf(r.getResourcePattern()).equals(String.valueOf(rule.getResourcePattern())));
            rules.add(rule);
        }
    }

    @Override
    public boolean removeRule(PermissionRule rule) {
        return rules.remove(rule);
    }

    @Override
    public void clearRules() {
        rules.clear();
    }

    @Override
    public void loadDefaultRules() {
        rules.clear();

        // Read tools - generally safe
        addRule(PermissionRule.allow("read_file")
                .withPriority(10)
                .withDescription("Чтение файлов разрешено")
                .forAllResources());

        addRule(PermissionRule.allow("glob")
                .withPriority(10)
                .withDescription("Поиск файлов разрешен")
                .forAllResources());

        addRule(PermissionRule.allow("grep")
                .withPriority(10)
                .withDescription("Поиск в файлах разрешен")
                .forAllResources());

        addRule(PermissionRule.allow("list_files")
                .withPriority(10)
                .withDescription("Список файлов разрешен")
                .forAllResources());

        // Write tools - ask by default
        addRule(PermissionRule.ask("edit_file")
                .withPriority(10)
                .withDescription("Редактирование файлов требует подтверждения")
                .forAllResources());

        addRule(PermissionRule.ask("write_file")
                .withPriority(10)
                .withDescription("Создание файлов требует подтверждения")
                .forAllResources());

        // Shell - ask by default, deny dangerous patterns
        addRule(PermissionRule.deny("shell")
                .forResourcePattern("rm -rf *")
                .withPriority(100)
                .withDescription("Опасные команды запрещены")
                .build());

        addRule(PermissionRule.deny("shell")
                .forResourcePattern("*rm -rf /*")
                .withPriority(100)
                .withDescription("Опасные команды запрещены")
                .build());

        addRule(PermissionRule.deny("shell")
                .forResourcePattern("*format*")
                .withPriority(90)
                .withDescription("Команды форматирования запрещены")
                .build());

        addRule(PermissionRule.deny("shell")
                .forResourcePattern("*shutdown*")
                .withPriority(90)
                .withDescription("Системные команды запрещены")
                .build());

        addRule(PermissionRule.deny("shell")
                .forResourcePattern("*reboot*")
                .withPriority(90)
                .withDescription("Системные команды запрещены")
                .build());

        // Safe shell commands
        addRule(PermissionRule.allow("shell")
                .forResourcePattern("git *")
                .withPriority(50)
                .withDescription("Git команды разрешены")
                .build());

        addRule(PermissionRule.allow("shell")
                .forResourcePattern("mvn *")
                .withPriority(50)
                .withDescription("Maven команды разрешены")
                .build());

        addRule(PermissionRule.allow("shell")
                .forResourcePattern("gradle *")
                .withPriority(50)
                .withDescription("Gradle команды разрешены")
                .build());

        addRule(PermissionRule.allow("shell")
                .forResourcePattern("npm *")
                .withPriority(50)
                .withDescription("NPM команды разрешены")
                .build());

        // Default shell rule - ask
        addRule(PermissionRule.ask("shell")
                .withPriority(1)
                .withDescription("Shell команды требуют подтверждения")
                .forAllResources());
    }

    @Override
    public void setCallback(IPermissionCallback callback) {
        this.callback = callback;
    }

    @Override
    public IPermissionCallback getCallback() {
        return callback;
    }

    @Override
    public void addSessionPermission(String toolName, String resource, PermissionDecision decision) {
        String key = toolName + ":" + resource;
        sessionPermissions.put(key, decision);
    }

    @Override
    public void clearSessionPermissions() {
        sessionPermissions.clear();
    }

    @Override
    public void saveRules() {
        try {
            IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(PLUGIN_ID);

            // Serialize rules to JSON
            List<RuleData> ruleDataList = new ArrayList<>();
            for (PermissionRule rule : rules) {
                ruleDataList.add(RuleData.from(rule));
            }

            String json = gson.toJson(ruleDataList);
            prefs.put(PREF_RULES, json);
            prefs.flush();

            logInfo("Правила разрешений сохранены: " + rules.size());
        } catch (BackingStoreException e) {
            logError("Ошибка сохранения правил разрешений", e);
        }
    }

    @Override
    public void loadRules() {
        try {
            IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(PLUGIN_ID);
            String json = prefs.get(PREF_RULES, null);

            if (json != null && !json.isEmpty()) {
                List<RuleData> ruleDataList = gson.fromJson(json,
                        new TypeToken<List<RuleData>>(){}.getType());

                if (ruleDataList != null) {
                    rules.clear();
                    for (RuleData data : ruleDataList) {
                        rules.add(data.toRule());
                    }
                    logInfo("Правила разрешений загружены: " + rules.size());
                    return;
                }
            }
        } catch (Exception e) {
            logError("Ошибка загрузки правил разрешений", e);
        }

        // Fall back to defaults
        loadDefaultRules();
    }

    // --- Logging ---

    private void logInfo(String message) {
        LOG.log(new Status(IStatus.INFO, PLUGIN_ID, message));
    }

    private void logError(String message, Throwable error) {
        LOG.log(new Status(IStatus.ERROR, PLUGIN_ID, message, error));
    }

    /**
     * DTO для сериализации правила.
     */
    private static class RuleData {
        String toolName;
        String decision;
        String resourcePattern;
        boolean isGlob;
        int priority;
        String description;

        static RuleData from(PermissionRule rule) {
            RuleData data = new RuleData();
            data.toolName = rule.getToolName();
            data.decision = rule.getDecision().name();
            data.resourcePattern = rule.getResourcePattern();
            data.isGlob = rule.isGlob();
            data.priority = rule.getPriority();
            data.description = rule.getDescription();
            return data;
        }

        PermissionRule toRule() {
            PermissionRule.Builder builder = PermissionRule.builder(
                    toolName,
                    PermissionDecision.valueOf(decision)
            );

            if (isGlob) {
                builder.forResourcePattern(resourcePattern);
            } else {
                builder.forResourceRegex(resourcePattern);
            }

            return builder
                    .withPriority(priority)
                    .withDescription(description)
                    .build();
        }
    }
}
