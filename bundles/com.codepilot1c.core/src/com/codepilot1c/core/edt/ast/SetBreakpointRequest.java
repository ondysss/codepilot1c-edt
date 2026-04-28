/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.ast;

import java.util.Map;

public record SetBreakpointRequest(
        String projectName,
        String filePath,
        int line,
        String condition,
        Boolean enabled) {

    public static SetBreakpointRequest fromParameters(Map<String, Object> parameters) {
        Integer line = DebugRequestSupport.integer(parameters, "line"); //$NON-NLS-1$
        return new SetBreakpointRequest(
                DebugRequestSupport.string(parameters, "projectName"), //$NON-NLS-1$
                DebugRequestSupport.string(parameters, "filePath"), //$NON-NLS-1$
                line == null ? -1 : line.intValue(),
                DebugRequestSupport.string(parameters, "condition"), //$NON-NLS-1$
                DebugRequestSupport.bool(parameters, "enabled")); //$NON-NLS-1$
    }

    public void validate() {
        DebugRequestSupport.require(projectName, "projectName"); //$NON-NLS-1$
        DebugRequestSupport.require(filePath, "filePath"); //$NON-NLS-1$
        DebugRequestSupport.requirePositive(Integer.valueOf(line), "line"); //$NON-NLS-1$
    }
}
