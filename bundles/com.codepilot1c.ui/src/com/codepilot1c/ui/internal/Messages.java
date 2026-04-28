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
    public static String ChatView_TokenFooter_Session;
    public static String ChatView_TokenFooter_Requests;
    public static String ChatView_TokenFooter_Cache;
    public static String ChatView_AttachButton;
    public static String ChatView_AttachDialogTitle;
    public static String ChatView_AttachmentTooLarge;
    public static String ChatView_AttachmentLimitExceeded;
    public static String ChatView_ImageAttachmentsUnsupported;
    public static String ChatView_ModelButton;
    public static String ChatView_ModelButtonTooltip;
    public static String ChatView_ModelFetching;
    public static String ChatView_ModelFetchError;
    public static String ChatView_ModelNoModels;
    public static String ChatView_NewChatButton;
    public static String ChatView_NewChatTooltip;
    public static String ChatView_NewChatConfirmTitle;
    public static String ChatView_NewChatConfirmMessage;
    public static String ChatView_ModelSwitchTitle;
    public static String ChatView_ModelSwitchMessage;
    public static String ChatView_ModelSwitchNewChat;
    public static String ChatView_ModelSwitchContinue;

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
    public static String PreferencePage_TerminalAlwaysUseActiveProjectLabel;
    public static String PreferencePage_TerminalNoColorLabel;
    public static String PreferencePage_TerminalTitlePrefixLabel;
    public static String PreferencePage_TestConnectionButton;
    public static String PreferencePage_TestSuccess;
    public static String PreferencePage_TestFailed;

    public static String AccountPreferencePage_Description;
    public static String AccountPreferencePage_ConnectionGroup;
    public static String AccountPreferencePage_StatusLabel;
    public static String AccountPreferencePage_ServerLabel;
    public static String AccountPreferencePage_UserLabel;
    public static String AccountPreferencePage_RotateKeyButton;
    public static String AccountPreferencePage_LogoutButton;
    public static String AccountPreferencePage_UsageGroup;
    public static String AccountPreferencePage_BudgetLabel;
    public static String AccountPreferencePage_SpentLabel;
    public static String AccountPreferencePage_RemainingLabel;
    public static String AccountPreferencePage_PromptTokensLabel;
    public static String AccountPreferencePage_CompletionTokensLabel;
    public static String AccountPreferencePage_TotalTokensLabel;
    public static String AccountPreferencePage_ResetDateLabel;
    public static String AccountPreferencePage_RefreshButton;
    public static String AccountPreferencePage_NotConfiguredMessage;
    public static String AccountPreferencePage_RegisterButton;
    public static String AccountPreferencePage_LoginButton;
    public static String AccountPreferencePage_RotatingKeyButton;
    public static String AccountPreferencePage_SuccessTitle;
    public static String AccountPreferencePage_RotateKeySuccess;
    public static String AccountPreferencePage_UnknownError;
    public static String AccountPreferencePage_ErrorTitle;
    public static String AccountPreferencePage_RotateKeyFailedPrefix;
    public static String AccountPreferencePage_LogoutConfirmTitle;
    public static String AccountPreferencePage_LogoutConfirmMessage;
    public static String AccountPreferencePage_StatusConnected;
    public static String AccountPreferencePage_StatusDisconnected;
    public static String AccountPreferencePage_DefaultBudgetDuration;

    public static String QaProjectPropertyPage_NoProject;
    public static String QaProjectPropertyPage_InfoGroup;
    public static String QaProjectPropertyPage_ProjectLabel;
    public static String QaProjectPropertyPage_ConfigPathLabel;
    public static String QaProjectPropertyPage_RuntimeGroup;
    public static String QaProjectPropertyPage_UseEdtRuntimeLabel;
    public static String QaProjectPropertyPage_UseTestManagerLabel;
    public static String QaProjectPropertyPage_ProjectNameLabel;
    public static String QaProjectPropertyPage_TimeoutLabel;
    public static String QaProjectPropertyPage_EpfPathLabel;
    public static String QaProjectPropertyPage_EpfPathNote;
    public static String QaProjectPropertyPage_ParamsTemplateLabel;
    public static String QaProjectPropertyPage_ParamsTemplateNote;
    public static String QaProjectPropertyPage_PathsGroup;
    public static String QaProjectPropertyPage_FeaturesDirLabel;
    public static String QaProjectPropertyPage_StepsDirLabel;
    public static String QaProjectPropertyPage_StepsDirNote;
    public static String QaProjectPropertyPage_StepsCatalogLabel;
    public static String QaProjectPropertyPage_StepsCatalogNote;
    public static String QaProjectPropertyPage_ResultsDirLabel;
    public static String QaProjectPropertyPage_BehaviorGroup;
    public static String QaProjectPropertyPage_UnknownStepsModeLabel;
    public static String QaProjectPropertyPage_UnknownStepsModeNote;
    public static String QaProjectPropertyPage_JunitReportLabel;
    public static String QaProjectPropertyPage_ScreenshotsLabel;
    public static String QaProjectPropertyPage_CloseClientLabel;
    public static String QaProjectPropertyPage_QuietInstallLabel;
    public static String QaProjectPropertyPage_ShowMainFormLabel;
    public static String QaProjectPropertyPage_BrowseButton;
    public static String QaProjectPropertyPage_SaveErrorTitle;


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
    public static String ProvidersPreferencePage_AccountGroup;
    public static String ProvidersPreferencePage_AccountStatus;
    public static String ProvidersPreferencePage_AccountModel;
    public static String ProvidersPreferencePage_AccountDetails;
    public static String ProvidersPreferencePage_UseAccountProvider;
    public static String ProvidersPreferencePage_AccountStatusConnected;
    public static String ProvidersPreferencePage_AccountStatusConnectedActive;
    public static String ProvidersPreferencePage_AccountStatusDisconnected;
    public static String ProvidersPreferencePage_AccountDetailsConnected;
    public static String ProvidersPreferencePage_AccountDetailsDisconnected;
    public static String ProvidersPreferencePage_AccountNotAvailableTitle;
    public static String ProvidersPreferencePage_AccountNotAvailableMessage;

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
    public static String ProviderEditDialog_ManualModelEntryRequired;
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


    // Skills preference page
    public static String SkillsPreferencePage_Description;
    public static String SkillsPreferencePage_ColumnEnabled;
    public static String SkillsPreferencePage_ColumnName;
    public static String SkillsPreferencePage_ColumnSource;
    public static String SkillsPreferencePage_ColumnTools;
    public static String SkillsPreferencePage_Add;
    public static String SkillsPreferencePage_Edit;
    public static String SkillsPreferencePage_Remove;
    public static String SkillsPreferencePage_Refresh;
    public static String SkillsPreferencePage_OpenFile;
    public static String SkillsPreferencePage_SourceBundled;
    public static String SkillsPreferencePage_SourceUser;
    public static String SkillsPreferencePage_SourceProject;
    public static String SkillsPreferencePage_RemoveConfirmTitle;
    public static String SkillsPreferencePage_RemoveConfirmMessage;

    // Skill edit dialog
    public static String SkillEditDialog_TitleAdd;
    public static String SkillEditDialog_TitleEdit;
    public static String SkillEditDialog_TitleView;
    public static String SkillEditDialog_Name;
    public static String SkillEditDialog_Description;
    public static String SkillEditDialog_BackendOnly;
    public static String SkillEditDialog_AllowedTools;
    public static String SkillEditDialog_Body;
    public static String SkillEditDialog_NameRequired;
    public static String SkillEditDialog_NameInvalid;
    public static String SkillEditDialog_NameDuplicate;
    public static String SkillEditDialog_DescriptionTooLong;

    // Profiles preference page
    public static String ProfilesPreferencePage_Description;
    public static String ProfilesPreferencePage_ColumnIcon;
    public static String ProfilesPreferencePage_ColumnProfile;
    public static String ProfilesPreferencePage_ColumnTools;
    public static String ProfilesPreferencePage_ColumnMaxSteps;
    public static String ProfilesPreferencePage_ColumnTimeout;
    public static String ProfilesPreferencePage_Configure;
    public static String ProfilesPreferencePage_Reset;
    public static String ProfilesPreferencePage_ResetConfirmTitle;
    public static String ProfilesPreferencePage_ResetConfirmMessage;
    public static String ProfilesPreferencePage_Customized;

    // Profile edit dialog
    public static String ProfileEditDialog_Title;
    public static String ProfileEditDialog_MaxSteps;
    public static String ProfileEditDialog_Timeout;
    public static String ProfileEditDialog_DisabledTools;
    public static String ProfileEditDialog_AdditionalPrompt;
    public static String ProfileEditDialog_ResetButton;

    // Skill display info
    public static String SkillDisplayInfo_SkillLabel_review;
    public static String SkillDisplayInfo_SkillLabel_refactor;
    public static String SkillDisplayInfo_SkillLabel_explain;
    public static String SkillDisplayInfo_SkillLabel_architect;
    public static String SkillDisplayInfo_SkillLabel_validator;
    public static String SkillDisplayInfo_ProfileLabel_build;
    public static String SkillDisplayInfo_ProfileLabel_code;
    public static String SkillDisplayInfo_ProfileLabel_metadata;
    public static String SkillDisplayInfo_ProfileLabel_qa;
    public static String SkillDisplayInfo_ProfileLabel_dcs;
    public static String SkillDisplayInfo_ProfileLabel_extension;
    public static String SkillDisplayInfo_ProfileLabel_recovery;
    public static String SkillDisplayInfo_ProfileLabel_plan;
    public static String SkillDisplayInfo_ProfileLabel_explore;
    public static String SkillDisplayInfo_ProfileLabel_orchestrator;
    public static String SkillDisplayInfo_ProfileLabel_auto;

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
