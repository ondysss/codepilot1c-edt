/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.preferences;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.ITableLabelProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.Text;

import com.codepilot1c.core.mcp.config.McpServerConfig;
import com.codepilot1c.core.mcp.config.McpServerConfig.AuthMode;
import com.codepilot1c.core.mcp.config.McpServerConfig.TransportType;
import com.codepilot1c.ui.internal.Messages;

/**
 * Dialog for editing MCP server configuration.
 */
public class McpServerEditDialog extends TitleAreaDialog {

    private final McpServerConfig existingConfig;
    private final List<Map.Entry<String, String>> envVariables = new ArrayList<>();

    private Text nameText;
    private Button enabledCheckbox;
    private Combo transportCombo;
    private Combo authModeCombo;
    private Text commandText;
    private Text argsText;
    private Text workingDirText;
    private Text remoteUrlText;
    private Text remoteSseUrlText;
    private Button allowLegacyFallbackCheckbox;
    private Text staticHeadersText;
    private Text oauthProfileIdText;
    private Text preferredProtocolText;
    private Text supportedProtocolsText;
    private TableViewer envTableViewer;
    private Spinner connectionTimeoutSpinner;
    private Spinner requestTimeoutSpinner;

    private Composite stdioGroup;
    private Composite remoteGroup;
    private Composite staticHeadersGroup;
    private Composite oauthGroup;

    private McpServerConfig savedConfig;

