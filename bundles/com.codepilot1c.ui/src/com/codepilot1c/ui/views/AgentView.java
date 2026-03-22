/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.views;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;

import com.codepilot1c.core.agent.AgentResult;
import com.codepilot1c.core.agent.AgentState;
import com.codepilot1c.core.agent.profiles.AgentProfile;
import com.codepilot1c.core.agent.profiles.AgentProfileRegistry;
import com.codepilot1c.core.provider.ILlmProvider;
import com.codepilot1c.core.provider.LlmProviderRegistry;
import com.codepilot1c.core.remote.AgentSessionController;
import com.codepilot1c.ui.chat.AgentProgressWidget;
import com.codepilot1c.ui.chat.AgentViewAdapter;

/**
 * View для работы с agentic assistant.
 *
 * <p>В отличие от ChatView, AgentView использует полноценный agentic loop
 * через AgentRunner с поддержкой:</p>
 * <ul>
 *   <li>Автоматического выполнения инструментов</li>
 *   <li>Разных профилей (build, plan, explore)</li>
 *   <li>Отображения прогресса выполнения</li>
 *   <li>Ограничения количества шагов</li>
 * </ul>
 */
public class AgentView extends ViewPart {

    public static final String ID = "com.codepilot1c.ui.views.AgentView"; //$NON-NLS-1$

    private ScrolledComposite scrolledComposite;
    private Composite messagesContainer;
    private Text inputField;
    private Button sendButton;
    private Button stopButton;
    private Button clearButton;
    private Combo profileCombo;
    private AgentProgressWidget progressWidget;

    private final List<ChatMessageComposite> messageWidgets = new ArrayList<>();
    private AgentViewAdapter agentAdapter;
    private CompletableFuture<AgentResult> currentTask;

    @Override
    public void createPartControl(Composite parent) {
        Composite container = new Composite(parent, SWT.NONE);
        container.setLayout(new GridLayout(1, false));

        createProgressArea(container);
        createChatArea(container);
        createInputArea(container);

        // Initialize adapter
        agentAdapter = new AgentViewAdapter(parent.getDisplay());
        setupAdapterCallbacks();

        appendSystemMessage("🤖 Агентный режим готов. Выберите профиль и введите задачу.");
    }

    private void createProgressArea(Composite parent) {
        progressWidget = new AgentProgressWidget(parent);
        progressWidget.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    private void createChatArea(Composite parent) {
        scrolledComposite = new ScrolledComposite(parent, SWT.BORDER | SWT.V_SCROLL);
        scrolledComposite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        scrolledComposite.setExpandHorizontal(true);
        scrolledComposite.setExpandVertical(true);

        messagesContainer = new Composite(scrolledComposite, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.verticalSpacing = 8;
        messagesContainer.setLayout(layout);

        scrolledComposite.setContent(messagesContainer);
        messagesContainer.addListener(SWT.Resize, e -> updateScrollSize());
    }

    private void createInputArea(Composite parent) {
        Composite inputArea = new Composite(parent, SWT.NONE);
        inputArea.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout inputLayout = new GridLayout(1, false);
        inputLayout.marginWidth = 8;
        inputLayout.marginHeight = 8;
        inputLayout.verticalSpacing = 8;
        inputArea.setLayout(inputLayout);

        // Profile selector and input row
        Composite topRow = new Composite(inputArea, SWT.NONE);
        topRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout topLayout = new GridLayout(3, false);
        topLayout.marginWidth = 0;
        topLayout.marginHeight = 0;
        topRow.setLayout(topLayout);

        // Profile label
        Label profileLabel = new Label(topRow, SWT.NONE);
        profileLabel.setText("Профиль:"); //$NON-NLS-1$
        profileLabel.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        // Profile combo
        profileCombo = new Combo(topRow, SWT.READ_ONLY | SWT.DROP_DOWN);
        profileCombo.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        populateProfileCombo();

        // Spacer
        Label spacer = new Label(topRow, SWT.NONE);
        spacer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Input field
        inputField = new Text(inputArea, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        GridData inputData = new GridData(SWT.FILL, SWT.FILL, true, false);
        inputData.heightHint = 80;
        inputField.setLayoutData(inputData);
        inputField.setMessage("Введите задачу для агента (Ctrl+Enter для отправки)..."); //$NON-NLS-1$

        inputField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.keyCode == SWT.CR && (e.stateMask & SWT.CTRL) != 0) {
                    runAgent();
                }
            }
        });

