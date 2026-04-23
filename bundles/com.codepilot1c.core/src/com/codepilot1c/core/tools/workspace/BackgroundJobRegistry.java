/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.workspace;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.codepilot1c.core.logging.VibeLogger;

/**
 * Thread-safe registry for long-running background tool jobs.
 *
 * <p>Used by tools that cannot fit inside the LLM-facing timeout budget
 * (for example a full EDT infobase update). The caller submits work,
 * receives a job id immediately, and polls {@link #lookupJob(String)}
 * until the job finishes.</p>
 *
 * <p>Concurrency policy:</p>
 * <ul>
 *   <li>Up to 4 jobs run in parallel on daemon threads named
 *       {@code codepilot1c-bg-job-N}.</li>
 *   <li>Submission queue is bounded (16). When saturated the executor
 *       rejects new work via {@link RejectedExecutionException}; callers
 *       should surface a structured error rather than blocking.</li>
 *   <li>A hard cap of {@value #MAX_JOBS} tracked jobs guards against
 *       unbounded map growth between sweeps; when the cap is exceeded the
 *       oldest completed job is evicted first.</li>
 *   <li>Finished jobs are retained for 60 minutes so pollers can still
 *       read the result. After eviction a lightweight tombstone lives for
 *       an additional 60 minutes so pollers can tell expired apart from
 *       unknown.</li>
 * </ul>
 */
public final class BackgroundJobRegistry {

    /** How long finished jobs are kept in the registry before eviction. */
    public static final Duration RESULT_RETENTION = Duration.ofMinutes(60);

    /** Extra time the expired-tombstone is kept after eviction. */
    public static final Duration EXPIRED_RETENTION = Duration.ofMinutes(60);

    /** Hard cap on tracked (live) jobs to prevent unbounded growth. */
    public static final int MAX_JOBS = 256;

