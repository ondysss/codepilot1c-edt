package com.codepilot1c.core.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.codepilot1c.core.agent.profiles.InitAgentProfile;
import com.codepilot1c.core.agent.AgentConfig;
import com.codepilot1c.core.agent.AgentResult;
import com.codepilot1c.core.agent.AgentState;
import com.codepilot1c.core.agent.IAgentRunner;
import com.codepilot1c.core.agent.events.AgentCompletedEvent;
import com.codepilot1c.core.agent.events.AgentStepEvent;
import com.codepilot1c.core.agent.events.IAgentEventListener;
import com.codepilot1c.core.agent.profiles.AgentProfile;
import com.codepilot1c.core.memory.project.ProjectMemoryInitializationService;
import com.codepilot1c.core.memory.project.ProjectMemoryInitializationService.Mode;
import com.codepilot1c.core.memory.project.ProjectMemoryInitializationService.Request;
import com.codepilot1c.core.memory.project.ProjectMemoryInitializationService.Status;
import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.model.LlmRequest;
import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.model.LlmStreamChunk;
import com.codepilot1c.core.provider.ILlmProvider;

public class AgentSessionControllerTest {

    private static final String TEST_PROMPT_ADDITION = "test prompt addition"; //$NON-NLS-1$

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private AgentSessionController controller;
    private String cleanupClientId;
    private Object previousRunnerFactory;
    private Object previousToolRegistrySupplier;
    private Object previousProviderSupplier;
    private Object previousConfigFactory;

    @Before
    public void setUp() {
        controller = AgentSessionController.getInstance();
        cleanupClientId = "test-cleanup-" + UUID.randomUUID(); //$NON-NLS-1$
        controller.claimControllerLease(cleanupClientId, true);
        controller.releaseControllerLease(cleanupClientId);
        controller.resetSession("test_setup"); //$NON-NLS-1$
    }

