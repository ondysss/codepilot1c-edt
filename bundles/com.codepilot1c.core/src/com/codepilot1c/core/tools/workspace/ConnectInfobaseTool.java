package com.codepilot1c.core.tools.workspace;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.runtime.EdtInfobaseConnectService;
import com.codepilot1c.core.edt.runtime.EdtInfobaseConnectService.ConnectRequest;
import com.codepilot1c.core.edt.runtime.EdtInfobaseConnectService.ConnectResult;
import com.codepilot1c.core.edt.runtime.EdtInfobaseConnectService.ConnectionKind;
import com.codepilot1c.core.edt.runtime.EdtToolErrorCode;
import com.codepilot1c.core.edt.runtime.EdtToolException;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Binds an infobase (file- or standalone-server-based) to an EDT project without user interaction.
 */
@ToolMeta(
        name = "connect_infobase",
        category = "workspace",
        mutating = true,
        tags = {"workspace", "edt"})
public class ConnectInfobaseTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(ConnectInfobaseTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project_name": {
                  "type": "string",
                  "description": "EDT project name to bind the infobase to"
                },
                "database_path": {
                  "type": "string",
                  "description": "Filesystem path for file-binding, or infobase data path for the standalone server"
                },
                "kind": {
                  "type": "string",
                  "enum": ["file", "standalone"],
                  "description": "Connection mode: 'file' for file-based infobase, 'standalone' for the local standalone server"
                },
                "login": {
                  "type": "string",
                  "description": "Optional infobase login (empty string means OS authentication)"
                },
                "password": {
                  "type": "string",
                  "description": "Optional infobase password (never echoed back in the result)"
                },
                "set_primary": {
                  "type": "boolean",
                  "description": "Make the bound infobase the project's primary infobase (default: true)"
                },
                "force": {
                  "type": "boolean",
                  "description": "Replace an existing primary infobase when set_primary=true (default: false)"
                },
                "server_port": {
                  "type": "integer",
                  "description": "Cluster port for 'standalone' kind (default: 1541)"
                },
                "runtime_version": {
                  "type": "string",
                  "description": "Optional 1C runtime version for 'standalone' kind (e.g. 8.3.24.1656)"
                }
              },
              "required": ["project_name", "database_path", "kind"]
            }
            """; //$NON-NLS-1$

    private final EdtInfobaseConnectService connectService;

    public ConnectInfobaseTool() {
        this(new EdtInfobaseConnectService());
    }

    public ConnectInfobaseTool(EdtInfobaseConnectService connectService) {
        this.connectService = connectService;
    }

    @Override
    public String getDescription() {
        return "Подключает файловую или standalone-инфобазу к EDT проекту и, по желанию, делает её основной."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public boolean requiresConfirmation() {
        return true;
    }

    @Override
    public boolean isDestructive() {
        return true;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> parameters = params.getRaw();
            String opId = LogSanitizer.newId("connect-ib"); //$NON-NLS-1$
            String projectName = asString(parameters == null ? null : parameters.get("project_name")); //$NON-NLS-1$
            String rawKind = asString(parameters == null ? null : parameters.get("kind")); //$NON-NLS-1$

            LOG.info("[%s] START connect_infobase project=%s kind=%s", opId, projectName, rawKind); //$NON-NLS-1$

            try {
                ConnectionKind kind = ConnectionKind.parse(rawKind);
                if (kind == null) {
                    throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                            "kind must be 'file' or 'standalone', got: " + rawKind); //$NON-NLS-1$
                }
                String databasePath = asString(parameters.get("database_path")); //$NON-NLS-1$
                String login = asString(parameters.get("login")); //$NON-NLS-1$
                String password = asRawString(parameters.get("password")); //$NON-NLS-1$
                boolean setPrimary = !Boolean.FALSE.equals(parameters.get("set_primary")); //$NON-NLS-1$
                boolean force = Boolean.TRUE.equals(parameters.get("force")); //$NON-NLS-1$
                Integer serverPort = asInteger(parameters.get("server_port")); //$NON-NLS-1$
                String runtimeVersion = asString(parameters.get("runtime_version")); //$NON-NLS-1$

                ConnectRequest request = new ConnectRequest(projectName, databasePath, kind, login,
                        password, setPrimary, serverPort, runtimeVersion, force);
                ConnectResult result = connectService.connect(request);
                JsonObject payload = successPayload(opId, projectName, result);
                return ToolResult.success(pretty(payload), ToolResult.ToolResultType.CODE);
            } catch (EdtToolException e) {
                return ToolResult.failure(pretty(errorPayload(opId, projectName, e.getCode(), e.getMessage())));
            } catch (IllegalStateException e) {
                return ToolResult.failure(pretty(errorPayload(opId, projectName,
                        EdtToolErrorCode.EDT_NOT_READY, e.getMessage())));
            } catch (Exception e) {
                return ToolResult.failure(pretty(errorPayload(opId, projectName,
                        EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE, e.getMessage())));
            }
        });
    }

    private static JsonObject successPayload(String opId, String projectName, ConnectResult result) {
        JsonObject json = new JsonObject();
        json.addProperty("op_id", opId); //$NON-NLS-1$
        json.addProperty("success", true); //$NON-NLS-1$
        json.addProperty("project", projectName); //$NON-NLS-1$
        json.addProperty("primary", result.primary()); //$NON-NLS-1$
        if (result.replacedPrevious() != null && !result.replacedPrevious().isBlank()) {
            json.addProperty("replaced_previous", result.replacedPrevious()); //$NON-NLS-1$
        }
        JsonObject infobase = new JsonObject();
        infobase.addProperty("kind", result.kind().name().toLowerCase()); //$NON-NLS-1$
        infobase.addProperty("path", result.resolvedPath()); //$NON-NLS-1$
        if (result.infobaseName() != null && !result.infobaseName().isBlank()) {
            infobase.addProperty("name", result.infobaseName()); //$NON-NLS-1$
        }
        if (result.login() != null) {
            infobase.addProperty("login", result.login()); //$NON-NLS-1$
        }
        if (result.serverPort() != null) {
            infobase.addProperty("port", result.serverPort().intValue()); //$NON-NLS-1$
        }
        json.add("infobase", infobase); //$NON-NLS-1$
        return json;
    }

    private static JsonObject errorPayload(String opId, String projectName, EdtToolErrorCode code, String message) {
        JsonObject json = new JsonObject();
        json.addProperty("op_id", opId); //$NON-NLS-1$
        json.addProperty("success", false); //$NON-NLS-1$
        json.addProperty("project", projectName == null ? "" : projectName); //$NON-NLS-1$ //$NON-NLS-2$
        EdtToolErrorCode effectiveCode = code == null ? EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE : code;
        json.addProperty("error_code", effectiveCode.name()); //$NON-NLS-1$
        String safeMessage = message == null ? "" : message; //$NON-NLS-1$
        json.addProperty("message", safeMessage); //$NON-NLS-1$
        switch (effectiveCode) {
            case PRIMARY_EXISTS -> {
                json.addProperty("error", "primary_exists"); //$NON-NLS-1$ //$NON-NLS-2$
                String currentPrimary = extractCurrentPrimary(safeMessage);
                if (currentPrimary != null) {
                    json.addProperty("current_primary", currentPrimary); //$NON-NLS-1$
                }
                json.addProperty("hint", "pass force=true to replace"); //$NON-NLS-1$ //$NON-NLS-2$
            }
            case INVALID_PATH -> {
                json.addProperty("error", "invalid_path"); //$NON-NLS-1$ //$NON-NLS-2$
                json.addProperty("hint", //$NON-NLS-1$
                        "database_path must be inside workspace or home directory"); //$NON-NLS-1$
            }
            case EDT_NOT_READY -> json.addProperty("error", "edt_not_ready"); //$NON-NLS-1$ //$NON-NLS-2$
            default -> { /* no extra shape */ }
        }
        return json;
    }

    /** Best-effort parse of the primary-exists marker embedded in the service message. */
    private static String extractCurrentPrimary(String message) {
        if (message == null) {
            return null;
        }
        String marker = "current_primary="; //$NON-NLS-1$
        int idx = message.indexOf(marker);
        if (idx < 0) {
            return null;
        }
        int start = idx + marker.length();
        int end = message.indexOf(',', start);
        String value = (end < 0 ? message.substring(start) : message.substring(start, end)).trim();
        return value.isEmpty() ? null : value;
    }

    private static String pretty(JsonObject object) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(object);
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    /** Returns the raw string value (NOT trimmed, NOT null-to-blank-collapsed), for passwords. */
    private static String asRawString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return Integer.valueOf(n.intValue());
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(Integer.parseInt(s));
        } catch (NumberFormatException e) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "Expected integer value, got: " + s); //$NON-NLS-1$
        }
    }
}
