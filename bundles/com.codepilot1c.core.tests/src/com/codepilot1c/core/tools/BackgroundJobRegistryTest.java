package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Test;

import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry.JobLookup;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry.JobLookupKind;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry.JobState;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry.JobStatus;

public class BackgroundJobRegistryTest {

    private static final String KIND = "test-job"; //$NON-NLS-1$

    @After
    public void restoreClock() {
        BackgroundJobRegistry.getInstance().setClockForTest(Clock.systemUTC());
    }

    @Test
    public void startJob_returnsIdAndEventuallyCompletes() throws Exception {
        BackgroundJobRegistry registry = BackgroundJobRegistry.getInstance();
        String jobId = registry.startJob(KIND, () -> "result-42"); //$NON-NLS-1$
        assertNotNull(jobId);
        assertFalse(jobId.isEmpty());

        JobStatus status = awaitTerminal(registry, jobId);
        assertEquals(JobState.DONE, status.getState());
        assertEquals("result-42", status.getResult()); //$NON-NLS-1$
        assertNotNull(status.getStartedAt());
        assertNotNull(status.getFinishedAt());
    }

    @Test
    public void getStatus_unknownJobReturnsEmpty() {
        Optional<JobStatus> status = BackgroundJobRegistry.getInstance()
                .getStatus("does-not-exist"); //$NON-NLS-1$
        assertTrue(status.isEmpty());
    }

    @Test
    public void startJob_concurrentJobsEachGetOwnId() throws Exception {
        BackgroundJobRegistry registry = BackgroundJobRegistry.getInstance();
        int count = 3;
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(count);

        String[] ids = new String[count];
        for (int i = 0; i < count; i++) {
            ids[i] = registry.startJob(KIND, () -> {
                started.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("release latch timeout"); //$NON-NLS-1$
                }
                return "ok"; //$NON-NLS-1$
            });
        }

        assertTrue(started.await(5, TimeUnit.SECONDS));
        for (int i = 0; i < count; i++) {
            for (int j = i + 1; j < count; j++) {
                assertNotEquals(ids[i], ids[j]);
            }
            JobState state = registry.getStatus(ids[i]).orElseThrow().getState();
            assertTrue("Expected PENDING or RUNNING, got " + state, //$NON-NLS-1$
                    state == JobState.PENDING || state == JobState.RUNNING);
        }

        List<String> listed = registry.listJobs(KIND);
        for (String id : ids) {
            assertTrue("listJobs missing " + id, listed.contains(id)); //$NON-NLS-1$
        }

