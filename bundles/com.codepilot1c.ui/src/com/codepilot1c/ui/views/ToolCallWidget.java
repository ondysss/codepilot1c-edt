/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.views;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import com.codepilot1c.ui.chat.MessagePart.ToolCallPart;
import com.codepilot1c.ui.chat.MessagePart.ToolCallPart.ToolCallStatus;
import com.codepilot1c.ui.chat.MessagePart.ToolResultPart;
import com.codepilot1c.ui.theme.ThemeManager;
import com.codepilot1c.ui.theme.VibeTheme;

/**
 * Widget for displaying a tool call with its status and result.
 *
 * <p>Supports collapsible content and status indicators.</p>
 */
public class ToolCallWidget extends Composite {

    private final String toolName;
    private final String toolCallId;
    private ToolCallStatus status;
    private String resultContent;
    private boolean resultSuccess;

    private Label statusLabel;
    private Composite contentArea;
    private StyledText resultText;
    private boolean expanded = false;

    private final VibeTheme theme;

    /**
     * Creates a new tool call widget from a ToolCallPart.
     *
     * @param parent the parent composite
     * @param toolCall the tool call part
     */
    public ToolCallWidget(Composite parent, ToolCallPart toolCall) {
        super(parent, SWT.NONE);
        this.toolName = toolCall.toolName();
        this.toolCallId = toolCall.toolCallId();
        this.status = toolCall.status();
        this.theme = ThemeManager.getInstance().getTheme();

        createContents();
    }

    /**
     * Creates a new tool call widget.
     *
     * @param parent the parent composite
     * @param toolName the tool name
     * @param toolCallId the tool call ID
     * @param status the initial status
     */
    public ToolCallWidget(Composite parent, String toolName, String toolCallId, ToolCallStatus status) {
        super(parent, SWT.NONE);
        this.toolName = toolName;
        this.toolCallId = toolCallId;
        this.status = status;
        this.theme = ThemeManager.getInstance().getTheme();

        createContents();
    }

    private void createContents() {
        // Layout
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.verticalSpacing = 0;
        setLayout(layout);
        setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Header with tool name and status
        createHeader();

        // Collapsible content area
        createContentArea();
    }

    private void createHeader() {
        Composite header = new Composite(this, SWT.NONE);
        header.setBackground(theme.getToolCallBackground());
        GridLayout headerLayout = new GridLayout(3, false);
        headerLayout.marginWidth = theme.getMargin();
        headerLayout.marginHeight = theme.getMarginSmall();
        header.setLayout(headerLayout);
        header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Expand/collapse button
        Button toggleButton = new Button(header, SWT.PUSH | SWT.FLAT);
        toggleButton.setText(expanded ? "\u25BC" : "\u25B6"); // ▼ or ▶ //$NON-NLS-1$ //$NON-NLS-2$
        toggleButton.setFont(theme.getFont());
        toggleButton.addListener(SWT.Selection, e -> {
            expanded = !expanded;
            toggleButton.setText(expanded ? "\u25BC" : "\u25B6"); //$NON-NLS-1$ //$NON-NLS-2$
            updateContentVisibility();
        });

        // Tool name with icon
        Label nameLabel = new Label(header, SWT.NONE);
        nameLabel.setBackground(header.getBackground());
        nameLabel.setForeground(theme.getText());
        nameLabel.setFont(theme.getFontBold());
        nameLabel.setText("\uD83D\uDD27 " + getToolDisplayName(toolName)); // 🔧 //$NON-NLS-1$
        nameLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Status indicator
        statusLabel = new Label(header, SWT.NONE);
        statusLabel.setBackground(header.getBackground());
        statusLabel.setFont(theme.getFontSmall());
        updateStatusLabel();
    }

    private void createContentArea() {
        contentArea = new Composite(this, SWT.NONE);
        contentArea.setBackground(theme.getToolResultBackground());
        GridLayout contentLayout = new GridLayout(1, false);
        contentLayout.marginWidth = theme.getMarginLarge();
        contentLayout.marginHeight = theme.getMargin();
        contentArea.setLayout(contentLayout);

        GridData contentData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        contentData.exclude = !expanded;
        contentArea.setLayoutData(contentData);
        contentArea.setVisible(expanded);

        // Result text (will be populated when result is set)
        resultText = new StyledText(contentArea, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP);
        resultText.setBackground(contentArea.getBackground());
        resultText.setForeground(theme.getText());
        resultText.setEditable(false);
        resultText.setText("Ожидание результата..."); //$NON-NLS-1$

        // Monospace font for results
        resultText.setFont(theme.getFontMono());

        GridData textData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        textData.widthHint = 400;
        resultText.setLayoutData(textData);
        resultText.setMargins(theme.getMarginSmall(), theme.getMarginSmall(), theme.getMarginSmall(), theme.getMarginSmall());
    }

    private void updateContentVisibility() {
        GridData contentData = (GridData) contentArea.getLayoutData();
        contentData.exclude = !expanded;
        contentArea.setVisible(expanded);

        // Relayout all the way up to ScrolledComposite
        relayoutParentChain();
    }

