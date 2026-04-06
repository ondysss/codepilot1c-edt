/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.metadata;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.metadata.EdtMetadataService;
import com.codepilot1c.core.edt.metadata.InspectTemplateRequest;
import com.codepilot1c.core.edt.metadata.InspectTemplateResult;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;

/**
 * Tool for inspecting an existing template (макет) — read-only.
 * Returns a grid of cells with text/parameters, list of named areas, and dimensions.
 */
@ToolMeta(name = "inspect_template", category = "metadata", mutating = false,
        tags = {"read-only", "workspace", "edt"})
public class InspectTemplateTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(InspectTemplateTool.class);

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
                }
              },
              "required": ["project", "template_fqn"]
            }
            """; //$NON-NLS-1$

    private final EdtMetadataService metadataService;

    public InspectTemplateTool() {
        this(new EdtMetadataService());
    }

    InspectTemplateTool(EdtMetadataService metadataService) {
        this.metadataService = metadataService;
    }

    @Override
    public String getDescription() {
        return "Читает содержимое макета: ячейки, параметры, именованные области. Только чтение."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> parameters = params.getRaw();
            String opId = LogSanitizer.newId("inspect-tpl"); //$NON-NLS-1$
            long startedAt = System.currentTimeMillis();
            LOG.info("[%s] START inspect_template", opId); //$NON-NLS-1$
            try {
                String projectName = stringParam(parameters, "project"); //$NON-NLS-1$
                String templateFqn = stringParam(parameters, "template_fqn"); //$NON-NLS-1$

                InspectTemplateRequest request = new InspectTemplateRequest(projectName, templateFqn);
                InspectTemplateResult result = metadataService.inspectTemplate(request);

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
                LOG.error("[" + opId + "] inspect_template failed", e); //$NON-NLS-1$ //$NON-NLS-2$
                return ToolResult.failure("Ошибка inspect_template: " + e.getMessage()); //$NON-NLS-1$
            }
        });
    }

    private String stringParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
