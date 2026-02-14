package com.codepilot1c.core.edt.lang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Type-at-position result.
 */
public class BslTypeResult {

    private final String projectName;
    private final String filePath;
    private final int line;
    private final int column;
    private final int offset;
    private final String elementClass;
    private final List<TypeInfo> types;

    public BslTypeResult(
            String projectName,
            String filePath,
            int line,
            int column,
            int offset,
            String elementClass,
            List<TypeInfo> types) {
        this.projectName = projectName;
        this.filePath = filePath;
        this.line = line;
        this.column = column;
        this.offset = offset;
        this.elementClass = elementClass;
        this.types = new ArrayList<>(types != null ? types : List.of());
    }

    public String getProjectName() {
        return projectName;
    }

    public String getFilePath() {
        return filePath;
    }

    public int getLine() {
        return line;
    }

    public int getColumn() {
        return column;
    }

    public int getOffset() {
        return offset;
    }

    public String getElementClass() {
        return elementClass;
    }

    public List<TypeInfo> getTypes() {
        return Collections.unmodifiableList(types);
    }

    /**
     * Type information item.
     */
    public static class TypeInfo {
        private final String name;
        private final String nameRu;
        private final String compositeId;

        public TypeInfo(String name, String nameRu, String compositeId) {
            this.name = name;
            this.nameRu = nameRu;
            this.compositeId = compositeId;
        }

        public String getName() {
            return name;
        }

        public String getNameRu() {
            return nameRu;
        }

        public String getCompositeId() {
            return compositeId;
        }
    }
}
