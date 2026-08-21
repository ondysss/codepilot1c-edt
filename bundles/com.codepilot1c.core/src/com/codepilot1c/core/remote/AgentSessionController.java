package com.codepilot1c.core.remote;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

import com.codepilot1c.core.agent.AgentConfig;
import com.codepilot1c.core.agent.AgentResult;
import com.codepilot1c.core.agent.AgentState;
import com.codepilot1c.core.agent.IAgentRunner;
import com.codepilot1c.core.agent.events.AgentCompletedEvent;
import com.codepilot1c.core.agent.events.AgentEvent;
import com.codepilot1c.core.agent.events.AgentStartedEvent;
import com.codepilot1c.core.agent.events.AgentStepEvent;
import com.codepilot1c.core.agent.events.ConfirmationRequiredEvent;
import com.codepilot1c.core.agent.events.IAgentEventListener;
import com.codepilot1c.core.agent.events.StreamChunkEvent;
import com.codepilot1c.core.agent.events.ToolCallEvent;
import com.codepilot1c.core.agent.events.ToolResultEvent;
import com.codepilot1c.core.agent.langgraph.LangGraphAgentRunner;
import com.codepilot1c.core.agent.profiles.AgentProfile;
import com.codepilot1c.core.agent.profiles.AgentProfileRegistry;
import com.codepilot1c.core.internal.VibeCorePlugin;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.provider.ILlmProvider;
import com.codepilot1c.core.provider.LlmProviderRegistry;
import com.codepilot1c.core.tools.ToolRegistry;

/**
 * Shared agent orchestration service for desktop UI and remote web companion.
 */
public class AgentSessionController {

    public static final String DESKTOP_CLIENT_ID = "desktop-ui"; //$NON-NLS-1$

    public interface RemoteEventListener {
        void onRemoteEvent(RemoteEvent event);
    }

    @FunctionalInterface
    interface RunnerFactory {
        IAgentRunner create(ILlmProvider provider, ToolRegistry registry, String systemPromptAddition);
    }

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(AgentSessionController.class);
    private static final int MAX_EVENTS = 2_000;

    private static AgentSessionController instance;

    private final Object lock = new Object();
    private final List<IAgentEventListener> agentListeners = new CopyOnWriteArrayList<>();
    private final List<RemoteEventListener> remoteListeners = new CopyOnWriteArrayList<>();
    private final Deque<RemoteEvent> eventLog = new ArrayDeque<>();

    private long nextSequence = 1L;
    private String sessionId = UUID.randomUUID().toString();
    private String controllerClientId;
    private String currentProfileId = AgentProfileRegistry.getInstance().getDefaultProfile().getId();
    private List<LlmMessage> conversationHistory = new ArrayList<>();
    private IAgentRunner activeRunner;
    private CompletableFuture<AgentResult> activeTask;
    // Keeps reentrant stop/reset calls from cancelling a runner from its own cleanup callback.
    private IAgentRunner runnerBeingCleaned;
    private ConfirmationRequiredEvent pendingAgentConfirmation;
    private PendingRemoteAction pendingRemoteAction;
    private AgentState currentState = AgentState.IDLE;
    private String lastErrorMessage;
    // A submission may commit only in the reset epoch in which it was prepared.
    private long resetEpoch;
    private boolean resetInProgress;
    private RunnerFactory runnerFactory = LangGraphAgentRunner::new;
    private Supplier<ToolRegistry> toolRegistrySupplier = ToolRegistry::getInstance;
    private Supplier<ILlmProvider> providerSupplier =
            () -> LlmProviderRegistry.getInstance().getActiveProvider();
    private Function<AgentProfile, AgentConfig> configFactory =
            profile -> AgentProfileRegistry.getInstance().createConfig(profile);

    public static synchronized AgentSessionController getInstance() {
        if (instance == null) {
            instance = new AgentSessionController();
        }
        return instance;
    }

    public void addAgentListener(IAgentEventListener listener) {
        if (listener != null) {
            agentListeners.add(listener);
        }
    }

    public void removeAgentListener(IAgentEventListener listener) {
        agentListeners.remove(listener);
    }

    public void addRemoteEventListener(RemoteEventListener listener, long fromSequence) {
        if (listener == null) {
            return;
        }
        List<RemoteEvent> backlog;
        synchronized (lock) {
            remoteListeners.add(listener);
            backlog = eventLog.stream()
                    .filter(event -> event.getSequence() > fromSequence)
                    .toList();
        }
        backlog.forEach(listener::onRemoteEvent);
    }

    public void removeRemoteEventListener(RemoteEventListener listener) {
        remoteListeners.remove(listener);
    }

    public List<RemoteEvent> getEventsAfter(long fromSequence) {
        synchronized (lock) {
            return eventLog.stream()
                    .filter(event -> event.getSequence() > fromSequence)
                    .toList();
        }
    }

    public RemoteBootstrapResponse buildBootstrap(String clientId, IdeSnapshot ideSnapshot) {
        String capturedSessionId;
        String capturedControllerClientId;
        AgentState capturedState;
        int historySize;
        String capturedProfileId;
        String capturedError;
        boolean capturedRunning;
        PendingRemoteAction remoteAction;
        ConfirmationRequiredEvent agentConfirmation;
        synchronized (lock) {
            capturedSessionId = sessionId;
            capturedControllerClientId = controllerClientId;
            capturedState = currentState;
            historySize = conversationHistory.size();
            capturedProfileId = currentProfileId;
            capturedError = lastErrorMessage;
            capturedRunning = activeRunner != null || activeTask != null;
            remoteAction = pendingRemoteAction;
            agentConfirmation = pendingAgentConfirmation;
        }
        return new RemoteBootstrapResponse(
                clientId,
                capturedSessionId,
                clientId != null && clientId.equals(capturedControllerClientId),
                capturedControllerClientId,
                payload(
                        "state", capturedState.name(), //$NON-NLS-1$
                        "running", Boolean.valueOf(capturedRunning), //$NON-NLS-1$
                        "profileId", capturedProfileId, //$NON-NLS-1$
                        "historySize", Integer.valueOf(historySize), //$NON-NLS-1$
                        "lastError", capturedError != null ? capturedError : "" //$NON-NLS-1$ //$NON-NLS-2$
                ),
                pendingConfirmationPayload(remoteAction, agentConfirmation),
                ideSnapshot,
                availableProfiles());
    }

