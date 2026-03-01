/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.preferences;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FileFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IntegerFieldEditor;
import org.eclipse.jface.preference.RadioGroupFieldEditor;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

import com.codepilot1c.core.settings.VibePreferenceConstants;
import com.codepilot1c.ui.internal.Messages;

/**
 * Main preference page for 1C Copilot plugin.
 *
 * <p>Provider configuration is handled separately in ProvidersPreferencePage.</p>
 */
public class VibePreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

    private static final String CORE_PLUGIN_ID = "com.codepilot1c.core"; //$NON-NLS-1$

    public VibePreferencePage() {
        super(GRID);
        setDescription(Messages.PreferencePage_Description);
    }

    @Override
    public void init(IWorkbench workbench) {
        setPreferenceStore(new ScopedPreferenceStore(InstanceScope.INSTANCE, CORE_PLUGIN_ID));
    }

    @Override
    protected void createFieldEditors() {
        // Timeout
        IntegerFieldEditor timeoutEditor = new IntegerFieldEditor(
                VibePreferenceConstants.PREF_REQUEST_TIMEOUT,
                Messages.PreferencePage_TimeoutLabel,
                getFieldEditorParent());
        timeoutEditor.setValidRange(10, 300);
        addField(timeoutEditor);

        // Streaming
        addField(new BooleanFieldEditor(
                VibePreferenceConstants.PREF_STREAMING_ENABLED,
                Messages.PreferencePage_StreamingLabel,
                getFieldEditorParent()));

        // Max tool iterations (agent loop limit)
        IntegerFieldEditor maxIterationsEditor = new IntegerFieldEditor(
                VibePreferenceConstants.PREF_MAX_TOOL_ITERATIONS,
                Messages.PreferencePage_MaxIterationsLabel,
                getFieldEditorParent());
        maxIterationsEditor.setValidRange(10, 500);
        addField(maxIterationsEditor);

        // Allow dangerous mode: skip confirmation dialogs for tool execution
        addField(new BooleanFieldEditor(
                VibePreferenceConstants.PREF_AGENT_SKIP_TOOL_CONFIRMATIONS,
                Messages.PreferencePage_SkipToolConfirmationsLabel,
                getFieldEditorParent()));

        addField(new BooleanFieldEditor(
                VibePreferenceConstants.PREF_CHAT_AUTO_COMPACT_ENABLED,
                Messages.PreferencePage_AutoCompactEnabledLabel,
                getFieldEditorParent()));

        IntegerFieldEditor autoCompactThresholdEditor = new IntegerFieldEditor(
                VibePreferenceConstants.PREF_CHAT_AUTO_COMPACT_THRESHOLD_PERCENT,
                Messages.PreferencePage_AutoCompactThresholdLabel,
                getFieldEditorParent());
        autoCompactThresholdEditor.setValidRange(50, 95);
        addField(autoCompactThresholdEditor);

        addField(new BooleanFieldEditor(
                VibePreferenceConstants.PREF_CHAT_SHOW_TOKEN_USAGE,
                Messages.PreferencePage_ShowTokenUsageLabel,
                getFieldEditorParent()));

        FileFieldEditor vaEpfEditor = new FileFieldEditor(
                VibePreferenceConstants.PREF_QA_VA_EPF_PATH,
                Messages.PreferencePage_VaEpfPathLabel,
                true,
                getFieldEditorParent());
        vaEpfEditor.setFileExtensions(new String[] { "*.epf" }); //$NON-NLS-1$
        vaEpfEditor.setEmptyStringAllowed(true);
        addField(vaEpfEditor);

        addField(new RadioGroupFieldEditor(
                VibePreferenceConstants.PREF_TERMINAL_CWD_MODE,
                Messages.PreferencePage_TerminalCwdModeLabel,
                2,
                new String[][] {
                        { Messages.PreferencePage_TerminalCwdModeProject, "project" }, //$NON-NLS-1$
                        { Messages.PreferencePage_TerminalCwdModeSelection, "selection" }, //$NON-NLS-1$
                        { Messages.PreferencePage_TerminalCwdModeWorkspace, "workspace" }, //$NON-NLS-1$
                        { Messages.PreferencePage_TerminalCwdModeHome, "home" } //$NON-NLS-1$
                },
                getFieldEditorParent(),
                true));

        addField(new BooleanFieldEditor(
                VibePreferenceConstants.PREF_TERMINAL_ALWAYS_USE_ACTIVE_PROJECT,
                Messages.PreferencePage_TerminalAlwaysUseActiveProjectLabel,
                getFieldEditorParent()));

        addField(new BooleanFieldEditor(
                VibePreferenceConstants.PREF_TERMINAL_NO_COLOR,
                Messages.PreferencePage_TerminalNoColorLabel,
                getFieldEditorParent()));

        StringFieldEditor terminalTitlePrefix = new StringFieldEditor(
                VibePreferenceConstants.PREF_TERMINAL_TITLE_PREFIX,
                Messages.PreferencePage_TerminalTitlePrefixLabel,
                getFieldEditorParent());
        terminalTitlePrefix.setEmptyStringAllowed(true);
        addField(terminalTitlePrefix);
    }
}
