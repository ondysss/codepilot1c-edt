/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.ui.views;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import com.codepilot1c.ui.chat.ChatMessage;
import com.codepilot1c.ui.chat.MessageContentParser;
import com.codepilot1c.ui.chat.MessageKind;
import com.codepilot1c.ui.chat.MessagePart;
import com.codepilot1c.ui.chat.MessagePart.CodeBlockPart;
import com.codepilot1c.ui.chat.MessagePart.TextPart;
import com.codepilot1c.ui.chat.MessagePart.TodoListPart;
import com.codepilot1c.ui.chat.MessagePart.ToolCallPart;
import com.codepilot1c.ui.chat.MessagePart.ToolResultPart;
import com.codepilot1c.ui.internal.VibeUiPlugin;
import com.codepilot1c.ui.theme.ThemeManager;
import com.codepilot1c.ui.theme.VibeTheme;

/**
 * Composite widget for displaying a chat message with interactive code blocks.
 *
 * <p>Parses Markdown in the message and renders:</p>
 * <ul>
 *   <li>Plain text with basic Markdown formatting (bold, italic)</li>
 *   <li>Code blocks as interactive {@link CodeBlockWidget}s with Copy/Apply buttons</li>
 *   <li>Tool calls as collapsible {@link ToolCallWidget}s</li>
 *   <li>Todo lists as interactive {@link TodoListWidget}s</li>
 * </ul>
 */
