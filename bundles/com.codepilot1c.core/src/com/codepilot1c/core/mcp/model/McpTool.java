/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp.model;

import java.util.Objects;

import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;

/**
 * Represents an MCP tool definition.
 *
 * <p>Tools are functions that the AI can invoke through MCP servers.</p>
 */
public class McpTool {

    @SerializedName("name")
    private String name;

    @SerializedName("description")
    private String description;

    @SerializedName("inputSchema")
    private JsonObject inputSchema;

    @SerializedName("annotations")
    private JsonObject annotations;

    /**
     * Creates an empty tool.
     */
    public McpTool() {
    }

    /**
     * Creates a tool with name and description.
     *
     * @param name the tool name
     * @param description the tool description
     */
    public McpTool(String name, String description) {
        this.name = name;
        this.description = description;
    }

    /**
     * Creates a tool with all fields.
     *
     * @param name the tool name
     * @param description the tool description
     * @param inputSchema the JSON schema for parameters
     */
    public McpTool(String name, String description, JsonObject inputSchema) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
    }

    /**
     * Returns the tool name.
     *
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the tool name.
     *
     * @param name the name
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Returns the tool description.
     *
     * @return the description
     */
    public String getDescription() {
        return description;
    }

    /**
     * Sets the tool description.
     *
     * @param description the description
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Returns the input schema for the tool parameters.
     *
     * @return the JSON schema
     */
    public JsonObject getInputSchema() {
        return inputSchema;
    }

    /**
     * Sets the input schema.
     *
     * @param inputSchema the JSON schema
     */
    public void setInputSchema(JsonObject inputSchema) {
        this.inputSchema = inputSchema;
    }

    /** Returns standard MCP behavioral annotations supplied by the server. */
    public JsonObject getAnnotations() {
        return annotations;
    }

    /** Sets standard MCP behavioral annotations supplied by the server. */
    public void setAnnotations(JsonObject annotations) {
        this.annotations = annotations;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        McpTool other = (McpTool) obj;
        return Objects.equals(name, other.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return "McpTool[name=" + name + "]";
    }
}
