package com.codepilot1c.core.tools.diagnostics;

import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService.AccessSettings;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ActiveProjectSupport;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolExecutionContext;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonObject;

/**
 * Returns non-secret metadata about EDT-stored infobase access settings. The stored password is
 * never included in the model-facing result; callers may use OS authentication or ask the user to
 * sign in manually in the browser session. Read-only against EDT's infobase access settings.
 */
@ToolMeta(name = "get_infobase_credentials", category = "diagnostics",
        tags = {"read-only", "edt", "diagnostics", "sensitive"})
public class GetInfobaseCredentialsTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(GetInfobaseCredentialsTool.class);

    private static final String TOOL_NAME = "get_infobase_credentials"; //$NON-NLS-1$

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "projectName": {"type": "string", "description": "EDT project whose infobase access credentials are needed. Optional: if omitted, the active editor project (or the single open project) is used."}
              },
              "required": [],
              "additionalProperties": false
            }
            """; //$NON-NLS-1$

    private final EdtRuntimeService runtimeService;

    public GetInfobaseCredentialsTool() {
        this(new EdtRuntimeService());
    }

    GetInfobaseCredentialsTool(EdtRuntimeService runtimeService) {
        this.runtimeService = runtimeService == null ? new EdtRuntimeService() : runtimeService;
    }

    @Override
    public String getDescription() {
        return "Returns the EDT-stored infobase login name and authentication availability metadata, " //$NON-NLS-1$
                + "never the stored password. Use OS authentication or manual user login in the browser session."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return doExecute(params, ToolExecutionContext.unscoped());
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(
            ToolParameters params, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("infobase-creds"); //$NON-NLS-1$
            String projectName = resolveProjectName(params, context);
            if (projectName == null || projectName.isBlank()) {
                return ObservabilityToolSupport.failure(opId, TOOL_NAME, "PROJECT_NOT_RESOLVED", //$NON-NLS-1$
                        "projectName could not be resolved automatically. Open projects: " //$NON-NLS-1$
                                + ActiveProjectSupport.openProjectNames()
                                + ". Pass projectName explicitly, or open the target project in the EDT editor.", //$NON-NLS-1$
                        true);
            }

            AccessSettings settings;
            try {
                settings = runtimeService.resolveAccessSettings(projectName);
            } catch (Exception e) {
                return ObservabilityToolSupport.failure(opId, TOOL_NAME, "CREDENTIALS_LOOKUP_FAILED", //$NON-NLS-1$
                        e.getMessage(), true);
            }
            if (settings == null) {
                return ObservabilityToolSupport.failure(opId, TOOL_NAME, "CREDENTIALS_NOT_DEFINED", //$NON-NLS-1$
                        "No infobase access credentials are stored in EDT for project '" + projectName //$NON-NLS-1$
                                + "'. Use OS authentication if available, configure infobase access in EDT, " //$NON-NLS-1$
                                + "or ask the user to sign in manually in the browser session.", //$NON-NLS-1$
                        true);
            }

            String authKind = settings.isOsAuthentication() ? "os" //$NON-NLS-1$
                    : settings.isInfobaseAuthentication() ? "infobase" //$NON-NLS-1$
                    : "additional"; //$NON-NLS-1$
            // Log only non-secret metadata; the password is never written to logs.
            LOG.info("[%s] resolved infobase credentials project=%s auth_kind=%s", opId, //$NON-NLS-1$
                    LogSanitizer.truncate(projectName, 200), authKind);

            JsonObject payload = ObservabilityToolSupport.successEnvelope(opId, TOOL_NAME);
            JsonObject data = payload.getAsJsonObject("data"); //$NON-NLS-1$
            boolean passwordAvailable = settings.getPassword() != null && !settings.getPassword().isBlank();
            fillCredentialsData(data, projectName, authKind, settings.getUserName(), passwordAvailable,
                    settings.getAdditionalParameters());
            return ObservabilityToolSupport.success(payload);
        });
    }

    static void fillCredentialsData(JsonObject data, String project, String authKind, String userName,
            boolean passwordAvailable, String additionalParameters) {
        String maskedAdditionalParameters = InfobaseAccessParameterMasking.mask(additionalParameters);
        data.addProperty("project", project); //$NON-NLS-1$
        data.addProperty("auth_kind", authKind); //$NON-NLS-1$
        data.addProperty("user_name", nullToEmpty(userName)); //$NON-NLS-1$
        data.addProperty("password_available", passwordAvailable); //$NON-NLS-1$
        data.addProperty("password_delivery", "unavailable"); //$NON-NLS-1$ //$NON-NLS-2$
        data.addProperty("password_delivery_reason", "plaintext_delivery_disabled"); //$NON-NLS-1$ //$NON-NLS-2$
        data.addProperty("login_strategy", loginStrategy(authKind, passwordAvailable)); //$NON-NLS-1$
        data.addProperty("additional_parameters", nullToEmpty(maskedAdditionalParameters)); //$NON-NLS-1$
        data.addProperty("additional_parameters_masked", //$NON-NLS-1$
                InfobaseAccessParameterMasking.isMasked(additionalParameters, maskedAdditionalParameters));
        data.addProperty("next_action", //$NON-NLS-1$
                "The stored password is never returned to the model. Use OS authentication if the " //$NON-NLS-1$
                        + "infobase allows it, or ask the user to sign in manually in the browser session " //$NON-NLS-1$
                        + "and continue from the authenticated page. Never ask the user to enter a password " //$NON-NLS-1$
                        + "in chat, expose it to the model, guess it, or reuse one from elsewhere."); //$NON-NLS-1$
        data.addProperty("security_note", //$NON-NLS-1$
                "CodePilot1C does not expose stored infobase passwords to the model, to logs, " //$NON-NLS-1$
                        + "or to conversation history."); //$NON-NLS-1$

        if ("os".equals(authKind)) { //$NON-NLS-1$
            data.addProperty("note", //$NON-NLS-1$
                    "OS authentication is configured: no explicit login/password — the web client uses the OS session."); //$NON-NLS-1$
        } else if (!passwordAvailable) {
            data.addProperty("hint", //$NON-NLS-1$
                    "Ask the user to sign in manually in the browser session; this account has no stored password."); //$NON-NLS-1$
        }
    }

    private static String loginStrategy(String authKind, boolean passwordAvailable) {
        if ("os".equals(authKind)) { //$NON-NLS-1$
            return "os_session"; //$NON-NLS-1$
        }
        if (passwordAvailable) {
            return "ask_user"; //$NON-NLS-1$
        }
        return "no_password_required"; //$NON-NLS-1$
    }

    private String resolveProjectName(ToolParameters params, ToolExecutionContext context) {
        Object raw = params.getRaw().get("projectName"); //$NON-NLS-1$
        String explicit = raw == null ? null : String.valueOf(raw).trim();
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return ActiveProjectSupport.resolveActiveProjectName(context);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value; //$NON-NLS-1$
    }
}