        release.countDown();
        for (String id : ids) {
            JobStatus st = awaitTerminal(registry, id);
            assertEquals(JobState.DONE, st.getState());
        }
    }

    @Test
    public void failedJob_recordsError() throws Exception {
        BackgroundJobRegistry registry = BackgroundJobRegistry.getInstance();
        String jobId = registry.startJob(KIND, () -> {
            throw new IllegalStateException("boom"); //$NON-NLS-1$
        });
        JobStatus status = awaitTerminal(registry, jobId);
        assertEquals(JobState.FAILED, status.getState());
        assertNotNull(status.getError());
        assertTrue(status.getError().contains("boom")); //$NON-NLS-1$
    }

    @Test
    public void evictExpired_removesJobsPastRetention() throws Exception {
        BackgroundJobRegistry registry = BackgroundJobRegistry.getInstance();
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z"); //$NON-NLS-1$
        registry.setClockForTest(Clock.fixed(t0, ZoneOffset.UTC));
        int initial = registry.size();

        String jobId = registry.startJob(KIND, () -> "done"); //$NON-NLS-1$
        awaitTerminal(registry, jobId);
        assertTrue(registry.getStatus(jobId).isPresent());

        // Advance past retention window. Direct invoke of evictExpired keeps
        // the test deterministic (doesn't rely on the cleaner thread tick).
        Instant t1 = t0.plus(BackgroundJobRegistry.RESULT_RETENTION).plus(Duration.ofMinutes(1));
        registry.setClockForTest(Clock.fixed(t1, ZoneOffset.UTC));
        registry.evictExpired();

        assertTrue(registry.getStatus(jobId).isEmpty());
        assertEquals(initial, Math.min(registry.size(), initial));
    }

    @Test
    public void lookupJob_expiredId_returnsExpiredWithTimestamp() throws Exception {
        BackgroundJobRegistry registry = BackgroundJobRegistry.getInstance();
        Instant t0 = Instant.parse("2026-02-01T00:00:00Z"); //$NON-NLS-1$
        registry.setClockForTest(Clock.fixed(t0, ZoneOffset.UTC));

        String jobId = registry.startJob(KIND, () -> "bye"); //$NON-NLS-1$
        awaitTerminal(registry, jobId);
        assertEquals(JobLookupKind.PRESENT, registry.lookupJob(jobId).getKind());

        Instant t1 = t0.plus(BackgroundJobRegistry.RESULT_RETENTION).plus(Duration.ofMinutes(1));
        registry.setClockForTest(Clock.fixed(t1, ZoneOffset.UTC));
        registry.evictExpired();

        JobLookup lookup = registry.lookupJob(jobId);
        assertEquals(JobLookupKind.EXPIRED, lookup.getKind());
        assertNotNull(lookup.getExpiredAt());
        assertNull(lookup.getStatus());

        // An id that was never issued should still come back UNKNOWN.
        JobLookup unknown = registry.lookupJob("never-issued"); //$NON-NLS-1$
        assertEquals(JobLookupKind.UNKNOWN, unknown.getKind());

        // After the expired-tombstone retention elapses the id becomes UNKNOWN.
        Instant t2 = t1.plus(BackgroundJobRegistry.EXPIRED_RETENTION).plus(Duration.ofMinutes(1));
        registry.setClockForTest(Clock.fixed(t2, ZoneOffset.UTC));
        registry.evictExpired();
        assertEquals(JobLookupKind.UNKNOWN, registry.lookupJob(jobId).getKind());
    }

    @Test
    public void startJob_rejectedWhenExecutorSaturated() throws Exception {
        // Use an isolated instance so concurrent submissions from other tests
        // using the singleton do not leak into this test's queue accounting.
        BackgroundJobRegistry isolated = BackgroundJobRegistry.createForTest();
        int concurrency = 4; // must match MAX_CONCURRENT_JOBS
        int queueCapacity = 16; // must match QUEUE_CAPACITY
        int totalAcceptable = concurrency + queueCapacity;

        CountDownLatch release = new CountDownLatch(1);
        int accepted = 0;
        String[] acceptedIds = new String[totalAcceptable];
        try {
            for (int i = 0; i < totalAcceptable; i++) {
                acceptedIds[i] = isolated.startJob(KIND, () -> {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("release timeout"); //$NON-NLS-1$
                    }
                    return "ok"; //$NON-NLS-1$
                });
                accepted++;
            }
            // With 4 workers all blocked and the queue of capacity 16 full, the
            // next submission must be rejected synchronously.
            try {
                isolated.startJob(KIND, () -> "overflow"); //$NON-NLS-1$
                fail("expected RejectedExecutionException"); //$NON-NLS-1$
            } catch (RejectedExecutionException expected) {
                // good
            }
        } finally {
            release.countDown();
            for (int i = 0; i < accepted; i++) {
                awaitTerminal(isolated, acceptedIds[i]);
            }
            isolated.shutdown();
        }
    }

    @Test
    public void maxJobs_capEvictsOldestCompletedFirst() throws Exception {
        BackgroundJobRegistry isolated = BackgroundJobRegistry.createForTest();
        try {
            int target = BackgroundJobRegistry.MAX_JOBS + 16;
            String firstId = null;
            for (int i = 0; i < target; i++) {
                String id = isolated.startJob(KIND, () -> "x"); //$NON-NLS-1$
                if (i == 0) {
                    firstId = id;
                }
                awaitTerminal(isolated, id);
            }
            // Live-map must be bounded by MAX_JOBS.
            assertTrue("size=" + isolated.size(), //$NON-NLS-1$
                    isolated.size() <= BackgroundJobRegistry.MAX_JOBS);
            // The oldest completed id must have been evicted (now EXPIRED).
            assertEquals(JobLookupKind.EXPIRED,
                    isolated.lookupJob(firstId).getKind());
        } finally {
            isolated.shutdown();
        }
    }

    @Test
    public void shutdown_terminatesExecutorsAndClearsMaps() throws Exception {
        BackgroundJobRegistry isolated = BackgroundJobRegistry.createForTest();
        String jobId = isolated.startJob(KIND, () -> "done"); //$NON-NLS-1$
        awaitTerminal(isolated, jobId);
        assertTrue(isolated.size() > 0);

        isolated.shutdown();

        assertEquals(0, isolated.size());
        assertEquals(0, isolated.expiredSize());
        // After shutdown, further submissions must be rejected.
        try {
            isolated.startJob(KIND, () -> "nope"); //$NON-NLS-1$
            fail("expected RejectedExecutionException after shutdown"); //$NON-NLS-1$
        } catch (RejectedExecutionException expected) {
            // good
        }
        // Idempotent: calling shutdown again must not throw.
        isolated.shutdown();
    }

    private static JobStatus awaitTerminal(BackgroundJobRegistry registry, String jobId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            JobStatus status = registry.getStatus(jobId).orElse(null);
            if (status != null
                    && (status.getState() == JobState.DONE || status.getState() == JobState.FAILED)) {
                return status;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Job " + jobId + " never reached terminal state"); //$NON-NLS-1$ //$NON-NLS-2$
    }
}
