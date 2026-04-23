/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.metadata;

/**
 * Request for inspecting an existing template layout.
 */
public record InspectTemplateRequest(
        String projectName,
        String templateFqn
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
    }
}
