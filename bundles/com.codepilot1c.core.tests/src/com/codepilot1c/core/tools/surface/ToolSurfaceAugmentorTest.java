package com.codepilot1c.core.tools.surface;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.Test;

import com.codepilot1c.core.model.ToolDefinition;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolResult;

public class ToolSurfaceAugmentorTest {

    private static final ITool SAMPLE_TOOL = new ITool() {
        @Override
        public String getName() {
            return "sample_tool"; //$NON-NLS-1$
        }

        @Override
        public String getDescription() {
            return "Raw description."; //$NON-NLS-1$
        }

        @Override
        public String getParameterSchema() {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        @Override
        public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            return CompletableFuture.completedFuture(ToolResult.success("ok")); //$NON-NLS-1$
        }
    };

    @Test
    public void passthroughPreservesRawDefinition() {
        ToolDefinition effective = ToolSurfaceAugmentor.passthrough()
                .augment(SAMPLE_TOOL, ToolSurfaceContext.passthrough());

        assertNotNull(effective);
        assertEquals("sample_tool", effective.getName()); //$NON-NLS-1$
        assertEquals("Raw description.", effective.getDescription()); //$NON-NLS-1$
        assertEquals("{\"type\":\"object\"}", effective.getParametersSchema()); //$NON-NLS-1$
    }

    @Test
    public void contributorsAreAppliedInOrder() {
        ToolSurfaceContributor first = new MarkerContributor(" [first]", -100); //$NON-NLS-1$ //$NON-NLS-2$
        ToolSurfaceContributor second = new MarkerContributor(" [second]", 10); //$NON-NLS-1$ //$NON-NLS-2$

        ToolDefinition effective = new ToolSurfaceAugmentor(List.of(second, first))
                .augment(SAMPLE_TOOL, ToolSurfaceContext.passthrough());

        assertEquals("Raw description. [first] [second]", effective.getDescription()); //$NON-NLS-1$
    }

    private static final class MarkerContributor implements ToolSurfaceContributor {
        private final String marker;
        private final int order;

        private MarkerContributor(String marker, int order) {
            this.marker = marker;
            this.order = order;
        }

        @Override
        public boolean supports(ToolSurfaceContext context) {
            return true;
        }

        @Override
        public void contribute(ToolSurfaceContext context, ToolDefinition.Builder builder) {
            builder.description(builder.getDescription() + marker);
        }

        @Override
        public int getOrder() {
            return order;
        }
    }
}
