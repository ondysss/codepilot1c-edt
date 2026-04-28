/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.profiling;

/**
 * Per-line EDT profiling data.
 */
public record ProfilingLineResult(
        int line,
        long calls,
        double pct,
        double durability,
        double pureDurability,
        String code,
        String method) {
}