public class ChatMessageComposite extends Composite {

    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*(.+?)\\*\\*|__(.+?)__"); //$NON-NLS-1$
    private static final Pattern ITALIC_PATTERN = Pattern.compile("(?<![\\*_])\\*([^\\*]+)\\*(?![\\*_])|(?<![\\*_])_([^_]+)_(?![\\*_])"); //$NON-NLS-1$
    private static final Pattern INLINE_CODE_PATTERN = Pattern.compile("`([^`]+)`"); //$NON-NLS-1$
    private static final Pattern HEADER_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE); //$NON-NLS-1$
    // Table pattern: header row, separator row, data rows
    private static final Pattern TABLE_PATTERN = Pattern.compile(
            "(^\\|.+\\|\\s*\\n)(^\\|[-:|\\s]+\\|\\s*\\n)((?:^\\|.+\\|\\s*\\n?)+)", //$NON-NLS-1$
            Pattern.MULTILINE);
    private static final String MERMAID_RESOURCE = "web/mermaid.min.js"; //$NON-NLS-1$
    private static volatile String mermaidJs;

    private final String sender;
    private final String message;
    private final boolean isAssistant;
    private final MessageKind messageKind;

    private final List<CodeBlockWidget> codeBlockWidgets = new ArrayList<>();
    private final List<ToolCallWidget> toolCallWidgets = new ArrayList<>();
    private final Map<String, ToolCallWidget> toolCallWidgetMap = new HashMap<>();
    private final List<TodoListWidget> todoListWidgets = new ArrayList<>();

    private final MessageContentParser contentParser = new MessageContentParser();
    private final VibeTheme theme;

    /**
     * Creates a new chat message composite.
     *
     * @param parent the parent composite
     * @param sender the sender name (e.g., "AI", "You")
     * @param message the message content (can contain Markdown)
     * @param isAssistant true if this is an AI assistant message
     */
    public ChatMessageComposite(Composite parent, String sender, String message, boolean isAssistant) {
        super(parent, SWT.NONE);
        this.sender = sender;
        this.message = message != null ? message : ""; //$NON-NLS-1$
        this.isAssistant = isAssistant;
        this.messageKind = isAssistant ? MessageKind.ASSISTANT : MessageKind.USER;
        this.theme = ThemeManager.getInstance().getTheme();

        createContents();
    }

    /**
     * Creates a new chat message composite from a ChatMessage.
     *
     * @param parent the parent composite
     * @param chatMessage the chat message
     */
    public ChatMessageComposite(Composite parent, ChatMessage chatMessage) {
        super(parent, SWT.NONE);
        this.sender = chatMessage.getKind().getDisplayName();
        this.message = chatMessage.getRawContent() != null ? chatMessage.getRawContent() : ""; //$NON-NLS-1$
        this.isAssistant = chatMessage.getKind().isAssistantOutput();
        this.messageKind = chatMessage.getKind();
        this.theme = ThemeManager.getInstance().getTheme();

        createContentsFromChatMessage(chatMessage);
    }

    private void createContents() {
        // Set background based on message kind
        setBackground(getMessageBackground());

        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = theme.getMargin();
        layout.marginHeight = theme.getMargin();
        layout.verticalSpacing = theme.getMarginSmall();
        setLayout(layout);
        setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Sender label
        createSenderLabel();

        // Parse message and create content widgets
        parseAndRenderMessage();
    }

    private Color getMessageBackground() {
        if (isAssistant) {
            return theme.getAssistantMessageBackground();
        } else if ("Система".equals(sender) || "System".equals(sender)) { //$NON-NLS-1$ //$NON-NLS-2$
            return theme.getSystemMessageBackground();
        } else {
            return theme.getUserMessageBackground();
        }
    }

    private void createContentsFromChatMessage(ChatMessage chatMessage) {
        // Set background based on message kind
        setBackground(getMessageBackground());

        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = theme.getMargin();
        layout.marginHeight = theme.getMargin();
        layout.verticalSpacing = theme.getMarginSmall();
        setLayout(layout);
        setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Sender label
        createSenderLabel();

        // Check if message has pre-parsed parts
        List<MessagePart> parts = chatMessage.getParts();
        if (!parts.isEmpty()) {
            // Render pre-parsed parts
            for (MessagePart part : parts) {
                renderPart(part);
            }
        } else {
            // Parse and render the raw content
            parseAndRenderMessage();
        }
    }

    private void createSenderLabel() {
        Label senderLabel = new Label(this, SWT.NONE);
        senderLabel.setBackground(getBackground());
        senderLabel.setText(sender);
        senderLabel.setFont(theme.getFontBold());
        senderLabel.setForeground(isAssistant ? theme.getAccent() : theme.getText());
        senderLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    private void parseAndRenderMessage() {
        // Parse message into structured parts
        List<MessagePart> parts = contentParser.parse(message);

        if (parts.isEmpty() && !message.trim().isEmpty()) {
            // Fallback: render as plain text
            createStyledTextWidget(message);
        } else {
            // Render each part
            for (MessagePart part : parts) {
                renderPart(part);
            }
        }
    }

    /**
     * Renders a single message part.
     *
     * @param part the part to render
     */
    private void renderPart(MessagePart part) {
        if (part instanceof TextPart textPart) {
            createStyledTextWidget(textPart.content());
        } else if (part instanceof CodeBlockPart codeBlock) {
            createCodeBlockWidget(codeBlock);
        } else if (part instanceof ToolCallPart toolCall) {
            createToolCallWidget(toolCall);
        } else if (part instanceof ToolResultPart toolResult) {
            updateToolCallWithResult(toolResult);
        } else if (part instanceof TodoListPart todoList) {
            createTodoListWidget(todoList);
        }
    }

    private void createCodeBlockWidget(CodeBlockPart codeBlock) {
        if (isMermaidCode(codeBlock) && createMermaidWidget(codeBlock)) {
            return;
        }
        CodeBlockWidget codeWidget = new CodeBlockWidget(
                this,
                codeBlock.code(),
                codeBlock.language()
        );
        codeWidget.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        codeBlockWidgets.add(codeWidget);
    }

    private boolean isMermaidCode(CodeBlockPart codeBlock) {
        String language = codeBlock.language();
        return language != null && (language.equalsIgnoreCase("mermaid") //$NON-NLS-1$
                || language.equalsIgnoreCase("mmd")); //$NON-NLS-1$
    }

    private boolean createMermaidWidget(CodeBlockPart codeBlock) {
        Browser browser = createBrowser(this);
        if (browser == null) {
            return false;
        }
        String script = getMermaidJs();
        if (script == null || script.isBlank()) {
            return false;
        }

        String html = buildMermaidHtml(script, codeBlock.code(), theme.isDark(), getBackground(), theme.getText());
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, false);
        gd.heightHint = 360;
        browser.setLayoutData(gd);
        browser.setText(html);
        return true;
    }

    private Browser createBrowser(Composite parent) {
        try {
            return new Browser(parent, SWT.EDGE);
        } catch (SWTError e1) {
            try {
                return new Browser(parent, SWT.NONE);
            } catch (SWTError e2) {
                VibeUiPlugin.log(e2);
                return null;
            }
        }
    }

    private String getMermaidJs() {
        String cached = mermaidJs;
        if (cached != null) {
            return cached;
        }
        synchronized (ChatMessageComposite.class) {
            if (mermaidJs != null) {
                return mermaidJs;
            }
            try (InputStream in = ChatMessageComposite.class.getClassLoader()
                    .getResourceAsStream(MERMAID_RESOURCE)) {
                if (in == null) {
                    VibeUiPlugin.log("Mermaid ресурс не найден: " + MERMAID_RESOURCE); //$NON-NLS-1$
                    return null;
                }
                mermaidJs = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return mermaidJs;
            } catch (IOException e) {
                VibeUiPlugin.log(e);
                return null;
            }
        }
    }

    private String buildMermaidHtml(String script, String code, boolean darkTheme,
            Color background, Color textColor) {
        StringBuilder sb = new StringBuilder();
        String bg = toCssColor(background);
        String fg = toCssColor(textColor);
        String themeName = darkTheme ? "dark" : "default"; //$NON-NLS-1$ //$NON-NLS-2$

        sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">"); //$NON-NLS-1$
        sb.append("<style>"); //$NON-NLS-1$
        sb.append("body{margin:0;padding:0;background:").append(bg).append(";color:")
                .append(fg).append(";font-family:Arial, sans-serif;}"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append(".mermaid{width:100%;}"); //$NON-NLS-1$
        sb.append("</style></head><body>"); //$NON-NLS-1$
        sb.append("<div class=\"mermaid\">"); //$NON-NLS-1$
        sb.append(escapeHtml(code));
        sb.append("</div>"); //$NON-NLS-1$
        sb.append("<script>"); //$NON-NLS-1$
        sb.append(script);
        sb.append("</script>"); //$NON-NLS-1$
        sb.append("<script>"); //$NON-NLS-1$
        sb.append("try{mermaid.initialize({startOnLoad:true,securityLevel:'loose',theme:'")
                .append(themeName).append("'});}catch(e){console.error(e);}"); //$NON-NLS-1$ //$NON-NLS-2$
        sb.append("</script>"); //$NON-NLS-1$
        sb.append("</body></html>"); //$NON-NLS-1$
        return sb.toString();
    }

    private String toCssColor(Color color) {
        if (color == null) {
            return "#ffffff"; //$NON-NLS-1$
        }
        return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue()); //$NON-NLS-1$
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return ""; //$NON-NLS-1$
        }
        return value.replace("&", "&amp;") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("<", "&lt;") //$NON-NLS-1$ //$NON-NLS-2$
                .replace(">", "&gt;"); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private void createToolCallWidget(ToolCallPart toolCall) {
        ToolCallWidget toolWidget = new ToolCallWidget(this, toolCall);
        toolWidget.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        toolCallWidgets.add(toolWidget);
        toolCallWidgetMap.put(toolCall.toolCallId(), toolWidget);
    }

    private void updateToolCallWithResult(ToolResultPart result) {
        ToolCallWidget widget = toolCallWidgetMap.get(result.toolCallId());
        if (widget != null && !widget.isDisposed()) {
            widget.setResult(result);
        } else {
            // No matching widget - create a standalone result display
            createToolResultWidget(result);
        }
    }

    private void createToolResultWidget(ToolResultPart result) {
        // Create a simple result display if no matching tool call widget
        Composite resultComposite = new Composite(this, SWT.NONE);
        resultComposite.setBackground(theme.getToolResultBackground());
        resultComposite.setLayout(new GridLayout(1, false));
        resultComposite.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label label = new Label(resultComposite, SWT.NONE);
        label.setBackground(resultComposite.getBackground());
        label.setForeground(result.success() ? theme.getSuccess() : theme.getDanger());
        label.setFont(theme.getFontBold());
        label.setText(result.success() ? "\u2713 " + result.toolName() : "\u2717 " + result.toolName()); //$NON-NLS-1$ //$NON-NLS-2$

        if (result.content() != null && !result.content().isEmpty()) {
            StyledText text = new StyledText(resultComposite, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
            text.setBackground(resultComposite.getBackground());
            text.setForeground(theme.getText());
            text.setFont(theme.getFontMono());
            text.setText(result.content());
            text.setEditable(false);
            GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
            gd.heightHint = 180;
            text.setLayoutData(gd);
        }
    }

    private void createTodoListWidget(TodoListPart todoList) {
        TodoListWidget todoWidget = new TodoListWidget(this, todoList);
        todoWidget.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        todoListWidgets.add(todoWidget);
    }

    private void createStyledTextWidget(String text) {
        // Process Markdown formatting
        List<StyleRange> styles = new ArrayList<>();
        String processedText = processMarkdownFormatting(text, styles);

        StyledText styledText = new StyledText(this, SWT.WRAP | SWT.READ_ONLY);
        styledText.setText(processedText);
        styledText.setEditable(false);
        styledText.setBackground(getBackground());
        styledText.setForeground(theme.getText());
        styledText.setFont(theme.getFont());
        styledText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Apply styles
        for (StyleRange style : styles) {
            if (style.start >= 0 && style.start + style.length <= styledText.getCharCount()) {
                styledText.setStyleRange(style);
            }
        }
    }

    private String processMarkdownFormatting(String text, List<StyleRange> styles) {
        // Process tables first (before other formatting that might break table structure)
        text = processTables(text, styles);

        // Process headers
        text = processHeaders(text, styles);

        // Process bold
        text = processBold(text, styles);

        // Process italic
        text = processItalic(text, styles);

        // Process inline code
        text = processInlineCode(text, styles);

        return text;
    }

    private String processHeaders(String text, List<StyleRange> styles) {
        Matcher matcher = HEADER_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String headerText = matcher.group(2);
            int startPos = sb.length();

            matcher.appendReplacement(sb, Matcher.quoteReplacement(headerText));

            StyleRange style = new StyleRange();
            style.start = startPos;
            style.length = headerText.length();
            style.font = theme.getFontHeader();
            style.foreground = theme.getAccent();
            styles.add(style);
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    private String processBold(String text, List<StyleRange> styles) {
        Matcher matcher = BOLD_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String boldText = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            int startPos = sb.length();

            matcher.appendReplacement(sb, Matcher.quoteReplacement(boldText));

            StyleRange style = new StyleRange();
            style.start = startPos;
            style.length = boldText.length();
            style.font = theme.getFontBold();
            style.fontStyle = SWT.BOLD;
            styles.add(style);
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    private String processItalic(String text, List<StyleRange> styles) {
        Matcher matcher = ITALIC_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String italicText = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            int startPos = sb.length();

            matcher.appendReplacement(sb, Matcher.quoteReplacement(italicText));

            StyleRange style = new StyleRange();
            style.start = startPos;
            style.length = italicText.length();
            style.font = theme.getFontItalic();
            style.fontStyle = SWT.ITALIC;
            styles.add(style);
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    private String processInlineCode(String text, List<StyleRange> styles) {
        Matcher matcher = INLINE_CODE_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String codeText = matcher.group(1);
            int startPos = sb.length();

            matcher.appendReplacement(sb, Matcher.quoteReplacement(codeText));

            StyleRange style = new StyleRange();
            style.start = startPos;
            style.length = codeText.length();
            style.font = theme.getFontMono();
            style.foreground = theme.getCodeText();
            style.background = theme.getInlineCodeBackground();
            styles.add(style);
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    /**
     * Processes Markdown tables and converts them to ASCII art tables.
     */
    private String processTables(String text, List<StyleRange> styles) {
        Matcher matcher = TABLE_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String headerRow = matcher.group(1).trim();
            String dataRows = matcher.group(3);

            // Parse header cells
            String[] headers = parseTableRow(headerRow);
            if (headers.length == 0) {
                continue;
            }

            // Parse data rows
            String[] dataLines = dataRows.split("\\n"); //$NON-NLS-1$
            List<String[]> rows = new ArrayList<>();
            for (String line : dataLines) {
                line = line.trim();
                if (!line.isEmpty() && line.startsWith("|")) { //$NON-NLS-1$
                    String[] cells = parseTableRow(line);
                    if (cells.length > 0) {
                        rows.add(cells);
                    }
                }
            }

            // Calculate column widths
            int[] widths = new int[headers.length];
            for (int i = 0; i < headers.length; i++) {
                widths[i] = headers[i].length();
            }
            for (String[] row : rows) {
                for (int i = 0; i < Math.min(row.length, widths.length); i++) {
                    widths[i] = Math.max(widths[i], row[i].length());
                }
            }

            // Build ASCII table
            StringBuilder table = new StringBuilder();
            int startPos = sb.length();

            // Top border
            table.append(buildTableBorder(widths, '┌', '┬', '┐')).append("\n"); //$NON-NLS-1$

            // Header row
            table.append(buildTableRow(headers, widths)).append("\n"); //$NON-NLS-1$

            // Header separator
            table.append(buildTableBorder(widths, '├', '┼', '┤')).append("\n"); //$NON-NLS-1$

            // Data rows
            for (String[] row : rows) {
                table.append(buildTableRow(row, widths)).append("\n"); //$NON-NLS-1$
            }

            // Bottom border
            table.append(buildTableBorder(widths, '└', '┴', '┘')); //$NON-NLS-1$

            String tableText = table.toString();
            matcher.appendReplacement(sb, Matcher.quoteReplacement(tableText));

            // Add style for the table (monospace font)
            StyleRange style = new StyleRange();
            style.start = startPos;
            style.length = tableText.length();
            style.font = theme.getFontMono();
            style.foreground = theme.getAccent();
            styles.add(style);
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    /**
     * Parses a table row into cells.
     */
    private String[] parseTableRow(String row) {
        // Remove leading and trailing |
        row = row.trim();
        if (row.startsWith("|")) { //$NON-NLS-1$
            row = row.substring(1);
        }
        if (row.endsWith("|")) { //$NON-NLS-1$
            row = row.substring(0, row.length() - 1);
        }

        String[] cells = row.split("\\|"); //$NON-NLS-1$
        for (int i = 0; i < cells.length; i++) {
            cells[i] = cells[i].trim();
        }
        return cells;
    }

    /**
     * Builds a table border line.
     */
    private String buildTableBorder(int[] widths, char left, char middle, char right) {
        StringBuilder sb = new StringBuilder();
        sb.append(left);
        for (int i = 0; i < widths.length; i++) {
            for (int j = 0; j < widths[i] + 2; j++) {
                sb.append('─');
            }
            if (i < widths.length - 1) {
                sb.append(middle);
            }
        }
        sb.append(right);
        return sb.toString();
    }

    /**
     * Builds a table data row.
     */
    private String buildTableRow(String[] cells, int[] widths) {
        StringBuilder sb = new StringBuilder();
        sb.append('│');
        for (int i = 0; i < widths.length; i++) {
            sb.append(' ');
            String cell = i < cells.length ? cells[i] : ""; //$NON-NLS-1$
            sb.append(cell);
            // Pad with spaces
            for (int j = cell.length(); j < widths[i]; j++) {
                sb.append(' ');
            }
            sb.append(' ');
            sb.append('│');
        }
        return sb.toString();
    }

    // === Public API for updating message parts ===

    /**
     * Updates a tool call status.
     *
     * @param toolCallId the tool call ID
     * @param status the new status
     */
    public void updateToolCallStatus(String toolCallId, ToolCallPart.ToolCallStatus status) {
        ToolCallWidget widget = toolCallWidgetMap.get(toolCallId);
        if (widget != null && !widget.isDisposed()) {
            widget.setStatus(status);
        }
    }

    /**
     * Adds a tool result to this message.
     *
     * @param result the tool result part
     */
    public void addToolResult(ToolResultPart result) {
        updateToolCallWithResult(result);
    }

    /**
     * Returns the code block widgets.
     *
     * @return list of code block widgets
     */
    public List<CodeBlockWidget> getCodeBlockWidgets() {
        return new ArrayList<>(codeBlockWidgets);
    }

    /**
     * Returns the tool call widgets.
     *
     * @return list of tool call widgets
     */
    public List<ToolCallWidget> getToolCallWidgets() {
        return new ArrayList<>(toolCallWidgets);
    }

    /**
     * Returns the message kind.
     *
     * @return the message kind
     */
    public MessageKind getMessageKind() {
        return messageKind;
    }

    @Override
    public void dispose() {
        // Dispose code block widgets
        for (CodeBlockWidget widget : codeBlockWidgets) {
            if (!widget.isDisposed()) {
                widget.dispose();
            }
        }
        codeBlockWidgets.clear();

        // Dispose tool call widgets
        for (ToolCallWidget widget : toolCallWidgets) {
            if (!widget.isDisposed()) {
                widget.dispose();
            }
        }
        toolCallWidgets.clear();
        toolCallWidgetMap.clear();

        // Dispose todo list widgets
        for (TodoListWidget widget : todoListWidgets) {
            if (!widget.isDisposed()) {
                widget.dispose();
            }
        }
        todoListWidgets.clear();

        // All colors and fonts are managed by ThemeManager - no local disposal needed

        super.dispose();
    }
}
