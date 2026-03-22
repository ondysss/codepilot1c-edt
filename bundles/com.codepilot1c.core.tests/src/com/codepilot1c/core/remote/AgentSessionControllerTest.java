package com.codepilot1c.core.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class AgentSessionControllerTest {

    private AgentSessionController controller;
    private String cleanupClientId;

    @Before
    public void setUp() {
        controller = AgentSessionController.getInstance();
        cleanupClientId = "test-cleanup-" + UUID.randomUUID(); //$NON-NLS-1$
        controller.claimControllerLease(cleanupClientId, true);
        controller.releaseControllerLease(cleanupClientId);
        controller.resetSession("test_setup"); //$NON-NLS-1$
    }

    @After
    public void tearDown() {
        controller.claimControllerLease(cleanupClientId, true);
        controller.releaseControllerLease(cleanupClientId);
        controller.resetSession("test_teardown"); //$NON-NLS-1$
    }

    @Test
    public void controllerLeaseSupportsClaimConflictForceTakeoverAndRelease() {
        String clientA = "client-a-" + UUID.randomUUID(); //$NON-NLS-1$
        String clientB = "client-b-" + UUID.randomUUID(); //$NON-NLS-1$

        RemoteCommandResult firstClaim = controller.claimControllerLease(clientA, false);
        assertTrue(firstClaim.isOk());
        assertEquals(clientA, controller.getControllerClientId());
        assertTrue(controller.hasControllerLease(clientA));

        RemoteCommandResult conflictingClaim = controller.claimControllerLease(clientB, false);
        assertFalse(conflictingClaim.isOk());
        assertEquals("lease_conflict", conflictingClaim.getCode()); //$NON-NLS-1$
        assertEquals(clientA, conflictingClaim.getPayload().get("controllerClientId")); //$NON-NLS-1$

        RemoteCommandResult takeover = controller.claimControllerLease(clientB, true);
        assertTrue(takeover.isOk());
        assertEquals(clientB, controller.getControllerClientId());
        assertTrue(controller.hasControllerLease(clientB));
        assertFalse(controller.hasControllerLease(clientA));

        RemoteCommandResult release = controller.releaseControllerLease(clientB);
        assertTrue(release.isOk());
        assertEquals(null, controller.getControllerClientId());
    }

    @Test
    public void remoteEventsRemainMonotonicAndReplayFromSequence() {
        long baseline = controller.getEventsAfter(0).stream()
                .mapToLong(RemoteEvent::getSequence)
                .max()
                .orElse(0L);

        List<RemoteEvent> observed = new CopyOnWriteArrayList<>();
        AgentSessionController.RemoteEventListener listener = observed::add;
        controller.addRemoteEventListener(listener, baseline);

        String clientId = "client-events-" + UUID.randomUUID(); //$NON-NLS-1$
        controller.claimControllerLease(clientId, false);
        controller.releaseControllerLease(clientId);
        controller.resetSession("test_event_replay"); //$NON-NLS-1$
        controller.removeRemoteEventListener(listener);

        assertTrue(observed.size() >= 3);

        long previous = baseline;
        boolean sawLease = false;
        boolean sawReset = false;
        for (RemoteEvent event : observed) {
            assertTrue(event.getSequence() > previous);
            previous = event.getSequence();
            if ("lease_changed".equals(event.getType())) { //$NON-NLS-1$
                sawLease = true;
            }
            if ("session_reset".equals(event.getType())) { //$NON-NLS-1$
                sawReset = true;
            }
        }

        assertTrue(sawLease);
        assertTrue(sawReset);

        List<RemoteEvent> replayed = controller.getEventsAfter(baseline);
        assertFalse(replayed.isEmpty());
        assertEquals(observed.get(0).getSequence(), replayed.get(0).getSequence());
        assertEquals(observed.get(observed.size() - 1).getSequence(), replayed.get(replayed.size() - 1).getSequence());
    }

    @Test
    public void workbenchCommandsAreRejectedBeforeConfirmationWhenMissingOrDenied() {
        String clientId = "client-command-" + UUID.randomUUID(); //$NON-NLS-1$
        controller.claimControllerLease(clientId, false);
        controller.resetSession("test_command_validation"); //$NON-NLS-1$

        long baseline = controller.getEventsAfter(0).stream()
                .mapToLong(RemoteEvent::getSequence)
                .max()
                .orElse(0L);

        RemoteCommandResult missing = controller.executeWorkbenchCommand(clientId, "", Map.of()); //$NON-NLS-1$
        assertFalse(missing.isOk());
        assertEquals("missing_command", missing.getCode()); //$NON-NLS-1$
        assertTrue(controller.currentPendingConfirmation().isEmpty());

        RemoteCommandResult denied = controller.executeWorkbenchCommand(clientId, "org.eclipse.ui.file.exit", Map.of()); //$NON-NLS-1$
        assertFalse(denied.isOk());
        assertEquals("command_denied", denied.getCode()); //$NON-NLS-1$
        assertTrue(controller.currentPendingConfirmation().isEmpty());

        List<RemoteEvent> emitted = controller.getEventsAfter(baseline);
        assertTrue(emitted.stream().noneMatch(event -> "confirmation_required".equals(event.getType()))); //$NON-NLS-1$
    }
}
