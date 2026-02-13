package com.codepilot1c.core.edt.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Structured content assist result.
 */
public class ContentAssistResult {

    private final String engine;
    private final int total;
    private final boolean hasMore;
    private final List<Item> items;

    public ContentAssistResult(String engine, int total, boolean hasMore, List<Item> items) {
        this.engine = engine;
        this.total = total;
        this.hasMore = hasMore;
        this.items = new ArrayList<>(items != null ? items : List.of());
    }

    public String getEngine() {
        return engine;
    }

    public int getTotal() {
        return total;
    }

    public boolean isHasMore() {
        return hasMore;
    }

    public List<Item> getItems() {
        return Collections.unmodifiableList(items);
    }

    public static class Item {
        private final String label;
        private final String kind;
        private final String detail;

        public Item(String label, String kind, String detail) {
            this.label = label;
            this.kind = kind;
            this.detail = detail;
        }

        public String getLabel() {
            return label;
        }

        public String getKind() {
            return kind;
        }

        public String getDetail() {
            return detail;
        }
    }
}
