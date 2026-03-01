/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.internal;

import org.eclipse.osgi.util.NLS;

/**
 * Message bundle for UI strings.
 */
public class Messages extends NLS {

    private static final String BUNDLE_NAME = "com.codepilot1c.ui.internal.messages"; //$NON-NLS-1$

    public static String ChatView_Title;
    public static String ChatView_InputPlaceholder;
    public static String ChatView_SendButton;
    public static String ChatView_ClearButton;
    public static String ChatView_StopButton;
    public static String ChatView_ApplyCodeButton;
    public static String ChatView_ApplyCodeTooltip;
    public static String ChatView_ApplyCodeTitle;
    public static String ChatView_ApplyCodeMessage;
    public static String ChatView_ReplaceSelection;
    public static String ChatView_InsertAtCursor;
    public static String ChatView_Cancel;
    public static String ChatView_CodeAppliedSuccess;
    public static String ChatView_CodeAppliedFailed;
    public static String ChatView_WelcomeMessage;
    public static String ChatView_ErrorMessage;
    public static String ChatView_NotConfiguredMessage;
    public static String ChatView_CompactContextButton;
    public static String ChatView_CompactContextTooltip;
    public static String ChatView_ContextCompactedNotice;
    public static String ChatView_ContextCompactedSkippedNotice;
    public static String ChatView_AutoCompactLabel;
    public static String ChatView_ManualCompactLabel;

    public static String PreferencePage_Description;
    public static String PreferencePage_ProviderLabel;
    public static String PreferencePage_ApiKeyLabel;
    public static String PreferencePage_ApiUrlLabel;
    public static String PreferencePage_ModelLabel;
    public static String PreferencePage_MaxTokensLabel;
    public static String PreferencePage_TimeoutLabel;
    public static String PreferencePage_StreamingLabel;
    public static String PreferencePage_MaxIterationsLabel;
    public static String PreferencePage_SkipToolConfirmationsLabel;
    public static String PreferencePage_AutoCompactEnabledLabel;
    public static String PreferencePage_AutoCompactThresholdLabel;
    public static String PreferencePage_ShowTokenUsageLabel;
    public static String PreferencePage_VaEpfPathLabel;
    public static String PreferencePage_TerminalCwdModeLabel;
    public static String PreferencePage_TerminalCwdModeProject;
    public static String PreferencePage_TerminalCwdModeSelection;
    public static String PreferencePage_TerminalCwdModeWorkspace;
    public static String PreferencePage_TerminalCwdModeHome;
    public static String PreferencePage_TerminalNoColorLabel;
    public static String PreferencePage_TerminalTitlePrefixLabel;
    public static String PreferencePage_TestConnectionButton;
    public static String PreferencePage_TestSuccess;
    public static String PreferencePage_TestFailed;


    // Model configuration editor
    public static String ModelConfigurationEditor_ActiveModelLabel;
    public static String ModelConfigurationEditor_CustomModelsLabel;

    // Model list field editor
    public static String ModelListFieldEditor_AddButton;
    public static String ModelListFieldEditor_EditButton;
    public static String ModelListFieldEditor_RemoveButton;
    public static String ModelListFieldEditor_UpButton;
    public static String ModelListFieldEditor_DownButton;
    public static String ModelListFieldEditor_AddDialogTitle;
    public static String ModelListFieldEditor_AddDialogMessage;
    public static String ModelListFieldEditor_EditDialogTitle;
    public static String ModelListFieldEditor_EditDialogMessage;
    public static String ModelListFieldEditor_ValidationEmpty;
    public static String ModelListFieldEditor_ValidationSemicolon;

    // Providers preference page (new universal system)
    public static String ProvidersPreferencePage_Description;
    public static String ProvidersPreferencePage_ColumnActive;
    public static String ProvidersPreferencePage_ColumnName;
    public static String ProvidersPreferencePage_ColumnType;
    public static String ProvidersPreferencePage_ColumnModel;
    public static String ProvidersPreferencePage_ColumnStatus;
    public static String ProvidersPreferencePage_StatusConfigured;
    public static String ProvidersPreferencePage_StatusNotConfigured;
    public static String ProvidersPreferencePage_Add;
    public static String ProvidersPreferencePage_Edit;
    public static String ProvidersPreferencePage_Remove;
    public static String ProvidersPreferencePage_Duplicate;
    public static String ProvidersPreferencePage_SetActive;
    public static String ProvidersPreferencePage_RemoveConfirmTitle;
    public static String ProvidersPreferencePage_RemoveConfirmMessage;

    // Provider edit dialog
    public static String ProviderEditDialog_TitleAdd;
    public static String ProviderEditDialog_TitleEdit;
    public static String ProviderEditDialog_Description;
    public static String ProviderEditDialog_Name;
    public static String ProviderEditDialog_Type;
    public static String ProviderEditDialog_BaseUrl;
    public static String ProviderEditDialog_ApiKey;
    public static String ProviderEditDialog_Model;
    public static String ProviderEditDialog_MaxTokens;
    public static String ProviderEditDialog_EnableStreaming;
    public static String ProviderEditDialog_FetchModels;
    public static String ProviderEditDialog_Fetching;
    public static String ProviderEditDialog_FetchTooltip;
    public static String ProviderEditDialog_FetchNotSupported;
    public static String ProviderEditDialog_NoModelsFound;
    public static String ProviderEditDialog_NameRequired;
    public static String ProviderEditDialog_BaseUrlRequired;
    public static String ProviderEditDialog_ModelRequired;
    public static String ProviderEditDialog_ApiKeyRequired;

