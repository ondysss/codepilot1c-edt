/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.meta;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.codepilot1c.core.agent.profiles.DynamicToolCapability;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.ToolRegistry.ToolResolution;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.Gson;

import sun.misc.Unsafe;

/** Deterministic races between effective slots and routing metadata. */
public class ToolDescriptorPublicationRaceTest {

    private ToolRegistry registry;
    private ToolDescriptorRegistry descriptors;
    private ExecutorService executor;

    @Before
    public void setUp() throws Exception {
        descriptors = ToolDescriptorRegistry.createDetached();
        registry = isolatedToolRegistry(descriptors);
        executor = Executors.newCachedThreadPool();
    }

    @After
    public void tearDown() throws Exception {
        if (executor != null) {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void reorderedBuiltInRegistrationPublishesMatchingDescriptor()
            throws Exception {
        MetadataBarrier barrier = new MetadataBarrier();
        MetadataTool delayed = tool(
                "descriptor_builtin_reordered", false, true, "delayed", barrier); //$NON-NLS-1$ //$NON-NLS-2$
        MetadataTool replacement = tool(
                delayed.getName(), true, false, "replacement", null); //$NON-NLS-1$

        Future<?> delayedRegistration = executor.submit(() -> registry.register(delayed));
        barrier.awaitEntered();
        registry.register(replacement);
        barrier.release();
        delayedRegistration.get(2, TimeUnit.SECONDS);

        assertPublished(delayed);
        assertFalse(delayed.registryMonitorHeldDuringMetadata);
    }

    @Test
    public void reorderedDynamicReplacementPublishesMatchingDescriptor()
            throws Exception {
        MetadataBarrier barrier = new MetadataBarrier();
        MetadataTool delayed = tool(
                "mcp_descriptor_dynamic_reordered", true, true, "delayed", barrier); //$NON-NLS-1$ //$NON-NLS-2$
        MetadataTool replacement = tool(
                delayed.getName(), false, false, "replacement", null); //$NON-NLS-1$

        Future<?> delayedRegistration = executor.submit(() -> registry.registerDynamicTool(
                delayed, DynamicToolCapability.MUTATING));
        barrier.awaitEntered();
        registry.registerDynamicTool(replacement, DynamicToolCapability.READ_ONLY);
        barrier.release();
        delayedRegistration.get(2, TimeUnit.SECONDS);

        assertPublished(delayed);
        assertEquals(DynamicToolCapability.MUTATING,
                registry.resolveTool(delayed.getName()).dynamicCapability());
        assertFalse(delayed.registryMonitorHeldDuringMetadata);
    }

    @Test
    public void staleRefreshCannotOverwriteReplacementDescriptor()
            throws Exception {
        String name = "descriptor_stale_refresh"; //$NON-NLS-1$
        MetadataTool original = tool(name, false, false, "original", null); //$NON-NLS-1$
        registry.register(original);

        MetadataBarrier barrier = new MetadataBarrier();
        original.pauseOn(barrier);
        Future<?> refresh = executor.submit(registry::refreshToolDescriptors);
        barrier.awaitEntered();

        MetadataTool replacement = tool(name, true, true, "replacement", null); //$NON-NLS-1$
        registry.register(replacement);
        barrier.release();
        refresh.get(2, TimeUnit.SECONDS);

        assertPublished(replacement);
        assertFalse(original.registryMonitorHeldDuringMetadata);
    }

    @Test
    public void unregisterDuringPendingReregistrationKeepsNewDescriptor()
            throws Exception {
        String name = "mcp_descriptor_unregister_reregister"; //$NON-NLS-1$
        MetadataTool original = tool(name, true, false, "original", null); //$NON-NLS-1$
        registry.registerDynamicTool(original, DynamicToolCapability.MUTATING);
        ToolResolution stale = registry.resolveTool(name);
        ToolDescriptor staleDescriptor = descriptors.describeTool(original);

        MetadataBarrier barrier = new MetadataBarrier();
        MetadataTool replacement = tool(name, false, true, "replacement", barrier); //$NON-NLS-1$
        Future<?> reregister = executor.submit(() -> registry.registerDynamicTool(
                replacement, DynamicToolCapability.READ_ONLY));
        barrier.awaitEntered();
        registry.unregisterDynamicTool(name);
        assertNull(registry.getTool(name));
        assertNull(descriptors.get(name));
        assertFalse(descriptors.publishSlot(
                stale.slotIdentity(), stale.slotIdentity(), staleDescriptor));
        assertNull(descriptors.get(name));

        barrier.release();
        reregister.get(2, TimeUnit.SECONDS);

        assertPublished(replacement);
    }

    @Test
    public void staleRemovalCannotDeleteReplacementDescriptor() {
        String name = "mcp_descriptor_stale_removal"; //$NON-NLS-1$
        MetadataTool original = tool(name, false, false, "original", null); //$NON-NLS-1$
        MetadataTool replacement = tool(name, true, true, "replacement", null); //$NON-NLS-1$
        registry.registerDynamicTool(original, DynamicToolCapability.READ_ONLY);
        ToolResolution stale = registry.resolveTool(name);
        ToolDescriptor staleDescriptor = descriptors.describeTool(original);

        registry.registerDynamicTool(replacement, DynamicToolCapability.MUTATING);
        assertFalse(descriptors.publishSlot(
                stale.slotIdentity(), stale.slotIdentity(), staleDescriptor));
        descriptors.removeSlot(name, stale.slotIdentity());
        descriptors.unregister(name);

        assertPublished(replacement);
    }

    @Test
    public void hiddenDynamicCollisionCannotReplaceBuiltInDescriptor() {
        String name = "descriptor_hidden_collision"; //$NON-NLS-1$
        MetadataTool builtIn = tool(name, false, true, "builtin", null); //$NON-NLS-1$
        MetadataTool hidden = tool(name, true, false, "dynamic", null); //$NON-NLS-1$
        registry.register(builtIn);
        ToolResolution before = registry.resolveTool(name);

        registry.registerDynamicTool(hidden, DynamicToolCapability.MUTATING);
        assertPublished(builtIn);
        assertSame(before.slotIdentity(), registry.resolveTool(name).slotIdentity());

        registry.unregisterDynamicTool(name);
        assertPublished(builtIn);
        assertSame(before.slotIdentity(), registry.resolveTool(name).slotIdentity());
    }

    @Test
    public void removedDynamicNamesLeaveNoDescriptorTombstones() {
        for (int i = 0; i < 256; i++) {
            String name = "mcp_descriptor_churn_" + i; //$NON-NLS-1$
            registry.registerDynamicTool(
                    tool(name, false, false, "churn", null), //$NON-NLS-1$
                    DynamicToolCapability.READ_ONLY);
            registry.unregisterDynamicTool(name);
        }

        assertTrue(descriptors.getAll().isEmpty());
    }

    @Test
    public void metadataCallbacksCanAccessDescriptorRegistryReentrantly() {
        String sideName = "descriptor_reentrant_side"; //$NON-NLS-1$
        MetadataTool tool = new MetadataTool(
                registry,
                "descriptor_reentrant", //$NON-NLS-1$
                true,
                true,
                "reentrant", //$NON-NLS-1$
                null) {
            @Override
            public boolean requiresValidationToken() {
                descriptors.register(ToolDescriptor.builder(sideName)
                        .category(ToolCategory.OTHER)
                        .build());
                descriptors.getAll();
                return super.requiresValidationToken();
            }
        };

        registry.registerDynamicTool(tool, DynamicToolCapability.MUTATING);

        assertPublished(tool);
        assertEquals(ToolCategory.OTHER,
                descriptors.get(sideName).getCategory());
    }

    private void assertPublished(MetadataTool expected) {
        ToolResolution resolution = registry.resolveTool(expected.getName());
        ToolDescriptor descriptor = descriptors.get(expected.getName());

        assertSame(expected, resolution.tool());
        assertTrue(descriptors.belongsToSlot(
                expected.getName(), resolution.slotIdentity()));
        assertEquals(expected.mutating, descriptor.isMutating());
        assertEquals(expected.validation, descriptor.requiresValidationToken());
        assertEquals(Set.of(expected.tag), descriptor.getTags());
    }

    private MetadataTool tool(
            String name,
            boolean mutating,
            boolean validation,
            String tag,
            MetadataBarrier barrier) {
        return new MetadataTool(
                registry, name, mutating, validation, tag, barrier);
    }

    private static ToolRegistry isolatedToolRegistry(
            ToolDescriptorRegistry descriptors) throws Exception {
        ToolRegistry registry = (ToolRegistry) unsafe().allocateInstance(ToolRegistry.class);
        setField(registry, "tools", new HashMap<String, ITool>()); //$NON-NLS-1$
        setField(registry, "dynamicTools", new ConcurrentHashMap<String, ITool>()); //$NON-NLS-1$
        setField(registry, "dynamicToolCapabilities", //$NON-NLS-1$
                new ConcurrentHashMap<String, DynamicToolCapability>());
        setField(registry, "gson", new Gson()); //$NON-NLS-1$
        setField(registry, "descriptorRegistry", descriptors); //$NON-NLS-1$
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

    private static final class MetadataBarrier {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        private void awaitEntered() throws InterruptedException {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
        }

        private void pause() {
            entered.countDown();
            try {
                if (!released.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting for metadata release"); //$NON-NLS-1$
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while computing metadata", e); //$NON-NLS-1$
            }
        }

        private void release() {
            released.countDown();
        }
    }

    private static class MetadataTool implements ITool {
        private final ToolRegistry registry;
        private final String name;
        private final boolean mutating;
        private final boolean validation;
        private final String tag;
        private volatile MetadataBarrier barrier;
        private volatile boolean registryMonitorHeldDuringMetadata;

        private MetadataTool(
                ToolRegistry registry,
                String name,
                boolean mutating,
                boolean validation,
                String tag,
                MetadataBarrier barrier) {
            this.registry = registry;
            this.name = name;
            this.mutating = mutating;
            this.validation = validation;
            this.tag = tag;
            this.barrier = barrier;
        }

        private void pauseOn(MetadataBarrier newBarrier) {
            barrier = newBarrier;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "descriptor race test tool"; //$NON-NLS-1$
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
        public String getCategory() {
            return mutating ? "metadata" : "bsl"; //$NON-NLS-1$ //$NON-NLS-2$
        }

        @Override
        public boolean isMutating() {
            return mutating;
        }

        @Override
        public boolean requiresValidationToken() {
            registryMonitorHeldDuringMetadata = Thread.holdsLock(registry);
            if (barrier != null) {
                barrier.pause();
            }
            return validation;
        }

        @Override
        public Set<String> getTags() {
            return Set.of(tag);
        }
    }
}