        // Button bar
        Composite buttonBar = new Composite(inputArea, SWT.NONE);
        buttonBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout buttonLayout = new GridLayout(4, false);
        buttonLayout.marginWidth = 0;
        buttonLayout.marginHeight = 0;
        buttonLayout.horizontalSpacing = 8;
        buttonBar.setLayout(buttonLayout);

        sendButton = new Button(buttonBar, SWT.PUSH);
        sendButton.setText("Выполнить"); //$NON-NLS-1$
        sendButton.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        sendButton.addListener(SWT.Selection, e -> runAgent());

        // Spacer
        Label btnSpacer = new Label(buttonBar, SWT.NONE);
        btnSpacer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        stopButton = new Button(buttonBar, SWT.PUSH);
        stopButton.setText("Остановить"); //$NON-NLS-1$
        stopButton.setEnabled(false);
        stopButton.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        stopButton.addListener(SWT.Selection, e -> stopAgent());

        clearButton = new Button(buttonBar, SWT.PUSH);
        clearButton.setText("Очистить"); //$NON-NLS-1$
        clearButton.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        clearButton.addListener(SWT.Selection, e -> clearChat());
    }

    private void populateProfileCombo() {
        for (AgentProfile profile : AgentProfileRegistry.getInstance().getAllProfiles()) {
            String displayName = String.format("%s - %s",
                    profile.getName(),
                    profile.isReadOnly() ? "только чтение" : "полный доступ");
            profileCombo.add(displayName);
            profileCombo.setData(displayName, profile.getId());
        }
        profileCombo.select(0);

        // Add tooltip
        profileCombo.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                updateProfileTooltip();
            }
        });
        updateProfileTooltip();
    }

    private void updateProfileTooltip() {
        int idx = profileCombo.getSelectionIndex();
        if (idx >= 0) {
            String displayName = profileCombo.getItem(idx);
            String profileId = (String) profileCombo.getData(displayName);
            AgentProfileRegistry.getInstance().getProfile(profileId).ifPresent(profile -> {
                String tooltip = String.format(
                        "%s\n\nМакс. шагов: %d\nТаймаут: %d сек\nИнструменты: %s",
                        profile.getDescription(),
                        profile.getMaxSteps(),
                        profile.getTimeoutMs() / 1000,
                        String.join(", ", profile.getAllowedTools()));
                profileCombo.setToolTipText(tooltip);
            });
        }
    }

    private void setupAdapterCallbacks() {
        agentAdapter.setMessageAppender(new AgentViewAdapter.MessageAppender() {
            @Override
            public void appendSystemMessage(String message) {
                AgentView.this.appendSystemMessage(message);
            }

            @Override
            public void appendAssistantMessage(String message) {
                AgentView.this.appendAssistantMessage(message);
            }

            @Override
            public void appendToolMessage(String message) {
                AgentView.this.appendMessage("Инструмент", message, false); //$NON-NLS-1$
            }

            @Override
            public void updateStreamingMessage(String messageId, String content) {
                // For streaming updates, we could update the last assistant message
                // For now, just log it
            }
        });

        agentAdapter.setProgressUpdater((current, max, status) -> {
            if (progressWidget != null && !progressWidget.isDisposed()) {
                progressWidget.updateProgress(current, max, status);
            }
        });

        agentAdapter.setStateChangeListener((newState, errorMessage) -> {
            if (progressWidget != null && !progressWidget.isDisposed()) {
                progressWidget.updateState(newState, errorMessage);
            }

            boolean isRunning = newState == AgentState.RUNNING ||
                    newState == AgentState.WAITING_TOOL ||
                    newState == AgentState.WAITING_CONFIRMATION;
            setProcessing(isRunning);
        });
    }

    private void runAgent() {
        String prompt = inputField.getText().trim();
        if (prompt.isEmpty()) {
            return;
        }

        // Check provider
        ILlmProvider provider = LlmProviderRegistry.getInstance().getActiveProvider();
        if (provider == null || !provider.isConfigured()) {
            appendSystemMessage("❌ LLM провайдер не настроен. Откройте настройки.");
            return;
        }

        // Get selected profile
        int idx = profileCombo.getSelectionIndex();
        String profileId = "build"; //$NON-NLS-1$
        if (idx >= 0) {
            String displayName = profileCombo.getItem(idx);
            profileId = (String) profileCombo.getData(displayName);
        }

        // Show user message
        appendUserMessage(prompt);
        inputField.setText(""); //$NON-NLS-1$
        setProcessing(true);

        // Run agent
        final String finalProfileId = profileId;
        currentTask = agentAdapter.run(prompt, finalProfileId)
                .whenComplete((result, error) -> {
                    Display.getDefault().asyncExec(() -> {
                        if (!isDisposed()) {
                            setProcessing(false);
                            if (error != null) {
                                appendSystemMessage("❌ Ошибка: " + error.getMessage());
                            }
                        }
                    });
                });
    }

    private void stopAgent() {
        if (agentAdapter != null) {
            agentAdapter.cancel();
        }
        if (currentTask != null && !currentTask.isDone()) {
            currentTask.cancel(true);
        }
        setProcessing(false);
    }

    private void clearChat() {
        AgentSessionController.getInstance().resetSession("desktop_clear"); //$NON-NLS-1$

        // Dispose all message widgets
        for (ChatMessageComposite widget : messageWidgets) {
            if (!widget.isDisposed()) {
                widget.dispose();
            }
        }
        messageWidgets.clear();

        // Clear children
        for (Control child : messagesContainer.getChildren()) {
            if (!child.isDisposed()) {
                child.dispose();
            }
        }

        messagesContainer.layout(true, true);
        updateScrollSize();

        if (progressWidget != null && !progressWidget.isDisposed()) {
            progressWidget.reset();
        }

        appendSystemMessage("🤖 Чат очищен. Готов к новым задачам.");
    }

    private void setProcessing(boolean processing) {
        if (!isDisposed()) {
            sendButton.setEnabled(!processing);
            stopButton.setEnabled(processing);
            inputField.setEnabled(!processing);
            profileCombo.setEnabled(!processing);
        }
    }

    private void appendUserMessage(String message) {
        appendMessage("Вы", message, false); //$NON-NLS-1$
    }

    private void appendAssistantMessage(String message) {
        appendMessage("Агент", message, true); //$NON-NLS-1$
    }

    private void appendSystemMessage(String message) {
        appendMessage("Система", message, false); //$NON-NLS-1$
    }

    private void appendMessage(String sender, String message, boolean isAssistant) {
        if (messagesContainer == null || messagesContainer.isDisposed()) {
            return;
        }

        ChatMessageComposite messageWidget = new ChatMessageComposite(
                messagesContainer, sender, message, isAssistant);
        messageWidget.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        messageWidgets.add(messageWidget);

        messagesContainer.layout(true, true);
        updateScrollSize();
        scrollToBottom();
    }

    private void updateScrollSize() {
        if (messagesContainer != null && !messagesContainer.isDisposed()) {
            messagesContainer.setSize(messagesContainer.computeSize(
                    scrolledComposite.getClientArea().width, SWT.DEFAULT));
        }
    }

    private void scrollToBottom() {
        if (scrolledComposite != null && !scrolledComposite.isDisposed()) {
            scrolledComposite.getDisplay().asyncExec(() -> {
                if (!scrolledComposite.isDisposed() && !messagesContainer.isDisposed()) {
                    scrolledComposite.setOrigin(0, messagesContainer.getSize().y);
                }
            });
        }
    }

    private boolean isDisposed() {
        return scrolledComposite == null || scrolledComposite.isDisposed();
    }

    /**
     * Программный запуск агента с указанным промптом.
     *
     * @param prompt задача для агента
     * @param profileId ID профиля (build, plan, explore)
     * @return Future с результатом
     */
    public CompletableFuture<AgentResult> runProgrammatic(String prompt, String profileId) {
        inputField.setText(prompt);

        // Select profile in combo
        for (int i = 0; i < profileCombo.getItemCount(); i++) {
            String displayName = profileCombo.getItem(i);
            String id = (String) profileCombo.getData(displayName);
            if (profileId.equals(id)) {
                profileCombo.select(i);
                break;
            }
        }

        runAgent();
        return currentTask;
    }

    @Override
    public void setFocus() {
        inputField.setFocus();
    }

    @Override
    public void dispose() {
        stopAgent();
        if (agentAdapter != null) {
            agentAdapter.dispose();
            agentAdapter = null;
        }
        for (ChatMessageComposite widget : messageWidgets) {
            if (!widget.isDisposed()) {
                widget.dispose();
            }
        }
        messageWidgets.clear();
        super.dispose();
    }
}
