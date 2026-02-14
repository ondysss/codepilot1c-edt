/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp.model;

import com.google.gson.annotations.SerializedName;

/**
 * Represents a JSON-RPC 2.0 message in MCP protocol.
 *
 * <p>This class handles all three message types:</p>
 * <ul>
 *   <li>Request: has method and id</li>
 *   <li>Notification: has method but no id</li>
 *   <li>Response: has id but no method</li>
 * </ul>
 *
 * <p>Note: The id field is stored as Object because JSON-RPC 2.0 allows
 * both String and Number values for request identifiers.</p>
 */
public class McpMessage {

    @SerializedName("jsonrpc")
    private String jsonrpc = "2.0";

    @SerializedName("method")
    private String method;

    @SerializedName("params")
    private Object params;

    @SerializedName("id")
    private Object id;  // Can be String or Number per JSON-RPC 2.0 spec

    @SerializedName("result")
    private Object result;

    @SerializedName("error")
    private McpError error;

    /**
     * Creates a new empty MCP message.
     */
    public McpMessage() {
    }

    /**
     * Checks if this message is a request (has both method and id).
     *
     * @return true if this is a request message
     */
    public boolean isRequest() {
        return method != null && id != null;
    }

    /**
     * Checks if this message is a notification (has method but no id).
     *
     * @return true if this is a notification message
     */
    public boolean isNotification() {
        return method != null && id == null;
    }

    /**
     * Checks if this message is a response (has id but no method).
     *
     * @return true if this is a response message
     */
    public boolean isResponse() {
        return method == null && id != null;
    }

    /**
     * Checks if this response message contains an error.
     *
     * @return true if this is an error response
     */
    public boolean isErrorResponse() {
        return isResponse() && error != null;
    }

    // Getters and setters

    public String getJsonrpc() {
        return jsonrpc;
    }

    public void setJsonrpc(String jsonrpc) {
        this.jsonrpc = jsonrpc;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public Object getParams() {
        return params;
    }

    public void setParams(Object params) {
        this.params = params;
    }

    /**
     * Returns the message ID as a string.
     *
     * @return the ID, or null if not set
     */
    public String getId() {
        return id != null ? String.valueOf(id) : null;
    }

    /**
     * Sets the message ID.
     *
     * @param id the ID (can be String or Number)
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * Sets the message ID with raw JSON-RPC type.
     *
     * @param id the ID (String or Number)
     */
    public void setRawId(Object id) {
        this.id = id;
    }

    /**
     * Returns the raw ID object (can be String or Number).
     *
     * @return the raw ID object
     */
    public Object getRawId() {
        return id;
    }

    public Object getResult() {
        return result;
    }

    public void setResult(Object result) {
        this.result = result;
    }

    public McpError getError() {
        return error;
    }

    public void setError(McpError error) {
        this.error = error;
    }

    @Override
    public String toString() {
        if (isRequest()) {
            return "McpRequest[id=" + id + ", method=" + method + "]";
        } else if (isNotification()) {
            return "McpNotification[method=" + method + "]";
        } else if (isErrorResponse()) {
            return "McpErrorResponse[id=" + id + ", error=" + error + "]";
        } else if (isResponse()) {
            return "McpResponse[id=" + id + "]";
        } else {
            return "McpMessage[invalid]";
        }
    }
}
