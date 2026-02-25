/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.forms.EdtFormService;
import com.codepilot1c.core.edt.forms.FormRecipeRequest;
import com.codepilot1c.core.edt.forms.FormRecipeResult;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Tool for applying declarative form recipes.
 */
public class ApplyFormRecipeTool implements ITool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(ApplyFormRecipeTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {
                  "type": "string",
                  "description": "Имя проекта EDT"
                },
                "mode": {
                  "type": "string",
                  "enum": ["create", "update", "upsert", "CREATE", "UPDATE", "UPSERT"],
                  "description": "Режим применения рецепта"
                },
                "form_fqn": {
                  "type": "string",
                  "description": "FQN формы (если известен)"
                },
                "owner_fqn": {
                  "type": "string",
                  "description": "FQN владельца (для создания формы)"
                },
                "name": {
                  "type": "string",
                  "description": "Имя формы (опционально, для создания/поиска)"
                },
                "usage": {
                  "type": "string",
                  "enum": ["OBJECT", "LIST", "CHOICE", "AUXILIARY", "object", "list", "choice", "auxiliary"],
                  "description": "Роль формы"
                },
                "managed": {
                  "type": "boolean",
                  "description": "Тип формы (MVP: только true)"
                },
                "set_as_default": {
                  "type": "boolean",
                  "description": "Назначить форму default для owner по usage"
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
                "attributes": {
                  "type": "array",
                  "items": {
                    "type": "object"
                  },
                  "description": "Реквизиты формы. Каждый элемент: {name|id, action=create|update|upsert|remove, type|field_type|fieldType, set, properties}. Для update/remove нужен name или id."
                },
                "layout": {
                  "type": "array",
                  "items": {
                    "type": "object"
                  },
                  "description": "Операции по макету формы (mutate_form_model)"
                },
                "validation_token": {
                  "type": "string",
                  "description": "Одноразовый токен из edt_validate_request"
                }
              },
              "required": ["project", "validation_token"]
            }
            """; //$NON-NLS-1$

    private final EdtFormService formService;
    private final MetadataRequestValidationService validationService;

    public ApplyFormRecipeTool() {
        this(new EdtFormService(), new MetadataRequestValidationService());
    }

    ApplyFormRecipeTool(EdtFormService formService, MetadataRequestValidationService validationService) {
        this.formService = formService;
        this.validationService = validationService;
    }

    @Override
    public String getName() {
        return "apply_form_recipe"; //$NON-NLS-1$
    }

    @Override
    public String getDescription() {
        return "Применяет декларативный рецепт управляемой формы: создание (при необходимости), реквизиты и макет."; //$NON-NLS-1$
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
            String opId = LogSanitizer.newId("form-recipe"); //$NON-NLS-1$
            long startedAt = System.currentTimeMillis();
            LOG.info("[%s] START apply_form_recipe", opId); //$NON-NLS-1$
            LOG.debug("[%s] Raw parameters: %s", opId, // $NON-NLS-1$
                    LogSanitizer.truncate(LogSanitizer.redactSecrets(String.valueOf(parameters)), 4000));
            try {
                String projectName = getString(parameters, "project"); //$NON-NLS-1$
                String mode = getOptionalString(parameters, "mode"); //$NON-NLS-1$
                String formFqn = getOptionalString(parameters, "form_fqn"); //$NON-NLS-1$
                String ownerFqn = getOptionalString(parameters, "owner_fqn"); //$NON-NLS-1$
                String name = getOptionalString(parameters, "name"); //$NON-NLS-1$
                String usage = getOptionalString(parameters, "usage"); //$NON-NLS-1$
                Boolean managed = getOptionalBoolean(parameters, "managed"); //$NON-NLS-1$
                Boolean setAsDefault = getOptionalBoolean(parameters, "set_as_default"); //$NON-NLS-1$
                String synonym = getOptionalString(parameters, "synonym"); //$NON-NLS-1$
                String comment = getOptionalString(parameters, "comment"); //$NON-NLS-1$
                Long waitMs = getOptionalLong(parameters, "wait_ms"); //$NON-NLS-1$
                List<Map<String, Object>> attributes = asListOfMaps(parameters.get("attributes")); //$NON-NLS-1$
                List<Map<String, Object>> layout = asListOfMaps(parameters.get("layout")); //$NON-NLS-1$
                String validationToken = getString(parameters, "validation_token"); //$NON-NLS-1$

                Map<String, Object> normalizedPayload = validationService.normalizeApplyFormRecipePayload(
                        projectName,
                        mode,
                        formFqn,
                        ownerFqn,
                        name,
                        usage,
                        managed,
                        setAsDefault,
                        synonym,
                        comment,
                        waitMs,
                        attributes,
                        layout);
                Map<String, Object> validatedPayload = validationService.consumeToken(
                        validationToken,
                        ValidationOperation.APPLY_FORM_RECIPE,
                        projectName);
                if (!validatedPayload.equals(normalizedPayload)) {
                    LOG.warn("[%s] Input payload differs from validated payload, applying validated payload", opId); //$NON-NLS-1$
                }

                FormRecipeRequest request = new FormRecipeRequest(
                        projectName,
                        asOptionalString(validatedPayload, "mode"), //$NON-NLS-1$
                        asOptionalString(validatedPayload, "form_fqn"), //$NON-NLS-1$
                        asOptionalString(validatedPayload, "owner_fqn"), //$NON-NLS-1$
                        asOptionalString(validatedPayload, "name"), //$NON-NLS-1$
                        asOptionalString(validatedPayload, "usage"), //$NON-NLS-1$
                        asOptionalBoolean(validatedPayload.get("managed")), //$NON-NLS-1$
                        asOptionalBoolean(validatedPayload.get("set_as_default")), //$NON-NLS-1$
                        asOptionalString(validatedPayload, "synonym"), //$NON-NLS-1$
                        asOptionalString(validatedPayload, "comment"), //$NON-NLS-1$
                        asOptionalLong(validatedPayload.get("wait_ms")), //$NON-NLS-1$
                        asListOfMaps(validatedPayload.get("attributes")), //$NON-NLS-1$
                        asListOfMaps(validatedPayload.get("layout"))); //$NON-NLS-1$

                FormRecipeResult result = formService.applyFormRecipe(request);
                LOG.info("[%s] SUCCESS in %s form=%s", opId, //$NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        result.formFqn());
                return ToolResult.success(result.formatForLlm());
            } catch (MetadataOperationException e) {
                LOG.warn("[%s] FAILED in %s: %s (%s)", opId, //$NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        e.getMessage(),
                        e.getCode());
                return ToolResult.failure("[" + e.getCode() + "] " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (Exception e) {
                LOG.error("[" + opId + "] apply_form_recipe failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure("Ошибка apply_form_recipe: " + e.getMessage()); //$NON-NLS-1$
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

    private String asOptionalString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        String text = value == null ? null : String.valueOf(value);
        return text == null || text.isBlank() ? null : text;
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
