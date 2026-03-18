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

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.codepilot1c.core.backend.BackendService;
import com.codepilot1c.core.provider.ILlmProvider;
import com.codepilot1c.core.provider.LlmProviderRegistry;
import com.codepilot1c.core.provider.config.DynamicLlmProvider;
import com.codepilot1c.core.provider.config.LlmProviderConfig;
import com.codepilot1c.core.provider.config.LlmProviderConfigStore;
import com.codepilot1c.ui.internal.Messages;

/**
 * Preference page for configuring LLM providers.
 *
 * <p>Provides a table-based UI for managing provider configurations
 * with Add, Edit, Remove, and Set Active functionality.</p>
 */
public class ProvidersPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    private TableViewer tableViewer;
    private Button addButton;
    private Button editButton;
    private Button removeButton;
    private Button duplicateButton;
    private Button setActiveButton;
    private Button useAccountButton;
    private Label accountStatusValue;
    private Label accountModelValue;
    private Label accountDetailsValue;

    private List<LlmProviderConfig> providers;
    private String activeProviderId;

    public ProvidersPreferencePage() {
        setDescription(Messages.ProvidersPreferencePage_Description);
    }

    @Override
    public void init(IWorkbench workbench) {
        // Load providers from config store
        LlmProviderConfigStore store = LlmProviderConfigStore.getInstance();
        providers = new ArrayList<>(store.getProviders());
        activeProviderId = LlmProviderRegistry.getInstance().getEffectiveActiveProviderId();
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite container = new Composite(parent, SWT.NONE);
        container.setLayout(new GridLayout(1, false));
        container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createAccountProviderSection(container);

        Composite providersContainer = new Composite(container, SWT.NONE);
        providersContainer.setLayout(new GridLayout(2, false));
        providersContainer.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // Create table
        createTable(providersContainer);

        // Create buttons
        createButtons(providersContainer);

        // Initial update
        updateButtonStates();
        refreshAccountProviderSection();

        return container;
    }

    private void createAccountProviderSection(Composite parent) {
        Group group = new Group(parent, SWT.NONE);
        group.setText(Messages.ProvidersPreferencePage_AccountGroup);
        group.setLayout(new GridLayout(2, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        createAccountRow(group, Messages.ProvidersPreferencePage_AccountStatus);
        accountStatusValue = new Label(group, SWT.WRAP);
        accountStatusValue.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        createAccountRow(group, Messages.ProvidersPreferencePage_AccountModel);
        accountModelValue = new Label(group, SWT.WRAP);
        accountModelValue.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        createAccountRow(group, Messages.ProvidersPreferencePage_AccountDetails);
        accountDetailsValue = new Label(group, SWT.WRAP);
        GridData detailsData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        detailsData.widthHint = 420;
        accountDetailsValue.setLayoutData(detailsData);

        useAccountButton = new Button(group, SWT.PUSH);
        useAccountButton.setText(Messages.ProvidersPreferencePage_UseAccountProvider);
        useAccountButton.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false, 2, 1));
        useAccountButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                setActiveAccountProvider();
            }
        });
    }

    private void createAccountRow(Composite parent, String labelText) {
        Label label = new Label(parent, SWT.NONE);
        label.setText(labelText);
        label.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
    }

    private void createTable(Composite parent) {
        tableViewer = new TableViewer(parent, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
        Table table = tableViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);

        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.heightHint = 300;
        table.setLayoutData(gd);

        // Active column (checkmark)
        TableViewerColumn activeCol = createColumn(Messages.ProvidersPreferencePage_ColumnActive, 50);
        activeCol.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                LlmProviderConfig config = (LlmProviderConfig) element;
                return config.getId().equals(activeProviderId) ? "\u2713" : ""; //$NON-NLS-1$ //$NON-NLS-2$
            }
        });

        // Name column
        TableViewerColumn nameCol = createColumn(Messages.ProvidersPreferencePage_ColumnName, 150);
        nameCol.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((LlmProviderConfig) element).getName();
            }
        });

        // Type column
        TableViewerColumn typeCol = createColumn(Messages.ProvidersPreferencePage_ColumnType, 120);
        typeCol.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((LlmProviderConfig) element).getType().getDisplayName();
            }
        });

        // Model column
        TableViewerColumn modelCol = createColumn(Messages.ProvidersPreferencePage_ColumnModel, 150);
        modelCol.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((LlmProviderConfig) element).getModel();
            }
        });

        // Status column
        TableViewerColumn statusCol = createColumn(Messages.ProvidersPreferencePage_ColumnStatus, 100);
        statusCol.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                LlmProviderConfig config = (LlmProviderConfig) element;
                return config.isConfigured() ?
                        Messages.ProvidersPreferencePage_StatusConfigured :
                        Messages.ProvidersPreferencePage_StatusNotConfigured;
            }
        });

        tableViewer.setContentProvider(ArrayContentProvider.getInstance());
        tableViewer.setInput(providers);

        tableViewer.addSelectionChangedListener(new ISelectionChangedListener() {
            @Override
            public void selectionChanged(SelectionChangedEvent event) {
                updateButtonStates();
            }
        });

        // Double-click to edit
        tableViewer.addDoubleClickListener(event -> editProvider());
    }

    private TableViewerColumn createColumn(String title, int width) {
        TableViewerColumn column = new TableViewerColumn(tableViewer, SWT.NONE);
        TableColumn tc = column.getColumn();
        tc.setText(title);
        tc.setWidth(width);
        tc.setResizable(true);
        return column;
    }

    private void createButtons(Composite parent) {
        Composite buttonArea = new Composite(parent, SWT.NONE);
        buttonArea.setLayout(new GridLayout(1, false));
        buttonArea.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));

        addButton = createButton(buttonArea, Messages.ProvidersPreferencePage_Add, this::addProvider);
        editButton = createButton(buttonArea, Messages.ProvidersPreferencePage_Edit, this::editProvider);
        removeButton = createButton(buttonArea, Messages.ProvidersPreferencePage_Remove, this::removeProvider);
        duplicateButton = createButton(buttonArea, Messages.ProvidersPreferencePage_Duplicate, this::duplicateProvider);

        // Spacer
        new Composite(buttonArea, SWT.NONE).setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 1, 1));

        setActiveButton = createButton(buttonArea, Messages.ProvidersPreferencePage_SetActive, this::setActiveProvider);
    }

    private Button createButton(Composite parent, String text, Runnable action) {
        Button button = new Button(parent, SWT.PUSH);
        button.setText(text);
        button.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        button.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                action.run();
            }
        });
        return button;
    }

    private void updateButtonStates() {
        IStructuredSelection selection = tableViewer.getStructuredSelection();
        boolean hasSelection = !selection.isEmpty();
        LlmProviderConfig selected = (LlmProviderConfig) selection.getFirstElement();

        editButton.setEnabled(hasSelection);
        removeButton.setEnabled(hasSelection);
        duplicateButton.setEnabled(hasSelection);
        setActiveButton.setEnabled(hasSelection && selected != null &&
                !selected.getId().equals(activeProviderId));
        if (useAccountButton != null && !useAccountButton.isDisposed()) {
            boolean hasConfiguredBackend = BackendService.getInstance().isConfigured();
            useAccountButton.setEnabled(hasConfiguredBackend && !"backend".equals(activeProviderId)); //$NON-NLS-1$
        }
    }

    private void addProvider() {
        ProviderEditDialog dialog = new ProviderEditDialog(getShell());
        if (dialog.open() == IDialogConstants.OK_ID) {
            LlmProviderConfig config = dialog.getConfig();
            providers.add(config);
            tableViewer.refresh();

            // If this is the first provider, make it active
            if (providers.size() == 1) {
                activeProviderId = config.getId();
                tableViewer.refresh();
            }

            updateButtonStates();
        }
    }

    private void editProvider() {
        IStructuredSelection selection = tableViewer.getStructuredSelection();
        if (selection.isEmpty()) {
            return;
        }

        LlmProviderConfig config = (LlmProviderConfig) selection.getFirstElement();
        ProviderEditDialog dialog = new ProviderEditDialog(getShell(), config);
        if (dialog.open() == IDialogConstants.OK_ID) {
            // Update the config in the list
            LlmProviderConfig updated = dialog.getConfig();
            int index = providers.indexOf(config);
            if (index >= 0) {
                providers.set(index, updated);
            }
            tableViewer.refresh();
        }
    }

    private void removeProvider() {
        IStructuredSelection selection = tableViewer.getStructuredSelection();
        if (selection.isEmpty()) {
            return;
        }

        LlmProviderConfig config = (LlmProviderConfig) selection.getFirstElement();

        boolean confirmed = MessageDialog.openConfirm(getShell(),
                Messages.ProvidersPreferencePage_RemoveConfirmTitle,
                String.format(Messages.ProvidersPreferencePage_RemoveConfirmMessage, config.getName()));

        if (confirmed) {
            providers.remove(config);

            // Clear active if it was the removed provider
            if (config.getId().equals(activeProviderId)) {
                activeProviderId = providers.isEmpty() ? null : providers.get(0).getId();
            }

            tableViewer.refresh();
            updateButtonStates();
        }
    }

    private void duplicateProvider() {
        IStructuredSelection selection = tableViewer.getStructuredSelection();
        if (selection.isEmpty()) {
            return;
        }

        LlmProviderConfig config = (LlmProviderConfig) selection.getFirstElement();
        LlmProviderConfig copy = config.copyWithNewId();
        providers.add(copy);
        tableViewer.refresh();
        tableViewer.setSelection(new org.eclipse.jface.viewers.StructuredSelection(copy), true);
        updateButtonStates();
    }

    private void setActiveProvider() {
        IStructuredSelection selection = tableViewer.getStructuredSelection();
        if (selection.isEmpty()) {
            return;
        }

        LlmProviderConfig config = (LlmProviderConfig) selection.getFirstElement();
        activeProviderId = config.getId();
        tableViewer.refresh();
        refreshAccountProviderSection();
        updateButtonStates();
    }

    private void setActiveAccountProvider() {
        if (!BackendService.getInstance().isConfigured()) {
            MessageDialog.openInformation(
                    getShell(),
                    Messages.ProvidersPreferencePage_AccountNotAvailableTitle,
                    Messages.ProvidersPreferencePage_AccountNotAvailableMessage);
            return;
        }
        activeProviderId = "backend"; //$NON-NLS-1$
        tableViewer.refresh();
        refreshAccountProviderSection();
        updateButtonStates();
    }

    private void refreshAccountProviderSection() {
        if (accountStatusValue == null || accountStatusValue.isDisposed()) {
            return;
        }
        ILlmProvider backend = LlmProviderRegistry.getInstance().getBackendProvider();
        boolean isConfigured = backend != null && backend.isConfigured();
        boolean isActive = "backend".equals(activeProviderId); //$NON-NLS-1$

        accountStatusValue.setText(isConfigured
                ? (isActive
                        ? Messages.ProvidersPreferencePage_AccountStatusConnectedActive
                        : Messages.ProvidersPreferencePage_AccountStatusConnected)
                : Messages.ProvidersPreferencePage_AccountStatusDisconnected);
        accountModelValue.setText(isConfigured
                ? (backend instanceof DynamicLlmProvider dynamicProvider
                        ? dynamicProvider.getConfig().getModel()
                        : backend.getDisplayName())
                : "—"); //$NON-NLS-1$
        accountDetailsValue.setText(isConfigured
                ? Messages.ProvidersPreferencePage_AccountDetailsConnected
                : Messages.ProvidersPreferencePage_AccountDetailsDisconnected);
        accountStatusValue.getParent().layout(true, true);
    }

    @Override
    protected void performDefaults() {
        // Clear all providers
        providers.clear();
        activeProviderId = null;
        tableViewer.refresh();
        refreshAccountProviderSection();
        updateButtonStates();
        super.performDefaults();
    }

    @Override
    public boolean performOk() {
        // Save providers to config store
        LlmProviderConfigStore store = LlmProviderConfigStore.getInstance();
        store.saveProviders(providers);
        if (activeProviderId == null) {
            store.setActiveProviderId(""); //$NON-NLS-1$
        }
        LlmProviderRegistry registry = LlmProviderRegistry.getInstance();
        registry.refreshDynamicProviders();
        if (activeProviderId != null) {
            registry.setActiveProvider(activeProviderId);
        }

        return super.performOk();
    }

    @Override
    protected void performApply() {
        performOk();
    }
}
