/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.forms;

import java.util.List;
import java.util.Map;

import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Request for applying a declarative recipe to a managed form.
 */
public record FormRecipeRequest(
        String projectName,
        String mode,
        String formFqn,
        String ownerFqn,
        String name,
        String usage,
        Boolean managed,
        Boolean setAsDefault,
        String synonym,
        String comment,
        Long waitMs,
        List<Map<String, Object>> attributes,
        List<Map<String, Object>> layoutOperations
) {
    public void validate() {
        if (projectName == null || projectName.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_FOUND,
                    "projectName is required", false); //$NON-NLS-1$
        }
        if (managed != null && !managed.booleanValue()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_FORM_USAGE,
                    "Only managed forms are supported in MVP", false); //$NON-NLS-1$
        }
        boolean hasFormFqn = formFqn != null && !formFqn.isBlank();
        boolean hasOwner = ownerFqn != null && !ownerFqn.isBlank();
        if (!hasFormFqn && !hasOwner) {
            throw new MetadataOperationException(
                    MetadataOperationCode.METADATA_PARENT_NOT_FOUND,
                    "form_fqn or owner_fqn is required", false); //$NON-NLS-1$
        }
    }
}
