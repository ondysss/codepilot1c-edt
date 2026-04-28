/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.debug;

import java.util.Map;

import com.codepilot1c.core.edt.ast.StepRequest;
import com.codepilot1c.core.edt.debug.EdtDebugService;
import com.codepilot1c.core.tools.ToolMeta;

@ToolMeta(name = "step", category = "debug", surfaceCategory = "dynamic", mutating = true,
        tags = {"debug", "edt"})
public class StepTool extends AbstractEdtDebugTool {

    private static final String SCHEMA = """
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "projectName": {"type": "string", "description": "EDT project name"},
                "threadId": {"type": "string", "description": "Optional debug thread id"},
                "kind": {"type": "string", "enum": ["into", "over", "out"], "description": "Step command kind"}
              },
              "required": ["projectName", "kind"]
            }
            """; //$NON-NLS-1$

    @Override
    public String getDescription() {
        return "Step an EDT/1C debug thread into, over, or out."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected Object executeDebug(Map<String, Object> parameters) {
        return EdtDebugService.getInstance().step(StepRequest.fromParameters(parameters));
    }
}
