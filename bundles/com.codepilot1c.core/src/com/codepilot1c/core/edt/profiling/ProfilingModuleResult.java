/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.profiling;

import java.util.List;

/**
 * Profiling lines grouped by BSL module.
 */
public record ProfilingModuleResult(
        String module,
        int returnedLines,
        int omittedLines,
        List<ProfilingLineResult> lines) {

    public ProfilingModuleResult {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
