/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Test;

import com.codepilot1c.core.agent.profiles.DynamicToolCapability;
import com.codepilot1c.core.model.ToolCall;
import com.codepilot1c.core.tools.ToolRegistry.ToolResolution;
import com.google.gson.Gson;

import sun.misc.Unsafe;

/** Concurrency contract for exact registry-slot dispatch claims. */
public class ToolRegistrySlotClaimTest {

    private ToolRegistry registry;

    @Before
    public void setUp() throws Exception {
        registry = isolatedRegistry();
    }

    @Test
    public void replacementBeforeClaimInvalidatesResolution() {
        CapturingTool trusted = new CapturingTool("mcp_slot_before"); //$NON-NLS-1$
        CapturingTool replacement = new CapturingTool("mcp_slot_before"); //$NON-NLS-1$
        registry.registerDynamicTool(trusted, DynamicToolCapability.READ_ONLY);
        ToolResolution authorized = registry.resolveTool(trusted.getName());

        registry.registerDynamicTool(replacement, DynamicToolCapability.MUTATING);

        assertTrue(registry.dispatchIfCurrent(authorized).isEmpty());
        assertEquals(0, trusted.calls);
        assertEquals(0, replacement.calls);
    }

    @Test
    public void replacementAfterClaimExecutesExactClaimedTool() throws Exception {
        CapturingTool trusted = new CapturingTool("mcp_slot_after"); //$NON-NLS-1$
        CapturingTool replacement = new CapturingTool("mcp_slot_after"); //$NON-NLS-1$
        registry.registerDynamicTool(trusted, DynamicToolCapability.READ_ONLY);
        ToolResolution authorized = registry.resolveTool(trusted.getName());
        ToolResolution claimed = registry.dispatchIfCurrent(authorized).orElseThrow();

        registry.registerDynamicTool(replacement, DynamicToolCapability.MUTATING);
        ToolResult result = claimed.tool().execute(Map.of()).get(1, TimeUnit.SECONDS);

        assertTrue(result.isSuccess());
        assertEquals(1, trusted.calls);
        assertEquals(0, replacement.calls);
        assertSame(replacement, registry.getTool(trusted.getName()));
    }

    @Test
    public void removeAndReaddSameImplementationCannotRevalidateOldSlot() {
        CapturingTool tool = new CapturingTool("mcp_slot_aba"); //$NON-NLS-1$
        registry.registerDynamicTool(tool, DynamicToolCapability.READ_ONLY);
        ToolResolution original = registry.resolveTool(tool.getName());

        registry.unregisterDynamicTool(tool.getName());
        registry.registerDynamicTool(tool, DynamicToolCapability.READ_ONLY);

        assertTrue(registry.dispatchIfCurrent(original).isEmpty());
        assertTrue(registry.dispatchIfCurrent(
                registry.resolveTool(tool.getName())).isPresent());
    }

    @Test
    public void executeCanWaitForUnrelatedRegistrationWithoutDeadlock()
            throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CapturingTool unrelated = new CapturingTool("mcp_slot_unrelated"); //$NON-NLS-1$
            WaitingRegistrationTool waiting = new WaitingRegistrationTool(
                    registry, executor, unrelated);
            registry.registerDynamicTool(waiting, DynamicToolCapability.READ_ONLY);
            ToolResolution authorized = registry.resolveTool(waiting.getName());

            Optional<CompletableFuture<ToolResult>> dispatched =
                    registry.getExecutionService().executeIfCurrent(
                            call(waiting.getName()), Map.of(), null, null,
                            ToolExecutionContext.unscoped(), authorized);
            ToolResult result = dispatched.orElseThrow().get(2, TimeUnit.SECONDS);

            assertTrue(result.isSuccess());
            assertFalse(waiting.registryMonitorHeld);
            assertSame(unrelated, registry.getTool(unrelated.getName()));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void executeCanAccessRegistryReentrantlyOutsideMonitor() throws Exception {
        RegistryAccessTool tool = new RegistryAccessTool(registry);
        registry.registerDynamicTool(tool, DynamicToolCapability.READ_ONLY);
        ToolResolution authorized = registry.resolveTool(tool.getName());

        ToolResult result = registry.getExecutionService().executeIfCurrent(
                call(tool.getName()), Map.of(), null, null,
                ToolExecutionContext.unscoped(), authorized)
                .orElseThrow().get(1, TimeUnit.SECONDS);

        assertTrue(result.isSuccess());
        assertFalse(tool.registryMonitorHeld);
        assertTrue(registry.getDynamicToolNames().contains(tool.registeredName));
    }

