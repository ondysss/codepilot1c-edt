package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.codepilot1c.core.agent.profiles.AgentCapability;
import com.codepilot1c.core.agent.profiles.PlanAgentProfile;
import com.codepilot1c.core.agent.AgentConfig;
import com.codepilot1c.core.model.ToolCall;
import com.google.gson.Gson;

import sun.misc.Unsafe;

public class ToolExecutionContextPropagationTest {

    @Test
    public void explicitContextCrossesRegistryAndExecutionService() throws Exception {
        ContextAwareTool tool = new ContextAwareTool();
        ToolRegistry registry = isolatedRegistry(Map.of(tool.getName(), tool));
        ToolExecutionContext context = new ToolExecutionContext(
                "plan", AgentCapability.READ_ONLY, 2); //$NON-NLS-1$

        ToolResult result = registry.execute(
                new ToolCall("call-1", tool.getName(), "{}"), //$NON-NLS-1$ //$NON-NLS-2$
                Map.of("value", "approved"), null, null, context).join(); //$NON-NLS-1$ //$NON-NLS-2$

        assertTrue(result.isSuccess());
        assertSame(context, tool.context.get());
        assertEquals("approved", tool.parameters.get().get("value")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void perViewProjectAndSessionIdentityCrossRegistryUnchanged() throws Exception {
        ContextAwareTool tool = new ContextAwareTool();
        ToolRegistry registry = isolatedRegistry(Map.of(tool.getName(), tool));
        ToolExecutionContext context = new ToolExecutionContext(
                "plan", AgentCapability.READ_ONLY, 0, //$NON-NLS-1$
                "/workspace/project-a", "chat-a"); //$NON-NLS-1$ //$NON-NLS-2$

        registry.execute(new ToolCall("call-1", tool.getName(), "{}"), //$NON-NLS-1$ //$NON-NLS-2$
                Map.of(), null, null, context).join();

        assertSame(context, tool.context.get());
        assertTrue(tool.context.get().hasProjectIdentity());
        assertEquals("/workspace/project-a", tool.context.get().projectPath()); //$NON-NLS-1$
        assertEquals("chat-a", tool.context.get().sessionId()); //$NON-NLS-1$
        assertEquals("/workspace/project-a", ActiveProjectSupport.resolveProjectPath(context)); //$NON-NLS-1$
    }

    @Test
    public void delegatedAgentConfigPreservesParentTurnIdentity() {
        AgentConfig parent = AgentConfig.builder()
                .executionIdentity("/workspace/project-a", "chat-a") //$NON-NLS-1$ //$NON-NLS-2$
                .build();
        AgentConfig child = AgentConfig.builder().from(parent)
                .delegationDepth(1)
                .build();

        assertEquals("/workspace/project-a", child.getProjectPath()); //$NON-NLS-1$
        assertEquals("chat-a", child.getSessionId()); //$NON-NLS-1$
    }

    @Test
    public void legacyRegistryOverloadUsesUnscopedContext() throws Exception {
        ContextAwareTool tool = new ContextAwareTool();
        ToolRegistry registry = isolatedRegistry(Map.of(tool.getName(), tool));

        registry.execute(
                new ToolCall("call-1", tool.getName(), "{}"), //$NON-NLS-1$ //$NON-NLS-2$
                Map.of(), null, null).join();

        assertSame(ToolExecutionContext.unscoped(), tool.context.get());
        assertFalse(tool.context.get().isScoped());
        assertEquals(AgentCapability.MUTATING, tool.context.get().delegationCeiling());
    }

    @Test
    public void thirdPartyLegacyToolUsesBackwardCompatibleDefaultMethod() throws Exception {
        LegacyOnlyTool tool = new LegacyOnlyTool();
        ToolRegistry registry = isolatedRegistry(Map.of(tool.getName(), tool));
        ToolExecutionContext context = new ToolExecutionContext(
                "plan", AgentCapability.READ_ONLY, 1); //$NON-NLS-1$

        ToolResult result = registry.execute(
                new ToolCall("call-1", tool.getName(), "{}"), //$NON-NLS-1$ //$NON-NLS-2$
                Map.of(), null, null, context).join();

        assertTrue(result.isSuccess());
        assertTrue(tool.executed);
    }

    @Test
    public void blankProfileIdCannotBecomeUnscopedContext() {
        PlanAgentProfile blankProfile = new PlanAgentProfile() {
            @Override
            public String getId() {
                return "  "; //$NON-NLS-1$
            }
        };

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> ToolExecutionContext.of(blankProfile, 0));

        assertEquals("parent profile id must not be blank", error.getMessage()); //$NON-NLS-1$
    }

    private static ToolRegistry isolatedRegistry(Map<String, ITool> tools) throws Exception {
        ToolRegistry registry = (ToolRegistry) unsafe().allocateInstance(ToolRegistry.class);
        setField(registry, "tools", new HashMap<>(tools)); //$NON-NLS-1$
        setField(registry, "dynamicTools", new ConcurrentHashMap<String, ITool>()); //$NON-NLS-1$
        setField(registry, "gson", new Gson()); //$NON-NLS-1$
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

    private static final class ContextAwareTool implements ITool {
        private final AtomicReference<Map<String, Object>> parameters = new AtomicReference<>();
        private final AtomicReference<ToolExecutionContext> context = new AtomicReference<>();

        @Override
        public String getName() {
            return "context_aware"; //$NON-NLS-1$
        }

        @Override
        public String getDescription() {
            return "test"; //$NON-NLS-1$
        }

        @Override
        public String getParameterSchema() {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        @Override
        public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            this.parameters.set(parameters);
            this.context.set(ToolExecutionContext.unscoped());
            return CompletableFuture.completedFuture(ToolResult.success("ok")); //$NON-NLS-1$
        }

        @Override
        public CompletableFuture<ToolResult> execute(
                Map<String, Object> parameters, ToolExecutionContext context) {
            this.parameters.set(parameters);
            this.context.set(context);
            return CompletableFuture.completedFuture(ToolResult.success("ok")); //$NON-NLS-1$
        }
    }

    private static final class LegacyOnlyTool implements ITool {
        private boolean executed;

        @Override
        public String getName() {
            return "legacy_only"; //$NON-NLS-1$
        }

        @Override
        public String getDescription() {
            return "test"; //$NON-NLS-1$
        }

        @Override
        public String getParameterSchema() {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        @Override
        public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            executed = true;
            return CompletableFuture.completedFuture(ToolResult.success("ok")); //$NON-NLS-1$
        }
    }
}
