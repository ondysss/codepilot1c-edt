/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.gsd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.codepilot1c.core.agent.profiles.AgentCapability;
import com.codepilot1c.core.agent.profiles.AgentProfileRegistry;
import com.codepilot1c.core.agent.profiles.DynamicToolCapability;
import com.codepilot1c.core.agent.profiles.GsdDiscussProfile;
import com.codepilot1c.core.mcp.host.McpHostConfig;
import com.codepilot1c.core.mcp.host.McpHostRequestRouter;
import com.codepilot1c.core.mcp.host.McpToolExposurePolicy;
import com.codepilot1c.core.mcp.host.prompt.IMcpPromptProvider;
import com.codepilot1c.core.mcp.host.session.McpHostSession;
import com.codepilot1c.core.mcp.model.McpMessage;
import com.codepilot1c.core.mcp.model.McpPrompt;
import com.codepilot1c.core.mcp.model.McpPromptResult;
import com.codepilot1c.core.model.ToolCall;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolExecutionContext;
import com.codepilot1c.core.tools.ToolExecutionService;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.meta.ToolDescriptorRegistry;
import com.codepilot1c.core.tools.surface.ToolSurfaceAugmentor;
import com.codepilot1c.core.ui.ChatToolGate;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import sun.misc.Unsafe;

public class GsdFeatureDisablementIntegrationTest {

    private static final String PROBE = "gsd_feature_gate_probe"; //$NON-NLS-1$

    private String previousJvmValue;
    private ToolRegistry previousRegistry;
    private ToolRegistry registry;

    @Before
    public void disableGsd() throws Exception {
        previousJvmValue = System.getProperty(GsdFeatureGate.JVM_PROPERTY);
        System.setProperty(GsdFeatureGate.JVM_PROPERTY, "false"); //$NON-NLS-1$
        registry = isolatedRegistry();
        previousRegistry = installRegistry(registry);
    }

    @After
    public void restoreJvmValue() throws Exception {
        if (previousJvmValue == null) {
            System.clearProperty(GsdFeatureGate.JVM_PROPERTY);
        } else {
            System.setProperty(GsdFeatureGate.JVM_PROPERTY, previousJvmValue);
        }
        installRegistry(previousRegistry);
    }

