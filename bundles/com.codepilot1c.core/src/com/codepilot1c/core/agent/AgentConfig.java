/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.agent;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Конфигурация агента для выполнения задачи.
 *
 * <p>Использование Builder:</p>
 * <pre>
 * AgentConfig config = AgentConfig.builder()
 *     .maxSteps(25)
 *     .timeoutMs(300_000)
 *     .enabledTools(Set.of("read_file", "grep"))
 *     .build();
 * </pre>
 */
public class AgentConfig {

    /** Максимальное количество шагов по умолчанию */
    public static final int DEFAULT_MAX_STEPS = 25;

    /** Таймаут по умолчанию (5 минут) */
    public static final long DEFAULT_TIMEOUT_MS = 5 * 60 * 1000L;

    /** Максимальный размер вывода инструмента по умолчанию (100KB) */
    public static final int DEFAULT_MAX_TOOL_OUTPUT_SIZE = 100 * 1024;

    private final int maxSteps;
    private final long timeoutMs;
    private final int maxToolOutputSize;
    private final Set<String> enabledTools;
    private final Set<String> disabledTools;
    private final boolean streamingEnabled;
    private final String systemPromptAddition;
    private final String profileName;
    private final boolean toolGraphEnabled;
    private final com.codepilot1c.core.agent.graph.ToolGraphPolicy toolGraphPolicy;

    private AgentConfig(Builder builder) {
        this.maxSteps = builder.maxSteps;
        this.timeoutMs = builder.timeoutMs;
        this.maxToolOutputSize = builder.maxToolOutputSize;
        this.enabledTools = Collections.unmodifiableSet(new HashSet<>(builder.enabledTools));
        this.disabledTools = Collections.unmodifiableSet(new HashSet<>(builder.disabledTools));
        this.streamingEnabled = builder.streamingEnabled;
        this.systemPromptAddition = builder.systemPromptAddition;
        this.profileName = builder.profileName;
        this.toolGraphEnabled = builder.toolGraphEnabled;
        this.toolGraphPolicy = builder.toolGraphPolicy;
    }

    /**
     * Создает Builder с настройками по умолчанию.
     *
     * @return новый Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Создает конфигурацию по умолчанию.
     *
     * @return конфигурация по умолчанию
     */
    public static AgentConfig defaults() {
        return builder().build();
    }

    /**
     * Максимальное количество шагов (итераций tool loop).
     * После достижения лимита агент завершится.
     *
     * @return максимальное количество шагов
     */
    public int getMaxSteps() {
        return maxSteps;
    }

    /**
     * Общий таймаут выполнения в миллисекундах.
     *
     * @return таймаут в мс
     */
    public long getTimeoutMs() {
        return timeoutMs;
    }

    /**
     * Максимальный размер вывода инструмента в байтах.
     * Больший вывод будет обрезан.
     *
     * @return максимальный размер в байтах
     */
    public int getMaxToolOutputSize() {
        return maxToolOutputSize;
    }

    /**
     * Набор явно включенных инструментов.
     * Если пустой - используются все доступные инструменты.
     *
     * @return набор имен инструментов
     */
    public Set<String> getEnabledTools() {
        return enabledTools;
    }

    /**
     * Набор явно отключенных инструментов.
     *
     * @return набор имен инструментов
     */
    public Set<String> getDisabledTools() {
        return disabledTools;
    }

    /**
     * Включен ли потоковый режим вывода.
     *
     * @return true если streaming включен
     */
    public boolean isStreamingEnabled() {
        return streamingEnabled;
    }

    /**
     * Дополнительный текст для системного промпта.
     *
     * @return дополнительный промпт или null
     */
    public String getSystemPromptAddition() {
        return systemPromptAddition;
    }

    /**
     * Имя профиля агента (build, plan, explore).
     *
     * @return имя профиля или null для дефолтного
     */
    public String getProfileName() {
        return profileName;
    }

    /**
     * Whether tool graph routing is enabled.
     *
     * @return true if enabled
     */
    public boolean isToolGraphEnabled() {
        return toolGraphEnabled;
    }

    /**
     * Tool graph routing policy.
     *
     * @return policy
     */
    public com.codepilot1c.core.agent.graph.ToolGraphPolicy getToolGraphPolicy() {
        return toolGraphPolicy;
    }

    /**
     * Проверяет, разрешен ли инструмент с учетом enabled/disabled списков.
     *
     * @param toolName имя инструмента
     * @return true если инструмент разрешен
     */
    public boolean isToolAllowed(String toolName) {
        if (disabledTools.contains(toolName)) {
            return false;
        }
        if (enabledTools.isEmpty()) {
            return true; // Все разрешены если список пуст
        }
        return enabledTools.contains(toolName);
    }

    @Override
    public String toString() {
        return "AgentConfig{" +
                "maxSteps=" + maxSteps +
                ", timeoutMs=" + timeoutMs +
                ", streaming=" + streamingEnabled +
                ", profile=" + profileName +
                ", toolGraph=" + toolGraphEnabled +
                ", toolGraphPolicy=" + toolGraphPolicy +
                '}';
    }

