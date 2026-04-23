/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.preferences;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.CheckboxTableViewer;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;

import com.codepilot1c.core.agent.profiles.AgentProfile;
import com.codepilot1c.core.agent.profiles.ProfileOverride;
import com.codepilot1c.ui.internal.Messages;

/**
 * Dialog for customizing agent profile parameters.
 */
public class ProfileEditDialog extends TitleAreaDialog {

    private final AgentProfile profile;
    private final ProfileOverride currentOverride;

    private Spinner maxStepsSpinner;
    private Spinner timeoutSpinner;
    private CheckboxTableViewer toolsViewer;
    private Text additionalPromptText;

    private ProfileOverride result;

    /**
     * Creates the dialog.
     *
     * @param shell           parent shell
     * @param profile         the profile to configure
     * @param currentOverride existing override (may be null)
     */
    public ProfileEditDialog(Shell shell, AgentProfile profile, ProfileOverride currentOverride) {
        super(shell);
        this.profile = profile;
        this.currentOverride = currentOverride;
    }

    @Override
    protected void configureShell(Shell newShell) {
        super.configureShell(newShell);
        newShell.setText(MessageFormat.format(Messages.ProfileEditDialog_Title, profile.getName()));
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        setTitle(MessageFormat.format(Messages.ProfileEditDialog_Title, profile.getName()));

        Composite container = new Composite(area, SWT.NONE);
        container.setLayout(new GridLayout(2, false));
        container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        // Max steps
        new Label(container, SWT.NONE).setText(Messages.ProfileEditDialog_MaxSteps);
        maxStepsSpinner = new Spinner(container, SWT.BORDER);
        maxStepsSpinner.setMinimum(1);
        maxStepsSpinner.setMaximum(100);
        int currentSteps = (currentOverride != null && currentOverride.maxSteps() != null)
                ? currentOverride.maxSteps() : profile.getMaxSteps();
        maxStepsSpinner.setSelection(currentSteps);

        // Timeout
        new Label(container, SWT.NONE).setText(Messages.ProfileEditDialog_Timeout);
        timeoutSpinner = new Spinner(container, SWT.BORDER);
        timeoutSpinner.setMinimum(10);
        timeoutSpinner.setMaximum(600);
        long currentTimeoutMs = (currentOverride != null && currentOverride.timeoutMs() != null)
                ? currentOverride.timeoutMs() : profile.getTimeoutMs();
        timeoutSpinner.setSelection((int) (currentTimeoutMs / 1000));

        // Tools checkbox list
        Label toolsLabel = new Label(container, SWT.NONE);
        toolsLabel.setText(Messages.ProfileEditDialog_DisabledTools);
        toolsLabel.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false, 2, 1));

        toolsViewer = CheckboxTableViewer.newCheckList(container, SWT.BORDER | SWT.V_SCROLL);
        GridData toolsGd = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
        toolsGd.heightHint = 150;
        toolsViewer.getTable().setLayoutData(toolsGd);
        toolsViewer.setContentProvider(ArrayContentProvider.getInstance());
        toolsViewer.setLabelProvider(new LabelProvider());

        List<String> profileTools = new ArrayList<>(profile.getAllowedTools());
        profileTools.sort(String::compareTo);
        toolsViewer.setInput(profileTools);

        // Set checked = tool is enabled (not in disabled set)
        Set<String> disabled = (currentOverride != null && currentOverride.disabledTools() != null)
                ? currentOverride.disabledTools() : Set.of();
        for (String tool : profileTools) {
            toolsViewer.setChecked(tool, !disabled.contains(tool));
        }

        // Additional prompt
        Label promptLabel = new Label(container, SWT.NONE);
        promptLabel.setText(Messages.ProfileEditDialog_AdditionalPrompt);
        promptLabel.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false, 2, 1));

        additionalPromptText = new Text(container, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.WRAP);
        GridData promptGd = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
        promptGd.heightHint = 80;
        additionalPromptText.setLayoutData(promptGd);
        if (currentOverride != null && currentOverride.additionalPrompt() != null) {
            additionalPromptText.setText(currentOverride.additionalPrompt());
        }

        // Reset button
        Button resetBtn = new Button(container, SWT.PUSH);
        resetBtn.setText(Messages.ProfileEditDialog_ResetButton);
        resetBtn.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false, 2, 1));
        resetBtn.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> resetToDefaults()));

        return area;
    }

    private void resetToDefaults() {
        maxStepsSpinner.setSelection(profile.getMaxSteps());
        timeoutSpinner.setSelection((int) (profile.getTimeoutMs() / 1000));
        additionalPromptText.setText(""); //$NON-NLS-1$

        // Check all tools (no disabled)
        List<String> profileTools = new ArrayList<>(profile.getAllowedTools());
        for (String tool : profileTools) {
            toolsViewer.setChecked(tool, true);
        }
    }

    @Override
    protected void okPressed() {
        // Collect disabled tools (unchecked items)
        Set<String> disabledTools = new HashSet<>();
        List<String> profileTools = new ArrayList<>(profile.getAllowedTools());
        for (String tool : profileTools) {
            if (!toolsViewer.getChecked(tool)) {
                disabledTools.add(tool);
            }
        }

        // Build override — only set fields that differ from defaults
        Integer maxSteps = null;
        if (maxStepsSpinner.getSelection() != profile.getMaxSteps()) {
            maxSteps = maxStepsSpinner.getSelection();
        }

        Long timeoutMs = null;
        long selectedTimeoutMs = timeoutSpinner.getSelection() * 1000L;
        if (selectedTimeoutMs != profile.getTimeoutMs()) {
            timeoutMs = selectedTimeoutMs;
        }

        String additionalPrompt = additionalPromptText.getText().trim();
        if (additionalPrompt.isEmpty()) {
            additionalPrompt = null;
        }

        result = new ProfileOverride(
                maxSteps,
                timeoutMs,
                disabledTools.isEmpty() ? null : disabledTools,
                additionalPrompt);

        super.okPressed();
    }

    /**
     * Returns the configured override, or null if dialog was cancelled.
     */
    public ProfileOverride getResult() {
        return result;
    }
}
