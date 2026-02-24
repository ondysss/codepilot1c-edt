/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analyzes selected code to determine its type and suggest relevant actions.
 *
 * <p>This analyzer inspects the selected text and surrounding context to identify:
 * <ul>
 *   <li>Code type (method, query, form handler, etc.)</li>
 *   <li>Symbol name and metadata path</li>
 *   <li>Suggested AI actions based on context</li>
 * </ul>
 * </p>
 */
public class CodeContextAnalyzer {

    // Unicode identifier pattern - matches Cyrillic and Latin letters, digits, underscore
    private static final String ID = "[\\p{L}\\p{N}_]+"; //$NON-NLS-1$

    // BSL method patterns (Unicode-aware for Cyrillic method names)
    private static final Pattern METHOD_PATTERN = Pattern.compile(
            "(?iu)^\\s*(процедура|procedure|функция|function)\\s+(" + ID + ")\\s*\\(",
            Pattern.MULTILINE);

    private static final Pattern METHOD_END_PATTERN = Pattern.compile(
            "(?iu)^\\s*(конецпроцедуры|endprocedure|конецфункции|endfunction)",
            Pattern.MULTILINE);

    // SDBL Query patterns
    private static final Pattern QUERY_PATTERN = Pattern.compile(
            "(?iu)(ВЫБРАТЬ|SELECT|ВЫБРАТЬ\\s+РАЗЛИЧНЫЕ|SELECT\\s+DISTINCT)",
            Pattern.MULTILINE);

    private static final Pattern QUERY_CONSTRUCTOR_PATTERN = Pattern.compile(
            "(?iu)новый\\s+запрос|new\\s+query",
            Pattern.MULTILINE);

    // Form handler patterns
    private static final Pattern FORM_HANDLER_PATTERN = Pattern.compile(
            "(?iu)&(наклиенте|насервере|насерверебезконтекста|atclient|atserver|atservernocontext)",
            Pattern.MULTILINE);

    // Variable/parameter patterns (Unicode-aware)
    private static final Pattern VARIABLE_PATTERN = Pattern.compile(
            "(?iu)^\\s*(перем|var)\\s+(" + ID + ")",
            Pattern.MULTILINE);

    private static final Pattern PARAMETER_PATTERN = Pattern.compile(
            "(?iu)(знач|val)?\\s*(" + ID + ")\\s*=",
            Pattern.MULTILINE);

    // Comment patterns
    private static final Pattern COMMENT_PATTERN = Pattern.compile(
            "^\\s*//.*$",
            Pattern.MULTILINE);

    private static final Pattern MULTILINE_COMMENT_PATTERN = Pattern.compile(
            "(?s)/\\*.*?\\*/");

    // Region patterns (Unicode-aware)
    private static final Pattern REGION_PATTERN = Pattern.compile(
            "(?iu)#(область|region)\\s+(" + ID + ")",
            Pattern.MULTILINE);

    /**
     * Types of code that can be analyzed.
     */
    public enum CodeType {
        /** A procedure or function definition */
        METHOD,
        /** An SDBL query (embedded or in Query object) */
        QUERY,
        /** A form event handler (with &AtClient/&AtServer annotation) */
        FORM_HANDLER,
        /** A variable declaration */
        VARIABLE,
        /** A code comment */
        COMMENT,
        /** A region block */
        REGION,
        /** A complete module or large code block */
        MODULE,
        /** Selected text that couldn't be classified */
        CODE_BLOCK,
        /** No selection or empty */
        NONE
    }

    /**
     * Result of code context analysis.
     */
    public static class AnalysisResult {
        private final CodeType type;
        private final String symbolName;
        private final String metadataPath;
        private final List<SuggestedAction> suggestedActions;
        private final String selectedText;
        private final boolean hasQuery;
        private final boolean isExportMethod;

        private AnalysisResult(Builder builder) {
            this.type = builder.type;
            this.symbolName = builder.symbolName;
            this.metadataPath = builder.metadataPath;
            this.suggestedActions = Collections.unmodifiableList(builder.suggestedActions);
            this.selectedText = builder.selectedText;
            this.hasQuery = builder.hasQuery;
            this.isExportMethod = builder.isExportMethod;
        }

