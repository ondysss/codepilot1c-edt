package com.codepilot1c.core.agent.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Tool routing graph definition.
 */
public class ToolGraph {

    private final String id;
    private final String name;
    private final String version;
    private final String startNodeId;
    private final Map<String, ToolNode> nodes;
    private final List<ToolEdge> edges;

    public ToolGraph(
            String id,
            String name,
            String version,
            String startNodeId,
            Map<String, ToolNode> nodes,
            List<ToolEdge> edges) {
        this.id = Objects.requireNonNull(id, "id"); //$NON-NLS-1$
        this.name = name != null ? name : id;
        this.version = version != null ? version : "1"; //$NON-NLS-1$
        this.startNodeId = Objects.requireNonNull(startNodeId, "startNodeId"); //$NON-NLS-1$
        this.nodes = new HashMap<>(nodes != null ? nodes : Map.of());
        this.edges = new ArrayList<>(edges != null ? edges : List.of());
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }

    public String getStartNodeId() {
        return startNodeId;
    }

    public ToolNode getNode(String nodeId) {
        return nodes.get(nodeId);
    }

    public Map<String, ToolNode> getNodes() {
        return Collections.unmodifiableMap(nodes);
    }

    public List<ToolEdge> getEdges() {
        return Collections.unmodifiableList(edges);
    }

    public List<ToolEdge> getEdgesFrom(String nodeId) {
        if (nodeId == null) {
            return List.of();
        }
        return edges.stream()
                .filter(edge -> nodeId.equals(edge.getFrom()))
                .sorted((a, b) -> Integer.compare(b.getPriority(), a.getPriority()))
                .collect(Collectors.toList());
    }
}
