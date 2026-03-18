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
import org.eclipse.swt.custom.StackLayout;
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
import com.codepilot1c.core.backend.SignupStartResult;

/**
 * Dialog for 2-step registration with email verification.
 */
public class RegistrationDialog extends TitleAreaDialog {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$"); //$NON-NLS-1$
    private static final int MIN_NAME_LENGTH = 2;
    private static final int MIN_PASSWORD_LENGTH = 8;

    private enum Step {
        REQUEST_CODE,
        CONFIRM_CODE
    }

    private Step currentStep = Step.REQUEST_CODE;

    private Text emailText;
    private Text nameText;
    private Text passwordText;
    private Text verificationCodeText;

    private Composite codeContainer;
    private StackLayout codeLayout;
    private Composite codeHiddenComposite;
    private Composite codeVisibleComposite;

    private Button actionButton;
    private Button resendButton;

    private int resendCountdownSeconds;
    private RegistrationResult registrationResult;

    public RegistrationDialog(Shell parentShell) {
        super(parentShell);
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Регистрация"); //$NON-NLS-1$
        shell.setMinimumSize(520, 380);
    }

    @Override
    public void create() {
        super.create();
        setTitle("Регистрация в CodePilot"); //$NON-NLS-1$
        setMessage("Шаг 1 из 2. Введите данные и отправьте код подтверждения на email."); //$NON-NLS-1$
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

        Label nameLabel = new Label(container, SWT.NONE);
        nameLabel.setText("Имя:"); //$NON-NLS-1$
        nameText = new Text(container, SWT.BORDER);
        nameText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        nameText.addModifyListener(e -> validateInput());

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

        codeContainer = new Composite(container, SWT.NONE);
        codeLayout = new StackLayout();
        codeContainer.setLayout(codeLayout);
        codeContainer.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        codeHiddenComposite = new Composite(codeContainer, SWT.NONE);
        codeHiddenComposite.setLayout(new GridLayout(1, false));

        codeVisibleComposite = new Composite(codeContainer, SWT.NONE);
        GridLayout codeVisibleLayout = new GridLayout(2, false);
        codeVisibleLayout.marginWidth = 0;
        codeVisibleLayout.marginHeight = 0;
        codeVisibleComposite.setLayout(codeVisibleLayout);

        Label codeLabel = new Label(codeVisibleComposite, SWT.NONE);
        codeLabel.setText("Код подтверждения:"); //$NON-NLS-1$
        verificationCodeText = new Text(codeVisibleComposite, SWT.BORDER);
        verificationCodeText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        verificationCodeText.addModifyListener(e -> validateInput());

        Composite resendRow = new Composite(codeVisibleComposite, SWT.NONE);
        GridLayout resendLayout = new GridLayout(1, false);
        resendLayout.marginWidth = 0;
        resendLayout.marginHeight = 0;
        resendRow.setLayout(resendLayout);
        resendRow.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false, 2, 1));

        resendButton = new Button(resendRow, SWT.PUSH);
        resendButton.setText("Отправить код повторно"); //$NON-NLS-1$
        resendButton.setEnabled(false);
        resendButton.addSelectionListener(new org.eclipse.swt.events.SelectionAdapter() {
            @Override
            public void widgetSelected(org.eclipse.swt.events.SelectionEvent e) {
                requestCode();
            }
        });

        codeLayout.topControl = codeHiddenComposite;
        codeContainer.layout();
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        actionButton = createButton(parent, IDialogConstants.OK_ID, "Отправить код", true); //$NON-NLS-1$
        actionButton.setEnabled(false);
        createButton(parent, IDialogConstants.CANCEL_ID, "Отмена", false); //$NON-NLS-1$
    }

    @Override
    protected void okPressed() {
        if (currentStep == Step.REQUEST_CODE) {
            requestCode();
            return;
        }
        confirmSignup();
    }

    private void requestCode() {
        setWorkingState(true, "Отправка кода..."); //$NON-NLS-1$
        BackendService.getInstance().signupStart(getEmail(), getName())
                .thenAccept(result -> {
                    Display display = getDisplaySafe();
                    if (display != null && !display.isDisposed()) {
                        display.asyncExec(() -> handleSignupStartResult(result));
                    }
                });
    }

    private void handleSignupStartResult(SignupStartResult result) {
        if (isDialogDisposed()) {
            return;
        }
        setWorkingState(false, "Отправить код"); //$NON-NLS-1$

        if (result != null && result.isSuccess()) {
            currentStep = Step.CONFIRM_CODE;
            actionButton.setText("Завершить регистрацию"); //$NON-NLS-1$
            codeLayout.topControl = codeVisibleComposite;
            codeContainer.layout(true, true);
            setErrorMessage(null);
            setMessage("Шаг 2 из 2. Введите код из письма и завершите регистрацию."); //$NON-NLS-1$
            startResendCountdown((int) result.getResendAvailableInSeconds());
            validateInput();
            return;
        }

        setErrorMessage(formatError(
                result != null ? result.getErrorCode() : null,
                result != null ? result.getError() : "Не удалось отправить код")); //$NON-NLS-1$
    }

    private void confirmSignup() {
        setWorkingState(true, "Подтверждение..."); //$NON-NLS-1$
        BackendService.getInstance().signupConfirm(
                getEmail(),
                getName(),
                getPassword(),
                getVerificationCode())
                .thenAccept(result -> {
                    Display display = getDisplaySafe();
                    if (display != null && !display.isDisposed()) {
                        display.asyncExec(() -> handleConfirmResult(result));
                    }
                });
    }

    private void handleConfirmResult(RegistrationResult result) {
        if (isDialogDisposed()) {
            return;
        }
        setWorkingState(false, "Завершить регистрацию"); //$NON-NLS-1$

        if (result != null && result.isSuccess()) {
            registrationResult = result;
            setReturnCode(OK);
            close();
            return;
        }

        setErrorMessage(formatError(
                result != null ? result.getErrorCode() : null,
                result != null ? result.getError() : "Ошибка регистрации")); //$NON-NLS-1$
    }

    private void setWorkingState(boolean working, String actionText) {
        if (actionButton != null && !actionButton.isDisposed()) {
            actionButton.setEnabled(!working);
            actionButton.setText(actionText);
        }
        if (emailText != null && !emailText.isDisposed()) {
            emailText.setEnabled(!working && currentStep == Step.REQUEST_CODE);
        }
        if (nameText != null && !nameText.isDisposed()) {
            nameText.setEnabled(!working && currentStep == Step.REQUEST_CODE);
        }
        if (passwordText != null && !passwordText.isDisposed()) {
            passwordText.setEnabled(!working && currentStep == Step.REQUEST_CODE);
        }
        if (verificationCodeText != null && !verificationCodeText.isDisposed()) {
            verificationCodeText.setEnabled(!working);
        }
        if (resendButton != null && !resendButton.isDisposed() && working) {
            resendButton.setEnabled(false);
        }
    }

    private void validateInput() {
        if (actionButton == null || actionButton.isDisposed()) {
            return;
        }

        String email = getEmail();
        String name = getName();
        String password = getPassword();

        if (email.isEmpty()) {
            setErrorMessage("Введите email"); //$NON-NLS-1$
            actionButton.setEnabled(false);
            return;
        }
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            setErrorMessage("Некорректный email"); //$NON-NLS-1$
            actionButton.setEnabled(false);
            return;
        }
        if (name.isEmpty()) {
            setErrorMessage("Введите имя"); //$NON-NLS-1$
            actionButton.setEnabled(false);
            return;
        }
        if (name.length() < MIN_NAME_LENGTH) {
            setErrorMessage("Имя должно содержать минимум 2 символа"); //$NON-NLS-1$
            actionButton.setEnabled(false);
            return;
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            setErrorMessage("Пароль должен содержать минимум 8 символов"); //$NON-NLS-1$
            actionButton.setEnabled(false);
            return;
        }
        if (currentStep == Step.CONFIRM_CODE && getVerificationCode().isEmpty()) {
            setErrorMessage("Введите код подтверждения из письма"); //$NON-NLS-1$
            actionButton.setEnabled(false);
            return;
        }

        setErrorMessage(null);
        actionButton.setEnabled(true);
    }

    private void startResendCountdown(int seconds) {
        resendCountdownSeconds = Math.max(0, seconds);
        updateResendButton();
        scheduleNextResendTick();
    }

    private void scheduleNextResendTick() {
        if (resendCountdownSeconds <= 0) {
            return;
        }
        Display display = getDisplaySafe();
        if (display == null || display.isDisposed()) {
            return;
        }
        display.timerExec(1000, () -> {
            if (isDialogDisposed()) {
                return;
            }
            if (resendCountdownSeconds > 0) {
                resendCountdownSeconds--;
                updateResendButton();
                scheduleNextResendTick();
            }
        });
    }

    private void updateResendButton() {
        if (resendButton == null || resendButton.isDisposed()) {
            return;
        }
        if (resendCountdownSeconds > 0) {
            resendButton.setEnabled(false);
            resendButton.setText("Отправить код повторно (" + resendCountdownSeconds + " c)"); //$NON-NLS-1$ //$NON-NLS-2$
        } else {
            resendButton.setEnabled(true);
            resendButton.setText("Отправить код повторно"); //$NON-NLS-1$
        }
    }

    private String formatError(String errorCode, String fallback) {
        if ("verification_required".equals(errorCode)) { //$NON-NLS-1$
            return "Введите код подтверждения из письма"; //$NON-NLS-1$
        }
        if ("verification_not_started".equals(errorCode)) { //$NON-NLS-1$
            return "Сначала запросите код подтверждения"; //$NON-NLS-1$
        }
        if ("verification_invalid_code".equals(errorCode)) { //$NON-NLS-1$
            return "Неверный код подтверждения"; //$NON-NLS-1$
        }
        if ("verification_expired".equals(errorCode)) { //$NON-NLS-1$
            return "Срок действия кода истёк. Запросите новый код."; //$NON-NLS-1$
        }
        if ("verification_too_many_attempts".equals(errorCode) || "rate_limited".equals(errorCode)) { //$NON-NLS-1$ //$NON-NLS-2$
            return "Слишком много попыток. Подождите и попробуйте снова."; //$NON-NLS-1$
        }
        if ("already_registered".equals(errorCode)) { //$NON-NLS-1$
            return "Этот email уже зарегистрирован"; //$NON-NLS-1$
        }
        if ("service_unavailable".equals(errorCode)) { //$NON-NLS-1$
            return "Сервис регистрации временно недоступен"; //$NON-NLS-1$
        }
        return fallback != null && !fallback.isEmpty() ? fallback : "Ошибка регистрации"; //$NON-NLS-1$
    }

    private String getEmail() {
        return emailText != null ? emailText.getText().trim() : ""; //$NON-NLS-1$
    }

    private String getName() {
        return nameText != null ? nameText.getText().trim() : ""; //$NON-NLS-1$
    }

    private String getPassword() {
        return passwordText != null ? passwordText.getText() : ""; //$NON-NLS-1$
    }

    private String getVerificationCode() {
        return verificationCodeText != null ? verificationCodeText.getText().trim() : ""; //$NON-NLS-1$
    }

    private boolean isDialogDisposed() {
        return getShell() == null || getShell().isDisposed();
    }

    private Display getDisplaySafe() {
        Shell shell = getShell();
        return shell != null ? shell.getDisplay() : Display.getDefault();
    }

    public RegistrationResult getRegistrationResult() {
        return registrationResult;
    }
}
