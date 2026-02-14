package com.codepilot1c.core.tools;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.metadata.AddMetadataChildRequest;
import com.codepilot1c.core.edt.metadata.EdtMetadataService;
import com.codepilot1c.core.edt.metadata.MetadataChildKind;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.metadata.MetadataOperationResult;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Tool for creating nested metadata objects for different metadata owners.
 */
public class AddMetadataChildTool implements ITool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(AddMetadataChildTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {
                  "type": "string",
                  "description": "Имя проекта EDT"
                },
                "parent_fqn": {
                  "type": "string",
                  "description": "FQN родителя: <Type>.<Name>[.<Marker>.<Name>...]"
                },
                "child_kind": {
                  "type": "string",
                  "enum": ["Attribute", "Tabular_Section", "Command", "Form", "Template", "Dimension", "Resource", "Requisite"],
                  "description": "Тип создаваемого дочернего объекта"
                },
                "name": {
                  "type": "string",
                  "description": "Имя дочернего объекта"
                },
                "synonym": {
                  "type": "string",
                  "description": "Синоним"
                },
                "comment": {
                  "type": "string",
                  "description": "Комментарий"
                },
                "properties": {
                  "type": "object",
                  "description": "Дополнительные параметры. Для batch: children=[{name,synonym,comment}]"
                },
                "validation_token": {
                  "type": "string",
                  "description": "Одноразовый токен из edt_validate_request"
                }
              },
              "required": ["project", "parent_fqn", "child_kind", "name", "validation_token"]
            }
            """; //$NON-NLS-1$

    private final EdtMetadataService metadataService;
    private final MetadataRequestValidationService validationService;

    public AddMetadataChildTool() {
        this(new EdtMetadataService(), new MetadataRequestValidationService());
    }

    AddMetadataChildTool(EdtMetadataService metadataService, MetadataRequestValidationService validationService) {
        this.metadataService = metadataService;
        this.validationService = validationService;
    }

    @Override
    public String getName() {
        return "add_metadata_child"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Создает дочерние объекты метаданных (attribute/tabular section/command/form/template/dimension/resource/requisite)."; //$NON-NLS-1$
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
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("add-child"); //$NON-NLS-1$
            long startedAt = System.currentTimeMillis();
            LOG.info("[%s] START add_metadata_child", opId); //$NON-NLS-1$
            LOG.debug("[%s] Raw parameters: %s", opId, // $NON-NLS-1$
                    LogSanitizer.truncate(LogSanitizer.redactSecrets(String.valueOf(parameters)), 4000));
            try {
                String projectName = getString(parameters, "project"); //$NON-NLS-1$
                String parentFqn = getString(parameters, "parent_fqn"); //$NON-NLS-1$
                String childKindValue = getString(parameters, "child_kind"); //$NON-NLS-1$
                String name = getString(parameters, "name"); //$NON-NLS-1$
                String synonym = getOptionalString(parameters, "synonym"); //$NON-NLS-1$
                String comment = getOptionalString(parameters, "comment"); //$NON-NLS-1$
                Map<String, Object> properties = parameterMap(parameters.get("properties")); //$NON-NLS-1$
                String validationToken = getString(parameters, "validation_token"); //$NON-NLS-1$

                Map<String, Object> normalizedPayload = validationService.normalizeAddChildPayload(
                        projectName, parentFqn, childKindValue, name, synonym, comment, properties);
                LOG.debug("[%s] Normalized payload: %s", opId, // $NON-NLS-1$
                        LogSanitizer.truncate(LogSanitizer.redactSecrets(String.valueOf(normalizedPayload)), 4000));
                Map<String, Object> validatedPayload = validationService.consumeToken(
                        validationToken,
                        ValidationOperation.ADD_METADATA_CHILD,
                        projectName);
                LOG.debug("[%s] Validation token consumed successfully", opId); //$NON-NLS-1$
                LOG.debug("[%s] Validated payload from token: %s", opId, // $NON-NLS-1$
                        LogSanitizer.truncate(LogSanitizer.redactSecrets(String.valueOf(validatedPayload)), 4000));

                if (!validatedPayload.equals(normalizedPayload)) {
                    LOG.warn("[%s] Input payload differs from validated payload, applying validated payload from token", opId); //$NON-NLS-1$
                }
                MetadataChildKind childKind = MetadataChildKind.fromString(asRequiredString(validatedPayload, "child_kind")); //$NON-NLS-1$
                String validatedParentFqn = asRequiredString(validatedPayload, "parent_fqn"); //$NON-NLS-1$
                String validatedName = asRequiredString(validatedPayload, "name"); //$NON-NLS-1$
                String validatedSynonym = asOptionalString(validatedPayload, "synonym"); //$NON-NLS-1$
                String validatedComment = asOptionalString(validatedPayload, "comment"); //$NON-NLS-1$
                Map<String, Object> validatedProperties = parameterMap(validatedPayload.get("properties")); //$NON-NLS-1$
                AddMetadataChildRequest request = new AddMetadataChildRequest(
                        projectName, validatedParentFqn, childKind, validatedName, validatedSynonym, validatedComment, validatedProperties);
                LOG.info("[%s] Calling EdtMetadataService.addMetadataChild(project=%s, parent=%s, kind=%s, name=%s)", // $NON-NLS-1$
                        opId, projectName, validatedParentFqn, childKind, validatedName);
                MetadataOperationResult result = metadataService.addMetadataChild(request);
                LOG.info("[%s] SUCCESS in %s, fqn=%s", opId, // $NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        result.fqn());
                return ToolResult.success(result.formatForLlm());
            } catch (MetadataOperationException e) {
                LOG.warn("[%s] FAILED in %s: %s (%s)", opId, // $NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        e.getMessage(),
                        e.getCode());
                if (e.getCode() == com.codepilot1c.core.edt.metadata.MetadataOperationCode.EDT_TRANSACTION_FAILED) {
                    LOG.error("[" + opId + "] add_metadata_child EDT transaction error details", e); //$NON-NLS-1$ //$NON-NLS-2$
                } else if (e.getCode() == com.codepilot1c.core.edt.metadata.MetadataOperationCode.PROJECT_NOT_READY) {
                    LOG.error("[" + opId + "] add_metadata_child readiness error details", e); //$NON-NLS-1$ //$NON-NLS-2$
                }
                return ToolResult.failure("[" + e.getCode() + "] " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (Exception e) {
                LOG.error("[" + opId + "] add_metadata_child failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure("Ошибка add_metadata_child: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private String getString(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String getOptionalString(Map<String, Object> parameters, String key) {
        String value = getString(parameters, key);
        return value == null || value.isBlank() ? null : value;
    }

    private String asRequiredString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String asOptionalString(Map<String, Object> payload, String key) {
        String value = asRequiredString(payload, key);
        return value == null || value.isBlank() ? null : value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parameterMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Collections.emptyMap();
    }
}
