/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.views;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;

import com.codepilot1c.core.diff.CodeDiffUtils;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.model.LlmMessage;
import com.codepilot1c.core.model.LlmRequest;
import com.codepilot1c.core.model.LlmResponse;
import com.codepilot1c.core.model.LlmStreamChunk;
import com.codepilot1c.core.model.ToolCall;
import com.codepilot1c.core.model.ToolDefinition;
import com.codepilot1c.core.provider.ILlmProvider;
import com.codepilot1c.core.provider.LlmProviderRegistry;
import com.codepilot1c.core.rag.RagService;
import com.codepilot1c.core.settings.VibePreferenceConstants;
import com.codepilot1c.core.tools.ITool;
import com.codepilot1c.core.tools.ToolRegistry;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.ui.dialogs.ToolConfirmationDialog;
import com.codepilot1c.ui.diff.DiffReviewDialog;
import com.codepilot1c.ui.diff.ProposedChange;
import com.codepilot1c.ui.diff.ProposedChangeSet;
import com.codepilot1c.ui.editor.CodeApplicationService;
import com.codepilot1c.ui.internal.Messages;
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
    private static final int MAX_TOOL_RESULT_PREVIEW_CHARS = 20_000;
    private static final String CORE_PLUGIN_ID = "com.codepilot1c.core"; //$NON-NLS-1$

    private ScrolledComposite scrolledComposite;
    private Composite messagesContainer;
    private BrowserChatPanel browserChatPanel;
    private Text inputField;
    private Button sendButton;
    private Button clearButton;
    private Button stopButton;
    private Button applyCodeButton;
    private TypingIndicatorWidget typingIndicator;

    private final List<LlmMessage> conversationHistory = new ArrayList<>();
    private final List<ChatMessageComposite> messageWidgets = new ArrayList<>();
    private CompletableFuture<?> currentRequest;
    private boolean isProcessing = false;
    private String lastAssistantResponse;

    /** Accumulated content during streaming (thread-safe) */
    private StringBuffer streamingContent;
    /** Accumulated reasoning during streaming (thread-safe) */
    private StringBuffer streamingReasoning;
    /** Whether streaming is in progress */
    private volatile boolean isStreaming = false;
    /** Whether tool calls were handled during current streaming session */
    private volatile boolean streamingHandledToolCalls = false;

    /** Whether to show diff preview before applying file changes */
    private boolean previewModeEnabled = false;
    /** Current set of proposed changes awaiting review */
    private ProposedChangeSet currentProposedChanges;

    @Override
    public void createPartControl(Composite parent) {
        VibeTheme theme = ThemeManager.getInstance().getTheme();

        Composite container = new Composite(parent, SWT.NONE);
        container.setLayout(new GridLayout(1, false));
        container.setBackground(theme.getBackground());

        createChatArea(container);
        createInputArea(container);

        appendSystemMessage(Messages.ChatView_WelcomeMessage);
    }

    private void createChatArea(Composite parent) {
        if (USE_BROWSER_RENDERING) {
            createBrowserChatArea(parent);
        } else {
            createStyledTextChatArea(parent);
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
        inputAreaLayout.marginWidth = theme.getMargin();
        inputAreaLayout.marginHeight = theme.getMargin();
        inputAreaLayout.verticalSpacing = theme.getMargin();
        inputArea.setLayout(inputAreaLayout);

        // Input field - full width
        inputField = new Text(inputArea, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        inputField.setBackground(theme.getInputBackground());
        inputField.setForeground(theme.getText());
        inputField.setFont(theme.getFont());
        GridData inputData = new GridData(SWT.FILL, SWT.FILL, true, false);
        inputData.heightHint = 80;
        inputField.setLayoutData(inputData);
        inputField.setMessage(Messages.ChatView_InputPlaceholder);

        inputField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
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

        // Button bar - compact horizontal layout
        Composite buttonBar = new Composite(inputArea, SWT.NONE);
        buttonBar.setBackground(inputArea.getBackground());
        buttonBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout buttonLayout = new GridLayout(5, false);
        buttonLayout.marginWidth = 0;
        buttonLayout.marginHeight = 0;
        buttonLayout.horizontalSpacing = 4; // Compact spacing
        buttonBar.setLayout(buttonLayout);

        // Send button with icon
        sendButton = new Button(buttonBar, SWT.PUSH);
        sendButton.setText("\u27A4"); // ➤ send icon //$NON-NLS-1$
        sendButton.setToolTipText(Messages.ChatView_SendButton + " (Enter)"); //$NON-NLS-1$
        sendButton.setFont(theme.getFont());
        GridData sendData = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        sendData.widthHint = 36;
        sendData.heightHint = 28;
        sendButton.setLayoutData(sendData);
        sendButton.addListener(SWT.Selection, e -> sendMessage());

        // Apply code button with icon
        applyCodeButton = new Button(buttonBar, SWT.PUSH);
        applyCodeButton.setText("\u2913"); // ⤓ apply icon //$NON-NLS-1$
        applyCodeButton.setToolTipText(Messages.ChatView_ApplyCodeTooltip);
        applyCodeButton.setFont(theme.getFont());
        applyCodeButton.setEnabled(false);
        GridData applyData = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        applyData.widthHint = 36;
        applyData.heightHint = 28;
        applyCodeButton.setLayoutData(applyData);
        applyCodeButton.addListener(SWT.Selection, e -> applyCodeToEditor());

        // Spacer to push stop and clear to the right
        Label spacer = new Label(buttonBar, SWT.NONE);
        spacer.setBackground(buttonBar.getBackground());
        spacer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Stop button with icon
        stopButton = new Button(buttonBar, SWT.PUSH);
        stopButton.setText("\u25A0"); // ■ stop icon //$NON-NLS-1$
        stopButton.setToolTipText(Messages.ChatView_StopButton);
        stopButton.setFont(theme.getFont());
        stopButton.setEnabled(false);
        GridData stopData = new GridData(SWT.RIGHT, SWT.CENTER, false, false);
        stopData.widthHint = 36;
        stopData.heightHint = 28;
        stopButton.setLayoutData(stopData);
        stopButton.addListener(SWT.Selection, e -> stopGeneration());

        // Clear button with icon
        clearButton = new Button(buttonBar, SWT.PUSH);
        clearButton.setText("\uD83D\uDDD1"); // 🗑 trash icon //$NON-NLS-1$
        clearButton.setToolTipText(Messages.ChatView_ClearButton);
        clearButton.setFont(theme.getFont());
        GridData clearData = new GridData(SWT.RIGHT, SWT.CENTER, false, false);
        clearData.widthHint = 36;
        clearData.heightHint = 28;
        clearButton.setLayoutData(clearData);
        clearButton.addListener(SWT.Selection, e -> clearChat());
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
        LOG.debug("sendMessage called, isProcessing=%b, inputLength=%d", isProcessing, userInput.length()); //$NON-NLS-1$

        if (userInput.isEmpty() || isProcessing) {
            LOG.debug("sendMessage blocked: isEmpty=%b, isProcessing=%b", userInput.isEmpty(), isProcessing); //$NON-NLS-1$
            return;
        }

        ILlmProvider provider = LlmProviderRegistry.getInstance().getActiveProvider();
        if (provider == null || !provider.isConfigured()) {
            LOG.warn("Provider not configured"); //$NON-NLS-1$
            appendSystemMessage(Messages.ChatView_NotConfiguredMessage);
            return;
        }

        // Add user message to UI
        appendUserMessage(userInput);
        inputField.setText(""); //$NON-NLS-1$

        // No automatic context preparation: send the user message as-is.
        setProcessing(true, "Отправка запроса..."); //$NON-NLS-1$
        conversationHistory.add(LlmMessage.user(userInput));
        startConversationLoop(provider);
    }

    /**
     * Starts the conversation loop with tool support.
     * This handles the tool call -> execute -> response cycle.
     */
    private void startConversationLoop(ILlmProvider provider) {
        LOG.debug("startConversationLoop: beginning"); //$NON-NLS-1$

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

        streamingContent = new StringBuffer();
        streamingReasoning = new StringBuffer();
        isStreaming = true;
        streamingHandledToolCalls = false; // Reset for new streaming session

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
            LOG.debug("Stream received tool calls"); //$NON-NLS-1$
            streamingHandledToolCalls = true; // Mark that tool calls were handled
            final List<ToolCall> toolCalls = chunk.getToolCalls();
            final String accumulatedContent = streamingContent.toString();
            final String accumulatedReasoning = streamingReasoning.toString();

            if (!display.isDisposed()) {
                display.asyncExec(() -> {
                    if (!isDisposed()) {
                        isStreaming = false;
                        setProcessingStage("Обработка инструментов..."); //$NON-NLS-1$

                        // Create response object for tool handling
                        LlmResponse toolResponse = LlmResponse.builder()
                                .content(accumulatedContent)
                                .toolCalls(toolCalls)
                                .finishReason(LlmResponse.FINISH_REASON_TOOL_USE)
                                .build();

                        // Update the displayed message with current content and reasoning
                        if (USE_BROWSER_RENDERING && browserChatPanel != null) {
                            if (!accumulatedReasoning.isEmpty() || !accumulatedContent.isEmpty()) {
                                browserChatPanel.updateLastMessageWithReasoning(accumulatedContent, accumulatedReasoning);
                            }
                        }

                        // Handle tool calls (this will continue the conversation loop)
                        ILlmProvider provider = LlmProviderRegistry.getInstance().getActiveProvider();
                        if (provider != null) {
                            handleResponseWithTools(toolResponse, provider, 0)
                                    .thenAccept(finalContent -> {
                                        if (!display.isDisposed()) {
                                            display.asyncExec(() -> {
                                                if (!isDisposed()) {
                                                    setProcessing(false);
                                                }
                                            });
                                        }
                                    })
                                    .exceptionally(error -> {
                                        if (!display.isDisposed()) {
                                            display.asyncExec(() -> {
                                                if (!isDisposed()) {
                                                    handleError(error);
                                                }
                                            });
                                        }
                                        return null;
                                    });
                        }
                    }
                });
            }
            return;
        }

        // Handle completion (without tool calls)
        // Skip if tool calls were already handled - they will manage completion themselves
        if (chunk.isComplete() && !streamingHandledToolCalls) {
            final String finalContent = streamingContent.toString();
            LOG.debug("Stream complete, content length: %d", finalContent.length()); //$NON-NLS-1$

            if (!display.isDisposed()) {
                display.asyncExec(() -> {
                    if (!isDisposed()) {
                        isStreaming = false;

                        // Add to conversation history
                        if (!finalContent.isEmpty()) {
                            conversationHistory.add(LlmMessage.assistant(finalContent));
                            lastAssistantResponse = finalContent;

                            // Check for code blocks
                            boolean hasCode = !CodeDiffUtils.extractCodeBlocks(finalContent).isEmpty();
                            applyCodeButton.setEnabled(hasCode);
                        }

                        setProcessing(false);
                    }
                });
            }
        } else if (chunk.isComplete() && streamingHandledToolCalls) {
            LOG.debug("Stream complete ignored - tool calls are being processed"); //$NON-NLS-1$
        }
    }

    /**
     * Starts a non-streaming request to the LLM provider.
     */
    private void startNonStreamingRequest(ILlmProvider provider, LlmRequest request, Display display) {
        LOG.debug("startNonStreamingRequest: using non-streaming mode"); //$NON-NLS-1$

        // Send request
        currentRequest = provider.complete(request)
                .thenCompose(response -> {
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
            List<ToolDefinition> tools = ToolRegistry.getInstance().getToolDefinitions();
            requestBuilder.tools(tools);
            requestBuilder.toolChoice(LlmRequest.ToolChoice.AUTO);
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
            LOG.warn("handleResponseWithTools: max iterations (%d) reached, stopping tool loop", maxToolIterations); //$NON-NLS-1$
            // Show warning to user
            if (!display.isDisposed()) {
                display.asyncExec(() -> {
                    if (!isDisposed()) {
                        appendSystemMessage(String.format(
                            "⚠️ Достигнут лимит итераций (%d). Агент остановлен для предотвращения бесконечного цикла.", //$NON-NLS-1$
                            maxToolIterations));
                    }
                });
            }
        }

        LOG.debug("handleResponseWithTools: final response (no tool calls or max iterations)"); //$NON-NLS-1$
        // No tool calls - this is the final response
        String content = response.getContent();
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
                        conversationHistory.add(LlmMessage.assistant(content));

                        // Store response and check for code blocks
                        lastAssistantResponse = content;
                        boolean hasCode = !CodeDiffUtils.extractCodeBlocks(content).isEmpty();
                        applyCodeButton.setEnabled(hasCode);
                        LOG.debug("handleResponseWithTools: message appended successfully"); //$NON-NLS-1$
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
        conversationHistory.add(LlmMessage.assistantWithToolCalls(assistantContent, toolCalls));

        // Separate edit_file calls for preview from other tool calls
        List<ToolCall> editCalls = new ArrayList<>();
        List<ToolCall> otherCalls = new ArrayList<>();

        for (ToolCall call : toolCalls) {
            if (shouldInterceptForPreview(call)) {
                editCalls.add(call);
            } else {
                otherCalls.add(call);
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
                    if (USE_BROWSER_RENDERING && browserChatPanel != null && browserChatPanel.isBrowserAvailable()) {
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
                            String suffix = shouldInterceptForPreview(call)
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
                    ProposedChange change = createProposedChangeFromToolCall(call);
                    proposedChanges.addChange(change);
                } catch (Exception e) {
                    // If we can't create proposed change, fall back to normal execution
                    otherCalls.add(call);
                }
            }
            currentProposedChanges = proposedChanges;
        }

        // Execute non-edit tool calls with confirmation for destructive operations
        ToolRegistry registry = ToolRegistry.getInstance();
        List<CompletableFuture<ToolResult>> futures = new ArrayList<>();
        List<ToolCall> executedCalls = new ArrayList<>();

        for (ToolCall call : otherCalls) {
            ITool tool = registry.getTool(call.getName());
            executedCalls.add(call);

            boolean skipConfirmations = shouldSkipToolConfirmations();
            if (!skipConfirmations && tool != null && (tool.requiresConfirmation() || tool.isDestructive())) {
                // Need confirmation on UI thread
                CompletableFuture<ToolResult> confirmedFuture = new CompletableFuture<>();

                // Check display before asyncExec to prevent hanging futures
                if (display.isDisposed()) {
                    LOG.warn("Display disposed, skipping tool confirmation for %s", call.getName()); //$NON-NLS-1$
                    confirmedFuture.complete(ToolResult.failure("Display disposed")); //$NON-NLS-1$
                } else {
                    display.asyncExec(() -> {
                        if (isDisposed()) {
                            confirmedFuture.complete(ToolResult.failure("View disposed")); //$NON-NLS-1$
                            return;
                        }

                        ToolConfirmationDialog dialog = new ToolConfirmationDialog(
                                getShell(),
                                call,
                                tool.getDescription(),
                                tool.isDestructive()
                        );

                        if (dialog.openAndConfirm()) {
                            // User confirmed - execute the tool
                            registry.execute(call)
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
                    });
                }

                futures.add(confirmedFuture);
            } else {
                // No confirmation needed - execute directly
                futures.add(registry.execute(call));
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
            String resultContent = result.isSuccess()
                    ? result.getContent()
                    : "Error: " + result.getErrorMessage(); //$NON-NLS-1$
            conversationHistory.add(LlmMessage.toolResult(call.getId(), resultContent));
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

            try {
                provider.streamComplete(request, chunk -> {
                    if (out.isDone()) {
                        return;
                    }

                    if (chunk.isError()) {
                        out.completeExceptionally(new RuntimeException(chunk.getErrorMessage()));
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
                                    .build());
                        } else {
                            out.complete(LlmResponse.builder()
                                    .content(content.toString())
                                    .reasoningContent(reasoning.toString())
                                    .finishReason(finishReason[0])
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
                                .build());
                    } else {
                        out.complete(LlmResponse.builder()
                                .content(content.toString())
                                .reasoningContent(reasoning.toString())
                                .finishReason(finishReason[0])
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
        if (USE_BROWSER_RENDERING && browserChatPanel != null && browserChatPanel.isBrowserAvailable()) {
            // Determine status
            BrowserChatPanel.ToolCallStatus status = result.isSuccess()
                    ? BrowserChatPanel.ToolCallStatus.SUCCESS
                    : BrowserChatPanel.ToolCallStatus.ERROR;

            // Build result summary (e.g., "1,240 chars" or error message)
            String content = result.isSuccess() ? result.getContent() : result.getErrorMessage();
            String resultSummary;
            if (result.isSuccess() && content != null) {
                int len = content.length();
                if (len >= 1000) {
                    resultSummary = String.format("%,d символов", len); //$NON-NLS-1$
                } else {
                    resultSummary = String.format("%d символов", len); //$NON-NLS-1$
                }
            } else if (!result.isSuccess()) {
                resultSummary = "Ошибка"; //$NON-NLS-1$
            } else {
                resultSummary = "Выполнено"; //$NON-NLS-1$
            }

            // Build result preview (full content with safety cap for UI responsiveness)
            String resultPreview = ""; //$NON-NLS-1$
            if (content != null && !content.isEmpty()) {
                if (content.length() > MAX_TOOL_RESULT_PREVIEW_CHARS) {
                    resultPreview = content.substring(0, MAX_TOOL_RESULT_PREVIEW_CHARS)
                            + "\n... (обрезано в UI)"; //$NON-NLS-1$
                } else {
                    resultPreview = content;
                }
            }

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
            // Truncate very long results for display
            if (content.length() > 1500) {
                content = content.substring(0, 1500) + "\n... (обрезано)"; //$NON-NLS-1$
            }
            sb.append(content);
        }

        appendMessage("Инструмент", sb.toString(), false); //$NON-NLS-1$
    }

    /**
     * Checks if a tool call is for edit_file and should be intercepted for preview.
     */
    private boolean shouldInterceptForPreview(ToolCall call) {
        return previewModeEnabled && "edit_file".equals(call.getName()); //$NON-NLS-1$
    }

    /**
     * Parses tool call arguments from JSON string to Map.
     *
     * @param json the JSON arguments string
     * @return parsed arguments map
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseToolArguments(String json) {
        if (json == null || json.isEmpty() || "{}".equals(json)) { //$NON-NLS-1$
            return new HashMap<>();
        }
        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            java.lang.reflect.Type mapType = new com.google.gson.reflect.TypeToken<Map<String, Object>>(){}.getType();
            Map<String, Object> result = gson.fromJson(json, mapType);
            return result != null ? result : new HashMap<>();
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    /**
     * Creates a ProposedChange from an edit_file tool call.
     */
    private ProposedChange createProposedChangeFromToolCall(ToolCall call) {
        // Parse JSON arguments string to Map
        Map<String, Object> args = parseToolArguments(call.getArguments());

        String filePath = (String) args.get("path"); //$NON-NLS-1$
        if (filePath == null) {
            filePath = (String) args.get("file_path"); //$NON-NLS-1$
        }

        String newContent = (String) args.get("content"); //$NON-NLS-1$
        String oldString = (String) args.get("old_string"); //$NON-NLS-1$
        String newString = (String) args.get("new_string"); //$NON-NLS-1$

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
        return switch (name) {
            case "read_file" -> "Чтение файла"; //$NON-NLS-1$ //$NON-NLS-2$
            case "edit_file" -> "Редактирование файла"; //$NON-NLS-1$ //$NON-NLS-2$
            case "list_files" -> "Список файлов"; //$NON-NLS-1$ //$NON-NLS-2$
            case "grep" -> "Поиск текста"; //$NON-NLS-1$ //$NON-NLS-2$
            case "search_codebase" -> "Поиск по коду"; //$NON-NLS-1$ //$NON-NLS-2$
            case "edt_content_assist" -> "EDT автодополнение"; //$NON-NLS-1$ //$NON-NLS-2$
            case "edt_find_references" -> "EDT поиск ссылок"; //$NON-NLS-1$ //$NON-NLS-2$
            case "edt_metadata_details" -> "EDT детали метаданных"; //$NON-NLS-1$ //$NON-NLS-2$
            case "inspect_form_layout" -> "Структура формы"; //$NON-NLS-1$ //$NON-NLS-2$
            case "get_platform_documentation" -> "Справка платформы"; //$NON-NLS-1$ //$NON-NLS-2$
            case "bsl_symbol_at_position" -> "BSL символ по позиции"; //$NON-NLS-1$ //$NON-NLS-2$
            case "bsl_type_at_position" -> "BSL тип по позиции"; //$NON-NLS-1$ //$NON-NLS-2$
            case "bsl_scope_members" -> "BSL элементы области"; //$NON-NLS-1$ //$NON-NLS-2$
            case "edt_validate_request" -> "Валидация запроса EDT"; //$NON-NLS-1$ //$NON-NLS-2$
            case "create_metadata" -> "Создание метаданных EDT"; //$NON-NLS-1$ //$NON-NLS-2$
            case "add_metadata_child" -> "Создание вложенных метаданных EDT"; //$NON-NLS-1$ //$NON-NLS-2$
            case "edt_trace_export" -> "Трейс экспорта EDT"; //$NON-NLS-1$ //$NON-NLS-2$
            case "edt_metadata_smoke" -> "Smoke метаданных EDT"; //$NON-NLS-1$ //$NON-NLS-2$
            default -> name;
        };
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
            return getShell();
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
        if (toolsEnabled) {
            appendToolsSection(prompt);
        }

        // === RAG CONTEXT ===
        RagService ragService = RagService.getInstance();
        if (ragService.isReady()) {
            prompt.append("""
            # Контекст из кодовой базы

            В сообщениях пользователя может быть раздел "Релевантный код из кодовой базы" -
            это результаты семантического поиска. Используйте этот код как:
            - Примеры существующего стиля и паттернов
            - Информацию о структуре и именовании в проекте
            - Приоритетный контекст для формирования ответов

            """); //$NON-NLS-1$
        }

        // === FINAL INSTRUCTIONS ===
        prompt.append("""
            # Контекст редактора

            Если в сообщении есть информация о текущем файле или выделенном коде -
            это контекст из активного редактора. Учитывайте его при ответе.
            """); //$NON-NLS-1$

        return prompt.toString();
    }

    private void appendToolsSection(StringBuilder prompt) {
        List<ToolDefinition> tools = ToolRegistry.getInstance().getToolDefinitions();
        if (tools.isEmpty()) {
            prompt.append("""
            # Инструменты

            Инструменты недоступны в текущей конфигурации.

            """); //$NON-NLS-1$
            return;
        }

        prompt.append("""
        # Инструменты

        Используйте инструменты при работе с кодом, файлами и проектом.
        Если нужна информация из проекта, сначала вызывайте подходящий инструмент.

        Доступные инструменты:
        """); //$NON-NLS-1$

        for (ToolDefinition tool : tools) {
            prompt.append("- ").append(tool.getName()).append(": ") //$NON-NLS-1$ //$NON-NLS-2$
                    .append(tool.getDescription()).append("\n"); //$NON-NLS-1$
        }

        prompt.append("\n"); //$NON-NLS-1$
    }

    private void handleError(Throwable error) {
        LOG.error("handleError: %s", error.getMessage()); //$NON-NLS-1$
        if (error.getCause() != null) {
            LOG.error("handleError cause: %s", error.getCause().getMessage()); //$NON-NLS-1$
        }
        String message = error.getCause() != null ? error.getCause().getMessage() : error.getMessage();
        appendSystemMessage(java.text.MessageFormat.format(Messages.ChatView_ErrorMessage, message));
        LOG.debug("handleError: calling setProcessing(false)"); //$NON-NLS-1$
        setProcessing(false);
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
        }
        setProcessing(false);
    }

    private void clearChat() {
        if (USE_BROWSER_RENDERING) {
            clearChatBrowser();
        } else {
            clearChatStyledText();
        }

        conversationHistory.clear();
        lastAssistantResponse = null;
        if (!isDisposed()) {
            applyCodeButton.setEnabled(false);
        }

        appendSystemMessage(Messages.ChatView_WelcomeMessage);
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
            stopButton.setEnabled(processing);
            inputField.setEnabled(!processing);

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
        appendMessage("Вы", message, false); //$NON-NLS-1$
    }

    private void appendAssistantMessage(String message) {
        appendMessage("AI", message, true); //$NON-NLS-1$
    }

    private void appendSystemMessage(String message) {
        appendMessage("Система", message, false); //$NON-NLS-1$
    }

    /**
     * Appends a message to the chat area.
     *
     * @param sender the sender name
     * @param message the message content (may contain Markdown)
     * @param isAssistant true if this is an AI assistant message
     */
    private void appendMessage(String sender, String message, boolean isAssistant) {
        if (USE_BROWSER_RENDERING) {
            appendMessageBrowser(sender, message, isAssistant);
        } else {
            appendMessageStyledText(sender, message, isAssistant);
        }
    }

    /**
     * Appends a message using Browser-based rendering.
     */
    private void appendMessageBrowser(String sender, String message, boolean isAssistant) {
        LOG.debug("appendMessageBrowser: sender=%s, isAssistant=%b, messageLength=%d", //$NON-NLS-1$
                sender, isAssistant, message != null ? message.length() : 0);
        if (browserChatPanel == null || browserChatPanel.isDisposed()) {
            LOG.warn("appendMessageBrowser: browserChatPanel is null or disposed"); //$NON-NLS-1$
            return;
        }

        boolean isSystem = "Система".equals(sender) || "System".equals(sender); //$NON-NLS-1$ //$NON-NLS-2$
        browserChatPanel.addMessage(sender, message, isAssistant, isSystem);
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

    @Override
    public void setFocus() {
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
        if (currentRequest != null && !currentRequest.isDone()) {
            currentRequest.cancel(true);
        }
        conversationHistory.clear();

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