    @After
    public void tearDown() throws Exception {
        controller.claimControllerLease(cleanupClientId, true);
        controller.releaseControllerLease(cleanupClientId);
        controller.resetSession("test_teardown"); //$NON-NLS-1$
        if (previousRunnerFactory != null) {
            setControllerField("runnerFactory", previousRunnerFactory); //$NON-NLS-1$
            previousRunnerFactory = null;
        }
        if (previousToolRegistrySupplier != null) {
            setControllerField("toolRegistrySupplier", previousToolRegistrySupplier); //$NON-NLS-1$
            previousToolRegistrySupplier = null;
        }
        if (previousProviderSupplier != null) {
            setControllerField("providerSupplier", previousProviderSupplier); //$NON-NLS-1$
            previousProviderSupplier = null;
        }
        if (previousConfigFactory != null) {
            setControllerField("configFactory", previousConfigFactory); //$NON-NLS-1$
            previousConfigFactory = null;
        }
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

    @Test
    public void freshDesktopSubmitResetsHistoryAndStoresRequestedProfileBeforeLaunch() throws Exception {
        installProvider(null);
        setControllerField("conversationHistory", new ArrayList<>(List.of(LlmMessage.user("old chat")))); //$NON-NLS-1$ //$NON-NLS-2$
        String beforeSessionId = controller.getSessionId();
        long baseline = controller.getEventsAfter(0).stream()
                .mapToLong(RemoteEvent::getSequence)
                .max()
                .orElse(0L);

        controller.submitFromDesktopFresh("refresh project memory", InitAgentProfile.ID); //$NON-NLS-1$

        RemoteBootstrapResponse bootstrap = controller.buildBootstrap(
                "test-client", IdeSnapshot.unavailable("test")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNotEquals(beforeSessionId, bootstrap.getSessionId());
        assertEquals(InitAgentProfile.ID, bootstrap.getAgent().get("profileId")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(0), bootstrap.getAgent().get("historySize")); //$NON-NLS-1$
        assertTrue(controller.getEventsAfter(baseline).stream()
                .anyMatch(event -> "session_reset".equals(event.getType()) //$NON-NLS-1$
                        && "desktop_fresh".equals(event.getPayload().get("reason")))); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void forwardingListenerHandlesConfirmations() throws Exception {
        installProvider(new NoopProvider());
        CompletedRunner runner = new CompletedRunner("confirmation-listener", false); //$NON-NLS-1$
        previousRunnerFactory = controllerField("runnerFactory"); //$NON-NLS-1$
        previousToolRegistrySupplier = controllerField("toolRegistrySupplier"); //$NON-NLS-1$
        setControllerField("toolRegistrySupplier", //$NON-NLS-1$
                (java.util.function.Supplier<com.codepilot1c.core.tools.ToolRegistry>) () -> null);
        setControllerField("runnerFactory", (AgentSessionController.RunnerFactory) //$NON-NLS-1$
                (provider, tools, prompt) -> runner);

        assertTrue(controller.submitFromDesktopFresh(
                "confirmation listener", InitAgentProfile.ID).join().isSuccess()); //$NON-NLS-1$
        assertTrue(runner.listenerHandlesConfirmations);
    }

    @Test
    public void cancelledRunKeepsSlotUntilCapturedRunnerCleanupAndPreservesNextRun() throws Exception {
        installProvider(new NoopProvider());

        FakeRunner firstRunner = new FakeRunner(true);
        FakeRunner secondRunner = new FakeRunner(false);
        AtomicInteger created = new AtomicInteger();
        previousRunnerFactory = controllerField("runnerFactory"); //$NON-NLS-1$
        previousToolRegistrySupplier = controllerField("toolRegistrySupplier"); //$NON-NLS-1$
        setControllerField("toolRegistrySupplier", //$NON-NLS-1$
                (java.util.function.Supplier<com.codepilot1c.core.tools.ToolRegistry>) () -> null);
        setControllerField("runnerFactory", (AgentSessionController.RunnerFactory) //$NON-NLS-1$
                (provider, tools, prompt) -> created.getAndIncrement() == 0 ? firstRunner : secondRunner);

        CompletableFuture<AgentResult> firstHandle = controller.submitFromDesktopFreshScoped(
                "init first", InitAgentProfile.ID, "/projects/first", "view-session-1"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("/projects/first", firstRunner.config.getProjectPath()); //$NON-NLS-1$
        assertEquals("view-session-1", firstRunner.config.getSessionId()); //$NON-NLS-1$
        assertEquals(1, firstRunner.runInvocationCount.get());
        assertTrue(firstHandle.cancel(true));
        assertTrue(firstRunner.cancelled);

        CompletableFuture<Void> unwind = CompletableFuture.runAsync(firstRunner::finishCancelled);
        assertTrue(firstRunner.disposeEntered.await(2, TimeUnit.SECONDS));
        CompletableFuture<AgentResult> rejected = controller.submitFromDesktopFreshScoped(
                "init second too early", InitAgentProfile.ID, "/projects/second", "view-session-2"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(rejected.isCompletedExceptionally());
        assertEquals(1, created.get());

        firstRunner.releaseDispose.countDown();
        unwind.get(2, TimeUnit.SECONDS);
        CompletableFuture<AgentResult> secondHandle = controller.submitFromDesktopFreshScoped(
                "init second", InitAgentProfile.ID, "/projects/second", "view-session-2"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals("/projects/second", secondRunner.config.getProjectPath()); //$NON-NLS-1$
        assertEquals("view-session-2", secondRunner.config.getSessionId()); //$NON-NLS-1$
        assertEquals(1, secondRunner.runInvocationCount.get());
        secondRunner.finishSuccess();
        assertTrue(secondHandle.join().isSuccess());
        assertTrue(secondRunner.disposed);
        assertEquals(2, created.get());
    }

    @Test
    public void resetExcludesAdmissionAfterSynchronousCancelCompletionAndPreservesNextRun() throws Exception {
        installProvider(new NoopProvider());
        Object controllerLock = controllerField("lock"); //$NON-NLS-1$
        SynchronousCancelRunner firstRunner = new SynchronousCancelRunner(controller, controllerLock);
        FakeRunner secondRunner = new FakeRunner(false);
        AtomicInteger created = new AtomicInteger();
        previousRunnerFactory = controllerField("runnerFactory"); //$NON-NLS-1$
        previousToolRegistrySupplier = controllerField("toolRegistrySupplier"); //$NON-NLS-1$
        setControllerField("toolRegistrySupplier", //$NON-NLS-1$
                (java.util.function.Supplier<com.codepilot1c.core.tools.ToolRegistry>) () -> null);
        setControllerField("runnerFactory", (AgentSessionController.RunnerFactory) //$NON-NLS-1$
                (provider, tools, prompt) -> created.getAndIncrement() == 0 ? firstRunner : secondRunner);

        String clientId = "reset-admission-" + UUID.randomUUID(); //$NON-NLS-1$
        assertTrue(controller.claimControllerLease(clientId, true).isOk());
        CompletableFuture<AgentResult> firstHandle = controller.submitFromDesktopFreshScoped(
                "first run", InitAgentProfile.ID, "/projects/old", "view-old"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String sessionBeforeReset = controller.getSessionId();
        AtomicBoolean resetListenerReentered = new AtomicBoolean();
        AgentSessionController.RemoteEventListener resetListener = event -> {
            if ("session_reset".equals(event.getType()) //$NON-NLS-1$
                    && "synchronous_cancel".equals(event.getPayload().get("reason"))) { //$NON-NLS-1$ //$NON-NLS-2$
                controller.stopFromDesktop();
                resetListenerReentered.set(controller.getCurrentState() == AgentState.IDLE);
            }
        };
        controller.addRemoteEventListener(resetListener, Long.MAX_VALUE);
        try {
            CompletableFuture<Void> reset = CompletableFuture.runAsync(
                    () -> controller.resetSession("synchronous_cancel")); //$NON-NLS-1$
            assertTrue(firstRunner.cancelCompletionReturned.await(2, TimeUnit.SECONDS));
            assertTrue(firstHandle.isDone());
            assertFalse(controller.isRunning());
            assertEquals(sessionBeforeReset, controller.getSessionId());

            RemoteCommandResult rejectedRemote = controller.continueSession(
                    "must wait", InitAgentProfile.ID, clientId); //$NON-NLS-1$
            assertFalse(rejectedRemote.isOk());
            assertEquals("session_reset_in_progress", rejectedRemote.getCode()); //$NON-NLS-1$
            CompletableFuture<AgentResult> rejectedDesktop = controller.submitFromDesktopFreshScoped(
                    "also must wait", InitAgentProfile.ID, "/projects/racing", "view-racing"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            assertTrue(rejectedDesktop.isCompletedExceptionally());
            assertEquals(1, created.get());
            assertEquals("no_active_run", controller.stop(clientId).getCode()); //$NON-NLS-1$

            firstRunner.allowCancelReturn.countDown();
            reset.get(2, TimeUnit.SECONDS);
            assertTrue(firstRunner.disposed);
            assertFalse(firstRunner.disposeHeldLock.get());
            assertTrue(firstRunner.disposeReentered.get());
            assertTrue(resetListenerReentered.get());
        } finally {
            firstRunner.allowCancelReturn.countDown();
            controller.removeRemoteEventListener(resetListener);
        }

        String resetSessionId = controller.getSessionId();
        assertNotEquals(sessionBeforeReset, resetSessionId);
        RemoteBootstrapResponse afterReset = controller.buildBootstrap(
                "after-reset", IdeSnapshot.unavailable("test")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(AgentState.IDLE.name(), afterReset.getAgent().get("state")); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, afterReset.getAgent().get("running")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(0), afterReset.getAgent().get("historySize")); //$NON-NLS-1$
        assertEquals(InitAgentProfile.ID, afterReset.getAgent().get("profileId")); //$NON-NLS-1$

        CompletableFuture<AgentResult> secondHandle = controller.submitFromDesktopFreshScoped(
                "second run", InitAgentProfile.ID, "/projects/new", "view-new"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String secondSessionId = controller.getSessionId();
        assertNotEquals(resetSessionId, secondSessionId);
        assertEquals("/projects/new", secondRunner.config.getProjectPath()); //$NON-NLS-1$
        assertEquals("view-new", secondRunner.config.getSessionId()); //$NON-NLS-1$
        List<LlmMessage> nextHistory = List.of(
                LlmMessage.user("second run"), LlmMessage.assistant("done")); //$NON-NLS-1$ //$NON-NLS-2$
        secondRunner.finishSuccess(nextHistory);
        assertTrue(secondHandle.get(2, TimeUnit.SECONDS).isSuccess());

        RemoteBootstrapResponse afterNextRun = controller.buildBootstrap(
                "after-next", IdeSnapshot.unavailable("test")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(secondSessionId, afterNextRun.getSessionId());
        assertEquals(AgentState.COMPLETED.name(), afterNextRun.getAgent().get("state")); //$NON-NLS-1$
        assertEquals(Boolean.FALSE, afterNextRun.getAgent().get("running")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(2), afterNextRun.getAgent().get("historySize")); //$NON-NLS-1$
        assertEquals(2, created.get());
    }

    @Test
    public void completionOfResetRunCannotClearNewerAdmittedRun() throws Exception {
        installProvider(new NoopProvider());
        DelayedRunReturnRunner resetRunner = new DelayedRunReturnRunner();
        FakeRunner nextRunner = new FakeRunner(false);
        AtomicInteger created = new AtomicInteger();
        previousRunnerFactory = controllerField("runnerFactory"); //$NON-NLS-1$
        previousToolRegistrySupplier = controllerField("toolRegistrySupplier"); //$NON-NLS-1$
        setControllerField("toolRegistrySupplier", //$NON-NLS-1$
                (java.util.function.Supplier<com.codepilot1c.core.tools.ToolRegistry>) () -> null);
        setControllerField("runnerFactory", (AgentSessionController.RunnerFactory) //$NON-NLS-1$
                (provider, tools, prompt) -> created.getAndIncrement() == 0 ? resetRunner : nextRunner);

        CompletableFuture<CompletableFuture<AgentResult>> resetSubmission = CompletableFuture.supplyAsync(
                () -> controller.submitFromDesktopFreshScoped(
                        "old run", InitAgentProfile.ID, "/projects/old", "session-old")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertTrue(resetRunner.runEntered.await(2, TimeUnit.SECONDS));
        controller.resetSession("admit_next_before_old_completion"); //$NON-NLS-1$
        assertTrue(resetRunner.cancelled);
        assertFalse(controller.isRunning());
        assertEquals(AgentState.IDLE, controller.getCurrentState());

        CompletableFuture<AgentResult> nextHandle = controller.submitFromDesktopFreshScoped(
                "new run", InitAgentProfile.ID, "/projects/new", "session-new"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String nextSessionId = controller.getSessionId();
        assertTrue(controller.isRunning());
        assertEquals("/projects/new", nextRunner.config.getProjectPath()); //$NON-NLS-1$
        assertEquals("session-new", nextRunner.config.getSessionId()); //$NON-NLS-1$

        resetRunner.emitCompleted(AgentResult.success(
                "stale event", List.of(LlmMessage.user("stale event history")), 1, 1, 1)); //$NON-NLS-1$ //$NON-NLS-2$
        RemoteBootstrapResponse afterStaleEvent = controller.buildBootstrap(
                "during-new-run", IdeSnapshot.unavailable("test")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(nextSessionId, afterStaleEvent.getSessionId());
        assertEquals(AgentState.RUNNING.name(), afterStaleEvent.getAgent().get("state")); //$NON-NLS-1$
        assertEquals(Boolean.TRUE, afterStaleEvent.getAgent().get("running")); //$NON-NLS-1$
        assertEquals(Integer.valueOf(0), afterStaleEvent.getAgent().get("historySize")); //$NON-NLS-1$

        resetRunner.allowRunReturn.countDown();
        CompletableFuture<AgentResult> resetHandle = resetSubmission.get(2, TimeUnit.SECONDS);
        assertFalse(resetHandle.isDone());
        resetRunner.finishCancelled();
        assertEquals(AgentState.CANCELLED, resetHandle.get(2, TimeUnit.SECONDS).getFinalState());
        assertTrue(resetRunner.disposed);
        assertTrue(controller.isRunning());
        assertEquals(AgentState.RUNNING, controller.getCurrentState());
        assertFalse(nextRunner.disposed);

        nextRunner.finishSuccess(List.of(
                LlmMessage.user("new run"), LlmMessage.assistant("new result"))); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(nextHandle.get(2, TimeUnit.SECONDS).isSuccess());
        assertFalse(controller.isRunning());
        assertEquals(AgentState.COMPLETED, controller.getCurrentState());
        assertEquals(2, created.get());
    }

    @Test
    public void alreadyCompletedTaskSucceedsForProjectMemoryFreshAndLegacyDesktopSubmissions() throws Exception {
        installProvider(new NoopProvider());

        CompletedRunner directFreshRunner = new CompletedRunner("direct-fresh", false); //$NON-NLS-1$
        CompletedRunner memoryFreshRunner = new CompletedRunner("memory-fresh", true); //$NON-NLS-1$
        CompletedRunner legacyRunner = new CompletedRunner("legacy", false); //$NON-NLS-1$
        AtomicInteger created = new AtomicInteger();
        List<String> runnerPrompts = new ArrayList<>();
        previousRunnerFactory = controllerField("runnerFactory"); //$NON-NLS-1$
        previousToolRegistrySupplier = controllerField("toolRegistrySupplier"); //$NON-NLS-1$
        setControllerField("toolRegistrySupplier", //$NON-NLS-1$
                (java.util.function.Supplier<com.codepilot1c.core.tools.ToolRegistry>) () -> null);
        setControllerField("runnerFactory", (AgentSessionController.RunnerFactory) //$NON-NLS-1$
                (provider, tools, prompt) -> {
                    runnerPrompts.add(prompt);
                    return switch (created.getAndIncrement()) {
                        case 0 -> directFreshRunner;
                        case 1 -> memoryFreshRunner;
                        default -> legacyRunner;
                    };
                });

        CompletableFuture<AgentResult> directFresh = controller.submitFromDesktopFresh(
                "completed direct fresh", InitAgentProfile.ID); //$NON-NLS-1$
        assertEquals(1, directFreshRunner.runInvocationCount.get());
        assertEquals(TEST_PROMPT_ADDITION, runnerPrompts.get(0));
        assertSame(directFreshRunner.task, directFresh);
        assertEquals("direct-fresh", directFresh.join().getFinalResponse()); //$NON-NLS-1$
        assertEquals(1, directFreshRunner.removeListenerCount.get());
        assertEquals(1, directFreshRunner.disposeCount.get());

        Path projectRoot = temporaryFolder.newFolder("completed-fresh").toPath(); //$NON-NLS-1$
        CompletableFuture<ProjectMemoryInitializationService.Result> initializationFuture =
                new ProjectMemoryInitializationService().initialize(new Request(
                        Mode.CREATE, projectRoot, "CompletedFresh", //$NON-NLS-1$
                        "CompletedFresh/Code.md", "view-fresh")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(1, memoryFreshRunner.runInvocationCount.get());
        assertEquals(TEST_PROMPT_ADDITION, runnerPrompts.get(1));
        ProjectMemoryInitializationService.Result initialization = initializationFuture.join();
        assertEquals(Status.SUCCESS, initialization.getStatus());
        assertEquals(1, memoryFreshRunner.removeListenerCount.get());
        assertEquals(1, memoryFreshRunner.disposeCount.get());

        CompletableFuture<AgentResult> legacy = controller.submitFromDesktop(
                "completed legacy", InitAgentProfile.ID); //$NON-NLS-1$
        assertEquals(1, legacyRunner.runInvocationCount.get());
        assertEquals(TEST_PROMPT_ADDITION, runnerPrompts.get(2));
        assertNotNull(legacy);
        assertSame(legacyRunner.task, legacy);
        assertEquals("legacy", legacy.join().getFinalResponse()); //$NON-NLS-1$
        assertEquals(1, legacyRunner.removeListenerCount.get());
        assertEquals(1, legacyRunner.disposeCount.get());
        assertEquals(AgentState.COMPLETED, controller.getCurrentState());
        assertEquals(3, created.get());
    }

    @Test
    public void runnerLifecycleCallbacksAlwaysObserveControllerLockUnheld() throws Exception {
        installProvider(new NoopProvider());
        Object controllerLock = controllerField("lock"); //$NON-NLS-1$
        AtomicBoolean supplierHeldLock = new AtomicBoolean();
        AtomicBoolean factoryHeldLock = new AtomicBoolean();
        LockCheckingRunner runner = new LockCheckingRunner(controllerLock);
        previousToolRegistrySupplier = controllerField("toolRegistrySupplier"); //$NON-NLS-1$
        previousRunnerFactory = controllerField("runnerFactory"); //$NON-NLS-1$
        setControllerField("toolRegistrySupplier", //$NON-NLS-1$
                (java.util.function.Supplier<com.codepilot1c.core.tools.ToolRegistry>) () -> {
                    supplierHeldLock.set(Thread.holdsLock(controllerLock));
                    return null;
                });
        setControllerField("runnerFactory", (AgentSessionController.RunnerFactory) //$NON-NLS-1$
                (provider, tools, prompt) -> {
                    factoryHeldLock.set(Thread.holdsLock(controllerLock));
                    return runner;
                });

        CompletableFuture<AgentResult> task = controller.submitFromDesktopFresh(
                "lock discipline", InitAgentProfile.ID); //$NON-NLS-1$
        assertFalse(supplierHeldLock.get());
        assertFalse(factoryHeldLock.get());
        assertFalse(runner.addListenerHeldLock.get());

        controller.stopFromDesktop();
        assertFalse(runner.cancelHeldLock.get());
        runner.finishSuccess();
        assertTrue(task.join().isSuccess());
        assertFalse(runner.removeListenerHeldLock.get());
        assertFalse(runner.disposeHeldLock.get());
    }

    @Test
    public void blockingRemoteAndAgentListenersCanReenterWithoutDeadlock() throws Exception {
        Object controllerLock = controllerField("lock"); //$NON-NLS-1$
        AtomicBoolean remoteHeldLock = new AtomicBoolean();
        AtomicBoolean agentHeldLock = new AtomicBoolean();
        String clientId = "listener-lock-" + UUID.randomUUID(); //$NON-NLS-1$
        AgentSessionController.RemoteEventListener remote = event -> {
            if (!"lease_changed".equals(event.getType()) //$NON-NLS-1$
                    || !clientId.equals(event.getPayload().get("controllerClientId"))) { //$NON-NLS-1$
                return;
            }
            remoteHeldLock.set(Thread.holdsLock(controllerLock));
            try {
                assertNotNull(CompletableFuture.supplyAsync(controller::getSessionId)
                        .get(1, TimeUnit.SECONDS));
            } catch (Exception e) {
                throw new AssertionError("remote listener reentry blocked", e); //$NON-NLS-1$
            }
        };
        IAgentEventListener agent = event -> {
            agentHeldLock.set(Thread.holdsLock(controllerLock));
            try {
                assertNotNull(CompletableFuture.supplyAsync(controller::getCurrentState)
                        .get(1, TimeUnit.SECONDS));
            } catch (Exception e) {
                throw new AssertionError("agent listener reentry blocked", e); //$NON-NLS-1$
            }
        };
        controller.addRemoteEventListener(remote, Long.MAX_VALUE);
        controller.addAgentListener(agent);
        try {
            controller.claimControllerLease(clientId, true);
            Method handler = AgentSessionController.class.getDeclaredMethod(
                    "handleAgentEvent", com.codepilot1c.core.agent.events.AgentEvent.class); //$NON-NLS-1$
            handler.setAccessible(true);
            handler.invoke(controller, new AgentStepEvent(1, 2, "probe")); //$NON-NLS-1$

            assertFalse(remoteHeldLock.get());
            assertFalse(agentHeldLock.get());
        } finally {
            controller.removeRemoteEventListener(remote);
            controller.removeAgentListener(agent);
            controller.releaseControllerLease(clientId);
        }
    }

    private void setControllerField(String name, Object value) throws Exception {
        Field field = AgentSessionController.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(controller, value);
    }

    private Object controllerField(String name) throws Exception {
        Field field = AgentSessionController.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(controller);
    }

    private void installProvider(ILlmProvider provider) throws Exception {
        if (previousProviderSupplier == null) {
            previousProviderSupplier = controllerField("providerSupplier"); //$NON-NLS-1$
        }
        setControllerField("providerSupplier", //$NON-NLS-1$
                (java.util.function.Supplier<ILlmProvider>) () -> provider);
        if (previousConfigFactory == null) {
            previousConfigFactory = controllerField("configFactory"); //$NON-NLS-1$
        }
        setControllerField("configFactory", //$NON-NLS-1$
                (java.util.function.Function<AgentProfile, AgentConfig>)
                        AgentSessionControllerTest::preferenceFreeConfig);
    }

    private static AgentConfig preferenceFreeConfig(AgentProfile profile) {
        return AgentConfig.builder()
                .maxSteps(profile.getMaxSteps())
                .timeoutMs(profile.getTimeoutMs())
                .enabledTools(profile.getAllowedTools())
                .systemPromptAddition(TEST_PROMPT_ADDITION)
                .profileName(profile.getId())
                .build();
    }

    private static final class FakeRunner implements IAgentRunner {
        private final CompletableFuture<AgentResult> task = new CompletableFuture<>();
        private final boolean blockDispose;
        private final CountDownLatch disposeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseDispose = new CountDownLatch(1);
        private final AtomicInteger runInvocationCount = new AtomicInteger();
        private AgentConfig config;
        private volatile AgentState state = AgentState.IDLE;
        private volatile boolean cancelled;
        private volatile boolean disposed;

        private FakeRunner(boolean blockDispose) {
            this.blockDispose = blockDispose;
        }

        @Override
        public CompletableFuture<AgentResult> run(String prompt, AgentConfig config) {
            runInvocationCount.incrementAndGet();
            this.config = config;
            state = AgentState.WAITING_TOOL;
            return task;
        }

        @Override
        public CompletableFuture<AgentResult> run(
                String prompt, List<LlmMessage> history, AgentConfig config) {
            return run(prompt, config);
        }

        @Override public void cancel() { cancelled = true; state = AgentState.CANCELLED; }
        @Override public AgentState getState() { return state; }
        @Override public void addListener(IAgentEventListener listener) { }
        @Override public void removeListener(IAgentEventListener listener) { }
        @Override public int getCurrentStep() { return 1; }
        @Override public List<LlmMessage> getConversationHistory() { return Collections.emptyList(); }

        @Override
        public void dispose() {
            disposeEntered.countDown();
            if (blockDispose) {
                try {
                    if (!releaseDispose.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out waiting to finish captured runner disposal"); //$NON-NLS-1$
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            disposed = true;
        }

        private void finishCancelled() {
            task.complete(AgentResult.cancelled(Collections.emptyList(), 1, 1));
        }

        private void finishSuccess() {
            finishSuccess(Collections.emptyList());
        }

        private void finishSuccess(List<LlmMessage> history) {
            state = AgentState.COMPLETED;
            task.complete(AgentResult.success("ok", history, 1, 1, 1)); //$NON-NLS-1$
        }
    }

    private static final class SynchronousCancelRunner implements IAgentRunner {
        private final AgentSessionController controller;
        private final Object controllerLock;
        private final CompletableFuture<AgentResult> task = new CompletableFuture<>();
        private final CountDownLatch cancelCompletionReturned = new CountDownLatch(1);
        private final CountDownLatch allowCancelReturn = new CountDownLatch(1);
        private final AtomicBoolean disposeHeldLock = new AtomicBoolean();
        private final AtomicBoolean disposeReentered = new AtomicBoolean();
        private volatile boolean disposed;

        private SynchronousCancelRunner(AgentSessionController controller, Object controllerLock) {
            this.controller = controller;
            this.controllerLock = controllerLock;
        }

        @Override public CompletableFuture<AgentResult> run(String prompt, AgentConfig config) { return task; }
        @Override public CompletableFuture<AgentResult> run(
                String prompt, List<LlmMessage> history, AgentConfig config) { return task; }

        @Override
        public void cancel() {
            task.complete(AgentResult.cancelled(
                    List.of(LlmMessage.user("stale cancellation history")), 1, 1)); //$NON-NLS-1$
            cancelCompletionReturned.countDown();
            try {
                if (!allowCancelReturn.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out waiting to commit reset"); //$NON-NLS-1$
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }

        @Override public AgentState getState() { return AgentState.RUNNING; }
        @Override public void addListener(IAgentEventListener listener) { }
        @Override public void removeListener(IAgentEventListener listener) { }
        @Override public int getCurrentStep() { return 1; }
        @Override public List<LlmMessage> getConversationHistory() { return List.of(); }

        @Override
        public void dispose() {
            disposeHeldLock.set(Thread.holdsLock(controllerLock));
            controller.stopFromDesktop();
            disposeReentered.set(controller.getSessionId() != null);
            disposed = true;
        }
    }

    private static final class DelayedRunReturnRunner implements IAgentRunner {
        private final CompletableFuture<AgentResult> task = new CompletableFuture<>();
        private final CountDownLatch runEntered = new CountDownLatch(1);
        private final CountDownLatch allowRunReturn = new CountDownLatch(1);
        private volatile boolean cancelled;
        private volatile boolean disposed;
        private volatile IAgentEventListener listener;

        @Override
        public CompletableFuture<AgentResult> run(String prompt, AgentConfig config) {
            runEntered.countDown();
            try {
                if (!allowRunReturn.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out waiting to return old task"); //$NON-NLS-1$
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            return task;
        }

        @Override public CompletableFuture<AgentResult> run(
                String prompt, List<LlmMessage> history, AgentConfig config) { return run(prompt, config); }
        @Override public void cancel() { cancelled = true; }
        @Override public AgentState getState() { return AgentState.RUNNING; }
        @Override public void addListener(IAgentEventListener listener) { this.listener = listener; }
        @Override public void removeListener(IAgentEventListener listener) {
            if (this.listener == listener) {
                this.listener = null;
            }
        }
        @Override public int getCurrentStep() { return 1; }
        @Override public List<LlmMessage> getConversationHistory() { return List.of(); }
        @Override public void dispose() { disposed = true; }

        private void finishCancelled() {
            task.complete(AgentResult.cancelled(
                    List.of(LlmMessage.user("old task history")), 1, 1)); //$NON-NLS-1$
        }

        private void emitCompleted(AgentResult result) {
            IAgentEventListener captured = listener;
            if (captured != null) {
                captured.onEvent(new AgentCompletedEvent(result));
            }
        }
    }

    private static final class NoopProvider implements ILlmProvider {
        @Override public String getId() { return "test"; } //$NON-NLS-1$
        @Override public String getDisplayName() { return "test"; } //$NON-NLS-1$
        @Override public boolean isConfigured() { return true; }
        @Override public boolean supportsStreaming() { return false; }
        @Override public CompletableFuture<LlmResponse> complete(LlmRequest request) {
            return CompletableFuture.completedFuture(LlmResponse.of("ok")); //$NON-NLS-1$
        }
        @Override public void streamComplete(LlmRequest request, Consumer<LlmStreamChunk> consumer) { }
        @Override public void cancel() { }
        @Override public void dispose() { }
    }

    private static final class LockCheckingRunner implements IAgentRunner {
        private final Object controllerLock;
        private final CompletableFuture<AgentResult> task = new CompletableFuture<>();
        private final AtomicBoolean addListenerHeldLock = new AtomicBoolean();
        private final AtomicBoolean cancelHeldLock = new AtomicBoolean();
        private final AtomicBoolean removeListenerHeldLock = new AtomicBoolean();
        private final AtomicBoolean disposeHeldLock = new AtomicBoolean();

        private LockCheckingRunner(Object controllerLock) {
            this.controllerLock = controllerLock;
        }

        @Override public CompletableFuture<AgentResult> run(String prompt, AgentConfig config) { return task; }
        @Override public CompletableFuture<AgentResult> run(
                String prompt, List<LlmMessage> history, AgentConfig config) { return task; }
        @Override public void cancel() { cancelHeldLock.set(Thread.holdsLock(controllerLock)); }
        @Override public AgentState getState() { return AgentState.RUNNING; }
        @Override public void addListener(IAgentEventListener listener) {
            addListenerHeldLock.set(Thread.holdsLock(controllerLock));
        }
        @Override public void removeListener(IAgentEventListener listener) {
            removeListenerHeldLock.set(Thread.holdsLock(controllerLock));
        }
        @Override public int getCurrentStep() { return 0; }
        @Override public List<LlmMessage> getConversationHistory() { return List.of(); }
        @Override public void dispose() { disposeHeldLock.set(Thread.holdsLock(controllerLock)); }

        private void finishSuccess() {
            task.complete(AgentResult.success("ok", List.of(), 1, 0, 1)); //$NON-NLS-1$
        }
    }

    private static final class CompletedRunner implements IAgentRunner {
        private final CompletableFuture<AgentResult> task;
        private final boolean writeCodeMd;
        private final AtomicInteger runInvocationCount = new AtomicInteger();
        private final AtomicInteger removeListenerCount = new AtomicInteger();
        private final AtomicInteger disposeCount = new AtomicInteger();
        private volatile boolean listenerHandlesConfirmations;

        private CompletedRunner(String response, boolean writeCodeMd) {
            task = CompletableFuture.completedFuture(
                    AgentResult.success(response, Collections.emptyList(), 1, 1, 1));
            this.writeCodeMd = writeCodeMd;
        }

        @Override
        public CompletableFuture<AgentResult> run(String prompt, AgentConfig config) {
            runInvocationCount.incrementAndGet();
            if (writeCodeMd) {
                try {
                    Files.writeString(Path.of(config.getProjectPath()).resolve("Code.md"), //$NON-NLS-1$
                            "# Completed synchronously\n"); //$NON-NLS-1$
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            }
            return task;
        }
        @Override public CompletableFuture<AgentResult> run(
                String prompt, List<LlmMessage> history, AgentConfig config) { return run(prompt, config); }
        @Override public void cancel() { }
        @Override public AgentState getState() { return AgentState.COMPLETED; }
        @Override public void addListener(IAgentEventListener listener) {
            listenerHandlesConfirmations = listener.handlesConfirmations();
        }
        @Override public void removeListener(IAgentEventListener listener) { removeListenerCount.incrementAndGet(); }
        @Override public int getCurrentStep() { return 1; }
        @Override public List<LlmMessage> getConversationHistory() { return Collections.emptyList(); }
        @Override public void dispose() { disposeCount.incrementAndGet(); }
    }
}
