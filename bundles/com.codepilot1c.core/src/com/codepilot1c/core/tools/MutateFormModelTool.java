package com.codepilot1c.core.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.forms.EdtFormService;
import com.codepilot1c.core.edt.forms.UpdateFormModelRequest;
import com.codepilot1c.core.edt.forms.UpdateFormModelResult;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Tool for headless form model mutation via EDT BM API.
 */
public class MutateFormModelTool implements ITool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(MutateFormModelTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {
                  "type": "string",
                  "description": "Имя проекта EDT"
                },
                "form_fqn": {
                  "type": "string",
                  "description": "FQN формы, например Document.РасходнаяНакладная.Form.ФормаДокумента"
                },
                "operations": {
                  "type": "array",
                  "description": "Список операций mutate_form_model: set_form_props/add_group/add_field/set_item/remove_item/move_item"
                },
                "validation_token": {
                  "type": "string",
                  "description": "Одноразовый токен из edt_validate_request"
                }
              },
              "required": ["project", "form_fqn", "operations", "validation_token"]
            }
            """; //$NON-NLS-1$

    private final EdtFormService formService;
    private final MetadataRequestValidationService validationService;

    public MutateFormModelTool() {
        this(new EdtFormService(), new MetadataRequestValidationService());
    }

    MutateFormModelTool(EdtFormService formService, MetadataRequestValidationService validationService) {
        this.formService = formService;
        this.validationService = validationService;
    }

    @Override
    public String getName() {
        return "mutate_form_model"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Изменяет модель управляемой формы через EDT BM API без active editor."; //$NON-NLS-1$
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
            String opId = LogSanitizer.newId("mutate-form"); //$NON-NLS-1$
            long startedAt = System.currentTimeMillis();
            LOG.info("[%s] START mutate_form_model", opId); //$NON-NLS-1$
            LOG.debug("[%s] Raw parameters: %s", opId, // $NON-NLS-1$
                    LogSanitizer.truncate(LogSanitizer.redactSecrets(String.valueOf(parameters)), 4000));
            try {
                String projectName = stringParam(parameters, "project"); //$NON-NLS-1$
                String formFqn = stringParam(parameters, "form_fqn"); //$NON-NLS-1$
                List<Map<String, Object>> operations = asListOfMaps(parameters.get("operations")); //$NON-NLS-1$
                String validationToken = stringParam(parameters, "validation_token"); //$NON-NLS-1$

                Map<String, Object> normalizedPayload = validationService.normalizeUpdateFormModelPayload(
                        projectName,
                        formFqn,
                        operations);
                Map<String, Object> validatedPayload = validationService.consumeToken(
                        validationToken,
                        ValidationOperation.MUTATE_FORM_MODEL,
                        projectName);
                if (!validatedPayload.equals(normalizedPayload)) {
                    LOG.warn("[%s] Input payload differs from validated payload, applying validated payload from token", opId); //$NON-NLS-1$
                }

                UpdateFormModelRequest request = new UpdateFormModelRequest(
                        projectName,
                        asRequiredString(validatedPayload, "form_fqn"), //$NON-NLS-1$
                        asListOfMaps(validatedPayload.get("operations"))); //$NON-NLS-1$
                UpdateFormModelResult result = formService.updateFormModel(request);
                LOG.info("[%s] SUCCESS in %s form=%s operations=%d", opId, //$NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        result.formFqn(),
                        Integer.valueOf(result.operationsApplied()));
                return ToolResult.success(result.formatForLlm());
            } catch (MetadataOperationException e) {
                LOG.warn("[%s] FAILED in %s: %s (%s)", opId, //$NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        e.getMessage(),
                        e.getCode());
                return ToolResult.failure("[" + e.getCode() + "] " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (Exception e) {
                LOG.error("[" + opId + "] mutate_form_model failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure("Ошибка mutate_form_model: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private String stringParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String asRequiredString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asListOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }
}
