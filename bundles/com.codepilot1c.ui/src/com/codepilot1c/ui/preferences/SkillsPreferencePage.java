/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.preferences;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.CheckboxTableViewer;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITableLabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;
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

import com.codepilot1c.core.skills.SkillCatalog;
import com.codepilot1c.core.skills.SkillConfigStore;
import com.codepilot1c.core.skills.SkillDefinition;
import com.codepilot1c.core.skills.SkillFileService;
import com.codepilot1c.ui.internal.Messages;
import com.codepilot1c.ui.internal.SkillDisplayInfo;

/**
 * Preference page for managing skills.
 */
public class SkillsPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    private CheckboxTableViewer tableViewer;
    private List<SkillDefinition> skills;
    private Set<String> disabledSkills;
    private Button addButton;
    private Button editButton;
    private Button removeButton;
    private Button refreshButton;
    private Label detailLabel;

    @Override
    public void init(IWorkbench workbench) {
        refreshSkillsList();
    }

    private void refreshSkillsList() {
        SkillCatalog catalog = new SkillCatalog();
        skills = new ArrayList<>(catalog.discoverSkills());
        disabledSkills = new HashSet<>(SkillConfigStore.getInstance().getDisabledSkills());
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite container = new Composite(parent, SWT.NONE);
        container.setLayout(new GridLayout(2, false));

        // Description
        Label description = new Label(container, SWT.WRAP);
        description.setText(Messages.SkillsPreferencePage_Description);
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
        tableViewer = CheckboxTableViewer.newCheckList(parent,
                SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE);
        Table table = tableViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);

        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.heightHint = 200;
        table.setLayoutData(gd);

        createColumn(table, Messages.SkillsPreferencePage_ColumnName, 150);
        createColumn(table, Messages.SkillsPreferencePage_ColumnSource, 100);
        createColumn(table, Messages.SkillsPreferencePage_ColumnTools, 60);

        tableViewer.setContentProvider(ArrayContentProvider.getInstance());
        tableViewer.setLabelProvider(new SkillLabelProvider());
        tableViewer.setInput(skills);

        // Set checked state based on disabled set
        for (SkillDefinition skill : skills) {
            tableViewer.setChecked(skill, !disabledSkills.contains(
                    skill.name().trim().toLowerCase(java.util.Locale.ROOT)));
        }

        tableViewer.addCheckStateListener(event -> {
            SkillDefinition skill = (SkillDefinition) event.getElement();
            String normalized = skill.name().trim().toLowerCase(java.util.Locale.ROOT);
            if (event.getChecked()) {
                disabledSkills.remove(normalized);
            } else {
                disabledSkills.add(normalized);
            }
        });

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

        addButton = createButton(buttonComposite, Messages.SkillsPreferencePage_Add);
        addButton.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> addSkill()));

        editButton = createButton(buttonComposite, Messages.SkillsPreferencePage_Edit);
        editButton.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> editSkill()));
        editButton.setEnabled(false);

        removeButton = createButton(buttonComposite, Messages.SkillsPreferencePage_Remove);
        removeButton.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> removeSkill()));
        removeButton.setEnabled(false);

        refreshButton = createButton(buttonComposite, Messages.SkillsPreferencePage_Refresh);
        refreshButton.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> {
            refreshSkillsList();
            tableViewer.setInput(skills);
            for (SkillDefinition skill : skills) {
                tableViewer.setChecked(skill, !disabledSkills.contains(
                        skill.name().trim().toLowerCase(java.util.Locale.ROOT)));
            }
        }));
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
        SkillDefinition selected = getSelectedSkill();
        boolean hasSelection = selected != null;
        boolean isUserSkill = hasSelection && selected.sourceType() == SkillDefinition.SourceType.USER;

        editButton.setEnabled(hasSelection);
        removeButton.setEnabled(isUserSkill);
    }

    private void updateDetailPanel() {
        SkillDefinition selected = getSelectedSkill();
        if (selected == null) {
            detailLabel.setText(""); //$NON-NLS-1$
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(SkillDisplayInfo.getSkillIcon(selected.name()));
        sb.append(' ').append(selected.name()).append(": ").append(selected.description()); //$NON-NLS-1$
        if (!selected.allowedTools().isEmpty()) {
            sb.append("\n").append(String.join(", ", selected.allowedTools())); //$NON-NLS-1$ //$NON-NLS-2$
        }
        detailLabel.setText(sb.toString());
        detailLabel.getParent().layout(true);
    }

    private SkillDefinition getSelectedSkill() {
        IStructuredSelection sel = tableViewer.getStructuredSelection();
        return sel.isEmpty() ? null : (SkillDefinition) sel.getFirstElement();
    }

    private void addSkill() {
        SkillEditDialog dialog = new SkillEditDialog(getShell(), null, skills);
        if (dialog.open() == Window.OK) {
            try {
                SkillFileService.getInstance().createUserSkill(
                        dialog.getSkillName(),
                        dialog.getSkillDescription(),
                        dialog.getAllowedTools(),
                        dialog.isBackendOnly(),
                        dialog.getBody());
                refreshSkillsList();
                tableViewer.setInput(skills);
                for (SkillDefinition skill : skills) {
                    tableViewer.setChecked(skill, !disabledSkills.contains(
                            skill.name().trim().toLowerCase(java.util.Locale.ROOT)));
                }
            } catch (Exception e) {
                MessageDialog.openError(getShell(), Messages.SkillEditDialog_TitleAdd, e.getMessage());
            }
        }
    }

    private void editSkill() {
        SkillDefinition selected = getSelectedSkill();
        if (selected == null) {
            return;
        }
        SkillEditDialog dialog = new SkillEditDialog(getShell(), selected, skills);
        if (dialog.open() == Window.OK
                && selected.sourceType() == SkillDefinition.SourceType.USER) {
            try {
                SkillFileService.getInstance().updateSkill(
                        Path.of(selected.sourcePath()),
                        dialog.getSkillDescription(),
                        dialog.getAllowedTools(),
                        dialog.isBackendOnly(),
                        dialog.getBody());
                refreshSkillsList();
                tableViewer.setInput(skills);
                for (SkillDefinition skill : skills) {
                    tableViewer.setChecked(skill, !disabledSkills.contains(
                            skill.name().trim().toLowerCase(java.util.Locale.ROOT)));
                }
            } catch (Exception e) {
                MessageDialog.openError(getShell(), Messages.SkillEditDialog_TitleEdit, e.getMessage());
            }
        }
    }

    private void removeSkill() {
        SkillDefinition selected = getSelectedSkill();
        if (selected == null || selected.sourceType() != SkillDefinition.SourceType.USER) {
            return;
        }
        String msg = java.text.MessageFormat.format(
                Messages.SkillsPreferencePage_RemoveConfirmMessage, selected.name());
        if (MessageDialog.openConfirm(getShell(),
                Messages.SkillsPreferencePage_RemoveConfirmTitle, msg)) {
            SkillFileService.getInstance().deleteSkill(Path.of(selected.sourcePath()));
            refreshSkillsList();
            tableViewer.setInput(skills);
            for (SkillDefinition skill : skills) {
                tableViewer.setChecked(skill, !disabledSkills.contains(
                        skill.name().trim().toLowerCase(java.util.Locale.ROOT)));
            }
        }
    }

    @Override
    public boolean performOk() {
        SkillConfigStore.getInstance().setDisabledSkills(disabledSkills);
        return super.performOk();
    }

    @Override
    protected void performDefaults() {
        disabledSkills.clear();
        for (SkillDefinition skill : skills) {
            tableViewer.setChecked(skill, true);
        }
        super.performDefaults();
    }

    // ---- label provider ----

    private static class SkillLabelProvider extends LabelProvider implements ITableLabelProvider {

        @Override
        public Image getColumnImage(Object element, int columnIndex) {
            return null;
        }

        @Override
        public String getColumnText(Object element, int columnIndex) {
            SkillDefinition skill = (SkillDefinition) element;
            switch (columnIndex) {
                case 0:
                    return SkillDisplayInfo.getSkillIcon(skill.name()) + " " + skill.name(); //$NON-NLS-1$
                case 1:
                    return formatSource(skill.sourceType());
                case 2:
                    return String.valueOf(skill.allowedTools().size());
                default:
                    return ""; //$NON-NLS-1$
            }
        }

        private String formatSource(SkillDefinition.SourceType type) {
            switch (type) {
                case BUNDLED:
                    return Messages.SkillsPreferencePage_SourceBundled;
                case USER:
                    return Messages.SkillsPreferencePage_SourceUser;
                case PROJECT:
                    return Messages.SkillsPreferencePage_SourceProject;
                default:
                    return type.name();
            }
        }
    }
}