    @Test
    public void keepsGsdToolsRegisteredButRemovesEveryModelFacingSurface() {
        assertNotNull(registry.getTool("gsd_get_state")); //$NON-NLS-1$
        assertTrue(registry.getAllTools().stream()
                .anyMatch(tool -> "gsd_get_state".equals(tool.getName()))); //$NON-NLS-1$
        assertFalse(registry.getModelFacingToolResolutions().stream()
                .anyMatch(tool -> tool.name().startsWith("gsd_"))); //$NON-NLS-1$
        assertFalse(registry.getToolDefinitions().stream()
                .anyMatch(tool -> tool.getName().startsWith("gsd_"))); //$NON-NLS-1$

        ChatToolGate chat = new ChatToolGate(
                new GsdDiscussProfile(), List::of, ignored -> Map.of(),
                () -> true, () -> true);
        assertFalse(chat.visibleToolDefinitions(registry).stream()
                .anyMatch(tool -> tool.getName().startsWith("gsd_"))); //$NON-NLS-1$

        McpMessage listed = router().route(request("tools/list", Map.of()), //$NON-NLS-1$
                new McpHostSession("gsd-disabled-list")); //$NON-NLS-1$
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) //$NON-NLS-1$
                ((Map<String, Object>) listed.getResult()).get("tools"); //$NON-NLS-1$
        assertFalse(tools.stream().anyMatch(
                tool -> String.valueOf(tool.get("name")).startsWith("gsd_"))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void centralExecutionGateBlocksEveryOverloadBeforeToolExecute() {
        CountingGsdTool probe = new CountingGsdTool();
        registry.registerDynamicTool(probe);
        ToolCall call = new ToolCall("direct-gsd", PROBE, "{}"); //$NON-NLS-1$ //$NON-NLS-2$

        ToolResult raw = registry.execute(call).join();
        ToolExecutionContext context = new ToolExecutionContext(
                "explore", AgentCapability.READ_ONLY, 0); //$NON-NLS-1$
        ToolResult scoped = registry.execute(
                call, Map.of(), null, null, context).join();
        ToolResult exact = registry.getExecutionService().executeIfCurrent(
                call, Map.of(), null, null, context, registry.resolveTool(PROBE))
                .orElseThrow()
                .join();

        assertGsdDisabled(raw);
        assertGsdDisabled(scoped);
        assertGsdDisabled(exact);
        assertEquals(raw.getErrorMessage(), scoped.getErrorMessage());
        assertEquals(raw.getErrorMessage(), exact.getErrorMessage());
        assertEquals(raw.getStructuredData(), scoped.getStructuredData());
        assertEquals(raw.getStructuredData(), exact.getStructuredData());
        assertEquals("No execution overload may reach ITool.execute", //$NON-NLS-1$
                0, probe.executions.get());

        McpMessage response = router().route(request("tools/call", Map.of( //$NON-NLS-1$
                "name", PROBE, "arguments", Map.of())), //$NON-NLS-1$ //$NON-NLS-2$
                new McpHostSession("gsd-disabled-call")); //$NON-NLS-1$
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        assertEquals(Boolean.TRUE, result.get("isError")); //$NON-NLS-1$
        JsonObject structured = (JsonObject) result.get("structuredContent"); //$NON-NLS-1$
        assertEquals("gsd_disabled", structured.get("error_code").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(0, probe.executions.get());
    }

    private static void assertGsdDisabled(ToolResult result) {
        assertFalse(result.isSuccess());
        assertEquals("gsd_disabled", result.getStructuredString("error_code")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void disabledProfilesAreUnavailableAndChatFallsBackToExploreNotBuild() {
        AgentProfileRegistry registry = AgentProfileRegistry.getInstance();

        assertTrue(registry.getProfile("gsd-discuss").isPresent()); //$NON-NLS-1$
        assertTrue(registry.getAvailableProfile("gsd-discuss").isEmpty()); //$NON-NLS-1$
        assertFalse(registry.getAllProfiles().stream()
                .anyMatch(profile -> profile.getId().startsWith("gsd-"))); //$NON-NLS-1$
        assertThrows(IllegalArgumentException.class,
                () -> registry.createConfig(new GsdDiscussProfile()));
        assertEquals("explore", ChatToolGate.selectProfile("gsd-discuss").getId()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static McpHostRequestRouter router() {
        return new McpHostRequestRouter(new AllowAllExposure(), List.of(),
                new EmptyPrompts(), McpHostConfig.MutationPolicy.ALLOW);
    }

    private static ToolRegistry isolatedRegistry() throws Exception {
        ToolRegistry result = (ToolRegistry) unsafe().allocateInstance(ToolRegistry.class);
        Map<String, ITool> builtins = new HashMap<>();
        builtins.put("read_file", new NamedTool("read_file")); //$NON-NLS-1$ //$NON-NLS-2$
        builtins.put("gsd_get_state", new NamedTool("gsd_get_state")); //$NON-NLS-1$ //$NON-NLS-2$
        setField(result, "tools", builtins); //$NON-NLS-1$
        setField(result, "dynamicTools", //$NON-NLS-1$
                new ConcurrentHashMap<String, ITool>());
        setField(result, "dynamicToolCapabilities", //$NON-NLS-1$
                new ConcurrentHashMap<String, DynamicToolCapability>());
        setField(result, "effectiveToolSlots", new HashMap<>()); //$NON-NLS-1$
        setField(result, "gson", new Gson()); //$NON-NLS-1$
        setField(result, "descriptorRegistry", ToolDescriptorRegistry.createDetached()); //$NON-NLS-1$
        setField(result, "augmentor", ToolSurfaceAugmentor.passthrough()); //$NON-NLS-1$
        setField(result, "executionService", new ToolExecutionService(result)); //$NON-NLS-1$
        return result;
    }

    private static ToolRegistry installRegistry(ToolRegistry replacement) throws Exception {
        Field field = ToolRegistry.class.getDeclaredField("instance"); //$NON-NLS-1$
        field.setAccessible(true);
        ToolRegistry previous = (ToolRegistry) field.get(null);
        field.set(null, replacement);
        return previous;
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

    private static McpMessage request(String method, Object params) {
        McpMessage request = new McpMessage();
        request.setMethod(method);
        request.setRawId(method + "-id"); //$NON-NLS-1$
        request.setParams(params);
        return request;
    }

    private static final class CountingGsdTool extends AbstractTool {
        private final AtomicInteger executions = new AtomicInteger();

        @Override
        public String getName() {
            return PROBE;
        }

        @Override
        public String getDescription() {
            return "GSD feature-gate probe"; //$NON-NLS-1$
        }

        @Override
        public String getParameterSchema() {
            return "{\"type\":\"object\",\"properties\":{}}"; //$NON-NLS-1$
        }

        @Override
        protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
            executions.incrementAndGet();
            return CompletableFuture.completedFuture(ToolResult.success("executed")); //$NON-NLS-1$
        }
    }

    private static final class NamedTool extends AbstractTool {
        private final String toolName;

        private NamedTool(String toolName) {
            this.toolName = toolName;
        }

        @Override
        public String getName() {
            return toolName;
        }

        @Override
        public String getDescription() {
            return "Test tool " + toolName; //$NON-NLS-1$
        }

        @Override
        public String getParameterSchema() {
            return "{\"type\":\"object\",\"properties\":{}}"; //$NON-NLS-1$
        }

        @Override
        protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
            return CompletableFuture.completedFuture(ToolResult.success("ok")); //$NON-NLS-1$
        }
    }

    private static final class AllowAllExposure implements McpToolExposurePolicy {
        @Override
        public boolean isExposed(String toolName) {
            return true;
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

    private static final class EmptyPrompts implements IMcpPromptProvider {
        @Override
        public List<McpPrompt> listPrompts() {
            return List.of();
        }

        @Override
        public Optional<McpPromptResult> getPrompt(
                String name, Map<String, Object> arguments) {
            return Optional.empty();
        }
    }
}
