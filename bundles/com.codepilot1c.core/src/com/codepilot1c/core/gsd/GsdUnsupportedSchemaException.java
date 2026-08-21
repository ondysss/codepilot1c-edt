/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

import java.io.IOException;

/** Rejects state written by a newer implementation without attempting recovery. */
public final class GsdUnsupportedSchemaException extends IOException {

    private static final long serialVersionUID = 1L;

    private final int schemaVersion;

    public GsdUnsupportedSchemaException(int schemaVersion) {
        super("unsupported future GSD schemaVersion " + schemaVersion //$NON-NLS-1$
                + "; current schemaVersion is " + GsdState.CURRENT_SCHEMA_VERSION); //$NON-NLS-1$
        this.schemaVersion = schemaVersion;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }
}
