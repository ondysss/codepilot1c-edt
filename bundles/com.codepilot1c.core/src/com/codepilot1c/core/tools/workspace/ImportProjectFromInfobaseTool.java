package com.codepilot1c.core.tools.workspace;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.imports.EdtProjectImportService;
import com.codepilot1c.core.edt.imports.ImportProjectFromInfobaseRequest;
import com.codepilot1c.core.edt.imports.ImportProjectFromInfobaseResult;
import com.codepilot1c.core.edt.runtime.EdtToolException;
import com.codepilot1c.core.logging.LogSanitizer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Imports a new EDT project from configuration exported from an associated infobase.
 */
@ToolMeta(name = "import_project_from_infobase", category = "workspace", mutating = true, tags = {"workspace", "edt"})
public class ImportProjectFromInfobaseTool extends AbstractTool {

    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "source_project_name": {
                  "type": "string",
                  "description": "Исходный EDT проект, через который резолвятся связанная инфобаза, runtime и доступ"
                },
                "target_project_name": {
                  "type": "string",
                  "description": "Имя нового EDT проекта, который будет создан импортом из инфобазы"
                },
                "project_path": {
                  "type": "string",
                  "description": "Абсолютный путь создаваемого проекта; если задан, должен оканчиваться target_project_name"
                },
                "version": {
                  "type": "string",
                  "description": "Версия платформы для целевого проекта; по умолчанию берется из source_project_name"
                },
                "base_project_name": {
                  "type": "string",
                  "description": "Базовый проект для import API (опционально, например для импортов поверх базы)"
                },
                "start_server": {
                  "type": "boolean",
                  "description": "Запускать созданный standalone server (default: true)"
                },
                "cluster_port": {
                  "type": "integer",
                  "description": "Порт standalone cluster (default: 1541)"
                },
                "cluster_registry_directory": {
                  "type": "string",
                  "description": "Директория cluster registry для standalone server (default: .codepilot/imports/<opId>/cluster-registry)"
                },
                "publication_path": {
                  "type": "string",
                  "description": "Директория publication для standalone server (default: .codepilot/imports/<opId>/publication)"
                },
                "dry_run": {
                  "type": "boolean",
                  "description": "Проверить разрешение runtime/infobase и спланировать пути без экспорта и импорта"
                },
                "diagnostics_wait_ms": {
                  "type": "integer",
                  "description": "Ожидание перед сбором diagnostics после импорта (default: 1500)"
                },
                "diagnostics_max_items": {
                  "type": "integer",
                  "description": "Максимум элементов diagnostics в ответе (default: 100)"
                }
              },
              "required": ["source_project_name", "target_project_name"]
            }
            """; //$NON-NLS-1$

    private final EdtProjectImportService importService;

    public ImportProjectFromInfobaseTool() {
        this(new EdtProjectImportService());
    }

    public ImportProjectFromInfobaseTool(EdtProjectImportService importService) {
        this.importService = importService;
    }

    @Override
    public String getDescription() {
        return "Создает новый EDT project из связанной инфобазы: поднимает standalone server, выгружает конфигурацию и выполняет import. Используй, когда источник истины сейчас в базе, а не в локальном проекте или git-репозитории."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> parameters = params.getRaw();
            String opId = LogSanitizer.newId("edt-import-infobase"); //$NON-NLS-1$
            try {
                ImportProjectFromInfobaseRequest request = new ImportProjectFromInfobaseRequest(
                        string(parameters, "source_project_name"), //$NON-NLS-1$
                        string(parameters, "target_project_name"), //$NON-NLS-1$
                        string(parameters, "project_path"), //$NON-NLS-1$
                        string(parameters, "version"), //$NON-NLS-1$
                        string(parameters, "base_project_name"), //$NON-NLS-1$
                        parameters == null || !Boolean.FALSE.equals(parameters.get("start_server")), //$NON-NLS-1$
                        integer(parameters, "cluster_port"), //$NON-NLS-1$
                        string(parameters, "cluster_registry_directory"), //$NON-NLS-1$
                        string(parameters, "publication_path"), //$NON-NLS-1$
                        parameters != null && Boolean.TRUE.equals(parameters.get("dry_run")), //$NON-NLS-1$
                        intOrDefault(parameters, "diagnostics_wait_ms", 1500), //$NON-NLS-1$
                        intOrDefault(parameters, "diagnostics_max_items", 100)); //$NON-NLS-1$
                ImportProjectFromInfobaseResult result = importService.importProject(opId, request);
                return ToolResult.success(pretty(GSON.toJsonTree(result).getAsJsonObject()),
                        ToolResult.ToolResultType.CONFIRMATION);
            } catch (EdtToolException e) {
                return ToolResult.failure(pretty(error(opId, e.getCode().name(), e.getMessage())));
            } catch (RuntimeException e) {
                return ToolResult.failure(pretty(error(opId, "PROJECT_IMPORT_FAILED", e.getMessage()))); //$NON-NLS-1$
            }
        });
    }

    private static JsonObject error(String opId, String code, String message) {
        JsonObject json = new JsonObject();
        json.addProperty("op_id", opId); //$NON-NLS-1$
        json.addProperty("status", "error"); //$NON-NLS-1$ //$NON-NLS-2$
        json.addProperty("error_code", code == null ? "" : code); //$NON-NLS-1$ //$NON-NLS-2$
        json.addProperty("message", message == null ? "" : message); //$NON-NLS-1$ //$NON-NLS-2$
        return json;
    }

    private static String pretty(JsonObject object) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(object);
    }

    private static String string(Map<String, Object> parameters, String key) {
        if (parameters == null) {
            return null;
        }
        Object value = parameters.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    private static Integer integer(Map<String, Object> parameters, String key) {
        if (parameters == null) {
            return null;
        }
        Object value = parameters.get(key);
        if (value instanceof Number number) {
            return Integer.valueOf(number.intValue());
        }
        if (value == null) {
            return null;
        }
        String raw = String.valueOf(value).trim();
        return raw.isEmpty() ? null : Integer.valueOf(raw);
    }

    private static int intOrDefault(Map<String, Object> parameters, String key, int defaultValue) {
        Integer value = integer(parameters, key);
        return value == null ? defaultValue : value.intValue();
    }
}
