package com.codepilot1c.core.agent.langgraph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.junit.Ignore;
import org.junit.Test;

import com.codepilot1c.core.agent.AgentConfig;
import com.codepilot1c.core.agent.AgentResult;
import com.codepilot1c.core.agent.profiles.OrchestratorProfile;
import com.codepilot1c.core.model.LlmRequest;
import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.model.LlmStreamChunk;
import com.codepilot1c.core.model.ToolCall;
import com.codepilot1c.core.provider.ILlmProvider;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ProviderContextResolver;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.surface.ToolSurfaceAugmentor;
import com.google.gson.Gson;

import sun.misc.Unsafe;

// Depends on Eclipse Platform OSGi (SessionManager.<clinit> calls Platform.getLog)
// which is unavailable in a plain Maven JUnit run. Run as Eclipse JUnit Plug-in Test.
@Ignore("requires Eclipse Platform OSGi — run as Eclipse JUnit Plug-in Test") //$NON-NLS-1$
public class LangGraphAgentRunnerTest {

    @Test
    public void runnerKeepsDelegateToolEnabledForBslLikePrompt() throws Exception {
        ToolRegistry registry = isolatedRegistry(Map.of(
                "delegate_to_agent", delegateTool())); //$NON-NLS-1$
        SequencedProvider provider = new SequencedProvider();
        LangGraphAgentRunner runner = new LangGraphAgentRunner(provider, registry, "system"); //$NON-NLS-1$

        AgentConfig config = AgentConfig.builder()
                .profileName(OrchestratorProfile.ID)
                .enabledTools(Set.of("delegate_to_agent")) //$NON-NLS-1$
                .maxSteps(4)
                .streamingEnabled(false)
                .build();

        AgentResult result = runner.run(
                "Открой модуль CommonModules/МоиЗадачи/Module.bsl и делегируй детальный анализ кода.", //$NON-NLS-1$
                config)
                .get();

        assertTrue(result.isSuccess());
        assertEquals("delegated ok", result.getFinalResponse()); //$NON-NLS-1$
        assertEquals(1, result.getToolCallsExecuted());
        assertEquals(2, provider.getRequestToolNames().size());
        assertTrue(provider.getRequestToolNames().get(0).contains("delegate_to_agent")); //$NON-NLS-1$
    }

    private static ITool delegateTool() {
        return new ITool() {
            @Override
            public String getName() {
                return "delegate_to_agent"; //$NON-NLS-1$
            }

            @Override
            public String getDescription() {
                return "Delegates work to a sub-agent"; //$NON-NLS-1$
            }

            @Override
            public String getParameterSchema() {
                return "{\"type\":\"object\",\"properties\":{\"agentType\":{\"type\":\"string\"},\"task\":{\"type\":\"string\"}},\"required\":[\"agentType\",\"task\"]}"; //$NON-NLS-1$
            }

            @Override
            public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
                return CompletableFuture.completedFuture(ToolResult.success("subagent ok")); //$NON-NLS-1$
            }
        };
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

    private static final class SequencedProvider implements ILlmProvider {
        private final AtomicInteger callCount = new AtomicInteger();
        private final List<List<String>> requestToolNames = new ArrayList<>();

        @Override
        public String getId() {
            return "seq"; //$NON-NLS-1$
        }

        @Override
        public String getDisplayName() {
            return "Sequenced"; //$NON-NLS-1$
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
            requestToolNames.add(request.hasTools()
                    ? request.getTools().stream().map(def -> def.getName()).collect(Collectors.toList())
                    : List.of());
            int current = callCount.getAndIncrement();
            if (current == 0) {
                return CompletableFuture.completedFuture(LlmResponse.withToolCalls(List.of(
                        new ToolCall("call-1", "delegate_to_agent", //$NON-NLS-1$ //$NON-NLS-2$
                                "{\"agentType\":\"code\",\"task\":\"Проверь модуль\"}")))); //$NON-NLS-1$
            }
            return CompletableFuture.completedFuture(LlmResponse.of("delegated ok")); //$NON-NLS-1$
        }

        List<List<String>> getRequestToolNames() {
            return requestToolNames;
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
