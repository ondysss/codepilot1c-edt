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
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Handler for opening the OS shell terminal view.
 */
public class OpenTerminalHandler extends AbstractHandler {

    private static final String TERMINAL_VIEW_ID = "org.eclipse.tm.terminal.view.ui.TerminalsView"; //$NON-NLS-1$
    private static final String TERMINAL_BUNDLE_ID = "org.eclipse.tm.terminal.view.ui"; //$NON-NLS-1$

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        if (window == null) {
            return null;
        }

        if (Platform.getBundle(TERMINAL_BUNDLE_ID) == null) {
            MessageDialog.openError(
                    window.getShell(),
                    "Терминал недоступен",
                    "Не найден пакет TM Terminal. Добавьте его в target/установку EDT и повторите.");
            return null;
        }

        IWorkbenchPage page = window.getActivePage();
        if (page == null) {
            return null;
        }

        try {
            page.showView(TERMINAL_VIEW_ID);
        } catch (PartInitException e) {
            throw new ExecutionException("Failed to open Terminal view", e); //$NON-NLS-1$
        }

        return null;
    }
}
