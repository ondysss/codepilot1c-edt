package com.codepilot1c.core.agent.graph;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.codepilot1c.core.logging.VibeLogger;

/**
 * Registry for tool graphs.
 */
public final class ToolGraphRegistry {

    public static final String GENERAL_GRAPH_ID = "general"; //$NON-NLS-1$
    public static final String BSL_GRAPH_ID = "bsl"; //$NON-NLS-1$
    public static final String METADATA_GRAPH_ID = "metadata"; //$NON-NLS-1$
    public static final String FORMS_GRAPH_ID = "forms"; //$NON-NLS-1$

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(ToolGraphRegistry.class);

    private static ToolGraphRegistry instance;

    private final Map<String, ToolGraph> graphs = new HashMap<>();

    private ToolGraphRegistry() {
        registerDefaults();
    }

    public static synchronized ToolGraphRegistry getInstance() {
        if (instance == null) {
            instance = new ToolGraphRegistry();
        }
        return instance;
    }

    public void register(ToolGraph graph) {
        if (graph == null) {
            return;
        }
        graphs.put(graph.getId(), graph);
    }

    public ToolGraph get(String graphId) {
        if (graphId == null) {
            return null;
        }
        return graphs.get(graphId);
    }

    public Collection<ToolGraph> getAll() {
        return Collections.unmodifiableCollection(graphs.values());
    }

    private void registerDefaults() {
        register(ToolGraphDefinitions.createGeneralGraph());
        register(ToolGraphDefinitions.createBslGraph());
        register(ToolGraphDefinitions.createMetadataGraph());
        register(ToolGraphDefinitions.createFormsGraph());
        LOG.debug("ToolGraphRegistry initialized with %d graphs", graphs.size()); //$NON-NLS-1$
    }
}