    // Model selection dialog
    public static String ModelSelectionDialog_Title;
    public static String ModelSelectionDialog_Filter;
    public static String ModelSelectionDialog_FilterPlaceholder;
    public static String ModelSelectionDialog_ModelCount;


    // Code block widget
    public static String CodeBlockWidget_Copy;
    public static String CodeBlockWidget_CopyTooltip;
    public static String CodeBlockWidget_Insert;
    public static String CodeBlockWidget_InsertTooltip;
    public static String CodeBlockWidget_Replace;
    public static String CodeBlockWidget_ReplaceTooltip;

    // MCP servers preference page
    public static String McpServersPreferencePage_Description;
    public static String McpServersPreferencePage_ColumnEnabled;
    public static String McpServersPreferencePage_ColumnName;
    public static String McpServersPreferencePage_ColumnCommand;
    public static String McpServersPreferencePage_ColumnStatus;
    public static String McpServersPreferencePage_Add;
    public static String McpServersPreferencePage_Edit;
    public static String McpServersPreferencePage_Remove;
    public static String McpServersPreferencePage_Start;
    public static String McpServersPreferencePage_Stop;
    public static String McpServersPreferencePage_StatusRunning;
    public static String McpServersPreferencePage_StatusStarting;
    public static String McpServersPreferencePage_StatusStopped;
    public static String McpServersPreferencePage_StatusError;

    // MCP server edit dialog
    public static String McpServerEditDialog_TitleAdd;
    public static String McpServerEditDialog_TitleEdit;
    public static String McpServerEditDialog_Description;
    public static String McpServerEditDialog_Name;
    public static String McpServerEditDialog_Enabled;
    public static String McpServerEditDialog_TransportType;
    public static String McpServerEditDialog_AuthMode;
    public static String McpServerEditDialog_Command;
    public static String McpServerEditDialog_Arguments;
    public static String McpServerEditDialog_WorkingDirectory;
    public static String McpServerEditDialog_RemoteUrl;
    public static String McpServerEditDialog_RemoteSseUrl;
    public static String McpServerEditDialog_AllowLegacyFallback;
    public static String McpServerEditDialog_PreferredProtocol;
    public static String McpServerEditDialog_SupportedProtocols;
    public static String McpServerEditDialog_StaticHeaders;
    public static String McpServerEditDialog_OAuthProfileId;
    public static String McpServerEditDialog_Browse;
    public static String McpServerEditDialog_SelectDirectory;
    public static String McpServerEditDialog_Environment;
    public static String McpServerEditDialog_EnvKey;
    public static String McpServerEditDialog_EnvValue;
    public static String McpServerEditDialog_EnvKeyPrompt;
    public static String McpServerEditDialog_EnvValuePrompt;
    public static String McpServerEditDialog_AddEnv;
    public static String McpServerEditDialog_RemoveEnv;
    public static String McpServerEditDialog_ConnectionTimeout;
    public static String McpServerEditDialog_RequestTimeout;
    public static String McpServerEditDialog_NameRequired;
    public static String McpServerEditDialog_CommandRequired;
    public static String McpServerEditDialog_RemoteUrlRequired;

    // MCP host preference page
    public static String McpHostPreferencePage_Description;
    public static String McpHostPreferencePage_Enabled;
    public static String McpHostPreferencePage_HttpEnabled;
    public static String McpHostPreferencePage_Status;
    public static String McpHostPreferencePage_CheckNow;
    public static String McpHostPreferencePage_StatusRunning;
    public static String McpHostPreferencePage_StatusRunningNoHttp;
    public static String McpHostPreferencePage_StatusStopped;
    public static String McpHostPreferencePage_StatusHttpOk;
    public static String McpHostPreferencePage_StatusHttpFail;
    public static String McpHostPreferencePage_BindAddress;
    public static String McpHostPreferencePage_Port;
    public static String McpHostPreferencePage_AuthMode;
    public static String McpHostPreferencePage_AuthModeOauthBearer;
    public static String McpHostPreferencePage_AuthModeOauth;
    public static String McpHostPreferencePage_AuthModeBearer;
    public static String McpHostPreferencePage_AuthModeNone;
    public static String McpHostPreferencePage_BearerToken;
    public static String McpHostPreferencePage_RotateToken;
    public static String McpHostPreferencePage_MutationPolicy;
    public static String McpHostPreferencePage_MutationAsk;
    public static String McpHostPreferencePage_MutationDeny;
    public static String McpHostPreferencePage_MutationAllow;
    public static String McpHostPreferencePage_ExposedTools;
    public static String McpHostPreferencePage_LocalOnlyInfo;
    public static String McpHostPreferencePage_NonLocalWarning;
    public static String McpHostPreferencePage_InstallHints;
    public static String McpHostPreferencePage_InstallHintsTemplate;

    static {
        NLS.initializeMessages(BUNDLE_NAME, Messages.class);
    }

    private Messages() {
    }
}
