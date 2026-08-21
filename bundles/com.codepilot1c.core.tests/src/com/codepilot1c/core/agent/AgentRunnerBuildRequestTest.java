package com.codepilot1c.core.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.junit.Test;

import com.codepilot1c.core.agent.events.ToolResultEvent;
import com.codepilot1c.core.agent.profiles.ExploreAgentProfile;
import com.codepilot1c.core.agent.profiles.DynamicToolCapability;
import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.model.LlmRequest;
import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.model.LlmStreamChunk;
import com.codepilot1c.core.model.ToolDefinition;
import com.codepilot1c.core.provider.ILlmProvider;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolContextGate;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.surface.ToolSurfaceAugmentor;
import com.google.gson.Gson;
import com.google.gson.JsonParser;

import sun.misc.Unsafe;

public class AgentRunnerBuildRequestTest {

    @Test
    public void sensitiveToolResultTracePayloadContainsOnlyMetadata() throws Exception {
        String toolName = "sensitive_trace_tool"; //$NON-NLS-1$
        ToolRegistry registry = isolatedRegistry(Map.of(toolName, tool(toolName, Set.of("sensitive")))); //$NON-NLS-1$
        AgentRunner runner = new AgentRunner(new NoopProvider(), registry, "system"); //$NON-NLS-1$

        Map<String, Object> success = runner.buildToolResultTracePayload(new ToolResultEvent(
                1, toolName, "call-success", ToolResult.success("stored-secret"), 17L)); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, Object> failure = runner.buildToolResultTracePayload(new ToolResultEvent(
                1, toolName, "call-failure", ToolResult.failure("sensitive-error"), 19L)); //$NON-NLS-1$ //$NON-NLS-2$

        assertSensitiveTraceMetadata(success, 13);
        assertSensitiveTraceMetadata(failure, 15);
    }

