package com.codepilot1c.core.agent.graph;

import com.codepilot1c.core.logging.VibeLogger;
import com.google.gson.Gson;

/**
 * Structured logger for tool graph events.
 */
public final class ToolGraphLogger {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(ToolGraphLogger.class);
    private static final Gson GSON = new Gson();

    private ToolGraphLogger() {
    }

    public static void log(ToolGraphEvent event) {
        if (event == null) {
            return;
        }
        String payload = GSON.toJson(event.toMap());
        LOG.info("tool-graph-event=%s", payload); //$NON-NLS-1$
    }
}
