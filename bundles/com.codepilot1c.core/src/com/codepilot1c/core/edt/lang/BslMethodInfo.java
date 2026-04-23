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
    private final boolean used;
    private final List<BslMethodParamInfo> params;
    private final List<String> pragmas;
    private final String documentation;

    public BslMethodInfo(
            String name,
            String kind,
            int startLine,
            int endLine,
            boolean export,
            boolean async,
            boolean event,
            boolean used,
            List<BslMethodParamInfo> params,
            List<String> pragmas,
            String documentation) {
        this.name = name;
        this.kind = kind;
        this.startLine = startLine;
        this.endLine = endLine;
        this.export = export;
        this.async = async;
        this.event = event;
        this.used = used;
        this.params = new ArrayList<>(params != null ? params : List.of());
        this.pragmas = new ArrayList<>(pragmas != null ? pragmas : List.of());
        this.documentation = documentation;
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

    public boolean isUsed() {
        return used;
    }

    public List<BslMethodParamInfo> getParams() {
        return Collections.unmodifiableList(params);
    }

    public List<String> getPragmas() {
        return Collections.unmodifiableList(pragmas);
    }

    public String getDocumentation() {
        return documentation;
    }
}
