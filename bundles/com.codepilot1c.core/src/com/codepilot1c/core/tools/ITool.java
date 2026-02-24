/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for AI tools that can be invoked by the LLM.
 *
 * <p>Tools allow the AI to perform actions like searching code,
 * reading files, and editing code.</p>
 */
public interface ITool {

    /**
     * Returns the unique name of this tool.
     *
     * @return the tool name (e.g., "read_file", "grep")
     */
    String getName();

    /**
     * Returns a description of what this tool does.
     *
     * @return the tool description for the LLM
     */
    String getDescription();

    /**
     * Returns the JSON schema for the tool's parameters.
     *
     * @return the parameter schema as a JSON string
     */
    String getParameterSchema();

    /**
     * Executes the tool with the given parameters.
     *
     * @param parameters the parameters parsed from the LLM's tool call
     * @return a future containing the tool result
     */
    CompletableFuture<ToolResult> execute(Map<String, Object> parameters);

    /**
     * Returns whether this tool requires user confirmation before execution.
     *
     * @return true if confirmation is required
     */
    default boolean requiresConfirmation() {
        return false;
    }

    /**
     * Returns whether this tool can modify files or system state.
     *
     * @return true if the tool is destructive
     */
    default boolean isDestructive() {
        return false;
    }
}
