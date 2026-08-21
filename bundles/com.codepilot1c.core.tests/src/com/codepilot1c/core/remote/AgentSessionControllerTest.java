package com.codepilot1c.core.remote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
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
import org.junit.Test;

import com.codepilot1c.core.agent.profiles.InitAgentProfile;
import com.codepilot1c.core.agent.AgentConfig;
import com.codepilot1c.core.agent.AgentResult;
import com.codepilot1c.core.agent.AgentState;
import com.codepilot1c.core.agent.IAgentRunner;
import com.codepilot1c.core.agent.events.IAgentEventListener;
import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.model.LlmRequest;
import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.model.LlmStreamChunk;
import com.codepilot1c.core.provider.ILlmProvider;
import com.codepilot1c.core.provider.LlmProviderRegistry;

public class AgentSessionControllerTest {

    private AgentSessionController controller;
    private String cleanupClientId;
    private LlmProviderRegistry previousRegistry;
    private Object previousRunnerFactory;
    private Object previousToolRegistrySupplier;

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
        if (previousRegistry != null) {
            installRegistry(previousRegistry);
            previousRegistry = null;
        }
        if (previousRunnerFactory != null) {
            setControllerField("runnerFactory", previousRunnerFactory); //$NON-NLS-1$
            previousRunnerFactory = null;
        }
        if (previousToolRegistrySupplier != null) {
            setControllerField("toolRegistrySupplier", previousToolRegistrySupplier); //$NON-NLS-1$
            previousToolRegistrySupplier = null;
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
        previousRegistry = installRegistry(emptyInitializedRegistry());
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
        LlmProviderRegistry registry = emptyInitializedRegistry();
        legacyProviders(registry).put("test", new NoopProvider()); //$NON-NLS-1$
        previousRegistry = installRegistry(registry);

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
        secondRunner.finishSuccess();
        assertTrue(secondHandle.join().isSuccess());
        assertTrue(secondRunner.disposed);
        assertEquals(2, created.get());
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

    private static LlmProviderRegistry emptyInitializedRegistry() throws Exception {
        Constructor<LlmProviderRegistry> constructor = LlmProviderRegistry.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        LlmProviderRegistry registry = constructor.newInstance();

        Field initializedField = LlmProviderRegistry.class.getDeclaredField("initialized"); //$NON-NLS-1$
        initializedField.setAccessible(true);
        initializedField.set(registry, true);
        return registry;
    }

    private static LlmProviderRegistry installRegistry(LlmProviderRegistry registry) throws Exception {
        Field instanceField = LlmProviderRegistry.class.getDeclaredField("instance"); //$NON-NLS-1$
        instanceField.setAccessible(true);
        LlmProviderRegistry previous = (LlmProviderRegistry) instanceField.get(null);
        instanceField.set(null, registry);
        return previous;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ILlmProvider> legacyProviders(LlmProviderRegistry registry) throws Exception {
        Field field = LlmProviderRegistry.class.getDeclaredField("legacyProviders"); //$NON-NLS-1$
        field.setAccessible(true);
        return (Map<String, ILlmProvider>) field.get(registry);
    }

    private static final class FakeRunner implements IAgentRunner {
        private final CompletableFuture<AgentResult> task = new CompletableFuture<>();
        private final boolean blockDispose;
        private final CountDownLatch disposeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseDispose = new CountDownLatch(1);
        private AgentConfig config;
        private volatile AgentState state = AgentState.IDLE;
        private volatile boolean cancelled;
        private volatile boolean disposed;

        private FakeRunner(boolean blockDispose) {
            this.blockDispose = blockDispose;
        }

        @Override
        public CompletableFuture<AgentResult> run(String prompt, AgentConfig config) {
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
}
