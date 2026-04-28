/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.ast;

import java.util.Map;
import java.util.Set;

public record StepRequest(
        String projectName,
        String threadId,
        String kind) {

    private static final Set<String> ALLOWED_KINDS = Set.of("into", "over", "out"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    public static StepRequest fromParameters(Map<String, Object> parameters) {
        return new StepRequest(
                DebugRequestSupport.string(parameters, "projectName"), //$NON-NLS-1$
                DebugRequestSupport.string(parameters, "threadId"), //$NON-NLS-1$
                DebugRequestSupport.string(parameters, "kind")); //$NON-NLS-1$
    }

    public void validate() {
        DebugRequestSupport.require(projectName, "projectName"); //$NON-NLS-1$
        DebugRequestSupport.normalizeOneOf(kind, "kind", ALLOWED_KINDS); //$NON-NLS-1$
    }

    public String normalizedKind() {
        return DebugRequestSupport.normalizeOneOf(kind, "kind", ALLOWED_KINDS); //$NON-NLS-1$
    }
}
