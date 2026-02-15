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
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ProgressBar;

/**
 * Composite widget for displaying agent run progress.
 *
 * <p>Shows current operation status, tool calls in progress,
 * and provides cancel functionality.</p>
 */
public class AgentRunComposite extends Composite {

    /**
     * State of the agent run.
     */
    public enum AgentState {
        /** Agent is idle, no run in progress */
        IDLE,
        /** Agent is thinking/processing */
        THINKING,
        /** Agent is executing a tool */
        TOOL_EXECUTING,
        /** Waiting for user confirmation */
        AWAITING_CONFIRMATION,
        /** Run completed successfully */
        COMPLETED,
        /** Run was cancelled */
        CANCELLED,
        /** Run failed with error */
        ERROR
    }

    /**
     * Listener for agent run events.
     */
    public interface AgentRunListener {
        /** Called when cancel button is pressed */
        void onCancelRequested();
    }

    private AgentState currentState = AgentState.IDLE;
    private String currentOperation;
    private int toolCallsTotal;
    private int toolCallsCompleted;
    private final List<AgentRunListener> listeners = new CopyOnWriteArrayList<>();

    private Composite headerComposite;
    private Label stateLabel;
    private Label operationLabel;
    private ProgressBar progressBar;
    private Label progressLabel;
    private Button cancelButton;
    private Composite toolsComposite;
    private final List<ToolStatusWidget> toolWidgets = new ArrayList<>();

    private Font boldFont;
    private Color activeColor;
    private Color completedColor;
    private Color errorColor;
    private Color bgColor;

    /**
     * Creates a new agent run composite.
     *
     * @param parent the parent composite
     * @param style the SWT style
     */
    public AgentRunComposite(Composite parent, int style) {
        super(parent, style);
        createResources();
        createContents();
        updateState(AgentState.IDLE);
    }

    private void createResources() {
        Display display = getDisplay();

        // Bold font for headers
        FontData[] fontData = getFont().getFontData();
        for (FontData fd : fontData) {
            fd.setStyle(SWT.BOLD);
        }
        boldFont = new Font(display, fontData);

        // Colors
        activeColor = new Color(display, 59, 130, 246);  // Blue
        completedColor = new Color(display, 34, 197, 94); // Green
        errorColor = new Color(display, 239, 68, 68);    // Red
        bgColor = new Color(display, 249, 250, 251);     // Light gray
    }

    private void createContents() {
        setBackground(bgColor);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 12;
        layout.marginHeight = 8;
        layout.verticalSpacing = 8;
        setLayout(layout);

        // Header with state and cancel button
        createHeader();

        // Progress section
        createProgressSection();

        // Tool calls section
        createToolsSection();
    }

    private void createHeader() {
        headerComposite = new Composite(this, SWT.NONE);
        headerComposite.setBackground(bgColor);
        headerComposite.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout headerLayout = new GridLayout(3, false);
        headerLayout.marginWidth = 0;
        headerLayout.marginHeight = 0;
        headerComposite.setLayout(headerLayout);

        // State indicator
        stateLabel = new Label(headerComposite, SWT.NONE);
        stateLabel.setFont(boldFont);
        stateLabel.setBackground(bgColor);
        stateLabel.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        // Current operation
        operationLabel = new Label(headerComposite, SWT.NONE);
        operationLabel.setBackground(bgColor);
        operationLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Cancel button
        cancelButton = new Button(headerComposite, SWT.PUSH);
        cancelButton.setText("Отменить"); //$NON-NLS-1$
        cancelButton.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        cancelButton.addListener(SWT.Selection, e -> {
            for (AgentRunListener listener : listeners) {
                listener.onCancelRequested();
            }
        });
    }

    private void createProgressSection() {
        Composite progressComposite = new Composite(this, SWT.NONE);
        progressComposite.setBackground(bgColor);
        progressComposite.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout progressLayout = new GridLayout(2, false);
        progressLayout.marginWidth = 0;
        progressLayout.marginHeight = 0;
        progressComposite.setLayout(progressLayout);

        // Progress bar
        progressBar = new ProgressBar(progressComposite, SWT.SMOOTH);
        progressBar.setMinimum(0);
        progressBar.setMaximum(100);
        GridData progressData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        progressData.heightHint = 8;
        progressBar.setLayoutData(progressData);

        // Progress label
        progressLabel = new Label(progressComposite, SWT.NONE);
        progressLabel.setBackground(bgColor);
        progressLabel.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
    }

