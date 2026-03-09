package com.codepilot1c.core.edt.runtime.tests;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.codepilot1c.core.edt.runtime.EdtLaunchProcessRegistry;

public class EdtLaunchProcessRegistryTest {

    @Test
    public void removesProcessAfterExit() throws Exception {
        EdtLaunchProcessRegistry registry = EdtLaunchProcessRegistry.getInstance();
        registry.cleanupAll();
        StubProcess process = new StubProcess();

        registry.register("op-1", process); //$NON-NLS-1$
        assertSame(process, registry.get("op-1")); //$NON-NLS-1$

        process.finish(0);
        for (int i = 0; i < 20 && registry.get("op-1") != null; i++) { //$NON-NLS-1$
            Thread.sleep(25L);
        }

        assertNull(registry.get("op-1")); //$NON-NLS-1$
    }

    @Test
    public void cleanupDestroysAliveProcesses() {
        EdtLaunchProcessRegistry registry = EdtLaunchProcessRegistry.getInstance();
        registry.cleanupAll();
        StubProcess process = new StubProcess();
        registry.register("op-2", process); //$NON-NLS-1$

        registry.cleanupAll();

        assertTrue(process.destroyed);
        assertNull(registry.get("op-2")); //$NON-NLS-1$
    }

    private static final class StubProcess extends Process {
        private final CountDownLatch finished = new CountDownLatch(1);
        private final InputStream inputStream = new ByteArrayInputStream(new byte[0]);
        private final InputStream errorStream = new ByteArrayInputStream(new byte[0]);
        private final OutputStream outputStream = new ByteArrayOutputStream();
        private volatile boolean alive = true;
        private volatile boolean destroyed;
        private volatile int exitCode;

        void finish(int exitCode) {
            this.exitCode = exitCode;
            this.alive = false;
            finished.countDown();
        }

        @Override
        public OutputStream getOutputStream() {
            return outputStream;
        }

        @Override
        public InputStream getInputStream() {
            return inputStream;
        }

        @Override
        public InputStream getErrorStream() {
            return errorStream;
        }

        @Override
        public int waitFor() throws InterruptedException {
            finished.await();
            return exitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return finished.await(timeout, unit);
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException();
            }
            return exitCode;
        }

        @Override
        public void destroy() {
            finish(-1);
        }

        @Override
        public Process destroyForcibly() {
            destroyed = true;
            finish(-9);
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }
    }
}
