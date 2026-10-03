package com.codepilot1c.core.tools.workspace;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.core.runtime.NullProgressMonitor;
import com.codepilot1c.core.edt.runtime.EdtExtensionUpdateService;
import com.codepilot1c.core.edt.runtime.EdtExtensionUpdateService.Target;
import com.codepilot1c.core.edt.runtime.EdtToolErrorCode;
import com.codepilot1c.core.edt.runtime.EdtToolException;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/** Full reload of exactly one extension using EDT, with background completion tracking. */
@ToolMeta(name = "edt_update_extension", category = "diagnostics", surfaceCategory = "smoke_runtime_recovery",
        mutating = true, tags = {"workspace", "edt", "extension"})
public class EdtUpdateExtensionTool extends AbstractTool {
    private static final Set<String> ACTIVE_INFOBASES = ConcurrentHashMap.newKeySet();
    private final EdtExtensionUpdateService service;
    private final BackgroundJobRegistry registry;
    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "extension_project": {"type": "string", "description": "Exact EDT configuration extension project name"},
                "base_project": {"type": "string", "description": "Optional expected parent; must match EDT's extension parent"},
                "dry_run": {"type": "boolean", "description": "Resolve and report target without loading it into the infobase"},
                "async": {"type": "boolean", "description": "Run in background and return job_id for update_infobase_status (default true)"},
                "keep_connected": {"type": "boolean", "description": "Keep EDT synchronization connected after full reload (default false)"},
                "allow_conflict_override": {"type": "boolean", "description": "Explicitly permit overriding conflicting infobase extension changes (default false)"}
              },
              "required": ["extension_project"],
              "additionalProperties": false
            }
            """;

    public EdtUpdateExtensionTool() {
        this(new EdtExtensionUpdateService(), BackgroundJobRegistry.getInstance());
    }

    public EdtUpdateExtensionTool(EdtExtensionUpdateService service, BackgroundJobRegistry registry) {
        this.service = service;
        this.registry = registry;
    }

    @Override public String getDescription() {
        return "Полностью обновляет выбранное расширение в штатной базе его проекта через EDT reloadInfobase. "
                + "По умолчанию в фоне: итог получать через update_infobase_status. dry_run проверяет область обновления.";
    }
    @Override public String getParameterSchema() {return SCHEMA;}
    @Override public boolean requiresConfirmation() {return true;}
    @Override public boolean isDestructive() {return true;}

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> prepare(params.getRaw()));
    }

    private ToolResult prepare(Map<String, Object> params) {
        String extension = text(params.get("extension_project"));
        String base = text(params.get("base_project"));
        Target target;
        try {
            target = service.resolve(extension, base);
        } catch (EdtToolException e) {
            return failure(extension, base, e.getCode(), e.getMessage());
        } catch (Exception | LinkageError e) {
            return failure(extension, base, EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                    "Cannot resolve the extension target using EDT services; see the EDT log");
        }
        boolean dryRun = flag(params, "dry_run", false);
        boolean async = flag(params, "async", true);
        boolean keepConnected = flag(params, "keep_connected", false);
        boolean allowOverride = flag(params, "allow_conflict_override", false);
        if (dryRun) {
            JsonObject payload = payload(target, "dry_run", false);
            payload.addProperty("dry_run", true);
            payload.addProperty("async_ignored", async);
            return success(payload);
        }
        // Serialize extension loads into the same infobase, including queued async work.
        if (!ACTIVE_INFOBASES.add(target.binding())) {
            return failure(extension, target.base().getName(), EdtToolErrorCode.UPDATE_FAILED,
                    "An extension update for this infobase is already in progress");
        }
        if (!async) {
            return apply(target, keepConnected, allowOverride);
        }
        try {
            String id = registry.startJob("edt_update_extension", () -> {
                ToolResult result = apply(target, keepConnected, allowOverride);
                if (!result.isSuccess()) {
                    // The registry must mark failed EDT work FAILED, never DONE with an error result.
                    throw new IllegalStateException(result.getErrorMessage());
                }
                return result.getContent();
            });
            JsonObject payload = payload(target, "scheduled", false);
            payload.addProperty("async", true);
            payload.addProperty("job_id", id);
            payload.addProperty("state", registry.getStatus(id).orElseThrow().getState().name());
            return success(payload);
        } catch (RuntimeException e) {
            ACTIVE_INFOBASES.remove(target.binding());
            return failure(extension, target.base().getName(), EdtToolErrorCode.UPDATE_FAILED,
                    "The background update could not be scheduled; retry later");
        }
    }

    private ToolResult apply(Target target, boolean keepConnected, boolean allowOverride) {
        try {
            boolean updated = service.update(target, keepConnected, allowOverride, new NullProgressMonitor());
            if (!updated) {
                return failure(target.extension().getName(), target.base().getName(), EdtToolErrorCode.UPDATE_FAILED,
                        "EDT did not complete the extension reload (failed or cancelled)");
            }
            return success(payload(target, "updated", true));
        } catch (EdtToolException e) {
            return failure(target.extension().getName(), target.base().getName(), e.getCode(), e.getMessage());
        } catch (Exception | LinkageError e) {
            // EDT exceptions can contain connection strings and passwords; keep them out of MCP output.
            return failure(target.extension().getName(), target.base().getName(), EdtToolErrorCode.UPDATE_FAILED,
                    "EDT failed to reload the selected extension; see the EDT log");
        } finally {
            ACTIVE_INFOBASES.remove(target.binding());
        }
    }

    private static JsonObject payload(Target target, String status, boolean updated) {
        JsonObject result = new JsonObject();
        result.addProperty("status", status);
        result.addProperty("extension_project", target.extension().getName());
        result.addProperty("base_project", target.base().getName());
        result.addProperty("target_binding", target.binding());
        result.addProperty("engine", "EDT.reloadInfobase");
        result.addProperty("updated", updated);
        return result;
    }

    private static ToolResult failure(String extension, String base, EdtToolErrorCode code, String message) {
        JsonObject result = new JsonObject();
        result.addProperty("status", "error");
        result.addProperty("extension_project", extension);
        result.addProperty("base_project", base);
        result.addProperty("updated", false);
        result.addProperty("error_code", code.name());
        result.addProperty("message", message);
        return ToolResult.failure(pretty(result));
    }
    private static ToolResult success(JsonObject result) {
        return ToolResult.success(pretty(result), ToolResult.ToolResultType.CODE);
    }
    private static String pretty(JsonObject result) {return new GsonBuilder().setPrettyPrinting().create().toJson(result);}
    private static String text(Object value) {return value == null ? null : value.toString().trim();}
    private static boolean flag(Map<String, Object> params, String name, boolean defaultValue) {
        return EdtUpdateInfobaseTool.asBoolean(params.get(name), defaultValue);
    }
}