    private void createToolsSection() {
        toolsComposite = new Composite(this, SWT.NONE);
        toolsComposite.setBackground(bgColor);
        toolsComposite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        GridLayout toolsLayout = new GridLayout(1, false);
        toolsLayout.marginWidth = 0;
        toolsLayout.marginHeight = 0;
        toolsLayout.verticalSpacing = 4;
        toolsComposite.setLayout(toolsLayout);
    }

    /**
     * Updates the agent state.
     *
     * @param state the new state
     */
    public void updateState(AgentState state) {
        if (isDisposed()) return;

        this.currentState = state;

        Display.getDefault().asyncExec(() -> {
            if (isDisposed()) return;

            String stateText;
            Color stateColor;
            boolean showProgress;
            boolean enableCancel;

            switch (state) {
                case IDLE:
                    stateText = "● Готов"; //$NON-NLS-1$
                    stateColor = completedColor;
                    showProgress = false;
                    enableCancel = false;
                    break;
                case THINKING:
                    stateText = "◉ Обрабатываю..."; //$NON-NLS-1$
                    stateColor = activeColor;
                    showProgress = true;
                    enableCancel = true;
                    break;
                case TOOL_EXECUTING:
                    stateText = "⚙ Выполняю инструмент"; //$NON-NLS-1$
                    stateColor = activeColor;
                    showProgress = true;
                    enableCancel = true;
                    break;
                case AWAITING_CONFIRMATION:
                    stateText = "⏸ Ожидание подтверждения"; //$NON-NLS-1$
                    stateColor = activeColor;
                    showProgress = false;
                    enableCancel = true;
                    break;
                case COMPLETED:
                    stateText = "✓ Завершено"; //$NON-NLS-1$
                    stateColor = completedColor;
                    showProgress = false;
                    enableCancel = false;
                    break;
                case CANCELLED:
                    stateText = "✕ Отменено"; //$NON-NLS-1$
                    stateColor = errorColor;
                    showProgress = false;
                    enableCancel = false;
                    break;
                case ERROR:
                    stateText = "⚠ Ошибка"; //$NON-NLS-1$
                    stateColor = errorColor;
                    showProgress = false;
                    enableCancel = false;
                    break;
                default:
                    stateText = "●"; //$NON-NLS-1$
                    stateColor = activeColor;
                    showProgress = false;
                    enableCancel = false;
            }

            stateLabel.setText(stateText);
            stateLabel.setForeground(stateColor);
            progressBar.setVisible(showProgress);
            cancelButton.setEnabled(enableCancel);

            layout(true, true);
        });
    }

    /**
     * Sets the current operation description.
     *
     * @param operation the operation description
     */
    public void setCurrentOperation(String operation) {
        this.currentOperation = operation;

        Display.getDefault().asyncExec(() -> {
            if (isDisposed() || operationLabel.isDisposed()) return;
            operationLabel.setText(operation != null ? operation : ""); //$NON-NLS-1$
            headerComposite.layout(true);
        });
    }

    /**
     * Updates the progress indicator.
     *
     * @param completed completed steps
     * @param total total steps
     */
    public void updateProgress(int completed, int total) {
        this.toolCallsCompleted = completed;
        this.toolCallsTotal = total;

        Display.getDefault().asyncExec(() -> {
            if (isDisposed()) return;

            if (total > 0) {
                int percentage = (completed * 100) / total;
                progressBar.setSelection(percentage);
                progressLabel.setText(String.format("%d/%d", completed, total)); //$NON-NLS-1$
            } else {
                progressBar.setSelection(0);
                progressLabel.setText(""); //$NON-NLS-1$
            }
        });
    }

    /**
     * Adds a tool execution status widget.
     *
     * @param toolName the tool name
     * @param toolCallId the tool call ID
     * @return the created widget
     */
    public ToolStatusWidget addToolStatus(String toolName, String toolCallId) {
        final ToolStatusWidget[] widget = new ToolStatusWidget[1];

        Display.getDefault().syncExec(() -> {
            if (isDisposed() || toolsComposite.isDisposed()) return;

            widget[0] = new ToolStatusWidget(toolsComposite, toolName, toolCallId);
            widget[0].setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            toolWidgets.add(widget[0]);

            toolsComposite.layout(true, true);
            layout(true, true);
        });

        return widget[0];
    }

