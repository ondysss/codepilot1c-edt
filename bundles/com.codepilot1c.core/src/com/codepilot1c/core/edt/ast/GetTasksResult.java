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
 * Structured task markers result.
 */
public class GetTasksResult {

    private final String projectName;
    private final int total;
    private final boolean hasMore;
    private final List<MarkerData> markers;

    public GetTasksResult(String projectName, int total, boolean hasMore, List<MarkerData> tasks) {
        this.projectName = projectName;
        this.total = total;
        this.hasMore = hasMore;
        this.markers = new ArrayList<>(tasks != null ? tasks : List.of());
    }

    public String getProjectName() {
        return projectName;
    }

    public int getTotal() {
        return total;
    }

    public boolean isHasMore() {
        return hasMore;
    }

    public List<MarkerData> getMarkers() {
        return Collections.unmodifiableList(markers);
    }

    public List<MarkerData> getTasks() {
        return getMarkers();
    }
}
