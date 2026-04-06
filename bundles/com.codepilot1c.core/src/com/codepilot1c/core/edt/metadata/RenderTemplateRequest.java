/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.metadata;

import java.util.List;
import java.util.Map;

/**
 * Request for rendering a template (макет) layout from section-based JSON.
 * This is a full-layout replacement — not incremental mutation.
 */
public record RenderTemplateRequest(
        String projectName,
        String templateFqn,
        List<Map<String, Object>> sections
) {

    public void validate() {
        if (projectName == null || projectName.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_FOUND,
                    "projectName is required", false); //$NON-NLS-1$
        }
        if (templateFqn == null || templateFqn.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_NOT_FOUND,
                    "template_fqn is required", false); //$NON-NLS-1$
        }
        if (!templateFqn.contains(".Template.")) { //$NON-NLS-1$
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_NAME,
                    "template_fqn must contain '.Template.' segment, got: " + templateFqn, false); //$NON-NLS-1$
        }
        if (sections == null || sections.isEmpty()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_CHANGE,
                    "sections must not be empty", false); //$NON-NLS-1$
        }
        for (Map<String, Object> section : sections) {
            if (section == null || section.isEmpty()) {
                throw new MetadataOperationException(
                        MetadataOperationCode.INVALID_METADATA_CHANGE,
                        "section must be an object", false); //$NON-NLS-1$
            }
            Object name = section.get("name"); //$NON-NLS-1$
            if (name == null || String.valueOf(name).isBlank()) {
                throw new MetadataOperationException(
                        MetadataOperationCode.INVALID_METADATA_CHANGE,
                        "section must contain non-empty 'name' field", false); //$NON-NLS-1$
            }
            Object rows = section.get("rows"); //$NON-NLS-1$
            if (!(rows instanceof List<?> rowsList) || rowsList.isEmpty()) {
                throw new MetadataOperationException(
                        MetadataOperationCode.INVALID_METADATA_CHANGE,
                        "section '" + name + "' must contain non-empty 'rows' array", false); //$NON-NLS-1$ //$NON-NLS-2$
            }
        }
    }
}