    @Test
    public void removedDynamicNamesRetainNoSlotIdentityTombstones() throws Exception {
        for (int i = 0; i < 512; i++) {
            String name = "mcp_slot_churn_" + i; //$NON-NLS-1$
            registry.registerDynamicTool(
                    new CapturingTool(name), DynamicToolCapability.READ_ONLY);
            registry.unregisterDynamicTool(name);
        }

        assertTrue(registry.getAllToolResolutions().isEmpty());
        assertTrue(registry.getDynamicToolNames().isEmpty());
        assertTrue(effectiveSlots(registry).isEmpty());
    }

    private static ToolCall call(String name) {
        return new ToolCall("slot-claim", name, null); //$NON-NLS-1$
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> effectiveSlots(ToolRegistry registry)
            throws Exception {
        Field field = ToolRegistry.class.getDeclaredField("effectiveToolSlots"); //$NON-NLS-1$
        field.setAccessible(true);
        return (Map<String, Object>) field.get(registry);
    }

    private static ToolRegistry isolatedRegistry() throws Exception {
        ToolRegistry registry = (ToolRegistry) unsafe().allocateInstance(ToolRegistry.class);
        setField(registry, "tools", new HashMap<String, ITool>()); //$NON-NLS-1$
        setField(registry, "dynamicTools", new ConcurrentHashMap<String, ITool>()); //$NON-NLS-1$
        setField(registry, "dynamicToolCapabilities", //$NON-NLS-1$
                new ConcurrentHashMap<String, DynamicToolCapability>());
        setField(registry, "gson", new Gson()); //$NON-NLS-1$
        return registry;
    }

    private static void setField(Object target, String name, Object value)
            throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe"); //$NON-NLS-1$
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static class CapturingTool implements ITool {

        private final String name;
        private int calls;

        private CapturingTool(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "slot claim test tool"; //$NON-NLS-1$
        }

        @Override
        public String getParameterSchema() {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        @Override
        public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            calls++;
            return CompletableFuture.completedFuture(ToolResult.success("ok")); //$NON-NLS-1$
        }
    }

    private static final class WaitingRegistrationTool extends CapturingTool {

        private final ToolRegistry registry;
        private final ExecutorService executor;
        private final ITool unrelated;
        private boolean registryMonitorHeld;

        private WaitingRegistrationTool(
                ToolRegistry registry, ExecutorService executor, ITool unrelated) {
            super("mcp_slot_waiting"); //$NON-NLS-1$
            this.registry = registry;
            this.executor = executor;
            this.unrelated = unrelated;
        }

        @Override
        public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            registryMonitorHeld = Thread.holdsLock(registry);
            Future<?> registration = executor.submit(() -> registry.registerDynamicTool(
                    unrelated, DynamicToolCapability.MUTATING));
            try {
                registration.get(1, TimeUnit.SECONDS);
                return CompletableFuture.completedFuture(ToolResult.success("ok")); //$NON-NLS-1$
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        }
    }

    private static final class RegistryAccessTool extends CapturingTool {

        private final ToolRegistry registry;
        private final String registeredName = "mcp_slot_reentrant_registered"; //$NON-NLS-1$
        private boolean registryMonitorHeld;

        private RegistryAccessTool(ToolRegistry registry) {
            super("mcp_slot_reentrant"); //$NON-NLS-1$
            this.registry = registry;
        }

        @Override
        public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            registryMonitorHeld = Thread.holdsLock(registry);
            registry.resolveTool(getName());
            registry.registerDynamicTool(
                    new CapturingTool(registeredName), DynamicToolCapability.READ_ONLY);
            return CompletableFuture.completedFuture(ToolResult.success("ok")); //$NON-NLS-1$
        }
    }
}
