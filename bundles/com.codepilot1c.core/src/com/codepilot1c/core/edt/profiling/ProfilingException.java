/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.profiling;

import java.util.List;

/**
 * Machine-readable profiling tool failure.
 */
public class ProfilingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final boolean recoverable;
    private final List<String> activeTargets;

    public ProfilingException(String code, String message, boolean recoverable) {
        this(code, message, recoverable, List.of(), null);
    }

    public ProfilingException(String code, String message, boolean recoverable, Throwable cause) {
        this(code, message, recoverable, List.of(), cause);
    }

    public ProfilingException(String code, String message, boolean recoverable, List<String> activeTargets) {
        this(code, message, recoverable, activeTargets, null);
    }

    private ProfilingException(String code, String message, boolean recoverable, List<String> activeTargets,
            Throwable cause) {
        super(message, cause);
        this.code = code;
        this.recoverable = recoverable;
        this.activeTargets = activeTargets == null ? List.of() : List.copyOf(activeTargets);
    }

    public String getCode() {
        return code;
    }

    public boolean isRecoverable() {
        return recoverable;
    }

    public List<String> getActiveTargets() {
        return activeTargets;
    }
}