        public CodeType getType() {
            return type;
        }

        public String getSymbolName() {
            return symbolName;
        }

        public String getMetadataPath() {
            return metadataPath;
        }

        public List<SuggestedAction> getSuggestedActions() {
            return suggestedActions;
        }

        public String getSelectedText() {
            return selectedText;
        }

        public boolean hasQuery() {
            return hasQuery;
        }

        public boolean isExportMethod() {
            return isExportMethod;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private CodeType type = CodeType.NONE;
            private String symbolName = ""; //$NON-NLS-1$
            private String metadataPath = ""; //$NON-NLS-1$
            private List<SuggestedAction> suggestedActions = new ArrayList<>();
            private String selectedText = ""; //$NON-NLS-1$
            private boolean hasQuery = false;
            private boolean isExportMethod = false;

            public Builder type(CodeType type) {
                this.type = type;
                return this;
            }

            public Builder symbolName(String symbolName) {
                this.symbolName = symbolName;
                return this;
            }

            public Builder metadataPath(String metadataPath) {
                this.metadataPath = metadataPath;
                return this;
            }

            public Builder suggestedActions(List<SuggestedAction> actions) {
                this.suggestedActions = new ArrayList<>(actions);
                return this;
            }

            public Builder addSuggestedAction(SuggestedAction action) {
                this.suggestedActions.add(action);
                return this;
            }

            public Builder selectedText(String text) {
                this.selectedText = text;
                return this;
            }

            public Builder hasQuery(boolean hasQuery) {
                this.hasQuery = hasQuery;
                return this;
            }

            public Builder isExportMethod(boolean isExport) {
                this.isExportMethod = isExport;
                return this;
            }

            public AnalysisResult build() {
                return new AnalysisResult(this);
            }
        }
    }

    /**
     * Suggested action based on code context.
     */
    public static class SuggestedAction {
        private final String id;
        private final String label;
        private final String commandId;
        private final ActionCategory category;
        private final int priority;

        public SuggestedAction(String id, String label, String commandId, ActionCategory category, int priority) {
            this.id = id;
            this.label = label;
            this.commandId = commandId;
            this.category = category;
            this.priority = priority;
        }

        public String getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }

        public String getCommandId() {
            return commandId;
        }

        public ActionCategory getCategory() {
            return category;
        }

