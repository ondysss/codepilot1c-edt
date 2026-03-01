package com.codepilot1c.core.qa;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class QaStatusState {

    private static final Duration DEFAULT_MAX_AGE = Duration.ofMinutes(10);
    private static final AtomicReference<Snapshot> LAST = new AtomicReference<>();

    private QaStatusState() {
    }

    public static void record(File workspaceRoot, File configFile, int errors, int warnings) {
        Snapshot snapshot = new Snapshot(
                canonicalPath(workspaceRoot),
                canonicalPath(configFile),
                Instant.now(),
                errors,
                warnings);
        LAST.set(snapshot);
    }

    public static Snapshot getLast() {
        return LAST.get();
    }

    public static String validateRecentOk(File workspaceRoot, File configFile) {
        return validateRecentOk(workspaceRoot, configFile, DEFAULT_MAX_AGE);
    }

    public static String validateRecentOk(File workspaceRoot, File configFile, Duration maxAge) {
        Snapshot snapshot = LAST.get();
        if (snapshot == null) {
            return "qa_status is required before qa_run"; //$NON-NLS-1$
        }
        if (configFile == null || workspaceRoot == null) {
            return "qa_status is required before qa_run (workspace/config unknown)"; //$NON-NLS-1$
        }
        String workspacePath = canonicalPath(workspaceRoot);
        String configPath = canonicalPath(configFile);
        if (workspacePath == null || configPath == null) {
            return "qa_status is required before qa_run (workspace/config unknown)"; //$NON-NLS-1$
        }
        if (!Objects.equals(snapshot.workspaceRoot, workspacePath) || !Objects.equals(snapshot.configPath, configPath)) {
            return "qa_status must be executed for the same workspace/config before qa_run"; //$NON-NLS-1$
        }
        if (!snapshot.isOk()) {
            return "qa_status reported errors; fix configuration before qa_run"; //$NON-NLS-1$
        }
        Duration age = Duration.between(snapshot.timestamp, Instant.now());
        if (age.compareTo(maxAge) > 0) {
            return "qa_status is stale; re-run qa_status before qa_run"; //$NON-NLS-1$
        }
        return null;
    }

    private static String canonicalPath(File file) {
        if (file == null) {
            return null;
        }
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }

    public static final class Snapshot {
        private final String workspaceRoot;
        private final String configPath;
        private final Instant timestamp;
        private final int errors;
        private final int warnings;

        private Snapshot(String workspaceRoot, String configPath, Instant timestamp, int errors, int warnings) {
            this.workspaceRoot = workspaceRoot;
            this.configPath = configPath;
            this.timestamp = timestamp;
            this.errors = errors;
            this.warnings = warnings;
        }

        public boolean isOk() {
            return errors == 0;
        }

        public Instant getTimestamp() {
            return timestamp;
        }
    }
}
