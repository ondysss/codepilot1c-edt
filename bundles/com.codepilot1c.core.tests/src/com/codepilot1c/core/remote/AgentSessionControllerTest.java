package com.codepilot1c.core.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
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
        Field field = AgentSessionController.class.getDeclaredField("forwardingListener"); //$NON-NLS-1$
        field.setAccessible(true);
        IAgentEventListener listener = (IAgentEventListener) field.get(controller);

        assertTrue(listener.handlesConfirmations());
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
    public void alreadyCompletedTaskSucceedsForProjectMemoryFreshAndLegacyDesktopSubmissions() throws Exception {
        installProvider(new NoopProvider());

        CompletedRunner directFreshRunner = new CompletedRunner("direct-fresh", false); //$NON-NLS-1$
        CompletedRunner memoryFreshRunner = new CompletedRunner("memory-fresh", true); //$NON-NLS-1$
        CompletedRunner legacyRunner = new CompletedRunner("legacy", false); //$NON-NLS-1$
        AtomicInteger created = new AtomicInteger();
        previousRunnerFactory = controllerField("runnerFactory"); //$NON-NLS-1$
        previousToolRegistrySupplier = controllerField("toolRegistrySupplier"); //$NON-NLS-1$
        setControllerField("toolRegistrySupplier", //$NON-NLS-1$
                (java.util.function.Supplier<com.codepilot1c.core.tools.ToolRegistry>) () -> null);
        setControllerField("runnerFactory", (AgentSessionController.RunnerFactory) //$NON-NLS-1$
                (provider, tools, prompt) -> switch (created.getAndIncrement()) {
                    case 0 -> directFreshRunner;
                    case 1 -> memoryFreshRunner;
                    default -> legacyRunner;
                });

        CompletableFuture<AgentResult> directFresh = controller.submitFromDesktopFresh(
                "completed direct fresh", InitAgentProfile.ID); //$NON-NLS-1$
        assertSame(directFreshRunner.task, directFresh);
        assertEquals("direct-fresh", directFresh.join().getFinalResponse()); //$NON-NLS-1$
        assertEquals(1, directFreshRunner.runInvocationCount.get());
        assertEquals(1, directFreshRunner.removeListenerCount.get());
        assertEquals(1, directFreshRunner.disposeCount.get());

        Path projectRoot = temporaryFolder.newFolder("completed-fresh").toPath(); //$NON-NLS-1$
        ProjectMemoryInitializationService.Result initialization =
                new ProjectMemoryInitializationService().initialize(new Request(
                        Mode.CREATE, projectRoot, "CompletedFresh", //$NON-NLS-1$
                        "CompletedFresh/Code.md", "view-fresh")).join(); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(Status.SUCCESS, initialization.getStatus());
        assertEquals(1, memoryFreshRunner.runInvocationCount.get());
        assertEquals(1, memoryFreshRunner.removeListenerCount.get());
        assertEquals(1, memoryFreshRunner.disposeCount.get());

        CompletableFuture<AgentResult> legacy = controller.submitFromDesktop(
                "completed legacy", InitAgentProfile.ID); //$NON-NLS-1$
        assertNotNull(legacy);
        assertSame(legacyRunner.task, legacy);
        assertEquals("legacy", legacy.join().getFinalResponse()); //$NON-NLS-1$
        assertEquals(1, legacyRunner.runInvocationCount.get());
        assertEquals(1, legacyRunner.removeListenerCount.get());
        assertEquals(1, legacyRunner.disposeCount.get());
        assertEquals(AgentState.COMPLETED, controller.getCurrentState());
        assertEquals(3, created.get());
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
                .systemPromptAddition(profile.getSystemPromptAddition())
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
            state = AgentState.COMPLETED;
            task.complete(AgentResult.success("ok", Collections.emptyList(), 1, 1, 1)); //$NON-NLS-1$
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

    private static final class CompletedRunner implements IAgentRunner {
        private final CompletableFuture<AgentResult> task;
        private final boolean writeCodeMd;
        private final AtomicInteger runInvocationCount = new AtomicInteger();
        private final AtomicInteger removeListenerCount = new AtomicInteger();
        private final AtomicInteger disposeCount = new AtomicInteger();

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
        @Override public void addListener(IAgentEventListener listener) { }
        @Override public void removeListener(IAgentEventListener listener) { removeListenerCount.incrementAndGet(); }
        @Override public int getCurrentStep() { return 1; }
        @Override public List<LlmMessage> getConversationHistory() { return Collections.emptyList(); }
        @Override public void dispose() { disposeCount.incrementAndGet(); }
    }
}
