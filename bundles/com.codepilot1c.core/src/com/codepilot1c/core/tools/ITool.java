/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
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

    /**
     * Returns the category of this tool for surface grouping.
     *
     * @return category string (e.g., "file", "bsl", "metadata", "dcs", "qa", "git")
     */
    default String getCategory() {
        return "general"; //$NON-NLS-1$
    }

    /**
     * Returns an optional explicit public surface category for contributor guidance.
     *
     * @return explicit surface category id, or blank to infer from other metadata
     */
    default String getSurfaceCategory() {
        return ""; //$NON-NLS-1$
    }

    /**
     * Returns whether this tool mutates project state (files, metadata, git).
     *
     * @return true if the tool can change state
     */
    default boolean isMutating() {
        return isDestructive();
    }

    /**
     * Returns whether this tool requires a validation token for EDT mutations.
     *
     * @return true if a validation token is needed
     */
    default boolean requiresValidationToken() {
        return false;
    }

    /**
     * Returns tags for tool classification and filtering.
     *
     * @return set of tag strings (e.g., "read-only", "edt", "workspace")
     */
    default Set<String> getTags() {
        return Collections.emptySet();
    }
}
