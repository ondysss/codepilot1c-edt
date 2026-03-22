package com.codepilot1c.core.tools.surface;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

import org.junit.After;
import org.junit.Test;

import com.codepilot1c.core.mcp.host.McpHostConfig;
import com.codepilot1c.core.mcp.host.McpHostRequestRouter;
import com.codepilot1c.core.mcp.host.McpToolExposurePolicy;
import com.codepilot1c.core.mcp.host.prompt.IMcpPromptProvider;
import com.codepilot1c.core.mcp.model.McpPrompt;
import com.codepilot1c.core.mcp.model.McpPromptResult;
import com.codepilot1c.core.model.ToolDefinition;
import com.codepilot1c.core.provider.config.LlmProviderConfig;
import com.codepilot1c.core.provider.config.LlmProviderConfigStore;
import com.codepilot1c.core.provider.config.ProviderType;
import com.codepilot1c.core.tools.ToolRegistry;
import com.google.gson.Gson;

import sun.misc.Unsafe;

public class ToolRegistryAugmentorRuntimeTest {

    private final LlmProviderConfigStore store = LlmProviderConfigStore.getInstance();

    @After
    public void cleanup() throws Exception {
        setStoreState(null, null);
    }

    @Test
    public void activeProviderSelectionReachesToolRegistryAndMcpHost() throws Exception {
        ToolRegistry toolRegistry = createIsolatedRegistry();
        toolRegistry.setAugmentor(new ToolSurfaceAugmentor(List.of(new ToolSurfaceContributor() {
            @Override
            public boolean supports(ToolSurfaceContext context) {
                return true;
            }

            @Override
            public void contribute(ToolSurfaceContext context, ToolDefinition.Builder builder) {
                if (context.getActiveProviderId() != null) {
                    builder.description(builder.getDescription() + " [provider=" //$NON-NLS-1$
                            + context.getActiveProviderId() + "]"); //$NON-NLS-1$
                }
            }
        })));
        toolRegistry.registerDynamicTool(new RuntimeTestTool());
        setStoreState(
                List.of(configured("provider-1", ProviderType.OPENAI_COMPATIBLE)), //$NON-NLS-1$
                "provider-1"); //$NON-NLS-1$

        List<ToolDefinition> definitions = toolRegistry.getToolDefinitions(toolRegistry.createRuntimeSurfaceContext(null));
        assertFalse(definitions.isEmpty());
        assertTrue(definitions.get(0).getDescription().contains("[provider=provider-1]")); //$NON-NLS-1$

        ToolRegistry previous = installSingleton(toolRegistry);
        List<Map<String, Object>> tools;
        try {
            McpHostRequestRouter router = new McpHostRequestRouter(
                    new AllowAllExposurePolicy(),
                    List.of(),
                    new EmptyPromptProvider(),
                    McpHostConfig.MutationPolicy.ALLOW);
            Method listTools = McpHostRequestRouter.class.getDeclaredMethod("listTools"); //$NON-NLS-1$
            listTools.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> effectiveTools = (List<Map<String, Object>>) listTools.invoke(router);
            tools = effectiveTools;
        } finally {
            installSingleton(previous);
        }

        assertFalse(tools.isEmpty());
        assertTrue(String.valueOf(tools.get(0).get("description")).contains("[provider=provider-1]")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void setStoreState(List<LlmProviderConfig> configs, String activeProviderId) throws Exception {
        Field configsField = LlmProviderConfigStore.class.getDeclaredField("cachedConfigs"); //$NON-NLS-1$
        configsField.setAccessible(true);
        configsField.set(store, configs);

        Field activeField = LlmProviderConfigStore.class.getDeclaredField("cachedActiveProviderId"); //$NON-NLS-1$
        activeField.setAccessible(true);
        activeField.set(store, activeProviderId);
    }

    private static LlmProviderConfig configured(String id, ProviderType type) {
        LlmProviderConfig config = new LlmProviderConfig();
        config.setId(id);
        config.setName(id);
        config.setType(type);
        config.setBaseUrl("https://example.com/v1"); //$NON-NLS-1$
        config.setApiKey("key"); //$NON-NLS-1$
        config.setModel("model"); //$NON-NLS-1$
        return config;
    }

    private static final class RuntimeTestTool implements com.codepilot1c.core.tools.ITool {
        @Override public String getName() { return "runtime_test_tool"; } //$NON-NLS-1$
        @Override public String getDescription() { return "Runtime tool"; } //$NON-NLS-1$
        @Override public String getParameterSchema() { return "{\"type\":\"object\"}"; } //$NON-NLS-1$
        @Override public CompletableFuture<com.codepilot1c.core.tools.ToolResult> execute(Map<String, Object> parameters) {
            return CompletableFuture.completedFuture(com.codepilot1c.core.tools.ToolResult.success("ok")); //$NON-NLS-1$
        }
    }

    private static final class AllowAllExposurePolicy implements McpToolExposurePolicy {
        @Override public boolean isExposed(String toolName) { return true; }
        @Override public boolean requiresConfirmation(String toolName, Map<String, Object> args) { return false; }
        @Override public boolean isDestructive(String toolName) { return false; }
    }

    private static final class EmptyPromptProvider implements IMcpPromptProvider {
        @Override public List<McpPrompt> listPrompts() { return Collections.emptyList(); }
        @Override public java.util.Optional<McpPromptResult> getPrompt(String name, Map<String, Object> arguments) {
            return java.util.Optional.empty();
        }
    }

    private static ToolRegistry createIsolatedRegistry() throws Exception {
        ToolRegistry registry = (ToolRegistry) unsafe().allocateInstance(ToolRegistry.class);
        setRegistryField(registry, "tools", new HashMap<String, com.codepilot1c.core.tools.ITool>()); //$NON-NLS-1$
        setRegistryField(registry, "dynamicTools", new ConcurrentHashMap<String, com.codepilot1c.core.tools.ITool>()); //$NON-NLS-1$
        setRegistryField(registry, "gson", new Gson()); //$NON-NLS-1$
        setRegistryField(registry, "augmentor", ToolSurfaceAugmentor.defaultAugmentor()); //$NON-NLS-1$
        return registry;
    }

    private static ToolRegistry installSingleton(ToolRegistry registry) throws Exception {
        Field field = ToolRegistry.class.getDeclaredField("instance"); //$NON-NLS-1$
        field.setAccessible(true);
        ToolRegistry previous = (ToolRegistry) field.get(null);
        field.set(null, registry);
        return previous;
    }

    private static void setRegistryField(ToolRegistry registry, String name, Object value) throws Exception {
        Field field = ToolRegistry.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(registry, value);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); //$NON-NLS-1$
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
