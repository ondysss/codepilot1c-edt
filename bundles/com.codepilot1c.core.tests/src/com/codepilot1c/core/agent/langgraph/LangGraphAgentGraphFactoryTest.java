package com.codepilot1c.core.agent.langgraph;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.bsc.langgraph4j.GraphRepresentation;
import org.junit.Test;

import com.codepilot1c.core.agent.AgentConfig;
import com.codepilot1c.core.tools.ToolRegistry;

public class LangGraphAgentGraphFactoryTest {

    @Test
    public void graphUsesSingleRunNodeWithoutDomainRouting() {
        LangGraphAgentRunContext context = new LangGraphAgentRunContext(
                null,
                ToolRegistry.getInstance(),
                AgentConfig.defaults(),
                "", //$NON-NLS-1$
                List.of(),
                List.of(),
                new AtomicReference<>(),
                new AtomicReference<>(),
                true);

        String mermaid = LangGraphAgentGraphFactory.buildGraph(context)
                .getGraph(GraphRepresentation.Type.MERMAID, "codepilot1c", true) //$NON-NLS-1$
                .getContent();

        assertTrue(mermaid.contains("run_agent")); //$NON-NLS-1$
        assertFalse(mermaid.contains("select_agent")); //$NON-NLS-1$
        assertFalse(mermaid.contains("agent_bsl")); //$NON-NLS-1$
        assertFalse(mermaid.contains("agent_metadata")); //$NON-NLS-1$
        assertFalse(mermaid.contains("agent_forms")); //$NON-NLS-1$
    }
}
