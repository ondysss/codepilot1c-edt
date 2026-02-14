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
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.IntegerFieldEditor;
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
    }
}
