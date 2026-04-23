package com.codepilot1c.core.edt.lang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Structural analysis result for one BSL method.
 */
public class BslMethodAnalysisResult {

    private final String projectName;
    private final String filePath;
    private final String methodName;
    private final String kind;
    private final int startLine;
    private final int endLine;
    private final int loc;
    private final int cyclomatic;
    private final int branches;
    private final int loops;
    private final int tryExcepts;
    private final List<String> unusedParams;
    private final List<CallSite> serverCallsInLoops;
    private final List<MethodRef> callees;
    private final List<MethodRef> callers;
    private final List<WarningItem> warnings;

    public BslMethodAnalysisResult(
            String projectName,
            String filePath,
            String methodName,
            String kind,
            int startLine,
            int endLine,
            int loc,
            int cyclomatic,
            int branches,
            int loops,
            int tryExcepts,
            List<String> unusedParams,
            List<CallSite> serverCallsInLoops,
            List<MethodRef> callees,
            List<MethodRef> callers,
            List<WarningItem> warnings) {
        this.projectName = projectName;
        this.filePath = filePath;
        this.methodName = methodName;
        this.kind = kind;
        this.startLine = startLine;
        this.endLine = endLine;
        this.loc = loc;
        this.cyclomatic = cyclomatic;
        this.branches = branches;
        this.loops = loops;
        this.tryExcepts = tryExcepts;
        this.unusedParams = new ArrayList<>(unusedParams != null ? unusedParams : List.of());
        this.serverCallsInLoops = new ArrayList<>(serverCallsInLoops != null ? serverCallsInLoops : List.of());
        this.callees = new ArrayList<>(callees != null ? callees : List.of());
        this.callers = new ArrayList<>(callers != null ? callers : List.of());
        this.warnings = new ArrayList<>(warnings != null ? warnings : List.of());
    }

    public String getProjectName() {
        return projectName;
    }

    public String getFilePath() {
        return filePath;
    }

    public String getMethodName() {
        return methodName;
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

    public int getLoc() {
        return loc;
    }

    public int getCyclomatic() {
        return cyclomatic;
    }

    public int getBranches() {
        return branches;
    }

    public int getLoops() {
        return loops;
    }

    public int getTryExcepts() {
        return tryExcepts;
    }

    public List<String> getUnusedParams() {
        return Collections.unmodifiableList(unusedParams);
    }

    public List<CallSite> getServerCallsInLoops() {
        return Collections.unmodifiableList(serverCallsInLoops);
    }

    public List<MethodRef> getCallees() {
        return Collections.unmodifiableList(callees);
    }

    public List<MethodRef> getCallers() {
        return Collections.unmodifiableList(callers);
    }

    public List<WarningItem> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    public static class CallSite {
        private final String name;
        private final int line;

        public CallSite(String name, int line) {
            this.name = name;
            this.line = line;
        }

        public String getName() {
            return name;
        }

        public int getLine() {
            return line;
        }
    }

    public static class MethodRef {
        private final String name;
        private final String kind;
        private final int startLine;
        private final int endLine;

        public MethodRef(String name, String kind, int startLine, int endLine) {
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

    public static class WarningItem {
        private final String code;
        private final String message;
        private final int line;

        public WarningItem(String code, String message, int line) {
            this.code = code;
            this.message = message;
            this.line = line;
        }

        public String getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }

        public int getLine() {
            return line;
        }
    }
}
