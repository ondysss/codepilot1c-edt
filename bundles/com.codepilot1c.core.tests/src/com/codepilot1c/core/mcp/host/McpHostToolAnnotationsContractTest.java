package com.codepilot1c.core.mcp.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.junit.Test;

import com.codepilot1c.core.mcp.host.prompt.IMcpPromptProvider;
import com.codepilot1c.core.mcp.host.session.McpHostSession;
import com.codepilot1c.core.mcp.model.McpMessage;
import com.codepilot1c.core.mcp.model.McpPrompt;
import com.codepilot1c.core.mcp.model.McpPromptResult;
import com.codepilot1c.core.model.ToolDefinition;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.surface.ToolSurfaceContext;
import com.google.gson.Gson;

public class McpHostToolAnnotationsContractTest {

    private static final String DESTRUCTIVE = "mcp_annotations_destructive"; //$NON-NLS-1$
    private static final String CONFIRMATION = "mcp_annotations_confirmation"; //$NON-NLS-1$
    private static final String READ_ONLY = "mcp_annotations_read_only"; //$NON-NLS-1$
    private static final String UNKNOWN = "mcp_annotations_unknown"; //$NON-NLS-1$
    private static final String CONTRADICTORY = "mcp_annotations_contradictory"; //$NON-NLS-1$

    @Test
    public void publishesOnlyAffirmativeHintsProvenByToolContracts() {
        List<ITool> tools = List.of(
                new FakeTool(DESTRUCTIVE, true, true, false, Set.of()),
                new FakeTool(CONFIRMATION, false, false, true, Set.of()),
                new FakeTool(READ_ONLY, false, false, false, Set.of("read-only")), //$NON-NLS-1$
                new FakeTool(UNKNOWN, false, false, false, Set.of()),
                new FakeTool(CONTRADICTORY, false, true, false, Set.of("read-only"))); //$NON-NLS-1$
        ToolRegistry registry = ToolRegistry.getInstance();
        tools.forEach(registry::registerDynamicTool);
        try {
            Map<String, Map<String, Object>> listed = listTools(tools);

            Map<String, Object> destructive = listed.get(DESTRUCTIVE);
            assertEquals(Boolean.TRUE, annotations(destructive).get("destructiveHint")); //$NON-NLS-1$
            assertFalse(annotations(destructive).containsKey("readOnlyHint")); //$NON-NLS-1$
            assertEquals(Boolean.TRUE, metadata(destructive).get(
                    "codepilot1c/requiresConfirmation")); //$NON-NLS-1$

            Map<String, Object> confirmation = listed.get(CONFIRMATION);
            assertFalse(confirmation.containsKey("annotations")); //$NON-NLS-1$
            assertEquals(Boolean.TRUE, metadata(confirmation).get(
                    "codepilot1c/requiresConfirmation")); //$NON-NLS-1$

            Map<String, Object> readOnly = listed.get(READ_ONLY);
            assertEquals(Boolean.TRUE, annotations(readOnly).get("readOnlyHint")); //$NON-NLS-1$
            assertFalse(annotations(readOnly).containsKey("destructiveHint")); //$NON-NLS-1$
            assertFalse(readOnly.containsKey("_meta")); //$NON-NLS-1$

            assertNoOptionalMetadata(listed.get(UNKNOWN));
            assertNoOptionalMetadata(listed.get(CONTRADICTORY));
        } finally {
            tools.forEach(tool -> registry.unregisterDynamicTool(tool.getName()));
        }
    }

    @Test
    public void preservesProviderNeutralToolFields() {
        ITool tool = new FakeTool(READ_ONLY, false, false, false, Set.of("read-only")); //$NON-NLS-1$
        ToolRegistry registry = ToolRegistry.getInstance();
        registry.registerDynamicTool(tool);
        try {
            ToolDefinition expected = registry.getToolDefinition(tool,
                    registry.createRuntimeSurfaceContext(ToolSurfaceContext.defaultProfile()));
            Map<String, Object> listed = listTools(List.of(tool)).get(READ_ONLY);

            assertNotNull(listed);
            assertEquals(expected.getName(), listed.get("name")); //$NON-NLS-1$
            assertEquals(expected.getDescription(), listed.get("description")); //$NON-NLS-1$
            assertEquals(new Gson().fromJson(expected.getParametersSchema(), Map.class),
                    listed.get("inputSchema")); //$NON-NLS-1$
        } finally {
            registry.unregisterDynamicTool(tool.getName());
        }
    }

