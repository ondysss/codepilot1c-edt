/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.mcp.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Configuration for an MCP server.
 */
public class McpServerConfig {

    public static final String DEFAULT_PROTOCOL_VERSION = "2025-11-25"; //$NON-NLS-1$
    public static final List<String> DEFAULT_SUPPORTED_PROTOCOLS = List.of(
        "2025-11-25", //$NON-NLS-1$
        "2025-06-18", //$NON-NLS-1$
        "2024-11-05" //$NON-NLS-1$
    );

    private String id;
    private String name;
    private boolean enabled;
    private TransportType transportType;
    private AuthMode authMode;

    // STDIO config
    private String command;
    private List<String> args;
    private Map<String, String> env;
    private String workingDirectory;

    // Remote transport config
    private String remoteUrl;
    private String remoteSseUrl;
    private boolean allowLegacyFallback;
    private Map<String, String> staticHeaders;
    private String oauthProfileId;

    // Protocol
    private String preferredProtocolVersion;
    private List<String> supportedProtocolVersions;

    // Timeouts
    private int connectionTimeoutMs = 30000;
    private int requestTimeoutMs = 60000;

    /**
     * Transport types for MCP servers.
     */
    public enum TransportType {
        /** Local process via stdin/stdout */
        STDIO,
        /** Remote MCP Streamable HTTP transport */
        STREAMABLE_HTTP,
        /** Legacy SSE transport */
        HTTP_SSE_LEGACY
    }

    /**
     * Auth mode for remote transports.
     */
    public enum AuthMode {
        NONE,
        STATIC_HEADERS,
        OAUTH2
    }

