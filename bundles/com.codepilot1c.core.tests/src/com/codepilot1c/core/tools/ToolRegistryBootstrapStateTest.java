/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
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
import java.util.function.Function;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.codepilot1c.core.tools.meta.ToolDescriptor;
import com.codepilot1c.core.tools.meta.ToolDescriptorRegistry;

/** Owner, waiter, reentry, and failure contracts for ToolRegistry startup. */
public class ToolRegistryBootstrapStateTest {

    private Field instanceField;
    private Field overrideField;
    private Field descriptorInstanceField;
    private ToolRegistry previousInstance;
    private Object previousOverride;
    private ToolDescriptorRegistry previousDescriptors;
    private Object singletonLock;
    private ExecutorService executor;

    @Before
    public void setUp() throws Exception {
        executor = Executors.newCachedThreadPool();
        instanceField = ToolRegistry.class.getDeclaredField("instance"); //$NON-NLS-1$
        instanceField.setAccessible(true);
        overrideField = ToolRegistry.class.getDeclaredField("initializationOverride"); //$NON-NLS-1$
        overrideField.setAccessible(true);
        descriptorInstanceField = ToolDescriptorRegistry.class.getDeclaredField("instance"); //$NON-NLS-1$
        descriptorInstanceField.setAccessible(true);
        Field singletonLockField = ToolRegistry.class.getDeclaredField("INSTANCE_LOCK"); //$NON-NLS-1$
        singletonLockField.setAccessible(true);
        singletonLock = singletonLockField.get(null);
        previousInstance = (ToolRegistry) instanceField.get(null);
        previousOverride = overrideField.get(null);
        previousDescriptors = (ToolDescriptorRegistry) descriptorInstanceField.get(null);
        descriptorInstanceField.set(null, ToolDescriptorRegistry.createDetached());
        instanceField.set(null, null);
        overrideField.set(null, null);
    }

    @After
    public void tearDown() throws Exception {
        overrideField.set(null, previousOverride);
        instanceField.set(null, previousInstance);
        descriptorInstanceField.set(null, previousDescriptors);
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }

    @Test
    public void metadataCallbackReentersSingletonAndAwaitsCrossThreadWork()
            throws Exception {
        AtomicReference<ToolRegistry> reentrant = new AtomicReference<>();
        AtomicReference<ToolRegistry> workerRegistry = new AtomicReference<>();
        AtomicReference<Boolean> registryMonitorHeld = new AtomicReference<>();
        AtomicReference<Boolean> classMonitorHeld = new AtomicReference<>();
        AtomicReference<Boolean> singletonMonitorHeld = new AtomicReference<>();
        AtomicReference<ToolDescriptor> structuralDescriptor = new AtomicReference<>();
        String bootstrapName = "bootstrap_reentrant_default"; //$NON-NLS-1$
        Constructor<ToolDescriptorRegistry> descriptorConstructor =
                ToolDescriptorRegistry.class.getDeclaredConstructor();
        descriptorConstructor.setAccessible(true);
        descriptorInstanceField.set(null, descriptorConstructor.newInstance());
        BootstrapTool bootstrapTool = new BootstrapTool(
                bootstrapName, () -> {
                    ToolRegistry current = ToolRegistry.getInstance();
                    reentrant.set(current);
                    registryMonitorHeld.set(Thread.holdsLock(current));
                    classMonitorHeld.set(Thread.holdsLock(ToolRegistry.class));
                    singletonMonitorHeld.set(Thread.holdsLock(singletonLock));
                    Future<ToolRegistry> registryLookup = executor.submit(
                            ToolRegistry::getInstance);
                    workerRegistry.set(await(registryLookup));
                    Future<ToolDescriptor> descriptorLookup = executor.submit(
                            () -> ToolDescriptorRegistry.getInstance()
                                    .getOrDefault(bootstrapName));
                    structuralDescriptor.set(await(descriptorLookup));
                });
        setOverride(ignored -> List.of(bootstrapTool));

        ToolRegistry initialized = ToolRegistry.getInstance();

        assertSame(initialized, reentrant.get());
        assertSame(initialized, workerRegistry.get());
        assertSame(bootstrapTool, initialized.getTool(bootstrapTool.getName()));
        assertFalse(registryMonitorHeld.get());
        assertFalse(classMonitorHeld.get());
        assertFalse(singletonMonitorHeld.get());
        assertTrue(structuralDescriptor.get().isMutating());
        assertTrue(structuralDescriptor.get().requiresValidationToken());
    }

