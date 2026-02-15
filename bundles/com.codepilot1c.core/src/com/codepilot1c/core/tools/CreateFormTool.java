package com.codepilot1c.core.tools;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.forms.CreateFormRequest;
import com.codepilot1c.core.edt.forms.CreateFormResult;
import com.codepilot1c.core.edt.forms.EdtFormService;
import com.codepilot1c.core.edt.forms.FormUsage;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Tool for creating managed forms for existing metadata objects.
 */
public class CreateFormTool implements ITool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(CreateFormTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {
                  "type": "string",
                  "description": "Имя проекта EDT"
                },
                "owner_fqn": {
                  "type": "string",
                  "description": "FQN владельца, например Catalog.Номенклатура"
                },
                "name": {
                  "type": "string",
                  "description": "Имя формы"
                },
                "usage": {
                  "type": "string",
                  "enum": ["OBJECT", "LIST", "CHOICE", "AUXILIARY", "object", "list", "choice", "auxiliary"],
                  "description": "Роль формы: OBJECT/LIST/CHOICE/AUXILIARY"
                },
                "managed": {
                  "type": "boolean",
                  "description": "Тип формы (MVP: только true)"
                },
                "set_as_default": {
                  "type": "boolean",
                  "description": "Назначить форму как default для owner по usage"
                },
                "synonym": {
                  "type": "string",
                  "description": "Синоним формы"
                },
                "comment": {
                  "type": "string",
                  "description": "Комментарий формы"
                },
                "wait_ms": {
                  "type": "integer",
                  "description": "Таймаут ожидания материализации формы в owner .mdo"
                },
                "validation_token": {
                  "type": "string",
                  "description": "Одноразовый токен из edt_validate_request"
                }
              },
              "required": ["project", "owner_fqn", "name", "validation_token"]
            }
            """; //$NON-NLS-1$

    private final EdtFormService formService;
    private final MetadataRequestValidationService validationService;

    public CreateFormTool() {
        this(new EdtFormService(), new MetadataRequestValidationService());
    }

    CreateFormTool(EdtFormService formService, MetadataRequestValidationService validationService) {
        this.formService = formService;
        this.validationService = validationService;
    }

    @Override
    public String getName() {
        return "create_form"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Создает управляемую форму объекта EDT и при необходимости назначает ее как default."; //$NON-NLS-1$
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
            String opId = LogSanitizer.newId("create-form"); //$NON-NLS-1$
            long startedAt = System.currentTimeMillis();
            LOG.info("[%s] START create_form", opId); //$NON-NLS-1$
            LOG.debug("[%s] Raw parameters: %s", opId, // $NON-NLS-1$
                    LogSanitizer.truncate(LogSanitizer.redactSecrets(String.valueOf(parameters)), 4000));
            try {
                String projectName = getString(parameters, "project"); //$NON-NLS-1$
                String ownerFqn = getString(parameters, "owner_fqn"); //$NON-NLS-1$
                String name = getString(parameters, "name"); //$NON-NLS-1$
                String usageValue = getOptionalString(parameters, "usage"); //$NON-NLS-1$
                Boolean managed = getOptionalBoolean(parameters, "managed"); //$NON-NLS-1$
                Boolean setAsDefault = getOptionalBoolean(parameters, "set_as_default"); //$NON-NLS-1$
                String synonym = getOptionalString(parameters, "synonym"); //$NON-NLS-1$
                String comment = getOptionalString(parameters, "comment"); //$NON-NLS-1$
                Long waitMs = getOptionalLong(parameters, "wait_ms"); //$NON-NLS-1$
                String validationToken = getString(parameters, "validation_token"); //$NON-NLS-1$

                Map<String, Object> normalizedPayload = validationService.normalizeCreateFormPayload(
                        projectName,
                        ownerFqn,
                        name,
                        usageValue,
                        managed,
                        setAsDefault,
                        synonym,
                        comment,
                        waitMs);
                LOG.debug("[%s] Normalized payload: %s", opId, // $NON-NLS-1$
                        LogSanitizer.truncate(LogSanitizer.redactSecrets(String.valueOf(normalizedPayload)), 4000));

                Map<String, Object> validatedPayload = validationService.consumeToken(
                        validationToken,
                        ValidationOperation.CREATE_FORM,
                        projectName);
                LOG.debug("[%s] Validation token consumed successfully", opId); //$NON-NLS-1$

                FormUsage usage = FormUsage.fromOptionalString(asOptionalString(validatedPayload, "usage")); //$NON-NLS-1$
                CreateFormRequest request = new CreateFormRequest(
                        projectName,
                        asRequiredString(validatedPayload, "owner_fqn"), //$NON-NLS-1$
                        asRequiredString(validatedPayload, "name"), //$NON-NLS-1$
                        usage,
                        asOptionalBoolean(validatedPayload.get("managed")), //$NON-NLS-1$
                        asOptionalBoolean(validatedPayload.get("set_as_default")), //$NON-NLS-1$
                        asOptionalString(validatedPayload, "synonym"), //$NON-NLS-1$
                        asOptionalString(validatedPayload, "comment"), //$NON-NLS-1$
                        asOptionalLong(validatedPayload.get("wait_ms"))); //$NON-NLS-1$

                CreateFormResult result = formService.createForm(request);
                String createdName = extractNameFromFormFqn(result.formFqn());
                LOG.info("[%s] SUCCESS in %s fqn=%s", opId, //$NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        result.formFqn());
                return ToolResult.success(result.formatForLlm(projectName, createdName));
            } catch (MetadataOperationException e) {
                LOG.warn("[%s] FAILED in %s: %s (%s)", opId, //$NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        e.getMessage(),
                        e.getCode());
                return ToolResult.failure("[" + e.getCode() + "] " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (Exception e) {
                LOG.error("[" + opId + "] create_form failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure("Ошибка create_form: " + e.getMessage()); //$NON-NLS-1$
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

    private Boolean getOptionalBoolean(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        return asOptionalBoolean(value);
    }

    private Long getOptionalLong(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        return asOptionalLong(value);
    }

    private String asRequiredString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String asOptionalString(Map<String, Object> payload, String key) {
        String value = asRequiredString(payload, key);
        return value == null || value.isBlank() ? null : value;
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
        if (text.isEmpty()) {
            return null;
        }
        return Boolean.valueOf(Boolean.parseBoolean(text));
    }

    private Long asOptionalLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return Long.valueOf(number.longValue());
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        return Long.valueOf(text);
    }

    private String extractNameFromFormFqn(String formFqn) {
        if (formFqn == null || formFqn.isBlank()) {
            return ""; //$NON-NLS-1$
        }
        int pos = formFqn.lastIndexOf('.');
        if (pos >= 0 && pos + 1 < formFqn.length()) {
            return formFqn.substring(pos + 1);
        }
        return formFqn;
    }
}
