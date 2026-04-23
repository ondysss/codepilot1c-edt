/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.metadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.metadata.EdtMetadataService;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.metadata.RenderTemplateRequest;
import com.codepilot1c.core.edt.metadata.RenderTemplateResult;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;

/**
 * Tool for rendering a print template (макет) layout from section-based JSON.
 * This is a full-layout replacement — model generates sections with data bindings,
 * renderer handles formatting and serialization to binary MOXCEL .mxl format.
 */
@ToolMeta(name = "render_template", category = "metadata", mutating = true,
        requiresValidationToken = true, tags = {"workspace", "edt"})
public class RenderTemplateTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(RenderTemplateTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": {
                  "type": "string",
                  "description": "Имя EDT проекта"
                },
                "template_fqn": {
                  "type": "string",
                  "description": "FQN макета: Document.X.Template.Y или Catalog.X.Template.Y"
                },
                "sections": {
                  "type": "array",
                  "minItems": 1,
                  "items": {
                    "type": "object",
                    "properties": {
                      "name": {
                        "type": "string",
                        "enum": ["Шапка", "ШапкаТаблицы", "СтрокаТаблицы", "Подвал", "Заголовок"],
                        "description": "Имя секции (именованная область)"
                      },
                      "style": {
                        "type": "string",
                        "enum": ["default", "title", "table-header", "table-row", "total-row", "signature"],
                        "description": "Стиль секции (по умолчанию: default)"
                      },
                      "rows": {
                        "type": "array",
                        "items": {
                          "type": "array",
                          "items": { "type": "string" }
                        },
                        "description": "Строки секции: [[cell1, cell2], ...]. Статический текст или [Привязка]"
                      }
                    },
                    "required": ["name", "rows"]
                  },
                  "description": "Секции макета: Шапка, ШапкаТаблицы, СтрокаТаблицы, Подвал"
                },
                "validation_token": {
                  "type": "string",
                  "description": "Одноразовый токен из edt_validate_request"
                }
              },
              "required": ["project", "template_fqn", "sections", "validation_token"]
            }
            """; //$NON-NLS-1$

    private final EdtMetadataService metadataService;
    private final MetadataRequestValidationService validationService;

    public RenderTemplateTool() {
        this(new EdtMetadataService(), new MetadataRequestValidationService());
    }

    RenderTemplateTool(EdtMetadataService metadataService, MetadataRequestValidationService validationService) {
        this.metadataService = metadataService;
        this.validationService = validationService;
    }

    @Override
    public String getDescription() {
        return "Генерирует макет печатной формы из секционного JSON. Полная замена содержимого .mxl файла."; //$NON-NLS-1$
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
            String opId = LogSanitizer.newId("render-tpl"); //$NON-NLS-1$
            long startedAt = System.currentTimeMillis();
            LOG.info("[%s] START render_template", opId); //$NON-NLS-1$
            try {
                String projectName = stringParam(parameters, "project"); //$NON-NLS-1$
                String templateFqn = stringParam(parameters, "template_fqn"); //$NON-NLS-1$
                List<Map<String, Object>> sections = asListOfMaps(parameters.get("sections")); //$NON-NLS-1$
                String validationToken = stringParam(parameters, "validation_token"); //$NON-NLS-1$

                Map<String, Object> normalizedPayload = validationService.normalizeRenderTemplatePayload(
                        projectName, templateFqn, sections);
                Map<String, Object> validatedPayload = validationService.consumeToken(
                        validationToken, ValidationOperation.RENDER_TEMPLATE, projectName);
                if (!validatedPayload.equals(normalizedPayload)) {
                    LOG.warn("[%s] Input payload differs from validated payload, applying validated payload from token", opId); //$NON-NLS-1$
                }

                RenderTemplateRequest request = new RenderTemplateRequest(
                        asRequiredString(validatedPayload, "project"), //$NON-NLS-1$
                        asRequiredString(validatedPayload, "template_fqn"), //$NON-NLS-1$
                        asListOfMaps(validatedPayload.get("sections"))); //$NON-NLS-1$
                RenderTemplateResult result = metadataService.renderTemplate(request);

                LOG.info("[%s] SUCCESS in %s template=%s rows=%d cols=%d", opId, //$NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        result.templateFqn(),
                        Integer.valueOf(result.totalRows()),
                        Integer.valueOf(result.totalColumns()));
                return ToolResult.success(result.formatForLlm());
            } catch (MetadataOperationException e) {
                LOG.warn("[%s] FAILED in %s: %s (%s)", opId, //$NON-NLS-1$
                        LogSanitizer.formatDuration(System.currentTimeMillis() - startedAt),
                        e.getMessage(), e.getCode());
                return ToolResult.failure("[" + e.getCode() + "] " + e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
            } catch (Exception e) {
                LOG.error("[" + opId + "] render_template failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure("Ошибка render_template: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private String stringParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String asRequiredString(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_NAME,
                    "Required field missing in validated payload: " + key, false); //$NON-NLS-1$
        }
        return String.valueOf(value);
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