    /**
     * Builder для AgentConfig.
     */
    public static class Builder {
        private int maxSteps = DEFAULT_MAX_STEPS;
        private long timeoutMs = DEFAULT_TIMEOUT_MS;
        private int maxToolOutputSize = DEFAULT_MAX_TOOL_OUTPUT_SIZE;
        private Set<String> enabledTools = new HashSet<>();
        private Set<String> disabledTools = new HashSet<>();
        private boolean streamingEnabled = true;
        private String systemPromptAddition;
        private String profileName;
        private boolean toolGraphEnabled = true;
        private com.codepilot1c.core.agent.graph.ToolGraphPolicy toolGraphPolicy =
                com.codepilot1c.core.agent.graph.ToolGraphPolicy.ADVISORY;

        private Builder() {
        }

        /**
         * Копирует настройки из существующего конфига.
         *
         * @param config исходный конфиг
         * @return this builder
         */
        public Builder from(AgentConfig config) {
            if (config != null) {
                this.maxSteps = config.maxSteps;
                this.timeoutMs = config.timeoutMs;
                this.maxToolOutputSize = config.maxToolOutputSize;
                this.enabledTools = new HashSet<>(config.enabledTools);
                this.disabledTools = new HashSet<>(config.disabledTools);
                this.streamingEnabled = config.streamingEnabled;
                this.systemPromptAddition = config.systemPromptAddition;
                this.profileName = config.profileName;
                this.toolGraphEnabled = config.toolGraphEnabled;
                this.toolGraphPolicy = config.toolGraphPolicy;
            }
            return this;
        }

        /**
         * Добавляет один инструмент в список разрешенных.
         *
         * @param toolName имя инструмента
         * @return this builder
         */
        public Builder enableTool(String toolName) {
            if (toolName != null && !toolName.isEmpty()) {
                this.enabledTools.add(toolName);
            }
            return this;
        }

        /**
         * Добавляет один инструмент в список запрещенных.
         *
         * @param toolName имя инструмента
         * @return this builder
         */
        public Builder disableTool(String toolName) {
            if (toolName != null && !toolName.isEmpty()) {
                this.disabledTools.add(toolName);
            }
            return this;
        }

        /**
         * Устанавливает максимальное количество шагов.
         *
         * @param maxSteps количество шагов (> 0)
         * @return this builder
         */
        public Builder maxSteps(int maxSteps) {
            if (maxSteps <= 0) {
                throw new IllegalArgumentException("maxSteps must be positive: " + maxSteps);
            }
            this.maxSteps = maxSteps;
            return this;
        }

        /**
         * Устанавливает таймаут выполнения.
         *
         * @param timeoutMs таймаут в миллисекундах (> 0)
         * @return this builder
         */
        public Builder timeoutMs(long timeoutMs) {
            if (timeoutMs <= 0) {
                throw new IllegalArgumentException("timeoutMs must be positive: " + timeoutMs);
            }
            this.timeoutMs = timeoutMs;
            return this;
        }

        /**
         * Устанавливает максимальный размер вывода инструмента.
         *
         * @param maxToolOutputSize размер в байтах (> 0)
         * @return this builder
         */
        public Builder maxToolOutputSize(int maxToolOutputSize) {
            if (maxToolOutputSize <= 0) {
                throw new IllegalArgumentException("maxToolOutputSize must be positive");
            }
            this.maxToolOutputSize = maxToolOutputSize;
            return this;
        }

        /**
         * Устанавливает список разрешенных инструментов.
         *
         * @param tools имена инструментов
         * @return this builder
         */
        public Builder enabledTools(Set<String> tools) {
            this.enabledTools = new HashSet<>(Objects.requireNonNull(tools));
            return this;
        }

        /**
         * Устанавливает список запрещенных инструментов.
         *
         * @param tools имена инструментов
         * @return this builder
         */
        public Builder disabledTools(Set<String> tools) {
            this.disabledTools = new HashSet<>(Objects.requireNonNull(tools));
            return this;
        }

        /**
         * Включает или отключает streaming.
         *
         * @param enabled true для включения
         * @return this builder
         */
        public Builder streamingEnabled(boolean enabled) {
            this.streamingEnabled = enabled;
            return this;
        }

        /**
         * Устанавливает дополнительный системный промпт.
         *
         * @param addition дополнительный текст
         * @return this builder
         */
        public Builder systemPromptAddition(String addition) {
            this.systemPromptAddition = addition;
            return this;
        }

        /**
         * Устанавливает профиль агента.
         *
         * @param profileName имя профиля (build, plan, explore)
         * @return this builder
         */
        public Builder profileName(String profileName) {
            this.profileName = profileName;
            return this;
        }

        public Builder toolGraphEnabled(boolean enabled) {
            this.toolGraphEnabled = enabled;
            return this;
        }

        public Builder toolGraphPolicy(com.codepilot1c.core.agent.graph.ToolGraphPolicy policy) {
            if (policy != null) {
                this.toolGraphPolicy = policy;
            }
            return this;
        }

        /**
         * Создает AgentConfig.
         *
         * @return новый AgentConfig
         */
        public AgentConfig build() {
            return new AgentConfig(this);
        }
    }
}
