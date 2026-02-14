package com.codepilot1c.core.edt.lang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Scope members-at-position result.
 */
public class BslScopeMembersResult {

    private final String projectName;
    private final String filePath;
    private final int line;
    private final int column;
    private final List<String> resolvedTypes;
    private final int total;
    private final boolean hasMore;
    private final List<MemberItem> items;

    public BslScopeMembersResult(
            String projectName,
            String filePath,
            int line,
            int column,
            List<String> resolvedTypes,
            int total,
            boolean hasMore,
            List<MemberItem> items) {
        this.projectName = projectName;
        this.filePath = filePath;
        this.line = line;
        this.column = column;
        this.resolvedTypes = new ArrayList<>(resolvedTypes != null ? resolvedTypes : List.of());
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

    public int getLine() {
        return line;
    }

    public int getColumn() {
        return column;
    }

    public List<String> getResolvedTypes() {
        return Collections.unmodifiableList(resolvedTypes);
    }

    public int getTotal() {
        return total;
    }

    public boolean isHasMore() {
        return hasMore;
    }

    public List<MemberItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    /**
     * Scope member description.
     */
    public static class MemberItem {
        private final String kind;
        private final String name;
        private final String nameRu;
        private final String ownerType;
        private final String signature;
        private final List<String> returnTypes;
        private final String source;

        public MemberItem(
                String kind,
                String name,
                String nameRu,
                String ownerType,
                String signature,
                List<String> returnTypes,
                String source) {
            this.kind = kind;
            this.name = name;
            this.nameRu = nameRu;
            this.ownerType = ownerType;
            this.signature = signature;
            this.returnTypes = new ArrayList<>(returnTypes != null ? returnTypes : List.of());
            this.source = source;
        }

        public String getKind() {
            return kind;
        }

        public String getName() {
            return name;
        }

        public String getNameRu() {
            return nameRu;
        }

        public String getOwnerType() {
            return ownerType;
        }

        public String getSignature() {
            return signature;
        }

        public List<String> getReturnTypes() {
            return Collections.unmodifiableList(returnTypes);
        }

        public String getSource() {
            return source;
        }
    }
}
