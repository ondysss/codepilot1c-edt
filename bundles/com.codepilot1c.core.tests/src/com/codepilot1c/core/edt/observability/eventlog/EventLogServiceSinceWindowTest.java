package com.codepilot1c.core.edt.observability.eventlog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * A partition far larger than the scan cap must still answer a since-bounded
 * query: the walk seeks to the window instead of parsing the partition from
 * byte zero.
 *
 * <p>Without the seek the cap is spent on records older than the window and the
 * query returns an empty list — indistinguishable from a genuinely quiet window,
 * which is the worst failure mode for a log query used as a CI gate.</p>
 */
public class EventLogServiceSinceWindowTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final int SCAN_CAP = 200;
    private static final int OLD_RECORDS = 5_000;

    @Test
    public void findsTailRecordBehindTheScanCap() throws IOException {
        Path logDir = writeLog();
        EventLogService.Query q = new EventLogService.Query();
        q.sinceRaw = 20260818_200000L;
        q.severities = Set.of("Error"); //$NON-NLS-1$
        q.limit = 10;

        EventLogService.Result result = new EventLogService(SCAN_CAP).query(logDir, q);

        assertEquals("the record inside the window must be found", 1, result.records.size()); //$NON-NLS-1$
        assertFalse("the window is small, the cap must not be hit", result.scanCapHit); //$NON-NLS-1$
        assertTrue("records older than the window must not be parsed", //$NON-NLS-1$
                result.scanned < OLD_RECORDS);
    }

    /** Records before the window must stay invisible even though they are parsed cheaply. */
    @Test
    public void ignoresRecordsBeforeTheWindow() throws IOException {
        Path logDir = writeLog();
        EventLogService.Query q = new EventLogService.Query();
        q.sinceRaw = 20260818_235959L; // after every record in the file
        q.limit = 10;

        EventLogService.Result result = new EventLogService(SCAN_CAP).query(logDir, q);

        assertEquals("nothing is in that window", 0, result.records.size()); //$NON-NLS-1$
    }

    /**
     * Builds one daily partition: many records before the window and a single
     * Error inside it. The reference catalog is minimal — decoding details are
     * covered elsewhere, here only the seek matters.
     */
    private Path writeLog() throws IOException {
        Path logDir = folder.newFolder("1Cv8Log").toPath(); //$NON-NLS-1$
        Files.writeString(logDir.resolve("1Cv8.lgf"), //$NON-NLS-1$
                "1CV8LOG(ver 2.0)\n{1,\n{1,0,\"Data\"}\n},\n", StandardCharsets.UTF_8); //$NON-NLS-1$

        StringBuilder sb = new StringBuilder("1CV8LOG(ver 2.0)\n") //$NON-NLS-1$
                .append("67aed8c3-5c53-46a2-8772-f76e1dacbba1\n\n"); //$NON-NLS-1$
        for (int i = 0; i < OLD_RECORDS; i++) {
            sb.append(record("20260818010101", "I")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        sb.append(record("20260818203000", "E")); //$NON-NLS-1$ //$NON-NLS-2$

        Files.writeString(logDir.resolve("20260818000000.lgp"), sb.toString(), //$NON-NLS-1$
                StandardCharsets.UTF_8);
        return logDir;
    }

    /** Severity code sits at top-level index 8, mirroring a real {@code .lgp} record. */
    private static String record(String stamp, String severity) {
        return "{" + stamp + ",N,\n" //$NON-NLS-1$ //$NON-NLS-2$
                + "{0,0},1,1,1,1,1," + severity + ",\"\",0,\n" //$NON-NLS-1$ //$NON-NLS-2$
                + "{\"U\"},\"\",0,0,0,1,0,\n" //$NON-NLS-1$
                + "{0}\n" //$NON-NLS-1$
                + "},\n"; //$NON-NLS-1$
    }
}
