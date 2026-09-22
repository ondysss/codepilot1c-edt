package com.codepilot1c.core.tools.metadata;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.cmi.EdtCommandInterfaceService;
import com.codepilot1c.core.edt.cmi.EdtCommandInterfaceService.Snapshot;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ActiveProjectSupport;
import com.codepilot1c.core.tools.ToolExecutionContext;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Reads the command interface ({@code CommandInterface.cmi}) of a subsystem or of the configuration
 * root from the EDT model: visibility entries with their state, placement and order by command group.
 * Read-only.
 */
@ToolMeta(name = "inspect_command_interface", category = "metadata", tags = {"read-only", "workspace", "edt"})
public class InspectCommandInterfaceTool extends AbstractTool {

    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {"type": "string", "description": "EDT project. Optional: if omitted, the active editor project (or the single open project) is used."},
                "owner_fqn": {"type": "string", "description": "Owner of the command interface: Configuration, Subsystem.<Name> or a nested Subsystem.<Name>.Subsystem.<Child>. The Russian form printed by EDT diagnostics (Подсистема.X.Подсистема.Y.КомандныйИнтерфейс) is accepted."}
              },
              "required": ["owner_fqn"]
            }
            """; //$NON-NLS-1$

    private final EdtCommandInterfaceService service;

    public InspectCommandInterfaceTool() {
        this(new EdtCommandInterfaceService());
    }

    InspectCommandInterfaceTool(EdtCommandInterfaceService service) {
        this.service = service == null ? new EdtCommandInterfaceService() : service;
    }

    @Override
    public String getDescription() {
        return "Читает командный интерфейс подсистемы или конфигурации из модели EDT: видимость команд " //$NON-NLS-1$
                + "(записи без команды, платформенные команды 0:<uuid>), размещение и порядок; выводится ли владелец в интерфейс."; //$NON-NLS-1$
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
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params, ToolExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> parameters = params.getRaw();
            try {
                String projectName = resolveProjectName(parameters, context);
                if (projectName == null || projectName.isBlank()) {
                    return ToolResult.failure("project could not be resolved automatically. Open projects: " //$NON-NLS-1$
                            + ActiveProjectSupport.openProjectNames()
                            + ". Pass project explicitly, or open the target project in the EDT editor."); //$NON-NLS-1$
                }
                Snapshot snapshot = service.inspect(projectName, stringParam(parameters, "owner_fqn")); //$NON-NLS-1$
                JsonObject structured = GSON.toJsonTree(snapshot).getAsJsonObject();
                return ToolResult.success(GSON.toJson(snapshot), ToolResult.ToolResultType.SEARCH_RESULTS, structured);
            } catch (MetadataOperationException e) {
                return ToolResult.failure("[" + e.getCode() + "] " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (Exception e) {
                return ToolResult.failure("INTERNAL_ERROR: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private String resolveProjectName(Map<String, Object> parameters, ToolExecutionContext context) {
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
