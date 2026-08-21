/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.filesystem;

import java.io.IOException;
import java.nio.file.Path;

/** Deterministic fail-closed error for providers without stable relative mutation handles. */
public final class SecureDirectoryCapabilityException extends IOException {

    private static final long serialVersionUID = 1L;

    public SecureDirectoryCapabilityException(Path path, String reason) {
        super(path + ": " + reason); //$NON-NLS-1$
    }

    public SecureDirectoryCapabilityException(Path path, String reason, Throwable cause) {
        super(path + ": " + reason, cause); //$NON-NLS-1$
    }
}
