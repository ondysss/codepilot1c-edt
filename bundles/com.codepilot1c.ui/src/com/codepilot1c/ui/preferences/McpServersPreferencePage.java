/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.preferences;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITableColorProvider;
import org.eclipse.jface.viewers.ITableLabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.codepilot1c.core.mcp.McpServerManager;
import com.codepilot1c.core.mcp.config.McpServerConfig;
import com.codepilot1c.core.mcp.config.McpServerConfigStore;
import com.codepilot1c.core.mcp.model.McpServerState;
import com.codepilot1c.ui.internal.Messages;

/**
 * Preference page for managing MCP servers.
 */
public class McpServersPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    private TableViewer tableViewer;
    private List<McpServerConfig> servers;
    private Button addButton;
    private Button editButton;
    private Button removeButton;
    private Button startButton;
    private Button stopButton;

    @Override
    public void init(IWorkbench workbench) {
        servers = new ArrayList<>(McpServerConfigStore.getInstance().getServers());
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite container = new Composite(parent, SWT.NONE);
        container.setLayout(new GridLayout(2, false));

        // Description
        Label description = new Label(container, SWT.WRAP);
        description.setText(Messages.McpServersPreferencePage_Description);
        GridData gd = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
        gd.widthHint = 400;
        description.setLayoutData(gd);

        // Table
        createTable(container);

        // Buttons
        createButtons(container);

        return container;
    }

    private void createTable(Composite parent) {
        tableViewer = new TableViewer(parent,
            SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE);
        Table table = tableViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);

        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.heightHint = 200;
        table.setLayoutData(gd);

        // Columns
        createColumn(table, Messages.McpServersPreferencePage_ColumnEnabled, 40);
        createColumn(table, Messages.McpServersPreferencePage_ColumnName, 150);
        createColumn(table, Messages.McpServersPreferencePage_ColumnCommand, 200);
        createColumn(table, Messages.McpServersPreferencePage_ColumnStatus, 100);

        tableViewer.setContentProvider(ArrayContentProvider.getInstance());
        tableViewer.setLabelProvider(new McpServerLabelProvider());
        tableViewer.setInput(servers);

        tableViewer.addDoubleClickListener(e -> editServer());
        tableViewer.addSelectionChangedListener(new ISelectionChangedListener() {
            @Override
            public void selectionChanged(SelectionChangedEvent event) {
                updateButtonStates();
            }
        });
    }

    private TableColumn createColumn(Table table, String text, int width) {
        TableColumn column = new TableColumn(table, SWT.NONE);
        column.setText(text);
        column.setWidth(width);
        return column;
    }

    private void createButtons(Composite parent) {
        Composite buttonBar = new Composite(parent, SWT.NONE);
        buttonBar.setLayout(new GridLayout(1, false));
        buttonBar.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));

        addButton = createButton(buttonBar, Messages.McpServersPreferencePage_Add, this::addServer);
        editButton = createButton(buttonBar, Messages.McpServersPreferencePage_Edit, this::editServer);
        removeButton = createButton(buttonBar, Messages.McpServersPreferencePage_Remove, this::removeServer);

        Label separator = new Label(buttonBar, SWT.SEPARATOR | SWT.HORIZONTAL);
        separator.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        startButton = createButton(buttonBar, Messages.McpServersPreferencePage_Start, this::startServer);
        stopButton = createButton(buttonBar, Messages.McpServersPreferencePage_Stop, this::stopServer);

        updateButtonStates();
    }

    private Button createButton(Composite parent, String text, Runnable action) {
        Button button = new Button(parent, SWT.PUSH);
        button.setText(text);
        button.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        button.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> action.run()));
        return button;
    }

    private void addServer() {
        McpServerEditDialog dialog = new McpServerEditDialog(getShell(), null);
        if (dialog.open() == Window.OK) {
            servers.add(dialog.getServerConfig());
            tableViewer.refresh();
        }
    }

    private void editServer() {
        McpServerConfig selected = getSelectedServer();
        if (selected != null) {
            McpServerEditDialog dialog = new McpServerEditDialog(getShell(), selected);
            if (dialog.open() == Window.OK) {
                int index = servers.indexOf(selected);
                servers.set(index, dialog.getServerConfig());
                tableViewer.refresh();
            }
        }
    }

    private void removeServer() {
        McpServerConfig selected = getSelectedServer();
        if (selected != null) {
            boolean confirm = MessageDialog.openConfirm(getShell(),
                Messages.ProvidersPreferencePage_RemoveConfirmTitle,
                String.format(Messages.ProvidersPreferencePage_RemoveConfirmMessage, selected.getName()));
            if (confirm) {
                // Stop server if running
                McpServerManager.getInstance().stopServer(selected.getId());
                servers.remove(selected);
                tableViewer.refresh();
            }
        }
    }

    private void startServer() {
        McpServerConfig selected = getSelectedServer();
        if (selected != null) {
            McpServerManager.getInstance().startServer(selected)
                .thenRun(() -> Display.getDefault().asyncExec(() -> {
                    tableViewer.refresh();
                    updateButtonStates();
                }));
            tableViewer.refresh();
            updateButtonStates();
        }
    }

    private void stopServer() {
        McpServerConfig selected = getSelectedServer();
        if (selected != null) {
            McpServerManager.getInstance().stopServer(selected.getId());
            tableViewer.refresh();
            updateButtonStates();
        }
    }

    private McpServerConfig getSelectedServer() {
        IStructuredSelection selection = tableViewer.getStructuredSelection();
        return (McpServerConfig) selection.getFirstElement();
    }

    private void updateButtonStates() {
        McpServerConfig selected = getSelectedServer();
        boolean hasSelection = selected != null;
        McpServerState state = hasSelection ?
            McpServerManager.getInstance().getServerState(selected.getId()) :
            McpServerState.STOPPED;
        boolean isRunning = state == McpServerState.RUNNING;
        boolean isStarting = state == McpServerState.STARTING;

        editButton.setEnabled(hasSelection);
        removeButton.setEnabled(hasSelection);
        startButton.setEnabled(hasSelection && !isRunning && !isStarting);
        stopButton.setEnabled(hasSelection && (isRunning || isStarting));
    }

    @Override
    public boolean performOk() {
        McpServerConfigStore.getInstance().setServers(servers);
        return true;
    }

    @Override
    protected void performDefaults() {
        servers.clear();
        tableViewer.refresh();
        super.performDefaults();
    }

    /**
     * Label provider showing status with colors.
     */
    private class McpServerLabelProvider extends LabelProvider
            implements ITableLabelProvider, ITableColorProvider {

        @Override
        public String getColumnText(Object element, int columnIndex) {
            McpServerConfig config = (McpServerConfig) element;
            switch (columnIndex) {
                case 0:
                    return config.isEnabled() ? "\u2713" : "";
                case 1:
                    return config.getName();
                case 2:
                    if (config.getTransportType() == McpServerConfig.TransportType.STDIO) {
                        String cmd = config.getCommand();
                        List<String> args = config.getArgs();
                        if (!args.isEmpty()) {
                            cmd += " " + String.join(" ", args);
                        }
                        return cmd;
                    }
                    String url = config.getRemoteUrl() != null ? config.getRemoteUrl() : "";
                    return config.getTransportType().name() + ": " + url;
                case 3:
                    return getStatusText(config);
                default:
                    return "";
            }
        }

        private String getStatusText(McpServerConfig config) {
            McpServerState state = McpServerManager.getInstance().getServerState(config.getId());
            switch (state) {
                case RUNNING:
                    var client = McpServerManager.getInstance().getClient(config.getId());
                    if (client != null && client.getNegotiatedProtocolVersion() != null) {
                        return Messages.McpServersPreferencePage_StatusRunning
                            + " (" + client.getNegotiatedProtocolVersion() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    return Messages.McpServersPreferencePage_StatusRunning;
                case STARTING:
                    return Messages.McpServersPreferencePage_StatusStarting;
                case ERROR:
                    return Messages.McpServersPreferencePage_StatusError;
                default:
                    return Messages.McpServersPreferencePage_StatusStopped;
            }
        }

        @Override
        public Image getColumnImage(Object element, int columnIndex) {
            return null;
        }

        @Override
        public Color getForeground(Object element, int columnIndex) {
            if (columnIndex == 3) {
                McpServerConfig config = (McpServerConfig) element;
                McpServerState state = McpServerManager.getInstance().getServerState(config.getId());
                if (state == McpServerState.RUNNING) {
                    return Display.getDefault().getSystemColor(SWT.COLOR_DARK_GREEN);
                } else if (state == McpServerState.ERROR) {
                    return Display.getDefault().getSystemColor(SWT.COLOR_RED);
                } else if (state == McpServerState.STARTING) {
                    return Display.getDefault().getSystemColor(SWT.COLOR_DARK_YELLOW);
                }
            }
            return null;
        }

        @Override
        public Color getBackground(Object element, int columnIndex) {
            return null;
        }
    }
}
