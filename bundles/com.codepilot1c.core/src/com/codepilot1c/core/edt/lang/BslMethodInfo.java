package com.codepilot1c.core.edt.lang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * BSL method outline entry.
 */
public class BslMethodInfo {

    private final String name;
    private final String kind;
    private final int startLine;
    private final int endLine;
    private final boolean export;
    private final boolean async;
    private final boolean event;
    private final List<BslMethodParamInfo> params;

    public BslMethodInfo(
            String name,
            String kind,
            int startLine,
            int endLine,
            boolean export,
            boolean async,
            boolean event,
            List<BslMethodParamInfo> params) {
        this.name = name;
        this.kind = kind;
        this.startLine = startLine;
        this.endLine = endLine;
        this.export = export;
        this.async = async;
        this.event = event;
        this.params = new ArrayList<>(params != null ? params : List.of());
    }

    public String getName() {
        return name;
    }

    public String getKind() {
        return kind;
    }

    public int getStartLine() {
        return startLine;
    }

    public int getEndLine() {
        return endLine;
    }

    public boolean isExport() {
        return export;
    }

    public boolean isAsync() {
        return async;
    }

    public boolean isEvent() {
        return event;
    }

    public List<BslMethodParamInfo> getParams() {
        return Collections.unmodifiableList(params);
    }
}
