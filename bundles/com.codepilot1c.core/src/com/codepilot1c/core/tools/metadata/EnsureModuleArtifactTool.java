package com.codepilot1c.core.tools.metadata;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.metadata.EdtMetadataService;
import com.codepilot1c.core.edt.metadata.EnsureModuleArtifactRequest;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.metadata.ModuleArtifactKind;
import com.codepilot1c.core.edt.metadata.ModuleArtifactResult;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Ensures module artifact (*.bsl) exists for a metadata object.
 */
@ToolMeta(name = "ensure_module_artifact", category = "metadata", mutating = true, tags = {"workspace", "edt"})
public class EnsureModuleArtifactTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(EnsureModuleArtifactTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {
                  "type": "string",
                  "description": "Имя проекта EDT"
                },
                "object_fqn": {
                  "type": "string",
                  "description": "FQN объекта метаданных, например Document.ПриходнаяНакладная"
                },
                "module_kind": {
                  "type": "string",
                  "description": "Тип модуля: auto|object|manager|module"
                },
                "create_if_missing": {
                  "type": "boolean",
                  "description": "Создать файл модуля, если отсутствует (по умолчанию true)"
                },
                "initial_content": {
                  "type": "string",
                  "description": "Начальное содержимое файла при создании (опционально)"
                },
                "validation_token": {
                  "type": "string",
                  "description": "Одноразовый токен из edt_validate_request"
                }
              },
              "required": ["project", "object_fqn", "validation_token"]
            }
            """; //$NON-NLS-1$

    private final EdtMetadataService metadataService;
    private final MetadataRequestValidationService validationService;

    public EnsureModuleArtifactTool() {
        this(new EdtMetadataService(), new MetadataRequestValidationService());
    }

    EnsureModuleArtifactTool(EdtMetadataService metadataService, MetadataRequestValidationService validationService) {
        this.metadataService = metadataService;
        this.validationService = validationService;
    }

    @Override
    public String getDescription() {
        return "Гарантирует наличие файла модуля (*.bsl) для объекта метаданных после pre-validation и возвращает путь для последующего редактирования."; //$NON-NLS-1$
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
            String opId = LogSanitizer.newId("ensure-module"); //$NON-NLS-1$
            long startedAt = System.currentTimeMillis();
            LOG.info("[%s] START ensure_module_artifact", opId); //$NON-NLS-1$
            LOG.debug("[%s] Raw parameters: %s", opId, //$NON-NLS-1$
                    LogSanitizer.truncate(LogSanitizer.redactSecrets(String.valueOf(parameters)), 4000));
            try {
                String projectName = getString(parameters, "project"); //$NON-NLS-1$
                String objectFqn = getString(parameters, "object_fqn", "objectFqn"); //$NON-NLS-1$ //$NON-NLS-2$
                ModuleArtifactKind moduleKind = ModuleArtifactKind.fromString(
                        getString(parameters, "module_kind", "moduleType", "moduleKind")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                boolean createIfMissing = getBoolean(parameters, true, "create_if_missing", "createIfMissing"); //$NON-NLS-1$ //$NON-NLS-2$
                String initialContent = getString(parameters, "initial_content", "initialContent"); //$NON-NLS-1$ //$NON-NLS-2$
                String validationToken = getString(parameters, "validation_token", "validationToken"); //$NON-NLS-1$ //$NON-NLS-2$

                Map<String, Object> normalizedPayload = validationService.normalizeEnsureModuleArtifactPayload(
                        projectName,
                        objectFqn,
                        moduleKind.name(),
                        Boolean.valueOf(createIfMissing),
                        initialContent);
                Map<String, Object> validatedPayload = validationService.consumeToken(
                        validationToken,
                        ValidationOperation.ENSURE_MODULE_ARTIFACT,
                        projectName);
                if (!validatedPayload.equals(normalizedPayload)) {
                    LOG.warn("[%s] Input payload differs from validated payload, applying validated payload from token", opId); //$NON-NLS-1$
                }

                EnsureModuleArtifactRequest request = new EnsureModuleArtifactRequest(
                        projectName,
                        getString(validatedPayload, "object_fqn"), //$NON-NLS-1$
                        ModuleArtifactKind.fromString(getString(validatedPayload, "module_kind")), //$NON-NLS-1$
                        !Boolean.FALSE.equals(validatedPayload.get("create_if_missing")), //$NON-NLS-1$
                        getString(validatedPayload, "initial_content")); //$NON-NLS-1$
                ModuleArtifactResult result = metadataService.ensureModuleArtifact(request);
                LOG.info("[%s] SUCCESS in %s path=%s", opId, //$NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        result.modulePath());
                return ToolResult.success(result.formatForLlm());
            } catch (MetadataOperationException e) {
                LOG.warn("[%s] FAILED in %s: %s (%s)", opId, //$NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        e.getMessage(),
                        e.getCode());
                return ToolResult.failure("[" + e.getCode() + "] " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (Exception e) {
                LOG.error("[" + opId + "] ensure_module_artifact failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure("Ошибка ensure_module_artifact: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private String getString(Map<String, Object> parameters, String... keys) {
        for (String key : keys) {
            Object value = parameters.get(key);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private boolean getBoolean(Map<String, Object> parameters, boolean defaultValue, String... keys) {
        for (String key : keys) {
            Object value = parameters.get(key);
            if (value instanceof Boolean bool) {
                return bool.booleanValue();
            }
            if (value instanceof String stringValue) {
                return Boolean.parseBoolean(stringValue);
            }
            if (value != null) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
}
