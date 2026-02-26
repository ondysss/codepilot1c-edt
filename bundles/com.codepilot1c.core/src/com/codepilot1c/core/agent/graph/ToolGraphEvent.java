package com.codepilot1c.core.agent.graph;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured event for tool graph routing.
 */
public class ToolGraphEvent {

    private final ToolGraphEventType type;
    private final String graphId;
    private final String nodeId;
    private final String toolName;
    private final String reason;

    public ToolGraphEvent(ToolGraphEventType type, String graphId, String nodeId, String toolName, String reason) {
        this.type = type;
        this.graphId = graphId;
        this.nodeId = nodeId;
        this.toolName = toolName;
        this.reason = reason;
    }

    public ToolGraphEventType getType() {
        return type;
    }

    public String getGraphId() {
        return graphId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getToolName() {
        return toolName;
    }

    public String getReason() {
        return reason;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type != null ? type.name() : null); //$NON-NLS-1$
        map.put("graphId", graphId); //$NON-NLS-1$
        map.put("nodeId", nodeId); //$NON-NLS-1$
        map.put("toolName", toolName); //$NON-NLS-1$
        map.put("reason", reason); //$NON-NLS-1$
        return map;
    }
}
