/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.forms;

import java.util.Locale;

import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Execution mode for apply_form_recipe.
 */
public enum FormRecipeMode {
    CREATE,
    UPDATE,
    UPSERT;

    public static FormRecipeMode fromOptionalString(String value) {
        if (value == null || value.isBlank()) {
            return UPSERT;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "create", "new", "create_only" -> CREATE; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "update", "modify", "patch" -> UPDATE; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            case "upsert", "ensure", "apply" -> UPSERT; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            default -> throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_METADATA_CHANGE,
                    "Unsupported form recipe mode: " + value, false); //$NON-NLS-1$
        };
    }
}
