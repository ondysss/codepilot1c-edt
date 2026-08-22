/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

/** Raised when a new cycle attempts to reuse any cycle id in aggregate history. */
public final class GsdCycleIdReuseException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final String cycleId;

    public GsdCycleIdReuseException(String cycleId) {
        super("cycleId '" + cycleId + "' has already been used by this GSD aggregate"); //$NON-NLS-1$ //$NON-NLS-2$
        this.cycleId = cycleId;
    }

    public String getCycleId() {
        return cycleId;
    }
}
