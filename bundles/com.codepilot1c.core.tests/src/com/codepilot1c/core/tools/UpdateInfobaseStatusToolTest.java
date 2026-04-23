package com.codepilot1c.core.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Test;

import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry.JobState;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry.JobStatus;
import com.codepilot1c.core.tools.workspace.UpdateInfobaseStatusTool;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class UpdateInfobaseStatusToolTest {

    private static final String KIND = "edt_update_infobase"; //$NON-NLS-1$

    @After
    public void restoreClock() {
        BackgroundJobRegistry.getInstance().setClockForTest(Clock.systemUTC());
    }

    @Test
    public void unknownJob_returnsFailure() throws Exception {
        UpdateInfobaseStatusTool tool = new UpdateInfobaseStatusTool();
        ToolResult result = tool.execute(Map.of("job_id", "nope")).join(); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains("Unknown job")); //$NON-NLS-1$
    }

    @Test
    public void missingJobId_returnsFailure() throws Exception {
        UpdateInfobaseStatusTool tool = new UpdateInfobaseStatusTool();
        ToolResult result = tool.execute(Map.of()).join();
        assertFalse(result.isSuccess());
    }

    @Test
    public void runningJob_reportsRunningOrPendingState() throws Exception {
        BackgroundJobRegistry registry = BackgroundJobRegistry.getInstance();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        String jobId = registry.startJob(KIND, () -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("release latch timed out"); //$NON-NLS-1$
            }
            return "ok"; //$NON-NLS-1$
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            UpdateInfobaseStatusTool tool = new UpdateInfobaseStatusTool();
            ToolResult result = tool.execute(Map.of("job_id", jobId)).join(); //$NON-NLS-1$
            assertTrue(result.isSuccess());
            JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
            assertEquals(jobId, json.get("job_id").getAsString()); //$NON-NLS-1$
            assertEquals(KIND, json.get("kind").getAsString()); //$NON-NLS-1$
            String state = json.get("state").getAsString(); //$NON-NLS-1$
            assertTrue("state was " + state, //$NON-NLS-1$
                    JobState.RUNNING.name().equals(state) || JobState.PENDING.name().equals(state));
            assertFalse(json.has("result")); //$NON-NLS-1$
            assertFalse(json.has("error")); //$NON-NLS-1$
        } finally {
            release.countDown();
            awaitTerminal(registry, jobId);
        }
    }

    @Test
    public void doneJob_reportsResult() throws Exception {
        BackgroundJobRegistry registry = BackgroundJobRegistry.getInstance();
        String jobId = registry.startJob(KIND, () -> "final-result"); //$NON-NLS-1$
        awaitTerminal(registry, jobId);

        UpdateInfobaseStatusTool tool = new UpdateInfobaseStatusTool();
        ToolResult result = tool.execute(Map.of("job_id", jobId)).join(); //$NON-NLS-1$
        assertTrue(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertEquals(JobState.DONE.name(), json.get("state").getAsString()); //$NON-NLS-1$
        assertEquals("final-result", json.get("result").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotNull(json.get("startedAt")); //$NON-NLS-1$
        assertNotNull(json.get("finishedAt")); //$NON-NLS-1$
    }

    @Test
    public void failedJob_reportsError() throws Exception {
        BackgroundJobRegistry registry = BackgroundJobRegistry.getInstance();
        String jobId = registry.startJob(KIND, () -> {
            throw new IllegalStateException("oops"); //$NON-NLS-1$
        });
        awaitTerminal(registry, jobId);

        UpdateInfobaseStatusTool tool = new UpdateInfobaseStatusTool();
        ToolResult result = tool.execute(Map.of("job_id", jobId)).join(); //$NON-NLS-1$
        assertTrue(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getContent()).getAsJsonObject();
        assertEquals(JobState.FAILED.name(), json.get("state").getAsString()); //$NON-NLS-1$
        assertTrue(json.get("error").getAsString().contains("oops")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void expiredJob_returnsJobExpiredError() throws Exception {
        BackgroundJobRegistry registry = BackgroundJobRegistry.getInstance();
        Instant t0 = Instant.parse("2026-03-01T00:00:00Z"); //$NON-NLS-1$
        registry.setClockForTest(Clock.fixed(t0, ZoneOffset.UTC));

        String jobId = registry.startJob(KIND, () -> "bye"); //$NON-NLS-1$
        awaitTerminal(registry, jobId);

        // Advance past retention window and evict.
        Instant t1 = t0.plus(BackgroundJobRegistry.RESULT_RETENTION).plus(Duration.ofMinutes(1));
        registry.setClockForTest(Clock.fixed(t1, ZoneOffset.UTC));
        registry.evictExpired();

        UpdateInfobaseStatusTool tool = new UpdateInfobaseStatusTool();
        ToolResult result = tool.execute(Map.of("job_id", jobId)).join(); //$NON-NLS-1$
        assertFalse(result.isSuccess());
        JsonObject json = JsonParser.parseString(result.getErrorMessage()).getAsJsonObject();
        assertEquals("job_expired", json.get("error").getAsString()); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(json.has("expired_at")); //$NON-NLS-1$
        assertEquals(jobId, json.get("job_id").getAsString()); //$NON-NLS-1$

        // Unknown id (never issued) still returns the plain "Unknown job" error.
        ToolResult unknown = tool.execute(Map.of("job_id", "totally-bogus")).join(); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(unknown.isSuccess());
        assertNotNull(unknown.getErrorMessage());
        assertTrue(unknown.getErrorMessage().contains("Unknown job")); //$NON-NLS-1$
    }

    private static JobStatus awaitTerminal(BackgroundJobRegistry registry, String jobId)
            throws InterruptedException {
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
