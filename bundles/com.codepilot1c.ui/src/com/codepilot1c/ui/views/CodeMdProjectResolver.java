/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.views;

import java.util.List;
import java.util.function.Function;

/** Fail-closed selection policy for the project owned by one ChatView. */
public final class CodeMdProjectResolver {

    private CodeMdProjectResolver() {
    }

    /**
     * Resolves an explicit project identity without fallback. Only an unbound
     * view may use the single-open-project convenience fallback.
     */
    public static <T> T resolve(
            String explicitProjectPath,
            Function<String, T> explicitResolver,
            List<T> openProjects) {
        if (explicitProjectPath != null && !explicitProjectPath.isBlank()) {
            return explicitResolver.apply(explicitProjectPath);
        }
        return openProjects != null && openProjects.size() == 1
                ? openProjects.get(0) : null;
    }
}
