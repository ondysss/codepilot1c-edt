package com.codepilot1c.core.tools.workspace;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.concurrent.CompletableFuture;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;

import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;
import com.codepilot1c.core.edt.runtime.EdtToolErrorCode;
import com.codepilot1c.core.edt.runtime.EdtToolException;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Refreshes a workspace project from disk and clears problem markers.
 */
@ToolMeta(
        name = "edt_clean_project",
        category = "workspace",
        mutating = true,
        tags = {"workspace", "edt", "project"})
public class CleanProjectTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(CleanProjectTool.class);

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

    public CleanProjectTool() {
        this(new EdtMetadataGateway());
    }

    public CleanProjectTool(EdtMetadataGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public String getDescription() {
        return "Обновляет EDT проект с диска и очищает problem markers."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("edt-clean"); //$NON-NLS-1$
            String projectName = ""; //$NON-NLS-1$
            LOG.info("[%s] START edt_clean_project", opId); //$NON-NLS-1$
            try {
                projectName = params.requireString("project_name").trim(); //$NON-NLS-1$
                IProject project = resolveExistingProject(projectName);
                project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
                project.deleteMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
                JsonObject result = resultPayload("success", projectName, //$NON-NLS-1$
                        "Project refreshed from disk and problem markers cleared"); //$NON-NLS-1$
                return ToolResult.success(pretty(result), ToolResult.ToolResultType.CONFIRMATION, result);
            } catch (ToolParameters.ToolParameterException e) {
                LOG.warn("[%s] edt_clean_project invalid argument: %s", opId, e.getMessage()); //$NON-NLS-1$
                JsonObject error = errorPayload(projectName, EdtToolErrorCode.INVALID_ARGUMENT.name(), e.getMessage());
                return ToolResult.failure(pretty(error));
            } catch (EdtToolException e) {
                LOG.warn("[%s] edt_clean_project failed: %s", opId, e.getMessage()); //$NON-NLS-1$
                JsonObject error = errorPayload(projectName, e.getCode().name(), e.getMessage());
                return ToolResult.failure(pretty(error));
            } catch (CoreException e) {
                LOG.error("[" + opId + "] edt_clean_project workspace operation failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                JsonObject error = errorPayload(projectName, "PROJECT_CLEAN_FAILED", e.getMessage()); //$NON-NLS-1$
                return ToolResult.failure(pretty(error));
            } catch (Exception e) {
                LOG.error("[" + opId + "] edt_clean_project failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                JsonObject error = errorPayload(projectName, "PROJECT_CLEAN_FAILED", e.getMessage()); //$NON-NLS-1$
                return ToolResult.failure(pretty(error));
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

    private static JsonObject resultPayload(String status, String projectName, String message) {
        JsonObject result = new JsonObject();
        result.addProperty("status", status); //$NON-NLS-1$
        result.addProperty("project_name", projectName); //$NON-NLS-1$
        result.addProperty("message", message); //$NON-NLS-1$
        return result;
    }

    private static JsonObject errorPayload(String projectName, String code, String message) {
        JsonObject result = resultPayload("error", projectName == null ? "" : projectName, //$NON-NLS-1$ //$NON-NLS-2$
                message == null ? "" : message); //$NON-NLS-1$
        result.addProperty("error_code", code == null ? "" : code); //$NON-NLS-1$ //$NON-NLS-2$
        return result;
    }

    private static String pretty(JsonObject object) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(object);
    }
}
