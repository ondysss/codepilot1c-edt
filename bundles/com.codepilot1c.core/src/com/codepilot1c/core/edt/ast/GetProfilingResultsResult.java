/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.edt.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Structured profiling results.
 */
public class GetProfilingResultsResult {

    private final String projectName;
    private final List<ModuleProfilingData> modules;

    public GetProfilingResultsResult(String projectName, List<ModuleProfilingData> modules) {
        this.projectName = projectName;
        this.modules = new ArrayList<>(modules != null ? modules : List.of());
    }

    public String getProjectName() {
        return projectName;
    }

    public List<ModuleProfilingData> getModules() {
        return Collections.unmodifiableList(modules);
    }

    /**
     * Profiling data for a single module.
     */
    public static class ModuleProfilingData {
        private final String moduleName;
        private final String filePath;
        private final long totalCalls;
        private final long totalTimeMs;
        private final List<LineProfilingData> lines;

        public ModuleProfilingData(String moduleName, String filePath, long totalCalls,
                                     long totalTimeMs, List<LineProfilingData> lines) {
            this.moduleName = moduleName;
            this.filePath = filePath;
            this.totalCalls = totalCalls;
            this.totalTimeMs = totalTimeMs;
            this.lines = new ArrayList<>(lines != null ? lines : List.of());
        }

        public String getModuleName() {
            return moduleName;
        }

        public String getFilePath() {
            return filePath;
        }

        public long getTotalCalls() {
            return totalCalls;
        }

        public long getTotalTimeMs() {
            return totalTimeMs;
        }

        public List<LineProfilingData> getLines() {
            return Collections.unmodifiableList(lines);
        }
    }

    /**
     * Profiling data for a single line.
     */
    public static class LineProfilingData {
        private final int lineNumber;
        private final long callCount;
        private final long totalTimeMs;
        private final long avgTimeMs;
        private final boolean covered;

        public LineProfilingData(int lineNumber, long callCount, long totalTimeMs,
                                  long avgTimeMs, boolean covered) {
            this.lineNumber = lineNumber;
            this.callCount = callCount;
            this.totalTimeMs = totalTimeMs;
            this.avgTimeMs = avgTimeMs;
            this.covered = covered;
        }

        public int getLineNumber() {
            return lineNumber;
        }

        public long getCallCount() {
            return callCount;
        }

        public long getTotalTimeMs() {
            return totalTimeMs;
        }

        public long getAvgTimeMs() {
            return avgTimeMs;
        }

        public boolean isCovered() {
            return covered;
        }
    }
}
