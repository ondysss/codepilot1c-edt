package com.codepilot1c.core.edt.lang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Structured module-level context for a BSL file.
 */
public class BslModuleContextResult {

    private final String projectName;
    private final String filePath;
    private final String moduleType;
    private final String ownerClass;
    private final String ownerName;
    private final String ownerUri;
    private final List<String> defaultPragmas;
    private final int totalMethods;
    private final int exportedMethods;
    private final int asyncMethods;
    private final int eventMethods;

    public BslModuleContextResult(
            String projectName,
            String filePath,
            String moduleType,
            String ownerClass,
            String ownerName,
            String ownerUri,
            List<String> defaultPragmas,
            int totalMethods,
            int exportedMethods,
            int asyncMethods,
            int eventMethods) {
        this.projectName = projectName;
        this.filePath = filePath;
        this.moduleType = moduleType;
        this.ownerClass = ownerClass;
        this.ownerName = ownerName;
        this.ownerUri = ownerUri;
        this.defaultPragmas = new ArrayList<>(defaultPragmas != null ? defaultPragmas : List.of());
        this.totalMethods = totalMethods;
        this.exportedMethods = exportedMethods;
        this.asyncMethods = asyncMethods;
        this.eventMethods = eventMethods;
    }

    public String getProjectName() {
        return projectName;
    }

    public String getFilePath() {
        return filePath;
    }

    public String getModuleType() {
        return moduleType;
    }

    public String getOwnerClass() {
        return ownerClass;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public String getOwnerUri() {
        return ownerUri;
    }

    public List<String> getDefaultPragmas() {
        return Collections.unmodifiableList(defaultPragmas);
    }

    public int getTotalMethods() {
        return totalMethods;
    }

    public int getExportedMethods() {
        return exportedMethods;
    }

    public int getAsyncMethods() {
        return asyncMethods;
    }

    public int getEventMethods() {
        return eventMethods;
    }
}
