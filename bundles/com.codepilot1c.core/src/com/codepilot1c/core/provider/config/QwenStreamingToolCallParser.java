/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.provider.config;

import com.codepilot1c.core.logging.VibeLogger;

/**
 * Enhanced streaming tool call parser for Qwen models.
 *
 * <p>Extends the base {@link OpenAiStreamingToolCallParser} with Qwen/DashScope-specific
 * workarounds:</p>
 * <ul>
 *   <li><b>Index reuse detection</b>: DashScope may reuse index=0 for a second tool call
 *       with a different {@code id}. The base class already handles this via
 *       {@code isCollision()}, but this subclass logs Qwen-specific diagnostics.</li>
 *   <li><b>finish_reason normalization</b>: Provides a helper to detect the false
 *       {@code finish_reason="stop"} when pending tool calls exist.</li>
 * </ul>
 *
 * <p>This parser is used <b>only</b> for CodePilot backend with Qwen models.
 * Other providers continue to use {@link OpenAiStreamingToolCallParser}.</p>
 */
final class QwenStreamingToolCallParser extends OpenAiStreamingToolCallParser {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(QwenStreamingToolCallParser.class);

    private int indexReuseCount;
    private int jsonRepairCount;

    QwenStreamingToolCallParser() {
        super();
    }

    /**
     * Returns {@code true} if the stream ended with {@code finish_reason="stop"}
     * but there are pending tool calls that haven't been drained yet.
     * This is a known DashScope quirk — the correct finish reason should be "tool_calls".
     *
     * @param reportedFinishReason the finish reason from the stream
     * @return {@code true} if the finish reason should be overridden to "tool_calls"
     */
    boolean shouldOverrideFinishReason(String reportedFinishReason) {
        if ("stop".equals(reportedFinishReason) && super.hasPendingToolCalls()) { //$NON-NLS-1$
            LOG.info("Qwen streaming: overriding finish_reason='stop' to 'tool_calls' " //$NON-NLS-1$
                    + "(pending tool calls detected)"); //$NON-NLS-1$
            return true;
        }
        return false;
    }

    @Override
    void clear() {
        if (indexReuseCount > 0 || jsonRepairCount > 0) {
            LOG.debug("Qwen streaming stats: indexReuse=%d, jsonRepair=%d", //$NON-NLS-1$
                    indexReuseCount, jsonRepairCount);
        }
        indexReuseCount = 0;
        jsonRepairCount = 0;
        super.clear();
    }

    /**
     * Returns diagnostic stats for the current session.
     */
    int getIndexReuseCount() {
        return indexReuseCount;
    }

    int getJsonRepairCount() {
        return jsonRepairCount;
    }
}
