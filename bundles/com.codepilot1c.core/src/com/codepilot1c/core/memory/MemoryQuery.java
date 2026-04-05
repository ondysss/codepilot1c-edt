/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.memory;

/**
 * Query parameters for memory recall.
 */
public class MemoryQuery {

    private MemoryCategory category;
    private MemoryVisibility visibility;
    private String keyPattern;
    private int limit = 50;
    private boolean includeExpired;

    public MemoryCategory getCategory() { return category; }
    public MemoryVisibility getVisibility() { return visibility; }
    public String getKeyPattern() { return keyPattern; }
    public int getLimit() { return limit; }
    public boolean isIncludeExpired() { return includeExpired; }

    public MemoryQuery category(MemoryCategory category) { this.category = category; return this; }
    public MemoryQuery visibility(MemoryVisibility visibility) { this.visibility = visibility; return this; }
    public MemoryQuery keyPattern(String keyPattern) { this.keyPattern = keyPattern; return this; }
    public MemoryQuery limit(int limit) { this.limit = limit; return this; }
    public MemoryQuery includeExpired(boolean includeExpired) { this.includeExpired = includeExpired; return this; }

    /** Returns a query that matches all non-expired entries. */
    public static MemoryQuery all() {
        return new MemoryQuery();
    }

    /** Returns a query for PENDING entries only. */
    public static MemoryQuery pending() {
        return new MemoryQuery().category(MemoryCategory.PENDING);
    }
}
