/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.gsd;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.codepilot1c.core.model.ToolCall;
import com.codepilot1c.core.tools.ToolResult;

/** Pure policy for deciding whether completed tool calls require a GSD reload. */
public final class GsdToolMutationRefreshPolicy {

    private static final String GSD_TOOL_PREFIX = "gsd_"; //$NON-NLS-1$
    private static final String READ_ONLY_TOOL = "gsd_get_state"; //$NON-NLS-1$

    private GsdToolMutationRefreshPolicy() {
    }

    /**
     * Returns whether at least one successful GSD mutation is present.
     * Failed/denied calls and the read-only state query do not trigger I/O.
     */
    public static boolean shouldRefresh(
            List<ToolCall> calls, Map<String, ToolResult> resultsByCallId,
            Set<String> executedCallIds) {
        if (calls == null || calls.isEmpty() || resultsByCallId == null
                || executedCallIds == null || executedCallIds.isEmpty()) {
            return false;
        }
        for (ToolCall call : calls) {
            if (call == null || !isMutation(call.getName())) {
                continue;
            }
            ToolResult result = resultsByCallId.get(call.getId());
            if (executedCallIds.contains(call.getId())
                    && result != null && result.isSuccess()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMutation(String toolName) {
        return toolName != null
                && toolName.startsWith(GSD_TOOL_PREFIX)
                && !READ_ONLY_TOOL.equals(toolName);
    }
}