        public int getPriority() {
            return priority;
        }
    }

    /**
     * Categories for grouping actions in the menu.
     */
    public enum ActionCategory {
    /** Quick read-only actions (explain, optimize query) */
        QUICK_ACTION,
        /** Code modification actions (fix, optimize, refactor) */
        EDIT_ACTION,
        /** Actions that open the chat */
        CHAT_ACTION,
        /** Settings and configuration */
        SETTINGS
    }

    // Standard command IDs
    private static final String CMD_EXPLAIN = "com.codepilot1c.ui.commands.ExplainCode"; //$NON-NLS-1$
    private static final String CMD_FIX = "com.codepilot1c.ui.commands.FixCode"; //$NON-NLS-1$
    private static final String CMD_REVIEW = "com.codepilot1c.ui.commands.CriticiseCode"; //$NON-NLS-1$
    private static final String CMD_DOC = "com.codepilot1c.ui.commands.GenerateDocComments"; //$NON-NLS-1$
    private static final String CMD_ADD = "com.codepilot1c.ui.commands.AddCode"; //$NON-NLS-1$
    private static final String CMD_GENERATE = "com.codepilot1c.ui.commands.GenerateCode"; //$NON-NLS-1$
    private static final String CMD_CHAT = "com.codepilot1c.ui.commands.OpenChat"; //$NON-NLS-1$
    private static final String CMD_OPTIMIZE_QUERY = "com.codepilot1c.ui.commands.OptimizeQuery"; //$NON-NLS-1$

    /**
     * Analyzes the selected code and returns context information.
     *
     * @param selectedText the selected text from the editor
     * @param fullModuleText the full module text (for context analysis), may be null
     * @param metadataPath the metadata path of the current file, may be null
     * @return analysis result with type, symbol name, and suggested actions
     */
    public AnalysisResult analyze(String selectedText, String fullModuleText, String metadataPath) {
        if (selectedText == null || selectedText.trim().isEmpty()) {
            return AnalysisResult.builder()
                    .type(CodeType.NONE)
                    .suggestedActions(getDefaultActions())
                    .build();
        }

        String text = selectedText.trim();
        AnalysisResult.Builder builder = AnalysisResult.builder()
                .selectedText(text)
                .metadataPath(metadataPath != null ? metadataPath : ""); //$NON-NLS-1$

        // Check for queries first (can be inside methods)
        boolean hasQuery = containsQuery(text);
        builder.hasQuery(hasQuery);

        // Determine primary code type
        CodeType type = detectCodeType(text);
        builder.type(type);

        // Extract symbol name if applicable
        String symbolName = extractSymbolName(text, type);
        builder.symbolName(symbolName);

        // Check if export method
        if (type == CodeType.METHOD || type == CodeType.FORM_HANDLER) {
            builder.isExportMethod(text.toLowerCase().contains("экспорт") || //$NON-NLS-1$
                    text.toLowerCase().contains("export")); //$NON-NLS-1$
        }

        // Build suggested actions based on context
        List<SuggestedAction> actions = buildSuggestedActions(type, hasQuery, symbolName);
        builder.suggestedActions(actions);

        return builder.build();
    }

    /**
     * Detects the primary code type from selected text.
     */
    private CodeType detectCodeType(String text) {
        // Check for method definition
        if (METHOD_PATTERN.matcher(text).find()) {
            // Check if it's a form handler
            if (FORM_HANDLER_PATTERN.matcher(text).find()) {
                return CodeType.FORM_HANDLER;
            }
            return CodeType.METHOD;
        }

        // Check for standalone query (not in method)
        if (isStandaloneQuery(text)) {
            return CodeType.QUERY;
        }

        // Check for variable declaration
        if (VARIABLE_PATTERN.matcher(text).find()) {
            return CodeType.VARIABLE;
        }

        // Check for comment block
        if (isCommentOnly(text)) {
            return CodeType.COMMENT;
        }

        // Check for region
        if (REGION_PATTERN.matcher(text).find()) {
            return CodeType.REGION;
        }

        // Large selection - treat as module/block
        int lineCount = text.split("\n").length; //$NON-NLS-1$
        if (lineCount > 50) {
            return CodeType.MODULE;
        }

        return CodeType.CODE_BLOCK;
    }

    /**
     * Checks if the text contains an SDBL query.
     */
    private boolean containsQuery(String text) {
        return QUERY_PATTERN.matcher(text).find() ||
               QUERY_CONSTRUCTOR_PATTERN.matcher(text).find();
    }

    /**
     * Checks if the text is primarily a standalone query.
     */
    private boolean isStandaloneQuery(String text) {
        // If starts with SELECT or ВЫБРАТЬ (possibly with quotes)
        String trimmed = text.trim();
        if (trimmed.startsWith("\"") || trimmed.startsWith("'")) { //$NON-NLS-1$ //$NON-NLS-2$
            trimmed = trimmed.substring(1);
        }
        return trimmed.toUpperCase().startsWith("ВЫБРАТЬ") || //$NON-NLS-1$
               trimmed.toUpperCase().startsWith("SELECT"); //$NON-NLS-1$
    }

    /**
     * Checks if the text is only comments.
     */
    private boolean isCommentOnly(String text) {
        String withoutMultiline = MULTILINE_COMMENT_PATTERN.matcher(text).replaceAll(""); //$NON-NLS-1$
        String[] lines = withoutMultiline.split("\n"); //$NON-NLS-1$
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("//")) { //$NON-NLS-1$
                return false;
            }
        }
        return true;
    }

    /**
     * Extracts the symbol name from the code.
     */
    private String extractSymbolName(String text, CodeType type) {
        switch (type) {
            case METHOD:
            case FORM_HANDLER:
                Matcher methodMatcher = METHOD_PATTERN.matcher(text);
                if (methodMatcher.find()) {
                    return methodMatcher.group(2);
                }
                break;
            case VARIABLE:
                Matcher varMatcher = VARIABLE_PATTERN.matcher(text);
                if (varMatcher.find()) {
                    return varMatcher.group(2);
                }
                break;
            case REGION:
                Matcher regionMatcher = REGION_PATTERN.matcher(text);
                if (regionMatcher.find()) {
                    return regionMatcher.group(2);
                }
                break;
            default:
                break;
        }
        return ""; //$NON-NLS-1$
    }

    /**
     * Builds the list of suggested actions based on code context.
     */
    private List<SuggestedAction> buildSuggestedActions(CodeType type, boolean hasQuery, String symbolName) {
        List<SuggestedAction> actions = new ArrayList<>();

        // Quick Actions (always available)
        actions.add(new SuggestedAction(
                "explain", //$NON-NLS-1$
                getExplainLabel(type, symbolName),
                CMD_EXPLAIN,
                ActionCategory.QUICK_ACTION,
                10));

        // Query-specific actions
        if (hasQuery || type == CodeType.QUERY) {
            actions.add(new SuggestedAction(
                    "optimizeQuery", //$NON-NLS-1$
                    Messages.Action_OptimizeQuery,
                    CMD_OPTIMIZE_QUERY,
                    ActionCategory.QUICK_ACTION,
                    15));
        }

        // Edit Actions
        actions.add(new SuggestedAction(
                "review", //$NON-NLS-1$
                Messages.Action_Review,
                CMD_REVIEW,
                ActionCategory.EDIT_ACTION,
                30));

        actions.add(new SuggestedAction(
                "fix", //$NON-NLS-1$
                Messages.Action_Fix,
                CMD_FIX,
                ActionCategory.EDIT_ACTION,
                40));

        // Documentation for methods
        if (type == CodeType.METHOD || type == CodeType.FORM_HANDLER) {
            actions.add(new SuggestedAction(
                    "doc", //$NON-NLS-1$
                    Messages.Action_GenerateDoc,
                    CMD_DOC,
                    ActionCategory.EDIT_ACTION,
                    50));
        }

        // Add code / Generate
        actions.add(new SuggestedAction(
                "addCode", //$NON-NLS-1$
                Messages.Action_AddCode,
                CMD_ADD,
                ActionCategory.EDIT_ACTION,
                60));

        // Chat action
        actions.add(new SuggestedAction(
                "chat", //$NON-NLS-1$
                Messages.Action_OpenChat,
                CMD_CHAT,
                ActionCategory.CHAT_ACTION,
                100));

        return actions;
    }

    /**
     * Gets the explain action label based on context.
     */
    private String getExplainLabel(CodeType type, String symbolName) {
        switch (type) {
            case METHOD:
            case FORM_HANDLER:
                if (!symbolName.isEmpty()) {
                    return String.format(Messages.Action_ExplainMethod, symbolName);
                }
                return Messages.Action_ExplainCode;
            case QUERY:
                return Messages.Action_ExplainQuery;
            case VARIABLE:
                return Messages.Action_ExplainVariable;
            default:
                return Messages.Action_ExplainCode;
        }
    }

    /**
     * Returns default actions when there's no selection.
     */
    private List<SuggestedAction> getDefaultActions() {
        List<SuggestedAction> actions = new ArrayList<>();

        actions.add(new SuggestedAction(
                "generate", //$NON-NLS-1$
                Messages.Action_GenerateCode,
                CMD_GENERATE,
                ActionCategory.EDIT_ACTION,
                10));

        actions.add(new SuggestedAction(
                "chat", //$NON-NLS-1$
                Messages.Action_OpenChat,
                CMD_CHAT,
                ActionCategory.CHAT_ACTION,
                100));

        return actions;
    }
}
