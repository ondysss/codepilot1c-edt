package com.codepilot1c.core.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

/**
 * Display-free lock-in test for the single-flight invariant enforced by
 * {@code ChatView.inflight} (Plan 1.1 in the token-reduction program).
 *
 * <p>The invariant under test:
 * <ul>
 *   <li>First request entry wins the {@code AtomicBoolean} CAS and proceeds.</li>
 *   <li>Any overlapping request entry (whether from streaming or non-streaming
 *       entry point) loses the CAS, logs a WARN, and returns immediately —
 *       it must NOT dispatch {@code handleResponseWithTools} a second time.</li>
 *   <li>Only after the outer chain terminates (thenAccept/exceptionally, error,
 *       or user cancel) may a new request acquire the guard.</li>
 * </ul>
 *
 * <p>Mirrors the exact CAS pattern used in {@code ChatView.startStreamingRequest}
 * and {@code ChatView.startNonStreamingRequest}. A deviation here must be a
 * deliberate design change, not an accidental refactor.
 */
public class ChatViewSingleFlightTest {

    /** Surrogate reproducer of ChatView's inflight guard + round-trip counter. */
    private static final class SingleFlightSurrogate {
        final AtomicBoolean inflight = new AtomicBoolean(false);
        final AtomicLong roundTripSeq = new AtomicLong(0);
        final AtomicInteger handleResponseDispatches = new AtomicInteger(0);
        final AtomicInteger droppedDuplicates = new AtomicInteger(0);

        /**
         * @return {@code true} if the request was admitted, {@code false} if it was
         *         dropped as a duplicate.
         */
        boolean tryEnterRequest() {
            long rt = roundTripSeq.incrementAndGet();
            if (!inflight.compareAndSet(false, true)) {
                droppedDuplicates.incrementAndGet();
                return false;
            }
            // Admitted: simulate kicking off the top-level chain that would
            // eventually call handleResponseWithTools.
            handleResponseDispatches.incrementAndGet();
            // rt is unused by the surrogate, but the real ChatView logs it.
            assertTrue("round-trip ids are strictly increasing", rt >= 1);
            return true;
        }

        /** Called at every outer termination tail (thenAccept/exceptionally/error). */
        void terminate() {
            inflight.set(false);
        }
    }

    @Test
    public void secondOverlappingRequestIsDropped() {
        SingleFlightSurrogate guard = new SingleFlightSurrogate();

        assertTrue("first request must be admitted", guard.tryEnterRequest());
        assertFalse("overlapping second request must be dropped", guard.tryEnterRequest());
        assertFalse("overlapping third request must be dropped", guard.tryEnterRequest());

        assertEquals("handleResponseWithTools must be dispatched exactly once",
                1, guard.handleResponseDispatches.get());
        assertEquals("two duplicates must be observed",
                2, guard.droppedDuplicates.get());
    }

    @Test
    public void guardReadmitsAfterTermination() {
        SingleFlightSurrogate guard = new SingleFlightSurrogate();

        assertTrue(guard.tryEnterRequest());
        guard.terminate();
        assertTrue("new request must be admitted after outer chain terminates",
                guard.tryEnterRequest());

        assertEquals(2, guard.handleResponseDispatches.get());
        assertEquals(0, guard.droppedDuplicates.get());
    }

    @Test
    public void userCancelClearsGuard() {
        SingleFlightSurrogate guard = new SingleFlightSurrogate();

        assertTrue(guard.tryEnterRequest());
        // Simulates ChatView.stopGeneration user-cancel clearing the guard.
        guard.terminate();
        assertTrue("request after user-cancel must be admitted",
                guard.tryEnterRequest());
    }

    @Test
    public void concurrentEntrantsStillAdmitExactlyOne() throws InterruptedException {
        final int threads = 16;
        final SingleFlightSurrogate guard = new SingleFlightSurrogate();

        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(guard::tryEnterRequest);
        }
        for (Thread w : workers) {
            w.start();
        }
        for (Thread w : workers) {
            w.join();
        }

        assertEquals("exactly one concurrent entrant wins the CAS",
                1, guard.handleResponseDispatches.get());
        assertEquals("remaining entrants are dropped as duplicates",
                threads - 1, guard.droppedDuplicates.get());
    }
}
