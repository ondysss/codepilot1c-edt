package com.codepilot1c.core.edt.lang;

/**
 * Candidate method for ambiguous lookup.
 */
public class BslMethodCandidate {

    private final String name;
    private final String kind;
    private final int startLine;
    private final int endLine;

    public BslMethodCandidate(String name, String kind, int startLine, int endLine) {
        this.name = name;
        this.kind = kind;
        this.startLine = startLine;
        this.endLine = endLine;
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
}
