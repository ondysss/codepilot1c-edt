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
 * Represents content in MCP protocol responses.
 *
 * <p>Content can be text, image (base64), or a resource reference.</p>
 */
public class McpContent {

    /**
     * Type of MCP content.
     */
    public enum Type {
        @SerializedName("text")
        TEXT,
        @SerializedName("image")
        IMAGE,
        @SerializedName("resource")
        RESOURCE
    }

    @SerializedName("type")
    private Type type;

    @SerializedName("text")
    private String text;

    @SerializedName("mimeType")
    private String mimeType;

    @SerializedName("data")
    private String data;

    @SerializedName("uri")
    private String uri;

    @SerializedName("resource")
    private Resource resource;

    /**
     * Creates an empty content object.
     */
    public McpContent() {
    }

    /**
     * Creates a text content.
     *
     * @param text the text content
     * @return the content object
     */
    public static McpContent text(String text) {
        McpContent content = new McpContent();
        content.type = Type.TEXT;
        content.text = text;
        return content;
    }

    /**
     * Creates an image content.
     *
     * @param data the base64-encoded image data
     * @param mimeType the image MIME type
     * @return the content object
     */
    public static McpContent image(String data, String mimeType) {
        McpContent content = new McpContent();
        content.type = Type.IMAGE;
        content.data = data;
        content.mimeType = mimeType;
        return content;
    }

    /**
     * Creates a resource reference content.
     *
     * @param uri the resource URI
     * @param text the resource text (optional)
     * @param mimeType the resource MIME type (optional)
     * @return the content object
     */
    public static McpContent resource(String uri, String text, String mimeType) {
        McpContent content = new McpContent();
        content.type = Type.RESOURCE;
        content.uri = uri;
        content.text = text;
        content.mimeType = mimeType;
        return content;
    }

    // Getters

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }

    public String getUri() {
        if (uri != null && !uri.isEmpty()) {
            return uri;
        }
        return resource != null ? resource.getUri() : null;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public Resource getResource() {
        return resource;
    }

    public void setResource(Resource resource) {
        this.resource = resource;
    }

    public String getEffectiveText() {
        if (text != null && !text.isEmpty()) {
            return text;
        }
        return resource != null ? resource.getText() : null;
    }

    public String getEffectiveMimeType() {
        if (mimeType != null && !mimeType.isEmpty()) {
            return mimeType;
        }
        return resource != null ? resource.getMimeType() : null;
    }

    /**
     * Nested resource payload shape used by some MCP servers.
     */
    public static class Resource {
        @SerializedName("uri")
        private String uri;

        @SerializedName("mimeType")
        private String mimeType;

        @SerializedName("text")
        private String text;

        @SerializedName("blob")
        private String blob;

        public String getUri() {
            return uri;
        }

        public String getMimeType() {
            return mimeType;
        }

        public String getText() {
            return text;
        }

        public String getBlob() {
            return blob;
        }
    }

    @Override
    public String toString() {
        return "McpContent[type=" + type + "]";
    }
}
