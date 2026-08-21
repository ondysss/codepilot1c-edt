/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.views;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.FileTransfer;
import org.eclipse.swt.dnd.ImageTransfer;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.ViewPart;

import com.codepilot1c.core.diff.CodeDiffUtils;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.agent.profiles.AgentProfile;
import com.codepilot1c.core.agent.prompts.SystemPromptAssembler;
import com.codepilot1c.core.skills.SkillMentionParser;
import com.codepilot1c.core.model.LlmAttachment;
import com.codepilot1c.core.memory.compaction.LlmCompactionService;
import com.codepilot1c.core.session.Session;
import com.codepilot1c.core.session.SessionManager;
import com.codepilot1c.core.session.SessionManager.ISessionChangeListener;
import com.codepilot1c.core.session.SessionMessage;
import com.codepilot1c.core.model.LlmContentPart;
import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.model.LlmConversationSanitizer;
import com.codepilot1c.core.model.LlmRequest;
import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.model.LlmStreamChunk;
import com.codepilot1c.core.model.ToolCall;
import com.codepilot1c.core.model.ToolDefinition;
import com.codepilot1c.core.memory.project.ProjectMemoryContextService;
import com.codepilot1c.core.memory.project.ProjectMemoryInitializationService;
import com.codepilot1c.core.provider.ILlmProvider;
import com.codepilot1c.core.provider.LlmProviderRegistry;
import com.codepilot1c.core.provider.ProviderCapabilities;
import com.codepilot1c.core.remote.AgentSessionController;
import com.codepilot1c.core.settings.VibePreferenceConstants;
import com.codepilot1c.core.permissions.PermissionManager;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.ui.ChatSystemPromptToolsSection;
import com.codepilot1c.core.ui.ChatToolGate;
import com.codepilot1c.core.util.AttachmentTextExtractor;
import com.codepilot1c.core.backend.BackendConfig;
import com.codepilot1c.core.backend.BackendService;
import com.codepilot1c.core.provider.config.LlmProviderConfig;
import com.codepilot1c.core.provider.config.LlmProviderConfigStore;
import com.codepilot1c.core.provider.config.ProviderType;
import com.codepilot1c.core.provider.config.ModelFetchService;
import com.codepilot1c.core.provider.config.ModelFetchService.ModelInfo;
import com.codepilot1c.ui.dialogs.ToolConfirmationDialog;
import com.codepilot1c.ui.diff.DiffReviewDialog;
import com.codepilot1c.ui.diff.ProposedChange;
import com.codepilot1c.ui.diff.ProposedChangeSet;
import com.codepilot1c.ui.editor.CodeApplicationService;
import com.codepilot1c.ui.gsd.GsdStatusPanel;
import com.codepilot1c.ui.gsd.GsdToolMutationRefreshPolicy;
import com.codepilot1c.ui.internal.Messages;
import com.codepilot1c.ui.internal.ToolDisplayNames;
import com.codepilot1c.ui.internal.VibeUiPlugin;
import com.codepilot1c.ui.preferences.ModelSelectionDialog;
import com.codepilot1c.ui.theme.ThemeManager;
import com.codepilot1c.ui.theme.VibeTheme;

/**
 * Chat view for interacting with AI assistant.
 *
 * <p>Features interactive code blocks with Copy/Insert/Replace buttons.</p>
 */
public class ChatView extends ViewPart {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(ChatView.class);

    public static final String ID = "com.codepilot1c.ui.views.ChatView"; //$NON-NLS-1$
    /** View ID - alias for ID for backwards compatibility */
    public static final String VIEW_ID = ID;

    /** Whether to enable tool calling (can be toggled) */
    private boolean toolsEnabled = true;

    /** Whether to use Browser-based rendering for chat messages */
    private static final boolean USE_BROWSER_RENDERING = true;
    private static final String CORE_PLUGIN_ID = "com.codepilot1c.core"; //$NON-NLS-1$
    private static final String PREF_CHAT_PROFILE_ID = "chat.profileId"; //$NON-NLS-1$
    private static final int CHAT_INPUT_HEIGHT = 92;
    private static final int CHAT_ACTION_BUTTON_HEIGHT = 30;
    private static final int CHAT_ICON_BUTTON_WIDTH = 36;
    private static final int CHAT_NEW_CHAT_BUTTON_WIDTH = 42;
    private static final int CHAT_COMPACT_BUTTON_WIDTH = 156;
    private static final int CHAT_MODEL_BUTTON_WIDTH = 148;
    private static final int CHAT_MODEL_LABEL_MAX_CHARS = 22;
    private static final int CHAT_COMPOSER_MARGIN = 12;
    private static final int CHAT_BUTTON_SPACING = 8;
    private static final int MAX_TOOL_RESULT_HISTORY_CHARS = 40_000;
    private static final String OUTPUT_LIMIT_WARNING =
            "Ответ модели достиг лимита вывода и может быть неполным. " //$NON-NLS-1$
                    + "Попросите модель продолжить с места остановки."; //$NON-NLS-1$
    private static final ProjectMemoryContextService PROJECT_MEMORY_SERVICE = new ProjectMemoryContextService();
    private static final ProjectMemoryInitializationService PROJECT_MEMORY_INIT_SERVICE =
            new ProjectMemoryInitializationService();

    private ScrolledComposite scrolledComposite;
    private Composite messagesContainer;
    private BrowserChatPanel browserChatPanel;
    private Text inputField;
    private Button sendButton;
    private Button attachButton;
    private Button clearButton;
    private Button newChatButton;
    private Button newWindowButton;
    private Button stopButton;
    private Button applyCodeButton;
    private Button initCodeMdButton;
    private Button compactButton;
    private Button modelButton;
    private String overrideModelId;
    private TypingIndicatorWidget typingIndicator;
    private Label tokenUsageLabel;
    private Composite attachmentPreviewArea;
    private GsdStatusPanel gsdStatusPanel;

    private final List<LlmMessage> conversationHistory = new ArrayList<>();
    private final List<ChatMessageComposite> messageWidgets = new ArrayList<>();
    /**
     * Phase 0/2 (chat persistence / multi-chat): this view instance's own chat session — the persisted
     * record of the conversation. Each ChatView instance owns a distinct session (multi-view). Saved on
     * close ({@link #dispose()}) and at clear-chat; restored on open via memento or most-recent.
     */
    private Session session;

    /** Memento key for the per-view session id (restored across EDT restart). */
    private static final String MEMENTO_SESSION_ID = "sessionId"; //$NON-NLS-1$
    /** Session id captured from the view memento in {@link #init}, restored in createPartControl. */
    private String restoredSessionId;
    private final List<LlmAttachment> draftAttachments = new ArrayList<>();
    /**
     * Plan 1.2: detects tool-call repetition loops (e.g., grep x 42 with the
     * same args). Reset at clear-chat and at each new user-message turn. On
     * trip, a synthetic USER message is appended to {@link #conversationHistory}
     * before dispatching the current tool call.
     */
    private final com.codepilot1c.core.agent.ToolRepetitionDetector toolRepetitionDetector =
            new com.codepilot1c.core.agent.ToolRepetitionDetector();
    private CompletableFuture<?> currentRequest;
    private boolean currentRequestUsesDesktopController;
    private boolean isProcessing = false;
    /** Skill names extracted from the latest user input via $mention syntax. */
    private List<String> currentRequestedSkills = List.of();
    private String lastAssistantResponse;

    /** Accumulated content during streaming (thread-safe) */
    private StringBuffer streamingContent;
    /** Accumulated reasoning during streaming (thread-safe) */
    private StringBuffer streamingReasoning;
    /** Whether streaming is in progress */
    private volatile boolean isStreaming = false;
    /**
     * Single-flight guard: set exactly once per streaming round-trip when the first
     * tool-calls chunk is observed. Any duplicate tool-calls chunk from the same
     * stream is dropped, preventing parallel handleResponseWithTools recursion.
     * Reset in startStreamingRequest before every new request.
     */
    private final AtomicBoolean streamingHandledToolCalls = new AtomicBoolean(false);

    /**
     * Turn-level single-flight guard (Plan 1.1). Set on entry of
     * {@link #startStreamingRequest} and {@link #startNonStreamingRequest}, cleared
     * at every outer termination point (thenAccept/exceptionally of the top-level
     * CompletableFuture chain, stream-error handler, and {@link #stopGeneration}
     * for user-cancel). NOT cleared inside {@link #handleResponseWithTools},
     * {@link #processToolCalls}, or {@link #continueAfterToolCalls}, because those
     * are part of the same logical turn and re-enter the top-level chain.
     */
    private final AtomicBoolean inflight = new AtomicBoolean(false);

    /** Monotonic round-trip id used in single-flight WARN logs and tracing. */
    private final java.util.concurrent.atomic.AtomicLong roundTripSeq = new java.util.concurrent.atomic.AtomicLong(0);
    /** Current round-trip id assigned at request entry; used for diagnostic logging. */
    private volatile long currentRoundTripId = 0;

    /**
     * Double-count guard for Plan 2.3 (real stream usage). Reset at round-trip
     * start (same place as {@link #inflight} CAS); set by the first path that
     * registers usage — either the terminal {@code stream_options.include_usage}
     * chunk or the {@link #streamToResponse} / fallback estimator path. The
     * {@link #registerUsage(LlmResponse)} call-sites that pass an estimated
     * usage are gated on this flag so a single round-trip contributes real
     * usage only once.
     */
    private volatile boolean usageRegisteredForThisRoundTrip = false;

    /** Whether to show diff preview before applying file changes */
    private boolean previewModeEnabled = false;
    /** Current set of proposed changes awaiting review */
    private ProposedChangeSet currentProposedChanges;
    /** Profile and permission policy fixed for the current chat turn. */
    private volatile ChatToolGate toolGate;
    /** Profile plus per-view prompt context fixed for the current chat turn. */
    private volatile ChatTurnContext turnContext;
    /** Session lifecycle bridge used only to request a UI-owned GSD refresh. */
    private final ISessionChangeListener sessionChangeListener = new ISessionChangeListener() {
        @Override
        public void onCurrentSessionChanged(Session oldSession, Session newSession) {
            requestGsdStatusRefresh();
        }
    };
    private boolean sessionChangeListenerRegistered;
    /** Token usage totals for current chat session */
    private long inputTokensTotal = 0;
    private long cachedInputTokensTotal = 0;
    private long outputTokensTotal = 0;
    private long totalTokensTotal = 0;
    /**
     * Count of accepted top-level round-trips in the current chat session
     * (Plan 2.4). Incremented exactly once per round-trip at the point where
     * the {@link #inflight} CAS succeeds in the streaming or non-streaming
     * request entry, and reset to 0 by {@link #resetTokenUsage()} on new chat.
     * Rendered in the compact footer via
     * {@code com.codepilot1c.core.ui.TokenFooterRenderer}.
     */
    private int requestCount = 0;
    private long lastAutoCompactAtMs = 0;
    private LlmRequest currentStreamingRequest;

    private static final int CHARS_PER_TOKEN_ESTIMATE = 4;
    private static final int AUTO_COMPACT_MIN_MESSAGES = 20;
    private static final int AUTO_COMPACT_HISTORY_TOKEN_BUDGET = 12000;
    private static final long AUTO_COMPACT_COOLDOWN_MS = 30_000L;
    private static final int COMPACT_TAIL_MESSAGES = 14;
    private static final String COMPACT_SUMMARY_MARKER = "[COMPACT_SUMMARY]"; //$NON-NLS-1$
    private static final long DEFAULT_MAX_ATTACHMENT_BYTES = 10L * 1024L * 1024L;
    private static final int DEFAULT_MAX_ATTACHMENTS = 5;
    private static final int FILE_PREVIEW_CHAR_LIMIT = 4000;

    @Override
    public void init(IViewSite site, IMemento memento) throws PartInitException {
        super.init(site, memento);
        // Capture the session id Eclipse saved for this view instance (multi-view restore).
        if (memento != null) {
            restoredSessionId = memento.getString(MEMENTO_SESSION_ID);
        }
    }

    @Override
    public void saveState(IMemento memento) {
        super.saveState(memento);
        // Persist this view's session id so Eclipse restores the exact chat on restart.
        if (session != null && memento != null) {
            memento.putString(MEMENTO_SESSION_ID, session.getId());
        }
    }

    @Override
    public void createPartControl(Composite parent) {
        VibeTheme theme = ThemeManager.getInstance().getTheme();

        Composite container = new Composite(parent, SWT.NONE);
        GridLayout containerLayout = new GridLayout(1, false);
        containerLayout.marginWidth = 0;
        containerLayout.marginHeight = 0;
        containerLayout.verticalSpacing = 0;
        container.setLayout(containerLayout);
        container.setBackground(theme.getBackground());

        createChatArea(container);
        createGsdStatusPanel(container);
        createInputArea(container);
        registerSessionChangeListener();

        // Phase 0: restore the last chat (persistence) or show the welcome message.
        restoreLastSessionOrWelcome();
    }

    private void createChatArea(Composite parent) {
        if (USE_BROWSER_RENDERING) {
            createBrowserChatArea(parent);
        } else {
            createStyledTextChatArea(parent);
        }
    }

    /**
     * Creates the GSD status panel between the chat area and the input composer.
     * The panel is read-only, collapsible, and loads state asynchronously off the UI thread.
     */
    private void createGsdStatusPanel(Composite parent) {
        gsdStatusPanel = new GsdStatusPanel(parent);
        GridData gsdData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gsdStatusPanel.getControl().setLayoutData(gsdData);

        // Select the suggested profile on this view's own persisted session.
        gsdStatusPanel.setSuggestedProfileAction(this::selectSuggestedProfile);

        // Initial refresh
        refreshGsdStatus();
    }

    /**
     * Resolves the project root for the GSD status panel from this view's own session
     * first, falling back to workspace resolution. Triggers an async reload of the
     * GSD state off the UI thread.
     */
    private void refreshGsdStatus() {
        if (gsdStatusPanel == null || gsdStatusPanel.isDisposed()) {
            return;
        }
        Path projectRoot = resolveGsdProjectRoot();
        gsdStatusPanel.setProjectRoot(projectRoot);
        gsdStatusPanel.refresh();
    }

    /** Requests a coalesced reload after lifecycle events or tool mutations. */
    private void requestGsdStatusRefresh() {
        GsdStatusPanel panel = gsdStatusPanel;
        if (panel == null || panel.isDisposed()) {
            return;
        }
        panel.setProjectRoot(resolveGsdProjectRoot());
        panel.refreshDebounced();
    }

    private void registerSessionChangeListener() {
        if (!sessionChangeListenerRegistered) {
            SessionManager.getInstance().addListener(sessionChangeListener);
            sessionChangeListenerRegistered = true;
        }
    }

    private void selectSuggestedProfile(String profileId) {
        if (isDisposed()) {
            return;
        }
        Session target = viewSession();
        if (!ChatTurnContext.selectForSession(target, profileId)) {
            LOG.warn("Unknown suggested chat profile: %s", profileId); //$NON-NLS-1$
            return;
        }
        SessionManager.getInstance().saveSession(target);
        LOG.info("Selected chat profile %s for session %s", profileId, target.getId()); //$NON-NLS-1$
        // A running turn keeps its captured gate/context. startConversationLoop
        // resolves the newly selected session profile for the next turn.
    }

