/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.ui.handlers.HandlerUtil;

import com.codepilot1c.core.backend.RegistrationResult;
import com.codepilot1c.core.internal.VibeCorePlugin;
import com.codepilot1c.ui.dialogs.LoginDialog;
import com.codepilot1c.ui.dialogs.RegistrationDialog;

/**
 * Handler that opens login or registration flow for CodePilot account.
 */
public class RegisterHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        org.eclipse.swt.widgets.Shell shell = HandlerUtil.getActiveShell(event);
        MessageDialog chooser = new MessageDialog(
                shell,
                "Авторизация", //$NON-NLS-1$
                null,
                "Выберите действие", //$NON-NLS-1$
                MessageDialog.QUESTION,
                new String[] { "Войти", "Зарегистрироваться", "Отмена" }, //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                0);
        int choice = chooser.open();
        if (choice == 0) {
            LoginDialog dialog = new LoginDialog(shell);
            if (dialog.open() == Window.OK) {
                RegistrationResult result = dialog.getLoginResult();
                if (result != null && result.isSuccess()) {
                    VibeCorePlugin.initializeLlmProvider(result.getApiKey(), true);
                }
            }
        } else if (choice == 1) {
            RegistrationDialog dialog = new RegistrationDialog(shell);
            if (dialog.open() == Window.OK) {
                RegistrationResult result = dialog.getRegistrationResult();
                if (result != null && result.isSuccess()) {
                    VibeCorePlugin.initializeLlmProvider(result.getApiKey(), true);
                }
            }
        }
        return null;
    }
}
