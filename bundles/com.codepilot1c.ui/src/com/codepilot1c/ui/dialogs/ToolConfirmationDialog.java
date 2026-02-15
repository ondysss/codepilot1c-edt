/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.dialogs;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import com.codepilot1c.core.model.ToolCall;

/**
 * Dialog for confirming destructive tool operations.
 *
 * <p>Shows tool details and asks for user confirmation before
 * executing operations that modify files or system state.</p>
 */
public class ToolConfirmationDialog extends Dialog {

    private final ToolCall toolCall;
    private final String toolDescription;
    private final boolean isDestructive;

    private Color warningColor;
    private Font codeFont;

    /**
     * Creates a new tool confirmation dialog.
     *
     * @param parentShell the parent shell
     * @param toolCall the tool call to confirm
     * @param toolDescription description of what the tool does
     * @param isDestructive whether the tool modifies files/state
     */
    public ToolConfirmationDialog(Shell parentShell, ToolCall toolCall,
                                  String toolDescription, boolean isDestructive) {
        super(parentShell);
        this.toolCall = toolCall;
        this.toolDescription = toolDescription;
        this.isDestructive = isDestructive;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText(isDestructive
                ? "Подтверждение операции" //$NON-NLS-1$
                : "Подтверждение инструмента"); //$NON-NLS-1$
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite container = (Composite) super.createDialogArea(parent);

        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 15;
        layout.marginHeight = 15;
        layout.verticalSpacing = 10;
        container.setLayout(layout);

        Display display = parent.getDisplay();
        warningColor = new Color(display, 180, 80, 0);
        codeFont = new Font(display, "Menlo", 11, SWT.NORMAL); //$NON-NLS-1$

        // Warning header for destructive operations
        if (isDestructive) {
            createWarningHeader(container);
        }

        // Tool info
        createToolInfo(container);

        // Arguments preview
        createArgumentsPreview(container);

        return container;
    }

    private void createWarningHeader(Composite parent) {
        Composite warningArea = new Composite(parent, SWT.NONE);
        warningArea.setLayout(new GridLayout(2, false));
        warningArea.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label iconLabel = new Label(warningArea, SWT.NONE);
        iconLabel.setText("\u26A0"); // ⚠ //$NON-NLS-1$
        iconLabel.setForeground(warningColor);

        Label warningLabel = new Label(warningArea, SWT.WRAP);
        warningLabel.setText("Эта операция изменит файлы в проекте. Проверьте параметры перед продолжением."); //$NON-NLS-1$
        warningLabel.setForeground(warningColor);
        GridData warningData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        warningData.widthHint = 350;
        warningLabel.setLayoutData(warningData);
    }

    private void createToolInfo(Composite parent) {
        // Tool name
        Label nameLabel = new Label(parent, SWT.NONE);
        nameLabel.setText("Инструмент: " + getToolDisplayName(toolCall.getName())); //$NON-NLS-1$
        nameLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Tool description
        if (toolDescription != null && !toolDescription.isEmpty()) {
            Label descLabel = new Label(parent, SWT.WRAP);
            descLabel.setText(toolDescription);
            GridData descData = new GridData(SWT.FILL, SWT.CENTER, true, false);
            descData.widthHint = 400;
            descLabel.setLayoutData(descData);
        }
    }

    private void createArgumentsPreview(Composite parent) {
        Map<String, Object> arguments = parseArguments(toolCall.getArguments());
        if (arguments == null || arguments.isEmpty()) {
            return;
        }

        Label argsLabel = new Label(parent, SWT.NONE);
        argsLabel.setText("Параметры:"); //$NON-NLS-1$

        StyledText argsText = new StyledText(parent, SWT.MULTI | SWT.READ_ONLY | SWT.BORDER | SWT.V_SCROLL);
        argsText.setFont(codeFont);
        argsText.setEditable(false);

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            sb.append(key).append(": "); //$NON-NLS-1$

            if (value instanceof String strValue) {
                // Truncate long strings
                if (strValue.length() > 200) {
                    sb.append(strValue.substring(0, 200)).append("..."); //$NON-NLS-1$
                } else {
                    sb.append(strValue);
                }
            } else {
                sb.append(String.valueOf(value));
            }
            sb.append("\n"); //$NON-NLS-1$
        }

        argsText.setText(sb.toString().trim());

        GridData argsData = new GridData(SWT.FILL, SWT.FILL, true, true);
        argsData.widthHint = 400;
        argsData.heightHint = 150;
        argsText.setLayoutData(argsData);
    }

    /**
     * Parses JSON arguments string to Map.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String json) {
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

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID,
                isDestructive ? "Выполнить" : "OK", true); //$NON-NLS-1$ //$NON-NLS-2$
        createButton(parent, IDialogConstants.CANCEL_ID,
                "Отмена", false); //$NON-NLS-1$

        if (isDestructive) {
            createButton(parent, IDialogConstants.NO_ID,
                    "Пропустить", false); //$NON-NLS-1$
        }
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == IDialogConstants.NO_ID) {
            // Skip this tool call
            setReturnCode(IDialogConstants.NO_ID);
            close();
        } else {
            super.buttonPressed(buttonId);
        }
    }

    @Override
    public boolean close() {
        // Dispose resources
        if (warningColor != null && !warningColor.isDisposed()) {
            warningColor.dispose();
        }
        if (codeFont != null && !codeFont.isDisposed()) {
            codeFont.dispose();
        }
        return super.close();
    }

    /**
     * Opens the dialog and returns the result.
     *
     * @return true if user confirmed, false if cancelled/skipped
     */
    public boolean openAndConfirm() {
        return open() == IDialogConstants.OK_ID;
    }

    /**
     * Returns whether the user chose to skip this tool call.
     *
     * @return true if skipped
     */
    public boolean wasSkipped() {
        return getReturnCode() == IDialogConstants.NO_ID;
    }
}
