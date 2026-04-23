/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.preferences;

import java.text.NumberFormat;
import java.util.Locale;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.codepilot1c.core.backend.BackendConfig;
import com.codepilot1c.core.backend.BackendService;
import com.codepilot1c.core.backend.RegistrationResult;
import com.codepilot1c.core.backend.UsageInfo;
import com.codepilot1c.core.internal.VibeCorePlugin;
import com.codepilot1c.core.provider.ILlmProvider;
import com.codepilot1c.core.provider.LlmProviderRegistry;
import com.codepilot1c.ui.dialogs.LoginDialog;
import com.codepilot1c.ui.dialogs.RegistrationDialog;

/**
 * Preference page for CodePilot account registration and usage information.
 */
public class AccountPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    public static final String PAGE_ID = "com.codepilot1c.ui.preferences.AccountPreferencePage"; //$NON-NLS-1$

    private static final Locale RUSSIAN_LOCALE = new Locale("ru", "RU"); //$NON-NLS-1$ //$NON-NLS-2$
    private static final NumberFormat CURRENCY_FORMAT = buildCurrencyFormat();
    private static final NumberFormat NUMBER_FORMAT = NumberFormat.getIntegerInstance(Locale.forLanguageTag("ru")); //$NON-NLS-1$

    private Composite stackContainer;
    private StackLayout stackLayout;
    private Composite configuredComposite;
    private Composite notConfiguredComposite;

    private Label statusLabel;
    private Label serverLabel;
    private Label userEmailLabel;
    private Label budgetLabel;
    private Label spentLabel;
    private Label remainingLabel;
    private ProgressBar usageProgressBar;
    private Label promptTokensLabel;
    private Label completionTokensLabel;
    private Label totalTokensLabel;
    private Label resetDateLabel;

    public AccountPreferencePage() {
        super();
        setDescription("Управление аккаунтом CodePilot"); //$NON-NLS-1$
        noDefaultAndApplyButton();
    }

    @Override
    public void init(IWorkbench workbench) {
        // Preference store is not used here.
    }

    @Override
    protected Control createContents(Composite parent) {
        stackContainer = new Composite(parent, SWT.NONE);
        stackLayout = new StackLayout();
        stackContainer.setLayout(stackLayout);
        stackContainer.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createConfiguredComposite(stackContainer);
        createNotConfiguredComposite(stackContainer);

        if (BackendService.getInstance().isConfigured()) {
            showConfiguredMode();
            loadUsageAsync();
        } else {
            showNotConfiguredMode();
        }

        return stackContainer;
    }

    private void createConfiguredComposite(Composite parent) {
        configuredComposite = new Composite(parent, SWT.NONE);
        configuredComposite.setLayout(new GridLayout(1, false));
        configuredComposite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createConnectionGroup(configuredComposite);
        createUsageGroup(configuredComposite);
    }

    private void createConnectionGroup(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Подключение"); //$NON-NLS-1$
        group.setLayout(new GridLayout(2, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        createLabel(group, "Статус:"); //$NON-NLS-1$
        statusLabel = new Label(group, SWT.NONE);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        createLabel(group, "Сервер:"); //$NON-NLS-1$
        serverLabel = new Label(group, SWT.NONE);
        serverLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        createLabel(group, "Пользователь:"); //$NON-NLS-1$
        userEmailLabel = new Label(group, SWT.NONE);
        userEmailLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Composite buttonRow = new Composite(group, SWT.NONE);
        buttonRow.setLayout(new GridLayout(2, false));
        buttonRow.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false, 2, 1));

        Button rotateKeyButton = new Button(buttonRow, SWT.PUSH);
        rotateKeyButton.setText("Обновить ключ"); //$NON-NLS-1$
        rotateKeyButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onRotateKey(rotateKeyButton);
            }
        });

        Button logoutButton = new Button(buttonRow, SWT.PUSH);
        logoutButton.setText("Выйти"); //$NON-NLS-1$
        logoutButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onLogout();
            }
        });

        updateConnectionInfo();
    }

    private void createUsageGroup(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText("Использование"); //$NON-NLS-1$
        group.setLayout(new GridLayout(2, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        createLabel(group, "Бюджет:"); //$NON-NLS-1$
        budgetLabel = new Label(group, SWT.NONE);
        budgetLabel.setText("..."); //$NON-NLS-1$
        budgetLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        createLabel(group, "Потрачено:"); //$NON-NLS-1$
        spentLabel = new Label(group, SWT.NONE);
        spentLabel.setText("..."); //$NON-NLS-1$
        spentLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        createLabel(group, "Остаток:"); //$NON-NLS-1$
        remainingLabel = new Label(group, SWT.NONE);
        remainingLabel.setText("..."); //$NON-NLS-1$
        remainingLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        usageProgressBar = new ProgressBar(group, SWT.SMOOTH);
        usageProgressBar.setMinimum(0);
        usageProgressBar.setMaximum(100);
        GridData progressData = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
        progressData.heightHint = 16;
        usageProgressBar.setLayoutData(progressData);

        createLabel(group, "Входных токенов:"); //$NON-NLS-1$
        promptTokensLabel = new Label(group, SWT.NONE);
        promptTokensLabel.setText("..."); //$NON-NLS-1$
        promptTokensLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        createLabel(group, "Выходных токенов:"); //$NON-NLS-1$
        completionTokensLabel = new Label(group, SWT.NONE);
        completionTokensLabel.setText("..."); //$NON-NLS-1$
        completionTokensLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        createLabel(group, "Всего токенов:"); //$NON-NLS-1$
        totalTokensLabel = new Label(group, SWT.NONE);
        totalTokensLabel.setText("..."); //$NON-NLS-1$
        totalTokensLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        createLabel(group, "Сброс бюджета:"); //$NON-NLS-1$
        resetDateLabel = new Label(group, SWT.NONE);
        resetDateLabel.setText("..."); //$NON-NLS-1$
        resetDateLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button refreshButton = new Button(group, SWT.PUSH);
        refreshButton.setText("Обновить"); //$NON-NLS-1$
        refreshButton.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false, 2, 1));
        refreshButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                loadUsageAsync();
            }
        });
    }

    private void createNotConfiguredComposite(Composite parent) {
        notConfiguredComposite = new Composite(parent, SWT.NONE);
        notConfiguredComposite.setLayout(new GridLayout(1, false));
        notConfiguredComposite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Label notConfiguredLabel = new Label(notConfiguredComposite, SWT.WRAP);
        notConfiguredLabel.setText("Аккаунт не настроен. Войдите или зарегистрируйтесь для доступа к CodePilot API."); //$NON-NLS-1$
        GridData labelData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        labelData.widthHint = 400;
        notConfiguredLabel.setLayoutData(labelData);

        Button registerButton = new Button(notConfiguredComposite, SWT.PUSH);
        registerButton.setText("Зарегистрироваться"); //$NON-NLS-1$
        registerButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onRegister();
            }
        });

        Button loginButton = new Button(notConfiguredComposite, SWT.PUSH);
        loginButton.setText("Войти"); //$NON-NLS-1$
        loginButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                onLogin();
            }
        });
    }

    private void showConfiguredMode() {
        stackLayout.topControl = configuredComposite;
        stackContainer.layout();
    }

    private void showNotConfiguredMode() {
        stackLayout.topControl = notConfiguredComposite;
        stackContainer.layout();
    }

    private void onRegister() {
        RegistrationDialog dialog = new RegistrationDialog(getShell());
        if (dialog.open() == RegistrationDialog.OK) {
            RegistrationResult result = dialog.getRegistrationResult();
            if (result != null && result.isSuccess()) {
                VibeCorePlugin.initializeLlmProvider(result.getApiKey(), true);
                updateConnectionInfo();
                showConfiguredMode();
                loadUsageAsync();
            }
        }
    }

    private void onLogin() {
        LoginDialog dialog = new LoginDialog(getShell());
        if (dialog.open() == LoginDialog.OK) {
            RegistrationResult result = dialog.getLoginResult();
            if (result != null && result.isSuccess()) {
                VibeCorePlugin.initializeLlmProvider(result.getApiKey(), true);
                updateConnectionInfo();
                showConfiguredMode();
                loadUsageAsync();
            }
        }
    }

    private void onRotateKey(Button button) {
        button.setEnabled(false);
        button.setText("Обновление..."); //$NON-NLS-1$

        BackendService.getInstance().rotateKey()
                .thenAccept(result -> {
                    Display display = getControl() != null ? getControl().getDisplay() : Display.getDefault();
                    if (display != null && !display.isDisposed()) {
                        display.asyncExec(() -> {
                            if (getControl() == null || getControl().isDisposed()) {
                                return;
                            }
                            button.setEnabled(true);
                            button.setText("Обновить ключ"); //$NON-NLS-1$

                            if (result != null && result.isSuccess()) {
                                VibeCorePlugin.initializeLlmProvider(result.getApiKey(), true);
                                MessageDialog.openInformation(getShell(), "Успех", "Ключ успешно обновлён"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                            } else {
                                String error = result != null ? result.getError() : "Неизвестная ошибка"; //$NON-NLS-1$
                                MessageDialog.openError(getShell(),
                                        "Ошибка", //$NON-NLS-1$
                                        "Не удалось обновить ключ: " + error); //$NON-NLS-1$
                            }
                        });
                    }
                });
    }

    private void onLogout() {
        boolean confirm = MessageDialog.openConfirm(getShell(),
                "Выход из аккаунта", //$NON-NLS-1$
                "Вы уверены, что хотите выйти? Для продолжения работы потребуется повторный вход."); //$NON-NLS-1$

        if (confirm) {
            BackendService.getInstance().clearCredentials();
            VibeCorePlugin.clearBackendLlmProvider();
            ILlmProvider fallbackProvider = LlmProviderRegistry.getInstance().getActiveProvider();
            if (fallbackProvider != null && fallbackProvider.isConfigured()) {
                LlmProviderRegistry.getInstance().setActiveProvider(fallbackProvider.getId());
            }
            showNotConfiguredMode();
        }
    }

    private void updateConnectionInfo() {
        BackendService service = BackendService.getInstance();
        if (statusLabel != null && !statusLabel.isDisposed()) {
            if (service.isConfigured()) {
                statusLabel.setText("\u25CF Подключён"); //$NON-NLS-1$
                statusLabel.setForeground(statusLabel.getDisplay().getSystemColor(SWT.COLOR_DARK_GREEN));
            } else {
                statusLabel.setText("\u25CF Не подключён"); //$NON-NLS-1$
                statusLabel.setForeground(statusLabel.getDisplay().getSystemColor(SWT.COLOR_RED));
            }
        }
        if (serverLabel != null && !serverLabel.isDisposed()) {
            serverLabel.setText(BackendConfig.BASE_URL);
        }
        if (userEmailLabel != null && !userEmailLabel.isDisposed()) {
            String email = service.getUserEmail();
            if (email == null || email.isEmpty()) {
                email = service.getUserId();
            }
            userEmailLabel.setText(email != null && !email.isEmpty() ? email : "—"); //$NON-NLS-1$
        }
    }

    private void loadUsageAsync() {
        BackendService.getInstance().getUsage()
                .thenAccept(usage -> {
                    Display display = getControl() != null ? getControl().getDisplay() : Display.getDefault();
                    if (display != null && !display.isDisposed()) {
                        display.asyncExec(() -> updateUsageDisplay(usage));
                    }
                });
    }

    private void updateUsageDisplay(UsageInfo usage) {
        if (getControl() == null || getControl().isDisposed() || usage == null) {
            return;
        }

        double percent = usage.getUsagePercent();

        if (budgetLabel != null && !budgetLabel.isDisposed()) {
            String duration = usage.getBudgetDuration() != null ? usage.getBudgetDuration() : "месяц"; //$NON-NLS-1$
            budgetLabel.setText(String.format("%s / %s", formatCurrency(usage.getMaxBudget()), duration)); //$NON-NLS-1$
        }
        if (spentLabel != null && !spentLabel.isDisposed()) {
            spentLabel.setText(String.format("%s (%.1f%%)", formatCurrency(usage.getSpend()), percent)); //$NON-NLS-1$
        }
        if (remainingLabel != null && !remainingLabel.isDisposed()) {
            remainingLabel.setText(formatCurrency(usage.getRemaining()));
        }
        if (usageProgressBar != null && !usageProgressBar.isDisposed()) {
            usageProgressBar.setSelection((int) Math.min(percent, 100));
            updateProgressBarColor(percent);
        }
        if (promptTokensLabel != null && !promptTokensLabel.isDisposed()) {
            promptTokensLabel.setText(NUMBER_FORMAT.format(usage.getPromptTokens()));
        }
        if (completionTokensLabel != null && !completionTokensLabel.isDisposed()) {
            completionTokensLabel.setText(NUMBER_FORMAT.format(usage.getCompletionTokens()));
        }
        if (totalTokensLabel != null && !totalTokensLabel.isDisposed()) {
            totalTokensLabel.setText(NUMBER_FORMAT.format(usage.getTotalTokens()));
        }
        if (resetDateLabel != null && !resetDateLabel.isDisposed()) {
            String resetDate = usage.getResetDate();
            resetDateLabel.setText(resetDate != null && !resetDate.isEmpty() ? resetDate : "—"); //$NON-NLS-1$
        }

        configuredComposite.layout(true, true);
    }

    private void updateProgressBarColor(double percent) {
        if (usageProgressBar == null || usageProgressBar.isDisposed()) {
            return;
        }
        Display display = usageProgressBar.getDisplay();
        Color color;
        if (percent >= 90) {
            color = display.getSystemColor(SWT.COLOR_RED);
        } else if (percent >= 70) {
            color = display.getSystemColor(SWT.COLOR_DARK_YELLOW);
        } else {
            color = display.getSystemColor(SWT.COLOR_DARK_GREEN);
        }
        usageProgressBar.setForeground(color);
    }

    private Label createLabel(Composite parent, String text) {
        Label label = new Label(parent, SWT.NONE);
        label.setText(text);
        label.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        return label;
    }

    private static NumberFormat buildCurrencyFormat() {
        NumberFormat format = NumberFormat.getCurrencyInstance(RUSSIAN_LOCALE);
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return format;
    }

    private static String formatCurrency(double value) {
        return CURRENCY_FORMAT.format(value);
    }
}
