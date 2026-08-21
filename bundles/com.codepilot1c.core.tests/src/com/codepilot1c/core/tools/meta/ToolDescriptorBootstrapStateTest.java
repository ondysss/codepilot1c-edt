/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.meta;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.ToolResult;

/** Initialization-state security contracts for routing descriptors. */
public class ToolDescriptorBootstrapStateTest {

    private ExecutorService executor;

    @Before
    public void setUp() {
        executor = Executors.newCachedThreadPool();
    }

    @After
    public void tearDown() throws Exception {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }

    @Test
    public void concurrentLookupWaitsForValidatedBootstrapDescriptor()
            throws Exception {
        String name = "bootstrap_validation_required"; //$NON-NLS-1$
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        ToolDescriptorRegistry registry =
                ToolDescriptorRegistry.createForBootstrapTests(current -> {
                    ownerEntered.countDown();
                    await(releaseOwner);
                    current.register(ToolDescriptor.builder(name)
                            .category(ToolCategory.METADATA)
                            .mutating(true)
                            .requiresValidationToken(true)
                            .build());
                });

        Future<ToolDescriptor> owner = executor.submit(
                () -> registry.getOrDefault(name));
        assertTrue(ownerEntered.await(2, TimeUnit.SECONDS));

        CountDownLatch waiterStarted = new CountDownLatch(1);
        Future<ToolDescriptor> waiter = executor.submit(() -> {
            waiterStarted.countDown();
            return registry.getOrDefault(name);
        });
        assertTrue(waiterStarted.await(2, TimeUnit.SECONDS));
        assertFalse(waiter.isDone());

        releaseOwner.countDown();
        assertSecure(owner.get(2, TimeUnit.SECONDS));
        assertSecure(waiter.get(2, TimeUnit.SECONDS));
    }

    @Test
    public void sameThreadReentryReturnsConservativeMissingDescriptor() {
        String name = "bootstrap_reentrant_result"; //$NON-NLS-1$
        AtomicReference<ToolDescriptor> reentrant = new AtomicReference<>();
        ToolDescriptorRegistry registry =
                ToolDescriptorRegistry.createForBootstrapTests(current -> {
                    reentrant.set(current.getOrDefault("bootstrap_missing")); //$NON-NLS-1$
                    current.register(ToolDescriptor.builder(name)
                            .category(ToolCategory.BSL)
                            .mutating(false)
                            .requiresValidationToken(false)
                            .build());
                });

        ToolDescriptor initialized = registry.getOrDefault(name);

        assertSecure(reentrant.get());
        assertFalse(initialized.isMutating());
        assertFalse(initialized.requiresValidationToken());
    }

    @Test
    public void bootstrapFailureWakesWaitersAndRemainsDeterministic()
            throws Exception {
        IllegalStateException sentinel =
                new IllegalStateException("descriptor bootstrap sentinel"); //$NON-NLS-1$
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        ToolDescriptorRegistry registry =
                ToolDescriptorRegistry.createForBootstrapTests(ignored -> {
                    attempts.incrementAndGet();
                    ownerEntered.countDown();
                    await(releaseOwner);
                    throw sentinel;
                });

        Future<ToolDescriptor> owner = executor.submit(
                () -> registry.getOrDefault("bootstrap_failure")); //$NON-NLS-1$
        assertTrue(ownerEntered.await(2, TimeUnit.SECONDS));
        CountDownLatch waiterStarted = new CountDownLatch(1);
        Future<ToolDescriptor> waiter = executor.submit(() -> {
            waiterStarted.countDown();
            return registry.getOrDefault("bootstrap_failure"); //$NON-NLS-1$
        });
        assertTrue(waiterStarted.await(2, TimeUnit.SECONDS));
        assertFalse(waiter.isDone());

        releaseOwner.countDown();
        assertSame(sentinel, failureOf(owner));
        assertSame(sentinel, failureOf(waiter));
        assertSame(sentinel, directFailure(registry));
        assertEquals(1, attempts.get());
        assertRegistryRejectsFailedDescriptors(registry, sentinel);
    }

