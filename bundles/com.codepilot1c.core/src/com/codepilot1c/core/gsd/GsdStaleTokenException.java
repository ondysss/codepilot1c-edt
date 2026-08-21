/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

/** Raised when any component of the optimistic-concurrency token is stale. */
public final class GsdStaleTokenException extends GsdStaleRevisionException {

    private static final long serialVersionUID = 1L;

    private final GsdConcurrencyToken expectedToken;
    private final GsdConcurrencyToken actualToken;

    public GsdStaleTokenException(GsdConcurrencyToken expectedToken,
            GsdConcurrencyToken actualToken) {
        super(expectedToken.revision(), actualToken.revision(),
                "stale GSD token: expected " + expectedToken + " but disk has " + actualToken); //$NON-NLS-1$ //$NON-NLS-2$
        this.expectedToken = expectedToken;
        this.actualToken = actualToken;
    }

    public GsdConcurrencyToken getExpectedToken() {
        return expectedToken;
    }

    public GsdConcurrencyToken getActualToken() {
        return actualToken;
    }
}
