/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

import java.io.IOException;

/**
 * Raised when the GSD {@code state.json} (or its backup) is structurally corrupt:
 * unparseable JSON, a JSON {@code null} document, an empty document, or a
 * unsupported historical {@code schemaVersion}, or a state that violates invariants.
 * Future schemas use {@link GsdUnsupportedSchemaException} and never trigger recovery.
 *
 * <p>This is distinct from ordinary access/I/O failures (e.g.
 * {@link java.nio.file.AccessDeniedException}, {@link java.nio.file.NoSuchFileException}):
 * those are surfaced verbatim and fail closed, never silently recovering from a
 * backup, because they indicate an environment problem rather than damaged bytes.</p>
 *
 * <p>Extends {@link IOException} so it can be thrown from the same methods while still
 * being catchable separately by the recovery logic.</p>
 */
public class GsdCorruptException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructor.
     *
     * @param message the detail message
     */
    public GsdCorruptException(String message) {
        super(message);
    }

    /**
     * Constructor with cause.
     *
     * @param message the detail message
     * @param cause   the underlying parse error, or {@code null}
     */
    public GsdCorruptException(String message, Throwable cause) {
        super(message, cause);
    }
}
