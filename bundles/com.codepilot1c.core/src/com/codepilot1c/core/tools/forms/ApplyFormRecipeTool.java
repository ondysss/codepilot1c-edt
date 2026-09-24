/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.forms;
import com.codepilot1c.core.tools.ToolResult;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.AbstractTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;

import com.codepilot1c.core.edt.forms.EdtFormService;
import com.codepilot1c.core.edt.forms.FormRecipeRequest;
import com.codepilot1c.core.edt.forms.FormRecipeResult;
import com.codepilot1c.core.edt.forms.FormRecipePartialFailureException;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;

/**
 * Tool for applying declarative form recipes.
 */
@ToolMeta(name = "apply_form_recipe", category = "forms", mutating = true, requiresValidationToken = true, tags = {"workspace", "edt"})
public class ApplyFormRecipeTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(ApplyFormRecipeTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {
                  "type": "string",
                  "description": "Имя EDT проекта, where the target form or owner object exists."
                },
                "mode": {
                  "type": "string",
                  "description": "Recipe application mode. Use recipe flow when the change is a repeatable higher-level form composition."
                },
                "form_fqn": {
                  "type": "string",
                  "description": "Existing form FQN when the recipe targets a known form."
                },
                "owner_fqn": {
                  "type": "string",
                  "description": "Owner FQN when the recipe must create or locate a form under an existing metadata object."
                },
                "name": {
                  "type": "string",
                  "description": "Имя формы (опционально, для создания/поиска)"
                },
                "usage": {
                  "type": "string",
                  "enum": ["OBJECT", "RECORD", "LIST", "CHOICE", "AUXILIARY", "object", "record", "list", "choice", "auxiliary"],
                  "description": "Роль формы: OBJECT/RECORD/LIST/CHOICE/AUXILIARY. RECORD — форма записи регистра сведений (defaultRecordForm); OBJECT у регистра сведений означает то же."
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
                    "type": "object",
                    "properties": {
                      "action": {
                        "type": "string",
                        "enum": ["create", "update", "upsert", "remove", "add", "new", "set", "patch", "modify", "ensure", "apply", "merge", "delete", "drop"],
                        "description": "Form attribute action: create/update/upsert/remove; aliases accepted."
                      },
                      "name": {
                        "type": "string",
                        "description": "Form attribute name, not a visual item id. Required for create/upsert unless id is used."
                      },
                      "type": {
                        "description": "Data value type for the form attribute, e.g. String(150), Number(15,2), Date, Boolean, CatalogRef.Номенклатура, ValueTable/ТаблицаЗначений, ValueTree/ДеревоЗначений. For ValueTable/ValueTree provide a 'columns' array. Do not guess SpreadsheetDocument/ТабличныйДокумент; inspect candidates or use a type accepted by EDT for this form."
                      },
                      "columns": {
                        "type": "array",
                        "items": {
                          "type": "object",
                          "properties": {
                            "name": {"type": "string", "description": "Column identifier name."},
                            "type": {"description": "Column value type, e.g. String(50), Number(15,3), Date, CatalogRef.Номенклатура."}
                          },
                          "additionalProperties": true
                        },
                        "description": "Columns for a ValueTable/ValueTree form attribute. Each column is a named sub-attribute with its own type. Ignored for scalar attribute types."
                      },
                      "data_path": {
                        "type": "string",
                        "description": "Optional binding path used by visual fields; create/upsert this form attribute before layout add_field references it."
                      },
                      "set": {
                        "type": "object",
                        "description": "Properties for an existing or new form attribute. Use valueType/type for attribute data type, not visual widget type."
                      }
                    },
                    "additionalProperties": true
                  },
                  "description": "Form attributes are data-bearing form реквизиты, separate from visual items. Use create/update/upsert/remove. Before binding UI fields, inspect_form_layout if unsure, then create/upsert the attribute with explicit type and bind layout via data_path."
                },
                "layout": {
                  "type": "array",
                  "items": {
                    "type": "object"
                  },
                  "description": "Visual layout operations compatible with mutate_form_model. add_field creates a visual item only; data_path must point to an existing form attribute."
                },
                "validation_token": {
                  "type": "string",
                  "description": "Required unchanged token from edt_validate_request for this exact recipe payload."
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
    public String getDescription() {
        return "Создает/обновляет форму, form attributes and layout with validation token."; //$NON-NLS-1$
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
                LOG.info("[%s] SUCCESS in %s form=%s stubsWritten=%d stubsSkipped=%d", opId, //$NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        result.formFqn(),
                        Integer.valueOf(result.handlerStubsWritten().size()),
                        Integer.valueOf(result.handlerStubsSkippedExisting().size()));
                return ToolResult.success(result.formatForLlm(), toStructuredResult(result));
            } catch (FormRecipePartialFailureException e) {
                LOG.warn("[%s] PARTIAL in %s: %s (%s)", opId, //$NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        e.getMessage(),
                        e.getCode());
                return ToolResult.failure(
                        "[" + e.getCode() + "] " + e.getMessage(), //$NON-NLS-1$ //$NON-NLS-2$
                        toStructuredFailure(e));
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

    static JsonObject toStructuredResult(FormRecipeResult result) {
        return FormResultPayloads.formRecipeSuccess(result);
    }

    static JsonObject toStructuredFailure(FormRecipePartialFailureException failure) {
        return FormResultPayloads.partialFailure(failure);
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
