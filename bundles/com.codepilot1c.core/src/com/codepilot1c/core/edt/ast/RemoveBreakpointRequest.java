/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.ast;

import java.util.Map;

public record RemoveBreakpointRequest(
        String projectName,
        String filePath,
        Integer line,
        String breakpointId) {

    public static RemoveBreakpointRequest fromParameters(Map<String, Object> parameters) {
        return new RemoveBreakpointRequest(
                DebugRequestSupport.string(parameters, "projectName"), //$NON-NLS-1$
                DebugRequestSupport.string(parameters, "filePath"), //$NON-NLS-1$
                DebugRequestSupport.integer(parameters, "line"), //$NON-NLS-1$
                DebugRequestSupport.string(parameters, "breakpointId")); //$NON-NLS-1$
    }

    public void validate() {
        DebugRequestSupport.require(projectName, "projectName"); //$NON-NLS-1$
        if (breakpointId == null && (filePath == null || line == null)) {
            throw DebugRequestSupport.invalid("breakpointId or filePath+line is required"); //$NON-NLS-1$
        }
        if (line != null) {
            DebugRequestSupport.requirePositive(line, "line"); //$NON-NLS-1$
        }
    }
}
