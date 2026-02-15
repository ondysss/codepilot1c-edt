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
                "type_name": {"type": "string", "description": "Имя/синоним типа платформы (например: DocumentObject). Не передавай сюда имя метода/свойства."},
                "language": {"type": "string", "enum": ["ru", "en"], "description": "Предпочитаемый язык имен"},
                "member_filter": {"type": "string", "enum": ["all", "methods", "properties"], "description": "Какие элементы вернуть"},
                "contains": {"type": "string", "description": "Фильтр по имени метода/свойства (например: ПоказатьВопросПользователю)"},
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
        return "inspect_platform_reference"; //$NON-NLS-1$
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
                    return ToolResult.failure(toErrorJson(
                            "EDT_SERVICE_UNAVAILABLE", //$NON-NLS-1$
                            "EDT runtime services are unavailable", //$NON-NLS-1$
                            true));
                }
                PlatformDocumentationRequest request = PlatformDocumentationRequest.fromParameters(parameters);
                PlatformDocumentationResult result = service.getDocumentation(request);
                return ToolResult.success(GSON.toJson(result));
            } catch (PlatformDocumentationException e) {
                return ToolResult.failure(toErrorJson(e));
            } catch (Exception e) {
                return ToolResult.failure(toErrorJson(
                        "INTERNAL_ERROR", //$NON-NLS-1$
                        e.getMessage(),
                        false));
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

    private String toErrorJson(String code, String message, boolean recoverable) {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", code); //$NON-NLS-1$
        obj.addProperty("message", message == null || message.isBlank() ? "unknown" : message); //$NON-NLS-1$ //$NON-NLS-2$
        obj.addProperty("recoverable", recoverable); //$NON-NLS-1$
        return GSON.toJson(obj);
    }
}
