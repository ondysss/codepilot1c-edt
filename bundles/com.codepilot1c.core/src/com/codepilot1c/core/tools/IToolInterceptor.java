/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import java.util.Map;
import java.util.Optional;

/**
 * Interceptor for tool execution. Allows modifying arguments before execution
 * and processing results after execution.
 *
 * <p>Interceptors are invoked in registration order (sorted by priority).
 * If any interceptor returns empty from {@link #beforeToolCall}, the tool
 * execution is cancelled.</p>
 */
public interface IToolInterceptor {

    /**
     * Called before a tool is executed. May modify arguments or cancel execution.
     *
     * @param toolName the name of the tool being called
     * @param args the tool arguments
     * @return modified args to proceed, or empty to cancel execution
     */
    Optional<Map<String, Object>> beforeToolCall(String toolName, Map<String, Object> args);

    /**
     * Called after a tool is executed. May modify the result.
     *
     * @param toolName the name of the tool that was called
     * @param result the tool result
     * @param durationMs execution duration in milliseconds
     * @return modified or original result
     */
    ToolResult afterToolCall(String toolName, ToolResult result, long durationMs);

    /**
     * Returns the priority of this interceptor. Lower values execute first.
     * Default is 100.
     */
    default int getPriority() {
        return 100;
    }
}