    private static final int MAX_CONCURRENT_JOBS = 4;
    private static final int QUEUE_CAPACITY = 16;
    private static final Duration CLEANUP_INTERVAL = Duration.ofMinutes(5);
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);

    private static final VibeLogger.CategoryLogger LOG =
            VibeLogger.forClass(BackgroundJobRegistry.class);

    private static final BackgroundJobRegistry INSTANCE = new BackgroundJobRegistry();

    /**
     * Returns the process-wide singleton registry.
     *
     * @return the shared registry instance
     */
    public static BackgroundJobRegistry getInstance() {
        return INSTANCE;
    }

    private final ConcurrentMap<String, JobRecord> jobs = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Instant> expiredJobs = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor;
    private final ScheduledExecutorService cleaner;
    private volatile Clock clock = Clock.systemUTC();

    private BackgroundJobRegistry() {
        ThreadFactory workerFactory = new NamedDaemonThreadFactory("codepilot1c-bg-job"); //$NON-NLS-1$
        this.executor = new ThreadPoolExecutor(
                MAX_CONCURRENT_JOBS,
                MAX_CONCURRENT_JOBS,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                workerFactory,
                new ThreadPoolExecutor.AbortPolicy());

        ThreadFactory cleanerFactory = new NamedDaemonThreadFactory("codepilot1c-bg-job-cleaner"); //$NON-NLS-1$
        this.cleaner = Executors.newSingleThreadScheduledExecutor(cleanerFactory);
        this.cleaner.scheduleWithFixedDelay(this::evictExpired,
                CLEANUP_INTERVAL.toMillis(),
                CLEANUP_INTERVAL.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /**
     * Creates a dedicated non-singleton registry instance for tests that need
     * to exercise {@link #shutdown()} without disturbing the process-wide
     * singleton. Production code MUST NOT use this factory.
     *
     * @return a fresh registry instance owning its own executors
     */
    public static BackgroundJobRegistry createForTest() {
        return new BackgroundJobRegistry();
    }

    /**
     * Overrides the clock used for timestamps and retention checks. Intended
     * for tests only; callers must not rely on this in production code.
     *
     * @param clock non-null clock to use for {@code now}
     */
    public void setClockForTest(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock"); //$NON-NLS-1$
    }

    /**
     * Submits background work and returns a job id immediately.
     *
     * <p>The returned id can be used to poll {@link #lookupJob(String)}.
     * If the executor queue is saturated this method throws
     * {@link RejectedExecutionException}; the caller must surface a
     * structured error.</p>
     *
     * @param kind short tag for the job kind, used by {@link #listJobs(String)}
     * @param work callable that produces the job's string result
     * @return the generated job id
     * @throws RejectedExecutionException if the executor queue is saturated
     *         or the registry has been shut down
     */
    public String startJob(String kind, Callable<String> work) {
        Objects.requireNonNull(kind, "kind"); //$NON-NLS-1$
        Objects.requireNonNull(work, "work"); //$NON-NLS-1$
        // Keep the live-jobs map bounded: evict expired entries, then if we
        // still exceed the hard cap drop the oldest finished job first.
        evictExpired();
        enforceMaxJobs();

        String jobId = UUID.randomUUID().toString();
        JobRecord record = new JobRecord(jobId, kind, clock.instant());
        jobs.put(jobId, record);
        LOG.info("Submitting background job %s (kind=%s)", jobId, kind); //$NON-NLS-1$
        try {
            executor.execute(() -> runJob(record, work));
        } catch (RejectedExecutionException e) {
            // Queue saturated or executor shutting down: remove the record
            // and propagate so callers can return a structured error.
            jobs.remove(jobId);
            LOG.warn("Executor rejected background job (kind=%s): %s", //$NON-NLS-1$
                    kind, e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            record.markFailed("Executor rejected job: " + e.getMessage(), clock.instant()); //$NON-NLS-1$
            throw e;
        }
        return jobId;
    }

    /**
     * Returns the current status for the given job id, or {@link Optional#empty()}
     * if the id is unknown (either never existed, was evicted after
     * retention, or is an expired tombstone).
     *
     * <p>Prefer {@link #lookupJob(String)} when the caller needs to
     * distinguish {@code EXPIRED} from {@code UNKNOWN}.</p>
     *
     * @param jobId job id returned from {@link #startJob(String, Callable)}
     * @return optional job status snapshot
     */
    public Optional<JobStatus> getStatus(String jobId) {
        if (jobId == null) {
            return Optional.empty();
        }
        JobRecord record = jobs.get(jobId);
        return record == null ? Optional.empty() : Optional.of(record.snapshot());
    }

    /**
     * Returns a tri-state lookup that distinguishes present, expired, and
     * unknown job ids. {@code EXPIRED} carries the original finish time so
     * callers can report a deterministic message.
     *
     * @param jobId job id returned from {@link #startJob(String, Callable)}
     * @return the lookup outcome; never {@code null}
     */
    public JobLookup lookupJob(String jobId) {
        if (jobId == null || jobId.isEmpty()) {
            return JobLookup.unknown();
        }
        JobRecord record = jobs.get(jobId);
        if (record != null) {
            return JobLookup.present(record.snapshot());
        }
        Instant expiredAt = expiredJobs.get(jobId);
        if (expiredAt != null) {
            return JobLookup.expired(expiredAt);
        }
        return JobLookup.unknown();
    }

    /**
     * Returns the number of jobs currently tracked (running or retained).
     *
     * @return the number of tracked jobs
     */
    public int size() {
        return jobs.size();
    }

    /**
     * Returns the number of tombstoned expired ids currently tracked.
     * Intended for tests and diagnostics.
     *
     * @return count of expired tombstones
     */
    public int expiredSize() {
        return expiredJobs.size();
    }

    /**
     * Lists job ids whose kind matches the given argument. Intended for tests
     * and diagnostics.
     *
     * @param kind the kind to filter by; {@code null} returns every job id
     * @return snapshot list of matching job ids
     */
    public List<String> listJobs(String kind) {
        List<String> out = new ArrayList<>();
        for (JobRecord record : jobs.values()) {
            if (kind == null || kind.equals(record.kind)) {
                out.add(record.jobId);
            }
        }
        return out;
    }

    /**
     * Removes jobs whose retention window has elapsed. Exposed so tests can
     * advance the clock (via {@link #setClockForTest(Clock)}) and invoke
     * eviction deterministically instead of waiting on the scheduled sweeper.
     */
    public void evictExpired() {
        Instant now = clock.instant();
        // Move expired live records to tombstone map. The tombstone timestamp
        // is the moment of eviction (the "expired_at" surfaced to callers), so
        // tombstones live for exactly EXPIRED_RETENTION from now.
        for (JobRecord record : new ArrayList<>(jobs.values())) {
            if (record.isExpired(now, RESULT_RETENTION)) {
                if (jobs.remove(record.jobId, record)) {
                    expiredJobs.put(record.jobId, now);
                }
            }
        }
        // Drop tombstones past EXPIRED_RETENTION (relative to eviction time).
        Instant tombstoneCutoff = now.minus(EXPIRED_RETENTION);
        expiredJobs.entrySet().removeIf(e -> e.getValue().isBefore(tombstoneCutoff));
    }

    /**
     * Shuts the registry's executors down. Must be called from the owning
     * bundle's {@code stop()} method to avoid leaking threads across OSGi
     * bundle reloads. Safe to call multiple times.
     */
    public void shutdown() {
        LOG.info("Shutting down BackgroundJobRegistry (jobs=%d)", jobs.size()); //$NON-NLS-1$
        try {
            cleaner.shutdown();
            if (!cleaner.awaitTermination(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                cleaner.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cleaner.shutdownNow();
        }
        try {
            executor.shutdown();
            if (!executor.awaitTermination(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        // Free memory so stale JobRecord state can't survive a bundle reload.
        jobs.clear();
        expiredJobs.clear();
    }

    private void enforceMaxJobs() {
        if (jobs.size() < MAX_JOBS) {
            return;
        }
        // Find oldest finished job; if none, fall back to the oldest record.
        List<JobRecord> snapshot = new ArrayList<>(jobs.values());
        JobRecord victim = snapshot.stream()
                .filter(r -> {
                    JobState s = r.state;
                    return s == JobState.DONE || s == JobState.FAILED;
                })
                .min(Comparator.comparing(r -> {
                    Instant f = r.getFinishedAt();
                    return f == null ? Instant.MAX : f;
                }))
                .orElseGet(() -> snapshot.stream()
                        .min(Comparator.comparing(r -> r.submittedAt))
                        .orElse(null));
        if (victim != null && jobs.remove(victim.jobId, victim)) {
            LOG.warn("MAX_JOBS cap reached; evicting job %s (kind=%s)", //$NON-NLS-1$
                    victim.jobId, victim.kind);
            expiredJobs.put(victim.jobId, clock.instant());
        }
    }

    private void runJob(JobRecord record, Callable<String> work) {
        record.markRunning(clock.instant());
        try {
            String result = work.call();
            record.markDone(result, clock.instant());
        } catch (Exception e) {
            LOG.warn("Background job %s failed: %s", record.jobId, e.getMessage()); //$NON-NLS-1$
            record.markFailed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                    clock.instant());
        }
    }

    /** State of a background job. */
    public enum JobState {
        /** Job is queued but hasn't started yet. */
        PENDING,
        /** Job is currently executing. */
        RUNNING,
        /** Job finished successfully. */
        DONE,
        /** Job threw an exception. */
        FAILED
    }

    /** Outcome kind for {@link BackgroundJobRegistry#lookupJob(String)}. */
    public enum JobLookupKind {
        /** The id points to a live job record. */
        PRESENT,
        /** The id was evicted after retention; {@link JobLookup#getExpiredAt()} is populated. */
        EXPIRED,
        /** The id was never issued or has aged past the tombstone window. */
        UNKNOWN
    }

    /** Immutable tri-state lookup result. */
    public static final class JobLookup {
        private final JobLookupKind kind;
        private final JobStatus status;
        private final Instant expiredAt;

        private JobLookup(JobLookupKind kind, JobStatus status, Instant expiredAt) {
            this.kind = kind;
            this.status = status;
            this.expiredAt = expiredAt;
        }

        static JobLookup present(JobStatus status) {
            return new JobLookup(JobLookupKind.PRESENT, Objects.requireNonNull(status), null);
        }

        static JobLookup expired(Instant expiredAt) {
            return new JobLookup(JobLookupKind.EXPIRED, null, Objects.requireNonNull(expiredAt));
        }

        static JobLookup unknown() {
            return new JobLookup(JobLookupKind.UNKNOWN, null, null);
        }

        public JobLookupKind getKind() {
            return kind;
        }

        public JobStatus getStatus() {
            return status;
        }

        public Instant getExpiredAt() {
            return expiredAt;
        }
    }

    /** Immutable snapshot of a job's current state. */
    public static final class JobStatus {
        private final String jobId;
        private final String kind;
        private final JobState state;
        private final Instant submittedAt;
        private final Instant startedAt;
        private final Instant finishedAt;
        private final String result;
        private final String error;

        JobStatus(String jobId, String kind, JobState state, Instant submittedAt,
                Instant startedAt, Instant finishedAt, String result, String error) {
            this.jobId = jobId;
            this.kind = kind;
            this.state = state;
            this.submittedAt = submittedAt;
            this.startedAt = startedAt;
            this.finishedAt = finishedAt;
            this.result = result;
            this.error = error;
        }

        public String getJobId() {
            return jobId;
        }

        public String getKind() {
            return kind;
        }

        public JobState getState() {
            return state;
        }

        public Instant getSubmittedAt() {
            return submittedAt;
        }

        public Instant getStartedAt() {
            return startedAt;
        }

        public Instant getFinishedAt() {
            return finishedAt;
        }

        public String getResult() {
            return result;
        }

        public String getError() {
            return error;
        }
    }

    private static final class JobRecord {
        private final String jobId;
        private final String kind;
        private final Instant submittedAt;
        private volatile JobState state;
        private volatile Instant startedAt;
        private volatile Instant finishedAt;
        private volatile String result;
        private volatile String error;

        JobRecord(String jobId, String kind, Instant submittedAt) {
            this.jobId = jobId;
            this.kind = kind;
            this.submittedAt = submittedAt;
            this.state = JobState.PENDING;
        }

        synchronized void markRunning(Instant now) {
            if (state == JobState.PENDING) {
                state = JobState.RUNNING;
                startedAt = now;
            }
        }

        synchronized void markDone(String result, Instant now) {
            state = JobState.DONE;
            this.result = result;
            finishedAt = now;
        }

        synchronized void markFailed(String error, Instant now) {
            state = JobState.FAILED;
            this.error = error;
            finishedAt = now;
        }

        synchronized JobStatus snapshot() {
            return new JobStatus(jobId, kind, state, submittedAt,
                    startedAt, finishedAt, result, error);
        }

        Instant getFinishedAt() {
            return finishedAt;
        }

        boolean isExpired(Instant now, Duration retention) {
            Instant ref = finishedAt;
            if (ref == null) {
                return false;
            }
            return ref.plus(retention).isBefore(now);
        }
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicLong counter = new AtomicLong();

        NamedDaemonThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet()); //$NON-NLS-1$
            thread.setDaemon(true);
            return thread;
        }
    }
}