    @Test
    public void registryInitializerCanJoinDescriptorBootstrapWithoutCycle()
            throws Exception {
        Field registryInstance = ToolRegistry.class.getDeclaredField("instance"); //$NON-NLS-1$
        Field registryOverride = ToolRegistry.class.getDeclaredField(
                "initializationOverride"); //$NON-NLS-1$
        Field descriptorInstance = ToolDescriptorRegistry.class.getDeclaredField(
                "instance"); //$NON-NLS-1$
        registryInstance.setAccessible(true);
        registryOverride.setAccessible(true);
        descriptorInstance.setAccessible(true);
        Object previousRegistry = registryInstance.get(null);
        Object previousOverride = registryOverride.get(null);
        Object previousDescriptors = descriptorInstance.get(null);
        CountDownLatch descriptorOwnerEntered = new CountDownLatch(1);
        CountDownLatch releaseDescriptorOwner = new CountDownLatch(1);
        AtomicReference<ToolDescriptor> callbackDescriptor = new AtomicReference<>();
        ToolDescriptorRegistry descriptors =
                ToolDescriptorRegistry.createForBootstrapTests(current -> {
                    descriptorOwnerEntered.countDown();
                    await(releaseDescriptorOwner);
                    ToolRegistry.getInstance().refreshToolDescriptors();
                });
        Consumer<ToolRegistry> override = registry -> registry.register(
                new ReentrantDescriptorTool(descriptors, callbackDescriptor));
        try {
            descriptorInstance.set(null, descriptors);
            registryInstance.set(null, null);
            registryOverride.set(null, override);

            Future<ToolDescriptor> descriptorOwner = executor.submit(
                    () -> descriptors.getOrDefault("bootstrap_cycle_probe")); //$NON-NLS-1$
            assertTrue(descriptorOwnerEntered.await(2, TimeUnit.SECONDS));
            Future<ToolRegistry> registryOwner = executor.submit(
                    ToolRegistry::getInstance);

            ToolRegistry initialized = registryOwner.get(2, TimeUnit.SECONDS);
            assertTrue(initialized.getTool("bootstrap_descriptor_reentrant") //$NON-NLS-1$
                    instanceof ReentrantDescriptorTool);
            assertSecure(callbackDescriptor.get());

            releaseDescriptorOwner.countDown();
            descriptorOwner.get(2, TimeUnit.SECONDS);
        } finally {
            releaseDescriptorOwner.countDown();
            registryOverride.set(null, previousOverride);
            registryInstance.set(null, previousRegistry);
            descriptorInstance.set(null, previousDescriptors);
        }
    }

    private static void assertSecure(ToolDescriptor descriptor) {
        assertTrue(descriptor.isMutating());
        assertTrue(descriptor.requiresValidationToken());
    }

    private static Throwable failureOf(Future<?> future) throws Exception {
        try {
            future.get(2, TimeUnit.SECONDS);
            fail("Expected descriptor bootstrap failure"); //$NON-NLS-1$
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    private static Throwable directFailure(ToolDescriptorRegistry registry) {
        try {
            registry.getOrDefault("bootstrap_failure"); //$NON-NLS-1$
            fail("Expected persistent descriptor bootstrap failure"); //$NON-NLS-1$
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private void assertRegistryRejectsFailedDescriptors(
            ToolDescriptorRegistry descriptors,
            Throwable expected) throws Exception {
        Field registryInstance = ToolRegistry.class.getDeclaredField("instance"); //$NON-NLS-1$
        Field descriptorInstance = ToolDescriptorRegistry.class.getDeclaredField(
                "instance"); //$NON-NLS-1$
        registryInstance.setAccessible(true);
        descriptorInstance.setAccessible(true);
        Object previousRegistry = registryInstance.get(null);
        Object previousDescriptors = descriptorInstance.get(null);
        try {
            descriptorInstance.set(null, descriptors);
            registryInstance.set(null, null);
            Future<ToolRegistry> owner = executor.submit(ToolRegistry::getInstance);
            Future<ToolRegistry> waiter = executor.submit(ToolRegistry::getInstance);
            assertSame(expected, failureOf(owner));
            assertSame(expected, failureOf(waiter));
        } finally {
            registryInstance.set(null, previousRegistry);
            descriptorInstance.set(null, previousDescriptors);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test barrier"); //$NON-NLS-1$
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted at test barrier", e); //$NON-NLS-1$
        }
    }

    private static final class ReentrantDescriptorTool implements ITool {
        private final ToolDescriptorRegistry descriptors;
        private final AtomicReference<ToolDescriptor> observed;

        private ReentrantDescriptorTool(
                ToolDescriptorRegistry descriptors,
                AtomicReference<ToolDescriptor> observed) {
            this.descriptors = descriptors;
            this.observed = observed;
        }

        @Override
        public String getName() {
            return "bootstrap_descriptor_reentrant"; //$NON-NLS-1$
        }

        @Override
        public String getDescription() {
            return "descriptor bootstrap cycle probe"; //$NON-NLS-1$
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
        public boolean isMutating() {
            return true;
        }

        @Override
        public boolean requiresValidationToken() {
            observed.set(descriptors.getOrDefault("bootstrap_cycle_missing")); //$NON-NLS-1$
            return true;
        }
    }
}
