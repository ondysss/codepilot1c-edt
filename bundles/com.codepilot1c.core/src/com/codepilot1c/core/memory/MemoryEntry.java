/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A single memory entry with category, visibility, retention, and provenance.
 */
public class MemoryEntry {

    private final String key;
    private final String content;
    private final MemoryCategory category;
    private final MemoryVisibility visibility;
    private final MemoryScope scope;
    private final RetentionPolicy retention;
    private final String sourceSessionId;
    private final Instant createdAt;

    private MemoryEntry(Builder builder) {
        this.key = Objects.requireNonNull(builder.key, "key"); //$NON-NLS-1$
        this.content = Objects.requireNonNull(builder.content, "content"); //$NON-NLS-1$
        this.category = builder.category != null ? builder.category : MemoryCategory.FACT;
        this.visibility = builder.visibility != null ? builder.visibility : MemoryVisibility.MACHINE;
        this.scope = builder.scope != null ? builder.scope : MemoryScope.PROJECT;
        this.retention = builder.retention != null ? builder.retention : RetentionPolicy.PERMANENT;
        this.sourceSessionId = builder.sourceSessionId;
        this.createdAt = builder.createdAt != null ? builder.createdAt : Instant.now();
    }

    public String getKey() { return key; }
    public String getContent() { return content; }
    public MemoryCategory getCategory() { return category; }
    public MemoryVisibility getVisibility() { return visibility; }
    public MemoryScope getScope() { return scope; }
    public RetentionPolicy getRetention() { return retention; }
    public String getSourceSessionId() { return sourceSessionId; }
    public Instant getCreatedAt() { return createdAt; }

    public boolean isPending() { return category == MemoryCategory.PENDING; }
    public boolean isExpired() { return retention.isExpired(); }

    public static Builder builder(String key, String content) {
        return new Builder(key, content);
    }

    public static class Builder {
        private final String key;
        private final String content;
        private MemoryCategory category;
        private MemoryVisibility visibility;
        private MemoryScope scope;
        private RetentionPolicy retention;
        private String sourceSessionId;
        private Instant createdAt;

        Builder(String key, String content) {
            this.key = key;
            this.content = content;
        }

        public Builder category(MemoryCategory category) { this.category = category; return this; }
        public Builder visibility(MemoryVisibility visibility) { this.visibility = visibility; return this; }
        public Builder scope(MemoryScope scope) { this.scope = scope; return this; }
        public Builder retention(RetentionPolicy retention) { this.retention = retention; return this; }
        public Builder retention(Duration ttl) { this.retention = RetentionPolicy.withTtl(ttl); return this; }
        public Builder sourceSessionId(String sessionId) { this.sourceSessionId = sessionId; return this; }
        public Builder createdAt(Instant createdAt) { this.createdAt = createdAt; return this; }

        public MemoryEntry build() { return new MemoryEntry(this); }
    }
}