    @Test
    public void nonOwnerGetInstanceWaitsOutsideInitializationLocks()
            throws Exception {
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        AtomicReference<Boolean> registryMonitorHeld = new AtomicReference<>();
        AtomicReference<Boolean> classMonitorHeld = new AtomicReference<>();
        AtomicReference<Boolean> singletonMonitorHeld = new AtomicReference<>();
        setOverride(registry -> {
            registryMonitorHeld.set(Thread.holdsLock(registry));
            classMonitorHeld.set(Thread.holdsLock(ToolRegistry.class));
            singletonMonitorHeld.set(Thread.holdsLock(singletonLock));
            ownerEntered.countDown();
            await(releaseOwner);
            return List.of(new SimpleTool("bootstrap_waiter_ready")); //$NON-NLS-1$
        });

        Future<ToolRegistry> owner = executor.submit(ToolRegistry::getInstance);
        assertTrue(ownerEntered.await(2, TimeUnit.SECONDS));
        CountDownLatch waiterStarted = new CountDownLatch(1);
        Future<ToolRegistry> waiter = executor.submit(() -> {
            waiterStarted.countDown();
            return ToolRegistry.getInstance();
        });
        assertTrue(waiterStarted.await(2, TimeUnit.SECONDS));
        assertFalse(waiter.isDone());

        releaseOwner.countDown();
        ToolRegistry initialized = owner.get(2, TimeUnit.SECONDS);
        assertSame(initialized, waiter.get(2, TimeUnit.SECONDS));
        assertFalse(registryMonitorHeld.get());
        assertFalse(classMonitorHeld.get());
        assertFalse(singletonMonitorHeld.get());
    }

    @Test
    public void defaultMetadataFailureLeavesConservativeInstalledTool()
            throws Exception {
        String name = "bootstrap_default_metadata_failure"; //$NON-NLS-1$
        BootstrapTool broken = new BootstrapTool(name, () -> {
            throw new IllegalStateException("default metadata sentinel"); //$NON-NLS-1$
        });
        setOverride(ignored -> List.of(broken));

        ToolRegistry initialized = ToolRegistry.getInstance();

        assertSame(broken, initialized.getTool(name));
        ToolDescriptor descriptor = ToolDescriptorRegistry.getInstance()
                .getOrDefault(name);
        assertTrue(descriptor.isMutating());
        assertTrue(descriptor.requiresValidationToken());
    }

    @Test
    public void initializationFailureWakesWaitersWithoutRetry()
            throws Exception {
        IllegalStateException sentinel =
                new IllegalStateException("tool registry bootstrap sentinel"); //$NON-NLS-1$
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        setOverride(ignored -> {
            attempts.incrementAndGet();
            ownerEntered.countDown();
            await(releaseOwner);
            throw sentinel;
        });

        Future<ToolRegistry> owner = executor.submit(ToolRegistry::getInstance);
        assertTrue(ownerEntered.await(2, TimeUnit.SECONDS));
        CountDownLatch waiterStarted = new CountDownLatch(1);
        Future<ToolRegistry> waiter = executor.submit(() -> {
            waiterStarted.countDown();
            return ToolRegistry.getInstance();
        });
        assertTrue(waiterStarted.await(2, TimeUnit.SECONDS));
        assertFalse(waiter.isDone());

        releaseOwner.countDown();
        assertSame(sentinel, failureOf(owner));
        assertSame(sentinel, failureOf(waiter));
        assertSame(sentinel, directFailure());
        assertEquals(1, attempts.get());
    }

    private void setOverride(Function<ToolRegistry, List<ITool>> override)
            throws Exception {
        overrideField.set(null, override);
    }

    private static Throwable failureOf(Future<?> future) throws Exception {
        try {
            future.get(2, TimeUnit.SECONDS);
            fail("Expected ToolRegistry bootstrap failure"); //$NON-NLS-1$
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    private static Throwable directFailure() {
        try {
            ToolRegistry.getInstance();
            fail("Expected persistent ToolRegistry bootstrap failure"); //$NON-NLS-1$
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Cross-thread registry work failed", e); //$NON-NLS-1$
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

    private static class SimpleTool implements ITool {
        private final String name;

        private SimpleTool(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return "bootstrap state test tool"; //$NON-NLS-1$
        }

        @Override
        public String getParameterSchema() {
            return "{\"type\":\"object\"}"; //$NON-NLS-1$
        }

        @Override
        public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
            return CompletableFuture.completedFuture(ToolResult.success("ok")); //$NON-NLS-1$
        }
    }

    private static final class BootstrapTool extends SimpleTool {
        private final Runnable metadataCallback;

        private BootstrapTool(String name, Runnable metadataCallback) {
            super(name);
            this.metadataCallback = metadataCallback;
        }

        @Override
        public boolean requiresValidationToken() {
            metadataCallback.run();
            return true;
        }

        @Override
        public boolean isMutating() {
            return true;
        }
    }
}
