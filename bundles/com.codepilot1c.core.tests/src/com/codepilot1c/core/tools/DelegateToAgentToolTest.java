package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.junit.After;
import org.junit.Test;

import com.codepilot1c.core.agent.AgentConfig;
import com.codepilot1c.core.agent.AgentResult;
import com.codepilot1c.core.agent.profiles.AgentProfile;
import com.codepilot1c.core.agent.profiles.MetadataBuildProfile;
import com.codepilot1c.core.agent.profiles.ProfileRouter;
import com.codepilot1c.core.model.LlmRequest;
import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.model.LlmStreamChunk;
import com.codepilot1c.core.provider.ILlmProvider;
import com.codepilot1c.core.provider.LlmProviderRegistry;
import com.codepilot1c.core.provider.ProviderCapabilities;
import com.google.gson.Gson;

import sun.misc.Unsafe;

public class DelegateToAgentToolTest {

    private LlmProviderRegistry previousRegistry;

    @After
    public void restoreRegistry() throws Exception {
        if (previousRegistry != null) {
            installRegistry(previousRegistry);
            previousRegistry = null;
        }
    }

    @Test
    public void delegateToolRejectsUnsupportedAgentType() throws Exception {
        DelegateToAgentTool tool = new DelegateToAgentTool(placeholderRegistry());

        ToolResult result = tool.execute(Map.of(
                "agentType", "unknown", //$NON-NLS-1$ //$NON-NLS-2$
                "task", "Проверить конфигурацию")).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("Unsupported agentType")); //$NON-NLS-1$
    }

    @Test
    public void delegateToolRejectsNonBackendProvider() throws Exception {
        DelegateToAgentTool tool = new DelegateToAgentTool(placeholderRegistry());
        previousRegistry = installRegistry(registryWithLegacyProvider(new FakeProvider()));

        ToolResult result = tool.execute(Map.of(
                "agentType", "metadata", //$NON-NLS-1$ //$NON-NLS-2$
                "task", "Создай справочник Товары")).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("CodePilot Account backend")); //$NON-NLS-1$
    }

    @Test
    public void delegateToolPassesContextAndMappedProfileToTaskTool() throws Exception {
        CapturingExecutor executor = new CapturingExecutor();
        TaskTool taskTool = new TaskTool(placeholderRegistry(), new ProfileRouter(), executor);
        DelegateToAgentTool tool = new DelegateToAgentTool(taskTool, new ProfileRouter());
        previousRegistry = installRegistry(registryWithLegacyProvider(new BackendProvider()));

        ToolResult result = tool.execute(Map.of(
                "agentType", "metadata", //$NON-NLS-1$ //$NON-NLS-2$
                "task", "Создай справочник Товары", //$NON-NLS-1$ //$NON-NLS-2$
                "context", "project=DemoConfiguration; file=Catalogs/Items.xml")).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(result.isSuccess());
        assertEquals(MetadataBuildProfile.ID, executor.config.getProfileName());
        assertTrue(executor.prompt.contains("Дополнительный контекст")); //$NON-NLS-1$
        assertTrue(executor.prompt.contains("project=DemoConfiguration")); //$NON-NLS-1$
    }

    private static LlmProviderRegistry registryWithLegacyProvider(ILlmProvider provider) throws Exception {
        Constructor<LlmProviderRegistry> constructor = LlmProviderRegistry.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        LlmProviderRegistry registry = constructor.newInstance();

        Field legacyProvidersField = LlmProviderRegistry.class.getDeclaredField("legacyProviders"); //$NON-NLS-1$
        legacyProvidersField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ILlmProvider> legacyProviders = (Map<String, ILlmProvider>) legacyProvidersField.get(registry);
        legacyProviders.put(provider.getId(), provider);

        Field initializedField = LlmProviderRegistry.class.getDeclaredField("initialized"); //$NON-NLS-1$
        initializedField.setAccessible(true);
        initializedField.set(registry, true);
        return registry;
    }

    private static LlmProviderRegistry installRegistry(LlmProviderRegistry registry) throws Exception {
        Field instanceField = LlmProviderRegistry.class.getDeclaredField("instance"); //$NON-NLS-1$
        instanceField.setAccessible(true);
        LlmProviderRegistry previous = (LlmProviderRegistry) instanceField.get(null);
        instanceField.set(null, registry);
        return previous;
    }

    private static ToolRegistry placeholderRegistry() throws Exception {
        ToolRegistry registry = (ToolRegistry) unsafe().allocateInstance(ToolRegistry.class);
        setToolRegistryField(registry, "tools", new HashMap<String, ITool>()); //$NON-NLS-1$
        setToolRegistryField(registry, "dynamicTools", new ConcurrentHashMap<String, ITool>()); //$NON-NLS-1$
        setToolRegistryField(registry, "gson", new Gson()); //$NON-NLS-1$
        return registry;
    }

    private static void setToolRegistryField(ToolRegistry registry, String name, Object value) throws Exception {
        Field field = ToolRegistry.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(registry, value);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); //$NON-NLS-1$
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static class FakeProvider implements ILlmProvider {

        @Override
        public String getId() {
            return "fake"; //$NON-NLS-1$
        }

        @Override
        public String getDisplayName() {
            return "Fake"; //$NON-NLS-1$
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

    private static final class BackendProvider extends FakeProvider {
        @Override
        public ProviderCapabilities getCapabilities() {
            return ProviderCapabilities.builder()
                    .codePilotBackend(true)
                    .backendOptimizations(true)
                    .build();
        }
    }

    private static final class CapturingExecutor implements TaskTool.SubagentExecutor {
        private AgentConfig config;
        private String prompt;

        @Override
        public AgentResult run(
                ILlmProvider provider,
                ToolRegistry toolRegistry,
                AgentProfile profile,
                String prompt,
                AgentConfig config) {
            this.prompt = prompt;
            this.config = config;
            return AgentResult.success("ok", Collections.emptyList(), 1, 1, 5); //$NON-NLS-1$
        }
    }
}
