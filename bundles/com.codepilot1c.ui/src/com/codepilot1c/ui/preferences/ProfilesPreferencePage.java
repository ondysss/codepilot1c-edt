/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.preferences;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITableLabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.codepilot1c.core.agent.profiles.AgentProfile;
import com.codepilot1c.core.agent.profiles.AgentProfileRegistry;
import com.codepilot1c.core.agent.profiles.ProfileConfigStore;
import com.codepilot1c.core.agent.profiles.ProfileOverride;
import com.codepilot1c.ui.internal.Messages;
import com.codepilot1c.ui.internal.SkillDisplayInfo;

/**
 * Preference page for viewing and customizing agent profiles.
 */
public class ProfilesPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    private TableViewer tableViewer;
    private List<AgentProfile> profiles;
    private Button configureButton;
    private Button resetButton;
    private Label detailLabel;

    @Override
    public void init(IWorkbench workbench) {
        Collection<AgentProfile> all = AgentProfileRegistry.getInstance().getAllProfiles();
        profiles = new ArrayList<>(all);
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite container = new Composite(parent, SWT.NONE);
        container.setLayout(new GridLayout(2, false));

        // Description
        Label description = new Label(container, SWT.WRAP);
        description.setText(Messages.ProfilesPreferencePage_Description);
        GridData gd = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
        gd.widthHint = 400;
        description.setLayoutData(gd);

        // Table
        createTable(container);

        // Buttons
        createButtons(container);

        // Detail panel
        detailLabel = new Label(container, SWT.WRAP);
        detailLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));

        return container;
    }

    private void createTable(Composite parent) {
        tableViewer = new TableViewer(parent,
                SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE);
        Table table = tableViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);

        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.heightHint = 250;
        table.setLayoutData(gd);

        createColumn(table, "", 30); //$NON-NLS-1$
        createColumn(table, Messages.ProfilesPreferencePage_ColumnProfile, 120);
        createColumn(table, Messages.ProfilesPreferencePage_ColumnTools, 60);
        createColumn(table, Messages.ProfilesPreferencePage_ColumnMaxSteps, 60);
        createColumn(table, Messages.ProfilesPreferencePage_ColumnTimeout, 70);

        tableViewer.setContentProvider(ArrayContentProvider.getInstance());
        tableViewer.setLabelProvider(new ProfileLabelProvider());
        tableViewer.setInput(profiles);

        tableViewer.addDoubleClickListener(e -> configureProfile());
        tableViewer.addSelectionChangedListener(new ISelectionChangedListener() {
            @Override
            public void selectionChanged(SelectionChangedEvent event) {
                updateButtonStates();
                updateDetailPanel();
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
        Composite buttonComposite = new Composite(parent, SWT.NONE);
        buttonComposite.setLayout(new GridLayout());
        buttonComposite.setLayoutData(new GridData(SWT.CENTER, SWT.TOP, false, false));

        configureButton = createButton(buttonComposite, Messages.ProfilesPreferencePage_Configure);
        configureButton.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> configureProfile()));
        configureButton.setEnabled(false);

        resetButton = createButton(buttonComposite, Messages.ProfilesPreferencePage_Reset);
        resetButton.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> resetProfile()));
        resetButton.setEnabled(false);
    }

    private Button createButton(Composite parent, String text) {
        Button button = new Button(parent, SWT.PUSH);
        button.setText(text);
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.widthHint = 100;
        button.setLayoutData(gd);
        return button;
    }

    private void updateButtonStates() {
        AgentProfile selected = getSelectedProfile();
        boolean hasSelection = selected != null;
        configureButton.setEnabled(hasSelection);

        boolean hasOverride = hasSelection
                && ProfileConfigStore.getInstance().getOverride(selected.getId()).isPresent();
        resetButton.setEnabled(hasOverride);
    }

    private void updateDetailPanel() {
        AgentProfile selected = getSelectedProfile();
        if (selected == null) {
            detailLabel.setText(""); //$NON-NLS-1$
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(SkillDisplayInfo.getProfileIcon(selected.getId()));
        sb.append(' ').append(SkillDisplayInfo.getProfileLabel(selected.getId()));
        sb.append(": ").append(selected.getDescription()); //$NON-NLS-1$

        ProfileConfigStore.getInstance().getOverride(selected.getId())
                .ifPresent(o -> sb.append("\n").append(Messages.ProfilesPreferencePage_Customized)); //$NON-NLS-1$

        detailLabel.setText(sb.toString());
        detailLabel.getParent().layout(true);
    }

    private AgentProfile getSelectedProfile() {
        IStructuredSelection sel = tableViewer.getStructuredSelection();
        return sel.isEmpty() ? null : (AgentProfile) sel.getFirstElement();
    }

    private void configureProfile() {
        AgentProfile selected = getSelectedProfile();
        if (selected == null) {
            return;
        }
        ProfileOverride currentOverride = ProfileConfigStore.getInstance()
                .getOverride(selected.getId()).orElse(null);

        ProfileEditDialog dialog = new ProfileEditDialog(getShell(), selected, currentOverride);
        if (dialog.open() == Window.OK) {
            ProfileOverride newOverride = dialog.getResult();
            if (newOverride != null && !newOverride.isEmpty()) {
                ProfileConfigStore.getInstance().setOverride(selected.getId(), newOverride);
            } else {
                ProfileConfigStore.getInstance().removeOverride(selected.getId());
            }
            tableViewer.refresh();
            updateButtonStates();
            updateDetailPanel();
        }
    }

    private void resetProfile() {
        AgentProfile selected = getSelectedProfile();
        if (selected == null) {
            return;
        }
        String msg = java.text.MessageFormat.format(
                Messages.ProfilesPreferencePage_ResetConfirmMessage, selected.getName());
        if (MessageDialog.openConfirm(getShell(),
                Messages.ProfilesPreferencePage_ResetConfirmTitle, msg)) {
            ProfileConfigStore.getInstance().removeOverride(selected.getId());
            tableViewer.refresh();
            updateButtonStates();
            updateDetailPanel();
        }
    }

    // ---- label provider ----

    private static class ProfileLabelProvider extends LabelProvider implements ITableLabelProvider {

        @Override
        public Image getColumnImage(Object element, int columnIndex) {
            return null;
        }

        @Override
        public String getColumnText(Object element, int columnIndex) {
            AgentProfile profile = (AgentProfile) element;
            ProfileOverride override = ProfileConfigStore.getInstance()
                    .getOverride(profile.getId()).orElse(null);

            switch (columnIndex) {
                case 0:
                    return SkillDisplayInfo.getProfileIcon(profile.getId());
                case 1: {
                    String label = SkillDisplayInfo.getProfileLabel(profile.getId());
                    boolean customized = override != null;
                    return customized ? label + " *" : label; //$NON-NLS-1$
                }
                case 2: {
                    int toolCount = profile.getAllowedTools().size();
                    if (override != null && override.disabledTools() != null) {
                        toolCount -= override.disabledTools().size();
                        if (toolCount < 0) {
                            toolCount = 0;
                        }
                    }
                    return String.valueOf(toolCount);
                }
                case 3: {
                    int steps = (override != null && override.maxSteps() != null)
                            ? override.maxSteps() : profile.getMaxSteps();
                    return String.valueOf(steps);
                }
                case 4: {
                    long timeout = (override != null && override.timeoutMs() != null)
                            ? override.timeoutMs() : profile.getTimeoutMs();
                    return (timeout / 1000) + "s"; //$NON-NLS-1$
                }
                default:
                    return ""; //$NON-NLS-1$
            }
        }
    }
}