    public McpServerEditDialog(Shell parentShell, McpServerConfig existingConfig) {
        super(parentShell);
        this.existingConfig = existingConfig;
        if (existingConfig != null) {
            existingConfig.getEnv().forEach((k, v) -> envVariables.add(new AbstractMap.SimpleEntry<>(k, v)));
        }
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText(existingConfig == null
            ? Messages.McpServerEditDialog_TitleAdd
            : Messages.McpServerEditDialog_TitleEdit);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        setTitle(existingConfig == null
            ? Messages.McpServerEditDialog_TitleAdd
            : Messages.McpServerEditDialog_TitleEdit);
        setMessage(Messages.McpServerEditDialog_Description);

        Composite area = (Composite) super.createDialogArea(parent);
        Composite container = new Composite(area, SWT.NONE);
        container.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        container.setLayout(new GridLayout(2, false));

        createLabel(container, Messages.McpServerEditDialog_Name);
        nameText = new Text(container, SWT.BORDER);
        nameText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        if (existingConfig != null) {
            nameText.setText(existingConfig.getName());
        }

        enabledCheckbox = new Button(container, SWT.CHECK);
        enabledCheckbox.setText(Messages.McpServerEditDialog_Enabled);
        enabledCheckbox.setSelection(existingConfig == null || existingConfig.isEnabled());
        GridData enabledGd = new GridData();
        enabledGd.horizontalSpan = 2;
        enabledCheckbox.setLayoutData(enabledGd);

        createLabel(container, Messages.McpServerEditDialog_TransportType);
        transportCombo = new Combo(container, SWT.DROP_DOWN | SWT.READ_ONLY);
        transportCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        transportCombo.setItems(new String[] {
            "STDIO", "STREAMABLE_HTTP", "HTTP_SSE_LEGACY" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        });
        TransportType initialTransport = existingConfig != null
            ? existingConfig.getTransportType()
            : TransportType.STDIO;
        transportCombo.select(Math.max(0, initialTransport.ordinal()));
        transportCombo.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> updateVisibility()));

        stdioGroup = new Composite(container, SWT.NONE);
        stdioGroup.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false, 2, 1));
        stdioGroup.setLayout(new GridLayout(2, false));
        createStdioControls(stdioGroup);

        remoteGroup = new Composite(container, SWT.NONE);
        remoteGroup.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false, 2, 1));
        remoteGroup.setLayout(new GridLayout(2, false));
        createRemoteControls(remoteGroup);

        createLabel(container, Messages.McpServerEditDialog_AuthMode);
        authModeCombo = new Combo(container, SWT.DROP_DOWN | SWT.READ_ONLY);
        authModeCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        authModeCombo.setItems(new String[] { "NONE", "STATIC_HEADERS", "OAUTH2" }); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        AuthMode initialAuth = existingConfig != null ? existingConfig.getAuthMode() : AuthMode.NONE;
        authModeCombo.select(Math.max(0, initialAuth.ordinal()));
        authModeCombo.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> updateVisibility()));

        staticHeadersGroup = new Composite(container, SWT.NONE);
        staticHeadersGroup.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false, 2, 1));
        staticHeadersGroup.setLayout(new GridLayout(2, false));
        createStaticHeaderControls(staticHeadersGroup);

        oauthGroup = new Composite(container, SWT.NONE);
        oauthGroup.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false, 2, 1));
        oauthGroup.setLayout(new GridLayout(2, false));
        createOAuthControls(oauthGroup);

        createLabel(container, Messages.McpServerEditDialog_ConnectionTimeout);
        connectionTimeoutSpinner = new Spinner(container, SWT.BORDER);
        connectionTimeoutSpinner.setMinimum(5);
        connectionTimeoutSpinner.setMaximum(120);
        connectionTimeoutSpinner.setSelection(existingConfig != null
            ? existingConfig.getConnectionTimeoutMs() / 1000
            : 30);

        createLabel(container, Messages.McpServerEditDialog_RequestTimeout);
        requestTimeoutSpinner = new Spinner(container, SWT.BORDER);
        requestTimeoutSpinner.setMinimum(10);
        requestTimeoutSpinner.setMaximum(300);
        requestTimeoutSpinner.setSelection(existingConfig != null
            ? existingConfig.getRequestTimeoutMs() / 1000
            : 60);

        updateVisibility();
        return area;
    }

    private void createStdioControls(Composite parent) {
        createLabel(parent, Messages.McpServerEditDialog_Command);
        commandText = new Text(parent, SWT.BORDER);
        commandText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        commandText.setMessage("npx, node, python..."); //$NON-NLS-1$
        if (existingConfig != null && existingConfig.getCommand() != null) {
            commandText.setText(existingConfig.getCommand());
        }

        createLabel(parent, Messages.McpServerEditDialog_Arguments);
        argsText = new Text(parent, SWT.BORDER);
        argsText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        argsText.setMessage("-y @modelcontextprotocol/server-filesystem /path"); //$NON-NLS-1$
        if (existingConfig != null && !existingConfig.getArgs().isEmpty()) {
            argsText.setText(String.join(" ", existingConfig.getArgs())); //$NON-NLS-1$
        }

        createLabel(parent, Messages.McpServerEditDialog_WorkingDirectory);
        Composite dirComposite = new Composite(parent, SWT.NONE);
        dirComposite.setLayout(new GridLayout(2, false));
        dirComposite.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        workingDirText = new Text(dirComposite, SWT.BORDER);
        workingDirText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        if (existingConfig != null && existingConfig.getWorkingDirectory() != null) {
            workingDirText.setText(existingConfig.getWorkingDirectory());
        }
        Button browseButton = new Button(dirComposite, SWT.PUSH);
        browseButton.setText(Messages.McpServerEditDialog_Browse);
        browseButton.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> browseDirectory()));

        createLabel(parent, Messages.McpServerEditDialog_Environment);
        createEnvTable(parent);
    }

    private void createRemoteControls(Composite parent) {
        createLabel(parent, Messages.McpServerEditDialog_RemoteUrl);
        remoteUrlText = new Text(parent, SWT.BORDER);
        remoteUrlText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        if (existingConfig != null && existingConfig.getRemoteUrl() != null) {
            remoteUrlText.setText(existingConfig.getRemoteUrl());
        }

        createLabel(parent, Messages.McpServerEditDialog_RemoteSseUrl);
        remoteSseUrlText = new Text(parent, SWT.BORDER);
        remoteSseUrlText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        if (existingConfig != null && existingConfig.getRemoteSseUrl() != null) {
            remoteSseUrlText.setText(existingConfig.getRemoteSseUrl());
        }

        allowLegacyFallbackCheckbox = new Button(parent, SWT.CHECK);
        allowLegacyFallbackCheckbox.setText(Messages.McpServerEditDialog_AllowLegacyFallback);
        allowLegacyFallbackCheckbox.setSelection(existingConfig != null && existingConfig.isAllowLegacyFallback());
        GridData gd = new GridData();
        gd.horizontalSpan = 2;
        allowLegacyFallbackCheckbox.setLayoutData(gd);

        createLabel(parent, Messages.McpServerEditDialog_PreferredProtocol);
        preferredProtocolText = new Text(parent, SWT.BORDER);
        preferredProtocolText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        if (existingConfig != null && existingConfig.getPreferredProtocolVersion() != null) {
            preferredProtocolText.setText(existingConfig.getPreferredProtocolVersion());
        } else {
            preferredProtocolText.setText(McpServerConfig.DEFAULT_PROTOCOL_VERSION);
        }

        createLabel(parent, Messages.McpServerEditDialog_SupportedProtocols);
        supportedProtocolsText = new Text(parent, SWT.BORDER);
        supportedProtocolsText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        if (existingConfig != null) {
            supportedProtocolsText.setText(String.join(",", existingConfig.getSupportedProtocolVersions())); //$NON-NLS-1$
        } else {
            supportedProtocolsText.setText(String.join(",", McpServerConfig.DEFAULT_SUPPORTED_PROTOCOLS)); //$NON-NLS-1$
        }
    }

    private void createStaticHeaderControls(Composite parent) {
        createLabel(parent, Messages.McpServerEditDialog_StaticHeaders);
        staticHeadersText = new Text(parent, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.WRAP);
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, false);
        gd.heightHint = 80;
        staticHeadersText.setLayoutData(gd);
        staticHeadersText.setMessage("Authorization: Bearer ...\nX-Header: value"); //$NON-NLS-1$
        if (existingConfig != null && !existingConfig.getStaticHeaders().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            existingConfig.getStaticHeaders().forEach((k, v) -> {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(k).append(": ").append(v); //$NON-NLS-1$
            });
            staticHeadersText.setText(sb.toString());
        }
    }

    private void createOAuthControls(Composite parent) {
        createLabel(parent, Messages.McpServerEditDialog_OAuthProfileId);
        oauthProfileIdText = new Text(parent, SWT.BORDER);
        oauthProfileIdText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        if (existingConfig != null && existingConfig.getOauthProfileId() != null) {
            oauthProfileIdText.setText(existingConfig.getOauthProfileId());
        }
    }

    private void createLabel(Composite parent, String text) {
        Label label = new Label(parent, SWT.NONE);
        label.setText(text);
    }

    private void createEnvTable(Composite parent) {
        Composite envComposite = new Composite(parent, SWT.NONE);
        envComposite.setLayout(new GridLayout(2, false));
        envComposite.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));

        envTableViewer = new TableViewer(envComposite, SWT.BORDER | SWT.FULL_SELECTION);
        Table table = envTableViewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.heightHint = 80;
        table.setLayoutData(gd);

        TableColumn keyCol = new TableColumn(table, SWT.NONE);
        keyCol.setText(Messages.McpServerEditDialog_EnvKey);
        keyCol.setWidth(120);

        TableColumn valueCol = new TableColumn(table, SWT.NONE);
        valueCol.setText(Messages.McpServerEditDialog_EnvValue);
        valueCol.setWidth(180);

        envTableViewer.setContentProvider(ArrayContentProvider.getInstance());
        envTableViewer.setLabelProvider(new EnvLabelProvider());
        envTableViewer.setInput(envVariables);

        Composite envButtons = new Composite(envComposite, SWT.NONE);
        envButtons.setLayout(new GridLayout(1, false));
        envButtons.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));

        Button addEnvButton = new Button(envButtons, SWT.PUSH);
        addEnvButton.setText(Messages.McpServerEditDialog_AddEnv);
        addEnvButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        addEnvButton.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> addEnvVariable()));

        Button removeEnvButton = new Button(envButtons, SWT.PUSH);
        removeEnvButton.setText(Messages.McpServerEditDialog_RemoveEnv);
        removeEnvButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        removeEnvButton.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> removeEnvVariable()));
    }

    private void addEnvVariable() {
        InputDialog keyDialog = new InputDialog(getShell(),
            Messages.McpServerEditDialog_EnvKey,
            Messages.McpServerEditDialog_EnvKeyPrompt,
            "", null); //$NON-NLS-1$
        if (keyDialog.open() == Window.OK) {
            String key = keyDialog.getValue();
            InputDialog valueDialog = new InputDialog(getShell(),
                Messages.McpServerEditDialog_EnvValue,
                Messages.McpServerEditDialog_EnvValuePrompt,
                "", null); //$NON-NLS-1$
            if (valueDialog.open() == Window.OK) {
                envVariables.add(new AbstractMap.SimpleEntry<>(key, valueDialog.getValue()));
                envTableViewer.refresh();
            }
        }
    }

    private void removeEnvVariable() {
        IStructuredSelection selection = envTableViewer.getStructuredSelection();
        if (!selection.isEmpty()) {
            envVariables.remove(selection.getFirstElement());
            envTableViewer.refresh();
        }
    }

    private void browseDirectory() {
        DirectoryDialog dialog = new DirectoryDialog(getShell());
        dialog.setText(Messages.McpServerEditDialog_SelectDirectory);
        String path = dialog.open();
        if (path != null) {
            workingDirText.setText(path);
        }
    }

    private TransportType getSelectedTransport() {
        int idx = transportCombo.getSelectionIndex();
        if (idx < 0) {
            return TransportType.STDIO;
        }
        return TransportType.values()[idx];
    }

    private AuthMode getSelectedAuthMode() {
        int idx = authModeCombo.getSelectionIndex();
        if (idx < 0) {
            return AuthMode.NONE;
        }
        return AuthMode.values()[idx];
    }

    private void updateVisibility() {
        TransportType transport = getSelectedTransport();
        AuthMode authMode = getSelectedAuthMode();

        boolean isStdio = transport == TransportType.STDIO;
        setCompositeVisible(stdioGroup, isStdio);
        setCompositeVisible(remoteGroup, !isStdio);
        setCompositeVisible(staticHeadersGroup, !isStdio && authMode == AuthMode.STATIC_HEADERS);
        setCompositeVisible(oauthGroup, !isStdio && authMode == AuthMode.OAUTH2);
        getShell().layout(true, true);
    }

    private void setCompositeVisible(Composite composite, boolean visible) {
        if (composite == null) {
            return;
        }
        GridData gd = (GridData) composite.getLayoutData();
        gd.exclude = !visible;
        composite.setVisible(visible);
    }

    @Override
    protected void okPressed() {
        if (!validateInput()) {
            return;
        }
        savedConfig = buildServerConfig();
        super.okPressed();
    }

    private boolean validateInput() {
        if (nameText.getText().trim().isEmpty()) {
            setErrorMessage(Messages.McpServerEditDialog_NameRequired);
            nameText.setFocus();
            return false;
        }
        if (getSelectedTransport() == TransportType.STDIO && commandText.getText().trim().isEmpty()) {
            setErrorMessage(Messages.McpServerEditDialog_CommandRequired);
            commandText.setFocus();
            return false;
        }
        if (getSelectedTransport() != TransportType.STDIO && remoteUrlText.getText().trim().isEmpty()) {
            setErrorMessage(Messages.McpServerEditDialog_RemoteUrlRequired);
            remoteUrlText.setFocus();
            return false;
        }
        setErrorMessage(null);
        return true;
    }

    public McpServerConfig getServerConfig() {
        return savedConfig;
    }

    private McpServerConfig buildServerConfig() {
        TransportType transport = getSelectedTransport();
        AuthMode authMode = getSelectedAuthMode();
        McpServerConfig.Builder builder = McpServerConfig.builder()
            .name(nameText.getText().trim())
            .enabled(enabledCheckbox.getSelection())
            .transportType(transport)
            .authMode(authMode)
            .connectionTimeout(connectionTimeoutSpinner.getSelection() * 1000)
            .requestTimeout(requestTimeoutSpinner.getSelection() * 1000);

        if (existingConfig != null) {
            builder.id(existingConfig.getId());
        }

        if (transport == TransportType.STDIO) {
            builder.command(commandText.getText().trim())
                .args(parseArgs(argsText.getText()));
            String workDir = workingDirText.getText().trim();
            if (!workDir.isEmpty()) {
                builder.workingDirectory(workDir);
            }
            for (Map.Entry<String, String> entry : envVariables) {
                builder.putEnv(entry.getKey(), entry.getValue());
            }
        } else {
            builder.remoteUrl(remoteUrlText.getText().trim())
                .remoteSseUrl(emptyToNull(remoteSseUrlText.getText()))
                .allowLegacyFallback(allowLegacyFallbackCheckbox.getSelection())
                .preferredProtocolVersion(emptyToNull(preferredProtocolText.getText()))
                .supportedProtocolVersions(parseCsv(supportedProtocolsText.getText()));
            if (authMode == AuthMode.STATIC_HEADERS) {
                parseHeaderLines(staticHeadersText.getText()).forEach(builder::putStaticHeader);
            } else if (authMode == AuthMode.OAUTH2) {
                builder.oauthProfileId(emptyToNull(oauthProfileIdText.getText()));
            }
        }

        return builder.build();
    }

    private String emptyToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private List<String> parseCsv(String value) {
        List<String> result = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return result;
        }
        for (String item : value.split(",")) { //$NON-NLS-1$
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private Map<String, String> parseHeaderLines(String text) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return headers;
        }
        String[] lines = text.split("\\R"); //$NON-NLS-1$
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int separator = trimmed.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String key = trimmed.substring(0, separator).trim();
            String value = trimmed.substring(separator + 1).trim();
            if (!key.isEmpty()) {
                headers.put(key, value);
            }
        }
        return headers;
    }

    private List<String> parseArgs(String argsString) {
        List<String> args = new ArrayList<>();
        if (argsString == null || argsString.trim().isEmpty()) {
            return args;
        }

        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = 0;

        for (char c : argsString.toCharArray()) {
            if ((c == '"' || c == '\'') && !inQuotes) {
                inQuotes = true;
                quoteChar = c;
            } else if (c == quoteChar && inQuotes) {
                inQuotes = false;
                quoteChar = 0;
            } else if (c == ' ' && !inQuotes) {
                if (current.length() > 0) {
                    args.add(current.toString());
                    current = new StringBuilder();
                }
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            args.add(current.toString());
        }
        return args;
    }

    private static class EnvLabelProvider extends LabelProvider implements ITableLabelProvider {
        @Override
        public String getColumnText(Object element, int columnIndex) {
            @SuppressWarnings("unchecked")
            Map.Entry<String, String> entry = (Map.Entry<String, String>) element;
            return columnIndex == 0 ? entry.getKey() : entry.getValue();
        }

        @Override
        public Image getColumnImage(Object element, int columnIndex) {
            return null;
        }
    }
}
