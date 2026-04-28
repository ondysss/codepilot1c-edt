/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.ast;

/**
 * Result of start/stop profiling operation.
 */
public class StartProfilingResult {

    private final String projectName;
    private final boolean profilingEnabled;
    private final String status;

    public StartProfilingResult(String projectName, boolean profilingEnabled, String status) {
        this.projectName = projectName;
        this.profilingEnabled = profilingEnabled;
        this.status = status;
    }

    public String getProjectName() {
        return projectName;
    }

    public boolean isProfilingEnabled() {
        return profilingEnabled;
    }

    public String getStatus() {
        return status;
    }
}
