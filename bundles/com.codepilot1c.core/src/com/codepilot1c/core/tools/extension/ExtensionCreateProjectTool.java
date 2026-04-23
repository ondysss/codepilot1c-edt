package com.codepilot1c.core.tools.extension;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.extension.EdtExtensionService;
import com.codepilot1c.core.edt.extension.ExtensionCreateProjectRequest;
import com.codepilot1c.core.edt.extension.ExtensionCreateProjectResult;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.google.gson.Gson;

/**
 * Creates a new configuration extension project based on an existing EDT project.
 */
@ToolMeta(name = "extension_create_project", category = "extension", mutating = true, tags = {"workspace", "edt"})
public class ExtensionCreateProjectTool extends AbstractTool {

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
                  "description": "Имя создаваемого проекта расширения"
                },
                "project_path": {
                  "type": "string",
                  "description": "Путь для создаваемого проекта (опционально)"
                },
                "version": {
                  "type": "string",
                  "description": "Версия платформы (например 8.3.25), по умолчанию берется из base_project"
                },
                "configuration_name": {
                  "type": "string",
                  "description": "Имя корневого объекта Configuration в расширении"
                },
                "purpose": {
                  "type": "string",
                  "enum": ["ADD_ON", "CUSTOMIZATION", "PATCH", "add_on", "customization", "patch"],
                  "description": "Назначение расширения (по умолчанию ADD_ON)"
                },
                "compatibility_mode": {
                  "type": "string",
                  "description": "Compatibility mode, например VERSION8_325 или 8.3.25"
                },
                "validation_token": {
                  "type": "string",
                  "description": "Одноразовый токен из edt_validate_request"
                }
              },
              "required": ["project", "base_project", "extension_project", "validation_token"]
            }
            """; //$NON-NLS-1$

    private final EdtExtensionService extensionService;
    private final MetadataRequestValidationService validationService;

    public ExtensionCreateProjectTool() {
        this(new EdtExtensionService(), new MetadataRequestValidationService());
    }

    ExtensionCreateProjectTool(EdtExtensionService extensionService, MetadataRequestValidationService validationService) {
        this.extensionService = extensionService;
        this.validationService = validationService;
    }

    @Override
    public String getDescription() {
        return "Создает проект расширения конфигурации EDT на основе основной конфигурации."; //$NON-NLS-1$
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
                String projectPath = asOptionalString(parameters.get("project_path")); //$NON-NLS-1$
                String version = asOptionalString(parameters.get("version")); //$NON-NLS-1$
                String configurationName = asOptionalString(parameters.get("configuration_name")); //$NON-NLS-1$
                String purpose = asOptionalString(parameters.get("purpose")); //$NON-NLS-1$
                String compatibilityMode = asOptionalString(parameters.get("compatibility_mode")); //$NON-NLS-1$
                String validationToken = asString(parameters.get("validation_token")); //$NON-NLS-1$

                validationService.normalizeExtensionCreateProjectPayload(
                        project,
                        extensionProject,
                        baseProject,
                        projectPath,
                        version,
                        configurationName,
                        purpose,
                        compatibilityMode);

                Map<String, Object> validatedPayload = validationService.consumeToken(
                        validationToken,
                        ValidationOperation.EXTENSION_CREATE_PROJECT,
                        project);

                ExtensionCreateProjectRequest request = new ExtensionCreateProjectRequest(
                        asString(validatedPayload.get("base_project")), //$NON-NLS-1$
                        asString(validatedPayload.get("extension_project")), //$NON-NLS-1$
                        asOptionalString(validatedPayload.get("project_path")), //$NON-NLS-1$
                        asOptionalString(validatedPayload.get("version")), //$NON-NLS-1$
                        asOptionalString(validatedPayload.get("configuration_name")), //$NON-NLS-1$
                        asOptionalString(validatedPayload.get("purpose")), //$NON-NLS-1$
                        asOptionalString(validatedPayload.get("compatibility_mode"))); //$NON-NLS-1$
                ExtensionCreateProjectResult result = extensionService.createProject(request);
                return ToolResult.success(GSON.toJson(result), ToolResult.ToolResultType.CONFIRMATION);
            } catch (MetadataOperationException e) {
                return ToolResult.failure("[" + e.getCode() + "] " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (Exception e) {
                return ToolResult.failure("Ошибка extension_create_project: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String asOptionalString(Object value) {
        if (value == null) {
            return null;
        }
        String raw = String.valueOf(value).trim();
        return raw.isEmpty() ? null : raw;
    }
}
