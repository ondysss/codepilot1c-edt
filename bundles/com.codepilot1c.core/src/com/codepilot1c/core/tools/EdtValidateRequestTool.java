package com.codepilot1c.core.tools;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.edt.validation.ValidationRequest;
import com.codepilot1c.core.edt.validation.ValidationResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Validates metadata mutation request and returns a short-lived validation token.
 */
public class EdtValidateRequestTool implements ITool {

    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {
                  "type": "string",
                  "description": "Имя EDT проекта"
                },
                "operation": {
                  "type": "string",
                  "enum": ["create_metadata", "create_form", "add_metadata_child", "update_metadata", "delete_metadata", "mutate_form_model"],
                  "description": "Целевая мутационная операция"
                },
                "payload": {
                  "type": "object",
                  "description": "Параметры целевой операции без validation_token"
                }
              },
              "required": ["project", "operation", "payload"]
            }
            """; //$NON-NLS-1$

    private final MetadataRequestValidationService service;

    public EdtValidateRequestTool() {
        this(new MetadataRequestValidationService());
    }

    EdtValidateRequestTool(MetadataRequestValidationService service) {
        this.service = service;
    }

    @Override
    public String getName() {
        return "edt_validate_request"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Проверяет запрос на мутацию метаданных и выдает одноразовый validation_token."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    @SuppressWarnings("unchecked")
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String project = stringParam(parameters, "project"); //$NON-NLS-1$
                ValidationOperation operation = ValidationOperation.fromString(stringParam(parameters, "operation")); //$NON-NLS-1$
                Object payloadObject = parameters.get("payload"); //$NON-NLS-1$
                if (!(payloadObject instanceof Map<?, ?> payloadMap)) {
                    return ToolResult.failure(errorJson("KNOWLEDGE_REQUIRED", "payload must be an object", false)); //$NON-NLS-1$ //$NON-NLS-2$
                }

                ValidationRequest request = new ValidationRequest(project, operation, (Map<String, Object>) payloadMap);
                ValidationResult result = service.validateAndIssueToken(request);
                return ToolResult.success(GSON.toJson(result));
            } catch (MetadataOperationException e) {
                return ToolResult.failure(errorJson(e.getCode().name(), e.getMessage(), e.isRecoverable()));
            } catch (Exception e) {
                return ToolResult.failure(errorJson("INTERNAL_ERROR", e.getMessage(), false)); //$NON-NLS-1$
            }
        });
    }

    private String stringParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String errorJson(String code, String message, boolean recoverable) {
        JsonObject obj = new JsonObject();
        obj.addProperty("error", code); //$NON-NLS-1$
        obj.addProperty("message", message); //$NON-NLS-1$
        obj.addProperty("recoverable", recoverable); //$NON-NLS-1$
        return GSON.toJson(obj);
    }
}
