package com.codepilot1c.core.tools.workspace;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.IProject;

import com._1c.g5.v8.dt.platform.version.Version;
import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;
import com.codepilot1c.core.edt.runtime.EdtToolErrorCode;
import com.codepilot1c.core.edt.runtime.EdtToolException;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Returns the EDT platform version resolved for a workspace project.
 */
@ToolMeta(
        name = "edt_get_version",
        category = "workspace",
        mutating = false,
        tags = {"workspace", "edt", "project"})
public class GetEdtVersionTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(GetEdtVersionTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project_name": {
                  "type": "string",
                  "description": "EDT project name"
                }
              },
              "required": ["project_name"]
            }
            """; //$NON-NLS-1$

    private final EdtMetadataGateway gateway;

    public GetEdtVersionTool() {
        this(new EdtMetadataGateway());
    }

    public GetEdtVersionTool(EdtMetadataGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public String getDescription() {
        return "Возвращает версию EDT/platform для проекта workspace."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("edt-version"); //$NON-NLS-1$
            String projectName = ""; //$NON-NLS-1$
            LOG.info("[%s] START edt_get_version", opId); //$NON-NLS-1$
            try {
                projectName = params.requireString("project_name").trim(); //$NON-NLS-1$
                IProject project = resolveExistingProject(projectName);
                Version version = gateway.resolvePlatformVersion(project);
                JsonObject result = new JsonObject();
                result.addProperty("edt_version", version == null ? "" : version.toString()); //$NON-NLS-1$ //$NON-NLS-2$
                result.addProperty("project_name", projectName); //$NON-NLS-1$
                return ToolResult.success(pretty(result), ToolResult.ToolResultType.CODE, result);
            } catch (ToolParameters.ToolParameterException e) {
                LOG.warn("[%s] edt_get_version invalid argument: %s", opId, e.getMessage()); //$NON-NLS-1$
                return ToolResult.failure(pretty(errorPayload(projectName, EdtToolErrorCode.INVALID_ARGUMENT.name(),
                        e.getMessage())));
            } catch (EdtToolException e) {
                LOG.warn("[%s] edt_get_version failed: %s", opId, e.getMessage()); //$NON-NLS-1$
                return ToolResult.failure(pretty(errorPayload(projectName, e.getCode().name(), e.getMessage())));
            } catch (Exception e) {
                LOG.error("[" + opId + "] edt_get_version failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure(pretty(errorPayload(projectName, "GET_EDT_VERSION_FAILED", //$NON-NLS-1$
                        e.getMessage())));
            }
        });
    }

    private IProject resolveExistingProject(String projectName) {
        IProject project = gateway.resolveProject(projectName);
        if (project == null || !project.exists()) {
            throw new EdtToolException(EdtToolErrorCode.PROJECT_NOT_FOUND,
                    "EDT project not found: " + projectName); //$NON-NLS-1$
        }
        if (!project.isOpen()) {
            throw new EdtToolException(EdtToolErrorCode.EDT_NOT_READY,
                    "EDT project is not open: " + projectName); //$NON-NLS-1$
        }
        return project;
    }

    private static JsonObject errorPayload(String projectName, String code, String message) {
        JsonObject result = new JsonObject();
        result.addProperty("status", "error"); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("project_name", projectName == null ? "" : projectName); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("error_code", code == null ? "" : code); //$NON-NLS-1$ //$NON-NLS-2$
        result.addProperty("message", message == null ? "" : message); //$NON-NLS-1$ //$NON-NLS-2$
        return result;
    }

    private static String pretty(JsonObject object) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(object);
    }
}