    /**
     * Creates an empty configuration.
     */
    public McpServerConfig() {
        this.id = UUID.randomUUID().toString();
        this.enabled = true;
        this.transportType = TransportType.STDIO;
        this.authMode = AuthMode.NONE;
        this.args = new ArrayList<>();
        this.env = new HashMap<>();
        this.staticHeaders = new HashMap<>();
        this.preferredProtocolVersion = DEFAULT_PROTOCOL_VERSION;
        this.supportedProtocolVersions = new ArrayList<>(DEFAULT_SUPPORTED_PROTOCOLS);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public TransportType getTransportType() {
        return transportType;
    }

    public AuthMode getAuthMode() {
        return authMode != null ? authMode : AuthMode.NONE;
    }

    public String getCommand() {
        return command;
    }

    public List<String> getArgs() {
        return args != null ? args : Collections.emptyList();
    }

    public Map<String, String> getEnv() {
        return env != null ? env : Collections.emptyMap();
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public int getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    public int getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public String getRemoteUrl() {
        return remoteUrl;
    }

    public String getRemoteSseUrl() {
        return remoteSseUrl;
    }

    public boolean isAllowLegacyFallback() {
        return allowLegacyFallback;
    }

    public Map<String, String> getStaticHeaders() {
        return staticHeaders != null ? staticHeaders : Collections.emptyMap();
    }

    public String getOauthProfileId() {
        return oauthProfileId;
    }

    public String getPreferredProtocolVersion() {
        return preferredProtocolVersion != null ? preferredProtocolVersion : DEFAULT_PROTOCOL_VERSION;
    }

    public List<String> getSupportedProtocolVersions() {
        if (supportedProtocolVersions == null || supportedProtocolVersions.isEmpty()) {
            return DEFAULT_SUPPORTED_PROTOCOLS;
        }
        return Collections.unmodifiableList(supportedProtocolVersions);
    }

    // Compatibility accessors for previous HTTP draft fields.
    public String getUrl() {
        return getRemoteUrl();
    }

    public Map<String, String> getHeaders() {
        return getStaticHeaders();
    }

    public boolean isValid() {
        if (name == null || name.isBlank()) {
            return false;
        }
        if (transportType == TransportType.STDIO) {
            return command != null && !command.isBlank();
        }
        return remoteUrl != null && !remoteUrl.isBlank();
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", id); //$NON-NLS-1$
        json.addProperty("name", name); //$NON-NLS-1$
        json.addProperty("enabled", enabled); //$NON-NLS-1$
        json.addProperty("transportType", transportType.name()); //$NON-NLS-1$
        json.addProperty("authMode", getAuthMode().name()); //$NON-NLS-1$
        json.addProperty("command", command); //$NON-NLS-1$
        json.add("args", new Gson().toJsonTree(args)); //$NON-NLS-1$
        json.add("env", new Gson().toJsonTree(env)); //$NON-NLS-1$
        if (workingDirectory != null) {
            json.addProperty("workingDirectory", workingDirectory); //$NON-NLS-1$
        }
        if (remoteUrl != null) {
            json.addProperty("remoteUrl", remoteUrl); //$NON-NLS-1$
        }
        if (remoteSseUrl != null) {
            json.addProperty("remoteSseUrl", remoteSseUrl); //$NON-NLS-1$
        }
        json.addProperty("allowLegacyFallback", allowLegacyFallback); //$NON-NLS-1$
        json.add("staticHeaders", new Gson().toJsonTree(staticHeaders)); //$NON-NLS-1$
        if (oauthProfileId != null) {
            json.addProperty("oauthProfileId", oauthProfileId); //$NON-NLS-1$
        }
        json.addProperty("preferredProtocolVersion", getPreferredProtocolVersion()); //$NON-NLS-1$
        json.add("supportedProtocolVersions", new Gson().toJsonTree(getSupportedProtocolVersions())); //$NON-NLS-1$
        json.addProperty("connectionTimeoutMs", connectionTimeoutMs); //$NON-NLS-1$
        json.addProperty("requestTimeoutMs", requestTimeoutMs); //$NON-NLS-1$
        return json;
    }

    public static McpServerConfig fromJson(JsonObject json) {
        Builder builder = builder()
            .id(readString(json, "id")) //$NON-NLS-1$
            .name(readString(json, "name")) //$NON-NLS-1$
            .enabled(readBoolean(json, "enabled", true)) //$NON-NLS-1$
            .command(readString(json, "command")); //$NON-NLS-1$

        if (json.has("transportType")) { //$NON-NLS-1$
            try {
                builder.transportType(TransportType.valueOf(json.get("transportType").getAsString())); //$NON-NLS-1$
            } catch (IllegalArgumentException e) {
                builder.transportType(TransportType.STDIO);
            }
        }

        if (json.has("authMode")) { //$NON-NLS-1$
            try {
                builder.authMode(AuthMode.valueOf(json.get("authMode").getAsString())); //$NON-NLS-1$
            } catch (IllegalArgumentException e) {
                builder.authMode(AuthMode.NONE);
            }
        }

        if (json.has("args") && json.get("args").isJsonArray()) { //$NON-NLS-1$ //$NON-NLS-2$
            JsonArray argsArr = json.getAsJsonArray("args"); //$NON-NLS-1$
            argsArr.forEach(e -> builder.addArg(e.getAsString()));
        }

        if (json.has("env") && json.get("env").isJsonObject()) { //$NON-NLS-1$ //$NON-NLS-2$
            JsonObject envObj = json.getAsJsonObject("env"); //$NON-NLS-1$
            envObj.entrySet().forEach(e -> builder.putEnv(e.getKey(), e.getValue().getAsString()));
        }

        if (json.has("workingDirectory") && !json.get("workingDirectory").isJsonNull()) { //$NON-NLS-1$ //$NON-NLS-2$
            builder.workingDirectory(json.get("workingDirectory").getAsString()); //$NON-NLS-1$
        }

        // New remote keys + backward compatible aliases.
        String remoteUrl = readString(json, "remoteUrl"); //$NON-NLS-1$
        if (remoteUrl == null) {
            remoteUrl = readString(json, "url"); // legacy key //$NON-NLS-1$
        }
        builder.remoteUrl(remoteUrl);

        String remoteSseUrl = readString(json, "remoteSseUrl"); //$NON-NLS-1$
        if (remoteSseUrl == null) {
            remoteSseUrl = readString(json, "sseUrl"); // legacy key //$NON-NLS-1$
        }
        builder.remoteSseUrl(remoteSseUrl);
        builder.allowLegacyFallback(readBoolean(json, "allowLegacyFallback", false)); //$NON-NLS-1$

        if (json.has("staticHeaders") && json.get("staticHeaders").isJsonObject()) { //$NON-NLS-1$ //$NON-NLS-2$
            JsonObject headersObj = json.getAsJsonObject("staticHeaders"); //$NON-NLS-1$
            headersObj.entrySet().forEach(e -> builder.putStaticHeader(e.getKey(), e.getValue().getAsString()));
        } else if (json.has("headers") && json.get("headers").isJsonObject()) { // legacy key //$NON-NLS-1$ //$NON-NLS-2$
            JsonObject headersObj = json.getAsJsonObject("headers"); //$NON-NLS-1$
            headersObj.entrySet().forEach(e -> builder.putStaticHeader(e.getKey(), e.getValue().getAsString()));
        }

        builder.oauthProfileId(readString(json, "oauthProfileId")); //$NON-NLS-1$
        builder.preferredProtocolVersion(readString(json, "preferredProtocolVersion")); //$NON-NLS-1$
        if (json.has("supportedProtocolVersions") && json.get("supportedProtocolVersions").isJsonArray()) { //$NON-NLS-1$ //$NON-NLS-2$
            List<String> versions = new ArrayList<>();
            for (JsonElement elem : json.getAsJsonArray("supportedProtocolVersions")) { //$NON-NLS-1$
                versions.add(elem.getAsString());
            }
            builder.supportedProtocolVersions(versions);
        }

        if (json.has("connectionTimeoutMs")) { //$NON-NLS-1$
            builder.connectionTimeout(json.get("connectionTimeoutMs").getAsInt()); //$NON-NLS-1$
        }

        if (json.has("requestTimeoutMs")) { //$NON-NLS-1$
            builder.requestTimeout(json.get("requestTimeoutMs").getAsInt()); //$NON-NLS-1$
        }

        return builder.build();
    }

    private static String readString(JsonObject json, String key) {
        if (json.has(key) && !json.get(key).isJsonNull()) {
            return json.get(key).getAsString();
        }
        return null;
    }

    private static boolean readBoolean(JsonObject json, String key, boolean defaultValue) {
        if (json.has(key) && !json.get(key).isJsonNull()) {
            return json.get(key).getAsBoolean();
        }
        return defaultValue;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        McpServerConfig other = (McpServerConfig) obj;
        return Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "McpServerConfig[id=" + id + ", name=" + name + ", transportType=" + transportType + "]"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Builder for MCP server configuration.
     */
    public static class Builder {

        private final McpServerConfig config = new McpServerConfig();

        public Builder id(String id) {
            if (id != null && !id.isBlank()) {
                config.id = id;
            }
            return this;
        }

        public Builder name(String name) {
            config.name = name;
            return this;
        }

        public Builder enabled(boolean enabled) {
            config.enabled = enabled;
            return this;
        }

        public Builder transportType(TransportType type) {
            if (type != null) {
                config.transportType = type;
            }
            return this;
        }

        public Builder authMode(AuthMode mode) {
            if (mode != null) {
                config.authMode = mode;
            }
            return this;
        }

        public Builder command(String command) {
            config.command = command;
            return this;
        }

        public Builder args(List<String> args) {
            config.args = new ArrayList<>(args);
            return this;
        }

        public Builder addArg(String arg) {
            config.args.add(arg);
            return this;
        }

        public Builder env(Map<String, String> env) {
            config.env = new HashMap<>(env);
            return this;
        }

        public Builder putEnv(String key, String value) {
            config.env.put(key, value);
            return this;
        }

        public Builder workingDirectory(String dir) {
            config.workingDirectory = dir;
            return this;
        }

        public Builder remoteUrl(String url) {
            config.remoteUrl = url;
            return this;
        }

        public Builder remoteSseUrl(String url) {
            config.remoteSseUrl = url;
            return this;
        }

        public Builder allowLegacyFallback(boolean allowLegacyFallback) {
            config.allowLegacyFallback = allowLegacyFallback;
            return this;
        }

        public Builder staticHeaders(Map<String, String> headers) {
            config.staticHeaders = new HashMap<>(headers);
            return this;
        }

        public Builder putStaticHeader(String key, String value) {
            config.staticHeaders.put(key, value);
            return this;
        }

        public Builder oauthProfileId(String oauthProfileId) {
            config.oauthProfileId = oauthProfileId;
            return this;
        }

        public Builder preferredProtocolVersion(String version) {
            if (version != null && !version.isBlank()) {
                config.preferredProtocolVersion = version;
            }
            return this;
        }

        public Builder supportedProtocolVersions(List<String> versions) {
            if (versions != null && !versions.isEmpty()) {
                config.supportedProtocolVersions = new ArrayList<>(versions);
            }
            return this;
        }

        public Builder connectionTimeout(int ms) {
            config.connectionTimeoutMs = ms;
            return this;
        }

        public Builder requestTimeout(int ms) {
            config.requestTimeoutMs = ms;
            return this;
        }

        public McpServerConfig build() {
            Objects.requireNonNull(config.name, "name is required"); //$NON-NLS-1$
            if (config.transportType == TransportType.STDIO) {
                Objects.requireNonNull(config.command, "command is required for STDIO"); //$NON-NLS-1$
            } else {
                Objects.requireNonNull(config.remoteUrl, "remoteUrl is required for remote transport"); //$NON-NLS-1$
            }
            if (config.supportedProtocolVersions == null || config.supportedProtocolVersions.isEmpty()) {
                config.supportedProtocolVersions = new ArrayList<>(DEFAULT_SUPPORTED_PROTOCOLS);
            }
            if (config.preferredProtocolVersion == null || config.preferredProtocolVersion.isBlank()) {
                config.preferredProtocolVersion = DEFAULT_PROTOCOL_VERSION;
            }
            return config;
        }
    }
}
