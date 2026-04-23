/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Metadata for a chat attachment.
 */
public class LlmAttachment {

    public enum Kind {
        IMAGE,
        FILE
    }

    private final String id;
    private final Kind kind;
    private final String displayName;
    private final String mimeType;
    private final long sizeBytes;
    private final String originalPath;
    private final String cachePath;
    private final String sha256;
    private final String previewText;

    private LlmAttachment(Builder builder) {
        this.id = builder.id != null ? builder.id : UUID.randomUUID().toString();
        this.kind = Objects.requireNonNull(builder.kind, "kind"); //$NON-NLS-1$
        this.displayName = builder.displayName;
        this.mimeType = builder.mimeType;
        this.sizeBytes = builder.sizeBytes;
        this.originalPath = builder.originalPath;
        this.cachePath = builder.cachePath;
        this.sha256 = builder.sha256;
        this.previewText = builder.previewText;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getId() {
        return id;
    }

    public Kind getKind() {
        return kind;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getMimeType() {
        return mimeType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getOriginalPath() {
        return originalPath;
    }

    public String getCachePath() {
        return cachePath;
    }

    public String getSha256() {
        return sha256;
    }

    public String getPreviewText() {
        return previewText;
    }

    public boolean isImage() {
        return kind == Kind.IMAGE;
    }

    public boolean isFile() {
        return kind == Kind.FILE;
    }

    public String getEffectivePath() {
        if (cachePath != null && !cachePath.isBlank()) {
            return cachePath;
        }
        return originalPath;
    }

    public String toDisplayLabel() {
        StringBuilder sb = new StringBuilder();
        sb.append(displayName != null && !displayName.isBlank() ? displayName : "attachment"); //$NON-NLS-1$
        if (mimeType != null && !mimeType.isBlank()) {
            sb.append(" (").append(mimeType).append(')'); //$NON-NLS-1$
        }
        return sb.toString();
    }

    public static final class Builder {
        private String id;
        private Kind kind;
        private String displayName;
        private String mimeType;
        private long sizeBytes;
        private String originalPath;
        private String cachePath;
        private String sha256;
        private String previewText;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder kind(Kind kind) {
            this.kind = kind;
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder mimeType(String mimeType) {
            this.mimeType = mimeType;
            return this;
        }

        public Builder sizeBytes(long sizeBytes) {
            this.sizeBytes = sizeBytes;
            return this;
        }

        public Builder originalPath(String originalPath) {
            this.originalPath = originalPath;
            return this;
        }

        public Builder cachePath(String cachePath) {
            this.cachePath = cachePath;
            return this;
        }

        public Builder sha256(String sha256) {
            this.sha256 = sha256;
            return this;
        }

        public Builder previewText(String previewText) {
            this.previewText = previewText;
            return this;
        }

        public LlmAttachment build() {
            return new LlmAttachment(this);
        }
    }
}
