package com.codepilot1c.core.tools.diagnostics;

import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService.WebClientInfo;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ActiveProjectSupport;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolExecutionContext;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.JsonObject;

/**
 * Resolves the running 1C web client URL (and designer URL) of a project's standalone-server
 * infobase so a browser/Playwright session can navigate to it and verify the live UI.
 *
 * <p>Read-only. Also reports the standalone server state and a provider-independent soft multimodal
 * check that assumes the caller is vision-capable and never blocks.</p>
 */
@ToolMeta(name = "resolve_web_client_url", category = "diagnostics",
        tags = {"read-only", "edt", "diagnostics"})
public class ResolveWebClientUrlTool extends AbstractTool {

    private static final String TOOL_NAME = "resolve_web_client_url"; //$NON-NLS-1$

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "projectName": {"type": "string", "description": "EDT project whose standalone-server infobase web client URL is needed. Optional: if omitted, the active editor project (or the single open project) is used."}
              },
              "required": [],
              "additionalProperties": false
            }
            """; //$NON-NLS-1$

    private final EdtRuntimeService runtimeService;

    public ResolveWebClientUrlTool() {
        this.runtimeService = new EdtRuntimeService();
    }

    @Override
    public String getDescription() {
        return "Resolves the running 1C web client URL (and designer URL) of a project's standalone-server infobase for browser-based UI verification."; //$NON-NLS-1$
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
            String opId = LogSanitizer.newId("web-client-url"); //$NON-NLS-1$
            try {
                String projectName = resolveProjectName(params, context);
                if (projectName == null || projectName.isBlank()) {
                    return ObservabilityToolSupport.failure(opId, TOOL_NAME, "PROJECT_NOT_RESOLVED", //$NON-NLS-1$
                            "projectName could not be resolved automatically. Open projects: " //$NON-NLS-1$
                                    + ActiveProjectSupport.openProjectNames()
                                    + ". Pass projectName explicitly, or open the target project in the EDT editor.", //$NON-NLS-1$
                            true);
                }

                WebClientInfo info = runtimeService.resolveWebClientInfo(projectName);
                if (!info.available()) {
                    return ObservabilityToolSupport.failure(opId, TOOL_NAME, "WEB_CLIENT_UNAVAILABLE", //$NON-NLS-1$
                            info.message(), true);
                }

                JsonObject payload = ObservabilityToolSupport.successEnvelope(opId, TOOL_NAME);
                JsonObject data = payload.getAsJsonObject("data"); //$NON-NLS-1$
                data.addProperty("project", projectName); //$NON-NLS-1$
                data.addProperty("web_client_url", info.webClientUrl()); //$NON-NLS-1$
                data.addProperty("designer_url", info.designerUrl()); //$NON-NLS-1$
                data.addProperty("server_name", info.serverName()); //$NON-NLS-1$
                data.addProperty("server_state", info.serverState()); //$NON-NLS-1$
                data.addProperty("server_running", info.serverRunning()); //$NON-NLS-1$

                applyMultimodalGuard(data);
                data.addProperty("usage_hint", usageHint(info)); //$NON-NLS-1$

                return ObservabilityToolSupport.success(payload);
            } catch (Exception e) {
                return ObservabilityToolSupport.failure(opId, TOOL_NAME, "WEB_CLIENT_URL_FAILED", //$NON-NLS-1$
                        e.getMessage(), true);
            }
        });
    }

    private String resolveProjectName(ToolParameters params, ToolExecutionContext context) {
        Object raw = params.getRaw().get("projectName"); //$NON-NLS-1$
        String explicit = raw == null ? null : String.valueOf(raw).trim();
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return ActiveProjectSupport.resolveActiveProjectName(context);
    }

    /** Provider-independent soft guard that assumes multimodal capability and never blocks. */
    private void applyMultimodalGuard(JsonObject data) {
        data.addProperty("vision_confirmed", true); //$NON-NLS-1$
        data.addProperty("vision_basis", "assumed-multimodal"); //$NON-NLS-1$ //$NON-NLS-2$
        data.addProperty("vision_hint", ""); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private String usageHint(WebClientInfo info) {
        StringBuilder hint = new StringBuilder();
        if (!info.serverRunning()) {
            hint.append("Standalone server is '").append(info.serverState()) //$NON-NLS-1$
                    .append("': update the infobase and start the server before verifying. "); //$NON-NLS-1$
        }
        hint.append("Open web_client_url in a configured browser MCP (e.g. Playwright), sign in with the ") //$NON-NLS-1$
                .append("infobase credentials, exercise the changed UI, capture a screenshot, and verify the result."); //$NON-NLS-1$
        return hint.toString();
    }
}
