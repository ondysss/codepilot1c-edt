package com.codepilot1c.core.edt.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Structured references result.
 */
public class ReferenceSearchResult {

    private final String objectFqn;
    private final String engine;
    private final List<ReferenceItem> references;

    public ReferenceSearchResult(String objectFqn, String engine, List<ReferenceItem> references) {
        this.objectFqn = objectFqn;
        this.engine = engine;
        this.references = new ArrayList<>(references != null ? references : List.of());
    }

    public String getObjectFqn() {
        return objectFqn;
    }

    public String getEngine() {
        return engine;
    }

    public List<ReferenceItem> getReferences() {
        return Collections.unmodifiableList(references);
    }

    public static class ReferenceItem {
        private final String category;
        private final String path;
        private final int line;
        private final String snippet;

        public ReferenceItem(String category, String path, int line, String snippet) {
            this.category = category;
            this.path = path;
            this.line = line;
            this.snippet = snippet;
        }

        public String getCategory() {
            return category;
        }

        public String getPath() {
            return path;
        }

        public int getLine() {
            return line;
        }

        public String getSnippet() {
            return snippet;
        }
    }
}
