package com.codepilot1c.ui.preferences;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.codepilot1c.core.mcp.host.McpHostConfig;
import com.codepilot1c.core.mcp.host.McpHostConfigStore;
import com.codepilot1c.core.mcp.host.McpHostManager;
import com.codepilot1c.ui.internal.Messages;

/**
 * Preference page for inbound MCP host settings.
 */
public class McpHostPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    private Button enabledCheckbox;
    private Button httpEnabledCheckbox;
    private Text bindAddressText;
    private Spinner portSpinner;
    private Text bearerTokenText;
    private Combo mutationPolicyCombo;
    private Text exposedToolsText;
    private Label warningLabel;
    private Label statusLabel;
    private Text installHintsText;

    private McpHostConfig config;

    @Override
    public void init(IWorkbench workbench) {
        config = McpHostConfigStore.getInstance().load();
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite container = new Composite(parent, SWT.NONE);
        container.setLayout(new GridLayout(2, false));

        Label description = new Label(container, SWT.WRAP);
        description.setText(Messages.McpHostPreferencePage_Description);
        GridData descriptionGd = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
        descriptionGd.widthHint = 520;
        description.setLayoutData(descriptionGd);

        enabledCheckbox = createCheckbox(container, Messages.McpHostPreferencePage_Enabled, config.isEnabled(), 2);
        httpEnabledCheckbox = createCheckbox(container, Messages.McpHostPreferencePage_HttpEnabled, config.isHttpEnabled(), 2);

        createLabel(container, Messages.McpHostPreferencePage_Status);
        Composite statusRow = new Composite(container, SWT.NONE);
        statusRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        statusRow.setLayout(new GridLayout(2, false));
        statusLabel = new Label(statusRow, SWT.NONE);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button checkStatusButton = new Button(statusRow, SWT.PUSH);
        checkStatusButton.setText(Messages.McpHostPreferencePage_CheckNow);
        checkStatusButton.addListener(SWT.Selection, e -> refreshStatus(true));

        createLabel(container, Messages.McpHostPreferencePage_BindAddress);
        bindAddressText = new Text(container, SWT.BORDER);
        bindAddressText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        bindAddressText.setText(config.getBindAddress() != null ? config.getBindAddress() : "127.0.0.1"); //$NON-NLS-1$

        createLabel(container, Messages.McpHostPreferencePage_Port);
        portSpinner = new Spinner(container, SWT.BORDER);
        portSpinner.setMinimum(1);
        portSpinner.setMaximum(65535);
        portSpinner.setSelection(config.getPort());

        createLabel(container, Messages.McpHostPreferencePage_BearerToken);
        Composite tokenRow = new Composite(container, SWT.NONE);
        tokenRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        tokenRow.setLayout(new GridLayout(2, false));

        bearerTokenText = new Text(tokenRow, SWT.BORDER);
        bearerTokenText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        bearerTokenText.setText(config.getBearerToken() != null ? config.getBearerToken() : ""); //$NON-NLS-1$

        Button rotateButton = new Button(tokenRow, SWT.PUSH);
        rotateButton.setText(Messages.McpHostPreferencePage_RotateToken);
        rotateButton.addListener(SWT.Selection, e -> bearerTokenText.setText(McpHostConfig.generateToken()));
        bearerTokenText.addModifyListener(e -> installHintsText.setText(buildInstallHints()));

        createLabel(container, Messages.McpHostPreferencePage_MutationPolicy);
        mutationPolicyCombo = new Combo(container, SWT.DROP_DOWN | SWT.READ_ONLY);
        mutationPolicyCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        mutationPolicyCombo.setItems(new String[] {
            Messages.McpHostPreferencePage_MutationAsk,
            Messages.McpHostPreferencePage_MutationDeny,
            Messages.McpHostPreferencePage_MutationAllow
        });
        mutationPolicyCombo.select(Math.max(0, config.getMutationPolicy().ordinal()));

        createLabel(container, Messages.McpHostPreferencePage_ExposedTools);
        exposedToolsText = new Text(container, SWT.BORDER);
        exposedToolsText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        exposedToolsText.setMessage("*, -edit_file, -write_file"); //$NON-NLS-1$
        exposedToolsText.setText(config.getExposedToolsFilter() != null ? config.getExposedToolsFilter() : "*"); //$NON-NLS-1$

        warningLabel = new Label(container, SWT.WRAP);
        warningLabel.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
        warningLabel.setForeground(container.getDisplay().getSystemColor(SWT.COLOR_DARK_YELLOW));

        Label hintsLabel = new Label(container, SWT.NONE);
        hintsLabel.setText(Messages.McpHostPreferencePage_InstallHints);
        GridData hintsLabelGd = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
        hintsLabelGd.verticalIndent = 8;
        hintsLabel.setLayoutData(hintsLabelGd);

        installHintsText = new Text(container, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.READ_ONLY);
        GridData hintsGd = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
        hintsGd.widthHint = 620;
        hintsGd.heightHint = 280;
        installHintsText.setLayoutData(hintsGd);
        installHintsText.setText(buildInstallHints());

        ModifyListener updateWarning = e -> refreshWarning();
        bindAddressText.addModifyListener(updateWarning);
        httpEnabledCheckbox.addListener(SWT.Selection, e -> refreshStatus(true));
        enabledCheckbox.addListener(SWT.Selection, e -> refreshStatus(true));
        portSpinner.addModifyListener(e -> {
            installHintsText.setText(buildInstallHints());
            refreshStatus(false);
        });
        bindAddressText.addModifyListener(e -> {
            installHintsText.setText(buildInstallHints());
            refreshStatus(false);
        });
        refreshWarning();
        refreshStatus(true);
        return container;
    }

    private Button createCheckbox(Composite parent, String text, boolean selected, int span) {
        Button checkbox = new Button(parent, SWT.CHECK);
        checkbox.setText(text);
        checkbox.setSelection(selected);
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false, span, 1);
        checkbox.setLayoutData(gd);
        return checkbox;
    }

    private void createLabel(Composite parent, String text) {
        Label label = new Label(parent, SWT.NONE);
        label.setText(text);
    }

    private void refreshWarning() {
        String bind = bindAddressText.getText().trim();
        if ("127.0.0.1".equals(bind) || "localhost".equalsIgnoreCase(bind)) { //$NON-NLS-1$ //$NON-NLS-2$
            warningLabel.setText(Messages.McpHostPreferencePage_LocalOnlyInfo);
        } else {
            warningLabel.setText(Messages.McpHostPreferencePage_NonLocalWarning);
        }
    }

    private void refreshStatus(boolean withHealthCheck) {
        boolean running = McpHostManager.getInstance().isRunning();
        if (!running) {
            statusLabel.setText(Messages.McpHostPreferencePage_StatusStopped);
            statusLabel.setForeground(statusLabel.getDisplay().getSystemColor(SWT.COLOR_RED));
            return;
        }

        if (!httpEnabledCheckbox.getSelection()) {
            statusLabel.setText(Messages.McpHostPreferencePage_StatusRunningNoHttp);
            statusLabel.setForeground(statusLabel.getDisplay().getSystemColor(SWT.COLOR_DARK_GREEN));
            return;
        }

        if (!withHealthCheck) {
            statusLabel.setText(Messages.McpHostPreferencePage_StatusRunning);
            statusLabel.setForeground(statusLabel.getDisplay().getSystemColor(SWT.COLOR_DARK_GREEN));
            return;
        }

        boolean healthy = checkHealth();
        if (healthy) {
            statusLabel.setText(Messages.McpHostPreferencePage_StatusHttpOk);
            statusLabel.setForeground(statusLabel.getDisplay().getSystemColor(SWT.COLOR_DARK_GREEN));
        } else {
            statusLabel.setText(Messages.McpHostPreferencePage_StatusHttpFail);
            statusLabel.setForeground(statusLabel.getDisplay().getSystemColor(SWT.COLOR_DARK_YELLOW));
        }
    }

    private boolean checkHealth() {
        String bind = bindAddressText.getText().trim();
        if (bind.isBlank()) {
            bind = "127.0.0.1"; //$NON-NLS-1$
        } else if ("0.0.0.0".equals(bind)) { //$NON-NLS-1$
            bind = "127.0.0.1"; //$NON-NLS-1$
        }
        int port = portSpinner.getSelection();
        String url = "http://" + bind + ":" + port + "/health"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.statusCode() == 200 && "ok".equalsIgnoreCase(response.body().trim()); //$NON-NLS-1$
        } catch (Exception e) {
            return false;
        }
    }

    private String buildInstallHints() {
        String bind = bindAddressText.getText().trim();
        if (bind.isBlank()) {
            bind = "127.0.0.1"; //$NON-NLS-1$
        } else if ("0.0.0.0".equals(bind)) { //$NON-NLS-1$
            bind = "127.0.0.1"; //$NON-NLS-1$
        }
        int port = portSpinner.getSelection();
        String endpoint = "http://" + bind + ":" + port + "/mcp"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String token = bearerTokenText.getText().trim();
        if (token.isBlank()) {
            token = "<РЕЗЕРВНЫЙ_BEARER_ТОКЕН>"; //$NON-NLS-1$
        }
        return """
Встроенный MCP-сервер уже запущен в EDT и работает по HTTP + OAuth 2.1 (RFC 9728).

Endpoint:
%s

Claude Code (глобально, профиль пользователя):
claude mcp add --transport http -s user codepilot1c %s

Cursor (.cursor/mcp.json):
{
  "mcpServers": {
    "codepilot1c": {
      "url": "%s"
    }
  }
}

Codex (MCP-конфиг):
{
  "mcpServers": {
    "codepilot1c": {
      "url": "%s"
    }
  }
}

Резервный вариант для клиентов без OAuth (статический Bearer):
{
  "mcpServers": {
    "codepilot1c": {
      "url": "%s",
      "headers": {
        "Authorization": "Bearer %s"
      }
    }
  }
}
""".formatted(endpoint, endpoint, endpoint, endpoint, endpoint, token);
    }

    @Override
    public boolean performOk() {
        McpHostConfig newConfig = McpHostConfig.defaults();
        newConfig.setEnabled(enabledCheckbox.getSelection());
        newConfig.setHttpEnabled(httpEnabledCheckbox.getSelection());
        newConfig.setBindAddress(bindAddressText.getText().trim());
        newConfig.setPort(portSpinner.getSelection());
        newConfig.setBearerToken(bearerTokenText.getText().trim());
        newConfig.setMutationPolicy(McpHostConfig.MutationPolicy.values()[mutationPolicyCombo.getSelectionIndex()]);
        newConfig.setExposedToolsFilter(exposedToolsText.getText().trim());

        McpHostConfigStore.getInstance().save(newConfig);
        McpHostManager.getInstance().restart();
        refreshStatus(true);
        return true;
    }

    @Override
    protected void performDefaults() {
        McpHostConfig defaults = McpHostConfig.defaults();
        enabledCheckbox.setSelection(defaults.isEnabled());
        httpEnabledCheckbox.setSelection(defaults.isHttpEnabled());
        bindAddressText.setText(defaults.getBindAddress());
        portSpinner.setSelection(defaults.getPort());
        bearerTokenText.setText(defaults.getBearerToken());
        mutationPolicyCombo.select(defaults.getMutationPolicy().ordinal());
        exposedToolsText.setText(defaults.getExposedToolsFilter());
        installHintsText.setText(buildInstallHints());
        refreshWarning();
        refreshStatus(false);
        super.performDefaults();
    }
}