    /**
     * Resolves the project root for GSD state reading. Prefers this view's own session
     * over the global {@code currentSession} to avoid cross-view interference.
     *
     * @return the project root path, or {@code null} if no project is available
     */
    private Path resolveGsdProjectRoot() {
        // 1. Try this view's own session first
        if (session != null && session.hasProject()) {
            try {
                return Path.of(session.getProjectPath()).toAbsolutePath().normalize();
            } catch (RuntimeException e) {
                LOG.debug("Invalid GSD session project path: %s", e.getMessage()); //$NON-NLS-1$
            }
        }

        // 2. Fallback: resolve from session manager (same as Code.md resolution)
        try {
            IProject project = resolveCodeMdProject();
            return GsdStatusPanel.resolveProjectRoot(project);
        } catch (Exception e) {
            LOG.debug("Failed to resolve GSD project root: %s", e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    /**
     * Creates the chat area using Browser-based HTML/CSS rendering.
     * Provides better support for tables, code highlighting, and modern styling.
     */
    private void createBrowserChatArea(Composite parent) {
        browserChatPanel = new BrowserChatPanel(parent);
        browserChatPanel.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // Set up code application callback
        browserChatPanel.setApplyCodeCallback((code, language, filePath) -> {
            // TODO: Implement code application from browser
            // For now, just log
            LOG.info("Apply code requested: language=%s, filePath=%s, codeLength=%d", //$NON-NLS-1$
                    language, filePath, code != null ? code.length() : 0);
        });

        // Listen for theme changes
        ThemeManager.getInstance().addThemeChangeListener(theme -> {
            if (browserChatPanel != null && browserChatPanel.isBrowserAvailable()) {
                browserChatPanel.updateTheme(ThemeManager.getInstance().isDarkTheme());
            }
        });
    }

    /**
     * Creates the chat area using StyledText-based rendering.
     * Fallback when Browser is not available.
     */
    private void createStyledTextChatArea(Composite parent) {
        VibeTheme theme = ThemeManager.getInstance().getTheme();

        // Scrolled composite for chat messages
        scrolledComposite = new ScrolledComposite(parent, SWT.BORDER | SWT.V_SCROLL);
        scrolledComposite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        scrolledComposite.setExpandHorizontal(true);
        scrolledComposite.setExpandVertical(true);
        scrolledComposite.setBackground(theme.getBackground());

        // Container for messages
        messagesContainer = new Composite(scrolledComposite, SWT.NONE);
        messagesContainer.setBackground(theme.getBackground());
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = theme.getMargin();
        layout.marginHeight = theme.getMargin();
        layout.verticalSpacing = theme.getMargin();
        messagesContainer.setLayout(layout);

        scrolledComposite.setContent(messagesContainer);

        // Update scroll size when container changes
        messagesContainer.addListener(SWT.Resize, e -> updateScrollSize());

        // Configure scroll bar increment for smoother scrolling
        if (scrolledComposite.getVerticalBar() != null) {
            scrolledComposite.getVerticalBar().setIncrement(20);
            scrolledComposite.getVerticalBar().setPageIncrement(100);
        }

        // Install mouse wheel scrolling recursively on all children
        // This fixes the known SWT bug #93472 where ScrolledComposite content
        // doesn't get scrolled by mousewheel on Windows
        installMouseWheelScrolling(scrolledComposite, messagesContainer);

        // Create typing indicator (initially hidden)
        typingIndicator = new TypingIndicatorWidget(messagesContainer);
        typingIndicator.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    private void createInputArea(Composite parent) {
        VibeTheme theme = ThemeManager.getInstance().getTheme();

        Composite inputArea = new Composite(parent, SWT.NONE);
        inputArea.setBackground(theme.getSurface());
        inputArea.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout inputAreaLayout = new GridLayout(1, false);
        inputAreaLayout.marginWidth = CHAT_COMPOSER_MARGIN;
        inputAreaLayout.marginHeight = 10;
        inputAreaLayout.verticalSpacing = 8;
        inputArea.setLayout(inputAreaLayout);

        // Input field - full width
        inputField = new Text(inputArea, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        inputField.setBackground(theme.getInputBackground());
        inputField.setForeground(theme.getText());
        inputField.setFont(theme.getFont());
        GridData inputData = new GridData(SWT.FILL, SWT.FILL, true, false);
        inputData.heightHint = CHAT_INPUT_HEIGHT;
        inputField.setLayoutData(inputData);
        inputField.setMessage(Messages.ChatView_InputPlaceholder);

        inputField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if ((e.stateMask & SWT.MOD1) != 0 && (e.keyCode == 'v' || e.keyCode == 'V')) {
                    if (handleClipboardPaste()) {
                        e.doit = false;
                        return;
                    }
                }
                // Enter without modifiers or Ctrl+Enter - send message
                // Shift+Enter - insert newline (default behavior)
                if (e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR) {
                    if ((e.stateMask & SWT.SHIFT) == 0) {
                        // No Shift pressed - send message
                        e.doit = false; // Prevent newline insertion
                        sendMessage();
                    }
                    // Shift+Enter: let default behavior insert newline
                }
            }
        });

        installInputContextMenu();

        attachmentPreviewArea = new Composite(inputArea, SWT.NONE);
        attachmentPreviewArea.setBackground(inputArea.getBackground());
        attachmentPreviewArea.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout attachmentLayout = new GridLayout(1, false);
        attachmentLayout.marginWidth = 0;
        attachmentLayout.marginHeight = 0;
        attachmentLayout.verticalSpacing = 4;
        attachmentPreviewArea.setLayout(attachmentLayout);
        attachmentPreviewArea.setVisible(false);
        ((GridData) attachmentPreviewArea.getLayoutData()).exclude = true;

        // Button bar - compact horizontal layout
        Composite buttonBar = new Composite(inputArea, SWT.NONE);
        buttonBar.setBackground(inputArea.getBackground());
        buttonBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout buttonLayout = new GridLayout(10, false);
        buttonLayout.marginWidth = 0;
        buttonLayout.marginHeight = 0;
        buttonLayout.horizontalSpacing = CHAT_BUTTON_SPACING;
        buttonBar.setLayout(buttonLayout);

        attachButton = createChatActionButton(buttonBar, "+", Messages.ChatView_AttachButton, //$NON-NLS-1$
                CHAT_ICON_BUTTON_WIDTH);
        attachButton.addListener(SWT.Selection, e -> openAttachmentDialog());

        // Send button with icon
        sendButton = createChatActionButton(buttonBar, "\u27A4", //$NON-NLS-1$
                Messages.ChatView_SendButton + " (Enter)", CHAT_ICON_BUTTON_WIDTH); //$NON-NLS-1$
        sendButton.addListener(SWT.Selection, e -> sendMessage());

        initCodeMdButton = createChatActionButton(buttonBar, "", //$NON-NLS-1$
                Messages.ChatView_CodeMdNoProjectTooltip, SWT.DEFAULT);
        initCodeMdButton.addListener(SWT.Selection, e -> runCodeMdInitialization());
        updateInitCodeMdButton();

        // Apply code button with icon
        applyCodeButton = createChatActionButton(buttonBar, "\u2913", //$NON-NLS-1$
                Messages.ChatView_ApplyCodeTooltip, CHAT_ICON_BUTTON_WIDTH);
        applyCodeButton.setEnabled(false);
        applyCodeButton.addListener(SWT.Selection, e -> applyCodeToEditor());

        // Manual context compaction button
        compactButton = createChatActionButton(buttonBar, Messages.ChatView_CompactContextButton,
                Messages.ChatView_CompactContextTooltip, CHAT_COMPACT_BUTTON_WIDTH);
        compactButton.addListener(SWT.Selection, e -> {
            if (!compactConversationHistory(false)) {
                appendSystemMessage(Messages.ChatView_ContextCompactedSkippedNotice);
            }
        });

        // Model selector button — only visible when CodePilot is active
        modelButton = createChatActionButton(buttonBar, Messages.ChatView_ModelButton,
                Messages.ChatView_ModelButtonTooltip, CHAT_MODEL_BUTTON_WIDTH);
        modelButton.addListener(SWT.Selection, e -> openModelSelectionDialog());
        updateModelButtonVisibility();

        // Token usage label — hidden by default (Phase 2: replaced by budget indicator)
        tokenUsageLabel = new Label(buttonBar, SWT.NONE);
        tokenUsageLabel.setBackground(buttonBar.getBackground());
        tokenUsageLabel.setForeground(theme.getTextMuted());
        tokenUsageLabel.setVisible(false);
        GridData tokenData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        tokenData.exclude = true;
        tokenUsageLabel.setLayoutData(tokenData);
        tokenUsageLabel.setText(""); //$NON-NLS-1$

        // Spacer to push stop/new-chat to the right (replaces token label space)
        Label spacer = new Label(buttonBar, SWT.NONE);
        spacer.setBackground(buttonBar.getBackground());
        spacer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Stop button with icon
        stopButton = createChatActionButton(buttonBar, "\u25A0", //$NON-NLS-1$
                Messages.ChatView_StopButton, CHAT_ICON_BUTTON_WIDTH);
        stopButton.setEnabled(false);
        ((GridData) stopButton.getLayoutData()).horizontalAlignment = SWT.RIGHT;
        stopButton.addListener(SWT.Selection, e -> stopGeneration());

        // New Chat button — prominent action to start fresh conversation
        newChatButton = createChatActionButton(buttonBar, "\uD83D\uDCC4+", //$NON-NLS-1$
                Messages.ChatView_NewChatTooltip, CHAT_NEW_CHAT_BUTTON_WIDTH);
        ((GridData) newChatButton.getLayoutData()).horizontalAlignment = SWT.RIGHT;
        newChatButton.addListener(SWT.Selection, e -> confirmAndClearChat());

        // New Window button — open another chat in parallel (multi-view).
        newWindowButton = createChatActionButton(buttonBar, "➕", //$NON-NLS-1$
                Messages.ChatView_NewChatTooltip, CHAT_ICON_BUTTON_WIDTH);
        ((GridData) newWindowButton.getLayoutData()).horizontalAlignment = SWT.RIGHT;
        newWindowButton.addListener(SWT.Selection, e -> openNewChatWindow());

        // Clear button with icon (legacy, kept for backward compat)
        clearButton = createChatActionButton(buttonBar, "\uD83D\uDDD1", //$NON-NLS-1$
                Messages.ChatView_ClearButton, CHAT_ICON_BUTTON_WIDTH);
        clearButton.setVisible(false); // Hidden: replaced by newChatButton
        GridData clearData = (GridData) clearButton.getLayoutData();
        clearData.horizontalAlignment = SWT.RIGHT;
        clearData.exclude = true;
        clearButton.addListener(SWT.Selection, e -> clearChat());

        refreshAttachmentPreview();
    }

    private Button createChatActionButton(Composite parent, String text, String tooltip, int widthHint) {
        VibeTheme theme = ThemeManager.getInstance().getTheme();
        Button button = new Button(parent, SWT.PUSH);
        button.setText(text);
        button.setToolTipText(tooltip);
        button.setFont(theme.getFont());
        GridData data = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        if (widthHint != SWT.DEFAULT) {
            data.widthHint = widthHint;
        }
        data.heightHint = CHAT_ACTION_BUTTON_HEIGHT;
        button.setLayoutData(data);
        return button;
    }

    private void updateScrollSize() {
        if (messagesContainer == null || messagesContainer.isDisposed()
                || scrolledComposite == null || scrolledComposite.isDisposed()) {
            return;
        }

        int width = scrolledComposite.getClientArea().width;
        if (width <= 0) {
            width = scrolledComposite.getBounds().width - scrolledComposite.getVerticalBar().getSize().x;
        }

        if (width > 0) {
            Point size = messagesContainer.computeSize(width, SWT.DEFAULT);
            messagesContainer.setSize(size);
            scrolledComposite.setMinSize(size);
        }
    }

    private void openAttachmentDialog() {
        FileDialog dialog = new FileDialog(getSite().getShell(), SWT.OPEN | SWT.MULTI);
        dialog.setText(Messages.ChatView_AttachDialogTitle);
        dialog.open();
        String[] fileNames = dialog.getFileNames();
        if (fileNames == null || fileNames.length == 0) {
            return;
        }
        Path filterPath = dialog.getFilterPath() != null && !dialog.getFilterPath().isBlank()
                ? Path.of(dialog.getFilterPath())
                : null;
        List<LlmAttachment> attachments = new ArrayList<>();
        for (String fileName : fileNames) {
            Path path = filterPath != null ? filterPath.resolve(fileName) : Path.of(fileName);
            LlmAttachment attachment = createAttachmentFromPath(path);
            if (attachment != null) {
                attachments.add(attachment);
            }
        }
        addDraftAttachments(attachments);
    }

    /**
     * Installs an explicit Cut/Copy/Paste/Select-All context menu on the input field.
     *
     * <p>Without it the native control menu is used, which on macOS surfaces only the
     * system Writing Tools and omits a usable "Paste" entry. The Paste action routes
     * through {@link #handleClipboardPaste()} first so an image/file on the clipboard
     * becomes an attachment; otherwise it falls back to the native text paste.</p>
     */
    private void installInputContextMenu() {
        if (inputField == null || inputField.isDisposed()) {
            return;
        }
        Menu menu = new Menu(inputField);

        MenuItem cut = new MenuItem(menu, SWT.PUSH);
        cut.setText("Вырезать"); //$NON-NLS-1$
        cut.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> inputField.cut()));

        MenuItem copy = new MenuItem(menu, SWT.PUSH);
        copy.setText("Копировать"); //$NON-NLS-1$
        copy.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> inputField.copy()));

        MenuItem paste = new MenuItem(menu, SWT.PUSH);
        paste.setText("Вставить"); //$NON-NLS-1$
        paste.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> {
            if (!handleClipboardPaste()) {
                inputField.paste();
            }
        }));

        new MenuItem(menu, SWT.SEPARATOR);

        MenuItem selectAll = new MenuItem(menu, SWT.PUSH);
        selectAll.setText("Выделить всё"); //$NON-NLS-1$
        selectAll.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> inputField.selectAll()));

        menu.addMenuListener(new MenuAdapter() {
            @Override
            public void menuShown(MenuEvent e) {
                boolean hasSelection = inputField.getSelectionCount() > 0;
                cut.setEnabled(hasSelection);
                copy.setEnabled(hasSelection);
                selectAll.setEnabled(inputField.getCharCount() > 0);
            }
        });

        inputField.setMenu(menu);
    }

    private boolean handleClipboardPaste() {
        Clipboard clipboard = new Clipboard(getDisplay());
        try {
            Object filePayload = clipboard.getContents(FileTransfer.getInstance());
            if (filePayload instanceof String[] filePaths && filePaths.length > 0) {
                List<LlmAttachment> attachments = new ArrayList<>();
                for (String filePath : filePaths) {
                    LlmAttachment attachment = createAttachmentFromPath(Path.of(filePath));
                    if (attachment != null) {
                        attachments.add(attachment);
                    }
                }
                addDraftAttachments(attachments);
                return !attachments.isEmpty();
            }

            Object imagePayload = clipboard.getContents(ImageTransfer.getInstance());
            if (imagePayload instanceof ImageData imageData) {
                LlmAttachment attachment = createAttachmentFromClipboard(imageData);
                if (attachment != null) {
                    addDraftAttachments(List.of(attachment));
                    return true;
                }
            }

            // macOS: SWT ImageTransfer does not read screenshots from NSPasteboard (TIFF/PNG),
            // so the branch above returns nothing. Fall back to an isolated osascript subprocess
            // that writes the clipboard image to a PNG file (no in-process AWT, safe under
            // -XstartOnFirstThread).
            Path macImage = tryReadMacClipboardImage();
            if (macImage != null) {
                LlmAttachment attachment = createAttachmentFromPath(macImage);
                if (attachment != null) {
                    addDraftAttachments(List.of(attachment));
                    return true;
                }
            }
            return false;
        } finally {
            clipboard.dispose();
        }
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    }

    /**
     * Reads an image from the macOS clipboard via {@code osascript}, writing it to a PNG file.
     * SWT's Cocoa {@code ImageTransfer} does not return screenshots placed on the pasteboard,
     * so this isolated-subprocess fallback handles the common "screenshot to clipboard" case
     * without initializing AWT in-process (which is unsafe under {@code -XstartOnFirstThread}).
     *
     * @return path to a PNG file, or {@code null} when no clipboard image is available
     */
    private Path tryReadMacClipboardImage() {
        if (!isMac()) {
            return null;
        }
        try {
            Path cacheDir = getAttachmentCacheDir();
            Files.createDirectories(cacheDir);
            long stamp = System.currentTimeMillis();
            Path png = cacheDir.resolve("clipboard-" + stamp + ".png"); //$NON-NLS-1$ //$NON-NLS-2$

            // Preferred path: coerce the clipboard image directly to PNG.
            String pngScript = "set theFile to (POSIX file \"" + png + "\")\n" //$NON-NLS-1$ //$NON-NLS-2$
                    + "set theData to (the clipboard as «class PNGf»)\n" //$NON-NLS-1$
                    + "set fh to open for access theFile with write permission\n" //$NON-NLS-1$
                    + "set eof fh to 0\n" //$NON-NLS-1$
                    + "write theData to fh\n" //$NON-NLS-1$
                    + "close access fh"; //$NON-NLS-1$
            if (runProcess(new ProcessBuilder("/usr/bin/osascript", "-e", pngScript)) && isPng(png)) { //$NON-NLS-1$ //$NON-NLS-2$
                return png;
            }
            Files.deleteIfExists(png);

            // Fallback: screenshots live on the pasteboard as TIFF; grab TIFF then convert via sips.
            Path tiff = cacheDir.resolve("clipboard-" + stamp + ".tiff"); //$NON-NLS-1$ //$NON-NLS-2$
            String tiffScript = "set theFile to (POSIX file \"" + tiff + "\")\n" //$NON-NLS-1$ //$NON-NLS-2$
                    + "set theData to (the clipboard as TIFF picture)\n" //$NON-NLS-1$
                    + "set fh to open for access theFile with write permission\n" //$NON-NLS-1$
                    + "set eof fh to 0\n" //$NON-NLS-1$
                    + "write theData to fh\n" //$NON-NLS-1$
                    + "close access fh"; //$NON-NLS-1$
            if (runProcess(new ProcessBuilder("/usr/bin/osascript", "-e", tiffScript)) //$NON-NLS-1$ //$NON-NLS-2$
                    && Files.exists(tiff) && Files.size(tiff) > 0) {
                boolean converted = runProcess(new ProcessBuilder(
                        "/usr/bin/sips", "-s", "format", "png", tiff.toString(), "--out", png.toString())); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
                Files.deleteIfExists(tiff);
                if (converted && isPng(png)) {
                    return png;
                }
                Files.deleteIfExists(png);
            }
        } catch (IOException | RuntimeException e) {
            LOG.debug("macOS clipboard image fallback failed: %s", e.getMessage()); //$NON-NLS-1$
        }
        return null;
    }

    private boolean runProcess(ProcessBuilder builder) {
        try {
            builder.redirectErrorStream(true);
            Process process = builder.start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            LOG.debug("Clipboard subprocess failed: %s", e.getMessage()); //$NON-NLS-1$
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean isPng(Path file) {
        try {
            if (!Files.exists(file) || Files.size(file) < 8) {
                return false;
            }
            byte[] header = new byte[8];
            try (InputStream in = Files.newInputStream(file)) {
                if (in.read(header) != header.length) {
                    return false;
                }
            }
            return (header[0] & 0xFF) == 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G';
        } catch (IOException e) {
            return false;
        }
    }

    private void addDraftAttachments(List<LlmAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return;
        }
        ProviderCapabilities caps = currentProviderCapabilities();
        long maxBytes = caps.getMaxAttachmentBytes() > 0 ? caps.getMaxAttachmentBytes() : DEFAULT_MAX_ATTACHMENT_BYTES;
        int maxAttachments = caps.getMaxAttachmentsPerMessage() > 0
                ? caps.getMaxAttachmentsPerMessage()
                : DEFAULT_MAX_ATTACHMENTS;
        for (LlmAttachment attachment : attachments) {
            if (draftAttachments.size() >= maxAttachments) {
                appendSystemMessage(Messages.ChatView_AttachmentLimitExceeded);
                break;
            }
            if (attachment.getSizeBytes() > maxBytes) {
                appendSystemMessage(java.text.MessageFormat.format(
                        Messages.ChatView_AttachmentTooLarge,
                        attachment.getDisplayName()));
                continue;
            }
            draftAttachments.add(attachment);
        }
        refreshAttachmentPreview();
    }

    private void refreshAttachmentPreview() {
        if (attachmentPreviewArea == null || attachmentPreviewArea.isDisposed()) {
            return;
        }
        for (Control child : attachmentPreviewArea.getChildren()) {
            child.dispose();
        }
        boolean hasAttachments = !draftAttachments.isEmpty();
        ((GridData) attachmentPreviewArea.getLayoutData()).exclude = !hasAttachments;
        attachmentPreviewArea.setVisible(hasAttachments);
        if (hasAttachments) {
            for (int i = 0; i < draftAttachments.size(); i++) {
                LlmAttachment attachment = draftAttachments.get(i);
                Composite row = new Composite(attachmentPreviewArea, SWT.NONE);
                row.setBackground(attachmentPreviewArea.getBackground());
                row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
                GridLayout rowLayout = new GridLayout(2, false);
                rowLayout.marginWidth = 0;
                rowLayout.marginHeight = 0;
                rowLayout.horizontalSpacing = 8;
                row.setLayout(rowLayout);

                Label label = new Label(row, SWT.WRAP);
                label.setBackground(row.getBackground());
                label.setText(buildAttachmentLabel(attachment));
                label.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

                final int index = i;
                Button removeButton = new Button(row, SWT.PUSH);
                removeButton.setText("×"); //$NON-NLS-1$
                removeButton.setEnabled(!isProcessing);
                removeButton.addListener(SWT.Selection, e -> {
                    draftAttachments.remove(index);
                    refreshAttachmentPreview();
                });
            }
        }
        if (attachmentPreviewArea.getParent() != null && !attachmentPreviewArea.getParent().isDisposed()) {
            attachmentPreviewArea.getParent().layout(true, true);
        }
    }

    private String buildAttachmentLabel(LlmAttachment attachment) {
        StringBuilder sb = new StringBuilder();
        sb.append(attachment.isImage() ? "🖼 " : "📎 "); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(attachment.toDisplayLabel());
        if (attachment.getSizeBytes() > 0) {
            sb.append(" · ").append(formatAttachmentSize(attachment.getSizeBytes())); //$NON-NLS-1$
        }
        return sb.toString();
    }

    private String formatAttachmentSize(long sizeBytes) {
        if (sizeBytes < 1024) {
            return sizeBytes + " B"; //$NON-NLS-1$
        }
        double kb = sizeBytes / 1024.0d;
        if (kb < 1024.0d) {
            return String.format("%.1f KB", Double.valueOf(kb)); //$NON-NLS-1$
        }
        return String.format("%.1f MB", Double.valueOf(kb / 1024.0d)); //$NON-NLS-1$
    }

    private LlmAttachment createAttachmentFromPath(Path path) {
        if (path == null || !Files.exists(path)) {
            return null;
        }
        try {
            String mimeType = Files.probeContentType(path);
            if (mimeType == null || mimeType.isBlank()) {
                mimeType = guessMimeType(path);
            }
            long size = Files.size(path);
            LlmAttachment.Kind kind = mimeType.startsWith("image/") //$NON-NLS-1$
                    ? LlmAttachment.Kind.IMAGE
                    : LlmAttachment.Kind.FILE;
            return LlmAttachment.builder()
                    .kind(kind)
                    .displayName(path.getFileName().toString())
                    .mimeType(mimeType)
                    .sizeBytes(size)
                    .originalPath(path.toAbsolutePath().toString())
                    .previewText(kind == LlmAttachment.Kind.FILE ? extractPreviewText(path, mimeType) : null)
                    .build();
        } catch (IOException e) {
            LOG.warn("Failed to create attachment from path %s: %s", path, e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private LlmAttachment createAttachmentFromClipboard(ImageData imageData) {
        Path cachePath = saveClipboardImage(imageData);
        if (cachePath == null) {
            return null;
        }
        try {
            return LlmAttachment.builder()
                    .kind(LlmAttachment.Kind.IMAGE)
                    .displayName("clipboard-image.png") //$NON-NLS-1$
                    .mimeType("image/png") //$NON-NLS-1$
                    .sizeBytes(Files.size(cachePath))
                    .cachePath(cachePath.toString())
                    .build();
        } catch (IOException e) {
            LOG.warn("Failed to stat clipboard image %s: %s", cachePath, e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private Path saveClipboardImage(ImageData imageData) {
        try {
            Path cacheDir = getAttachmentCacheDir();
            Files.createDirectories(cacheDir);
            Path file = cacheDir.resolve("clipboard-" + System.currentTimeMillis() + ".png"); //$NON-NLS-1$ //$NON-NLS-2$
            ImageLoader loader = new ImageLoader();
            loader.data = new ImageData[] { imageData };
            loader.save(file.toString(), SWT.IMAGE_PNG);
            return file;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Failed to save clipboard image: %s", e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private Path getAttachmentCacheDir() {
        if (VibeUiPlugin.getDefault() != null) {
            return Path.of(VibeUiPlugin.getDefault().getStateLocation().toOSString()).resolve("chat-attachments"); //$NON-NLS-1$
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "codepilot1c-chat-attachments"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String extractPreviewText(Path path, String mimeType) {
        return AttachmentTextExtractor.extractPreviewText(path, mimeType, FILE_PREVIEW_CHAR_LIMIT);
    }

    private String guessMimeType(Path path) {
        String lower = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png"; //$NON-NLS-1$ //$NON-NLS-2$
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        if (lower.endsWith(".gif")) return "image/gif"; //$NON-NLS-1$ //$NON-NLS-2$
        if (lower.endsWith(".webp")) return "image/webp"; //$NON-NLS-1$ //$NON-NLS-2$
        if (lower.endsWith(".json")) return "application/json"; //$NON-NLS-1$ //$NON-NLS-2$
        if (lower.endsWith(".xml")) return "application/xml"; //$NON-NLS-1$ //$NON-NLS-2$
        if (lower.endsWith(".csv")) return "text/csv"; //$NON-NLS-1$ //$NON-NLS-2$
        return "application/octet-stream"; //$NON-NLS-1$
    }

    private ProviderCapabilities currentProviderCapabilities() {
        ILlmProvider provider = LlmProviderRegistry.getInstance().getActiveProvider();
        return provider != null ? provider.getCapabilities() : ProviderCapabilities.none();
    }

    private void scrollToBottom() {
        if (scrolledComposite == null || scrolledComposite.isDisposed()) {
            return;
        }

        // Force immediate layout update
        messagesContainer.layout(true, true);
        updateScrollSize();

        // Use asyncExec to ensure layout is fully processed before scrolling
        scrolledComposite.getDisplay().asyncExec(() -> {
            if (scrolledComposite.isDisposed() || messagesContainer.isDisposed()) {
                return;
            }

            // Scroll to bottom
            int contentHeight = messagesContainer.getSize().y;
            int viewportHeight = scrolledComposite.getClientArea().height;

            if (contentHeight > viewportHeight) {
                scrolledComposite.setOrigin(0, contentHeight - viewportHeight);
            }
        });
    }

    private void sendMessage() {
        String userInput = inputField.getText().trim();
        LOG.debug("sendMessage called, isProcessing=%b, inputLength=%d, attachments=%d", //$NON-NLS-1$
                isProcessing, userInput.length(), draftAttachments.size());

        if ((userInput.isEmpty() && draftAttachments.isEmpty()) || isProcessing) {
            LOG.debug("sendMessage blocked: isEmpty=%b, isProcessing=%b", //$NON-NLS-1$
                    userInput.isEmpty() && draftAttachments.isEmpty(), isProcessing);
            return;
        }

        ILlmProvider provider = LlmProviderRegistry.getInstance().getActiveProvider();
        if (provider == null || !provider.isConfigured()) {
            LOG.warn("Provider not configured"); //$NON-NLS-1$
            appendSystemMessage(Messages.ChatView_NotConfiguredMessage);
            return;
        }

        ProviderCapabilities caps = provider.getCapabilities();
        if (!caps.supportsImageInput() && draftAttachments.stream().anyMatch(LlmAttachment::isImage)) {
            appendSystemMessage(Messages.ChatView_ImageAttachmentsUnsupported);
            return;
        }

        // Add user message to UI
        List<LlmAttachment> outgoingAttachments = new ArrayList<>(draftAttachments);
        appendUserMessage(userInput, outgoingAttachments);
        inputField.setText(""); //$NON-NLS-1$
        draftAttachments.clear();
        refreshAttachmentPreview();

        maybeAutoCompactHistory();

        // Extract $skill mentions from user input for system prompt assembly
        currentRequestedSkills = SkillMentionParser.extractMentions(userInput);

        // No automatic context preparation: send the user message as-is.
        setProcessing(true, "Отправка запроса..."); //$NON-NLS-1$
        conversationHistory.add(buildUserMessage(userInput, outgoingAttachments));
        // Plan 1.2: a new user turn is a natural reset point for the repetition
        // detector — whatever the user asks next is a fresh intent.
        toolRepetitionDetector.resetForNewTurn();
        startConversationLoop(provider);
    }

    private void runCodeMdInitialization() {
        if (isProcessing) {
            return;
        }
        IProject project = resolveCodeMdProject();
        if (project == null) {
            updateInitCodeMdButton();
            return;
        }
        boolean updateExisting = hasCodeMd(project);
        bindCurrentSessionToProject(project);
        String codeMdToolPath = resolveCodeMdToolPath(project, updateExisting);
        if (!inputField.getText().trim().isEmpty() || !draftAttachments.isEmpty()) {
            boolean confirmed = MessageDialog.openConfirm(
                    getSite().getShell(),
                    Messages.ChatView_InitCodeMdConfirmTitle,
                    updateExisting
                            ? Messages.ChatView_UpdateCodeMdConfirmMessage
                            : Messages.ChatView_CreateCodeMdConfirmMessage);
            if (!confirmed) {
                return;
            }
            draftAttachments.clear();
            refreshAttachmentPreview();
        }
        IPath location = project.getLocation();
        if (location == null) {
            appendSystemMessage(Messages.CodeMdPreferencePage_NoProjectLocation);
            updateInitCodeMdButton();
            return;
        }
        ProjectMemoryInitializationService.Request request = new ProjectMemoryInitializationService.Request(
                updateExisting
                        ? ProjectMemoryInitializationService.Mode.UPDATE
                        : ProjectMemoryInitializationService.Mode.CREATE,
                Path.of(location.toOSString()),
                project.getName(),
                codeMdToolPath);
        String startedMessage = updateExisting
                ? Messages.ChatView_UpdateCodeMdStarted
                : Messages.ChatView_CreateCodeMdStarted;
        appendSystemMessage(startedMessage);
        setProcessing(true, updateExisting
                ? Messages.ChatView_UpdateCodeMdProcessingStage
                : Messages.ChatView_CreateCodeMdProcessingStage);

        CompletableFuture<ProjectMemoryInitializationService.Result> initRequest =
                PROJECT_MEMORY_INIT_SERVICE.initialize(request);
        currentRequest = initRequest;
        currentRequestUsesDesktopController = true;
        Display display = getDisplay();
        initRequest.whenComplete((result, error) -> {
            if (display == null || display.isDisposed()) {
                return;
            }
            display.asyncExec(() -> {
                if (isDisposed()) {
                    return;
                }
                if (currentRequest == initRequest) {
                    currentRequest = null;
                    currentRequestUsesDesktopController = false;
                }
                setProcessing(false);
                updateInitCodeMdButton();
                appendSystemMessage(formatCodeMdInitializationResult(result, error));
            });
        });
    }

    private String formatCodeMdInitializationResult(ProjectMemoryInitializationService.Result result, Throwable error) {
        if (error != null) {
            return MessageFormat.format(Messages.ChatView_CodeMdInitFailed, error.getMessage());
        }
        if (result == null) {
            return MessageFormat.format(Messages.ChatView_CodeMdInitFailed, "empty result"); //$NON-NLS-1$
        }
        switch (result.getStatus()) {
        case SUCCESS:
            return MessageFormat.format(Messages.ChatView_CodeMdInitSucceeded, sourcePathLabel(result.getSourcePath()));
        case PROVIDER_UNAVAILABLE:
            return Messages.ChatView_CodeMdInitProviderUnavailable;
        case AGENT_BUSY:
            return Messages.ChatView_CodeMdInitBusy;
        case CODE_MD_NOT_WRITTEN:
            return Messages.ChatView_CodeMdInitMissingFile;
        case AGENT_FAILED:
        default:
            return MessageFormat.format(Messages.ChatView_CodeMdInitFailed, result.getMessage());
        }
    }

    private String sourcePathLabel(Path sourcePath) {
        return sourcePath != null ? sourcePath.toString() : ProjectMemoryContextService.CANONICAL_FILE_NAME;
    }

    private void updateInitCodeMdButton() {
        if (initCodeMdButton == null || initCodeMdButton.isDisposed()) {
            return;
        }

        IProject project = resolveCodeMdProject();
        boolean hasProject = project != null;
        boolean updateExisting = hasProject && hasCodeMd(project);
        initCodeMdButton.setText(updateExisting
                ? Messages.ChatView_UpdateCodeMdButton
                : Messages.ChatView_CreateCodeMdButton);
        initCodeMdButton.setToolTipText(hasProject
                ? (updateExisting
                        ? Messages.ChatView_UpdateCodeMdTooltip
                        : Messages.ChatView_CreateCodeMdTooltip)
                : Messages.ChatView_CodeMdNoProjectTooltip);
        initCodeMdButton.setEnabled(!isProcessing && hasProject);
        if (initCodeMdButton.getParent() != null && !initCodeMdButton.getParent().isDisposed()) {
            initCodeMdButton.getParent().layout(true, true);
        }
    }

    private IProject resolveCodeMdProject() {
        try {
            Session session = SessionManager.getInstance().getOrCreateCurrentSession();
            IProject project = SessionManager.getInstance().findProjectByPath(
                    session != null ? session.getProjectPath() : null);
            if (isOpenProject(project)) {
                return project;
            }
        } catch (Exception e) {
            LOG.debug("Failed to resolve Code.md project from session: %s", e.getMessage()); //$NON-NLS-1$
        }

        try {
            for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                if (isOpenProject(project)) {
                    return project;
                }
            }
        } catch (Exception e) {
            LOG.debug("Failed to resolve Code.md project from workspace: %s", e.getMessage()); //$NON-NLS-1$
        }
        return null;
    }

    private boolean isOpenProject(IProject project) {
        return project != null && project.exists() && project.isOpen();
    }

    private void bindCurrentSessionToProject(IProject project) {
        try {
            SessionManager manager = SessionManager.getInstance();
            manager.bindSessionToProject(viewSession(), project);
            turnContext = null;
            toolGate = null;
            requestGsdStatusRefresh();
        } catch (Exception e) {
            LOG.debug("Failed to bind Code.md session to project: %s", e.getMessage()); //$NON-NLS-1$
        }
    }

    private String resolveCodeMdToolPath(IProject project, boolean updateExisting) {
        if (!isOpenProject(project)) {
            return ProjectMemoryContextService.CANONICAL_FILE_NAME;
        }
        IPath location = project.getLocation();
        if (location == null) {
            return ProjectMemoryContextService.CANONICAL_FILE_NAME;
        }

        if (updateExisting) {
            ProjectMemoryContextService.ReadResult result =
                    PROJECT_MEMORY_SERVICE.status(location.toOSString());
            String existingPath = toWorkspaceRelativeToolPath(project, location, result.getSourcePath());
            if (existingPath != null) {
                return existingPath;
            }
        }
        String canonicalPath = toWorkspaceRelativeToolPath(project, location,
                Path.of(location.toOSString()).resolve(ProjectMemoryContextService.CANONICAL_FILE_NAME));
        return canonicalPath != null ? canonicalPath : ProjectMemoryContextService.CANONICAL_FILE_NAME;
    }

    private String toWorkspaceRelativeToolPath(IProject project, IPath projectLocation, Path sourcePath) {
        if (sourcePath == null || projectLocation == null) {
            return null;
        }
        try {
            Path projectRoot = Path.of(projectLocation.toOSString()).toAbsolutePath().normalize();
            Path source = sourcePath.toAbsolutePath().normalize();
            if (!source.startsWith(projectRoot)) {
                return null;
            }
            String projectRelative = projectRoot.relativize(source).toString().replace('\\', '/');
            if (projectRelative.isBlank()) {
                return null;
            }
            String workspaceRelative = project.getFullPath().append(projectRelative).toPortableString();
            return workspaceRelative.startsWith("/") ? workspaceRelative.substring(1) : workspaceRelative; //$NON-NLS-1$
        } catch (RuntimeException e) {
            LOG.debug("Failed to resolve Code.md workspace path: %s", e.getMessage()); //$NON-NLS-1$
            return null;
        }
    }

    private boolean hasCodeMd(IProject project) {
        if (!isOpenProject(project)) {
            return false;
        }
        IPath location = project.getLocation();
        if (location == null) {
            return false;
        }
        ProjectMemoryContextService.Status status = PROJECT_MEMORY_SERVICE.status(location.toOSString()).getStatus();
        switch (status) {
        case FOUND:
        case EMPTY:
        case TRUNCATED:
            return true;
        default:
            return false;
        }
    }

    /**
     * Starts the conversation loop with tool support.
     * This handles the tool call -> execute -> response cycle.
     */
    private void startConversationLoop(ILlmProvider provider) {
        LOG.debug("startConversationLoop: beginning"); //$NON-NLS-1$

        this.turnContext = ChatTurnContext.resolve(viewSession(), configuredChatProfileId());
        this.toolGate = createToolGate(turnContext.profile());
        LOG.debug("chat tool gate: profile=%s", toolGate.profile().getId()); //$NON-NLS-1$

        // Build request with tools
        LlmRequest request = buildRequestWithTools();
        LOG.debug("startConversationLoop: request built with %d messages, %d tools", //$NON-NLS-1$
                request.getMessages().size(), request.hasTools() ? request.getTools().size() : 0);

        // Capture display reference for safe async callbacks
        final Display display = getDisplay();

        // Update stage to waiting for response
        if (!display.isDisposed()) {
            display.asyncExec(() -> {
                if (!isDisposed()) {
                    setProcessingStage("Ожидание ответа модели..."); //$NON-NLS-1$
                }
            });
        }

        // Use streaming if provider supports it
        // Tool calls are now properly accumulated in streaming mode via delta.tool_calls
        if (provider.supportsStreaming()) {
            startStreamingRequest(provider, request, display);
        } else {
            startNonStreamingRequest(provider, request, display);
        }
    }

    /**
     * Starts a streaming request to the LLM provider.
     */
    private void startStreamingRequest(ILlmProvider provider, LlmRequest request, Display display) {
        LOG.debug("startStreamingRequest: using streaming mode"); //$NON-NLS-1$

        long rt = roundTripSeq.incrementAndGet();
        if (!inflight.compareAndSet(false, true)) {
            LOG.warn("[single-flight] dropped duplicate request rt=%d (streaming entry)", rt); //$NON-NLS-1$
            return;
        }
        currentRoundTripId = rt;
        usageRegisteredForThisRoundTrip = false;
        // Plan 2.4: count accepted round-trips for the session footer.
        // Increment only after the CAS wins so duplicates don't inflate the count.
        requestCount++;

        currentStreamingRequest = request;
        streamingContent = new StringBuffer();
        streamingReasoning = new StringBuffer();
        isStreaming = true;
        streamingHandledToolCalls.set(false); // Reset for new streaming session

        // Add empty AI message that will be updated with streaming content
        if (!display.isDisposed()) {
            display.asyncExec(() -> {
                if (!isDisposed()) {
                    setProcessingStage("Получение ответа..."); //$NON-NLS-1$
                    appendAssistantMessage(""); // Empty message to be updated //$NON-NLS-1$
                }
            });
        }

        // Run streaming in background thread
        CompletableFuture.runAsync(() -> {
            try {
                provider.streamComplete(request, chunk -> handleStreamChunk(chunk, display));
            } catch (Exception e) {
                LOG.error("Streaming error: %s", e.getMessage()); //$NON-NLS-1$
                inflight.set(false);
                if (!display.isDisposed()) {
                    display.asyncExec(() -> {
                        if (!isDisposed()) {
                            handleError(e);
                        }
                    });
                }
            }
        });
    }

    /**
     * Handles a streaming chunk from the LLM.
     */
    private void handleStreamChunk(LlmStreamChunk chunk, Display display) {
        if (chunk.isError()) {
            LOG.error("Stream error: %s", chunk.getErrorMessage()); //$NON-NLS-1$
            inflight.set(false);
            if (!display.isDisposed()) {
                display.asyncExec(() -> {
                    if (!isDisposed()) {
                        isStreaming = false;
                        handleError(new RuntimeException(chunk.getErrorMessage()));
                    }
                });
            }
            return;
        }

        // Plan 2.3: real provider-reported usage wins over local estimation.
        // Register it once per round-trip and gate the estimator paths below.
        if (chunk.hasUsage()) {
            if (!usageRegisteredForThisRoundTrip) {
                registerUsage(LlmResponse.builder().usage(chunk.getUsage()).build());
                usageRegisteredForThisRoundTrip = true;
            }
            return;
        }

        // Handle reasoning content delta (thinking mode)
        if (chunk.hasReasoning()) {
            streamingReasoning.append(chunk.getReasoningContent());

            final String accumulatedReasoning = streamingReasoning.toString();
            final String accumulatedContent = streamingContent.toString();

            if (!display.isDisposed()) {
                display.asyncExec(() -> {
                    if (!isDisposed() && USE_BROWSER_RENDERING && browserChatPanel != null) {
                        browserChatPanel.updateLastMessageWithReasoning(accumulatedContent, accumulatedReasoning);
                    }
                });
            }
        }

        // Append content delta
        String content = chunk.getContent();
        if (content != null && !content.isEmpty()) {
            streamingContent.append(content);

            // Update UI with accumulated content (and reasoning if present)
            final String accumulated = streamingContent.toString();
            final String accumulatedReasoning = streamingReasoning.toString();
            if (!display.isDisposed()) {
                display.asyncExec(() -> {
                    if (!isDisposed() && USE_BROWSER_RENDERING && browserChatPanel != null) {
                        if (accumulatedReasoning.isEmpty()) {
                            browserChatPanel.updateLastMessage(accumulated);
                        } else {
                            browserChatPanel.updateLastMessageWithReasoning(accumulated, accumulatedReasoning);
                        }
                    }
                });
            }
        }

        // Handle tool calls if present
        if (chunk.hasToolCalls() || chunk.isToolUse()) {
            if (!streamingHandledToolCalls.compareAndSet(false, true)) {
                LOG.warn("[single-flight] dropped duplicate tool-calls chunk; first dispatch already in flight"); //$NON-NLS-1$
                return;
            }
            LOG.debug("Stream received tool calls"); //$NON-NLS-1$
            final List<ToolCall> toolCalls = chunk.getToolCalls();
            final String accumulatedContent = streamingContent.toString();
            final String accumulatedReasoning = streamingReasoning.toString();

            if (!display.isDisposed()) {
                display.asyncExec(() -> {
                    if (!isDisposed()) {
                        isStreaming = false;
                        setProcessingStage("Обработка инструментов..."); //$NON-NLS-1$

                        // Create response object for tool handling. Only attach the
                        // estimator when no real stream usage has been registered yet
                        // — otherwise we'd double-count against the single-flight turn.
                        LlmResponse.Builder toolBuilder = LlmResponse.builder()
                                .content(accumulatedContent)
                                .reasoningContent(accumulatedReasoning)
                                .toolCalls(toolCalls)
                                .finishReason(LlmResponse.FINISH_REASON_TOOL_USE);
                        if (!usageRegisteredForThisRoundTrip) {
                            toolBuilder.usage(estimateUsageForResponse(currentStreamingRequest, accumulatedContent,
                                    accumulatedReasoning));
                        }
                        LlmResponse toolResponse = toolBuilder.build();
                        if (!usageRegisteredForThisRoundTrip) {
                            registerUsage(toolResponse);
                            usageRegisteredForThisRoundTrip = true;
                        }

                        // Update the displayed message with current content and reasoning.
                        // On a tool-call-only turn (no text/reasoning) drop the empty placeholder
                        // bubble so it doesn't leave a blank row in tool-heavy sessions.
                        if (USE_BROWSER_RENDERING && browserChatPanel != null) {
                            if (!accumulatedReasoning.isEmpty() || !accumulatedContent.isEmpty()) {
                                browserChatPanel.updateLastMessageWithReasoning(accumulatedContent, accumulatedReasoning);
                            } else {
                                browserChatPanel.removeLastMessageIfEmptyAssistant();
                            }
                        }

                        // Handle tool calls (this will continue the conversation loop)
                        ILlmProvider provider = LlmProviderRegistry.getInstance().getActiveProvider();
                        if (provider != null) {
                            handleResponseWithTools(toolResponse, provider, 0)
                                    .thenAccept(finalContent -> {
                                        inflight.set(false);
                                        if (!display.isDisposed()) {
                                            display.asyncExec(() -> {
                                                if (!isDisposed()) {
                                                    setProcessing(false);
                                                }
                                            });
                                        }
                                    })
                                    .exceptionally(error -> {
                                        inflight.set(false);
                                        if (!display.isDisposed()) {
                                            display.asyncExec(() -> {
                                                if (!isDisposed()) {
                                                    handleError(error);
                                                }
                                            });
                                        }
                                        return null;
                                    });
                        } else {
                            inflight.set(false);
                        }
                    }
                });
            }
            return;
        }

        // Handle completion (without tool calls)
        // Skip if tool calls were already handled - they will manage completion themselves
        if (chunk.isComplete() && !streamingHandledToolCalls.get()) {
            final String finalContent = streamingContent.toString();
            final String finishReason = chunk.getFinishReason() != null && !chunk.getFinishReason().isBlank()
                    ? chunk.getFinishReason()
                    : LlmResponse.FINISH_REASON_STOP;
            final boolean outputLimited = LlmResponse.FINISH_REASON_LENGTH.equals(finishReason);
            LOG.debug("Stream complete, content length: %d", finalContent.length()); //$NON-NLS-1$

            inflight.set(false);
            if (!display.isDisposed()) {
                display.asyncExec(() -> {
                    if (!isDisposed()) {
                        isStreaming = false;

                        // Add to conversation history
                        if (!finalContent.isEmpty()) {
                            // Plan 2.3: only estimate when the real stream-usage path
                            // did not already register usage for this round-trip.
                            if (!usageRegisteredForThisRoundTrip) {
                                LlmResponse usageResponse = LlmResponse.builder()
                                        .content(finalContent)
                                        .usage(estimateUsageForResponse(currentStreamingRequest, finalContent,
                                                streamingReasoning != null ? streamingReasoning.toString() : null))
                                        .finishReason(finishReason)
                                        .build();
                                registerUsage(usageResponse);
                                usageRegisteredForThisRoundTrip = true;
                            }
                            conversationHistory.add(LlmMessage.assistant(finalContent,
                                    streamingReasoning != null ? streamingReasoning.toString() : null));
                            lastAssistantResponse = finalContent;

                            // Check for code blocks
                            boolean hasCode = !CodeDiffUtils.extractCodeBlocks(finalContent).isEmpty();
                            applyCodeButton.setEnabled(hasCode);
                        }
                        if (outputLimited) {
                            appendSystemMessage(OUTPUT_LIMIT_WARNING);
                        }

                        setProcessing(false);
                    }
                });
            }
        } else if (chunk.isComplete() && streamingHandledToolCalls.get()) {
            LOG.debug("Stream complete ignored - tool calls are being processed"); //$NON-NLS-1$
        }
    }

    /**
     * Starts a non-streaming request to the LLM provider.
     */
    private void startNonStreamingRequest(ILlmProvider provider, LlmRequest request, Display display) {
        LOG.debug("startNonStreamingRequest: using non-streaming mode"); //$NON-NLS-1$

        long rt = roundTripSeq.incrementAndGet();
        if (!inflight.compareAndSet(false, true)) {
            LOG.warn("[single-flight] dropped duplicate request rt=%d (non-streaming entry)", rt); //$NON-NLS-1$
            return;
        }
        currentRoundTripId = rt;
        usageRegisteredForThisRoundTrip = false;
        // Plan 2.4: count accepted round-trips for the session footer.
        // Increment only after the CAS wins so duplicates don't inflate the count.
        requestCount++;

        // Send request
        currentRequestUsesDesktopController = false;
        currentRequest = provider.complete(request)
                .thenCompose(response -> {
                    registerUsage(response);
                    LOG.debug("startConversationLoop: response received, hasToolCalls=%b", response.hasToolCalls()); //$NON-NLS-1$
                    // Update stage
                    if (!display.isDisposed()) {
                        display.asyncExec(() -> {
                            if (!isDisposed()) {
                                if (response.hasToolCalls()) {
                                    setProcessingStage("Обработка инструментов..."); //$NON-NLS-1$
                                } else {
                                    setProcessingStage("Генерация ответа..."); //$NON-NLS-1$
                                }
                            }
                        });
                    }
                    return handleResponseWithTools(response, provider, 0);
                })
                .thenAccept(finalContent -> {
                    LOG.debug("startConversationLoop: chain completed successfully"); //$NON-NLS-1$
                    inflight.set(false);
                    if (!display.isDisposed()) {
                        display.asyncExec(() -> {
                            if (!isDisposed()) {
                                LOG.debug("startConversationLoop: calling setProcessing(false) from thenAccept"); //$NON-NLS-1$
                                // Final response already appended in handleResponseWithTools
                                setProcessing(false);
                            }
                        });
                    }
                })
                .exceptionally(error -> {
                    LOG.error("startConversationLoop: error in chain: %s", error.getMessage()); //$NON-NLS-1$
                    inflight.set(false);
                    if (!display.isDisposed()) {
                        display.asyncExec(() -> {
                            if (!isDisposed()) {
                                handleError(error);
                            }
                        });
                    }
                    return null;
                });
        LOG.debug("startNonStreamingRequest: request sent asynchronously"); //$NON-NLS-1$
    }

    /**
     * Builds an LLM request with the current conversation and available tools.
     */
    private LlmRequest buildRequestWithTools() {
        LlmRequest.Builder requestBuilder = LlmRequest.builder();

        // Add system prompt for 1C development
        requestBuilder.systemMessage(getSystemPrompt());

        // Add conversation history
        for (LlmMessage msg : conversationHistory) {
            requestBuilder.addMessage(msg);
        }

        // Add tools if enabled
        if (toolsEnabled) {
            List<ToolDefinition> tools = activeToolGate()
                    .visibleToolDefinitions(ToolRegistry.getInstance());
            requestBuilder.tools(tools);
            requestBuilder.toolChoice(LlmRequest.ToolChoice.AUTO);
        }

        // Set model override only for CodePilot backend; custom providers use their own configured model
        if (currentProviderCapabilities().isCodePilotBackend()) {
            requestBuilder.model(getEffectiveModelId());
        }

        return requestBuilder.build();
    }

    /**
     * Handles LLM response with tool call support.
     * Returns a CompletableFuture that completes when all tool calls are processed
     * and the final text response is available.
     */
    private CompletableFuture<String> handleResponseWithTools(
            LlmResponse response, ILlmProvider provider, int iteration) {
        final int maxToolIterations = getMaxToolIterations();

        LOG.debug("handleResponseWithTools: iteration=%d, hasToolCalls=%b, finishReason=%s", //$NON-NLS-1$
                iteration, response.hasToolCalls(), response.getFinishReason());

        final Display display = getDisplay();

        // Check for tool calls
        if (response.hasToolCalls() && iteration < maxToolIterations) {
            LOG.debug("handleResponseWithTools: processing %d tool calls", response.getToolCalls().size()); //$NON-NLS-1$
            // Process tool calls
            return processToolCalls(response, provider, iteration, display);
        }

        // Check if we hit max iterations limit
        if (response.hasToolCalls() && iteration >= maxToolIterations) {
            LOG.warn("handleResponseWithTools: tool budget (%d iterations) exhausted", maxToolIterations); //$NON-NLS-1$
            // Show warning to user
            if (!display.isDisposed()) {
                display.asyncExec(() -> {
                    if (!isDisposed()) {
                        appendSystemMessage(String.format(
                            "Исчерпан бюджет инструментов текущего ответа (%d шагов). " //$NON-NLS-1$
                                    + "Если задача еще не завершена, отправьте короткое продолжение: \"продолжай с текущего места\".", //$NON-NLS-1$
                            maxToolIterations));
                    }
                });
            }
        }

        LOG.debug("handleResponseWithTools: final response (no tool calls or max iterations)"); //$NON-NLS-1$
        // No tool calls - this is the final response
        String content = response.getContent();
        boolean outputLimited = response.isLengthLimited();
        LOG.debug("handleResponseWithTools: content length=%d, display.isDisposed=%b", //$NON-NLS-1$
                content != null ? content.length() : 0, display.isDisposed());

        if (!display.isDisposed()) {
            display.asyncExec(() -> {
                LOG.debug("handleResponseWithTools asyncExec: isDisposed=%b, content empty=%b", //$NON-NLS-1$
                        isDisposed(), content == null || content.isEmpty());
                if (!isDisposed()) {
                    if (content != null && !content.isEmpty()) {
                        LOG.debug("handleResponseWithTools: appending assistant message, length=%d", content.length()); //$NON-NLS-1$
                        appendAssistantMessage(content);
                        conversationHistory.add(LlmMessage.assistant(content, response.getReasoningContent()));

                        // Store response and check for code blocks
                        lastAssistantResponse = content;
                        boolean hasCode = !CodeDiffUtils.extractCodeBlocks(content).isEmpty();
                        applyCodeButton.setEnabled(hasCode);
                        LOG.debug("handleResponseWithTools: message appended successfully"); //$NON-NLS-1$
                    }
                    if (outputLimited) {
                        appendSystemMessage(OUTPUT_LIMIT_WARNING);
                    }
                }
            });
        }

        return CompletableFuture.completedFuture(content);
    }

    /**
     * Processes tool calls from the model response.
     * Intercepts edit_file calls for diff preview when preview mode is enabled.
     */
    private CompletableFuture<String> processToolCalls(
            LlmResponse response, ILlmProvider provider, int iteration, Display display) {

        LOG.debug("processToolCalls: starting with %d tool calls", response.getToolCalls().size()); //$NON-NLS-1$

        List<ToolCall> toolCalls = response.getToolCalls();
        String assistantContent = response.getContent();

        // Add assistant message with tool calls to history
        conversationHistory.add(LlmMessage.assistantWithToolCalls(
                assistantContent, response.getReasoningContent(), toolCalls));

        ChatToolGate gate = activeToolGate();
        ToolRegistry registry = ToolRegistry.getInstance();
        Map<String, ChatToolGate.Decision> decisions = new LinkedHashMap<>();
        Map<String, ITool> resolvedTools = new HashMap<>();
        List<ToolCall> deniedCalls = new ArrayList<>();
        List<ToolCall> editCalls = new ArrayList<>();
        List<ToolCall> otherCalls = new ArrayList<>();

        for (ToolCall call : toolCalls) {
            ITool resolved = registry.getTool(call.getName());
            resolvedTools.put(call.getId(), resolved);
            ChatToolGate.Decision decision = gate.decide(call, resolved);
            decisions.put(call.getId(), decision);
            if (decision.action() == ChatToolGate.Action.DENY) {
                deniedCalls.add(call);
                LOG.warn("permission_denied tool=%s profile=%s layer=%s resource=%s", //$NON-NLS-1$
                        call.getName(), gate.profile().getId(),
                        decision.layer(), decision.resource());
            } else if (gate.interceptForPreview(call, decision, previewModeEnabled)) {
                editCalls.add(call);
            } else {
                otherCalls.add(call);
            }
            if ("confirmation_skipped_by_preference".equals(decision.reasonCode())) { //$NON-NLS-1$
                LOG.debug("chat tool gate: tool=%s profile=%s reason_code=%s", //$NON-NLS-1$
                        call.getName(), gate.profile().getId(), decision.reasonCode());
            }
        }

        // Show rich tool call cards and update processing stage
        // Use reasoning content if available, otherwise fall back to assistant content
        final String reasoningContent = response.hasReasoning()
                ? response.getReasoningContent()
                : assistantContent;
        final int currentIteration = iteration;

        if (!display.isDisposed()) {
            display.asyncExec(() -> {
                if (!isDisposed()) {
                    // Build tool names for stage
                    StringBuilder toolNames = new StringBuilder();
                    for (int i = 0; i < toolCalls.size(); i++) {
                        if (i > 0) toolNames.append(", "); //$NON-NLS-1$
                        toolNames.append(toolCalls.get(i).getName());
                    }
                    setProcessingStage("Выполнение: " + toolNames.toString()); //$NON-NLS-1$

                    // Add rich tool call cards using browser panel
                    if (USE_BROWSER_RENDERING && browserChatPanel != null && !browserChatPanel.isDisposed()) {
                        // Show reasoning block if there's content between tool iterations
                        // (iteration > 0 means this is a follow-up after previous tool results)
                        if (currentIteration > 0 && reasoningContent != null && !reasoningContent.trim().isEmpty()) {
                            browserChatPanel.addReasoningBlock(reasoningContent);
                        }

                        List<BrowserChatPanel.ToolCallDisplayData> toolCallCards = new ArrayList<>();
                        for (ToolCall call : toolCalls) {
                            BrowserChatPanel.ToolCallDisplayData cardData =
                                new BrowserChatPanel.ToolCallDisplayData(
                                    call.getId(),
                                    call.getName(),
                                    call.getArguments()
                                );
                            // Set initial status to RUNNING
                            cardData.setStatus(BrowserChatPanel.ToolCallStatus.RUNNING);
                            toolCallCards.add(cardData);
                        }
                        browserChatPanel.addToolCallCards(toolCallCards);
                    } else {
                        // Fallback for non-browser mode
                        StringBuilder toolInfo = new StringBuilder();
                        toolInfo.append("\uD83D\uDD27 Использую инструменты:\n"); //$NON-NLS-1$
                        for (ToolCall call : toolCalls) {
                            String suffix = editCalls.contains(call)
                                    ? " (предпросмотр)" : ""; //$NON-NLS-1$ //$NON-NLS-2$
                            toolInfo.append("\u2022 ").append(call.getName()).append(suffix).append("\n"); //$NON-NLS-1$ //$NON-NLS-2$
                        }
                        appendSystemMessage(toolInfo.toString().trim());
                    }
                }
            });
        }

        // Create proposed change set for edit_file calls
        ProposedChangeSet proposedChanges = null;
        if (!editCalls.isEmpty()) {
            proposedChanges = new ProposedChangeSet(String.valueOf(System.currentTimeMillis()));
            for (ToolCall call : editCalls) {
                try {
                    ProposedChange change = createProposedChangeFromToolCall(
                            call, decisions.get(call.getId()).arguments());
                    proposedChanges.addChange(change);
                } catch (Exception e) {
                    // If we can't create proposed change, fall back to normal execution
                    otherCalls.add(call);
                }
            }
            currentProposedChanges = proposedChanges;
        }

        // Execute non-edit tool calls after the profile and permission decision.
        List<CompletableFuture<ToolResult>> futures = new ArrayList<>();
        List<ToolCall> executedCalls = new ArrayList<>();

        for (ToolCall call : deniedCalls) {
            executedCalls.add(call);
            futures.add(CompletableFuture.completedFuture(
                    decisions.get(call.getId()).denial()));
        }

        for (ToolCall call : otherCalls) {
            ITool tool = resolvedTools.get(call.getId());
            ChatToolGate.Decision decision = decisions.get(call.getId());
            executedCalls.add(call);

            if (decision.action() == ChatToolGate.Action.CONFIRM) {
                // Need confirmation on UI thread
                CompletableFuture<ToolResult> confirmedFuture = new CompletableFuture<>();

                // Check display before asyncExec to prevent hanging futures
                if (display.isDisposed()) {
                    LOG.warn("Display disposed, skipping tool confirmation for %s", call.getName()); //$NON-NLS-1$
                    confirmedFuture.complete(decision.confirmationUnavailableDenial());
                } else {
                    display.asyncExec(() -> {
                        try {
                            if (isDisposed() || getShell() == null) {
                                confirmedFuture.complete(decision.confirmationUnavailableDenial());
                                return;
                            }
                            if (tool == null) {
                                confirmedFuture.complete(decision.confirmationUnavailableDenial());
                                return;
                            }

                            ToolConfirmationDialog dialog = new ToolConfirmationDialog(
                                    getShell(),
                                    call,
                                    tool.getDescription(),
                                    tool.isDestructive(),
                                    decision.arguments()
                            );

                            if (dialog.openAndConfirm()) {
                                // User confirmed - execute the tool
                                registry.execute(call, decision.arguments(), null, null, decision.context())
                                        .thenAccept(confirmedFuture::complete)
                                        .exceptionally(e -> {
                                            confirmedFuture.complete(ToolResult.failure("Error: " + e.getMessage())); //$NON-NLS-1$
                                            return null;
                                        });
                            } else if (dialog.wasSkipped()) {
                                // User skipped - return skip message
                                confirmedFuture.complete(ToolResult.success(
                                        "Операция пропущена пользователем", //$NON-NLS-1$
                                        ToolResult.ToolResultType.CONFIRMATION));
                            } else {
                                // User cancelled - return cancelled message
                                confirmedFuture.complete(ToolResult.failure(
                                        "Операция отменена пользователем")); //$NON-NLS-1$
                            }
                        } catch (Throwable t) { // NOSONAR future must complete on every runnable exit
                            LOG.error(String.format(Locale.ROOT,
                                    "tool confirmation failed for %s", call.getName()), t); //$NON-NLS-1$
                            confirmedFuture.complete(ToolResult.failure(
                                    "Ошибка подтверждения инструмента: " + call.getName())); //$NON-NLS-1$
                        }
                    });
                }

                futures.add(confirmedFuture);
            } else {
                // No confirmation needed - execute directly
                futures.add(registry.execute(
                        call, decision.arguments(), null, null, decision.context()));
            }
        }

        // Capture proposed changes for closure
        final ProposedChangeSet capturedProposedChanges = proposedChanges;
        final List<ToolCall> capturedEditCalls = editCalls;

        // Wait for all non-edit tools to complete
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenCompose(v -> {
                    // Collect results from executed tools
                    Map<String, ToolResult> allResults = new HashMap<>();

                    for (int i = 0; i < executedCalls.size(); i++) {
                        ToolCall call = executedCalls.get(i);
                        ToolResult result;
                        try {
                            result = futures.get(i).join();
                        } catch (Exception e) {
                            result = ToolResult.failure("Error: " + e.getMessage()); //$NON-NLS-1$
                        }
                        allResults.put(call.getId(), result);
                    }

                    // Handle proposed changes on UI thread (only if there are actual content changes)
                    if (capturedProposedChanges != null && !capturedProposedChanges.isEmpty()
                            && capturedProposedChanges.hasActualChanges()) {
                        CompletableFuture<Map<String, ToolResult>> diffFuture = new CompletableFuture<>();

                        // Check display before asyncExec to prevent hanging futures
                        if (display.isDisposed()) {
                            LOG.warn("Display disposed, skipping diff review"); //$NON-NLS-1$
                            Map<String, ToolResult> skipped = new HashMap<>();
                            for (ToolCall call : capturedEditCalls) {
                                skipped.put(call.getId(), ToolResult.failure("Display disposed")); //$NON-NLS-1$
                            }
                            diffFuture.complete(skipped);
                        } else {
                            display.asyncExec(() -> {
                                if (isDisposed()) {
                                    // Return skipped results if view is disposed
                                    Map<String, ToolResult> skipped = new HashMap<>();
                                    for (ToolCall call : capturedEditCalls) {
                                        skipped.put(call.getId(), ToolResult.failure("View disposed")); //$NON-NLS-1$
                                    }
                                    diffFuture.complete(skipped);
                                    return;
                                }

                                try {
                                    Map<String, ToolResult> diffResults =
                                            showDiffReviewAndApply(capturedProposedChanges);
                                    diffFuture.complete(diffResults);
                                } catch (Exception e) {
                                    LOG.error("Error showing diff review: %s", e.getMessage()); //$NON-NLS-1$
                                    Map<String, ToolResult> errors = new HashMap<>();
                                    for (ToolCall call : capturedEditCalls) {
                                        errors.put(call.getId(), ToolResult.failure("Error: " + e.getMessage())); //$NON-NLS-1$
                                    }
                                    diffFuture.complete(errors);
                                }
                            });
                        }

                        return diffFuture.thenCompose(diffResults -> {
                            allResults.putAll(diffResults);
                            return continueAfterToolCalls(toolCalls, allResults, provider, iteration, display);
                        });
                    }

                    return continueAfterToolCalls(toolCalls, allResults, provider, iteration, display);
                });
    }

    private boolean shouldSkipToolConfirmations() {
        IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(CORE_PLUGIN_ID);
        return prefs.getBoolean(VibePreferenceConstants.PREF_AGENT_SKIP_TOOL_CONFIRMATIONS, false);
    }

    private synchronized ChatToolGate activeToolGate() {
        if (toolGate == null) {
            ChatTurnContext context = activeTurnContext();
            toolGate = createToolGate(context.profile());
        }
        return toolGate;
    }

    private synchronized ChatTurnContext activeTurnContext() {
        if (turnContext == null) {
            turnContext = ChatTurnContext.resolve(viewSession(), configuredChatProfileId());
        }
        return turnContext;
    }

    private String configuredChatProfileId() {
        return InstanceScope.INSTANCE.getNode(CORE_PLUGIN_ID)
                .get(PREF_CHAT_PROFILE_ID, ""); //$NON-NLS-1$
    }

    private ChatToolGate createToolGate(AgentProfile profile) {
        Display display = getDisplay();
        ToolRegistry registry = ToolRegistry.getInstance();
        return new ChatToolGate(
                profile,
                () -> PermissionManager.getInstance().getAllRules(),
                registry.getExecutionService()::parseArguments,
                registry::getDynamicToolNames,
                () -> display != null && !display.isDisposed() && !isDisposed(),
                this::shouldSkipToolConfirmations);
    }

    /**
     * Plan 1.2: feeds one tool call through {@link #toolRepetitionDetector} and
     * injects a synthetic USER nudge into the conversation history if the
     * repetition threshold is tripped. Safe to call for every tool call — the
     * detector is responsible for rate-limiting nudges.
     */
    private void observeAndInjectRepetitionNudge(ToolCall call) {
        try {
            String canonical = canonicalizeCallArgumentsForDetector(call.getArguments());
            java.util.Optional<com.codepilot1c.core.agent.ToolRepetitionDetector.Trip> maybeTrip =
                    toolRepetitionDetector.observe(call.getName(), canonical);
            if (maybeTrip.isEmpty()) {
                return;
            }
            com.codepilot1c.core.agent.ToolRepetitionDetector.Trip trip = maybeTrip.get();
            conversationHistory.add(LlmMessage.user(trip.localizedMessage()));
            LOG.debug("tool-repetition trip: %s x%d — nudge injected", //$NON-NLS-1$
                    trip.toolName, trip.identicalCount);
        } catch (RuntimeException e) {
            LOG.debug("tool-repetition detector failed: %s", e.getMessage()); //$NON-NLS-1$
        }
    }

    private static String canonicalizeCallArgumentsForDetector(String rawArgs) {
        if (rawArgs == null || rawArgs.isEmpty() || "{}".equals(rawArgs)) { //$NON-NLS-1$
            return com.codepilot1c.core.agent.ToolRepetitionDetector.canonicalizeArgs(
                    new com.google.gson.JsonObject());
        }
        try {
            com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(rawArgs);
            if (parsed != null && parsed.isJsonObject()) {
                return com.codepilot1c.core.agent.ToolRepetitionDetector.canonicalizeArgs(
                        parsed.getAsJsonObject());
            }
            return rawArgs;
        } catch (RuntimeException e) {
            return rawArgs;
        }
    }

    private int getMaxToolIterations() {
        IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(CORE_PLUGIN_ID);
        return prefs.getInt(
                VibePreferenceConstants.PREF_MAX_TOOL_ITERATIONS,
                VibePreferenceConstants.DEFAULT_MAX_TOOL_ITERATIONS);
    }

    /**
     * Continues conversation after tool calls are processed.
     */
    private CompletableFuture<String> continueAfterToolCalls(
            List<ToolCall> toolCalls,
            Map<String, ToolResult> allResults,
            ILlmProvider provider,
            int iteration,
            Display display) {

        LOG.debug("continueAfterToolCalls: %d tool calls, %d results, iteration=%d", //$NON-NLS-1$
                toolCalls.size(), allResults.size(), iteration);

        // Add tool results to conversation history
        for (ToolCall call : toolCalls) {
            ToolResult result = allResults.get(call.getId());
            if (result == null) {
                result = ToolResult.failure("Результат не найден"); //$NON-NLS-1$
            }
            conversationHistory.add(LlmMessage.toolResult(
                    call.getId(),
                    result.getContentForLlm(MAX_TOOL_RESULT_HISTORY_CHARS)));
        }

        if (GsdToolMutationRefreshPolicy.shouldRefresh(toolCalls, allResults)) {
            requestGsdStatusRefresh();
        }

        // Plan 1.2: detect repetition loops only after all tool-result messages
        // for the assistant tool_calls batch were appended. Inserting a USER nudge
        // between assistant tool_calls and their tool results violates the OpenAI
        // message protocol and causes follow-up requests to fail.
        for (ToolCall call : toolCalls) {
            observeAndInjectRepetitionNudge(call);
        }

        // Update tool call cards with results
        if (!display.isDisposed()) {
            display.asyncExec(() -> {
                if (!isDisposed()) {
                    for (ToolCall call : toolCalls) {
                        ToolResult result = allResults.get(call.getId());
                        if (result != null) {
                            updateToolCallCardWithResult(call, result);
                        }
                    }
                }
            });
        }

        // Continue conversation with tool results
        LOG.debug("continueAfterToolCalls: sending next request to LLM"); //$NON-NLS-1$

        // Update stage before sending next request
        if (!display.isDisposed()) {
            display.asyncExec(() -> {
                if (!isDisposed()) {
                    setProcessingStage("Ожидание ответа модели..."); //$NON-NLS-1$
                }
            });
        }

        LlmRequest nextRequest = buildRequestWithTools();
        CompletableFuture<LlmResponse> nextResponseFuture = provider.supportsStreaming()
                ? streamToResponse(provider, nextRequest)
                : provider.complete(nextRequest);

        return nextResponseFuture.thenCompose(nextResponse -> {
                    registerUsage(nextResponse);
                    LOG.debug("continueAfterToolCalls: got next response, hasToolCalls=%b", nextResponse.hasToolCalls()); //$NON-NLS-1$
                    // Update stage based on response
                    if (!display.isDisposed()) {
                        display.asyncExec(() -> {
                            if (!isDisposed()) {
                                if (nextResponse.hasToolCalls()) {
                                    setProcessingStage("Обработка инструментов..."); //$NON-NLS-1$
                                } else {
                                    setProcessingStage("Генерация ответа..."); //$NON-NLS-1$
                                }
                            }
                        });
                    }
                    return handleResponseWithTools(nextResponse, provider, iteration + 1);
                });
    }

    /**
     * Converts a streaming request into a single LlmResponse, so the tool loop can keep working
     * without forcing a non-streaming call (which may time out on slow/self-hosted providers).
     */
    private CompletableFuture<LlmResponse> streamToResponse(ILlmProvider provider, LlmRequest request) {
        CompletableFuture<LlmResponse> out = new CompletableFuture<>();

        CompletableFuture.runAsync(() -> {
            StringBuilder content = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            List<ToolCall> toolCalls = new java.util.ArrayList<>();
            final String[] finishReason = { LlmResponse.FINISH_REASON_STOP };
            // Plan 2.3: capture real usage from the stream terminal usage chunk so
            // it survives the stream→response conversion and is accumulated by the
            // caller's registerUsage(nextResponse).
            final LlmResponse.Usage[] streamUsage = { null };

            try {
                provider.streamComplete(request, chunk -> {
                    if (out.isDone()) {
                        return;
                    }

                    if (chunk.isError()) {
                        out.completeExceptionally(new RuntimeException(chunk.getErrorMessage()));
                        return;
                    }

                    if (chunk.hasUsage() && streamUsage[0] == null) {
                        streamUsage[0] = chunk.getUsage();
                        return;
                    }

                    if (chunk.hasReasoning()) {
                        reasoning.append(chunk.getReasoningContent());
                    }

                    String delta = chunk.getContent();
                    if (delta != null && !delta.isEmpty()) {
                        content.append(delta);
                    }

                    if (chunk.hasToolCalls()) {
                        toolCalls.clear();
                        toolCalls.addAll(chunk.getToolCalls());
                    }

                    if (chunk.isComplete()) {
                        if (chunk.getFinishReason() != null && !chunk.getFinishReason().isEmpty()) {
                            finishReason[0] = chunk.getFinishReason();
                        }

                        if (!toolCalls.isEmpty()) {
                            out.complete(LlmResponse.builder()
                                    .content(content.toString())
                                    .reasoningContent(reasoning.toString())
                                    .toolCalls(toolCalls)
                                    .finishReason(LlmResponse.FINISH_REASON_TOOL_USE)
                                    .usage(streamUsage[0])
                                    .build());
                        } else {
                            out.complete(LlmResponse.builder()
                                    .content(content.toString())
                                    .reasoningContent(reasoning.toString())
                                    .finishReason(finishReason[0])
                                    .usage(streamUsage[0])
                                    .build());
                        }
                    }
                });

                // Some providers may end the stream without an explicit complete chunk.
                if (!out.isDone()) {
                    if (!toolCalls.isEmpty()) {
                        out.complete(LlmResponse.builder()
                                .content(content.toString())
                                .reasoningContent(reasoning.toString())
                                .toolCalls(toolCalls)
                                .finishReason(LlmResponse.FINISH_REASON_TOOL_USE)
                                .usage(streamUsage[0])
                                .build());
                    } else {
                        out.complete(LlmResponse.builder()
                                .content(content.toString())
                                .reasoningContent(reasoning.toString())
                                .finishReason(finishReason[0])
                                .usage(streamUsage[0])
                                .build());
                    }
                }
            } catch (Exception e) {
                out.completeExceptionally(e);
            }
        });

        return out;
    }

    /**
     * Updates a tool call card with the result (for browser-based rendering).
     */
    private void updateToolCallCardWithResult(ToolCall call, ToolResult result) {
        if (USE_BROWSER_RENDERING && browserChatPanel != null && !browserChatPanel.isDisposed()) {
            // Determine status
            BrowserChatPanel.ToolCallStatus status = result.isSuccess()
                    ? BrowserChatPanel.ToolCallStatus.SUCCESS
                    : BrowserChatPanel.ToolCallStatus.ERROR;

            // Build result summary (e.g., "Готово · 1,240 символов" or "Ошибка")
            String content = result.isSuccess() ? result.getContent() : result.getErrorMessage();
            int contentLength = content != null ? content.length() : 0;
            String lengthLabel = ""; //$NON-NLS-1$
            if (contentLength > 0) {
                lengthLabel = contentLength >= 1000
                        ? String.format("%,d символов", contentLength) //$NON-NLS-1$
                        : String.format("%d символов", contentLength); //$NON-NLS-1$
            }

            String resultSummary;
            if (result.isSuccess()) {
                resultSummary = lengthLabel.isEmpty()
                        ? "Готово" //$NON-NLS-1$
                        : "Готово · " + lengthLabel; //$NON-NLS-1$
            } else {
                resultSummary = lengthLabel.isEmpty()
                        ? "Ошибка" //$NON-NLS-1$
                        : "Ошибка · " + lengthLabel; //$NON-NLS-1$
            }

            String resultPreview = content != null ? content : ""; //$NON-NLS-1$

            browserChatPanel.updateToolCallResult(call.getId(), status, resultSummary, resultPreview);
        } else {
            // Fallback to old style for non-browser mode
            appendToolResultMessage(call, result);
        }
    }

    /**
     * Appends a tool result message to the chat UI (fallback for non-browser mode).
     */
    private void appendToolResultMessage(ToolCall call, ToolResult result) {
        if (messagesContainer == null || messagesContainer.isDisposed()) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        String icon = result.isSuccess() ? "\u2713" : "\u2717"; // ✓ or ✗ //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(icon).append(" **").append(getToolDisplayName(call.getName())).append("**\n\n"); //$NON-NLS-1$ //$NON-NLS-2$

        String content = result.isSuccess() ? result.getContent() : result.getErrorMessage();
        if (content != null && !content.isEmpty()) {
            sb.append(content);
        }

        appendMessage("Инструмент", sb.toString(), false); //$NON-NLS-1$
    }

    /**
     * Creates a ProposedChange from an edit_file tool call.
     */
    private ProposedChange createProposedChangeFromToolCall(
            ToolCall call, Map<String, Object> approvedArguments) {
        String filePath = (String) approvedArguments.get("path"); //$NON-NLS-1$
        if (filePath == null) {
            filePath = (String) approvedArguments.get("file_path"); //$NON-NLS-1$
        }

        String newContent = (String) approvedArguments.get("content"); //$NON-NLS-1$
        String oldString = (String) approvedArguments.get("old_string"); //$NON-NLS-1$
        String newString = (String) approvedArguments.get("new_string"); //$NON-NLS-1$

        // Read current file content for diff
        String beforeContent = readFileContent(filePath);
        String afterContent;
        ProposedChange.ChangeKind kind;

        if (beforeContent == null) {
            // New file
            afterContent = newContent != null ? newContent : newString;
            kind = ProposedChange.ChangeKind.CREATE;
        } else if (oldString != null && newString != null) {
            // Search and replace
            afterContent = beforeContent.replace(oldString, newString);
            kind = ProposedChange.ChangeKind.MODIFY;
        } else if (newContent != null) {
            // Full file replacement
            afterContent = newContent;
            kind = ProposedChange.ChangeKind.REPLACE;
        } else {
            // Invalid args, fall back to replace
            afterContent = beforeContent;
            kind = ProposedChange.ChangeKind.MODIFY;
        }

        return new ProposedChange(filePath, beforeContent, afterContent, kind, call.getId());
    }

    /**
     * Reads file content from workspace.
     */
    private String readFileContent(String filePath) {
        if (filePath == null) {
            return null;
        }

        try {
            org.eclipse.core.resources.IWorkspaceRoot root =
                org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot();

            // Normalize path
            String normalized = filePath;
            if (normalized.startsWith("/") && !normalized.startsWith("//")) { //$NON-NLS-1$ //$NON-NLS-2$
                normalized = normalized.substring(1);
            }
            normalized = normalized.replace('\\', '/');

            org.eclipse.core.resources.IFile file = root.getFile(
                org.eclipse.core.runtime.Path.fromPortableString(normalized));

            if (file.exists()) {
                try (java.io.InputStream is = file.getContents()) {
                    return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        } catch (Exception e) {
            // File doesn't exist or can't be read
        }
        return null;
    }

    /**
     * Shows the diff review dialog and applies accepted changes.
     * Returns tool results for the LLM.
     */
    private Map<String, ToolResult> showDiffReviewAndApply(ProposedChangeSet changeSet) {
        Map<String, ToolResult> results = new HashMap<>();

        if (changeSet == null || changeSet.isEmpty()) {
            return results;
        }

        DiffReviewDialog dialog = new DiffReviewDialog(getShell(), changeSet);
        boolean applied = dialog.openAndApply();

        // Create results for each proposed change
        for (ProposedChange change : changeSet.getChanges()) {
            ToolResult result;
            switch (change.getStatus()) {
                case APPLIED:
                    result = ToolResult.success(
                        String.format("Файл %s успешно изменён", change.getFileName()), //$NON-NLS-1$
                        ToolResult.ToolResultType.CONFIRMATION);
                    break;
                case REJECTED:
                    result = ToolResult.success(
                        String.format("Изменение файла %s отклонено пользователем", change.getFileName()), //$NON-NLS-1$
                        ToolResult.ToolResultType.CONFIRMATION);
                    break;
                case FAILED:
                    result = ToolResult.failure(
                        String.format("Не удалось применить изменения к %s", change.getFileName())); //$NON-NLS-1$
                    break;
                default:
                    result = ToolResult.success(
                        String.format("Изменение файла %s ожидает рассмотрения", change.getFileName()), //$NON-NLS-1$
                        ToolResult.ToolResultType.CONFIRMATION);
            }

            if (change.getToolCallId() != null) {
                results.put(change.getToolCallId(), result);
            }
        }

        return results;
    }

    /**
     * Returns human-readable tool name.
     */
    private String getToolDisplayName(String name) {
        return ToolDisplayNames.get(name);
    }

    /**
     * Checks if this view's widgets are disposed.
     *
     * @return true if disposed
     */
    private boolean isDisposed() {
        if (USE_BROWSER_RENDERING) {
            return browserChatPanel == null || browserChatPanel.isDisposed();
        }
        return scrolledComposite == null || scrolledComposite.isDisposed();
    }

    /**
     * Returns the shell for dialogs.
     */
    private org.eclipse.swt.widgets.Shell getShell() {
        if (USE_BROWSER_RENDERING && browserChatPanel != null && !browserChatPanel.isDisposed()) {
            return browserChatPanel.getShell();
        }
        if (scrolledComposite != null && !scrolledComposite.isDisposed()) {
            return scrolledComposite.getShell();
        }
        return Display.getDefault().getActiveShell();
    }

    /**
     * Returns the display.
     */
    private Display getDisplay() {
        if (USE_BROWSER_RENDERING && browserChatPanel != null && !browserChatPanel.isDisposed()) {
            return browserChatPanel.getDisplay();
        }
        if (scrolledComposite != null && !scrolledComposite.isDisposed()) {
            return scrolledComposite.getDisplay();
        }
        return Display.getDefault();
    }

    private String getSystemPrompt() {
        StringBuilder prompt = new StringBuilder();

        // Get workspace path early
        String workspacePath = ""; //$NON-NLS-1$
        try {
            workspacePath = org.eclipse.core.resources.ResourcesPlugin
                    .getWorkspace().getRoot().getLocation().toOSString();
        } catch (Exception e) {
            workspacePath = "/путь/к/workspace"; //$NON-NLS-1$
        }

        // === ROLE & IDENTITY (OpenCode pattern) ===
        prompt.append("""
            Вы - Vibe, лучший агент-разработчик для платформы 1С:Предприятие.

            Вы - интерактивный инструмент в 1C EDT, который помогает с задачами разработки.
            Используйте инструкции ниже и доступные инструменты для помощи пользователю.

            # Тон и стиль

            - ВСЕГДА отвечайте пользователю на русском языке, если он явно не попросил другой язык.
            - НЕ используйте эмодзи, если пользователь явно не попросит.
            - Ответы должны быть КОРОТКИМИ и ЛАКОНИЧНЫМИ.
            - Используйте Markdown для форматирования.
            - Выводите текст для общения с пользователем. Инструменты - только для выполнения задач.
            - НИКОГДА не создавайте файлы без необходимости. ВСЕГДА предпочитайте редактирование существующих.

            # Профессиональная объективность

            Приоритет - техническая точность, а не подтверждение убеждений пользователя.
            Фокус на фактах и решении проблем. Прямая, объективная техническая информация
            без лишних комплиментов или эмоциональной валидации.
            При неуверенности - исследуйте, а не подтверждайте догадки пользователя.

            # Ссылки на код

            При упоминании функций или кода ВСЕГДА указывайте путь и номер строки:
            `путь/к/файлу.bsl:123`

            <example>
            user: Где обрабатывается проведение документа?
            assistant: Проведение обрабатывается в процедуре `ОбработкаПроведения` в
            src/Documents/РеализацияТоваров/ObjectModule.bsl:245
            </example>

            """); //$NON-NLS-1$

        // === TOOLS SECTION ===
        // NB: we intentionally do NOT enumerate tools here. The full manifest is
        // delivered via the structured `tools` parameter on the LLM request; mirroring
        // it in the system prompt burned ~1200 tokens of redundant input per request
        // (codex review of Phase 3 flagged this as the primary duplicated overhead).
        if (toolsEnabled) {
            boolean hasTools = !activeToolGate()
                    .visibleToolDefinitions(ToolRegistry.getInstance()).isEmpty();
            ChatSystemPromptToolsSection.append(prompt, hasTools);
        }

        // === FINAL INSTRUCTIONS ===
        prompt.append("""
            # Контекст редактора

            Если в сообщении есть информация о текущем файле или выделенном коде -
            это контекст из активного редактора. Учитывайте его при ответе.
            """); //$NON-NLS-1$

        ChatTurnContext context = activeTurnContext();
        // activeToolGate() and context.profile() are created together and stay
        // fixed until the next top-level user turn.
        if (activeToolGate().profile() != context.profile()) {
            throw new IllegalStateException("Chat turn profile mismatch"); //$NON-NLS-1$
        }
        return SystemPromptAssembler.getInstance()
                .assembleDetailed(context.promptInput(prompt.toString(), currentRequestedSkills))
                .prompt();
    }

    private void handleError(Throwable error) {
        LOG.error("handleError: %s", error.getMessage()); //$NON-NLS-1$
        if (error.getCause() != null) {
            LOG.error("handleError cause: %s", error.getCause().getMessage()); //$NON-NLS-1$
        }

        // Extract the root cause (may be wrapped in CompletionException etc.)
        Throwable root = error.getCause() != null ? error.getCause() : error;
        String userMessage = formatUserFriendlyError(root);

        appendSystemMessage(userMessage);
        LOG.debug("handleError: calling setProcessing(false)"); //$NON-NLS-1$
        setProcessing(false);
    }

    /**
     * Formats a user-friendly error message, handling rate-limit and budget errors specially.
     */
    private String formatUserFriendlyError(Throwable error) {
        if (error instanceof com.codepilot1c.core.provider.LlmProviderException providerEx) {

            // Rate limit — spending window exceeded
            if (providerEx.isSpendWindowError()) {
                var details = providerEx.getRateLimitDetails();
                if (details != null) {
                    String window = "5h".equals(details.window()) ? "5 часов" : //$NON-NLS-1$ //$NON-NLS-2$
                                    "7d".equals(details.window()) ? "7 дней" : details.window(); //$NON-NLS-1$ //$NON-NLS-2$
                    String retryInfo;
                    if (details.retryAtLocal() != null && !details.retryAtLocal().isEmpty()) {
                        retryInfo = String.format("Попробуйте после %s", details.retryAtLocal()); //$NON-NLS-1$
                    } else if (details.retryAfterSeconds() > 0) {
                        retryInfo = String.format("Подождите %s", details.retryWaitFormatted()); //$NON-NLS-1$
                    } else {
                        retryInfo = "Подождите — лимит обновится автоматически"; //$NON-NLS-1$
                    }
                    return String.format(
                            "\u26A0\uFE0F Превышен лимит за %s. %s.", //$NON-NLS-1$
                            window,
                            retryInfo);
                }
                return "\u26A0\uFE0F Превышен лимит расхода. Подождите — лимит обновится автоматически."; //$NON-NLS-1$
            }

            // Budget fully exhausted
            if (providerEx.isBudgetExhausted()) {
                return "\u26D4 Бюджет исчерпан. Пополните баланс или перейдите на другой тариф."; //$NON-NLS-1$
            }

            // Generic rate limit (429 without specific code)
            if (providerEx.isRateLimitError()) {
                return "\u23F3 Слишком много запросов. Подождите несколько секунд и попробуйте снова."; //$NON-NLS-1$
            }

            // Authentication error
            if (providerEx.isAuthenticationError()) {
                return "\u274C Ошибка авторизации. Проверьте настройки аккаунта CodePilot."; //$NON-NLS-1$
            }
        }

        // Default: show raw message
        return java.text.MessageFormat.format(Messages.ChatView_ErrorMessage, error.getMessage());
    }

    /**
     * Applies the code from the last AI response to the active editor.
     */
    private void applyCodeToEditor() {
        if (lastAssistantResponse == null || lastAssistantResponse.isEmpty()) {
            return;
        }

        CodeApplicationService codeService = CodeApplicationService.getInstance();
        CodeApplicationService.SelectionInfo selection = codeService.getCurrentSelection();

        boolean hasSelection = selection != null && selection.hasSelection();

        // Ask user how to apply code
        String[] buttons = hasSelection
                ? new String[] { Messages.ChatView_ReplaceSelection, Messages.ChatView_InsertAtCursor, Messages.ChatView_Cancel }
                : new String[] { Messages.ChatView_InsertAtCursor, Messages.ChatView_Cancel };

        MessageDialog dialog = new MessageDialog(
                getShell(),
                Messages.ChatView_ApplyCodeTitle,
                null,
                Messages.ChatView_ApplyCodeMessage,
                MessageDialog.QUESTION,
                buttons,
                0);

        int result = dialog.open();

        boolean replaceSelection;
        if (hasSelection) {
            if (result == 0) {
                replaceSelection = true;
            } else if (result == 1) {
                replaceSelection = false;
            } else {
                return; // Cancelled
            }
        } else {
            if (result == 0) {
                replaceSelection = false;
            } else {
                return; // Cancelled
            }
        }

        boolean success = codeService.applyFromResponse(lastAssistantResponse, replaceSelection);

        if (success) {
            appendSystemMessage(Messages.ChatView_CodeAppliedSuccess);
        } else {
            appendSystemMessage(Messages.ChatView_CodeAppliedFailed);
        }
    }

    private void stopGeneration() {
        if (currentRequest != null && !currentRequest.isDone()) {
            currentRequest.cancel(true);
            ILlmProvider provider = LlmProviderRegistry.getInstance().getActiveProvider();
            if (provider != null) {
                provider.cancel();
            }
            if (currentRequestUsesDesktopController) {
                AgentSessionController.getInstance().stopFromDesktop();
            }
        }
        currentRequestUsesDesktopController = false;
        inflight.set(false);
        setProcessing(false);
    }

    /**
     * Confirms with user before clearing chat when conversation is non-empty.
     */
    private void confirmAndClearChat() {
        if (conversationHistory.isEmpty()) {
            clearChat();
            return;
        }
        boolean confirmed = MessageDialog.openConfirm(
                getSite().getShell(),
                Messages.ChatView_NewChatConfirmTitle,
                Messages.ChatView_NewChatConfirmMessage);
        if (confirmed) {
            clearChat();
        }
    }

    /**
     * This view's chat session (Phase 0 — persistence/multi-chat foundation). Bound on first use to
     * the current session; the {@link #session} field is the seam for full per-view ownership later.
     */
    private Session viewSession() {
        if (session == null) {
            // Multi-view: each ChatView instance owns a distinct session (not the global current one).
            session = SessionManager.getInstance().createSessionForCurrentProject();
            turnContext = null;
            toolGate = null;
            requestGsdStatusRefresh();
        }
        return session;
    }

    /**
     * Opens a new ChatView instance (multi-view) so the user can run another chat in parallel. The new
     * instance gets a unique secondary id and starts a fresh session.
     */
    private void openNewChatWindow() {
        try {
            String secondaryId = "chat-" + Long.toHexString(System.nanoTime()); //$NON-NLS-1$
            getSite().getWorkbenchWindow().getActivePage()
                    .showView(ID, secondaryId, IWorkbenchPage.VIEW_ACTIVATE);
        } catch (PartInitException e) {
            LOG.warn("openNewChatWindow failed: %s", e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * Appends the current UI conversation (USER/ASSISTANT text) into the given session record.
     * Used at clear-chat and on close so the session reflects what the user sees.
     */
    private void syncSessionFromHistory(Session target) {
        // Rebuild (not append) so re-saving a restored session does not duplicate messages.
        target.clearMessages();
        for (LlmMessage msg : conversationHistory) {
            LlmMessage.Role role = msg.getRole();
            if (role != LlmMessage.Role.USER && role != LlmMessage.Role.ASSISTANT) {
                continue;
            }
            String content = msg.getContent();
            if ((content == null || content.isBlank()) && msg.getContentParts() != null) {
                StringBuilder sb = new StringBuilder();
                for (var part : msg.getContentParts()) {
                    if (part.getText() != null) {
                        sb.append(part.getText());
                    }
                }
                content = sb.toString();
            }
            if (content != null && !content.isBlank()) {
                target.addMessage(role == LlmMessage.Role.USER
                        ? SessionMessage.user(content)
                        : SessionMessage.assistant(content, msg.getReasoningContent()));
            }
        }
    }

    /**
     * Persists this view's session to disk. Save-on-close entry point (Phase 0): called from
     * {@link #dispose()} so the conversation survives EDT restart. No-op for an empty chat.
     */
    private void persistSession() {
        try {
            if (conversationHistory.isEmpty()) {
                return;
            }
            Session target = viewSession();
            syncSessionFromHistory(target);
            SessionManager.getInstance().saveSession(target);
            LOG.debug("persistSession: saved session %s (%d messages)", //$NON-NLS-1$
                    target.getId(), Integer.valueOf(target.getMessages().size()));
        } catch (Exception e) {
            LOG.debug("persistSession failed: %s", e.getMessage()); //$NON-NLS-1$
        }
    }

    /**
     * On view open (Phase 0 — chat persistence): restores the most recent saved chat into this view,
     * or shows the welcome message when there is nothing to restore.
     */
    private void restoreLastSessionOrWelcome() {
        try {
            Session restored = null;
            if (restoredSessionId != null && !restoredSessionId.isBlank()) {
                // Restart: Eclipse recreated this view instance — restore its exact session.
                restored = SessionManager.getInstance().loadSession(restoredSessionId).orElse(null);
            } else if (getViewSite().getSecondaryId() == null) {
                // Primary view, fresh open: restore the most recent chat (Feature 1).
                restored = loadMostRecentSession();
            }
            // else: a window explicitly opened by the user (secondary id, no memento) → start fresh.
            if (restored != null) {
                session = restored;
                turnContext = null;
                toolGate = null;
                // Restore this window's per-view model choice.
                overrideModelId = restored.getModelId();
                SessionManager.getInstance().setCurrentSession(restored);
                if (restored.getMessages().isEmpty()) {
                    appendSystemMessage(Messages.ChatView_WelcomeMessage);
                } else {
                    renderSession(restored);
                }
                updateModelButtonLabel();
                refreshGsdStatus();
                return;
            }
        } catch (Exception e) {
            LOG.debug("restoreLastSession failed: %s", e.getMessage()); //$NON-NLS-1$
        }
        viewSession();
        appendSystemMessage(Messages.ChatView_WelcomeMessage);
        requestGsdStatusRefresh();
    }

    /** @return the most recent non-empty saved session, or {@code null}. */
    private Session loadMostRecentSession() {
        SessionManager manager = SessionManager.getInstance();
        for (com.codepilot1c.core.session.ISessionStore.SessionSummary summary
                : manager.listRecentSessions(10)) {
            if (summary.getMessageCount() <= 0) {
                continue;
            }
            Session loaded = manager.loadSession(summary.getId()).orElse(null);
            if (loaded != null && !loaded.getMessages().isEmpty()) {
                return loaded;
            }
        }
        return null;
    }

    /**
     * Replays a saved session into the view: seeds the LLM context and re-renders the visible
     * user/assistant bubbles. Tool-call/system detail is not replayed in this v1.
     */
    private void renderSession(Session restored) {
        conversationHistory.clear();
        conversationHistory.addAll(restored.toLlmMessages());
        for (SessionMessage msg : restored.getMessages()) {
            String content = msg.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            switch (msg.getType()) {
                case USER -> appendUserMessage(content);
                case ASSISTANT -> appendAssistantMessage(content);
                default -> { /* tool/system messages are not replayed in v1 */ }
            }
        }
        LOG.debug("renderSession: restored %d messages from session %s", //$NON-NLS-1$
                Integer.valueOf(restored.getMessages().size()), restored.getId());
    }

    private void clearChat() {
        // Sync UI conversation history into SessionManager and complete session.
        // This triggers memory extraction for facts like "Запомни что...".
        try {
            if (!conversationHistory.isEmpty()) {
                Session target = viewSession();
                syncSessionFromHistory(target);
                // Complete THIS view's session (save + memory extraction), not the global current.
                SessionManager.getInstance().completeSession(target);
                LOG.debug("clearChat: completed session %s (%d UI messages)", //$NON-NLS-1$
                        target.getId(), Integer.valueOf(conversationHistory.size()));
            }
            // Replace only this view's session; other ChatViews remain untouched.
            session = SessionManager.getInstance().createSessionForCurrentProject();
            turnContext = null;
            toolGate = null;
        } catch (Exception e) {
            LOG.debug("clearChat: session management failed: " + e.getMessage()); //$NON-NLS-1$
            session = null;
            turnContext = null;
            toolGate = null;
        }

        if (USE_BROWSER_RENDERING) {
            clearChatBrowser();
        } else {
            clearChatStyledText();
        }

        conversationHistory.clear();
        // Plan 1.2: full clear is also a turn boundary — drop any pending
        // repetition window so a fresh conversation starts clean.
        toolRepetitionDetector.resetForNewTurn();
        draftAttachments.clear();
        lastAssistantResponse = null;
        resetTokenUsage();
        if (!isDisposed()) {
            applyCodeButton.setEnabled(false);
        }
        refreshAttachmentPreview();

        appendSystemMessage(Messages.ChatView_WelcomeMessage);
        requestGsdStatusRefresh();
    }

    private void clearChatBrowser() {
        if (browserChatPanel != null && !browserChatPanel.isDisposed()) {
            browserChatPanel.clearChat();
        }
    }

    private void clearChatStyledText() {
        // Dispose all message widgets
        for (ChatMessageComposite widget : messageWidgets) {
            if (!widget.isDisposed()) {
                widget.dispose();
            }
        }
        messageWidgets.clear();

        // Clear children of messages container (except typing indicator)
        if (messagesContainer != null && !messagesContainer.isDisposed()) {
            for (Control child : messagesContainer.getChildren()) {
                if (!child.isDisposed() && child != typingIndicator) {
                    child.dispose();
                }
            }

            // Hide typing indicator if visible
            if (typingIndicator != null && !typingIndicator.isDisposed()) {
                typingIndicator.hide();
            }

            messagesContainer.layout(true, true);
            updateScrollSize();
        }
    }

    private void setProcessing(boolean processing) {
        setProcessing(processing, null);
    }

    private void setProcessing(boolean processing, String stage) {
        LOG.debug("setProcessing: changing from %b to %b, stage=%s", this.isProcessing, processing, stage); //$NON-NLS-1$
        this.isProcessing = processing;
        if (!isDisposed()) {
            sendButton.setEnabled(!processing);
            if (attachButton != null && !attachButton.isDisposed()) {
                attachButton.setEnabled(!processing);
            }
            updateInitCodeMdButton();
            stopButton.setEnabled(processing);
            inputField.setEnabled(!processing);
            if (compactButton != null && !compactButton.isDisposed()) {
                compactButton.setEnabled(!processing);
            }
            if (modelButton != null && !modelButton.isDisposed()) {
                modelButton.setEnabled(!processing);
            }
            refreshAttachmentPreview();

            // Show/hide typing indicator
            if (USE_BROWSER_RENDERING) {
                if (browserChatPanel != null && browserChatPanel.isBrowserAvailable()) {
                    browserChatPanel.showTypingIndicator(processing, stage);
                }
            } else {
                if (typingIndicator != null && !typingIndicator.isDisposed()) {
                    if (processing) {
                        // Move indicator to end of messages
                        typingIndicator.moveBelow(null);
                        typingIndicator.show();
                        updateScrollSize();
                        scrollToBottom();
                    } else {
                        typingIndicator.hide();
                        updateScrollSize();
                    }
                }
            }
        }
    }

    /**
     * Updates the processing stage text without changing the processing state.
     *
     * @param stage the stage description
     */
    private void setProcessingStage(String stage) {
        if (!isDisposed() && isProcessing && USE_BROWSER_RENDERING) {
            if (browserChatPanel != null && browserChatPanel.isBrowserAvailable()) {
                browserChatPanel.setProcessingStage(stage);
            }
        }
    }

    private void appendUserMessage(String message) {
        appendUserMessage(message, List.of());
    }

    private void appendUserMessage(String message, List<LlmAttachment> attachments) {
        appendMessage("Вы", message, false, attachments); //$NON-NLS-1$
    }

    private void appendAssistantMessage(String message) {
        appendMessage("AI", message, true, List.of()); //$NON-NLS-1$
    }

    /**
     * Returns the currently active model name for display in message badges.
     */
    private String getCurrentModelName() {
        String model = getEffectiveModelId();
        return model != null && !model.isBlank() ? model : null;
    }

    private void appendSystemMessage(String message) {
        appendMessage("Система", message, false, List.of()); //$NON-NLS-1$
    }

    /**
     * Appends a message to the chat area.
     *
     * @param sender the sender name
     * @param message the message content (may contain Markdown)
     * @param isAssistant true if this is an AI assistant message
     */
    private void appendMessage(String sender, String message, boolean isAssistant) {
        appendMessage(sender, message, isAssistant, List.of());
    }

    private void appendMessage(String sender, String message, boolean isAssistant, List<LlmAttachment> attachments) {
        if (USE_BROWSER_RENDERING) {
            appendMessageBrowser(sender, message, isAssistant, attachments);
        } else {
            appendMessageStyledText(sender, decorateMessageWithAttachments(message, attachments), isAssistant);
        }
    }

    /**
     * Appends a message using Browser-based rendering.
     */
    private void appendMessageBrowser(String sender, String message, boolean isAssistant, List<LlmAttachment> attachments) {
        LOG.debug("appendMessageBrowser: sender=%s, isAssistant=%b, messageLength=%d", //$NON-NLS-1$
                sender, isAssistant, message != null ? message.length() : 0);
        if (browserChatPanel == null || browserChatPanel.isDisposed()) {
            LOG.warn("appendMessageBrowser: browserChatPanel is null or disposed"); //$NON-NLS-1$
            return;
        }

        boolean isSystem = "Система".equals(sender) || "System".equals(sender); //$NON-NLS-1$ //$NON-NLS-2$
        String modelName = isAssistant ? getCurrentModelName() : null;
        browserChatPanel.addMessage(sender, message, isAssistant, isSystem, attachments, modelName);
        LOG.debug("appendMessageBrowser: message added to browserChatPanel"); //$NON-NLS-1$
    }

    /**
     * Appends a message using StyledText-based rendering.
     */
    private void appendMessageStyledText(String sender, String message, boolean isAssistant) {
        if (messagesContainer == null || messagesContainer.isDisposed()) {
            return;
        }

        // Create message composite
        ChatMessageComposite messageWidget = new ChatMessageComposite(
                messagesContainer, sender, message, isAssistant);
        messageWidget.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        messageWidgets.add(messageWidget);

        // Always keep typing indicator at the bottom
        if (typingIndicator != null && !typingIndicator.isDisposed() && typingIndicator.isShowing()) {
            typingIndicator.moveBelow(null);
        }

        // Relayout and scroll to bottom
        messagesContainer.layout(true, true);
        updateScrollSize();
        scrollToBottom();
    }

    private LlmMessage buildUserMessage(String text, List<LlmAttachment> attachments) {
        List<LlmContentPart> parts = new ArrayList<>();
        if (text != null && !text.isBlank()) {
            parts.add(LlmContentPart.text(text));
        }
        if (attachments != null) {
            for (LlmAttachment attachment : attachments) {
                parts.add(attachment.isImage()
                        ? LlmContentPart.image(attachment)
                        : LlmContentPart.file(attachment));
            }
        }
        return LlmMessage.user(parts);
    }

    private String decorateMessageWithAttachments(String message, List<LlmAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return message;
        }
        StringBuilder sb = new StringBuilder(message != null ? message : ""); //$NON-NLS-1$
        for (LlmAttachment attachment : attachments) {
            if (attachment == null || !attachment.isImage()) {
                continue;
            }
            if (sb.length() > 0 && !sb.toString().endsWith("\n\n")) { //$NON-NLS-1$
                sb.append("\n\n"); //$NON-NLS-1$
            }
            sb.append("- ").append(buildAttachmentLabel(attachment)).append('\n'); //$NON-NLS-1$
        }
        return sb.toString().trim();
    }

    /**
     * Sends a message programmatically.
     *
     * @param message the message to send
     */
    public void sendProgrammaticMessage(String message) {
        inputField.setText(message);
        sendMessage();
    }

    /**
     * Sends a message from external code (e.g., command handlers).
     *
     * @param prompt the message to send
     */
    public void sendMessage(String prompt) {
        sendProgrammaticMessage(prompt);
    }

    /**
     * Returns whether preview mode is enabled for file changes.
     *
     * @return true if preview mode is enabled
     */
    public boolean isPreviewModeEnabled() {
        return previewModeEnabled;
    }

    /**
     * Sets whether preview mode is enabled for file changes.
     * When enabled, edit_file operations show a diff review dialog.
     *
     * @param enabled true to enable preview mode
     */
    public void setPreviewModeEnabled(boolean enabled) {
        this.previewModeEnabled = enabled;
    }

    /**
     * Returns the current proposed change set, if any.
     *
     * @return the current proposed changes, or null
     */
    public ProposedChangeSet getCurrentProposedChanges() {
        return currentProposedChanges;
    }

    private void maybeAutoCompactHistory() {
        if (!isAutoCompactEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAutoCompactAtMs < AUTO_COMPACT_COOLDOWN_MS) {
            return;
        }
        if (!isHistoryLarge()) {
            return;
        }
        if (compactConversationHistory(true)) {
            lastAutoCompactAtMs = now;
        }
    }

    private boolean compactConversationHistory(boolean automatic) {
        if (conversationHistory.size() < 2) {
            return false;
        }
        int tailBudget = getCompactionTailMessages();
        int tailMessages = automatic ? tailBudget
                : Math.min(tailBudget, Math.max(4, conversationHistory.size() / 2));
        if (conversationHistory.size() <= tailMessages + 1) {
            return false;
        }

        int keepFrom = Math.max(0, conversationHistory.size() - tailMessages);
        keepFrom = LlmConversationSanitizer.findSafeCompactionStart(conversationHistory, keepFrom);
        List<LlmMessage> head = new ArrayList<>(conversationHistory.subList(0, keepFrom));
        List<LlmMessage> tail = new ArrayList<>(conversationHistory.subList(keepFrom, conversationHistory.size()));

        // Try LLM-based compaction first if feature flag is enabled
        LlmCompactionService compactor = LlmCompactionService.getInstance();
        String summary = null;
        if (compactor.isEnabled()) {
            int targetTokens = Math.max(200, estimateTokensForMessages(head) / 4);
            summary = compactor.compact(head, targetTokens);
        }
        // Fall back to existing truncation-based summary
        if (summary == null || summary.isBlank()) {
            summary = buildHistorySummary(head);
        }
        if (summary.isBlank()) {
            return false;
        }

        int beforeTokens = estimateTokensForMessages(conversationHistory);
        List<LlmMessage> compacted = new ArrayList<>();
        compacted.add(LlmMessage.system(COMPACT_SUMMARY_MARKER + "\n" + summary)); //$NON-NLS-1$
        compacted.addAll(tail);
        conversationHistory.clear();
        conversationHistory.addAll(compacted);
        int afterTokens = estimateTokensForMessages(conversationHistory);

        String mode = automatic ? Messages.ChatView_AutoCompactLabel : Messages.ChatView_ManualCompactLabel;
        appendSystemMessage(Messages.ChatView_ContextCompactedNotice + " (" + mode + ")."); //$NON-NLS-1$ //$NON-NLS-2$
        LOG.info("Chat history compacted (%s): messages %d -> %d, tokens %d -> %d", //$NON-NLS-1$
                mode, head.size() + tail.size(), conversationHistory.size(), beforeTokens, afterTokens);
        return true;
    }

    private String buildHistorySummary(List<LlmMessage> messages) {
        if (messages.isEmpty()) {
            return ""; //$NON-NLS-1$
        }
        String summary = messages.stream()
                .filter(msg -> msg != null && msg.getContent() != null && !msg.getContent().isBlank())
                .filter(msg -> !(msg.getRole() == LlmMessage.Role.SYSTEM
                        && msg.getContent().startsWith(COMPACT_SUMMARY_MARKER)))
                .map(msg -> summarizeMessageLine(msg.getRole(), msg.getContent()))
                .limit(120)
                .collect(Collectors.joining("\n")); //$NON-NLS-1$

        if (summary.length() > 6000) {
            return summary.substring(0, 6000) + "\n..."; //$NON-NLS-1$
        }
        return summary;
    }

    private String summarizeMessageLine(LlmMessage.Role role, String content) {
        String roleText = switch (role) {
            case USER -> "Пользователь"; //$NON-NLS-1$
            case ASSISTANT -> "Ассистент"; //$NON-NLS-1$
            case TOOL -> "Инструмент"; //$NON-NLS-1$
            case SYSTEM -> "Система"; //$NON-NLS-1$
        };
        String normalized = content.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() > 220) {
            normalized = normalized.substring(0, 220) + "..."; //$NON-NLS-1$
        }
        return roleText + ": " + normalized; //$NON-NLS-1$
    }

    private boolean isHistoryLarge() {
        if (conversationHistory.size() < AUTO_COMPACT_MIN_MESSAGES) {
            return false;
        }
        int thresholdPercent = getAutoCompactThresholdPercent();
        int estimatedTokens = estimateTokensForMessages(conversationHistory);
        int thresholdTokens = (AUTO_COMPACT_HISTORY_TOKEN_BUDGET * thresholdPercent) / 100;
        return estimatedTokens >= thresholdTokens;
    }

    private int estimateTokensForMessages(List<LlmMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int chars = 0;
        for (LlmMessage message : messages) {
            if (message == null) {
                continue;
            }
            String content = message.getContent();
            if (content != null) {
                chars += content.length();
            }
        }
        return Math.max(0, chars / CHARS_PER_TOKEN_ESTIMATE);
    }

    private LlmResponse.Usage estimateUsageForResponse(LlmRequest request, String content, String reasoning) {
        int input = request != null ? estimateTokensForMessages(request.getMessages()) : 0;
        int output = ((content == null ? 0 : content.length()) + (reasoning == null ? 0 : reasoning.length()))
                / CHARS_PER_TOKEN_ESTIMATE;
        return new LlmResponse.Usage(input, 0, output, Math.max(0, input + output));
    }

    private void registerUsage(LlmResponse response) {
        if (response == null) {
            return;
        }
        LlmResponse.Usage usage = response.getUsage();
        if (usage == null) {
            return;
        }
        inputTokensTotal += Math.max(0, usage.getPromptTokens());
        cachedInputTokensTotal += Math.max(0, usage.getCachedPromptTokens());
        outputTokensTotal += Math.max(0, usage.getCompletionTokens());
        totalTokensTotal += Math.max(0, usage.getTotalTokens());
        scheduleTokenUsageDisplayUpdate();
    }

    private void resetTokenUsage() {
        inputTokensTotal = 0;
        cachedInputTokensTotal = 0;
        outputTokensTotal = 0;
        totalTokensTotal = 0;
        // Plan 2.4: new chat resets the request counter too.
        requestCount = 0;
        scheduleTokenUsageDisplayUpdate();
    }

    private void scheduleTokenUsageDisplayUpdate() {
        if (isDisposed()) {
            return;
        }
        Display display = getDisplay();
        if (display == null || display.isDisposed()) {
            return;
        }
        if (Display.getCurrent() == display) {
            updateTokenUsageDisplay();
            return;
        }
        display.asyncExec(() -> {
            if (!isDisposed()) {
                updateTokenUsageDisplay();
            }
        });
    }

    private void updateTokenUsageDisplay() {
        // Token counter is hidden — will be replaced by budget indicator in Phase 2.
        // Still accumulate values internally for backend usage tracking.
        if (tokenUsageLabel != null && !tokenUsageLabel.isDisposed()) {
            tokenUsageLabel.setVisible(false);
            ((GridData) tokenUsageLabel.getLayoutData()).exclude = true;
        }
        // Plan 2.4: push the compact footer into the browser panel.
        // Safe to call before the panel is ready — updateTokenFooter is a no-op
        // until the browser finishes bootstrapping.
        if (browserChatPanel != null && !browserChatPanel.isDisposed()) {
            browserChatPanel.updateTokenFooter(
                    inputTokensTotal,
                    cachedInputTokensTotal,
                    outputTokensTotal,
                    totalTokensTotal,
                    requestCount);
        }
    }

    private void updateModelButtonVisibility() {
        if (modelButton == null || modelButton.isDisposed()) {
            return;
        }
        boolean isCodePilot = currentProviderCapabilities().isCodePilotBackend();
        modelButton.setVisible(isCodePilot);
        ((GridData) modelButton.getLayoutData()).exclude = !isCodePilot;
        modelButton.getParent().layout(true, true);
    }

    private void updateModelButtonLabel() {
        if (modelButton == null || modelButton.isDisposed()) {
            return;
        }
        String displayId = getEffectiveModelId();
        modelButton.setText(compactModelButtonLabel(displayId));
        modelButton.setToolTipText(displayId);
        modelButton.getParent().layout(true, true);
    }

    private String compactModelButtonLabel(String modelId) {
        if (modelId == null || modelId.length() <= CHAT_MODEL_LABEL_MAX_CHARS) {
            return modelId;
        }
        int prefixLength = Math.max(8, CHAT_MODEL_LABEL_MAX_CHARS / 2 - 1);
        int suffixLength = Math.max(8, CHAT_MODEL_LABEL_MAX_CHARS - prefixLength - 3);
        return modelId.substring(0, prefixLength) + "..." //$NON-NLS-1$
                + modelId.substring(modelId.length() - suffixLength);
    }

    /**
     * Returns the model ID to use for requests (CodePilot backend only) and for display.
     * Without an explicit override, resolves the active provider's configured model.
     */
    private String getEffectiveModelId() {
        if (overrideModelId != null && !overrideModelId.isBlank()) {
            return overrideModelId;
        }
        String activeModel = resolveActiveProviderModel();
        return activeModel != null ? activeModel : ""; //$NON-NLS-1$
    }

    private String resolveActiveProviderModel() {
        try {
            return LlmProviderConfigStore.getInstance().getActiveProvider()
                    .map(LlmProviderConfig::getModel)
                    .filter(model -> model != null && !model.isBlank())
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private void openModelSelectionDialog() {
        ILlmProvider provider = LlmProviderRegistry.getInstance().getActiveProvider();
        if (provider == null || !provider.getCapabilities().isCodePilotBackend()) {
            return;
        }
        modelButton.setEnabled(false);
        modelButton.setText(Messages.ChatView_ModelFetching);
        modelButton.getParent().layout(true, true);

        String apiKey = BackendService.getInstance().getApiKey();
        ModelFetchService.getInstance().fetchModels(
                BackendConfig.LITELLM_BASE_URL, apiKey, ProviderType.CODEPILOT_BACKEND)
                .thenAccept(result -> Display.getDefault().asyncExec(() -> {
                    if (modelButton == null || modelButton.isDisposed()) {
                        return;
                    }
                    modelButton.setEnabled(true);
                    updateModelButtonLabel();
                    if (!result.isSuccess()) {
                        MessageDialog.openError(getSite().getShell(),
                                Messages.ChatView_ModelButtonTooltip,
                                MessageFormat.format(
                                        Messages.ChatView_ModelFetchError, result.getError()));
                        return;
                    }
                    List<ModelInfo> models = result.getModels();
                    if (models.isEmpty()) {
                        MessageDialog.openInformation(getSite().getShell(),
                                Messages.ChatView_ModelButtonTooltip,
                                Messages.ChatView_ModelNoModels);
                        return;
                    }
                    ModelSelectionDialog dialog = new ModelSelectionDialog(getSite().getShell(), models);
                    if (dialog.open() == org.eclipse.jface.dialogs.IDialogConstants.OK_ID) {
                        ModelInfo selected = dialog.getSelectedModel();
                        if (selected != null) {
                            String previousModelId = overrideModelId;
                            overrideModelId = selected.getId();
                            // Persist the per-view model choice with this window's session.
                            Session modelSession = viewSession();
                            modelSession.setModelId(overrideModelId);
                            SessionManager.getInstance().saveSession(modelSession);
                            updateModelButtonLabel();

                            // Ask user whether to start a new chat when model changes mid-conversation
                            if (previousModelId != null && !previousModelId.equals(overrideModelId)
                                    && !conversationHistory.isEmpty()) {
                                String msg = MessageFormat.format(
                                        Messages.ChatView_ModelSwitchMessage, selected.getId());
                                MessageDialog switchDialog = new MessageDialog(
                                        getSite().getShell(),
                                        Messages.ChatView_ModelSwitchTitle,
                                        null, msg, MessageDialog.QUESTION,
                                        new String[] {
                                            Messages.ChatView_ModelSwitchNewChat,
                                            Messages.ChatView_ModelSwitchContinue
                                        }, 0);
                                if (switchDialog.open() == 0) {
                                    clearChat();
                                }
                            }
                        }
                    }
                }));
    }

    private boolean isTokenUsageVisible() {
        IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(CORE_PLUGIN_ID);
        return prefs.getBoolean(VibePreferenceConstants.PREF_CHAT_SHOW_TOKEN_USAGE, true);
    }

    private boolean isAutoCompactEnabled() {
        IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(CORE_PLUGIN_ID);
        return prefs.getBoolean(VibePreferenceConstants.PREF_CHAT_AUTO_COMPACT_ENABLED, true);
    }

    private int getAutoCompactThresholdPercent() {
        IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(CORE_PLUGIN_ID);
        int value = prefs.getInt(VibePreferenceConstants.PREF_CHAT_AUTO_COMPACT_THRESHOLD_PERCENT, 85);
        if (value < 50) {
            return 50;
        }
        if (value > 95) {
            return 95;
        }
        return value;
    }

    /**
     * Returns the number of tail messages to preserve verbatim during history
     * compaction. Reads {@link VibePreferenceConstants#LLM_COMPACTION_TAIL_MESSAGES}
     * with a fallback to {@link #COMPACT_TAIL_MESSAGES} (14). Values are clamped to
     * a sensible range [4, 64] to avoid degenerate compactions (Plan 1.3).
     */
    private int getCompactionTailMessages() {
        int fallback = COMPACT_TAIL_MESSAGES;
        int value;
        try {
            IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(CORE_PLUGIN_ID);
            value = prefs.getInt(VibePreferenceConstants.LLM_COMPACTION_TAIL_MESSAGES, fallback);
        } catch (Exception e) {
            value = fallback;
        }
        if (value < 4) {
            return 4;
        }
        if (value > 64) {
            return 64;
        }
        return value;
    }

    @Override
    public void setFocus() {
        // Multi-view: the focused chat becomes the global current session, so remote-trigger and
        // Code.md resolution target this window.
        if (session != null) {
            SessionManager.getInstance().setCurrentSession(session);
        }
        inputField.setFocus();
    }

    // === Mouse Wheel Scrolling Support (fixes SWT bug #93472) ===

    /**
     * Mouse wheel listener for scrolling.
     * Stored as field to allow adding to dynamically created children.
     */
    private org.eclipse.swt.events.MouseWheelListener mouseWheelScroller;

    /**
     * Installs mouse wheel scrolling support on a ScrolledComposite.
     * This fixes the known SWT bug where ScrolledComposite content
     * doesn't scroll with mouse wheel on Windows.
     *
     * @param scrollable the ScrolledComposite to enable scrolling on
     * @param content the content composite
     */
    private void installMouseWheelScrolling(ScrolledComposite scrollable, Composite content) {
        // Create the wheel listener
        mouseWheelScroller = e -> {
            if (scrollable.isDisposed()) {
                return;
            }
            Point origin = scrollable.getOrigin();
            // Scroll 5 lines worth of pixels per notch (more natural feel)
            int scrollAmount = e.count * 25;
            int newY = Math.max(0, origin.y - scrollAmount);
            scrollable.setOrigin(origin.x, newY);
        };

        // Install on scrollable itself
        scrollable.addMouseWheelListener(mouseWheelScroller);

        // Install recursively on all existing children
        installMouseWheelRecursively(content);

        // Listen for new children being added to messagesContainer
        content.addListener(SWT.Resize, e -> {
            // Re-install on new children after layout changes
            for (Control child : content.getChildren()) {
                if (child.getData("wheelListenerInstalled") == null) { //$NON-NLS-1$
                    installMouseWheelRecursively(child);
                }
            }
        });
    }

    /**
     * Recursively installs mouse wheel listener on a control and its children.
     *
     * @param control the control to install listener on
     */
    private void installMouseWheelRecursively(Control control) {
        if (control == null || control.isDisposed() || mouseWheelScroller == null) {
            return;
        }

        // Skip if already installed
        if (control.getData("wheelListenerInstalled") != null) { //$NON-NLS-1$
            return;
        }

        // Add listener
        control.addMouseWheelListener(mouseWheelScroller);
        control.setData("wheelListenerInstalled", Boolean.TRUE); //$NON-NLS-1$

        // Recurse into children
        if (control instanceof Composite) {
            for (Control child : ((Composite) control).getChildren()) {
                installMouseWheelRecursively(child);
            }
        }
    }

    @Override
    public void dispose() {
        // Phase 0: persist the conversation so it survives EDT close/restart.
        persistSession();
        if (sessionChangeListenerRegistered) {
            SessionManager.getInstance().removeListener(sessionChangeListener);
            sessionChangeListenerRegistered = false;
        }
        if (currentRequest != null && !currentRequest.isDone()) {
            currentRequest.cancel(true);
            if (currentRequestUsesDesktopController) {
                AgentSessionController.getInstance().stopFromDesktop();
            }
        }
        currentRequestUsesDesktopController = false;
        conversationHistory.clear();

        // Dispose GSD status panel
        if (gsdStatusPanel != null && !gsdStatusPanel.isDisposed()) {
            gsdStatusPanel.dispose();
        }

        // Dispose typing indicator
        if (typingIndicator != null && !typingIndicator.isDisposed()) {
            typingIndicator.dispose();
        }

        // Dispose message widgets
        for (ChatMessageComposite widget : messageWidgets) {
            if (!widget.isDisposed()) {
                widget.dispose();
            }
        }
        messageWidgets.clear();

        super.dispose();
    }
}
