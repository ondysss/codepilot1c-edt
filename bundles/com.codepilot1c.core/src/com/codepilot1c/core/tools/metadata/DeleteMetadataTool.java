package com.codepilot1c.core.tools.metadata;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.metadata.DeleteMetadataRequest;
import com.codepilot1c.core.edt.metadata.EdtMetadataService;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.metadata.MetadataOperationResult;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Tool for metadata deletion via BM API.
 */
@ToolMeta(name = "delete_metadata", category = "metadata", mutating = true, requiresValidationToken = true, tags = {"workspace", "edt"})
public class DeleteMetadataTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(DeleteMetadataTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {
                  "type": "string",
                  "description": "Имя EDT проекта, где существует удаляемый объект"
                },
                "target_fqn": {
                  "type": "string",
                  "description": "FQN существующего объекта или дочернего элемента, который нужно удалить"
                },
                "recursive": {
                  "type": "boolean",
                  "description": "Разрешить удаление с вложенными объектами"
                },
                "force": {
                  "type": "boolean",
                  "description": "Технический override: игнорировать проверки ссылок/рефакторинга и удалить принудительно"
                },
                "validation_token": {
                  "type": "string",
                  "description": "Одноразовый токен из edt_validate_request для operation=delete_metadata; передавать без изменений"
                }
              },
              "required": ["project", "target_fqn", "validation_token"]
            }
            """; //$NON-NLS-1$

    private final EdtMetadataService metadataService;
    private final MetadataRequestValidationService validationService;

    public DeleteMetadataTool() {
        this(new EdtMetadataService(), new MetadataRequestValidationService());
    }

    DeleteMetadataTool(EdtMetadataService metadataService, MetadataRequestValidationService validationService) {
        this.metadataService = metadataService;
        this.validationService = validationService;
    }

    @Override
    public String getDescription() {
        return "Удаляет существующий metadata object или child element через EDT BM API после edt_validate_request. Используй только когда действительно нужен delete, а не update_metadata. После удаления обязательно проверь diagnostics и impact на ссылки и формы."; //$NON-NLS-1$
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
            String opId = LogSanitizer.newId("delete-md"); //$NON-NLS-1$
            long startedAt = System.currentTimeMillis();
            LOG.info("[%s] START delete_metadata", opId); //$NON-NLS-1$
            LOG.debug("[%s] Raw parameters: %s", opId, // $NON-NLS-1$
                    LogSanitizer.truncate(LogSanitizer.redactSecrets(String.valueOf(parameters)), 4000));
            try {
                String projectName = getString(parameters, "project"); //$NON-NLS-1$
                String targetFqn = getString(parameters, "target_fqn"); //$NON-NLS-1$
                boolean recursive = asBoolean(parameters.get("recursive")); //$NON-NLS-1$
                boolean force = asBoolean(parameters.get("force")); //$NON-NLS-1$
                String validationToken = getString(parameters, "validation_token"); //$NON-NLS-1$

                Map<String, Object> normalizedPayload = validationService.normalizeDeletePayload(
                        projectName, targetFqn, recursive, force);
                Map<String, Object> validatedPayload = validationService.consumeToken(
                        validationToken,
                        ValidationOperation.DELETE_METADATA,
                        projectName);

                if (!validatedPayload.equals(normalizedPayload)) {
                    LOG.warn("[%s] Input payload differs from validated payload, applying validated payload from token", opId); //$NON-NLS-1$
                }
                String validatedTargetFqn = asRequiredString(validatedPayload, "target_fqn"); //$NON-NLS-1$
                boolean validatedRecursive = asBoolean(validatedPayload.get("recursive")); //$NON-NLS-1$
                boolean validatedForce = asBoolean(validatedPayload.get("force")); //$NON-NLS-1$

                DeleteMetadataRequest request = new DeleteMetadataRequest(
                        projectName, validatedTargetFqn, validatedRecursive, validatedForce);
                MetadataOperationResult result = metadataService.deleteMetadata(request);
                LOG.info("[%s] SUCCESS in %s, fqn=%s", opId, // $NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        result.fqn());
                return ToolResult.success(result.formatForLlm());
            } catch (MetadataOperationException e) {
                LOG.warn("[%s] FAILED in %s: %s (%s)", opId, // $NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        e.getMessage(),
                        e.getCode());
                return ToolResult.failure("[" + e.getCode() + "] " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (Exception e) {
                LOG.error("[" + opId + "] delete_metadata failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure("Ошибка delete_metadata: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private String getString(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String asRequiredString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool.booleanValue();
        }
        if (value == null) {
            return false;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
