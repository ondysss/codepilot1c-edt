package com.codepilot1c.core.tools.extension;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.extension.EdtExtensionService;
import com.codepilot1c.core.edt.extension.ExtensionSetPropertyStateRequest;
import com.codepilot1c.core.edt.extension.ExtensionSetPropertyStateResult;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.google.gson.Gson;

/**
 * Sets property state for adopted extension object property.
 */
@ToolMeta(name = "extension_set_property_state", category = "extension", mutating = true, tags = {"workspace", "edt"})
public class ExtensionSetPropertyStateTool extends AbstractTool {

    private static final Gson GSON = new Gson();

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {
                  "type": "string",
                  "description": "Имя базового EDT проекта (должно совпадать с base_project)"
                },
                "base_project": {
                  "type": "string",
                  "description": "Имя проекта основной конфигурации"
                },
                "extension_project": {
                  "type": "string",
                  "description": "Имя проекта расширения"
                },
                "source_object_fqn": {
                  "type": "string",
                  "description": "FQN объекта основной конфигурации"
                },
                "property_name": {
                  "type": "string",
                  "description": "Имя свойства объекта (EStructuralFeature)"
                },
                "state": {
                  "type": "string",
                  "enum": ["NONE", "CHECKED", "EXTENDED", "NOTIFY", "none", "checked", "extended", "notify"],
                  "description": "Новое состояние свойства расширения"
                },
                "validation_token": {
                  "type": "string",
                  "description": "Одноразовый токен из edt_validate_request"
                }
              },
              "required": ["project", "base_project", "extension_project", "source_object_fqn", "property_name", "state", "validation_token"]
            }
            """; //$NON-NLS-1$

    private final EdtExtensionService extensionService;
    private final MetadataRequestValidationService validationService;

    public ExtensionSetPropertyStateTool() {
        this(new EdtExtensionService(), new MetadataRequestValidationService());
    }

    ExtensionSetPropertyStateTool(EdtExtensionService extensionService, MetadataRequestValidationService validationService) {
        this.extensionService = extensionService;
        this.validationService = validationService;
    }

    @Override
    public String getDescription() {
        return "Устанавливает MdPropertyState для свойства адаптированного объекта расширения."; //$NON-NLS-1$
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
                String propertyName = asString(parameters.get("property_name")); //$NON-NLS-1$
                String state = asString(parameters.get("state")); //$NON-NLS-1$
                String validationToken = asString(parameters.get("validation_token")); //$NON-NLS-1$

                validationService.normalizeExtensionSetPropertyStatePayload(
                        project,
                        extensionProject,
                        baseProject,
                        sourceObjectFqn,
                        propertyName,
                        state);

                Map<String, Object> validatedPayload = validationService.consumeToken(
                        validationToken,
                        ValidationOperation.EXTENSION_SET_PROPERTY_STATE,
                        project);

                ExtensionSetPropertyStateRequest request = new ExtensionSetPropertyStateRequest(
                        asString(validatedPayload.get("extension_project")), //$NON-NLS-1$
                        asString(validatedPayload.get("base_project")), //$NON-NLS-1$
                        asString(validatedPayload.get("source_object_fqn")), //$NON-NLS-1$
                        asString(validatedPayload.get("property_name")), //$NON-NLS-1$
                        asString(validatedPayload.get("state"))); //$NON-NLS-1$
                ExtensionSetPropertyStateResult result = extensionService.setPropertyState(request);
                return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.CONFIRMATION);
            } catch (MetadataOperationException e) {
                return ToolResult.failure("[" + e.getCode() + "] " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (Exception e) {
                return ToolResult.failure("Ошибка extension_set_property_state: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
