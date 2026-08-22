package com.codepilot1c.core.tools.metadata;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.rights.EdtRoleRightsService;
import com.codepilot1c.core.edt.rights.EdtRoleRightsService.RoleRightsSnapshot;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ActiveProjectSupport;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolExecutionContext;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Reads a 1C role's access rights (the EDT rights model): per-object rights with SET/UNSET/PROVIDED
 * values and RLS presence, the three default flags, and RLS template names. Read-only.
 */
@ToolMeta(name = "inspect_role_rights", category = "metadata", tags = {"read-only", "workspace", "edt"})
public class InspectRoleRightsTool extends AbstractTool {

    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {"type": "string", "description": "EDT project. Optional: if omitted, the active editor project (or the single open project) is used."},
                "role": {"type": "string", "description": "Role name or Role.<Name> FQN, e.g. ПолныеПрава or Role.ПолныеПрава."},
                "object_filter": {"type": "string", "description": "Optional case-insensitive substring; keeps only objects whose FQN matches, e.g. Catalog or Документ."}
              },
              "required": ["role"]
            }
            """; //$NON-NLS-1$

    private final EdtRoleRightsService service;

    public InspectRoleRightsTool() {
        this(new EdtRoleRightsService());
    }

    InspectRoleRightsTool(EdtRoleRightsService service) {
        this.service = service == null ? new EdtRoleRightsService() : service;
    }

    @Override
    public String getDescription() {
        return "Читает права роли 1С: права по объектам (SET/UNSET/PROVIDED), RLS и флаги по умолчанию."; //$NON-NLS-1$
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
            Map<String, Object> parameters = params.getRaw();
            try {
                String projectName = resolveProjectName(parameters, context);
                if (projectName == null || projectName.isBlank()) {
                    return ToolResult.failure("project could not be resolved automatically. Open projects: " //$NON-NLS-1$
                            + ActiveProjectSupport.openProjectNames()
                            + ". Pass project explicitly, or open the target project in the EDT editor."); //$NON-NLS-1$
                }
                String role = stringParam(parameters, "role"); //$NON-NLS-1$
                String objectFilter = stringParam(parameters, "object_filter"); //$NON-NLS-1$

                RoleRightsSnapshot snapshot = service.inspectRoleRights(projectName, role, objectFilter);
                JsonObject structured = GSON.toJsonTree(snapshot).getAsJsonObject();
                return ToolResult.success(GSON.toJson(snapshot), ToolResult.ToolResultType.SEARCH_RESULTS, structured);
            } catch (MetadataOperationException e) {
                return ToolResult.failure("[" + e.getCode() + "] " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (Exception e) {
                return ToolResult.failure("INTERNAL_ERROR: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private String resolveProjectName(
            Map<String, Object> parameters, ToolExecutionContext context) {
        String explicit = stringParam(parameters, "project"); //$NON-NLS-1$
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return ActiveProjectSupport.resolveActiveProjectName(context);
    }

    private String stringParam(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }
}