    /**
     * Propagates layout changes up the parent chain to the ScrolledComposite.
     */
    private void relayoutParentChain() {
        Composite parent = getParent();
        while (parent != null && !parent.isDisposed()) {
            parent.layout(true, true);
            if (parent instanceof org.eclipse.swt.custom.ScrolledComposite) {
                // Found ScrolledComposite - update its min size
                org.eclipse.swt.custom.ScrolledComposite sc =
                        (org.eclipse.swt.custom.ScrolledComposite) parent;
                org.eclipse.swt.widgets.Control content = sc.getContent();
                if (content != null && !content.isDisposed()) {
                    int width = sc.getClientArea().width;
                    if (width > 0) {
                        org.eclipse.swt.graphics.Point size = content.computeSize(width, SWT.DEFAULT);
                        content.setSize(size);
                        sc.setMinSize(size);
                    }
                }
                break;
            }
            parent = parent.getParent();
        }
    }

    private void updateStatusLabel() {
        String statusText;
        org.eclipse.swt.graphics.Color color;

        switch (status) {
            case PENDING:
                statusText = "\u23F3 Ожидание"; // ⏳ //$NON-NLS-1$
                color = theme.getTextMuted();
                break;
            case RUNNING:
                statusText = "\u25B6 Выполняется"; // ▶ //$NON-NLS-1$
                color = theme.getAccent();
                break;
            case SUCCESS:
                statusText = "\u2713 Готово"; // ✓ //$NON-NLS-1$
                color = theme.getSuccess();
                break;
            case FAILED:
                statusText = "\u2717 Ошибка"; // ✗ //$NON-NLS-1$
                color = theme.getDanger();
                break;
            case CANCELLED:
                statusText = "\u2716 Отменено"; // ✖ //$NON-NLS-1$
                color = theme.getTextMuted();
                break;
            case NEEDS_CONFIRMATION:
                statusText = "\u26A0 Требует подтверждения"; // ⚠ //$NON-NLS-1$
                color = theme.getWarning();
                break;
            default:
                statusText = ""; //$NON-NLS-1$
                color = theme.getTextMuted();
        }

        statusLabel.setText(statusText);
        statusLabel.setForeground(color);
    }

    private String getToolDisplayName(String name) {
        // Convert tool names to human-readable format
        return switch (name) {
            case "read_file" -> "Чтение файла"; //$NON-NLS-1$ //$NON-NLS-2$
            case "edit_file" -> "Редактирование файла"; //$NON-NLS-1$ //$NON-NLS-2$
            case "list_files" -> "Список файлов"; //$NON-NLS-1$ //$NON-NLS-2$
            case "grep" -> "Поиск текста"; //$NON-NLS-1$ //$NON-NLS-2$
            case "search_codebase" -> "Поиск по коду"; //$NON-NLS-1$ //$NON-NLS-2$
            case "edt_content_assist" -> "EDT автодополнение"; //$NON-NLS-1$ //$NON-NLS-2$
            case "edt_find_references" -> "EDT поиск ссылок"; //$NON-NLS-1$ //$NON-NLS-2$
            case "edt_metadata_details" -> "EDT детали метаданных"; //$NON-NLS-1$ //$NON-NLS-2$
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

    // === Public API ===

    /**
     * Updates the status of this tool call.
     *
     * @param newStatus the new status
     */
    public void setStatus(ToolCallStatus newStatus) {
        this.status = newStatus;
        if (!statusLabel.isDisposed()) {
            getDisplay().asyncExec(() -> {
                if (!statusLabel.isDisposed()) {
                    updateStatusLabel();
                }
            });
        }
    }

    /**
     * Sets the result of the tool call.
     *
     * @param result the tool result part
     */
    public void setResult(ToolResultPart result) {
        this.resultContent = result.content();
        this.resultSuccess = result.success();
        this.status = resultSuccess ? ToolCallStatus.SUCCESS : ToolCallStatus.FAILED;

        if (!resultText.isDisposed()) {
            getDisplay().asyncExec(() -> {
                if (!resultText.isDisposed()) {
                    // Truncate long results
                    String displayContent = resultContent;
                    if (displayContent != null && displayContent.length() > 2000) {
                        displayContent = displayContent.substring(0, 2000) + "\n... (обрезано)"; //$NON-NLS-1$
                    }
                    resultText.setText(displayContent != null ? displayContent : ""); //$NON-NLS-1$
                    updateStatusLabel();

                    // Auto-expand on completion if there's content
                    if (resultContent != null && !resultContent.isEmpty() && !expanded) {
                        // Keep collapsed by default - user can expand if interested
                    }
                }
            });
        }
    }

    /**
     * Sets the result content directly.
     *
     * @param content the result content
     * @param success whether the tool succeeded
     */
    public void setResult(String content, boolean success) {
        this.resultContent = content;
        this.resultSuccess = success;
        this.status = success ? ToolCallStatus.SUCCESS : ToolCallStatus.FAILED;

        if (!resultText.isDisposed()) {
            getDisplay().asyncExec(() -> {
                if (!resultText.isDisposed()) {
                    String displayContent = content;
                    if (displayContent != null && displayContent.length() > 2000) {
                        displayContent = displayContent.substring(0, 2000) + "\n... (обрезано)"; //$NON-NLS-1$
                    }
                    resultText.setText(displayContent != null ? displayContent : ""); //$NON-NLS-1$
                    updateStatusLabel();
                }
            });
        }
    }

    /**
     * Returns the tool call ID.
     *
     * @return the tool call ID
     */
    public String getToolCallId() {
        return toolCallId;
    }

    /**
     * Returns the tool name.
     *
     * @return the tool name
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * Returns the current status.
     *
     * @return the status
     */
    public ToolCallStatus getStatus() {
        return status;
    }

    @Override
    public void dispose() {
        // All colors and fonts are managed by ThemeManager - no local disposal needed
        super.dispose();
    }
}