    /**
     * Clears all tool status widgets.
     */
    public void clearToolStatuses() {
        Display.getDefault().asyncExec(() -> {
            if (isDisposed() || toolsComposite.isDisposed()) return;

            for (ToolStatusWidget widget : toolWidgets) {
                if (!widget.isDisposed()) {
                    widget.dispose();
                }
            }
            toolWidgets.clear();

            toolsComposite.layout(true, true);
        });
    }

    /**
     * Resets the composite to idle state.
     */
    public void reset() {
        clearToolStatuses();
        setCurrentOperation(null);
        updateProgress(0, 0);
        updateState(AgentState.IDLE);
    }

    /**
     * Adds an agent run listener.
     *
     * @param listener the listener
     */
    public void addListener(AgentRunListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * Removes an agent run listener.
     *
     * @param listener the listener
     */
    public void removeListener(AgentRunListener listener) {
        listeners.remove(listener);
    }

    /**
     * Returns the current agent state.
     *
     * @return the current state
     */
    public AgentState getCurrentState() {
        return currentState;
    }

    @Override
    public void dispose() {
        if (boldFont != null && !boldFont.isDisposed()) {
            boldFont.dispose();
        }
        if (activeColor != null && !activeColor.isDisposed()) {
            activeColor.dispose();
        }
        if (completedColor != null && !completedColor.isDisposed()) {
            completedColor.dispose();
        }
        if (errorColor != null && !errorColor.isDisposed()) {
            errorColor.dispose();
        }
        if (bgColor != null && !bgColor.isDisposed()) {
            bgColor.dispose();
        }
        listeners.clear();
        super.dispose();
    }

    /**
     * Widget for displaying individual tool execution status.
     */
    public static class ToolStatusWidget extends Composite {

        /**
         * Tool execution status.
         */
        public enum ToolStatus {
            PENDING, EXECUTING, COMPLETED, FAILED, SKIPPED
        }

        private final String toolName;
        private final String toolCallId;
        private ToolStatus status = ToolStatus.PENDING;

        private Label statusIcon;
        private Label nameLabel;
        private Label detailLabel;

        /**
         * Creates a new tool status widget.
         *
         * @param parent the parent composite
         * @param toolName the tool name
         * @param toolCallId the tool call ID
         */
        public ToolStatusWidget(Composite parent, String toolName, String toolCallId) {
            super(parent, SWT.NONE);
            this.toolName = toolName;
            this.toolCallId = toolCallId;
            createContents();
        }

        private void createContents() {
            GridLayout layout = new GridLayout(3, false);
            layout.marginWidth = 4;
            layout.marginHeight = 2;
            layout.horizontalSpacing = 8;
            setLayout(layout);

            // Status icon
            statusIcon = new Label(this, SWT.NONE);
            statusIcon.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
            updateStatusIcon();

            // Tool name
            nameLabel = new Label(this, SWT.NONE);
            nameLabel.setText(getDisplayName(toolName));
            nameLabel.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

            // Detail label
            detailLabel = new Label(this, SWT.NONE);
            detailLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        }

        private String getDisplayName(String name) {
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

        private void updateStatusIcon() {
            String icon = switch (status) {
                case PENDING -> "○"; //$NON-NLS-1$
                case EXECUTING -> "◉"; //$NON-NLS-1$
                case COMPLETED -> "✓"; //$NON-NLS-1$
                case FAILED -> "✕"; //$NON-NLS-1$
                case SKIPPED -> "○"; //$NON-NLS-1$
            };
            statusIcon.setText(icon);
        }

        /**
         * Updates the tool status.
         *
         * @param newStatus the new status
         */
        public void updateStatus(ToolStatus newStatus) {
            this.status = newStatus;
            Display.getDefault().asyncExec(() -> {
                if (!isDisposed()) {
                    updateStatusIcon();
                    layout(true);
                }
            });
        }

        /**
         * Updates the tool status with detail text.
         *
         * @param newStatus the new status
         * @param detail the detail text
         */
        public void updateStatus(ToolStatus newStatus, String detail) {
            this.status = newStatus;
            Display.getDefault().asyncExec(() -> {
                if (!isDisposed()) {
                    updateStatusIcon();
                    detailLabel.setText(detail != null ? detail : ""); //$NON-NLS-1$
                    layout(true);
                }
            });
        }

        /**
         * Returns the tool call ID.
         *
         * @return the tool call ID
         */
        public String getToolCallId() {
            return toolCallId;
        }
    }
}
