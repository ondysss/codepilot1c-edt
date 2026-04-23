package com.codepilot1c.core.edt.lang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Exported procedures/functions for a BSL module.
 */
public class BslModuleExportsResult {

    private final String projectName;
    private final String filePath;
    private final int total;
    private final boolean hasMore;
    private final List<BslMethodInfo> items;

    public BslModuleExportsResult(
            String projectName,
            String filePath,
            int total,
            boolean hasMore,
            List<BslMethodInfo> items) {
        this.projectName = projectName;
        this.filePath = filePath;
        this.total = total;
        this.hasMore = hasMore;
        this.items = new ArrayList<>(items != null ? items : List.of());
    }

    public String getProjectName() {
        return projectName;
    }

    public String getFilePath() {
        return filePath;
    }

    public int getTotal() {
        return total;
    }

    public boolean isHasMore() {
        return hasMore;
    }

    public List<BslMethodInfo> getItems() {
        return Collections.unmodifiableList(items);
    }
}
