package com.codepilot1c.core.agent.graph;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime session for tool graph routing.
 */
public class ToolGraphSession {

    private final ToolGraph graph;
    private String currentNodeId;
    private final Map<String, Integer> visitCounts = new HashMap<>();
    private boolean validationReady;

    public ToolGraphSession(ToolGraph graph) {
        this.graph = graph;
        this.currentNodeId = graph.getStartNodeId();
        enterNode(currentNodeId);
    }

    public ToolGraph getGraph() {
        return graph;
    }

    public String getCurrentNodeId() {
        return currentNodeId;
    }

    public ToolNode getCurrentNode() {
        return graph.getNode(currentNodeId);
    }

    public boolean isValidationReady() {
        return validationReady;
    }

    public void setValidationReady(boolean validationReady) {
        this.validationReady = validationReady;
    }

    public CycleLimitAction enterNode(String nodeId) {
        if (nodeId == null) {
            return null;
        }
        currentNodeId = nodeId;
        int count = visitCounts.getOrDefault(nodeId, 0) + 1;
        visitCounts.put(nodeId, count);
        ToolNode node = graph.getNode(nodeId);
        if (node != null && count > node.getMaxVisits()) {
            return node.getOnCycleLimit();
        }
        return null;
    }

    public String resolveNextNodeId(ToolGraphContext context) {
        if (context == null) {
            return null;
        }
        List<ToolEdge> edges = graph.getEdgesFrom(currentNodeId);
        for (ToolEdge edge : edges) {
            if (edge.getPredicate().matches(context)) {
                return edge.getTo();
            }
        }
        return null;
    }
}
