package com.codepilot1c.core.tools;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.platformdoc.EdtPlatformDocumentationService;
import com.codepilot1c.core.edt.platformdoc.PlatformDocumentationException;
import com.codepilot1c.core.edt.platformdoc.PlatformDocumentationRequest;
import com.codepilot1c.core.edt.platformdoc.PlatformDocumentationResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Reads platform language documentation (types/methods/properties) from EDT mcore runtime.
 */
public class GetPlatformDocumentationTool implements ITool {

    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {"type": "string", "description": "Имя EDT проекта"},
                "type_name": {"type": "string", "description": "Имя/синоним типа платформы (например: DocumentObject). Если не задано, возвращается список кандидатов."},
                "language": {"type": "string", "enum": ["ru", "en"], "description": "Предпочитаемый язык имен"},
                "member_filter": {"type": "string", "enum": ["all", "methods", "properties"], "description": "Какие элементы вернуть"},
                "contains": {"type": "string", "description": "Фильтр по имени метода/свойства"},
                "limit": {"type": "integer", "description": "Лимит элементов на страницу (1..500, default=100)"},
                "offset": {"type": "integer", "description": "Смещение страницы (>=0)"}
              },
              "required": ["project"]
            }
            """; //$NON-NLS-1$

    private final EdtPlatformDocumentationService service;

    public GetPlatformDocumentationTool() {
        this(new EdtPlatformDocumentationService());
    }

    GetPlatformDocumentationTool(EdtPlatformDocumentationService service) {
        this.service = service;
    }

    @Override
    public String getName() {
        return "get_platform_documentation"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Возвращает встроенную справку EDT по типам платформы 1С: методы, свойства, параметры и типы возврата."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!service.isEdtAvailable()) {
                    return ToolResult.failure("{\"error\":\"EDT_SERVICE_UNAVAILABLE\",\"message\":\"EDT runtime services are unavailable\"}"); //$NON-NLS-1$
                }
                PlatformDocumentationRequest request = PlatformDocumentationRequest.fromParameters(parameters);
                PlatformDocumentationResult result = service.getDocumentation(request);
                return ToolResult.success(GSON.toJson(result));
            } catch (PlatformDocumentationException e) {
                return ToolResult.failure(toErrorJson(e));
            } catch (Exception e) {
                return ToolResult.failure("{\"error\":\"INTERNAL_ERROR\",\"message\":\"" //$NON-NLS-1$
                        + escapeJson(e.getMessage()) + "\"}"); //$NON-NLS-1$
            }
        });
    }

    private String toErrorJson(PlatformDocumentationException e) {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", e.getCode().name()); //$NON-NLS-1$
        obj.addProperty("message", e.getMessage()); //$NON-NLS-1$
        obj.addProperty("recoverable", e.isRecoverable()); //$NON-NLS-1$
        return GSON.toJson(obj);
    }

    private String escapeJson(String text) {
        if (text == null) {
            return "unknown"; //$NON-NLS-1$
        }
        return text.replace("\\", "\\\\").replace("\"", "\\\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
    }
}