    @Test
    public void nonSensitiveToolResultTracePayloadPreservesContentAndError() throws Exception {
        String toolName = "regular_trace_tool"; //$NON-NLS-1$
        ToolRegistry registry = isolatedRegistry(Map.of(toolName, tool(toolName)));
        AgentRunner runner = new AgentRunner(new NoopProvider(), registry, "system"); //$NON-NLS-1$

        Map<String, Object> success = runner.buildToolResultTracePayload(new ToolResultEvent(
                1, toolName, "call-success", ToolResult.success("ordinary-content"), 17L)); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, Object> failure = runner.buildToolResultTracePayload(new ToolResultEvent(
                1, toolName, "call-failure", ToolResult.failure("ordinary-error"), 19L)); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("ordinary-content", success.get("content")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(success.containsKey("error_message")); //$NON-NLS-1$
        assertEquals("ordinary-error", failure.get("error_message")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(failure.containsKey("content")); //$NON-NLS-1$
        assertFalse(success.containsKey("content_omitted")); //$NON-NLS-1$
        assertFalse(failure.containsKey("content_omitted")); //$NON-NLS-1$
    }

    @Test
    public void buildRequestAppliesProfileContextAndConfigFiltering() throws Exception {
        ToolRegistry registry = isolatedRegistry(Map.of(
                "read_file", tool("read_file"),
                "glob", tool("glob"),
                "edit_file", tool("edit_file"),
                "bsl_list_methods", tool("bsl_list_methods")));

        AgentRunner runner = new AgentRunner(new NoopProvider(), registry, "system"); //$NON-NLS-1$
        primeHistory(runner);
        primeContextGate(runner, Set.of("bsl_list_methods")); //$NON-NLS-1$

        AgentConfig config = AgentConfig.builder()
                .profileName("explore") //$NON-NLS-1$
                .disableTool("glob") //$NON-NLS-1$
                .build();

        LlmRequest request = invokeBuildRequest(runner, config);
        List<String> toolNames = request.getTools().stream()
                .map(def -> def.getName())
                .collect(Collectors.toList());

        assertTrue(toolNames.contains("read_file")); //$NON-NLS-1$
        assertFalse("Profile gate must exclude mutating tool", toolNames.contains("edit_file")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("Context gate must exclude primed tool", toolNames.contains("bsl_list_methods")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse("Config disable list must exclude tool", toolNames.contains("glob")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void buildRequestUsesTrustedRuntimeCapabilitiesAcrossProfiles() throws Exception {
        ToolRegistry registry = isolatedRegistry(Map.of());
        registry.registerDynamicTool(tool("mcp_runtime_lookup"), //$NON-NLS-1$
                DynamicToolCapability.READ_ONLY);
        registry.registerDynamicTool(tool("mcp_runtime_update"), //$NON-NLS-1$
                DynamicToolCapability.MUTATING);
        registry.registerDynamicTool(tool("mcp_runtime_unknown")); //$NON-NLS-1$
        AgentRunner runner = new AgentRunner(new NoopProvider(), registry, "system"); //$NON-NLS-1$
        primeHistory(runner);
        primeContextGate(runner, Set.of());

        Set<String> explore = invokeBuildRequest(runner, AgentConfig.builder()
                .profileName("explore").build()).getTools().stream() //$NON-NLS-1$
                .map(ToolDefinition::getName).collect(Collectors.toSet());
        assertTrue(explore.contains("mcp_runtime_lookup")); //$NON-NLS-1$
        assertFalse(explore.contains("mcp_runtime_update")); //$NON-NLS-1$
        assertFalse(explore.contains("mcp_runtime_unknown")); //$NON-NLS-1$

        Set<String> build = invokeBuildRequest(runner, AgentConfig.builder()
                .profileName("build").build()).getTools().stream() //$NON-NLS-1$
                .map(ToolDefinition::getName).collect(Collectors.toSet());
        assertTrue(build.contains("mcp_runtime_lookup")); //$NON-NLS-1$
        assertTrue(build.contains("mcp_runtime_update")); //$NON-NLS-1$
        assertFalse(build.contains("mcp_runtime_unknown")); //$NON-NLS-1$
    }

    @Test
    public void buildRequestRendersToolPromptFromFinalVisibleToolSurface() throws Exception {
        ToolRegistry registry = isolatedRegistry(Map.of(
                "read_file", tool("read_file"),
                "glob", tool("glob"),
                "edit_file", tool("edit_file")));

        AgentRunner runner = new AgentRunner(new NoopProvider(), registry, "system"); //$NON-NLS-1$
        primeHistory(runner,
                LlmMessage.system("BASE PROMPT"), //$NON-NLS-1$
                LlmMessage.user("test")); //$NON-NLS-1$
        primeContextGate(runner, Set.of());

        AgentConfig config = AgentConfig.builder()
                .profileName("explore") //$NON-NLS-1$
                .disableTool("glob") //$NON-NLS-1$
                .build();

        LlmRequest request = invokeBuildRequest(runner, config);
        String systemPrompt = request.getMessages().get(0).getContent();

        assertTrue(systemPrompt.contains("BASE PROMPT")); //$NON-NLS-1$
        assertTrue(systemPrompt.contains("Runtime Tool Surface")); //$NON-NLS-1$
        assertTrue(systemPrompt.contains("`read_file`")); //$NON-NLS-1$
        assertFalse("Prompt must not advertise profile-hidden mutating tools", //$NON-NLS-1$
                systemPrompt.contains("`edit_file`")); //$NON-NLS-1$
        assertFalse("Prompt must not advertise config-disabled tools", //$NON-NLS-1$
                systemPrompt.contains("`glob`")); //$NON-NLS-1$
    }

    @Test
    public void buildRequestUsesEffectiveRegistryDefinitionInToolsAndPrompt() throws Exception {
        ToolRegistry registry = isolatedRegistry(Map.of(
                "read_file", tool("read_file"))); //$NON-NLS-1$ //$NON-NLS-2$
        registry.setAugmentor(ToolSurfaceAugmentor.defaultAugmentor());

        AgentRunner runner = new AgentRunner(new NoopProvider(), registry, "system"); //$NON-NLS-1$
        primeHistory(runner,
                LlmMessage.system("BASE PROMPT"), //$NON-NLS-1$
                LlmMessage.user("test")); //$NON-NLS-1$
        primeContextGate(runner, Set.of());

        AgentConfig config = AgentConfig.builder()
                .profileName("explore") //$NON-NLS-1$
                .build();

        LlmRequest request = invokeBuildRequest(runner, config);
        String systemPrompt = request.getMessages().get(0).getContent();
        ToolDefinition expected = registry.getToolDefinition(
                registry.getTool("read_file"), //$NON-NLS-1$
                registry.createRuntimeSurfaceContext(new ExploreAgentProfile()));
        ToolDefinition actual = request.getTools().stream()
                .filter(definition -> "read_file".equals(definition.getName())) //$NON-NLS-1$
                .findFirst()
                .orElseThrow();

        assertEquals(expected.getDescription(), actual.getDescription());
        assertEquals(JsonParser.parseString(expected.getParametersSchema()),
                JsonParser.parseString(actual.getParametersSchema()));
        String compactDescription = expected.getDescription()
                .replace('\n', ' ')
                .replaceAll("\\s+", " ") //$NON-NLS-1$ //$NON-NLS-2$
                .strip();
        assertTrue(systemPrompt.contains(compactDescription.substring(
                0, Math.min(359, compactDescription.length()))));
    }

    @Test
    public void extensionProfileKeepsBootstrapExtensionToolVisible() throws Exception {
        ToolRegistry registry = isolatedRegistry(Map.of(
                "read_file", tool("read_file"),
                "list_files", tool("list_files"),
                "extension_manage", tool("extension_manage")));

        AgentRunner runner = new AgentRunner(new NoopProvider(), registry, "system"); //$NON-NLS-1$
        primeHistory(runner);
        primeContextGate(runner, Set.of());

        AgentConfig config = AgentConfig.builder()
                .profileName("extension") //$NON-NLS-1$
                .build();

        LlmRequest request = invokeBuildRequest(runner, config);
        List<String> toolNames = request.getTools().stream()
                .map(def -> def.getName())
                .collect(Collectors.toList());

        assertTrue("extension_manage must remain visible for extension bootstrap flows", //$NON-NLS-1$
                toolNames.contains("extension_manage")); //$NON-NLS-1$
    }

    private static LlmRequest invokeBuildRequest(AgentRunner runner, AgentConfig config) throws Exception {
        Method method = AgentRunner.class.getDeclaredMethod("buildRequest", AgentConfig.class); //$NON-NLS-1$
        method.setAccessible(true);
        return (LlmRequest) method.invoke(runner, config);
    }

    private static void primeContextGate(AgentRunner runner, Set<String> excludedTools) throws Exception {
        Field field = AgentRunner.class.getDeclaredField("contextGate"); //$NON-NLS-1$
        field.setAccessible(true);
        ToolContextGate gate = (ToolContextGate) field.get(runner);

        Field cachedExcluded = ToolContextGate.class.getDeclaredField("cachedExcluded"); //$NON-NLS-1$
        cachedExcluded.setAccessible(true);
        cachedExcluded.set(gate, excludedTools);

        Field cacheTimestamp = ToolContextGate.class.getDeclaredField("cacheTimestamp"); //$NON-NLS-1$
        cacheTimestamp.setAccessible(true);
        cacheTimestamp.setLong(gate, System.currentTimeMillis());
    }

    private static void primeHistory(AgentRunner runner) throws Exception {
        primeHistory(runner, LlmMessage.user("test")); //$NON-NLS-1$
    }

    private static void primeHistory(AgentRunner runner, LlmMessage... messages) throws Exception {
        Field field = AgentRunner.class.getDeclaredField("conversationHistory"); //$NON-NLS-1$
        field.setAccessible(true);
        field.set(runner, List.of(messages));
    }

    private static ToolRegistry isolatedRegistry(Map<String, ITool> tools) throws Exception {
        ToolRegistry registry = (ToolRegistry) unsafe().allocateInstance(ToolRegistry.class);
        setField(registry, "tools", new HashMap<>(tools)); //$NON-NLS-1$
        setField(registry, "dynamicTools", new ConcurrentHashMap<String, ITool>()); //$NON-NLS-1$
        setField(registry, "dynamicToolCapabilities", //$NON-NLS-1$
                new ConcurrentHashMap<String, DynamicToolCapability>());
        setField(registry, "gson", new Gson()); //$NON-NLS-1$
        setField(registry, "augmentor", ToolSurfaceAugmentor.passthrough()); //$NON-NLS-1$
        return registry;
    }

    private static ITool tool(String name) {
        return tool(name, Set.of());
    }

    private static ITool tool(String name, Set<String> tags) {
        return new ITool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return "Test tool " + name; //$NON-NLS-1$
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
            public Set<String> getTags() {
                return tags;
            }
        };
    }

    private static void assertSensitiveTraceMetadata(Map<String, Object> payload, int contentLength) {
        assertEquals(Boolean.TRUE, payload.get("content_omitted")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(contentLength), payload.get("content_length")); //$NON-NLS-1$
        assertFalse(payload.containsKey("content")); //$NON-NLS-1$
        assertFalse(payload.containsKey("error_message")); //$NON-NLS-1$
        assertFalse(payload.keySet().stream().anyMatch(key -> key.startsWith("exception"))); //$NON-NLS-1$
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = ToolRegistry.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); //$NON-NLS-1$
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static final class NoopProvider implements ILlmProvider {
        @Override
        public String getId() {
            return "noop"; //$NON-NLS-1$
        }

        @Override
        public String getDisplayName() {
            return "Noop"; //$NON-NLS-1$
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public boolean supportsStreaming() {
            return false;
        }

        @Override
        public CompletableFuture<LlmResponse> complete(LlmRequest request) {
            return CompletableFuture.completedFuture(LlmResponse.of("ok")); //$NON-NLS-1$
        }

        @Override
        public void streamComplete(LlmRequest request, Consumer<LlmStreamChunk> consumer) {
            consumer.accept(LlmStreamChunk.complete(LlmResponse.FINISH_REASON_STOP));
        }

        @Override
        public void cancel() {
        }

        @Override
        public void dispose() {
        }
    }
}
