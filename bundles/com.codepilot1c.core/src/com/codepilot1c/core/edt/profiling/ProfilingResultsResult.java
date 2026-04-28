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
 * Aggregated EDT profiling results.
 */
public record ProfilingResultsResult(
        String opId,
        String status,
        int count,
        String moduleFilter,
        int minFrequency,
        int maxLinesPerModule,
        List<ProfilingRunResult> results,
        String message) {

    public ProfilingResultsResult {
        results = results == null ? List.of() : List.copyOf(results);
    }
}
