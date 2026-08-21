/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

/**
 * Raised by {@link GsdStateStore#save(GsdState)} when the caller's expected revision
 * does not match the revision currently on disk, i.e. another writer persisted a newer
 * state since the caller last loaded. The caller must reload, re-apply its mutation,
 * and retry.
 */
public class GsdStaleRevisionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final long expectedRevision;
    private final long actualRevision;

    /**
     * Constructor.
     *
     * @param expectedRevision the revision the caller expected
     * @param actualRevision   the revision actually on disk
     */
    public GsdStaleRevisionException(long expectedRevision, long actualRevision) {
        this(expectedRevision, actualRevision,
                "stale GSD revision: expected " + expectedRevision //$NON-NLS-1$
                        + " but disk has " + actualRevision); //$NON-NLS-1$
    }

    /** Constructor used by token-aware compatibility subclasses. */
    protected GsdStaleRevisionException(long expectedRevision, long actualRevision, String message) {
        super(message);
        this.expectedRevision = expectedRevision;
        this.actualRevision = actualRevision;
    }

    /**
     * @return the revision the caller expected
     */
    public long getExpectedRevision() {
        return expectedRevision;
    }

    /**
     * @return the revision actually on disk
     */
    public long getActualRevision() {
        return actualRevision;
    }
}
