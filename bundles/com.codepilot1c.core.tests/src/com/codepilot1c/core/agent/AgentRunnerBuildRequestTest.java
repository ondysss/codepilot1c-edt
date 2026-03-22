package com.codepilot1c.core.agent;

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

import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.model.LlmRequest;
import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.model.LlmStreamChunk;
import com.codepilot1c.core.provider.ILlmProvider;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ProviderContextResolver;
import com.codepilot1c.core.tools.ToolContextGate;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.surface.ToolSurfaceAugmentor;
import com.google.gson.Gson;

import sun.misc.Unsafe;

public class AgentRunnerBuildRequestTest {

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
        Field field = AgentRunner.class.getDeclaredField("conversationHistory"); //$NON-NLS-1$
        field.setAccessible(true);
        field.set(runner, List.of(LlmMessage.user("test"))); //$NON-NLS-1$
    }

    private static ToolRegistry isolatedRegistry(Map<String, ITool> tools) throws Exception {
        ToolRegistry registry = (ToolRegistry) unsafe().allocateInstance(ToolRegistry.class);
        setField(registry, "tools", new HashMap<>(tools)); //$NON-NLS-1$
        setField(registry, "dynamicTools", new ConcurrentHashMap<String, ITool>()); //$NON-NLS-1$
        setField(registry, "gson", new Gson()); //$NON-NLS-1$
        setField(registry, "augmentor", ToolSurfaceAugmentor.passthrough()); //$NON-NLS-1$
        setField(registry, "providerContextResolver", new ProviderContextResolver()); //$NON-NLS-1$
        return registry;
    }

    private static ITool tool(String name) {
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
        };
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
