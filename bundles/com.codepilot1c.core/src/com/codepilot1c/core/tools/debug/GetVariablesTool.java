/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.debug;

import java.util.Map;

import com.codepilot1c.core.edt.ast.GetVariablesRequest;
import com.codepilot1c.core.edt.debug.EdtDebugService;
import com.codepilot1c.core.tools.ToolMeta;

@ToolMeta(name = "get_variables", category = "debug", surfaceCategory = "dynamic",
        tags = {"debug", "edt", "read-only"})
public class GetVariablesTool extends AbstractEdtDebugTool {

    private static final String SCHEMA = """
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "projectName": {"type": "string", "description": "EDT project name"},
                "threadId": {"type": "string", "description": "Optional debug thread id"},
                "frameId": {"type": "string", "description": "Optional stack frame id"}
              },
              "required": ["projectName"]
            }
            """; //$NON-NLS-1$

    @Override
    public String getDescription() {
        return "Get variables from the current EDT/1C stack frame."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected Object executeDebug(Map<String, Object> parameters) {
        return EdtDebugService.getInstance().getVariables(GetVariablesRequest.fromParameters(parameters));
    }
}