    private Map<String, Map<String, Object>> listTools(List<ITool> tools) {
        Set<String> names = tools.stream().map(ITool::getName).collect(java.util.stream.Collectors.toSet());
        McpHostRequestRouter router = new McpHostRequestRouter(
                new NamedExposurePolicy(names),
                List.of(),
                new EmptyPromptProvider(),
                McpHostConfig.MutationPolicy.ALLOW);
        McpMessage request = new McpMessage();
        request.setId("annotations-list"); //$NON-NLS-1$
        request.setMethod("tools/list"); //$NON-NLS-1$
        request.setParams(Map.of());

        McpMessage response = router.route(request, new McpHostSession("annotations-test")); //$NON-NLS-1$
        assertFalse(response.isErrorResponse());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> listedTools = (List<Map<String, Object>>) result.get("tools"); //$NON-NLS-1$
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Map<String, Object> listed : listedTools) {
            byName.put((String) listed.get("name"), listed); //$NON-NLS-1$
        }
        assertEquals(names, byName.keySet());
        return byName;
    }

    private void assertNoOptionalMetadata(Map<String, Object> tool) {
        assertNotNull(tool);
        assertFalse(tool.containsKey("annotations")); //$NON-NLS-1$
        assertFalse(tool.containsKey("_meta")); //$NON-NLS-1$
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> annotations(Map<String, Object> tool) {
        assertTrue(tool.containsKey("annotations")); //$NON-NLS-1$
        return (Map<String, Object>) tool.get("annotations"); //$NON-NLS-1$
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> metadata(Map<String, Object> tool) {
        assertTrue(tool.containsKey("_meta")); //$NON-NLS-1$
        return (Map<String, Object>) tool.get("_meta"); //$NON-NLS-1$
    }

    private static final class FakeTool implements ITool {
        private final String name;
        private final boolean destructive;
        private final boolean mutating;
        private final boolean confirmation;
        private final Set<String> tags;

        private FakeTool(String name, boolean destructive, boolean mutating,
                boolean confirmation, Set<String> tags) {
            this.name = name;
            this.destructive = destructive;
            this.mutating = mutating;
            this.confirmation = confirmation;
            this.tags = tags;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "Fake tool " + name; //$NON-NLS-1$
        }

        @Override
        public String getParameterSchema() {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        @Override
        public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            return CompletableFuture.completedFuture(ToolResult.success("ok")); //$NON-NLS-1$
        }

        @Override
        public boolean isDestructive() {
            return destructive;
        }

        @Override
        public boolean isMutating() {
            return mutating;
        }

        @Override
        public boolean requiresConfirmation() {
            return confirmation;
        }

        @Override
        public Set<String> getTags() {
            return tags;
        }
    }

    private static final class NamedExposurePolicy implements McpToolExposurePolicy {
        private final Set<String> names;

        private NamedExposurePolicy(Set<String> names) {
            this.names = names;
        }

        @Override
        public boolean isExposed(String toolName) {
            return names.contains(toolName);
        }

        @Override
        public boolean requiresConfirmation(String toolName, Map<String, Object> args) {
            return false;
        }

        @Override
        public boolean isDestructive(String toolName) {
            return false;
        }
    }

    private static final class EmptyPromptProvider implements IMcpPromptProvider {
        @Override
        public List<McpPrompt> listPrompts() {
            return List.of();
        }

        @Override
        public Optional<McpPromptResult> getPrompt(String name, Map<String, Object> arguments) {
            return Optional.empty();
        }
    }
}
