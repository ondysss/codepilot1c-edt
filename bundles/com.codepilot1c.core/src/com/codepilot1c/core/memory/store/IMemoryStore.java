/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.memory.store;

import java.util.List;

import com.codepilot1c.core.memory.MemoryEntry;
import com.codepilot1c.core.memory.MemoryQuery;

/**
 * Storage interface for persistent memory entries.
 *
 * <p>Default implementation is {@link MarkdownMemoryStore} which uses
 * two Markdown files: {@code project.md} (curated) and {@code .auto-memory.md} (machine).</p>
 */
public interface IMemoryStore {

    /**
     * Persists a memory entry. Routes to appropriate file based on visibility.
     *
     * @param entry the entry to store
     * @throws MemoryStoreException if persistence fails
     */
    void remember(MemoryEntry entry) throws MemoryStoreException;

    /**
     * Recalls entries matching the query. Merges from both curated and machine files.
     *
     * @param query query parameters
     * @return matching entries (may be empty, never null)
     * @throws MemoryStoreException if reading fails
     */
    List<MemoryEntry> recall(MemoryQuery query) throws MemoryStoreException;

    /**
     * Removes expired entries from machine-generated storage.
     *
     * @return number of entries removed
     * @throws MemoryStoreException if cleanup fails
     */
    int forgetExpired() throws MemoryStoreException;

    /**
     * Exception for memory store operations.
     */
    class MemoryStoreException extends Exception {
        private static final long serialVersionUID = 1L;

        public MemoryStoreException(String message) {
            super(message);
        }

        public MemoryStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
