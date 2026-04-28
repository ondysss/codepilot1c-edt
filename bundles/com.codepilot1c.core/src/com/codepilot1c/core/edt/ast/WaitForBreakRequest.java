/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.ast;

import java.util.Map;

public record WaitForBreakRequest(
        String projectName,
        long timeoutMs) {

    private static final long DEFAULT_TIMEOUT_MS = 30_000L;

    public static WaitForBreakRequest fromParameters(Map<String, Object> parameters) {
        Long timeout = DebugRequestSupport.longValue(parameters, "timeoutMs"); //$NON-NLS-1$
        return new WaitForBreakRequest(
                DebugRequestSupport.string(parameters, "projectName"), //$NON-NLS-1$
                timeout == null ? DEFAULT_TIMEOUT_MS : timeout.longValue());
    }

    public void validate() {
        DebugRequestSupport.require(projectName, "projectName"); //$NON-NLS-1$
        DebugRequestSupport.requirePositive(Long.valueOf(timeoutMs), "timeoutMs"); //$NON-NLS-1$
    }
}