    public RemoteCommandResult startNewSession(String prompt, String profileId, String clientId) {
        Objects.requireNonNull(prompt, "prompt"); //$NON-NLS-1$
        String normalizedProfile = normalizeProfile(profileId);
        long expectedResetEpoch;
        synchronized (lock) {
            if (!canControl(clientId)) {
                return leaseConflict();
            }
            if (resetInProgress) {
                return resetAdmissionUnavailable();
            }
            if (isRunning()) {
                return RemoteCommandResult.error("agent_busy", "Сессия агента уже выполняется"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            sessionId = UUID.randomUUID().toString();
            conversationHistory = new ArrayList<>();
            lastErrorMessage = null;
            currentProfileId = normalizedProfile;
            expectedResetEpoch = resetEpoch;
        }
        emitRemoteIfResetEpoch(expectedResetEpoch,
                "session_reset", payload("reason", "start")); //$NON-NLS-1$ //$NON-NLS-2$
        return submitPrompt(prompt, normalizedProfile, true, null, null,
                expectedResetEpoch).commandResult;
    }

    public RemoteCommandResult continueSession(String prompt, String profileId, String clientId) {
        Objects.requireNonNull(prompt, "prompt"); //$NON-NLS-1$
        String currentProfile;
        synchronized (lock) {
            currentProfile = currentProfileId;
        }
        String normalizedProfile = normalizeProfile(
                profileId != null && !profileId.isBlank() ? profileId : currentProfile);
        long expectedResetEpoch;
        synchronized (lock) {
            if (!canControl(clientId)) {
                return leaseConflict();
            }
            if (resetInProgress) {
                return resetAdmissionUnavailable();
            }
            if (isRunning()) {
                return RemoteCommandResult.error("agent_busy", "Сессия агента уже выполняется"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            currentProfileId = normalizedProfile;
            expectedResetEpoch = resetEpoch;
        }
        return submitPrompt(prompt, normalizedProfile, false, null, null,
                expectedResetEpoch).commandResult;
    }

    public RemoteCommandResult stop(String clientId) {
        IAgentRunner runner;
        synchronized (lock) {
            if (!canControl(clientId)) {
                return leaseConflict();
            }
            if (activeRunner == null || activeRunner == runnerBeingCleaned) {
                return RemoteCommandResult.error("no_active_run", "Нет активного запуска агента"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            runner = activeRunner;
        }
        runner.cancel();
        emitRemote("agent_stop_requested", payload("clientId", clientId)); //$NON-NLS-1$ //$NON-NLS-2$
        return RemoteCommandResult.ok("Запрошена остановка агента"); //$NON-NLS-1$
    }

    public RemoteCommandResult approvePending(String confirmationId, String clientId) {
        PendingRemoteAction remoteAction;
        ConfirmationRequiredEvent agentConfirmation;
        synchronized (lock) {
            if (!canControl(clientId)) {
                return leaseConflict();
            }
            remoteAction = pendingRemoteAction;
            agentConfirmation = pendingAgentConfirmation;
            if (remoteAction != null && matchesConfirmation(remoteAction.id, confirmationId)) {
                pendingRemoteAction = null;
            } else {
                remoteAction = null;
            }
            if (agentConfirmation != null && matchesConfirmation(confirmationId(agentConfirmation), confirmationId)) {
                pendingAgentConfirmation = null;
            } else {
                agentConfirmation = null;
            }
        }

        if (remoteAction != null) {
            return executeRemoteAction(remoteAction, true);
        }
        if (agentConfirmation != null) {
            agentConfirmation.confirm();
            emitRemote("confirmation_resolved", payload( //$NON-NLS-1$
                    "scope", "agent_tool", //$NON-NLS-1$ //$NON-NLS-2$
                    "confirmationId", confirmationId(agentConfirmation), //$NON-NLS-1$
                    "resolution", "approved")); //$NON-NLS-1$ //$NON-NLS-2$
            return RemoteCommandResult.ok("Подтверждение агента принято"); //$NON-NLS-1$
        }
        return RemoteCommandResult.error("no_pending_confirmation", "Подходящее ожидающее подтверждение не найдено"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    public RemoteCommandResult rejectPending(String confirmationId, String clientId) {
        PendingRemoteAction remoteAction;
        ConfirmationRequiredEvent agentConfirmation;
        synchronized (lock) {
            if (!canControl(clientId)) {
                return leaseConflict();
            }
            remoteAction = pendingRemoteAction;
            agentConfirmation = pendingAgentConfirmation;
            if (remoteAction != null && matchesConfirmation(remoteAction.id, confirmationId)) {
                pendingRemoteAction = null;
            } else {
                remoteAction = null;
            }
            if (agentConfirmation != null && matchesConfirmation(confirmationId(agentConfirmation), confirmationId)) {
                pendingAgentConfirmation = null;
            } else {
                agentConfirmation = null;
            }
        }

        if (remoteAction != null) {
            emitRemote("confirmation_resolved", payload( //$NON-NLS-1$
                    "scope", remoteAction.scope, //$NON-NLS-1$
                    "confirmationId", remoteAction.id, //$NON-NLS-1$
                    "resolution", "rejected")); //$NON-NLS-1$ //$NON-NLS-2$
            return RemoteCommandResult.error("rejected", "Удаленная команда отклонена управляющим клиентом"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (agentConfirmation != null) {
            agentConfirmation.deny();
            emitRemote("confirmation_resolved", payload( //$NON-NLS-1$
                    "scope", "agent_tool", //$NON-NLS-1$ //$NON-NLS-2$
                    "confirmationId", confirmationId(agentConfirmation), //$NON-NLS-1$
                    "resolution", "rejected")); //$NON-NLS-1$ //$NON-NLS-2$
            return RemoteCommandResult.ok("Подтверждение агента отклонено"); //$NON-NLS-1$
        }
        return RemoteCommandResult.error("no_pending_confirmation", "Подходящее ожидающее подтверждение не найдено"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    public RemoteCommandResult claimControllerLease(String clientId, boolean force) {
        boolean ordinaryClaim = false;
        synchronized (lock) {
            if (clientId == null || clientId.isBlank()) {
                return RemoteCommandResult.error("missing_client", "Нужно указать идентификатор клиента"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            if (controllerClientId == null || controllerClientId.equals(clientId)) {
                controllerClientId = clientId;
                ordinaryClaim = true;
            } else if (!force) {
                return leaseConflict();
            } else {
                controllerClientId = clientId;
            }
        }
        String mode = ordinaryClaim ? "claimed" : "force_takeover"; //$NON-NLS-1$ //$NON-NLS-2$
        emitRemote("lease_changed", payload("controllerClientId", clientId, "mode", mode)); //$NON-NLS-1$ //$NON-NLS-2$
        return RemoteCommandResult.ok(
                ordinaryClaim ? "Управление закреплено за клиентом" //$NON-NLS-1$
                        : "Управление принудительно передано другому клиенту", //$NON-NLS-1$
                payload("controllerClientId", clientId)); //$NON-NLS-1$
    }

    public RemoteCommandResult releaseControllerLease(String clientId) {
        synchronized (lock) {
            if (!hasControllerLease(clientId)) {
                return leaseConflict();
            }
            controllerClientId = null;
        }
        emitRemote("lease_changed", payload("controllerClientId", "", "mode", "released")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        return RemoteCommandResult.ok("Управление освобождено"); //$NON-NLS-1$
    }

    public boolean hasControllerLease(String clientId) {
        synchronized (lock) {
            return clientId != null && clientId.equals(controllerClientId);
        }
    }

    public CompletableFuture<AgentResult> submitFromDesktop(String prompt, String profileId) {
        Objects.requireNonNull(prompt, "prompt"); //$NON-NLS-1$
        String currentProfile;
        synchronized (lock) {
            currentProfile = currentProfileId;
        }
        String normalizedProfile = normalizeProfile(
                profileId != null && !profileId.isBlank() ? profileId : currentProfile);
        long expectedResetEpoch;
        synchronized (lock) {
            if (resetInProgress) {
                return failedResetAdmission();
            }
            if (isRunning()) {
                return CompletableFuture.failedFuture(new IllegalStateException("Agent session is already running")); //$NON-NLS-1$
            }
            currentProfileId = normalizedProfile;
            expectedResetEpoch = resetEpoch;
        }
        PromptSubmission submission = submitPrompt(prompt, normalizedProfile, false, null, null,
                expectedResetEpoch);
        if (!submission.commandResult.isOk()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(submission.commandResult.getMessage()));
        }
        return submission.task;
    }

    public CompletableFuture<AgentResult> submitFromDesktopFresh(String prompt, String profileId) {
        return submitFromDesktopFresh(prompt, profileId, null, null);
    }

    private CompletableFuture<AgentResult> submitFromDesktopFresh(
            String prompt, String profileId, String projectPath, String owningSessionId) {
        Objects.requireNonNull(prompt, "prompt"); //$NON-NLS-1$
        String currentProfile;
        synchronized (lock) {
            currentProfile = currentProfileId;
        }
        String normalizedProfile = normalizeProfile(
                profileId != null && !profileId.isBlank() ? profileId : currentProfile);
        String profileToUse;
        long expectedResetEpoch;
        synchronized (lock) {
            if (resetInProgress) {
                return failedResetAdmission();
            }
            if (isRunning()) {
                return CompletableFuture.failedFuture(new IllegalStateException("Agent session is already running")); //$NON-NLS-1$
            }
            sessionId = UUID.randomUUID().toString();
            conversationHistory = new ArrayList<>();
            pendingAgentConfirmation = null;
            pendingRemoteAction = null;
            activeRunner = null;
            activeTask = null;
            currentState = AgentState.IDLE;
            lastErrorMessage = null;
            profileToUse = normalizedProfile;
            currentProfileId = profileToUse;
            expectedResetEpoch = resetEpoch;
        }
        emitRemoteIfResetEpoch(expectedResetEpoch,
                "session_reset", payload("reason", "desktop_fresh")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        PromptSubmission submission = submitPrompt(
                prompt, profileToUse, true, projectPath, owningSessionId,
                expectedResetEpoch);
        if (!submission.commandResult.isOk()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException(submission.commandResult.getCode() + ": " //$NON-NLS-1$
                            + submission.commandResult.getMessage()));
        }
        return submission.task;
    }

    /**
     * Starts a desktop run whose returned cancellation handle owns only the
     * exact runner created by this submission. This keeps independent UI
     * clients from cancelling whichever desktop run happens to be global now.
     */
    public CompletableFuture<AgentResult> submitFromDesktopFreshScoped(String prompt, String profileId) {
        return submitFromDesktopFreshScoped(prompt, profileId, null, null);
    }

    public CompletableFuture<AgentResult> submitFromDesktopFreshScoped(
            String prompt, String profileId, String projectPath, String owningSessionId) {
        CompletableFuture<AgentResult> task = submitFromDesktopFresh(
                prompt, profileId, projectPath, owningSessionId);
        ScopedTaskFuture<AgentResult> scoped = new ScopedTaskFuture<>(() -> stopFromDesktop(task));
        task.whenComplete((result, error) -> {
            if (error != null) {
                scoped.completeExceptionally(error);
            } else {
                scoped.complete(result);
            }
        });
        return scoped;
    }

    public void stopFromDesktop() {
        IAgentRunner runner;
        synchronized (lock) {
            runner = activeRunner != runnerBeingCleaned ? activeRunner : null;
        }
        if (runner != null) {
            runner.cancel();
        }
    }

    private void stopFromDesktop(CompletableFuture<AgentResult> expectedTask) {
        IAgentRunner runner;
        synchronized (lock) {
            runner = activeTask == expectedTask && activeRunner != runnerBeingCleaned
                    ? activeRunner : null;
        }
        if (runner != null) {
            runner.cancel();
        }
    }

    private static final class ScopedTaskFuture<T> extends CompletableFuture<T> {

        private final Runnable cancelAction;

        private ScopedTaskFuture(Runnable cancelAction) {
            this.cancelAction = cancelAction;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled) {
                cancelAction.run();
            }
            return cancelled;
        }
    }

    /** Immutable result of one exact controller submission attempt. */
    private static final class PromptSubmission {
        private final RemoteCommandResult commandResult;
        private final CompletableFuture<AgentResult> task;

        private PromptSubmission(RemoteCommandResult commandResult,
                CompletableFuture<AgentResult> task) {
            this.commandResult = commandResult;
            this.task = task;
        }

        private static PromptSubmission rejected(RemoteCommandResult result) {
            return new PromptSubmission(result, null);
        }

        private static PromptSubmission accepted(RemoteCommandResult result,
                CompletableFuture<AgentResult> task) {
            return new PromptSubmission(result, task);
        }
    }

    public void resetSession(String reason) {
        IAgentRunner ownedRunner;
        IAgentRunner runnerToCancel;
        long ownedResetEpoch;
        synchronized (lock) {
            // This is the reset linearization point: older submissions and events
            // can no longer acquire or mutate the session slot.
            ownedResetEpoch = ++resetEpoch;
            resetInProgress = true;
            ownedRunner = activeRunner;
            runnerToCancel = ownedRunner != runnerBeingCleaned ? ownedRunner : null;
        }
        RuntimeException cancellationFailure = null;
        try {
            if (runnerToCancel != null) {
                runnerToCancel.cancel();
            }
        } catch (RuntimeException e) {
            cancellationFailure = e;
        }

        RemoteEvent resetEvent = null;
        synchronized (lock) {
            if (resetEpoch == ownedResetEpoch) {
                conversationHistory = new ArrayList<>();
                pendingAgentConfirmation = null;
                pendingRemoteAction = null;
                currentState = AgentState.IDLE;
                lastErrorMessage = null;
                sessionId = UUID.randomUUID().toString();
                // Exact identity prevents a superseded reset from clearing a run
                // admitted by a newer epoch.
                if (activeRunner == ownedRunner) {
                    activeRunner = null;
                    activeTask = null;
                }
                resetEvent = appendRemoteEventLocked("session_reset", payload( //$NON-NLS-1$
                        "reason", reason != null ? reason : "manual")); //$NON-NLS-1$ //$NON-NLS-2$
                resetInProgress = false;
            }
        }
        dispatchRemoteEvent(resetEvent);
        if (cancellationFailure != null) {
            throw cancellationFailure;
        }
    }

    public String getControllerClientId() {
        synchronized (lock) {
            return controllerClientId;
        }
    }

    public String getSessionId() {
        synchronized (lock) {
            return sessionId;
        }
    }

    public AgentState getCurrentState() {
        synchronized (lock) {
            return currentState;
        }
    }

    public boolean isRunning() {
        synchronized (lock) {
            return activeRunner != null || activeTask != null;
        }
    }

    public Map<String, Object> currentPendingConfirmation() {
        PendingRemoteAction remoteAction;
        ConfirmationRequiredEvent agentConfirmation;
        synchronized (lock) {
            remoteAction = pendingRemoteAction;
            agentConfirmation = pendingAgentConfirmation;
        }
        return pendingConfirmationPayload(remoteAction, agentConfirmation);
    }

    private Map<String, Object> pendingConfirmationPayload(
            PendingRemoteAction remoteAction,
            ConfirmationRequiredEvent agentConfirmation) {
        if (remoteAction != null) {
            return remoteAction.toPayload();
        }
        if (agentConfirmation == null) {
            return Map.of();
        }
        return payload(
                "scope", "agent_tool", //$NON-NLS-1$ //$NON-NLS-2$
                "confirmationId", confirmationId(agentConfirmation), //$NON-NLS-1$
                "toolName", agentConfirmation.getToolName(), //$NON-NLS-1$
                "description", agentConfirmation.getToolDescription(), //$NON-NLS-1$
                "arguments", agentConfirmation.getArguments() != null //$NON-NLS-1$
                        ? agentConfirmation.getArguments() : Map.of(),
                "destructive", Boolean.valueOf(agentConfirmation.isDestructive())); //$NON-NLS-1$
    }

    public IdeSnapshot captureIdeSnapshot() {
        IRemoteWorkbenchBridge bridge = bridge();
        if (bridge == null) {
            return IdeSnapshot.unavailable("Мост удаленного workbench недоступен"); //$NON-NLS-1$
        }
        try {
            return bridge.captureSnapshot();
        } catch (Exception e) {
            LOG.warn("Failed to capture IDE snapshot: %s", e.getMessage()); //$NON-NLS-1$
            return IdeSnapshot.unavailable(e.getMessage());
        }
    }

    public RemoteCommandResult openFile(String clientId, String path) {
        return executeImmediateBridge(clientId, "ide_open_file", payload("path", path), false, () -> bridge().openFile(path)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    public RemoteCommandResult revealRange(String clientId, String path, int startLine, int startColumn, int endLine, int endColumn) {
        return executeImmediateBridge(clientId, "ide_reveal_range", payload( //$NON-NLS-1$
                "path", path, //$NON-NLS-1$
                "startLine", Integer.valueOf(startLine), //$NON-NLS-1$
                "startColumn", Integer.valueOf(startColumn), //$NON-NLS-1$
                "endLine", Integer.valueOf(endLine), //$NON-NLS-1$
                "endColumn", Integer.valueOf(endColumn)), //$NON-NLS-1$
                false,
                () -> bridge().revealRange(path, startLine, startColumn, endLine, endColumn));
    }

    public RemoteCommandResult getSelection(String clientId) {
        return executeImmediateBridge(clientId, "ide_get_selection", Map.of(), false, () -> bridge().getSelection()); //$NON-NLS-1$
    }

    public RemoteCommandResult replaceSelection(String clientId, String text) {
        return queueBridgeConfirmation(clientId, "ide_replace_selection", payload("text", text), //$NON-NLS-1$ //$NON-NLS-2$
                "Заменить текущее выделение редактора", () -> bridge().replaceSelection(text)); //$NON-NLS-1$
    }

    public RemoteCommandResult insertAtCursor(String clientId, String text) {
        return queueBridgeConfirmation(clientId, "ide_insert_at_cursor", payload("text", text), //$NON-NLS-1$ //$NON-NLS-2$
                "Вставить текст в текущую позицию курсора", () -> bridge().insertAtCursor(text)); //$NON-NLS-1$
    }

    public RemoteCommandResult applyGeneratedCode(String clientId, String response, boolean replaceSelection) {
        return queueBridgeConfirmation(clientId, "ide_apply_generated_code", payload( //$NON-NLS-1$
                "replaceSelection", Boolean.valueOf(replaceSelection)), //$NON-NLS-1$
                "Применить сгенерированный код в редакторе", () -> bridge().applyGeneratedCode(response, replaceSelection)); //$NON-NLS-1$
    }

    public RemoteCommandResult showView(String clientId, String viewId) {
        return executeImmediateBridge(clientId, "ide_show_view", payload("viewId", viewId), false, () -> bridge().showView(viewId)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    public RemoteCommandResult activatePart(String clientId, String partId) {
        return executeImmediateBridge(clientId, "ide_activate_part", payload("partId", partId), false, () -> bridge().activatePart(partId)); //$NON-NLS-1$ //$NON-NLS-2$
    }

    public RemoteCommandResult executeWorkbenchCommand(String clientId, String commandId, Map<String, Object> parameters) {
        if (!canControl(clientId)) {
            return leaseConflict();
        }
        RemoteCommandResult validation = validateWorkbenchCommand(commandId);
        if (!validation.isOk()) {
            return validation;
        }
        return queueBridgeConfirmation(clientId, "workbench_command", payload( //$NON-NLS-1$
                "commandId", commandId, //$NON-NLS-1$
                "parameters", parameters != null ? parameters : Map.of()), //$NON-NLS-1$
                "Выполнить команду Eclipse " + commandId, () -> bridge().executeCommand(commandId, parameters)); //$NON-NLS-1$
    }

    private PromptSubmission submitPrompt(String prompt, String profileId, boolean startingFreshSession,
            String projectPath, String owningSessionId, long expectedResetEpoch) {
        ILlmProvider provider = providerSupplier.get();
        if (provider == null || !provider.isConfigured()) {
            return PromptSubmission.rejected(RemoteCommandResult.error(
                    "provider_unavailable", "LLM-провайдер не настроен")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        AgentProfile profile = AgentProfileRegistry.getInstance()
                .getProfile(profileId)
                .orElse(AgentProfileRegistry.getInstance().getDefaultProfile());
        AgentConfig baseConfig = configFactory.apply(profile);
        boolean hasProjectPath = projectPath != null && !projectPath.isBlank();
        boolean hasOwningSession = owningSessionId != null && !owningSessionId.isBlank();
        if (hasProjectPath != hasOwningSession) {
            return PromptSubmission.rejected(RemoteCommandResult.error(
                    "invalid_execution_identity", //$NON-NLS-1$
                    "Project and session identity must be supplied together")); //$NON-NLS-1$
        }
        AgentConfig config = hasProjectPath
                ? AgentConfig.builder().from(baseConfig)
                        .executionIdentity(projectPath, owningSessionId)
                        .build()
                : baseConfig;
        IAgentRunner createdRunner = null;
        try {
            ToolRegistry registry = toolRegistrySupplier.get();
            createdRunner = runnerFactory.create(
                    provider, registry, baseConfig.getSystemPromptAddition());
        } catch (RuntimeException e) {
            return PromptSubmission.rejected(
                    RemoteCommandResult.error("agent_start_failed", e.getMessage())); //$NON-NLS-1$
        }
        IAgentRunner submittedRunner = createdRunner;
        RunForwardingListener runListener = new RunForwardingListener(submittedRunner);
        try {
            submittedRunner.addListener(runListener);
        } catch (RuntimeException e) {
            try {
                cleanupRunner(submittedRunner, runListener);
            } catch (RuntimeException cleanupFailure) {
                e.addSuppressed(cleanupFailure);
            }
            return PromptSubmission.rejected(
                    RemoteCommandResult.error("agent_start_failed", e.getMessage())); //$NON-NLS-1$
        }

        List<LlmMessage> historySnapshot;
        boolean accepted;
        boolean resetPreventedAdmission;
        String admittedSessionId;
        synchronized (lock) {
            resetPreventedAdmission = resetInProgress || resetEpoch != expectedResetEpoch;
            accepted = !resetPreventedAdmission && activeRunner == null && activeTask == null;
            if (accepted) {
                historySnapshot = new ArrayList<>(conversationHistory);
                activeRunner = submittedRunner;
                currentState = AgentState.RUNNING;
                admittedSessionId = sessionId;
                runListener.activate(expectedResetEpoch);
            } else {
                historySnapshot = List.of();
                admittedSessionId = null;
            }
        }
        if (!accepted) {
            cleanupRunner(submittedRunner, runListener);
            return PromptSubmission.rejected(resetPreventedAdmission
                    ? resetAdmissionUnavailable()
                    : RemoteCommandResult.error(
                            "agent_busy", "Сессия агента уже выполняется")); //$NON-NLS-1$ //$NON-NLS-2$
        }

        CompletableFuture<AgentResult> submittedTask;
        try {
            submittedTask = startingFreshSession
                    ? submittedRunner.run(prompt, config)
                    : submittedRunner.run(prompt, historySnapshot, config);
        } catch (RuntimeException e) {
            markRunnerCleanup(submittedRunner);
            try {
                cleanupRunner(submittedRunner, runListener);
            } finally {
                synchronized (lock) {
                    if (activeRunner == submittedRunner && activeTask == null) {
                        activeRunner = null;
                        if (!resetInProgress && resetEpoch == expectedResetEpoch) {
                            currentState = AgentState.ERROR;
                            lastErrorMessage = e.getMessage();
                        }
                    }
                    clearRunnerCleanupLocked(submittedRunner);
                }
            }
            return PromptSubmission.rejected(
                    RemoteCommandResult.error("agent_start_failed", e.getMessage())); //$NON-NLS-1$
        }

        synchronized (lock) {
            if (activeRunner == submittedRunner && activeTask == null) {
                activeTask = submittedTask;
            }
        }

        submittedTask.whenComplete((result, error) -> {
            markRunnerCleanup(submittedRunner);
            try {
                cleanupRunner(submittedRunner, runListener);
            } finally {
                synchronized (lock) {
                    if (activeRunner == submittedRunner && activeTask == submittedTask) {
                        if (!resetInProgress && resetEpoch == expectedResetEpoch) {
                            if (result != null) {
                                conversationHistory = new ArrayList<>(result.getConversationHistory());
                                currentState = result.getFinalState();
                                lastErrorMessage = result.getErrorMessage();
                            } else if (error != null) {
                                currentState = AgentState.ERROR;
                                lastErrorMessage = error.getMessage();
                            }
                        }
                        activeRunner = null;
                        activeTask = null;
                    }
                    clearRunnerCleanupLocked(submittedRunner);
                }
            }
        });

        emitRemoteIfResetEpoch(expectedResetEpoch,
                startingFreshSession ? "agent_start_requested" : "agent_input_requested", payload( //$NON-NLS-1$ //$NON-NLS-2$
                "profileId", profile.getId(), //$NON-NLS-1$
                "promptLength", Integer.valueOf(prompt.length()))); //$NON-NLS-1$

        return PromptSubmission.accepted(
                RemoteCommandResult.accepted("agent_started", "Запуск агента начат", payload( //$NON-NLS-1$ //$NON-NLS-2$
                        "sessionId", admittedSessionId, //$NON-NLS-1$
                        "profileId", profile.getId())), //$NON-NLS-1$
                submittedTask);
    }

    private void cleanupRunner(IAgentRunner runner, IAgentEventListener listener) {
        try {
            runner.removeListener(listener);
        } finally {
            runner.dispose();
        }
    }

    private void markRunnerCleanup(IAgentRunner runner) {
        synchronized (lock) {
            if (activeRunner == runner) {
                runnerBeingCleaned = runner;
            }
        }
    }

    private void clearRunnerCleanupLocked(IAgentRunner runner) {
        if (runnerBeingCleaned == runner) {
            runnerBeingCleaned = null;
        }
    }

    private void handleAgentEvent(AgentEvent event) {
        handleAgentEvent(event, null, -1L);
    }

    private void handleAgentEvent(AgentEvent event, IAgentRunner eventRunner, long runResetEpoch) {
        if (event == null) {
            return;
        }

        String remoteType = null;
        Map<String, Object> remotePayload = Map.of();
        if (event instanceof ConfirmationRequiredEvent confirmationEvent) {
            remoteType = "confirmation_required"; //$NON-NLS-1$
            remotePayload = payload(
                    "scope", "agent_tool", //$NON-NLS-1$ //$NON-NLS-2$
                    "confirmationId", confirmationId(confirmationEvent), //$NON-NLS-1$
                    "toolName", confirmationEvent.getToolName(), //$NON-NLS-1$
                    "description", confirmationEvent.getToolDescription(), //$NON-NLS-1$
                    "arguments", confirmationEvent.getArguments() != null ? confirmationEvent.getArguments() : Map.of(), //$NON-NLS-1$
                    "destructive", Boolean.valueOf(confirmationEvent.isDestructive())); //$NON-NLS-1$
        } else if (event instanceof AgentStartedEvent startedEvent) {
            remoteType = "agent_started"; //$NON-NLS-1$
            remotePayload = payload(
                    "prompt", startedEvent.getPrompt(), //$NON-NLS-1$
                    "profileId", startedEvent.getConfig() != null ? startedEvent.getConfig().getProfileName() : ""); //$NON-NLS-1$ //$NON-NLS-2$
        } else if (event instanceof AgentStepEvent stepEvent) {
            remoteType = "agent_step"; //$NON-NLS-1$
            remotePayload = payload(
                    "step", Integer.valueOf(stepEvent.getStep()), //$NON-NLS-1$
                    "maxSteps", Integer.valueOf(stepEvent.getMaxSteps()), //$NON-NLS-1$
                    "description", stepEvent.getDescription()); //$NON-NLS-1$
        } else if (event instanceof ToolCallEvent toolCallEvent) {
            remoteType = "tool_call"; //$NON-NLS-1$
            remotePayload = payload(
                    "toolName", toolCallEvent.getToolName(), //$NON-NLS-1$
                    "callId", toolCallEvent.getCallId(), //$NON-NLS-1$
                    "arguments", toolCallEvent.getParsedArguments() != null ? toolCallEvent.getParsedArguments() : Map.of(), //$NON-NLS-1$
                    "requiresConfirmation", Boolean.valueOf(toolCallEvent.isRequiresConfirmation())); //$NON-NLS-1$
        } else if (event instanceof ToolResultEvent toolResultEvent) {
            remoteType = "tool_result"; //$NON-NLS-1$
            remotePayload = payload(
                    "toolName", toolResultEvent.getToolName(), //$NON-NLS-1$
                    "callId", toolResultEvent.getCallId(), //$NON-NLS-1$
                    "success", Boolean.valueOf(toolResultEvent.isSuccess()), //$NON-NLS-1$
                    "executionTimeMs", Long.valueOf(toolResultEvent.getExecutionTimeMs()), //$NON-NLS-1$
                    "content", toolResultEvent.getResult().isSuccess() //$NON-NLS-1$
                            ? toolResultEvent.getResult().getContent()
                            : toolResultEvent.getResult().getErrorMessage());
        } else if (event instanceof StreamChunkEvent streamChunkEvent) {
            remoteType = streamChunkEvent.isComplete() ? "stream_complete" : "stream_chunk"; //$NON-NLS-1$ //$NON-NLS-2$
            remotePayload = payload(
                    "content", streamChunkEvent.getContent(), //$NON-NLS-1$
                    "finishReason", streamChunkEvent.getFinishReason(), //$NON-NLS-1$
                    "complete", Boolean.valueOf(streamChunkEvent.isComplete())); //$NON-NLS-1$
        } else if (event instanceof AgentCompletedEvent completedEvent) {
            AgentResult result = completedEvent.getResult();
            remoteType = "agent_completed"; //$NON-NLS-1$
            remotePayload = payload(
                    "state", result.getFinalState().name(), //$NON-NLS-1$
                    "steps", Integer.valueOf(result.getStepsExecuted()), //$NON-NLS-1$
                    "toolCalls", Integer.valueOf(result.getToolCallsExecuted()), //$NON-NLS-1$
                    "executionTimeMs", Long.valueOf(result.getExecutionTimeMs()), //$NON-NLS-1$
                    "finalResponse", result.getFinalResponse() != null ? result.getFinalResponse() : "", //$NON-NLS-1$ //$NON-NLS-2$
                    "errorMessage", result.getErrorMessage() != null ? result.getErrorMessage() : ""); //$NON-NLS-1$ //$NON-NLS-2$
        }

        RemoteEvent remoteEvent;
        synchronized (lock) {
            if (eventRunner != null && (resetInProgress
                    || resetEpoch != runResetEpoch || activeRunner != eventRunner)) {
                return;
            }
            if (event instanceof ConfirmationRequiredEvent confirmationEvent) {
                pendingAgentConfirmation = confirmationEvent;
                currentState = AgentState.WAITING_CONFIRMATION;
            } else if (event instanceof AgentStartedEvent) {
                currentState = AgentState.RUNNING;
                pendingAgentConfirmation = null;
                lastErrorMessage = null;
            } else if (event instanceof ToolCallEvent) {
                currentState = AgentState.WAITING_TOOL;
            } else if (event instanceof ToolResultEvent) {
                currentState = AgentState.RUNNING;
            } else if (event instanceof AgentCompletedEvent completedEvent) {
                AgentResult result = completedEvent.getResult();
                currentState = result.getFinalState();
                pendingAgentConfirmation = null;
                if (result.getConversationHistory() != null) {
                    conversationHistory = new ArrayList<>(result.getConversationHistory());
                }
                lastErrorMessage = result.getErrorMessage();
            }
            remoteEvent = remoteType != null
                    ? appendRemoteEventLocked(remoteType, remotePayload)
                    : null;
        }
        dispatchRemoteEvent(remoteEvent);

        for (IAgentEventListener listener : agentListeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                LOG.warn("Agent listener failed: %s", e.getMessage()); //$NON-NLS-1$
            }
        }
    }

    private final class RunForwardingListener implements IAgentEventListener {
        private final IAgentRunner runner;
        private volatile long runResetEpoch = -1L;

        private RunForwardingListener(IAgentRunner runner) {
            this.runner = runner;
        }

        private void activate(long epoch) {
            runResetEpoch = epoch;
        }

        @Override
        public void onEvent(AgentEvent event) {
            handleAgentEvent(event, runner, runResetEpoch);
        }

        @Override
        public boolean handlesConfirmations() {
            return true;
        }
    }

    private RemoteCommandResult queueBridgeConfirmation(
            String clientId,
            String scope,
            Map<String, Object> payload,
            String description,
            Supplier<RemoteCommandResult> executor) {
        synchronized (lock) {
            if (!canControl(clientId)) {
                return leaseConflict();
            }
            if (pendingRemoteAction != null) {
                return RemoteCommandResult.error("confirmation_pending", "Уже ожидается другое удаленное подтверждение"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            pendingRemoteAction = new PendingRemoteAction(
                    LogSanitizer.newId("remote-confirm"), //$NON-NLS-1$
                    scope,
                    clientId,
                    description,
                    payload,
                    executor);
        }
        PendingRemoteAction action;
        synchronized (lock) {
            action = pendingRemoteAction;
        }
        emitRemote("confirmation_required", action != null ? action.toPayload() : Map.of()); //$NON-NLS-1$
        return RemoteCommandResult.accepted(
                "confirmation_required", //$NON-NLS-1$
                description,
                payload(
                        "confirmationId", action != null ? action.id : "", //$NON-NLS-1$
                        "scope", scope)); //$NON-NLS-1$
    }

    private RemoteCommandResult executeImmediateBridge(
            String clientId,
            String eventType,
            Map<String, Object> payload,
            boolean requireLease,
            Supplier<RemoteCommandResult> executor) {
        if (requireLease && !canControl(clientId)) {
            return leaseConflict();
        }
        IRemoteWorkbenchBridge bridge = bridge();
        if (bridge == null) {
            return RemoteCommandResult.error("bridge_unavailable", "Мост удаленного workbench недоступен"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        RemoteCommandResult result = executor.get();
        emitRemote(eventType, mergePayload(payload, payload( //$NON-NLS-1$
                "ok", Boolean.valueOf(result.isOk()), //$NON-NLS-1$
                "code", result.getCode()))); //$NON-NLS-1$
        return result;
    }

    private RemoteCommandResult validateWorkbenchCommand(String commandId) {
        if (commandId == null || commandId.isBlank()) {
            return RemoteCommandResult.error("missing_command", "Нужно указать commandId"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (RemoteWorkbenchCommandPolicy.isDenied(commandId)) {
            return RemoteCommandResult.error("command_denied", "Команда заблокирована политикой удаленного доступа: " + commandId); //$NON-NLS-1$ //$NON-NLS-2$
        }
        IRemoteWorkbenchBridge bridge = bridge();
        if (bridge == null) {
            return RemoteCommandResult.error("bridge_unavailable", "Мост удаленного workbench недоступен"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        IdeSnapshot snapshot = captureIdeSnapshot();
        if (snapshot.getCommands().isEmpty()) {
            return RemoteCommandResult.ok("Команда принята и ожидает подтверждения"); //$NON-NLS-1$
        }
        for (Map<String, Object> command : snapshot.getCommands()) {
            String id = command != null ? String.valueOf(command.getOrDefault("id", "")) : ""; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            if (!commandId.equals(id)) {
                continue;
            }
            if (Boolean.TRUE.equals(command.get("denied"))) { //$NON-NLS-1$
                return RemoteCommandResult.error("command_denied", "Команда заблокирована политикой удаленного доступа: " + commandId); //$NON-NLS-1$ //$NON-NLS-2$
            }
            return RemoteCommandResult.ok("Команда принята и ожидает подтверждения"); //$NON-NLS-1$
        }
        return RemoteCommandResult.error("invalid_command", "Неизвестная команда Eclipse: " + commandId); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private RemoteCommandResult executeRemoteAction(PendingRemoteAction action, boolean approved) {
        if (!approved) {
            return RemoteCommandResult.error("rejected", "Удаленное действие отклонено"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        RemoteCommandResult result;
        try {
            result = action.executor.get();
        } catch (Exception e) {
            result = RemoteCommandResult.error("execution_failed", e.getMessage() != null ? e.getMessage() : "Выполнение завершилось ошибкой"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        emitRemote("remote_action_result", mergePayload(action.payload, payload( //$NON-NLS-1$
                "confirmationId", action.id, //$NON-NLS-1$
                "scope", action.scope, //$NON-NLS-1$
                "ok", Boolean.valueOf(result.isOk()), //$NON-NLS-1$
                "code", result.getCode(), //$NON-NLS-1$
                "message", result.getMessage() != null ? result.getMessage() : ""))); //$NON-NLS-1$ //$NON-NLS-2$
        return result;
    }

    private Map<String, Object> mergePayload(Map<String, Object> first, Map<String, Object> second) {
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
        if (first != null) {
            merged.putAll(first);
        }
        if (second != null) {
            merged.putAll(second);
        }
        return merged;
    }

    private IRemoteWorkbenchBridge bridge() {
        VibeCorePlugin plugin = VibeCorePlugin.getDefault();
        return plugin != null ? plugin.getRemoteWorkbenchBridge() : null;
    }

    private String normalizeProfile(String profileId) {
        return AgentProfileRegistry.getInstance()
                .getProfile(profileId)
                .map(AgentProfile::getId)
                .orElseGet(() -> AgentProfileRegistry.getInstance().getDefaultProfile().getId());
    }

    private List<Map<String, Object>> availableProfiles() {
        return AgentProfileRegistry.getInstance().getAllProfiles().stream()
                .map(profile -> Map.<String, Object>of(
                        "id", profile.getId(), //$NON-NLS-1$
                        "name", profile.getName(), //$NON-NLS-1$
                        "description", profile.getDescription(), //$NON-NLS-1$
                        "readOnly", Boolean.valueOf(profile.isReadOnly()))) //$NON-NLS-1$
                .toList();
    }

    private boolean matchesConfirmation(String actualId, String requestedId) {
        return actualId != null && (requestedId == null || requestedId.isBlank() || actualId.equals(requestedId));
    }

    private boolean canControl(String clientId) {
        return DESKTOP_CLIENT_ID.equals(clientId) || hasControllerLease(clientId);
    }

    private RemoteCommandResult leaseConflict() {
        synchronized (lock) {
            return RemoteCommandResult.error("lease_conflict", "Управление уже удерживается другим клиентом", Map.of( //$NON-NLS-1$ //$NON-NLS-2$
                    "controllerClientId", controllerClientId != null ? controllerClientId : "")); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }

    private RemoteCommandResult resetAdmissionUnavailable() {
        return RemoteCommandResult.error(
                "session_reset_in_progress", //$NON-NLS-1$
                "Сброс сессии выполняется; повторите запрос после его завершения"); //$NON-NLS-1$
    }

    private CompletableFuture<AgentResult> failedResetAdmission() {
        RemoteCommandResult result = resetAdmissionUnavailable();
        return CompletableFuture.failedFuture(new IllegalStateException(
                result.getCode() + ": " + result.getMessage())); //$NON-NLS-1$
    }

    private String confirmationId(ConfirmationRequiredEvent event) {
        if (event == null || event.getToolCall() == null) {
            return ""; //$NON-NLS-1$
        }
        return event.getToolCall().getId();
    }

    private static Map<String, Object> payload(Object... keyValues) {
        if (keyValues == null || keyValues.length == 0) {
            return Map.of();
        }
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object key = keyValues[i];
            if (key == null) {
                continue;
            }
            map.put(String.valueOf(key), normalizePayloadValue(keyValues[i + 1]));
        }
        return map;
    }

    private static Object normalizePayloadValue(Object value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (key != null) {
                    normalized.put(String.valueOf(key), normalizePayloadValue(item));
                }
            });
            return normalized;
        }
        if (value instanceof List<?> list) {
            List<Object> normalized = new ArrayList<>(list.size());
            for (Object item : list) {
                normalized.add(normalizePayloadValue(item));
            }
            return normalized;
        }
        return value;
    }

    private void emitRemote(String type, Map<String, Object> payload) {
        RemoteEvent event;
        synchronized (lock) {
            event = appendRemoteEventLocked(type, payload);
        }
        dispatchRemoteEvent(event);
    }

    private void emitRemoteIfResetEpoch(long expectedResetEpoch,
            String type, Map<String, Object> payload) {
        RemoteEvent event;
        synchronized (lock) {
            event = !resetInProgress && resetEpoch == expectedResetEpoch
                    ? appendRemoteEventLocked(type, payload)
                    : null;
        }
        dispatchRemoteEvent(event);
    }

    private RemoteEvent appendRemoteEventLocked(String type, Map<String, Object> payload) {
        RemoteEvent event = new RemoteEvent(
                type, sessionId, nextSequence++, Instant.now(), payload);
        eventLog.addLast(event);
        while (eventLog.size() > MAX_EVENTS) {
            eventLog.removeFirst();
        }
        return event;
    }

    private void dispatchRemoteEvent(RemoteEvent event) {
        if (event == null) {
            return;
        }
        for (RemoteEventListener listener : remoteListeners) {
            try {
                listener.onRemoteEvent(event);
            } catch (Exception e) {
                LOG.warn("Remote event listener failed: %s", e.getMessage()); //$NON-NLS-1$
            }
        }
    }

    private static final class PendingRemoteAction {
        private final String id;
        private final String scope;
        private final String clientId;
        private final String description;
        private final Map<String, Object> payload;
        private final Supplier<RemoteCommandResult> executor;

        private PendingRemoteAction(
                String id,
                String scope,
                String clientId,
                String description,
                Map<String, Object> payload,
                Supplier<RemoteCommandResult> executor) {
            this.id = id;
            this.scope = scope;
            this.clientId = clientId;
            this.description = description;
            @SuppressWarnings("unchecked")
            Map<String, Object> sanitizedPayload = payload != null
                    ? (Map<String, Object>) normalizePayloadValue(payload)
                    : Map.of();
            this.payload = sanitizedPayload;
            this.executor = executor;
        }

        private Map<String, Object> toPayload() {
            return payload(
                    "scope", scope, //$NON-NLS-1$
                    "confirmationId", id, //$NON-NLS-1$
                    "clientId", clientId, //$NON-NLS-1$
                    "description", description, //$NON-NLS-1$
                    "payload", payload); //$NON-NLS-1$
        }
    }
}
