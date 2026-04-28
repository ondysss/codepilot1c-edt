package com.codepilot1c.core.tools.extension;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.extension.EdtExtensionService;
import com.codepilot1c.core.edt.extension.ExtensionAdoptObjectRequest;
import com.codepilot1c.core.edt.extension.ExtensionAdoptObjectResult;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.google.gson.Gson;

/**
 * Adopts object from base configuration into extension project.
 */
@ToolMeta(name = "extension_adopt_object", category = "extension", mutating = true, tags = {"workspace", "edt"})
public class ExtensionAdoptObjectTool extends AbstractTool {

    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {
                  "type": "string",
                  "description": "Имя EDT проекта основной конфигурации. Это validation scope; должно совпадать с base_project."
                },
                "base_project": {
                  "type": "string",
                  "description": "Имя EDT проекта основной конфигурации, где находится source_object_fqn."
                },
                "extension_project": {
                  "type": "string",
                  "description": "Имя EDT проекта расширения, куда заимствуется объект. Не равно project/base_project."
                },
                "source_object_fqn": {
                  "type": "string",
                  "description": "FQN существующего объекта основной конфигурации, например Catalog.Items."
                },
                "update_if_exists": {
                  "type": "boolean",
                  "description": "Если true и объект уже заимствован, обновить adopted object вместо ошибки."
                },
                "validation_token": {
                  "type": "string",
                  "description": "Одноразовый токен из edt_validate_request для operation=extension_adopt_object и того же payload."
                }
              },
              "required": ["project", "base_project", "extension_project", "source_object_fqn", "validation_token"]
            }
            """; //$NON-NLS-1$

    private final EdtExtensionService extensionService;
    private final MetadataRequestValidationService validationService;

    public ExtensionAdoptObjectTool() {
        this(new EdtExtensionService(), new MetadataRequestValidationService());
    }

    ExtensionAdoptObjectTool(EdtExtensionService extensionService, MetadataRequestValidationService validationService) {
        this.extensionService = extensionService;
        this.validationService = validationService;
    }

    @Override
    public String getDescription() {
        return "Заимствует существующий объект базовой конфигурации в EDT-проект расширения. Не создаёт новый объект и требует validation_token."; //$NON-NLS-1$
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
            try {
                String project = asString(parameters.get("project")); //$NON-NLS-1$
                String baseProject = asString(parameters.get("base_project")); //$NON-NLS-1$
                String extensionProject = asString(parameters.get("extension_project")); //$NON-NLS-1$
                String sourceObjectFqn = asString(parameters.get("source_object_fqn")); //$NON-NLS-1$
                Boolean updateIfExists = asOptionalBoolean(parameters.get("update_if_exists")); //$NON-NLS-1$
                String validationToken = asString(parameters.get("validation_token")); //$NON-NLS-1$

                validationService.normalizeExtensionAdoptPayload(
                        project,
                        extensionProject,
                        baseProject,
                        sourceObjectFqn,
                        updateIfExists);

                Map<String, Object> validatedPayload = validationService.consumeToken(
                        validationToken,
                        ValidationOperation.EXTENSION_ADOPT_OBJECT,
                        project);

                ExtensionAdoptObjectRequest request = new ExtensionAdoptObjectRequest(
                        asString(validatedPayload.get("base_project")), //$NON-NLS-1$
                        asString(validatedPayload.get("extension_project")), //$NON-NLS-1$
                        asString(validatedPayload.get("source_object_fqn")), //$NON-NLS-1$
                        asOptionalBoolean(validatedPayload.get("update_if_exists"))); //$NON-NLS-1$
                ExtensionAdoptObjectResult result = extensionService.adoptObject(request);
                return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.CONFIRMATION);
            } catch (MetadataOperationException e) {
                return ToolResult.failure("[" + e.getCode() + "] " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (Exception e) {
                return ToolResult.failure("Ошибка extension_adopt_object: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Boolean asOptionalBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return null;
        }
        return Boolean.valueOf(Boolean.parseBoolean(text));
    }
}
