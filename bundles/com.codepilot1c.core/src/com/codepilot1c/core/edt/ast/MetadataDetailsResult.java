package com.codepilot1c.core.edt.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Structured metadata details response.
 */
public class MetadataDetailsResult {

    private final String projectName;
    private final String engine;
    private final List<MetadataNode> nodes;

    public MetadataDetailsResult(String projectName, String engine, List<MetadataNode> nodes) {
        this.projectName = projectName;
        this.engine = engine;
        this.nodes = new ArrayList<>(nodes != null ? nodes : List.of());
    }

    public String getProjectName() {
        return projectName;
    }

    public String getEngine() {
        return engine;
    }

    public List<MetadataNode> getNodes() {
        return Collections.unmodifiableList(nodes);
    }
}
