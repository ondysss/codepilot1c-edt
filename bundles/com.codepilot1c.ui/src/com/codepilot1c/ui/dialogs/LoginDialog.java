/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.dialogs;

import java.util.regex.Pattern;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import com.codepilot1c.core.backend.BackendConfig;
import com.codepilot1c.core.backend.BackendService;
import com.codepilot1c.core.backend.RegistrationResult;

/**
 * Dialog for existing CodePilot account login.
 */
public class LoginDialog extends TitleAreaDialog {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$"); //$NON-NLS-1$
    private static final int MIN_PASSWORD_LENGTH = 8;

    private Text emailText;
    private Text passwordText;
    private Button loginButton;

    private RegistrationResult loginResult;

    public LoginDialog(Shell parentShell) {
        super(parentShell);
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Вход"); //$NON-NLS-1$
        shell.setMinimumSize(480, 280);
    }

    @Override
    public void create() {
        super.create();
        setTitle("Вход в CodePilot"); //$NON-NLS-1$
        setMessage("Введите email и пароль для входа."); //$NON-NLS-1$
        validateInput();
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        Composite container = new Composite(area, SWT.NONE);
        container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 15;
        layout.marginHeight = 15;
        layout.verticalSpacing = 10;
        container.setLayout(layout);

        Label emailLabel = new Label(container, SWT.NONE);
        emailLabel.setText("Email:"); //$NON-NLS-1$
        emailText = new Text(container, SWT.BORDER);
        emailText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        emailText.addModifyListener(e -> validateInput());

        Label passwordLabel = new Label(container, SWT.NONE);
        passwordLabel.setText("Пароль:"); //$NON-NLS-1$
        passwordText = new Text(container, SWT.BORDER | SWT.PASSWORD);
        passwordText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        passwordText.addModifyListener(e -> validateInput());

        Label serverLabel = new Label(container, SWT.NONE);
        serverLabel.setText("Auth API:"); //$NON-NLS-1$
        Label serverValue = new Label(container, SWT.NONE);
        serverValue.setText(BackendConfig.AUTH_BASE_URL);
        serverValue.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        loginButton = createButton(parent, IDialogConstants.OK_ID, "Войти", true); //$NON-NLS-1$
        loginButton.setEnabled(false);
        createButton(parent, IDialogConstants.CANCEL_ID, "Отмена", false); //$NON-NLS-1$
    }

    @Override
    protected void okPressed() {
        setWorkingState(true);
        BackendService.getInstance().login(getEmail(), getPassword())
                .thenAccept(result -> {
                    Display display = getShell() != null ? getShell().getDisplay() : Display.getDefault();
                    if (display != null && !display.isDisposed()) {
                        display.asyncExec(() -> handleLoginResult(result));
                    }
                });
    }

    private void handleLoginResult(RegistrationResult result) {
        if (getShell() == null || getShell().isDisposed()) {
            return;
        }
        setWorkingState(false);
        if (result != null && result.isSuccess()) {
            loginResult = result;
            setReturnCode(OK);
            close();
            return;
        }
        setErrorMessage(formatError(
                result != null ? result.getErrorCode() : null,
                result != null ? result.getError() : "Ошибка входа")); //$NON-NLS-1$
    }

    private void setWorkingState(boolean working) {
        if (loginButton != null && !loginButton.isDisposed()) {
            loginButton.setEnabled(!working);
            loginButton.setText(working ? "Вход..." : "Войти"); //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (emailText != null && !emailText.isDisposed()) {
            emailText.setEnabled(!working);
        }
        if (passwordText != null && !passwordText.isDisposed()) {
            passwordText.setEnabled(!working);
        }
    }

    private void validateInput() {
        if (loginButton == null || loginButton.isDisposed()) {
            return;
        }
        if (getEmail().isEmpty()) {
            setErrorMessage("Введите email"); //$NON-NLS-1$
            loginButton.setEnabled(false);
            return;
        }
        if (!EMAIL_PATTERN.matcher(getEmail()).matches()) {
            setErrorMessage("Некорректный email"); //$NON-NLS-1$
            loginButton.setEnabled(false);
            return;
        }
        if (getPassword().length() < MIN_PASSWORD_LENGTH) {
            setErrorMessage("Пароль должен содержать минимум 8 символов"); //$NON-NLS-1$
            loginButton.setEnabled(false);
            return;
        }
        setErrorMessage(null);
        loginButton.setEnabled(true);
    }

    private String formatError(String errorCode, String fallback) {
        if ("invalid_credentials".equals(errorCode)) { //$NON-NLS-1$
            return "Неверный email или пароль"; //$NON-NLS-1$
        }
        if ("rate_limited".equals(errorCode)) { //$NON-NLS-1$
            return "Слишком много попыток входа. Подождите и попробуйте снова."; //$NON-NLS-1$
        }
        if ("service_unavailable".equals(errorCode)) { //$NON-NLS-1$
            return "Сервис авторизации временно недоступен"; //$NON-NLS-1$
        }
        return fallback != null && !fallback.isEmpty() ? fallback : "Ошибка входа"; //$NON-NLS-1$
    }

    private String getEmail() {
        return emailText != null ? emailText.getText().trim() : ""; //$NON-NLS-1$
    }

    private String getPassword() {
        return passwordText != null ? passwordText.getText() : ""; //$NON-NLS-1$
    }

    public RegistrationResult getLoginResult() {
        return loginResult;
    }
}
