/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.debug;

import java.util.Map;

import com.codepilot1c.core.edt.ast.RemoveBreakpointRequest;
import com.codepilot1c.core.edt.debug.EdtDebugService;
import com.codepilot1c.core.tools.ToolMeta;

@ToolMeta(name = "remove_breakpoint", category = "debug", surfaceCategory = "dynamic", mutating = true,
        tags = {"debug", "edt"})
public class RemoveBreakpointTool extends AbstractEdtDebugTool {

    private static final String SCHEMA = """
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "projectName": {"type": "string", "description": "EDT project name"},
                "breakpointId": {"type": "string", "description": "Breakpoint marker id"},
                "filePath": {"type": "string", "description": "Project-relative BSL file path"},
                "line": {"type": "integer", "description": "1-based source line"}
              },
              "required": ["projectName"]
            }
            """; //$NON-NLS-1$

    @Override
    public String getDescription() {
        return "Remove an EDT/1C breakpoint by id or source location."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected Object executeDebug(Map<String, Object> parameters) {
        return EdtDebugService.getInstance().removeBreakpoint(RemoveBreakpointRequest.fromParameters(parameters));
    }
}
